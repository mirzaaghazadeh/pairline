#!/usr/bin/env python3
"""Generate protocol/test-vectors/v1-remote.json: remote mode (docs/PROTOCOL.md, "Remote mode (v1, D-080)").

An independent reference implementation with pyca/cryptography, reusing gen_vectors.py's fixed keys. The apps use
platform crypto (Android JCA, iOS CryptoKit) and their unit tests must reproduce every value here.
Dev tool only; it is never shipped in an app.

    python3 -I protocol/tools/gen_vectors_remote.py > protocol/test-vectors/v1-remote.json

Also importable: seal_mailbox / open_mailbox / seal_invite / open_invite / commit / code, for fake_bridge.py and
cloudkit_mailbox.py.
"""
import hashlib
import hmac
import json
import os
import struct
import sys
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_vectors import b64u, ecdh, hkdf, hx, label, private_key, pub  # noqa: E402

from cryptography.hazmat.primitives.asymmetric import ec  # noqa: E402
from cryptography.hazmat.primitives.ciphers.aead import AESGCM  # noqa: E402

VERSION = 0x01
KIND_SEALED, KIND_INVITE = 0x01, 0x02
DIR_B, DIR_R = 0x42, 0x52  # 'B' = B→R, 'R' = R→B
HEADER_INVITE = 27
HEADER_SEALED = 27 + 65
MAX_PLAINTEXT = 48 * 1024


def header(kind: int, direction: int, record_id: bytes, time_ms: int, eph_pub: bytes = b"") -> bytes:
    assert len(record_id) == 16
    h = bytes([VERSION, kind, direction]) + record_id + struct.pack(">Q", time_ms)
    if kind == KIND_SEALED:
        assert len(eph_pub) == 65
        h += eph_pub
    return h


def public_from_bytes(raw: bytes):
    return ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), raw)


def mailbox_key(ikm: bytes, hdr: bytes) -> bytes:
    return hkdf(ikm, hashlib.sha256(hdr).digest(), b"pairline v1 mailbox", 32)


def seal_mailbox(sender_static, recipient_pub: bytes, direction: int, record_id: bytes, time_ms: int,
                 plaintext: bytes, ephemeral=None) -> bytes:
    """Kind 1: e, es, ss. `ephemeral` is fixed only for test vectors."""
    e = ephemeral or ec.generate_private_key(ec.SECP256R1())
    peer = public_from_bytes(recipient_pub)
    ikm = e.exchange(ec.ECDH(), peer) + sender_static.exchange(ec.ECDH(), peer)
    hdr = header(KIND_SEALED, direction, record_id, time_ms, pub(e))
    return hdr + AESGCM(mailbox_key(ikm, hdr)).encrypt(bytes(12), plaintext, hdr)


def open_mailbox(recipient_static, sender_pub: bytes, sealed: bytes) -> bytes:
    hdr = sealed[:HEADER_SEALED]
    if len(sealed) < HEADER_SEALED + 16 or hdr[0] != VERSION or hdr[1] != KIND_SEALED:
        raise ValueError("not a kind 1 record")
    e_pub = public_from_bytes(hdr[27:92])
    ikm = recipient_static.exchange(ec.ECDH(), e_pub) + recipient_static.exchange(ec.ECDH(), public_from_bytes(sender_pub))
    return AESGCM(mailbox_key(ikm, hdr)).decrypt(bytes(12), sealed[HEADER_SEALED:], hdr)


def invite_keys(invite_secret: bytes) -> tuple:
    k = hkdf(invite_secret, b"", b"pairline v1 invite", 64)
    return k[:32], k[32:]  # B→R, R→B


def seal_invite(invite_secret: bytes, direction: int, record_id: bytes, time_ms: int, plaintext: bytes) -> bytes:
    k_br, k_rb = invite_keys(invite_secret)
    hdr = header(KIND_INVITE, direction, record_id, time_ms)
    key = k_br if direction == DIR_B else k_rb
    return hdr + AESGCM(key).encrypt(record_id[:12], plaintext, hdr)


