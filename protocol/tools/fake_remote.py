#!/usr/bin/env python3
"""Fake Pairline remote ("fake iPhone", role R) for testing the Android bridge over the debug TCP link.

Speaks docs/PROTOCOL.md (wire version 0) as a TCP client: pairing, the session handshake, encrypted
records and the JSON messages. Independent of gen_vectors.py; needs Python 3.9+ and pyca/cryptography
only. Dev tool only; it is never shipped in an app.

Setup: run the bridge debug build (emulator or phone), then on the Mac: adb forward tcp:47800 tcp:47800

Usage:  fake_remote.py [OPTIONS] pair --pair-uri URI [ACTION ...]
        fake_remote.py [OPTIONS] session [ACTION ...]
        fake_remote.py --selftest

  pair      pair using the bridge's pairing link, pin its key, then do the session handshake
  session   session handshake with the bridge pinned by an earlier `pair`

After the handshake both sides exchange device.info, then the ACTIONs run in order on the same
connection. Options may appear anywhere on the line and are shared by all actions.

  status                          status.get
  ping                            ping; prints the round-trip time
  threads  [--limit N]            sms.threads, following `more` pages (up to --max-pages)
  history  [--thread T] [--limit N]
                                  sms.history (limit <= 100), following `more` pages
  send-sms --to NUMBER --body TEXT [--sim N] [--wait S]
                                  sms.send, then prints sms.state events for up to --wait seconds
  dial     --number NUMBER [--sim N]          call.dial (chain `listen` to follow call.state)
  answer | reject | end  --call ID           call.answer / call.reject / call.end (ids are strings)
  dtmf     --call ID --digit D               call.dtmf
  audio    --call ID [--mute true|false] [--speaker true|false]   call.audio
  contacts                        contacts.sync from scratch, following `cursor` pages
  calllog                         calllog.sync from scratch, following `cursor` pages
  contacts-add --name NAME --number NUMBER   contacts.add
  calllog-delete --keys cl:1,cl:2 | --all-calls   calllog.delete (deletes on the bridge phone!)
  blocks                          block.list: the bridge's block list
  block | unblock  --number NUMBER           block.set with add / remove; prints the new list
  ussd     --code CODE [--sim N]  ussd.run; waits up to max(--timeout, --wait) for ussd.result
  listen   [--seconds S] [--auto-answer] [--auto-end S]
                                  print incoming events; optionally answer every call.incoming
                                  and end calls S seconds after they become active
  unpair                          send unpair and forget the pinned bridge (must be the last action)
  all                             status, ping, threads, history, contacts, calllog, and send-sms
                                  if --to is given (never dials, runs USSD or unpairs)

Wi-Fi link (M5; connect to the bridge's Wi-Fi port 47820 instead of the debug port):
  media    [--packets N] [--media-port P]
                                  open the UDP media channel (probes), send N test packets and a replay;
                                  a debug bridge with media echo on sends them back (counts media.open,
                                  media.echo for --expect)
  send-file --file PATH           file.offer + BULK data + file.done (counts file.sent)
  receive-file [--seconds S] [--save-dir DIR]
                                  accept the bridge's next file.offer and save it (counts file.received)

  Emulator setup for the Wi-Fi link (its NAT lands on the emulated mobile interface, which only a debug
  build on an emulator accepts):
      adb emu redir add tcp:47820:47820 && adb emu redir add udp:47820:47820
      adb shell content query --uri content://app.pairline.android.debug.devtools/media-echo-on
      fake_remote.py --port 47820 session media --expect media.echo
      adb shell content query --uri 'content://app.pairline.android.debug.devtools/send-test-file?size=5000000'
      fake_remote.py --port 47820 session receive-file --save-dir /tmp --expect file.received

The bridge's pings are always answered with pong. --expect TYPE (repeatable) fails the run if no
message of that type arrived from the bridge. The last line is always PASS or FAIL.

Examples:

    python3 -I protocol/tools/fake_remote.py --selftest
    python3 -I protocol/tools/fake_remote.py pair --pair-uri 'pairline://pair?v=0&id=...&k=...&s=...'
    python3 -I protocol/tools/fake_remote.py session status ping threads listen --seconds 20
    python3 -I protocol/tools/fake_remote.py session history --thread 17 --limit 20
    python3 -I protocol/tools/fake_remote.py session send-sms --to +15551234567 --body 'hi' --wait 30
    python3 -I protocol/tools/fake_remote.py session dial --number +15551234567 listen --seconds 60 --auto-end 10
    python3 -I protocol/tools/fake_remote.py session listen --seconds 120 --auto-answer --auto-end 5
    python3 -I protocol/tools/fake_remote.py session all --expect status --expect sms.threads
    python3 -I protocol/tools/fake_remote.py session unpair

State (--state, default ~/.pairline-fake-remote.json, mode 0600) holds this remote's identity key and
the pinned bridge key and device id, so pair once and use `session` afterwards. The pairing secret and
private keys are never printed. Message bodies are printed: this is a local transcript.

Exit codes: 0 PASS; 1 an --expect'ed type never arrived (or a selftest mismatch); 2 usage error, not
paired, or pairing refused (PAIR_FAIL or a bad PAIR_OK MAC); 3 link or protocol error (connect failed,
timeout, connection closed, bad frame or record, replay); 130 interrupted.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import re
import socket
import struct
import sys
import time
import urllib.parse
from collections import Counter, deque
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any, Callable, NamedTuple

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

Msg = dict[str, Any]

# ---------------------------------------------------------------------------------------------
# Protocol constants (docs/PROTOCOL.md, wire version 0)

VERSION = 0
ROLE_R, ROLE_B = 0x52, 0x42  # 'R', 'B'
DIR_B_TO_R, DIR_R_TO_B = 1, 2
CH_CONTROL, CH_MEDIA, CH_BULK = 0, 1, 2
FRAME_PAIR_HELLO, FRAME_PAIR_OK, FRAME_PAIR_FAIL, FRAME_HELLO, FRAME_RECORD = 0x01, 0x02, 0x03, 0x10, 0x20
FRAME_BULK = 0x21  # M5: bulk-channel record, Wi-Fi only
FRAME_NAMES = {FRAME_PAIR_HELLO: "PAIR_HELLO", FRAME_PAIR_OK: "PAIR_OK", FRAME_PAIR_FAIL: "PAIR_FAIL",
               FRAME_HELLO: "HELLO", FRAME_RECORD: "RECORD", FRAME_BULK: "BULK"}
WIFI_PORT = 47820          # the bridge's Wi-Fi TCP (and usually UDP media) port
MEDIA_MAX_PLAINTEXT = 1200
FILE_CHUNK = 49152
FILE_MAX = 1 << 30
MAX_FRAME = 65535
HELLO_LEN = 99
RECORD_OVERHEAD = 8 + 16  # counter + GCM tag
MAX_PLAINTEXT = MAX_FRAME - 1 - RECORD_OVERHEAD
COUNTER_LIMIT = 2 ** 32
PAIR_LABEL = b"pairline-pair-v0"
SESSION_INFO = b"pairline v0 session"
DEFAULT_PORT = 47800
DEVICE_INFO: Msg = {"t": "device.info", "name": "Fake iPhone", "platform": "ios", "os": "18.0",
                    "app": "0.0.0-fake", "proto": 0, "caps": ["files"]}


class LinkError(Exception):
    """The link failed or the bridge broke the protocol (exit 3)."""


class BridgeUnpaired(LinkError):
    """The bridge sent unpair (exit 3: the requested actions couldn't run)."""


class PairingRefused(Exception):
    """PAIR_FAIL, or a PAIR_OK that doesn't verify (exit 2)."""


class UsageError(Exception):
    """Bad command line or state file (exit 2)."""


# ---------------------------------------------------------------------------------------------
# Encodings and primitives

_B64U = re.compile(r"[A-Za-z0-9_-]*")


def b64u(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def b64u_decode(s: Any) -> bytes:
    if not isinstance(s, str) or not _B64U.fullmatch(s) or len(s) % 4 == 1:
        raise ValueError("not base64url without padding")
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def fingerprint(public_key: bytes) -> str:
    """Short, non-secret label for a public key in the transcript."""
    return hashlib.sha256(public_key).hexdigest()[:16]


def private_key_from_scalar(scalar: bytes) -> ec.EllipticCurvePrivateKey:
    return ec.derive_private_key(int.from_bytes(scalar, "big"), ec.SECP256R1())


def scalar_of(key: ec.EllipticCurvePrivateKey) -> bytes:
    return key.private_numbers().private_value.to_bytes(32, "big")


def pub_bytes(key: ec.EllipticCurvePrivateKey | ec.EllipticCurvePublicKey) -> bytes:
    if isinstance(key, ec.EllipticCurvePrivateKey):
        key = key.public_key()
    return key.public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)


def load_public(b: bytes) -> ec.EllipticCurvePublicKey:
    """65-byte uncompressed X9.62 point on P-256 (raises ValueError otherwise)."""
    if len(b) != 65 or b[0] != 0x04:
        raise ValueError("not a 65-byte uncompressed P-256 point")
    return ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), b)


def ecdh(private: ec.EllipticCurvePrivateKey, public: ec.EllipticCurvePublicKey) -> bytes:
    return private.exchange(ec.ECDH(), public)


def hkdf(ikm: bytes, salt: bytes, info: bytes, length: int) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=length, salt=salt, info=info).derive(ikm)


