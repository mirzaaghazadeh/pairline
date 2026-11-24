#!/usr/bin/env python3
"""Generate protocol/test-vectors/v0.json from fixed inputs (docs/PROTOCOL.md, wire version 0).

This is an independent reference implementation using pyca/cryptography. The apps use platform
crypto (Android JCA, iOS CryptoKit) and their unit tests must reproduce every value here.
Dev tool only; it is never shipped in an app.

    python3 -I protocol/tools/gen_vectors.py > protocol/test-vectors/v0.json
"""
import base64
import hashlib
import hmac
import json
import struct

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

VERSION = 0
ROLE_R, ROLE_B = 0x52, 0x42  # 'R', 'B'
DIR_B_TO_R, DIR_R_TO_B = 1, 2
FRAME_PAIR_HELLO, FRAME_PAIR_OK, FRAME_PAIR_FAIL, FRAME_HELLO, FRAME_RECORD = 0x01, 0x02, 0x03, 0x10, 0x20
FRAME_BULK = 0x21


def hx(b: bytes) -> str:
    return b.hex()


def b64u(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def label(s: str) -> bytes:
    """Deterministic 32 bytes for a test input."""
    return hashlib.sha256(b"pairline test vector: " + s.encode()).digest()


def private_key(name: str):
    return ec.derive_private_key(int.from_bytes(label(name), "big"), ec.SECP256R1())


def scalar(k) -> bytes:
    return k.private_numbers().private_value.to_bytes(32, "big")


def pub(k) -> bytes:
    return k.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)


def ecdh(k, other) -> bytes:
    return k.exchange(ec.ECDH(), other.public_key())


def hkdf(ikm: bytes, salt: bytes, info: bytes, length: int) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=length, salt=salt, info=info).derive(ikm)


def nonce(direction: int, counter: int) -> bytes:
    return struct.pack(">IQ", direction, counter)


def seal(key: bytes, direction: int, counter: int, channel: int, plaintext: bytes) -> bytes:
    ad = bytes([VERSION, channel])
    return struct.pack(">Q", counter) + AESGCM(key).encrypt(nonce(direction, counter), plaintext, ad)


class ReplayWindow:
    """The media channel's 64-record sliding replay window (PROTOCOL.md, "Records")."""

    SIZE = 64

    def __init__(self):
        self.top = None  # highest accepted counter
        self.mask = 0    # bit i set: counter top - i was accepted

    def may_accept(self, counter: int) -> bool:
        if self.top is None or counter > self.top:
            return True
        offset = self.top - counter
        return offset < self.SIZE and not (self.mask >> offset) & 1

    def mark(self, counter: int):
        if self.top is None:
            self.top, self.mask = counter, 1
        elif counter > self.top:
            shift = counter - self.top
            self.mask = ((self.mask << shift) | 1) & ((1 << self.SIZE) - 1) if shift < self.SIZE else 1
            self.top = counter
        else:
            self.mask |= 1 << (self.top - counter)


def replay_window_steps(key: bytes) -> list:
    """Datagrams B→R on the media channel and whether the receiver accepts each, in order."""
    window = ReplayWindow()
    steps = []
    # counter, authentic (False: the tag is altered)
    for counter, authentic in [(5, True), (3, True), (5, True), (70, True), (6, True), (7, True), (7, True),
                               (69, True), (200, False), (71, True), (200, True), (136, True), (137, True),
                               (137, True), (0, True), (201, True)]:
        plaintext = struct.pack(">Q", counter)
        wire = bytearray(seal(key, DIR_B_TO_R, counter, 1, plaintext))
        if not authentic:
            wire[-1] ^= 0x01
        accept = window.may_accept(counter) and authentic
        if accept:
            window.mark(counter)
        steps.append({"counter": counter, "wire": hx(bytes(wire)), "accept": accept,
                      "plaintext_hex": hx(plaintext) if accept else None})
    return steps


def fragments(frame: bytes, att_payload: int) -> list:
    """BLE fragmentation: 1-byte header (bit0 first, bit1 last); the first fragment adds a 2-byte length."""
    out, pos, first = [], 0, True
    while True:
        room = att_payload - 1 - (2 if first else 0)
        chunk = frame[pos:pos + room]
        pos += len(chunk)
        last = pos >= len(frame)
        header = (1 if first else 0) | (2 if last else 0)
        out.append(bytes([header]) + (struct.pack(">H", len(frame)) if first else b"") + chunk)
        first = False
        if last:
            return out