def open_invite(invite_secret: bytes, sealed: bytes) -> bytes:
    hdr = sealed[:HEADER_INVITE]
    if len(sealed) < HEADER_INVITE + 16 or hdr[0] != VERSION or hdr[1] != KIND_INVITE:
        raise ValueError("not a kind 2 record")
    k_br, k_rb = invite_keys(invite_secret)
    key = k_br if hdr[2] == DIR_B else k_rb
    return AESGCM(key).decrypt(hdr[3:15], sealed[HEADER_INVITE:], hdr)


def commit(pub_r: bytes, pub_b: bytes, nonce_b: bytes) -> bytes:
    return hashlib.sha256(b"pairline v1 commit" + pub_r + pub_b + nonce_b).digest()


def code(invite_secret: bytes, pub_r: bytes, pub_b: bytes, nonce_r: bytes, nonce_b: bytes) -> str:
    mac = hmac.new(invite_secret, b"pairline v1 code" + pub_r + pub_b + nonce_r + nonce_b, hashlib.sha256).digest()
    return f"{int.from_bytes(mac[:4], 'big') % 1_000_000:06d}"


def invite_uri(pub_r: bytes, invite_secret: bytes, backend: str, env: str, token: str, mb: dict, expiry: int) -> str:
    q = [("v", "1"), ("k", b64u(pub_r)), ("s", b64u(invite_secret)), ("b", backend), ("e", env), ("t", token),
         ("m", mb["b"]), ("a", mb["a"]), ("q", mb["q"]), ("x", str(expiry))]
    return "pairline://invite?" + urllib.parse.urlencode(q, quote_via=urllib.parse.quote, safe="")