# ---------------------------------------------------------------------------------------------
# Pairing

@dataclass(frozen=True)
class PairUri:
    device_id: bytes
    bridge_key: bytes
    secret: bytes = field(repr=False)  # the pairing secret `ps`: never printed


def parse_pair_uri(uri: str) -> PairUri:
    parts = urllib.parse.urlsplit(uri.strip())
    if parts.scheme != "pairline" or parts.netloc != "pair":
        raise ValueError("not a pairline://pair link")
    query = urllib.parse.parse_qs(parts.query, keep_blank_values=True)

    def one(name: str) -> str:
        values = query.get(name, [])
        if len(values) != 1:
            raise ValueError(f"the link needs exactly one '{name}' parameter")
        return values[0]

    if one("v") != str(VERSION):
        raise ValueError(f"pairing link version {one('v')!r} is not supported (this tool speaks v{VERSION})")
    device_id, key, secret = b64u_decode(one("id")), b64u_decode(one("k")), b64u_decode(one("s"))
    if len(device_id) != 8:
        raise ValueError("device id must be 8 bytes")
    load_public(key)
    if len(secret) != 32:
        raise ValueError("pairing secret must be 32 bytes")
    return PairUri(device_id, key, secret)


def pairing_transcript(pub_b: bytes, pub_r: bytes, nonce_r: bytes) -> bytes:
    return PAIR_LABEL + pub_b + pub_r + nonce_r


def pair_mac_r(ps: bytes, t: bytes) -> bytes:
    return hmac.new(ps, t, hashlib.sha256).digest()


def pair_mac_b(ps: bytes, t: bytes, nonce_b: bytes) -> bytes:
    return hmac.new(ps, t + nonce_b + b"B", hashlib.sha256).digest()


def build_pair_hello(ps: bytes, pub_b: bytes, pub_r: bytes, nonce_r: bytes) -> tuple[Msg, bytes]:
    """Returns the PAIR_HELLO JSON object and the transcript T."""
    t = pairing_transcript(pub_b, pub_r, nonce_r)
    return {"t": "pair_hello", "v": VERSION, "k": b64u(pub_r), "n": b64u(nonce_r), "mac": b64u(pair_mac_r(ps, t))}, t


def verify_pair_ok(ps: bytes, t: bytes, msg: Any) -> bytes:
    """Checks a PAIR_OK body in constant time; returns nonceB."""
    if not isinstance(msg, dict) or msg.get("t") != "pair_ok" or type(msg.get("v")) is not int or msg["v"] != VERSION:
        raise PairingRefused("PAIR_OK body is not {\"t\":\"pair_ok\",\"v\":0,...}")
    try:
        nonce_b, mac = b64u_decode(msg.get("n")), b64u_decode(msg.get("mac"))
    except ValueError as e:
        raise PairingRefused(f"PAIR_OK field is not base64url: {e}") from None
    if len(nonce_b) != 32 or len(mac) != 32:
        raise PairingRefused("PAIR_OK nonce and mac must be 32 bytes each")
    if not hmac.compare_digest(mac, pair_mac_b(ps, t, nonce_b)):
        raise PairingRefused("PAIR_OK MAC does not verify: wrong bridge or pairing secret")
    return nonce_b


# ---------------------------------------------------------------------------------------------
# Session handshake and records

class SessionKeys(NamedTuple):
    salt: bytes
    okm: bytes
    k_br: bytes
    k_rb: bytes


def hello_body(role: int, ephemeral_pub: bytes, rand: bytes) -> bytes:
    body = bytes([VERSION, role]) + ephemeral_pub + rand
    assert len(body) == HELLO_LEN
    return body


def parse_hello(body: bytes, role: int) -> ec.EllipticCurvePublicKey:
    """Validates a peer hello body; returns its ephemeral public key."""
    if len(body) != HELLO_LEN:
        raise LinkError(f"HELLO body is {len(body)} bytes, expected {HELLO_LEN}")
    if body[0] != VERSION:
        raise LinkError(f"bridge speaks protocol version {body[0]}, this tool speaks {VERSION}")
    if body[1] != role:
        raise LinkError(f"HELLO role byte is 0x{body[1]:02x}, expected 0x{role:02x}")
    try:
        return load_public(body[2:67])
    except ValueError:
        raise LinkError("HELLO ephemeral key is not a valid P-256 point") from None


def session_ikm_r(e_r: ec.EllipticCurvePrivateKey, s_r: ec.EllipticCurvePrivateKey,
                  e_b: ec.EllipticCurvePublicKey, s_b: ec.EllipticCurvePublicKey) -> bytes:
    """R's side of ikm = ECDH(eB,eR) ‖ ECDH(sB,eR) ‖ ECDH(eB,sR)."""
    return ecdh(e_r, e_b) + ecdh(e_r, s_b) + ecdh(s_r, e_b)


def derive_session(ikm: bytes, hello_r: bytes, hello_b: bytes) -> SessionKeys:
    salt = hashlib.sha256(hello_r + hello_b).digest()
    okm = hkdf(ikm, salt, SESSION_INFO, 64)
    return SessionKeys(salt, okm, okm[:32], okm[32:])


def channel_keys(okm: bytes, name: str) -> tuple[bytes, bytes]:
    """Wi-Fi channel keys ("media" or "bulk"): (B→R, R→B)."""
    k = hkdf(okm, b"", f"pairline v0 {name}".encode(), 64)
    return k[:32], k[32:]


def record_nonce(direction: int, counter: int) -> bytes:
    return struct.pack(">IQ", direction, counter)


def record_ad(channel: int) -> bytes:
    return bytes([VERSION, channel])


def seal(key: bytes, direction: int, counter: int, channel: int, plaintext: bytes) -> bytes:
    ct = AESGCM(key).encrypt(record_nonce(direction, counter), plaintext, record_ad(channel))
    return struct.pack(">Q", counter) + ct


def open_record(key: bytes, direction: int, channel: int, wire: bytes) -> tuple[int, bytes]:
    """Returns (counter, plaintext); raises InvalidTag if it doesn't authenticate."""
    if len(wire) < RECORD_OVERHEAD:
        raise LinkError(f"record is {len(wire)} bytes, shorter than counter + tag")
    counter = struct.unpack(">Q", wire[:8])[0]
    return counter, AESGCM(key).decrypt(record_nonce(direction, counter), wire[8:], record_ad(channel))