def main():
    sB, sR = private_key("sB"), private_key("sR")
    eB, eR = private_key("eB"), private_key("eR")
    device_id = bytes.fromhex("a1b2c3d4e5f60718")
    ps = label("pairing secret")
    nonce_r, nonce_b = label("nonceR"), label("nonceB")
    rand_r, rand_b = label("randR"), label("randB")

    # Pairing
    t = b"pairline-pair-v0" + pub(sB) + pub(sR) + nonce_r
    mac_r = hmac.new(ps, t, hashlib.sha256).digest()
    mac_b = hmac.new(ps, t + nonce_b + b"B", hashlib.sha256).digest()
    qr = f"pairline://pair?v=0&id={b64u(device_id)}&k={b64u(pub(sB))}&s={b64u(ps)}"

    # Session handshake
    hello_r = bytes([VERSION, ROLE_R]) + pub(eR) + rand_r
    hello_b = bytes([VERSION, ROLE_B]) + pub(eB) + rand_b
    salt = hashlib.sha256(hello_r + hello_b).digest()
    ikm_b = ecdh(eB, eR) + ecdh(sB, eR) + ecdh(eB, sR)
    ikm_r = ecdh(eR, eB) + ecdh(eR, sB) + ecdh(sR, eB)
    assert ikm_b == ikm_r
    okm = hkdf(ikm_b, salt, b"pairline v0 session", 64)
    k_br, k_rb = okm[:32], okm[32:]
    media = hkdf(okm, b"", b"pairline v0 media", 64)
    bulk = hkdf(okm, b"", b"pairline v0 bulk", 64)

    status = b'{"t":"status","battery":{"level":82,"charging":false},"app":"0.1.0"}'
    send = '{"t":"sms.send","id":7,"sim":0,"to":"+989121234567","body":"سلام 👋","cid":"c-1"}'.encode()
    record_b = seal(k_br, DIR_B_TO_R, 1, 0, status)
    record_r = seal(k_rb, DIR_R_TO_B, 1, 0, send)
    media_rec = seal(media[:32], DIR_B_TO_R, 5, 1, b"\x00" * 10)
    media_probe_r = seal(media[32:], DIR_R_TO_B, 0, 1, b"")  # an empty media record is a probe
    bulk_plain = bytes([0x01]) + struct.pack(">IQ", 7, 49152) + "file data ✓".encode()
    bulk_rec = seal(bulk[:32], DIR_B_TO_R, 0, 2, bulk_plain)
    bulk_frame = bytes([FRAME_BULK]) + bulk_rec
    frame_b = bytes([FRAME_RECORD]) + record_b
    qr_wifi = (qr + "&ip=192.168.1.23,fd12:3456:789a::1&port=47820"
               f"&ssid={b64u('AndroidShare_4821'.encode())}&pw={b64u('k3y with spaces & ✓'.encode())}")

    tampered = bytearray(record_b)
    tampered[-1] ^= 0x01

    vectors = {
        "description": "Pairline link protocol v0 test vectors. All binary values are lowercase hex unless the key "
                       "ends in _b64u. Generated by protocol/tools/gen_vectors.py; see docs/PROTOCOL.md.",
        "version": VERSION,
        "hkdf_rfc5869_case1": {
            "ikm": "0b" * 22, "salt": "000102030405060708090a0b0c", "info": "f0f1f2f3f4f5f6f7f8f9", "length": 42,
            "okm": hx(hkdf(bytes.fromhex("0b" * 22), bytes.fromhex("000102030405060708090a0b0c"),
                          bytes.fromhex("f0f1f2f3f4f5f6f7f8f9"), 42)),
        },
        "keys": {
            name: {"private": hx(scalar(k)), "public": hx(pub(k))}
            for name, k in (("sB", sB), ("sR", sR), ("eB", eB), ("eR", eR))
        },
        "ecdh": {
            "eB_eR": hx(ecdh(eB, eR)), "sB_eR": hx(ecdh(sB, eR)), "eB_sR": hx(ecdh(eB, sR)),
        },
        "pairing": {
            "device_id": hx(device_id), "pairing_secret": hx(ps), "nonce_r": hx(nonce_r), "nonce_b": hx(nonce_b),
            "qr_uri": qr,
            "transcript_t": hx(t), "mac_r": hx(mac_r), "mac_b": hx(mac_b),
            "pair_hello_json": {"t": "pair_hello", "v": 0, "k": b64u(pub(sR)), "n": b64u(nonce_r), "mac": b64u(mac_r)},
            "pair_ok_json": {"t": "pair_ok", "v": 0, "n": b64u(nonce_b), "mac": b64u(mac_b)},
        },
        "session": {
            "rand_r": hx(rand_r), "rand_b": hx(rand_b),
            "hello_r": hx(hello_r), "hello_b": hx(hello_b),
            "hello_r_frame": hx(bytes([FRAME_HELLO]) + hello_r),
            "salt": hx(salt), "ikm": hx(ikm_b), "okm": hx(okm), "k_br": hx(k_br), "k_rb": hx(k_rb),
            "confirm_r_to_b": hx(seal(k_rb, DIR_R_TO_B, 0, 0, b"")),
            "confirm_b_to_r": hx(seal(k_br, DIR_B_TO_R, 0, 0, b"")),
            "media_k_br": hx(media[:32]), "media_k_rb": hx(media[32:]),
            "bulk_k_br": hx(bulk[:32]), "bulk_k_rb": hx(bulk[32:]),
        },
        "records": [
            {"key": "k_br", "direction": DIR_B_TO_R, "counter": 1, "channel": 0,
             "nonce": hx(nonce(DIR_B_TO_R, 1)), "ad": hx(bytes([VERSION, 0])),
             "plaintext_utf8": status.decode(), "wire": hx(record_b)},
            {"key": "k_rb", "direction": DIR_R_TO_B, "counter": 1, "channel": 0,
             "nonce": hx(nonce(DIR_R_TO_B, 1)), "ad": hx(bytes([VERSION, 0])),
             "plaintext_utf8": send.decode(), "wire": hx(record_r)},
            {"key": "media_k_br", "direction": DIR_B_TO_R, "counter": 5, "channel": 1,
             "nonce": hx(nonce(DIR_B_TO_R, 5)), "ad": hx(bytes([VERSION, 1])),
             "plaintext_hex": "00" * 10, "wire": hx(media_rec)},
            {"key": "media_k_rb", "direction": DIR_R_TO_B, "counter": 0, "channel": 1,
             "nonce": hx(nonce(DIR_R_TO_B, 0)), "ad": hx(bytes([VERSION, 1])),
             "plaintext_hex": "", "wire": hx(media_probe_r)},
            {"key": "bulk_k_br", "direction": DIR_B_TO_R, "counter": 0, "channel": 2,
             "nonce": hx(nonce(DIR_B_TO_R, 0)), "ad": hx(bytes([VERSION, 2])),
             "plaintext_hex": hx(bulk_plain), "wire": hx(bulk_rec)},
        ],
        # Media datagrams B→R (key media_k_br) in arrival order; "accept" is what a receiver with a fresh
        # 64-record window must do with each. Rejected ones carry no plaintext.
        "media_replay_window": {"key": "media_k_br", "direction": DIR_B_TO_R, "channel": 1, "size": 64,
                                "steps": replay_window_steps(media[:32])},
        "bulk_file_data": {"fid": 7, "offset": 49152, "data_utf8": "file data ✓", "plaintext": hx(bulk_plain),
                           "frame": hx(bulk_frame), "tcp": hx(struct.pack(">I", len(bulk_frame)) + bulk_frame)},
        "pair_uri_wifi": {"uri": qr_wifi, "ip": ["192.168.1.23", "fd12:3456:789a::1"], "port": 47820,
                          "ssid": "AndroidShare_4821", "pw": "k3y with spaces & ✓"},
        "must_fail": [
            {"why": "tag altered", "key": "k_br", "direction": DIR_B_TO_R, "channel": 0, "wire": hx(bytes(tampered))},
            {"why": "wrong channel in associated data", "key": "k_br", "direction": DIR_B_TO_R, "channel": 1,
             "wire": hx(record_b)},
            {"why": "wrong direction label in nonce", "key": "k_br", "direction": DIR_R_TO_B, "channel": 0,
             "wire": hx(record_b)},
        ],
        "framing": {
            "frame": hx(frame_b),
            "ble_att_payload": 20,
            "ble_fragments": [hx(f) for f in fragments(frame_b, 20)],
            "ble_single_fragment_att_payload": 244,
            "ble_single_fragment": [hx(f) for f in fragments(frame_b, 244)],
            "tcp": hx(struct.pack(">I", len(frame_b)) + frame_b),
        },
    }
    print(json.dumps(vectors, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