def main():
    sB, sR = private_key("sB"), private_key("sR")
    pub_b, pub_r = pub(sB), pub(sR)
    time_ms = 1795700000000
    device_id = bytes.fromhex("a1b2c3d4e5f60718")

    # Kind 1, B→R: an incoming SMS for the alert mailbox.
    e1 = private_key("mailbox e1")
    rid1 = label("mailbox record 1")[:16]
    sms = '{"t":"sms.new","msg":{"key":"sms:1","thread":3,"addr":"+989121234567","body":"Your code is 4821",' \
          '"time":1795700000000,"box":"in","sim":0,"state":"received"}}'.encode()
    rec1 = seal_mailbox(sB, pub_r, DIR_B, rid1, time_ms, sms, e1)
    assert open_mailbox(sR, pub_b, rec1) == sms
    hdr1 = rec1[:HEADER_SEALED]
    ikm1 = ecdh(e1, sR) + ecdh(sB, sR)

    # Kind 1, R→B: a command.
    e2 = private_key("mailbox e2")
    rid2 = label("mailbox record 2")[:16]
    send = '{"t":"sms.send","id":1000001,"sim":0,"to":"+989121234567","body":"سلام 👋","cid":"c-9"}'.encode()
    rec2 = seal_mailbox(sR, pub_b, DIR_R, rid2, time_ms + 1000, send, e2)
    assert open_mailbox(sB, pub_r, rec2) == send

    tampered = bytearray(rec1)
    tampered[5] ^= 0x01  # a record id bit: the header is associated data
    try:
        open_mailbox(sR, pub_b, bytes(tampered))
        raise AssertionError("tampered header opened")
    except Exception:  # noqa: BLE001 (InvalidTag)
        pass

    # Remote pairing.
    invite_secret = label("invite secret")
    nonce_r, nonce_b = label("remote nonceR"), label("remote nonceB")
    mb = {"b": label("mailbox to B")[:16].hex(), "a": label("mailbox alert")[:16].hex(),
          "q": label("mailbox quiet")[:16].hex()}
    token = "167__54__ABCdef+/ghi=="  # not a real token; shows the percent-encoding
    uri = invite_uri(pub_r, invite_secret, "public", "development", token, mb, 1795700600)
    k_br, k_rb = invite_keys(invite_secret)
    c = commit(pub_r, pub_b, nonce_b)
    hello = json.dumps({"t": "rpair.hello", "k": b64u(pub_b), "id": b64u(device_id), "c": b64u(c)},
                       separators=(",", ":")).encode()
    nonce_msg = json.dumps({"t": "rpair.nonce", "n": b64u(nonce_r)}, separators=(",", ":")).encode()
    reveal = json.dumps({"t": "rpair.reveal", "n": b64u(nonce_b)}, separators=(",", ":")).encode()
    rid_h, rid_n, rid_v = (label(f"invite record {i}")[:16] for i in (1, 2, 3))
    inv_hello = seal_invite(invite_secret, DIR_B, rid_h, time_ms, hello)
    inv_nonce = seal_invite(invite_secret, DIR_R, rid_n, time_ms + 2000, nonce_msg)
    inv_reveal = seal_invite(invite_secret, DIR_B, rid_v, time_ms + 4000, reveal)
    assert open_invite(invite_secret, inv_hello) == hello
    the_code = code(invite_secret, pub_r, pub_b, nonce_r, nonce_b)
    e3 = private_key("mailbox e3")
    rid_c = label("confirm record")[:16]
    confirm = seal_mailbox(sB, pub_r, DIR_B, rid_c, time_ms + 9000, b'{"t":"rpair.confirm"}', e3)

    vectors = {
        "description": "Pairline remote mode (protocol v1) test vectors. Binary values are lowercase hex unless the "
                       "key ends in _b64u. Identity keys sB and sR are the ones in v0.json. Generated by "
                       "protocol/tools/gen_vectors_remote.py; see docs/PROTOCOL.md, \"Remote mode (v1, D-080)\".",
        "version": 1,
        "keys": {"sB_public": hx(pub_b), "sR_public": hx(pub_r)},
        "mailbox_b_to_r": {
            "ephemeral_private": hx(e1.private_numbers().private_value.to_bytes(32, "big")),
            "ephemeral_public": hx(pub(e1)),
            "record_id": hx(rid1), "record_name": rid1.hex(), "time_ms": time_ms,
            "header": hx(hdr1), "ikm": hx(ikm1), "salt": hx(hashlib.sha256(hdr1).digest()),
            "key": hx(mailbox_key(ikm1, hdr1)),
            "plaintext_utf8": sms.decode(), "sealed": hx(rec1), "sealed_b64u": b64u(rec1),
        },
        "mailbox_r_to_b": {
            "ephemeral_private": hx(e2.private_numbers().private_value.to_bytes(32, "big")),
            "ephemeral_public": hx(pub(e2)),
            "record_id": hx(rid2), "time_ms": time_ms + 1000,
            "header": hx(rec2[:HEADER_SEALED]), "ikm": hx(ecdh(e2, sB) + ecdh(sR, sB)),
            "key": hx(mailbox_key(ecdh(e2, sB) + ecdh(sR, sB), rec2[:HEADER_SEALED])),
            "plaintext_utf8": send.decode(), "sealed": hx(rec2),
        },
        "mailbox_must_fail": [
            {"why": "a header byte changed (the header is associated data)", "sealed": hx(bytes(tampered))},
            {"why": "opened with the wrong sender key (B's record checked against sR as the sender)",
             "sealed": hx(rec1), "use_sender_public": hx(pub_r)},
        ],
        "invite": {
            "invite_secret": hx(invite_secret), "uri": uri, "token": token, "mailboxes": mb, "expiry": 1795700600,
            "backend": "public", "env": "development",
            "k_br": hx(k_br), "k_rb": hx(k_rb),
            "nonce_r": hx(nonce_r), "nonce_b": hx(nonce_b), "device_id": hx(device_id),
            "commit": hx(c), "code": the_code,
            "hello_json": hello.decode(), "hello_record_id": hx(rid_h), "hello_time_ms": time_ms,
            "hello_sealed": hx(inv_hello),
            "nonce_json": nonce_msg.decode(), "nonce_record_id": hx(rid_n), "nonce_time_ms": time_ms + 2000,
            "nonce_sealed": hx(inv_nonce),
            "reveal_json": reveal.decode(), "reveal_record_id": hx(rid_v), "reveal_time_ms": time_ms + 4000,
            "reveal_sealed": hx(inv_reveal),
            "confirm_ephemeral_private": hx(e3.private_numbers().private_value.to_bytes(32, "big")),
            "confirm_ephemeral_public": hx(pub(e3)), "confirm_json": '{"t":"rpair.confirm"}',
            "confirm_record_id": hx(rid_c), "confirm_time_ms": time_ms + 9000, "confirm_sealed": hx(confirm),
        },
    }
    print(json.dumps(vectors, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