def encode_message(msg: Msg) -> bytes:
    return json.dumps(msg, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


# ---------------------------------------------------------------------------------------------
# Transports: TCP length prefix (used here) and BLE fragments (self-test only)

def tcp_encode(frame: bytes) -> bytes:
    return struct.pack(">I", len(frame)) + frame


def tcp_decode(buf: bytearray) -> bytes | None:
    """Removes and returns one complete frame from the front of buf, or None if it's incomplete."""
    if len(buf) < 4:
        return None
    n = struct.unpack(">I", buf[:4])[0]
    if n == 0 or n > MAX_FRAME:
        raise LinkError(f"TCP length prefix {n} is outside 1..{MAX_FRAME}")
    if len(buf) < 4 + n:
        return None
    frame = bytes(buf[4:4 + n])
    del buf[:4 + n]
    return frame


def ble_fragments(frame: bytes, att_payload: int) -> list[bytes]:
    """1-byte header (bit 0 first, bit 1 last); the first fragment adds the 2-byte total length."""
    out, pos, first = [], 0, True
    while True:
        room = att_payload - 1 - (2 if first else 0)
        chunk = frame[pos:pos + room]
        pos += len(chunk)
        last = pos >= len(frame)
        header = (1 if first else 0) | (2 if last else 0)
        out.append(bytes([header]) + (struct.pack(">H", len(frame)) if first else b"") + chunk)
        if last:
            return out
        first = False


def ble_reassemble(fragments: list[bytes]) -> bytes:
    if not fragments or not fragments[0] or not fragments[0][0] & 1 or len(fragments[0]) < 3:
        raise ValueError("first fragment missing its first flag or length")
    total = struct.unpack(">H", fragments[0][1:3])[0]
    data = bytearray(fragments[0][3:])
    for i, frag in enumerate(fragments):
        is_last = i == len(fragments) - 1
        if not frag or bool(frag[0] & 2) != is_last or (i > 0 and frag[0] & 1):
            raise ValueError(f"fragment {i} has wrong header bits")
        if i > 0:
            data += frag[1:]
    if len(data) != total:
        raise ValueError(f"reassembled {len(data)} bytes, header says {total}")
    return bytes(data)


# ---------------------------------------------------------------------------------------------
# Self-test against protocol/test-vectors/v0.json

class Checker:
    def __init__(self) -> None:
        self.passed = 0
        self.failed: list[str] = []

    @staticmethod
    def _show(v: Any) -> str:
        if isinstance(v, (bytes, bytearray)):
            return v.hex()
        if isinstance(v, tuple):
            return "(" + ", ".join(Checker._show(x) for x in v) + ")"
        return json.dumps(v, ensure_ascii=False)

    def eq(self, name: str, got: Any, want: Any) -> None:
        if got == want:
            self.passed += 1
            print(f"ok    {name}")
        else:
            self.failed.append(name)
            print(f"FAIL  {name}\n      want {self._show(want)}\n      got  {self._show(got)}")

    def fails(self, name: str, fn: Callable[[], Any]) -> None:
        try:
            fn()
        except (InvalidTag, ValueError, LinkError, PairingRefused):
            self.passed += 1
            print(f"ok    {name} (rejected)")
            return
        self.failed.append(name)
        print(f"FAIL  {name}: was accepted")


def selftest() -> int:
    path = Path(__file__).resolve().parent.parent / "test-vectors" / "v0.json"
    c = Checker()
    try:
        check_vectors(c, json.loads(path.read_text(encoding="utf-8")))
    except Exception as e:  # a malformed vector file is a failure, not a crash
        c.failed.append("aborted")
        print(f"FAIL  selftest aborted: {type(e).__name__}: {e}")
    if c.failed:
        print(f"selftest: {len(c.failed)} of {c.passed + len(c.failed)} checks FAILED ({path})")
        return 1
    print(f"selftest: all {c.passed} checks passed ({path.name})")
    return 0


def check_vectors(c: Checker, v: Msg) -> None:
    h = bytes.fromhex
    c.eq("vector file version", v["version"], VERSION)

    rfc = v["hkdf_rfc5869_case1"]
    c.eq("HKDF RFC 5869 case 1", hkdf(h(rfc["ikm"]), h(rfc["salt"]), h(rfc["info"]), rfc["length"]), h(rfc["okm"]))

    priv = {name: private_key_from_scalar(h(k["private"])) for name, k in v["keys"].items()}
    for name, k in v["keys"].items():
        c.eq(f"public key {name}", pub_bytes(priv[name]), h(k["public"]))
        c.eq(f"public key {name} parses", pub_bytes(load_public(h(k["public"]))), h(k["public"]))
    s_b, s_r, e_b, e_r = priv["sB"], priv["sR"], priv["eB"], priv["eR"]
    s_b_pub, e_b_pub = s_b.public_key(), e_b.public_key()
    e = v["ecdh"]
    c.eq("ECDH eB_eR (B side)", ecdh(e_b, e_r.public_key()), h(e["eB_eR"]))
    c.eq("ECDH eB_eR (R side)", ecdh(e_r, e_b_pub), h(e["eB_eR"]))
    c.eq("ECDH sB_eR (R side)", ecdh(e_r, s_b_pub), h(e["sB_eR"]))
    c.eq("ECDH eB_sR (R side)", ecdh(s_r, e_b_pub), h(e["eB_sR"]))

    p = v["pairing"]
    uri = parse_pair_uri(p["qr_uri"])
    ps, nonce_r, nonce_b = h(p["pairing_secret"]), h(p["nonce_r"]), h(p["nonce_b"])
    c.eq("QR link device id", uri.device_id, h(p["device_id"]))
    c.eq("QR link bridge key", uri.bridge_key, pub_bytes(s_b))
    c.eq("QR link pairing secret", uri.secret, ps)
    hello_json, t = build_pair_hello(ps, uri.bridge_key, pub_bytes(s_r), nonce_r)
    c.eq("pairing transcript T", t, h(p["transcript_t"]))
    c.eq("mac_r", pair_mac_r(ps, t), h(p["mac_r"]))
    c.eq("mac_b", pair_mac_b(ps, t, nonce_b), h(p["mac_b"]))
    c.eq("PAIR_HELLO JSON", hello_json, p["pair_hello_json"])
    c.eq("PAIR_OK JSON (as B builds it)",
         {"t": "pair_ok", "v": VERSION, "n": b64u(nonce_b), "mac": b64u(pair_mac_b(ps, t, nonce_b))}, p["pair_ok_json"])
    c.eq("PAIR_OK verifies", verify_pair_ok(ps, t, p["pair_ok_json"]), nonce_b)
    c.fails("PAIR_OK with a wrong MAC", lambda: verify_pair_ok(ps, t, {**p["pair_ok_json"], "mac": b64u(bytes(32))}))
    c.fails("PAIR_OK with v=false", lambda: verify_pair_ok(ps, t, {**p["pair_ok_json"], "v": False}))

    s = v["session"]
    hello_r = hello_body(ROLE_R, pub_bytes(e_r), h(s["rand_r"]))
    hello_b = hello_body(ROLE_B, pub_bytes(e_b), h(s["rand_b"]))
    c.eq("hello_r", hello_r, h(s["hello_r"]))
    c.eq("hello_b", hello_b, h(s["hello_b"]))
    c.eq("hello_r frame", bytes([FRAME_HELLO]) + hello_r, h(s["hello_r_frame"]))
    c.eq("hello_b parses", pub_bytes(parse_hello(h(s["hello_b"]), ROLE_B)), pub_bytes(e_b))
    c.fails("hello_r rejected as B's hello", lambda: parse_hello(h(s["hello_r"]), ROLE_B))
    ikm = session_ikm_r(e_r, s_r, e_b_pub, s_b_pub)
    keys = derive_session(ikm, hello_r, hello_b)
    c.eq("ikm (R side)", ikm, h(s["ikm"]))
    c.eq("salt", keys.salt, h(s["salt"]))
    c.eq("okm", keys.okm, h(s["okm"]))
    c.eq("k_br", keys.k_br, h(s["k_br"]))
    c.eq("k_rb", keys.k_rb, h(s["k_rb"]))
    c.eq("confirm R→B", seal(keys.k_rb, DIR_R_TO_B, 0, CH_CONTROL, b""), h(s["confirm_r_to_b"]))
    c.eq("confirm B→R", seal(keys.k_br, DIR_B_TO_R, 0, CH_CONTROL, b""), h(s["confirm_b_to_r"]))
    c.eq("confirm B→R opens", open_record(keys.k_br, DIR_B_TO_R, CH_CONTROL, h(s["confirm_b_to_r"])), (0, b""))
    media, bulk = channel_keys(keys.okm, "media"), channel_keys(keys.okm, "bulk")
    c.eq("media keys", media, (h(s["media_k_br"]), h(s["media_k_rb"])))
    c.eq("bulk keys", bulk, (h(s["bulk_k_br"]), h(s["bulk_k_rb"])))

    named = {"k_br": keys.k_br, "k_rb": keys.k_rb, "media_k_br": media[0], "media_k_rb": media[1],
             "bulk_k_br": bulk[0], "bulk_k_rb": bulk[1]}
    for i, r in enumerate(v["records"]):
        pt = r["plaintext_utf8"].encode() if "plaintext_utf8" in r else h(r["plaintext_hex"])
        key, label = named[r["key"]], f"record {i} ({r['key']}, counter {r['counter']})"
        c.eq(f"{label} nonce", record_nonce(r["direction"], r["counter"]), h(r["nonce"]))
        c.eq(f"{label} ad", record_ad(r["channel"]), h(r["ad"]))
        c.eq(f"{label} seal", seal(key, r["direction"], r["counter"], r["channel"], pt), h(r["wire"]))
        c.eq(f"{label} open", open_record(key, r["direction"], r["channel"], h(r["wire"])), (r["counter"], pt))
        if "plaintext_utf8" in r:
            c.eq(f"{label} JSON encoding", encode_message(json.loads(r["plaintext_utf8"])), pt)
    for m in v["must_fail"]:
        c.fails(f"must_fail: {m['why']}",
                lambda m=m: open_record(named[m["key"]], m["direction"], m["channel"], h(m["wire"])))

    f = v["framing"]
    frame = bytes([FRAME_RECORD]) + h(v["records"][0]["wire"])
    c.eq("frame", frame, h(f["frame"]))
    c.eq(f"BLE fragments (ATT payload {f['ble_att_payload']})",
         [x.hex() for x in ble_fragments(frame, f["ble_att_payload"])], f["ble_fragments"])
    c.eq(f"BLE single fragment (ATT payload {f['ble_single_fragment_att_payload']})",
         [x.hex() for x in ble_fragments(frame, f["ble_single_fragment_att_payload"])], f["ble_single_fragment"])
    c.eq("BLE reassembly", ble_reassemble([h(x) for x in f["ble_fragments"]]), frame)
    c.eq("TCP framing", tcp_encode(frame), h(f["tcp"]))

    # M5: media replay window, bulk file data, the Wi-Fi pair link.
    w, window = v["media_replay_window"], ReplayWindow(v["media_replay_window"]["size"])
    for i, step in enumerate(w["steps"]):
        wire = h(step["wire"])
        counter = struct.unpack(">Q", wire[:8])[0]
        accepted = None
        if window.may_accept(counter):
            try:
                accepted = open_record(named[w["key"]], w["direction"], w["channel"], wire)[1]
                window.mark(counter)
            except InvalidTag:
                pass
        c.eq(f"media window step {i} (counter {counter})", accepted is not None, step["accept"])
        if step["accept"]:
            c.eq(f"media window step {i} plaintext", accepted, h(step["plaintext_hex"]))
    b = v["bulk_file_data"]
    plain = b"\x01" + struct.pack(">IQ", b["fid"], b["offset"]) + b["data_utf8"].encode()
    c.eq("bulk file data plaintext", plain, h(b["plaintext"]))
    bulk_record = next(r for r in v["records"] if r["key"] == "bulk_k_br")
    c.eq("bulk frame", bytes([FRAME_BULK]) + h(bulk_record["wire"]), h(b["frame"]))
    c.eq("bulk TCP framing", tcp_encode(h(b["frame"])), h(b["tcp"]))
    p = v["pair_uri_wifi"]
    q = urllib.parse.parse_qs(urllib.parse.urlsplit(p["uri"]).query)
    c.eq("pair link ip", q["ip"][0].split(","), p["ip"])
    c.eq("pair link port", int(q["port"][0]), p["port"])
    c.eq("pair link ssid", b64u_decode(q["ssid"][0]).decode(), p["ssid"])
    c.eq("pair link pw", b64u_decode(q["pw"][0]).decode(), p["pw"])
    c.eq("pair link still parses", parse_pair_uri(p["uri"]).device_id, h(v["pairing"]["device_id"]))
    buf = bytearray(h(f["tcp"]))
    c.eq("TCP deframing", (tcp_decode(buf), bytes(buf)), (frame, b""))


# ---------------------------------------------------------------------------------------------
# Transcript

def abbreviate(msg: Msg, keep: int = 3) -> Msg:
    """Shortens long top-level lists (contacts, messages, ...) for the one-line transcript."""
    def cut(v: Any) -> Any:
        if not isinstance(v, list):
            return v
        n = keep if any(isinstance(x, (dict, list)) for x in v) else 20  # objects vs. short strings like caps
        return v[:n] + [f"… {len(v) - n} more"] if len(v) > n else v
    return {k: cut(v) for k, v in msg.items()}


class Transcript:
    def __init__(self, pretty: bool = False, full: bool = False) -> None:
        self.pretty, self.full = pretty, full

    def line(self, tag: str, text: str) -> None:
        print(f"{datetime.now().strftime('%H:%M:%S.%f')[:-3]} {tag} {text}", flush=True)

    def note(self, text: str) -> None:
        self.line("---", text)

    def warn(self, text: str) -> None:
        self.line("!!!", text)

    def msg(self, arrow: str, msg: Msg) -> None:
        shown = msg if self.full else abbreviate(msg)
        if self.pretty:
            self.line(arrow, json.dumps(shown, ensure_ascii=False, indent=2))
        else:
            self.line(arrow, json.dumps(shown, ensure_ascii=False, separators=(",", ":")))


# ---------------------------------------------------------------------------------------------
# State file

@dataclass
class State:
    path: Path
    identity: ec.EllipticCurvePrivateKey
    peer_id: bytes | None = None
    peer_key: bytes | None = None
    paired_at: int | None = None

    @classmethod
    def load(cls, path: Path, out: Transcript) -> State:
        if not path.exists():
            state = cls(path, ec.generate_private_key(ec.SECP256R1()))
            state.save()
            out.note(f"created a new identity key in {path}")
            return state
        try:
            if path.stat().st_mode & 0o077:
                out.warn(f"{path} is readable by other users; it should be mode 0600")
            data = json.loads(path.read_text(encoding="utf-8"))
            state = cls(path, private_key_from_scalar(bytes.fromhex(data["identity_private_key"])))
            peer = data.get("peer")
            if peer:
                state.peer_id, state.peer_key = bytes.fromhex(peer["device_id"]), bytes.fromhex(peer["public_key"])
                load_public(state.peer_key)
                state.paired_at = peer.get("paired_at")
            return state
        except (OSError, ValueError, KeyError, TypeError) as e:
            raise UsageError(f"state file {path} is unusable ({type(e).__name__}); delete it to start over "
                             "(you will need to pair again)") from None

    def save(self) -> None:
        data: Msg = {"format": 1, "identity_private_key": scalar_of(self.identity).hex(),
                     "identity_public_key": pub_bytes(self.identity).hex(), "peer": None}
        if self.peer_key is not None and self.peer_id is not None:
            data["peer"] = {"device_id": self.peer_id.hex(), "public_key": self.peer_key.hex(),
                            "paired_at": self.paired_at}
        tmp = self.path.with_name(self.path.name + ".tmp")
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            json.dump(data, fh, indent=2)
            fh.write("\n")
        os.chmod(tmp, 0o600)
        os.replace(tmp, self.path)

    def pin(self, device_id: bytes, key: bytes) -> None:
        self.peer_id, self.peer_key, self.paired_at = device_id, key, int(time.time() * 1000)
        self.save()

    def forget_peer(self) -> None:
        self.peer_id = self.peer_key = self.paired_at = None
        self.save()


# ---------------------------------------------------------------------------------------------
# Link: TCP frames

class Link:
    def __init__(self, host: str, port: int, timeout: float) -> None:
        try:
            self.sock = socket.create_connection((host, port), timeout=timeout)
        except OSError as e:
            raise LinkError(f"cannot connect to {host}:{port}: {e.strerror or e}. Is the bridge debug build "
                            f"running, and did you run `adb forward tcp:{port} tcp:{DEFAULT_PORT}`?") from None
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.buf = bytearray()

    def send_frame(self, ftype: int, body: bytes) -> None:
        frame = bytes([ftype]) + body
        if len(frame) > MAX_FRAME:
            raise LinkError(f"refusing to send a {len(frame)}-byte frame (max {MAX_FRAME})")
        try:
            self.sock.sendall(tcp_encode(frame))
        except OSError as e:
            raise LinkError(f"send failed: {e}") from None

    def recv_frame(self, timeout: float) -> tuple[int, bytes] | None:
        """Next (type, body), or None if nothing complete arrived within timeout seconds."""
        deadline = time.monotonic() + timeout
        while True:
            frame = tcp_decode(self.buf)
            if frame is not None:
                if frame[0] not in FRAME_NAMES:
                    raise LinkError(f"unknown frame type 0x{frame[0]:02x}; closing the connection")
                return frame[0], frame[1:]
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return None
            self.sock.settimeout(remaining)
            try:
                data = self.sock.recv(65536)
            except socket.timeout:
                return None
            except OSError as e:
                raise LinkError(f"connection error: {e}") from None
            if not data:
                raise LinkError("the bridge closed the connection" + (" mid-frame" if self.buf else ""))
            self.buf += data

    def expect_frame(self, ftypes: tuple[int, ...], timeout: float, what: str) -> tuple[int, bytes]:
        got = self.recv_frame(timeout)
        if got is None:
            raise LinkError(f"timed out after {timeout:g}s waiting for {what}")
        if got[0] not in ftypes:
            raise LinkError(f"expected {what}, got a {FRAME_NAMES[got[0]]} frame")
        return got

    def close(self) -> None:
        """Graceful close: half-close, drain briefly so unread data doesn't turn the FIN into a reset."""
        try:
            self.sock.shutdown(socket.SHUT_WR)
            self.sock.settimeout(1.0)
            end = time.monotonic() + 1.0
            while time.monotonic() < end and self.sock.recv(65536):
                pass
        except OSError:
            pass
        finally:
            self.sock.close()


# ---------------------------------------------------------------------------------------------
# Pairing and session (R side)

def pair(link: Link, out: Transcript, state: State, uri: PairUri, timeout: float) -> None:
    if state.peer_key is not None:
        out.note(f"already paired with bridge key {fingerprint(state.peer_key)}; a successful pairing replaces it")
    hello, t = build_pair_hello(uri.secret, uri.bridge_key, pub_bytes(state.identity), os.urandom(32))
    out.note(f"pairing with bridge id {uri.device_id.hex()} (key {fingerprint(uri.bridge_key)}); "
             "TCP has no `id` characteristic, so the device id isn't checked")
    link.send_frame(FRAME_PAIR_HELLO, encode_message(hello))
    out.msg("R→B", hello)
    ftype, body = link.expect_frame((FRAME_PAIR_OK, FRAME_PAIR_FAIL), timeout, "PAIR_OK or PAIR_FAIL")
    try:
        reply = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        raise PairingRefused(f"{FRAME_NAMES[ftype]} body is not UTF-8 JSON") from None
    if isinstance(reply, dict):
        out.msg("B→R", reply)
    if ftype == FRAME_PAIR_FAIL:
        reason = reply.get("reason") if isinstance(reply, dict) else None
        raise PairingRefused(f"the bridge refused pairing: {reason or 'no reason given'}")
    verify_pair_ok(uri.secret, t, reply)
    state.pin(uri.device_id, uri.bridge_key)
    out.note(f"PAIR_OK verified; pinned bridge key {fingerprint(uri.bridge_key)} in {state.path}")


class Session:
    def __init__(self, link: Link, out: Transcript, state: State, keys: SessionKeys, timeout: float) -> None:
        self.link, self.out, self.state, self.timeout = link, out, state, timeout
        self.k_rb, self.k_br = keys.k_rb, keys.k_br
        self.tx = 0  # next counter to send; 0 is the confirm
        self.rx_last = -1  # last accepted counter; nothing accepted yet
        # Wi-Fi sessions (M5): media and bulk keys from this session's okm; (B→R, R→B).
        self.media_keys = channel_keys(keys.okm, "media")
        self.bulk_keys = channel_keys(keys.okm, "bulk")
        self.bulk_tx = 0
        self.bulk_rx_last = -1
        self.bulk_handler: Callable[[bytes], None] | None = None
        self.bulk_received = 0
        self.next_id = 1
        self.backlog: deque[Msg] = deque(maxlen=1000)  # received but not yet consumed by a waiter
        self.received: Counter[str] = Counter()
        self.sent = 0
        self.error_replies = 0
        self.broken = False  # a send failed; don't try to read leftovers twice

    # Records

    def send_record(self, plaintext: bytes) -> None:
        if self.tx >= COUNTER_LIMIT:
            raise LinkError("send counter reached 2^32; reconnect for new keys")
        wire = seal(self.k_rb, DIR_R_TO_B, self.tx, CH_CONTROL, plaintext)
        self.tx += 1
        try:
            self.link.send_frame(FRAME_RECORD, wire)
        except LinkError:
            self.read_leftovers()
            raise

    def read_leftovers(self) -> None:
        """After a failed send, print what the bridge sent before it went away (an unpair, an error, ...)."""
        if self.broken:
            return
        self.broken = True
        try:
            while self.read_message(0.2) is not None:
                pass
        except BridgeUnpaired:
            raise
        except LinkError:
            pass

    def send_bulk(self, plaintext: bytes) -> None:
        """One BULK record (Wi-Fi only), sealed with the bulk R→B key on channel 2."""
        wire = seal(self.bulk_keys[1], DIR_R_TO_B, self.bulk_tx, CH_BULK, plaintext)
        self.bulk_tx += 1
        self.link.send_frame(FRAME_BULK, wire)

    def on_bulk_frame(self, body: bytes) -> None:
        if self.rx_last < 0:
            raise LinkError("BULK frame before the handshake")
        try:
            counter, plaintext = open_record(self.bulk_keys[0], DIR_B_TO_R, CH_BULK, body)
        except InvalidTag:
            raise LinkError("a BULK record from the bridge doesn't decrypt") from None
        if counter <= self.bulk_rx_last:
            raise LinkError(f"bulk replay check failed: counter {counter} <= {self.bulk_rx_last}")
        self.bulk_rx_last = counter
        self.bulk_received += 1
        if self.bulk_handler is not None:
            self.bulk_handler(plaintext)

    def recv_record(self, timeout: float) -> tuple[int, bytes] | None:
        deadline = time.monotonic() + timeout
        while True:
            got = self.link.recv_frame(max(0.0, deadline - time.monotonic()))
            if got is None:
                return None
            if got[0] != FRAME_BULK:
                break
            self.on_bulk_frame(got[1])
        ftype, body = got
        if ftype != FRAME_RECORD:
            raise LinkError(f"unexpected {FRAME_NAMES[ftype]} frame during the session")
        try:
            counter, plaintext = open_record(self.k_br, DIR_B_TO_R, CH_CONTROL, body)
        except InvalidTag:
            if self.rx_last < 0:
                raise LinkError("the bridge's confirm doesn't decrypt: it isn't the pinned bridge, or it pinned "
                                "another remote. Pair again.") from None
            raise LinkError("a record from the bridge doesn't decrypt (wrong key, direction or channel, "
                            "or tampered)") from None
        if counter <= self.rx_last:
            raise LinkError(f"replay check failed: counter {counter} <= last accepted {self.rx_last}; "
                            "dropping the connection")
        self.rx_last = counter
        return counter, plaintext

    def exchange_confirms(self) -> None:
        self.send_record(b"")
        self.out.line("R→B", "[confirm] counter 0")
        got = self.recv_record(self.timeout)
        if got is None:
            raise LinkError(f"timed out after {self.timeout:g}s waiting for the bridge's confirm")
        counter, plaintext = got
        if counter != 0 or plaintext:
            raise LinkError(f"the bridge's first record is not a confirm (counter {counter}, "
                            f"{len(plaintext)} bytes of plaintext)")
        self.out.line("B→R", "[confirm] counter 0")

    # Messages

    def send(self, msg: Msg) -> None:
        data = encode_message(msg)
        if len(data) > MAX_PLAINTEXT:
            raise UsageError(f"message is {len(data)} bytes; a record holds at most {MAX_PLAINTEXT}")
        self.send_record(data)
        self.sent += 1
        self.out.msg("R→B", msg)

    def request(self, t: str, /, **fields: Any) -> int:
        rid = self.next_id
        self.next_id += 1
        self.send({"t": t, "id": rid, **fields})
        return rid

    def read_message(self, timeout: float) -> Msg | None:
        got = self.recv_record(timeout)
        if got is None:
            return None
        counter, plaintext = got
        try:
            msg = json.loads(plaintext.decode("utf-8"))
        except (UnicodeDecodeError, ValueError) as e:
            raise LinkError(f"record {counter} is not UTF-8 JSON: {e}") from None
        if not isinstance(msg, dict) or not isinstance(msg.get("t"), str):
            raise LinkError(f'record {counter} is not a JSON object with a string "t"')
        self.out.msg("B→R", msg)
        self.received[msg["t"]] += 1
        if msg["t"] == "error":
            self.error_replies += 1
        elif msg["t"] == "ping" and "id" in msg:
            self.send({"t": "pong", "id": msg["id"]})
        elif msg["t"] == "unpair":
            self.state.forget_peer()
            raise BridgeUnpaired("the bridge sent unpair; the pinned bridge was removed from the state file")
        return msg

    def poll_for(self, pred: Callable[[Msg], bool], timeout: float) -> Msg | None:
        """First message matching pred (backlog first), or None after timeout seconds."""
        for m in self.backlog:
            if pred(m):
                self.backlog.remove(m)
                return m
        deadline = time.monotonic() + timeout
        while (remaining := deadline - time.monotonic()) > 0:
            m = self.read_message(remaining)
            if m is None:
                break
            if pred(m):
                return m
            if m["t"] != "ping":
                self.backlog.append(m)
        return None

    def wait_for(self, pred: Callable[[Msg], bool], what: str, timeout: float | None = None) -> Msg:
        timeout = self.timeout if timeout is None else timeout
        m = self.poll_for(pred, timeout)
        if m is None:
            raise LinkError(f"timed out after {timeout:g}s waiting for {what}")
        return m

    def call(self, t: str, /, timeout: float | None = None, **fields: Any) -> Msg:
        """Sends a request and returns the bridge's reply with the same id."""
        rid = self.request(t, **fields)
        return self.wait_for(lambda m: m.get("id") == rid and m["t"] != "ping", f"the reply to {t} (id {rid})",
                             timeout)


def start_session(link: Link, out: Transcript, state: State, timeout: float) -> Session:
    assert state.peer_key is not None
    s_b = load_public(state.peer_key)
    e_r = ec.generate_private_key(ec.SECP256R1())
    hello_r = hello_body(ROLE_R, pub_bytes(e_r), os.urandom(32))
    link.send_frame(FRAME_HELLO, hello_r)
    out.line("R→B", f"[HELLO] v{VERSION} role R, ephemeral key {fingerprint(pub_bytes(e_r))}")
    _, hello_b = link.expect_frame((FRAME_HELLO,), timeout, "the bridge's HELLO")
    e_b = parse_hello(hello_b, ROLE_B)
    out.line("B→R", f"[HELLO] v{VERSION} role B, ephemeral key {fingerprint(pub_bytes(e_b))}")
    keys = derive_session(session_ikm_r(e_r, state.identity, e_b, s_b), hello_r, hello_b)
    session = Session(link, out, state, keys, timeout)
    session.exchange_confirms()
    out.note(f"session established with bridge {state.peer_id.hex() if state.peer_id else '?'} "
             f"(key {fingerprint(state.peer_key)})")
    session.send(dict(DEVICE_INFO))
    deadline = time.monotonic() + timeout
    info = session.poll_for(lambda m: m["t"] == "device.info", timeout)
    if info is None:
        out.warn(f"the bridge sent no device.info within {timeout:g}s")
    else:
        out.note(f"bridge: {info.get('name')} ({info.get('platform')} {info.get('os')}), app {info.get('app')}, "
                 f"proto {info.get('proto')}")
        if info.get("proto") != VERSION:
            out.warn(f"bridge reports proto {info.get('proto')!r}, expected {VERSION}")
    status = session.poll_for(lambda m: m["t"] == "status" and "id" not in m, max(0.0, deadline - time.monotonic()))
    if status is None:
        out.warn(f"the bridge sent no status within {timeout:g}s of the handshake")
    else:
        out.note(describe_status(status))
    return session


# ---------------------------------------------------------------------------------------------
# Actions

def short(text: Any, n: int = 60) -> str:
    s = str(text).replace("\n", " ")
    return s if len(s) <= n else s[:n - 1] + "…"


def fmt_time(ms: Any) -> str:
    if not isinstance(ms, (int, float)) or isinstance(ms, bool):
        return "?"
    try:
        return datetime.fromtimestamp(ms / 1000).strftime("%Y-%m-%d %H:%M:%S")
    except (OverflowError, OSError, ValueError):
        return str(ms)


def describe_status(m: Msg) -> str:
    parts = []
    battery = m.get("battery")
    if isinstance(battery, dict):
        parts.append(f"battery {battery.get('level')}%" + (" charging" if battery.get("charging") else ""))
    for sim in m.get("sims") or []:
        if isinstance(sim, dict):
            parts.append(f"SIM {sim.get('slot')}: {sim.get('carrier')} {sim.get('net')} signal {sim.get('signal')}/4"
                         + (" roaming" if sim.get("roaming") else ""))
    if isinstance(m.get("caps"), list):
        parts.append("caps: " + (", ".join(map(str, m["caps"])) or "none"))
    return "status: " + ("; ".join(parts) or "(no fields)")


def check_reply(s: Session, reply: Msg, *want: str) -> bool:
    if reply["t"] in want:
        return True
    if reply["t"] == "error":
        s.out.note(f"error reply: {reply.get('code')}: {reply.get('msg')}")
    else:
        s.out.warn(f"expected {' or '.join(want)}, got {reply['t']}")
    return False


def fetch_pages(s: Session, a: argparse.Namespace, t: str, list_key: str, fields: Msg,
                advance: Callable[[Msg, list], Msg | None]) -> tuple[list, list[Msg]]:
    """Requests page after page while the bridge says more: true. Returns (all items, all replies)."""
    items: list = []
    replies: list[Msg] = []
    while True:
        reply = s.call(t, **fields)
        if not check_reply(s, reply, t):
            break
        replies.append(reply)
        page = reply.get(list_key) if isinstance(reply.get(list_key), list) else []
        items.extend(page)
        if reply.get("more") is not True:
            break
        if len(replies) >= a.max_pages:
            s.out.note(f"stopping after {a.max_pages} pages (--max-pages); the bridge has more")
            break
        nxt = advance(reply, page)
        if nxt is None or all(fields.get(k) == v for k, v in nxt.items()):
            s.out.warn(f"{t}: more is true but the page gives no new position to continue from; stopping")
            break
        fields = {**fields, **nxt}
    return items, replies


def before_oldest(_reply: Msg, page: list) -> Msg | None:
    times = [x["time"] for x in page
             if isinstance(x, dict) and isinstance(x.get("time"), int) and not isinstance(x.get("time"), bool)]
    return {"before": min(times)} if times else None


def since_cursor(reply: Msg, _page: list) -> Msg | None:
    cursor = reply.get("cursor")
    return {"since": cursor} if isinstance(cursor, str) and cursor else None


def show_samples(s: Session, items: list, fmt: Callable[[Msg], str], n: int = 3) -> None:
    for x in items[:n]:
        s.out.note("  " + (fmt(x) if isinstance(x, dict) else short(x)))


def act_status(s: Session, a: argparse.Namespace) -> None:
    reply = s.call("status.get")
    if check_reply(s, reply, "status"):
        s.out.note(describe_status(reply))


def act_ping(s: Session, a: argparse.Namespace) -> None:
    start = time.monotonic()
    reply = s.call("ping")
    if check_reply(s, reply, "pong"):
        s.out.note(f"pong after {(time.monotonic() - start) * 1000:.1f} ms")


def act_threads(s: Session, a: argparse.Namespace) -> None:
    threads, replies = fetch_pages(s, a, "sms.threads", "threads", {"limit": a.limit or 20, "before": None},
                                   before_oldest)
    s.out.note(f"sms.threads: {len(threads)} threads in {len(replies)} page(s)")
    show_samples(s, threads, lambda t: f"thread {t.get('thread')} {','.join(map(str, t.get('addrs') or []))} "
                                       f"{fmt_time(t.get('time'))} ({t.get('count')} msgs): {short(t.get('snippet'))}")


def act_history(s: Session, a: argparse.Namespace) -> None:
    limit = a.limit or 50
    if limit > 100:
        s.out.warn(f"sms.history limit is at most 100; using 100 instead of {limit}")
        limit = 100
    msgs, replies = fetch_pages(s, a, "sms.history", "msgs",
                                {"thread": a.thread, "before": None, "limit": limit}, before_oldest)
    s.out.note(f"sms.history: {len(msgs)} messages in {len(replies)} page(s)")
    show_samples(s, msgs, lambda m: f"{m.get('key')} {m.get('box')} {m.get('addr')} {fmt_time(m.get('time'))} "
                                    f"{m.get('state')}: {short(m.get('body'))}")


def act_send_sms(s: Session, a: argparse.Namespace) -> None:
    cid = f"fake-{int(time.time() * 1000)}"
    reply = s.call("sms.send", sim=a.sim, to=a.to, body=a.body, cid=cid)
    if not check_reply(s, reply, "ok"):
        return
    deadline = time.monotonic() + a.wait
    while True:
        m = s.poll_for(lambda m: m["t"] == "sms.state" and m.get("cid") == cid, deadline - time.monotonic())
        if m is None:
            s.out.note(f"no more sms.state for {cid} within {a.wait:g}s")
            return
        s.out.note(f"sms {cid}: {m.get('state')}" + (f", key {m['key']}" if m.get("key") else "")
                   + (f", error {m['error']}" if m.get("error") else ""))
        if m.get("state") in ("delivered", "failed"):
            return


def simple_request(t: str, *fields: str) -> Callable[[Session, argparse.Namespace], None]:
    """An action that sends one request built from command-line options and expects ok."""
    def run(s: Session, a: argparse.Namespace) -> None:
        check_reply(s, s.call(t, **{f: getattr(a, f) for f in fields}), "ok")
    return run


def act_dial(s: Session, a: argparse.Namespace) -> None:
    if check_reply(s, s.call("call.dial", sim=a.sim, number=a.number), "ok"):
        s.out.note("dialing; chain `listen` to follow call.state")


def act_audio(s: Session, a: argparse.Namespace) -> None:
    fields = {k: getattr(a, k) for k in ("mute", "speaker") if getattr(a, k) is not None}
    check_reply(s, s.call("call.audio", call=a.call, **fields), "ok")


def act_contacts(s: Session, a: argparse.Namespace) -> None:
    contacts, replies = fetch_pages(s, a, "contacts.sync", "contacts", {"since": a.since}, since_cursor)
    deleted = [k for r in replies for k in (r.get("deleted") or [])]
    cursor = replies[-1].get("cursor") if replies else None
    full = any(r.get("full") for r in replies)
    s.out.note(f"contacts.sync: {len(contacts)} contacts, {len(deleted)} deleted {deleted[:5]}, in {len(replies)} "
               f"page(s); full={full}; cursor {cursor!r}")
    show_samples(s, contacts, lambda c: f"{c.get('key')} {c.get('name')} " + ", ".join(
        str(n.get("n")) for n in c.get("numbers") or [] if isinstance(n, dict)))


def act_calllog(s: Session, a: argparse.Namespace) -> None:
    calls, replies = fetch_pages(s, a, "calllog.sync", "calls", {"since": a.since}, since_cursor)
    cursor = replies[-1].get("cursor") if replies else None
    s.out.note(f"calllog.sync: {len(calls)} calls in {len(replies)} page(s); cursor {cursor!r}")
    show_samples(s, calls, lambda c: f"{c.get('key')} {c.get('type')} {c.get('number')} {c.get('name') or ''} "
                                     f"{fmt_time(c.get('time'))} {c.get('dur')}s")


def act_contacts_add(s: Session, a: argparse.Namespace) -> None:
    check_reply(s, s.call("contacts.add", name=a.name, numbers=[{"n": a.number, "label": "mobile"}]), "ok")


def act_calllog_delete(s: Session, a: argparse.Namespace) -> None:
    if a.all_calls:
        check_reply(s, s.call("calllog.delete", all=True), "ok")
    else:
        keys = [k.strip() for k in (a.keys or "").split(",") if k.strip()]
        check_reply(s, s.call("calllog.delete", keys=keys), "ok")


def show_block_list(s: Session, reply: Msg) -> None:
    if check_reply(s, reply, "block.list"):
        numbers = reply.get("numbers") or []
        s.out.note(f"block.list: {len(numbers)} entries: "
                   + ", ".join(str(n.get("n")) for n in numbers if isinstance(n, dict)))


def act_blocks(s: Session, a: argparse.Namespace) -> None:
    show_block_list(s, s.call("block.list"))


def act_block(s: Session, a: argparse.Namespace) -> None:
    show_block_list(s, s.call("block.set", add=[a.number], remove=[]))


def act_unblock(s: Session, a: argparse.Namespace) -> None:
    show_block_list(s, s.call("block.set", add=[], remove=[a.number]))


def act_ussd(s: Session, a: argparse.Namespace) -> None:
    timeout = max(a.timeout, a.wait)
    rid = s.request("ussd.run", sim=a.sim, code=a.code)
    reply = s.wait_for(lambda m: m.get("id") == rid and m["t"] != "ping", f"the reply to ussd.run (id {rid})", timeout)
    if reply["t"] == "ok":  # tolerate an ok before the result
        reply = s.wait_for(lambda m: m.get("id") == rid and m["t"] == "ussd.result", f"ussd.result (id {rid})", timeout)
    if check_reply(s, reply, "ussd.result"):
        s.out.note(f"ussd: ok={reply.get('ok')} text={reply.get('text')!r}"
                   + (f" error={reply.get('error')!r}" if reply.get("error") else ""))


def act_listen(s: Session, a: argparse.Namespace) -> None:
    extras = (" with auto-answer" if a.auto_answer else "") + (f", ending calls after {a.auto_end:g}s"
                                                               if a.auto_end is not None else "")
    s.out.note(f"listening for {a.seconds:g}s{extras}")
    active: dict[Any, float] = {}  # call id -> when it became active
    ended: set[Any] = set()

    def handle(m: Msg) -> None:
        call = m.get("call")
        if call is None:
            return
        if m["t"] == "call.incoming" and a.auto_answer:
            s.request("call.answer", call=call)
        elif m["t"] == "call.state" and m.get("state") == "active":
            active.setdefault(call, time.monotonic())
        elif m["t"] == "call.state" and m.get("state") == "ended":
            active.pop(call, None)

    pending = list(s.backlog)
    s.backlog.clear()
    for m in pending:
        handle(m)
    deadline = time.monotonic() + a.seconds
    while True:
        now, wake = time.monotonic(), deadline
        if a.auto_end is not None:
            for call, since in active.items():
                if call in ended:
                    continue
                if now - since >= a.auto_end:
                    s.request("call.end", call=call)
                    ended.add(call)
                else:
                    wake = min(wake, since + a.auto_end)
        if now >= deadline:
            return
        m = s.read_message(max(0.0, wake - now))
        if m is not None:
            handle(m)


def act_unpair(s: Session, a: argparse.Namespace) -> None:
    s.send({"t": "unpair"})
    s.state.forget_peer()
    s.out.note(f"unpaired; removed the pinned bridge from {s.state.path}")


class ReplayWindow:
    """The media channel's 64-record sliding replay window (PROTOCOL.md, "Records")."""

    def __init__(self, size: int = 64) -> None:
        self.size, self.top, self.mask = size, -1, 0

    def may_accept(self, counter: int) -> bool:
        if self.top < 0 or counter > self.top:
            return True
        offset = self.top - counter
        return offset < self.size and not (self.mask >> offset) & 1

    def mark(self, counter: int) -> None:
        if self.top < 0:
            self.top, self.mask = counter, 1
        elif counter > self.top:
            shift = counter - self.top
            self.mask = ((self.mask << shift) | 1) & ((1 << self.size) - 1) if shift < self.size else 1
            self.top = counter
        else:
            self.mask |= 1 << (self.top - counter)


class MediaPath:
    """R's side of the UDP media channel (PROTOCOL.md, "Media channel (UDP)"): one record per datagram."""

    def __init__(self, host: str, port: int, keys: tuple[bytes, bytes]) -> None:
        family = socket.AF_INET6 if ":" in host else socket.AF_INET
        self.sock = socket.socket(family, socket.SOCK_DGRAM)
        self.sock.connect((host, port))
        self.k_br, self.k_rb = keys
        self.tx = 0
        self.window = ReplayWindow()
        self.packets: list[bytes] = []  # non-empty plaintexts from the bridge, in arrival order
        self.probes_back = 0
        self.dropped = 0
        self.sent_wires: list[bytes] = []

    def send(self, plaintext: bytes) -> bytes:
        assert len(plaintext) <= MEDIA_MAX_PLAINTEXT
        wire = seal(self.k_rb, DIR_R_TO_B, self.tx, CH_MEDIA, plaintext)
        self.tx += 1
        self.sock.send(wire)
        return wire

    def poll(self, timeout: float) -> int:
        """Reads datagrams for up to timeout seconds; returns how many authenticated."""
        deadline = time.monotonic() + timeout
        good = 0
        while True:
            remaining = deadline - time.monotonic()
            self.sock.settimeout(max(remaining, 0.001))
            try:
                data = self.sock.recv(2048)
            except (socket.timeout, BlockingIOError):
                return good
            except OSError:  # e.g. ICMP port unreachable before the bridge listens
                if remaining <= 0:
                    return good
                continue
            if not RECORD_OVERHEAD <= len(data) <= RECORD_OVERHEAD + MEDIA_MAX_PLAINTEXT:
                self.dropped += 1
                continue
            counter = struct.unpack(">Q", data[:8])[0]
            if not self.window.may_accept(counter):
                self.dropped += 1
                continue
            try:
                _, plaintext = open_record(self.k_br, DIR_B_TO_R, CH_MEDIA, data)
            except InvalidTag:
                self.dropped += 1
                continue
            self.window.mark(counter)
            good += 1
            if plaintext:
                self.packets.append(plaintext)
            else:
                self.probes_back += 1
            if remaining <= 0:
                return good


def act_media(s: Session, a: argparse.Namespace) -> None:
    """Opens the UDP media channel of this Wi-Fi session and sends test packets (echoed by a debug bridge
    with media echo on). Counts results as media.open / media.echo for --expect."""
    m = s.poll_for(lambda m: m["t"] == "wifi.media", s.timeout)
    if m is None:
        raise LinkError("no wifi.media from the bridge: is this a Wi-Fi session (--port 47820)?")
    port = a.media_port or m.get("port")
    if not isinstance(port, int):
        raise LinkError("the bridge has no media channel this session (wifi.media port is null)")
    path = MediaPath(a.host, port, s.media_keys)
    start = time.monotonic()
    while not path.probes_back:
        if time.monotonic() - start > s.timeout:
            raise LinkError(f"no probe came back from UDP {a.host}:{port} within {s.timeout:g}s "
                            "(for the emulator: adb emu redir add udp:47820:47820)")
        path.send(b"")
        path.poll(0.25)
    s.out.note(f"media channel open after {(time.monotonic() - start) * 1000:.0f} ms (UDP port {port})")
    s.received["media.open"] += 1
    first = None
    for i in range(a.packets):
        wire = path.send(struct.pack(">I", i) + os.urandom(156))
        first = first or wire
        path.poll(0.02)  # 20 ms apart, like audio frames
    path.sock.send(first)  # a replay: the bridge must drop it, so it must not come back
    path.poll(1.5)
    echoed = [p for p in path.packets if len(p) == 160]
    seqs = [struct.unpack(">I", p[:4])[0] for p in echoed]
    s.out.note(f"sent {a.packets} packets of 160 bytes and one replay; {len(echoed)} came back "
               f"({len(set(seqs))} distinct, {path.probes_back} probe replies, {path.dropped} dropped here)")
    if len(seqs) != len(set(seqs)):
        raise LinkError("a packet came back twice: the bridge accepted a replayed datagram")
    s.received["media.echo"] += len(echoed)
    path.sock.close()


def act_send_file(s: Session, a: argparse.Namespace) -> None:
    """file.offer, then the data in BULK records, then waits for file.done (Wi-Fi sessions only)."""
    path = Path(a.file).expanduser()
    size = path.stat().st_size
    fid = int.from_bytes(os.urandom(4), "big")
    s.send({"t": "file.offer", "fid": fid, "name": path.name, "size": size, "type": "application/octet-stream"})
    answer = s.wait_for(lambda m: m["t"] == "file.answer" and m.get("fid") == fid, "file.answer")
    if not answer.get("accept"):
        raise LinkError(f"the bridge declined the file: {answer.get('reason')}")
    digest = hashlib.sha256()
    start = time.monotonic()
    offset = 0
    with path.open("rb") as f:
        while chunk := f.read(FILE_CHUNK):
            s.send_bulk(b"\x01" + struct.pack(">IQ", fid, offset) + chunk)
            digest.update(chunk)
            offset += len(chunk)
    done = s.wait_for(lambda m: m["t"] == "file.done" and m.get("fid") == fid, "file.done",
                      timeout=max(s.timeout, 120))
    seconds = time.monotonic() - start
    if not done.get("ok"):
        raise LinkError(f"the bridge reports the file failed: {done.get('reason')}")
    s.out.note(f"sent {size} bytes in {seconds:.2f} s ({size / max(seconds, 1e-6) / 1e6:.1f} MB/s), "
               f"sha256 {digest.hexdigest()}")
    s.received["file.sent"] += 1


def act_receive_file(s: Session, a: argparse.Namespace) -> None:
    """Waits up to --seconds for a file.offer, accepts it, saves the data and answers file.done."""
    offer = s.wait_for(lambda m: m["t"] == "file.offer", "file.offer", timeout=a.seconds)
    fid, size = offer.get("fid"), offer.get("size")
    name = re.sub(r"[/\\\x00-\x1f]", "_", str(offer.get("name") or "file")).lstrip(".") or "file"
    if not isinstance(fid, int) or not isinstance(size, int) or not 0 <= size <= FILE_MAX:
        s.send({"t": "file.answer", "fid": fid, "accept": False, "reason": "too_large"})
        raise LinkError("bad file.offer")
    save_dir = Path(a.save_dir or ".").expanduser()
    save_dir.mkdir(parents=True, exist_ok=True)
    dest = save_dir / name
    digest = hashlib.sha256()
    received = 0
    out = (save_dir / (name + ".part")).open("wb")

    def handler(plaintext: bytes) -> None:
        nonlocal received
        if not plaintext or plaintext[0] != 1:
            return
        got_fid, offset = struct.unpack(">IQ", plaintext[1:13])
        if got_fid != fid:
            return
        if offset != received:
            raise LinkError(f"file data out of order: offset {offset}, expected {received}")
        data = plaintext[13:]
        out.write(data)
        digest.update(data)
        received += len(data)

    s.bulk_handler = handler
    s.send({"t": "file.answer", "fid": fid, "accept": True, "reason": None})
    start = time.monotonic()
    deadline = start + max(a.seconds, 120)
    while received < size:
        if time.monotonic() > deadline:
            raise LinkError(f"timed out with {received} of {size} bytes")
        s.poll_for(lambda m: m["t"] == "file.cancel" and m.get("fid") == fid, 0.5)
    out.close()
    (save_dir / (name + ".part")).replace(dest)
    s.bulk_handler = None
    s.send({"t": "file.done", "fid": fid, "ok": True, "reason": None})
    seconds = time.monotonic() - start
    s.out.note(f"received {name!r}: {size} bytes in {seconds:.2f} s, sha256 {digest.hexdigest()}, saved to {dest}")
    s.received["file.received"] += 1


def act_all(s: Session, a: argparse.Namespace) -> None:
    for name in ("status", "ping", "threads", "history", "contacts", "calllog") + (("send-sms",) if a.to else ()):
        s.out.note(f"== {name}")
        ACTIONS[name](s, a)


ACTIONS: dict[str, Callable[[Session, argparse.Namespace], None]] = {
    "status": act_status, "ping": act_ping, "threads": act_threads, "history": act_history,
    "send-sms": act_send_sms, "dial": act_dial,
    "answer": simple_request("call.answer", "call"), "reject": simple_request("call.reject", "call"),
    "end": simple_request("call.end", "call"), "dtmf": simple_request("call.dtmf", "call", "digit"),
    "audio": act_audio, "contacts": act_contacts, "calllog": act_calllog, "contacts-add": act_contacts_add,
    "calllog-delete": act_calllog_delete, "blocks": act_blocks, "block": act_block, "unblock": act_unblock,
    "ussd": act_ussd, "listen": act_listen, "unpair": act_unpair, "all": act_all,
    "media": act_media, "send-file": act_send_file, "receive-file": act_receive_file,
}
REQUIRED = {"send-sms": ("to", "body"), "dial": ("number",), "answer": ("call",), "reject": ("call",),
            "end": ("call",), "dtmf": ("call", "digit"), "audio": ("call",), "contacts-add": ("name", "number"),
            "ussd": ("code",), "send-file": ("file",), "block": ("number",), "unblock": ("number",)}


# ---------------------------------------------------------------------------------------------
# Command line

def parse_bool(s: str) -> bool:
    if s.lower() in ("true", "1", "yes", "on"):
        return True
    if s.lower() in ("false", "0", "no", "off"):
        return False
    raise argparse.ArgumentTypeError("expected true or false")


def positive(kind: type) -> Callable[[str], Any]:
    def conv(s: str) -> Any:
        v = kind(s)
        if v <= 0:
            raise argparse.ArgumentTypeError("must be greater than 0")
        return v
    return conv


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="fake_remote.py", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter,
        usage="%(prog)s [OPTIONS] (pair --pair-uri URI | session) [ACTION ...]\n       %(prog)s --selftest")
    p.add_argument("words", nargs="*", metavar="WORD", help="pair or session, then actions (see above)")
    p.add_argument("--selftest", action="store_true", help="check every value in protocol/test-vectors/v0.json")
    g = p.add_argument_group("connection")
    g.add_argument("--host", default="127.0.0.1", help="bridge address (default 127.0.0.1)")
    g.add_argument("--port", type=int, default=DEFAULT_PORT, help=f"bridge TCP port (default {DEFAULT_PORT})")
    g.add_argument("--state", default="~/.pairline-fake-remote.json", metavar="PATH",
                   help="identity and pinned-bridge file (default ~/.pairline-fake-remote.json)")
    g.add_argument("--pair-uri", metavar="URI", help="pairline://pair?... link shown by the bridge (pair only)")
    g.add_argument("--timeout", type=positive(float), default=10.0, metavar="S",
                   help="seconds to wait for each reply (default 10)")
    g = p.add_argument_group("output and checks")
    g.add_argument("--pretty", action="store_true", help="pretty-print JSON messages")
    g.add_argument("--full", action="store_true", help="don't shorten long lists in the transcript")
    g.add_argument("--expect", action="append", default=[], metavar="TYPE",
                   help="fail (exit 1) unless the bridge sent a message of this type; repeatable")
    g = p.add_argument_group("action options")
    g.add_argument("--limit", type=positive(int), metavar="N", help="page size (threads default 20, history 50)")
    g.add_argument("--max-pages", type=positive(int), default=10, metavar="N",
                   help="most pages to fetch per paged action (default 10)")
    g.add_argument("--thread", type=int, metavar="T", help="history: thread id (default: all threads)")
    g.add_argument("--since", metavar="CURSOR", help="contacts/calllog: continue from this cursor (default: full sync)")
    g.add_argument("--to", metavar="NUMBER", help="send-sms: recipient")
    g.add_argument("--body", metavar="TEXT", help="send-sms: message text")
    g.add_argument("--sim", type=int, metavar="N", help="0-based SIM slot (default: null = bridge default)")
    g.add_argument("--wait", type=positive(float), default=20.0, metavar="S",
                   help="send-sms/ussd: seconds to wait for sms.state / ussd.result (default 20)")
    g.add_argument("--number", metavar="NUMBER", help="dial / contacts-add: phone number")
    g.add_argument("--call", metavar="ID", help="answer/reject/end/dtmf/audio: call id from the bridge, e.g. c3")
    g.add_argument("--digit", metavar="D", help="dtmf: digit to send")
    g.add_argument("--mute", type=parse_bool, metavar="true|false", help="audio: mute the microphone")
    g.add_argument("--speaker", type=parse_bool, metavar="true|false", help="audio: use the speaker")
    g.add_argument("--name", metavar="NAME", help="contacts-add: contact name")
    g.add_argument("--code", metavar="CODE", help="ussd: code such as '*100#'")
    g.add_argument("--keys", metavar="KEYS", help="calllog-delete: comma-separated call-log keys, e.g. cl:12,cl:13")
    g.add_argument("--all-calls", action="store_true", help="calllog-delete: delete the whole call log")
    g.add_argument("--seconds", type=positive(float), default=30.0, metavar="S",
                   help="listen: how long to listen (default 30)")
    g.add_argument("--auto-answer", action="store_true", help="listen: answer every call.incoming")
    g.add_argument("--auto-end", type=positive(float), metavar="S",
                   help="listen: end each call S seconds after it becomes active")
    g.add_argument("--packets", type=positive(int), default=50, metavar="N",
                   help="media: test packets to send, 20 ms apart (default 50)")
    g.add_argument("--media-port", type=int, metavar="PORT",
                   help="media: UDP port to use instead of the one in wifi.media (e.g. a redirected port)")
    g.add_argument("--file", metavar="PATH", help="send-file: the file to send")
    g.add_argument("--save-dir", metavar="DIR", help="receive-file: where to save (default: current directory)")
    return p


def validate(a: argparse.Namespace) -> tuple[str, list[str], PairUri | None]:
    if not a.words:
        raise UsageError("give a mode (pair or session), or --selftest")
    mode, actions = a.words[0], a.words[1:]
    if mode not in ("pair", "session"):
        raise UsageError(f"unknown mode {mode!r}: the first word must be pair or session")
    unknown = [x for x in actions if x not in ACTIONS]
    if unknown:
        raise UsageError(f"unknown action(s): {', '.join(unknown)}; choose from {', '.join(ACTIONS)}")
    if "unpair" in actions and actions.index("unpair") != len(actions) - 1:
        raise UsageError("unpair must be the last action")
    for action in dict.fromkeys(actions):
        missing = [f"--{opt}" for opt in REQUIRED.get(action, ()) if getattr(a, opt) is None]
        if missing:
            raise UsageError(f"{action} needs {' and '.join(missing)}")
    if "all" in actions and a.to and a.body is None:
        raise UsageError("all with --to also needs --body")
    if "audio" in actions and a.mute is None and a.speaker is None:
        raise UsageError("audio needs --mute and/or --speaker")
    uri = None
    if mode == "pair":
        if not a.pair_uri:
            raise UsageError("pair needs --pair-uri 'pairline://pair?...' (quote it: it contains &)")
        try:
            uri = parse_pair_uri(a.pair_uri)
        except ValueError as e:
            raise UsageError(f"bad pairing link: {e}") from None
    elif a.pair_uri:
        raise UsageError("--pair-uri is only for pair")
    return mode, actions, uri


def run(a: argparse.Namespace, mode: str, actions: list[str], uri: PairUri | None) -> int:
    out = Transcript(a.pretty, a.full)
    path = Path(a.state).expanduser()
    not_paired = f"not paired yet (no bridge in {path}); run pair --pair-uri '...' first"
    if mode == "session" and not path.exists():
        raise UsageError(not_paired)
    state = State.load(path, out)
    if mode == "session" and state.peer_key is None:
        raise UsageError(not_paired)
    session: Session | None = None
    try:
        out.note(f"connecting to {a.host}:{a.port}")
        link = Link(a.host, a.port, a.timeout)
        try:
            out.note("connected")
            if uri is not None:
                pair(link, out, state, uri, a.timeout)
            session = start_session(link, out, state, a.timeout)
            for action in actions:
                out.note(f"action: {action}")
                ACTIONS[action](session, a)
        finally:
            link.close()
    except PairingRefused as e:
        out.warn(str(e))
        print(f"FAIL: pairing refused: {e}")
        return 2
    except LinkError as e:
        out.warn(str(e))
        print(f"FAIL: {e}")
        return 3
    assert session is not None
    counts = (f"{sum(session.received.values())} messages received, {session.sent} sent, "
              f"{session.error_replies} error replies")
    missing = [t for t in a.expect if not session.received[t]]
    if missing:
        print(f"FAIL: never received {', '.join(missing)} ({counts})")
        return 1
    print(f"PASS: {'received ' + ', '.join(a.expect) + '; ' if a.expect else ''}{counts}")
    return 0


def main(argv: list[str] | None = None) -> int:
    try:
        sys.stdout.reconfigure(errors="backslashreplace")  # type: ignore[attr-defined]
    except (AttributeError, ValueError):
        pass
    parser = build_parser()
    a = parser.parse_intermixed_args(argv)
    if a.selftest:
        if a.words:
            parser.error("--selftest takes no mode or actions")
        return selftest()
    try:
        mode, actions, uri = validate(a)
        return run(a, mode, actions, uri)
    except UsageError as e:
        parser.error(str(e))
    except KeyboardInterrupt:
        print("\nFAIL: interrupted")
        return 130
    return 2


if __name__ == "__main__":
    sys.exit(main())
