#!/usr/bin/env python3
"""Fake Android bridge for testing the Pairline iPhone app's DEBUG build on the iOS Simulator.

Plays role B (bridge) of the link protocol in docs/PROTOCOL.md (wire version 0) over the debug TCP
transport: each frame is preceded by its 4-byte big-endian length. It pairs with the app, runs the
session handshake, answers requests from made-up sample data (SMS threads, contacts, call log, USSD)
and lets you inject events (incoming SMS and one-time codes, calls, battery and signal changes) from
its console or from a line-based control socket. Dev tool only: it is never shipped in an app, all
data is fake, and it only listens on 127.0.0.1. Needs Python 3.10+ and pyca/cryptography.

Start it; it prints a pairing link (valid 5 minutes, single use):

    python3 -I protocol/tools/fake_bridge.py

Pair the Simulator's DEBUG build with the printed link:

    xcrun simctl openurl booted "pairline://pair?v=0&id=...&k=...&s=..."

Drive it while it runs: type commands into its console (`help` lists them), or use the control socket:

    nc 127.0.0.1 47801                   # interactive session
    echo "otp" | nc 127.0.0.1 47801      # one command from a script
    echo "pair" | nc 127.0.0.1 47801     # a fresh pairing link

Run it in the background (it does not read the terminal then):

    python3 -I protocol/tools/fake_bridge.py --no-stdin > /tmp/fake_bridge.log 2>&1 &

Options: --state PATH (default ~/.pairline/fake_bridge.json: B's identity key, device id and the
pinned iPhone key, so the app reconnects without pairing again), --reset (forget all of that),
--single-sim, --app-version X.Y.Z, --host/--port (127.0.0.1:47800), --control-port (47801; 0 turns
it off), --no-stdin, --self-test (check the crypto helpers against protocol/test-vectors/v0.json).
"""
import argparse
import asyncio
import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import struct
import sys
import threading
import time
import traceback
from pathlib import Path

try:
    from cryptography.exceptions import InvalidTag
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    from cryptography.hazmat.primitives.kdf.hkdf import HKDF
except ImportError:
    sys.exit("fake_bridge.py needs pyca/cryptography: python3 -m pip install cryptography")

# --- Protocol constants (docs/PROTOCOL.md) ---------------------------------------------------------

VERSION = 0
ROLE_R, ROLE_B = 0x52, 0x42  # 'R', 'B'
DIR_B_TO_R, DIR_R_TO_B = 1, 2
CONTROL_CHANNEL, MEDIA_CHANNEL, BULK_CHANNEL, LINK_MEDIA_CHANNEL = 0, 1, 2, 3
F_PAIR_HELLO, F_PAIR_OK, F_PAIR_FAIL, F_HELLO, F_RECORD, F_BULK = 0x01, 0x02, 0x03, 0x10, 0x20, 0x21
F_MEDIA = 0x22  # call audio inside the session (D-072)
FRAME_NAMES = {F_PAIR_HELLO: "PAIR_HELLO", F_PAIR_OK: "PAIR_OK", F_PAIR_FAIL: "PAIR_FAIL",
               F_HELLO: "HELLO", F_RECORD: "RECORD", F_BULK: "BULK", F_MEDIA: "MEDIA"}
MEDIA_MAX_PAYLOAD = 1200      # PROTOCOL.md, "Media channel (UDP)"
REPLAY_WINDOW = 64
FILE_CHUNK = 49152            # PROTOCOL.md, "File transfer"
FILE_MAX = 1 << 30
WIFI_PING_AFTER, WIFI_IDLE_LIMIT = 15, 45
MAX_FRAME = 65535
MAX_PLAINTEXT = MAX_FRAME - 1 - 8 - 16  # frame type, counter, GCM tag
HELLO_LEN = 99
COUNTER_LIMIT = 2 ** 32
CURVE = ec.SECP256R1()

PAIR_TTL = 300           # seconds a pairing secret stays valid
HANDSHAKE_TIMEOUT = 30   # seconds to wait for each pairing/handshake frame
STATUS_EVERY = 60
PING_EVERY = 90
PAGE_BUDGET = 30 * 1024 - 1024  # list items per page; the reply envelope fits in the rest of 30 KB
CONTACTS_PAGE = 13       # 25 sample contacts: a full sync takes 2 pages
CALLLOG_PAGE = 20        # 30 sample entries: 2 pages

ALL_CAPS = ["sms.receive", "sms.send", "sms.history", "call.observe", "call.control", "call.dial",
            "call.dtmf", "call.audio", "contacts", "calllog", "contacts.add", "ussd", "dualsim",
            "calllog.delete", "block", "net.switch", "sms.read", "call.hold", "contacts.edit", "phone.find",
            "phone.ringer"]
CAP_FOR = {"net.switch": "net.switch", "sms.read": "sms.read", "call.hold": "call.hold",
           "contacts.update": "contacts.edit", "phone.find": "phone.find", "phone.ringer": "phone.ringer","sms.threads": "sms.history", "sms.history": "sms.history", "sms.send": "sms.send",
           "call.dial": "call.dial", "call.answer": "call.control", "call.reject": "call.control",
           "call.end": "call.control", "call.dtmf": "call.dtmf", "call.audio": "call.audio",
           "contacts.sync": "contacts", "calllog.sync": "calllog", "contacts.add": "contacts.add",
           "ussd.run": "ussd", "calllog.delete": "calllog.delete", "block.list": "block", "block.set": "block"}
MAX_BLOCKED = 500        # keeps a block.list reply under the 30 KB page size (PROTOCOL.md, D-043)


class ProtocolError(Exception):
    """A protocol violation: the connection is closed."""


class Reject(Exception):
    """A request B refuses; it becomes {"t":"error","id":…,"code":…,"msg":…}."""

    def __init__(self, code, msg):
        super().__init__(msg)
        self.code, self.msg = code, msg


class PairFail(Exception):
    def __init__(self, reason, detail=""):
        super().__init__(detail or reason)
        self.reason = reason


class CommandError(Exception):
    """A bad console/control-socket command."""


# --- Crypto and framing helpers (same construction as protocol/tools/gen_vectors.py) ---------------

def b64u(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def unb64u(s) -> bytes:
    if not isinstance(s, str) or not re.fullmatch(r"[A-Za-z0-9_-]*", s):
        raise ValueError("not base64url without padding")
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def load_private(scalar: bytes):
    return ec.derive_private_key(int.from_bytes(scalar, "big"), CURVE)


def scalar_bytes(key) -> bytes:
    return key.private_numbers().private_value.to_bytes(32, "big")


def pub_bytes(key) -> bytes:
    return key.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)


def load_public(data: bytes):
    """A 65-byte uncompressed X9.62 P-256 point; raises ValueError for anything else."""
    if len(data) != 65 or data[0] != 0x04:
        raise ValueError("not a 65-byte uncompressed P-256 point")
    return ec.EllipticCurvePublicKey.from_encoded_point(CURVE, data)


def ecdh(private, peer_public: bytes) -> bytes:
    return private.exchange(ec.ECDH(), load_public(peer_public))


def hkdf(ikm: bytes, salt: bytes, info: bytes, length: int) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=length, salt=salt, info=info).derive(ikm)


def hmac256(key: bytes, data: bytes) -> bytes:
    return hmac.new(key, data, hashlib.sha256).digest()


def fingerprint(public: bytes) -> str:
    return hashlib.sha256(public).hexdigest()[:16]


def wifi_params(ips, port) -> str:
    """The optional Wi-Fi part of a pair link (PROTOCOL.md, "Pairing over Wi-Fi")."""
    return f"&ip={','.join(ips)}&port={port}" if ips and port else ""


def pairing_uri(device_id: bytes, pub_b: bytes, ps: bytes) -> str:
    return f"pairline://pair?v=0&id={b64u(device_id)}&k={b64u(pub_b)}&s={b64u(ps)}"


def pair_transcript(pub_b: bytes, pub_r: bytes, nonce_r: bytes) -> bytes:
    return b"pairline-pair-v0" + pub_b + pub_r + nonce_r


def check_pair_hello(ps: bytes, pub_b: bytes, body: bytes):
    """Validate a PAIR_HELLO body. Returns (pubR, transcript T); raises PairFail."""
    try:
        m = json.loads(body)
        if not isinstance(m, dict) or m.get("t") != "pair_hello" or type(m.get("v")) is not int or m["v"] != VERSION:
            raise ValueError("not a v0 pair_hello")
        pub_r, nonce_r, mac_r = unb64u(m.get("k")), unb64u(m.get("n")), unb64u(m.get("mac"))
        if len(nonce_r) != 32 or len(mac_r) != 32:
            raise ValueError("n and mac must be 32 bytes")
        load_public(pub_r)
    except ValueError as e:  # includes JSON and UTF-8 errors
        raise PairFail("bad_mac", f"malformed PAIR_HELLO: {e}") from None
    t = pair_transcript(pub_b, pub_r, nonce_r)
    if not hmac.compare_digest(hmac256(ps, t), mac_r):
        raise PairFail("bad_mac", "PAIR_HELLO MAC mismatch")
    return pub_r, t


def pair_ok_body(ps: bytes, t: bytes, nonce_b: bytes) -> dict:
    return {"t": "pair_ok", "v": VERSION, "n": b64u(nonce_b), "mac": b64u(hmac256(ps, t + nonce_b + b"B"))}


def make_hello(role: int, eph_public: bytes, rand: bytes) -> bytes:
    return bytes([VERSION, role]) + eph_public + rand


def parse_hello(body: bytes, role: int) -> bytes:
    """Check a 99-byte hello body from the peer; returns its ephemeral public key."""
    if len(body) != HELLO_LEN or body[0] != VERSION or body[1] != role:
        raise ProtocolError("bad HELLO (length, version or role)")
    try:
        load_public(body[2:67])
    except ValueError:
        raise ProtocolError("HELLO key is not a valid P-256 point") from None
    return body[2:67]


def bridge_ikm(s_b, e_b, s_r_pub: bytes, e_r_pub: bytes) -> bytes:
    """ikm = ECDH(eB, eR) ‖ ECDH(sB, eR) ‖ ECDH(eB, sR), computed on B's side."""
    return ecdh(e_b, e_r_pub) + ecdh(s_b, e_r_pub) + ecdh(e_b, s_r_pub)


def session_keys(ikm: bytes, hello_r: bytes, hello_b: bytes):
    """Returns (salt, okm, kBR, kRB)."""
    salt = hashlib.sha256(hello_r + hello_b).digest()
    okm = hkdf(ikm, salt, b"pairline v0 session", 64)
    return salt, okm, okm[:32], okm[32:]


def channel_keys(okm: bytes, name: str):
    """Channel keys of a session: "media" or "bulk" (Wi-Fi), "link media" (the debug link standing in for Bluetooth,
    D-072). Returns (B→R, R→B)."""
    k = hkdf(okm, b"", b"pairline v0 " + name.encode(), 64)
    return k[:32], k[32:]


class ReplayWindow:
    """The media channel's 64-record sliding replay window (PROTOCOL.md, "Records")."""

    def __init__(self):
        self.top, self.mask = None, 0

    def may_accept(self, counter: int) -> bool:
        if self.top is None or counter > self.top:
            return True
        offset = self.top - counter
        return offset < REPLAY_WINDOW and not (self.mask >> offset) & 1

    def mark(self, counter: int):
        if self.top is None:
            self.top, self.mask = counter, 1
        elif counter > self.top:
            shift = counter - self.top
            self.mask = ((self.mask << shift) | 1) & ((1 << REPLAY_WINDOW) - 1) if shift < REPLAY_WINDOW else 1
            self.top = counter
        else:
            self.mask |= 1 << (self.top - counter)


def nonce(direction: int, counter: int) -> bytes:
    return struct.pack(">IQ", direction, counter)


def seal(key: bytes, direction: int, counter: int, channel: int, plaintext: bytes) -> bytes:
    return struct.pack(">Q", counter) + AESGCM(key).encrypt(nonce(direction, counter), plaintext, bytes([VERSION, channel]))


def open_record(key: bytes, direction: int, channel: int, wire: bytes):
    """Returns (counter, plaintext); raises InvalidTag or ValueError."""
    if len(wire) < 8 + 16:
        raise ValueError("record too short")
    counter = struct.unpack(">Q", wire[:8])[0]
    return counter, AESGCM(key).decrypt(nonce(direction, counter), wire[8:], bytes([VERSION, channel]))


def tcp_frame(frame: bytes) -> bytes:
    return struct.pack(">I", len(frame)) + frame


async def read_tcp_frame(reader) -> bytes:
    n = struct.unpack(">I", await reader.readexactly(4))[0]
    if n == 0 or n > MAX_FRAME:
        raise ProtocolError(f"bad frame length {n}")
    return await reader.readexactly(n)


# --- Small utilities -------------------------------------------------------------------------------

def now_ms() -> int:
    return int(time.time() * 1000)


def ago_ms(days, hours, minutes) -> int:
    return ((days * 24 + hours) * 60 + minutes) * 60_000


def dumps(obj) -> bytes:
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode()


def norm(addr: str) -> str:
    return re.sub(r"[\s().-]", "", addr or "")


def ascii_digits(s: str) -> str:
    """The digits of s, with Persian and Arabic-Indic digits converted to ASCII."""
    out = []
    for ch in s or "":
        if "0" <= ch <= "9":
            out.append(ch)
        elif "۰" <= ch <= "۹":
            out.append(chr(ord(ch) - 0x06F0 + 48))
        elif "٠" <= ch <= "٩":
            out.append(chr(ord(ch) - 0x0660 + 48))
    return "".join(out)


def match_key(s: str):
    """The apps' number key: the last 9 digits, or the lowercased text for short codes and sender names."""
    d = ascii_digits(s)
    if len(d) >= 5:
        return d[-9:]
    t = (s or "").strip().lower()
    return "t:" + t if t else None


def block_matches(blocked: str, number: str) -> bool:
    """PROTOCOL.md (D-043): 7+ digits on both sides compare their endings (up to 9), else exact match keys."""
    a, b = ascii_digits(blocked), ascii_digits(number)
    if len(a) >= 7 and len(b) >= 7:
        n = min(9, len(a), len(b))
        return a[-n:] == b[-n:]
    ka = match_key(blocked)
    return ka is not None and ka == match_key(number)


def log(text: str):
    t = time.time()
    print(f"{time.strftime('%H:%M:%S', time.localtime(t))}.{int(t * 1000) % 1000:03d}  {text}", flush=True)


def describe(msg: dict) -> str:
    """One concise log line for a message: type, id and sizes, never bodies, numbers or names."""
    out = [str(msg.get("t"))]
    for k in ("id", "call", "cid", "state", "reason", "code", "key", "ok", "more"):
        if k in msg and not isinstance(msg[k], (dict, list)):
            out.append(f"{k}={json.dumps(msg[k], ensure_ascii=False)}")
    for k in ("threads", "msgs", "contacts", "deleted", "calls"):
        if isinstance(msg.get(k), list):
            out.append(f"{k}={len(msg[k])}")
    if isinstance(msg.get("msg"), dict):
        out.append(f"msg={msg['msg'].get('key')}")
    return " ".join(out)


def is_int(v) -> bool:
    return isinstance(v, int) and not isinstance(v, bool)


def arg_int(msg, key, default=None, lo=None, hi=None):
    v = msg.get(key)
    if v is None:
        return default
    if not is_int(v):
        raise Reject("invalid", f"{key} must be an integer")
    if (lo is not None and v < lo) or (hi is not None and v > hi):
        raise Reject("invalid", f"{key} must be in {lo}..{hi if hi is not None else ''}")
    return v


def arg_str(msg, key) -> str:
    v = msg.get(key)
    if not isinstance(v, str) or not v.strip():
        raise Reject("invalid", f"{key} must be a non-empty string")
    return v


def arg_bool(msg, key):
    v = msg.get(key)
    if v is not None and not isinstance(v, bool):
        raise Reject("invalid", f"{key} must be true, false or absent")
    return v


def fit(items, limit, size=lambda it: len(dumps(it))):
    """Take up to `limit` items whose JSON fits in one page. Returns (page, more)."""
    page, total = [], 0
    for it in items:
        n = size(it) + 1
        if len(page) >= limit or (page and total + n > PAGE_BUDGET):
            break
        page.append(it)
        total += n
    return page, len(page) < len(items)


# --- Sample data (all made up) ----------------------------------------------------------------------

SIM_SLOTS = [
    {"slot": 0, "carrier": "Irancell", "label": "SIM 1", "number": "+989121234567", "signal": 3, "net": "LTE",
     "roaming": False, "defaultSms": True, "defaultVoice": True, "defaultData": True},
    {"slot": 1, "carrier": "Vodafone", "label": "SIM 2", "number": None, "signal": 2, "net": "5G",
     "roaming": False, "defaultSms": False, "defaultVoice": False, "defaultData": False},
]

BANK = "72975"
SAMPLE_CONTACTS = [  # name, starred, [(number, label)], [emails]
    ("Alice Johnson", True, [("+15551230001", "mobile")], ["alice.j@example.com"]),
    ("Bob Smith", False, [("+15551230002", "mobile"), ("+15552220002", "work")], ["bob@example.com"]),
    ("Mom", True, [("+15551230010", "mobile"), ("+15553330010", "home")], []),
    ("Dad", True, [("+15551230012", "mobile")], []),
    ("David Miller", True, [("+15551230011", "mobile")], ["david.m@example.com"]),
    ("Sara Ahmadi", False, [("+989351112233", "mobile")], ["sara.ahmadi@example.com"]),
    ("Bright Smile Dental", False, [("+15557770100", "work")], []),
    ("Chen Wei", False, [("+15551230020", "mobile")], ["chen.wei@example.com"]),
    ("Priya Patel", False, [("+15551230021", "mobile"), ("+15554440021", "work")],
     ["priya@example.com", "p.patel@work.example"]),
    ("Marco Rossi", False, [("+393471234567", "mobile")], []),
    ("Emma Wilson", False, [("+15551230023", "mobile")], []),
    ("Liam O'Brien", False, [("+15551230024", "mobile")], ["liam@example.ie"]),
    ("Noah Garcia", False, [("+15551230025", "mobile"), ("+15551230026", "home")], []),
    ("Olivia Brown", True, [("+15551230027", "mobile")], ["olivia.b@example.com"]),
    ("Dr. Hannah Lee", False, [("+15557770101", "work")], ["hlee@clinic.example"]),
    ("Ali Rezaei", False, [("+989121112244", "mobile")], []),
    ("Maryam Karimi", False, [("+989121112255", "mobile")], ["maryam.k@example.com"]),
    ("Grandma", True, [("+15553330099", "home")], []),
    ("Office Front Desk", False, [("+15552220000", "main")], []),
    ("Pizza Corner", False, [("+15556660123", "work")], []),
    ("Sophie Martin", False, [("+33612345678", "mobile")], ["sophie.martin@example.fr"]),
    ("James Taylor", False, [("+15551230028", "mobile")], []),
    ("Ava Thompson", False, [("+15551230029", "mobile")], []),
    ("Lucas Schmidt", False, [("+491701234567", "mobile")], []),
    ("Zoe Clark", False, [], ["zoe@example.com"]),
]

SAMPLE_SMS = [  # address, sim slot, [(days, hours, minutes ago, box, body[, state])]
    ("+15551230001", 0, [  # Alice Johnson
        (9, 20, 10, "in", "Hey! Are we still on for climbing this weekend?"),
        (9, 20, 4, "out", "Yes! Saturday morning works for me"),
        (9, 19, 58, "in", "Great, 9am at the gym on 5th?"),
        (9, 19, 50, "out", "Perfect, see you there"),
        (7, 6, 30, "in", "That was so much fun. My arms are dead \U0001F605"),
        (7, 6, 12, "out", "Same here. Next week again?"),
        (7, 6, 5, "in", "Definitely. Also, did you see the photos I sent?"),
        (7, 3, 40, "out", "Just saw them, they're great!"),
        (2, 22, 15, "in", "Are you free for dinner Thursday? Trying the new Thai place"),
        (2, 21, 50, "out", "Sounds good, what time?"),
        (2, 21, 44, "in", "7:30? I'll book a table"),
        (0, 3, 5, "out", "Running 10 min late, sorry!"),
    ]),
    ("+15551230002", 0, [  # Bob Smith
        (8, 4, 20, "in", "Can you send me the slides from yesterday's meeting?"),
        (8, 4, 2, "out", "Sure, emailing them now"),
        (8, 3, 30, "in", "Got them, thanks!"),
        (5, 9, 0, "in", "Do you want to split an Uber to the airport on Friday?"),
        (5, 8, 41, "out", "Yes please. My flight is at 6:40pm"),
        (5, 8, 35, "in", "Mine's at 7:15, let's leave at 4"),
        (4, 23, 10, "out", "\U0001F44D", "sent"),
    ]),
    ("+15551230010", 0, [  # Mom
        (9, 2, 0, "in", "Did you get home safe?"),
        (9, 1, 52, "out", "Yes, all good. Thanks for dinner!"),
        (6, 12, 30, "in", "Don't forget grandma's birthday on Sunday"),
        (6, 12, 2, "out", "I won't! I'll call her in the morning"),
        (6, 11, 58, "in", "She'll love that ❤️"),
        (3, 7, 15, "in", "Can you pick up some bread on your way tomorrow?"),
        (3, 6, 50, "out", "Sure, anything else?"),
        (3, 6, 47, "in", "Milk and eggs too, thank you sweetie"),
        (1, 5, 20, "in", "Call me when you have a minute"),
        (1, 4, 3, "out", "Will do after work"),
    ]),
    ("+15551230011", 0, [  # David Miller
        (7, 1, 30, "in", "Game night at my place Saturday?"),
        (7, 1, 10, "out", "I'm in. Should I bring anything?"),
        (7, 1, 2, "in", "Snacks. And your copy of Catan"),
        (5, 14, 40, "out", "Who won in the end? I left early"),
        (5, 13, 55, "in", "Me, obviously \U0001F60E"),
        (1, 20, 30, "in", "Happy birthday to grandma! Did you call her?"),
        (1, 20, 12, "out", "Yes, this morning. She was so happy"),
    ]),
    (BANK, 1, [  # bank short code
        (8, 11, 5, "in", "MyBank: A purchase of $54.20 at GROCERY MART was made with your card ending 4821."),
        (6, 4, 18, "in", "MyBank: Your verification code is 482913. Do not share this code with anyone."),
        (4, 10, 0, "in", "MyBank: Your statement for card ending 4821 is ready. Minimum payment $35.00 due Dec 12."),
        (2, 13, 27, "in", "MyBank: A purchase of $12.99 at STREAMFLIX was made with your card ending 4821."),
        (0, 9, 12, "in", "MyBank: Payment of $250.00 received. Thank you."),
    ]),
    ("22000", 1, [  # Google
        (6, 2, 33, "in", "G-551203 is your Google verification code."),
        (1, 8, 47, "in", "G-904417 is your Google verification code."),
    ]),
    ("+15559870000", 1, [  # delivery service
        (3, 15, 0, "in", "QuickShip: Your package 1Z84K2 has shipped. Track: https://qship.example/t/1Z84K2"),
        (2, 8, 30, "in", "QuickShip: Your package 1Z84K2 is out for delivery today between 2-6pm."),
        (2, 8, 22, "out", "Please leave it at the front door"),
        (2, 8, 21, "in", "QuickShip: Thanks! Your delivery instructions have been saved."),
        (2, 2, 18, "in", "QuickShip: Your package 1Z84K2 was delivered at 3:42pm."),
        (0, 7, 40, "in", "QuickShip: Your package 7HQ9X1 has shipped. Estimated delivery: Thursday."),
    ]),
    ("30001234", 0, [  # Iranian service, Persian
        (5, 17, 3, "in", "کد تایید شما: ۷۳۸۲۹۱"),
        (5, 17, 1, "in", "خوش آمدید! حساب شما با موفقیت فعال شد."),
    ]),
    ("+989351112233", 0, [  # Sara Ahmadi
        (4, 16, 0, "in", "Hi! I landed in Tehran, will call you tomorrow"),
        (4, 15, 45, "out", "Welcome home! Say hi to everyone"),
        (3, 21, 10, "in", "سلام! مرسی \U0001F64F"),
        (0, 11, 30, "out", "Let's talk this weekend?"),
        (0, 10, 55, "in", "Sure, Saturday afternoon"),
    ]),
    ("+15557770100", 1, [  # dentist
        (4, 2, 0, "in", "Reminder: your appointment with Dr. Lee is on Monday at 10:00. Reply C to confirm."),
        (4, 1, 30, "out", "C"),
        (4, 1, 29, "in", "Thanks, your appointment is confirmed."),
    ]),
]

SAMPLE_CALLS = [  # days, hours, minutes ago, number, type, duration (s), sim slot
    (9, 22, 5, "+15551230010", "in", 754, 0), (9, 3, 40, "+15551230001", "out", 132, 0),
    (8, 20, 15, "+15559990001", "blocked", 0, 1), (8, 6, 2, "+15551230002", "missed", 0, 0),
    (8, 5, 50, "+15551230002", "out", 47, 0), (7, 23, 0, "+18005550199", "rejected", 0, 1),
    (7, 9, 30, "+15557770100", "in", 95, 1), (6, 21, 12, "+15553330099", "out", 1260, 0),
    (6, 14, 3, "+15551230011", "in", 310, 0), (6, 2, 45, "+15559990002", "missed", 0, 1),
    (5, 22, 30, "+15551230020", "out", 0, 0), (5, 22, 20, "+15551230020", "in", 540, 0),
    (5, 8, 10, "+989351112233", "in", 1820, 0), (5, 1, 5, "+15551230010", "voicemail", 34, 0),
    (4, 19, 40, "+15552220000", "out", 65, 1), (4, 7, 25, "+15551230021", "missed", 0, 1),
    (4, 7, 10, "+15551230021", "out", 202, 1), (3, 23, 50, "+15559990001", "blocked", 0, 1),
    (3, 18, 0, "+15556660123", "out", 88, 0), (3, 4, 33, "+15551230001", "in", 415, 0),
    (2, 21, 0, "+15551230010", "out", 980, 0), (2, 15, 45, "+18005550199", "rejected", 0, 1),
    (2, 6, 20, "+15551230027", "in", 76, 0), (1, 23, 10, "+15551230012", "missed", 0, 0),
    (1, 22, 55, "+15551230012", "out", 640, 0), (1, 9, 0, "+989121112244", "in", 233, 0),
    (1, 2, 30, "+15559990003", "missed", 0, 0), (0, 20, 5, "+15551230011", "out", 19, 0),
    (0, 6, 15, "+15551230001", "in", 302, 0), (0, 1, 2, "+15551230010", "missed", 0, 0),
]


def ussd_text(code: str, sim: int):
    if code == "*140#":
        return ("Your balance is 125,000 IRR. Valid until 2026-12-31." if sim == 0
                else "Your balance is EUR 12.40. Valid until 2026-12-31.")
    if code == "*555#":
        return ("Internet packages:\n1. Daily 1 GB - 15,000 IRR\n2. Weekly 5 GB - 49,000 IRR\n"
                "3. Monthly 30 GB - 189,000 IRR\n0. Back")
    if code == "*#06#":
        return "IMEI1: 356938035643809\nIMEI2: 356938035643817"
    return None


class Store:
    """The bridge phone's SMS, contacts and call log, made up relative to the start time."""

    def __init__(self, single_sim: bool):
        self.epoch = secrets.token_hex(3)  # cursors from an earlier run are foreign
        self.msgs, self.threads, self.times = [], {}, set()
        self.contacts, self.deleted, self.cseq = {}, {}, 0  # key -> (seq, contact); key -> seq
        self.calls, self.lseq = [], 0                       # [(seq, entry)]
        self.blocked = []                                   # [{"n": …, "time": …}] (D-043)
        self.next_sms = self.next_contact = 1
        slot = (lambda s: 0) if single_sim else (lambda s: s)
        now = now_ms()
        for name, starred, numbers, emails in SAMPLE_CONTACTS:
            self.add_contact(name, [{"n": n, "label": label} for n, label in numbers], emails, starred)
        rows = []
        for addr, sim, msgs in SAMPLE_SMS:
            self.thread_for(addr)  # thread ids in list order
            for d, h, m, box, body, *state in msgs:
                default = "received" if box == "in" else "delivered"
                rows.append((now - ago_ms(d, h, m), addr, slot(sim), box, body, state[0] if state else default))
        for t, addr, sim, box, body, state in sorted(rows):
            self.add_msg(addr, body, box, sim, state, t)
        for d, h, m, number, kind, dur, sim in sorted(SAMPLE_CALLS, key=lambda c: -ago_ms(*c[:3])):
            self.add_call(number, kind, now - ago_ms(d, h, m), dur, slot(sim))

    # SMS
    def thread_for(self, addr: str) -> int:
        k = norm(addr)
        for tid, addrs in self.threads.items():
            if any(norm(a) == k for a in addrs):
                return tid
        tid = len(self.threads) + 1
        self.threads[tid] = [addr]
        return tid

    def add_msg(self, addr, body, box, sim, state, t=None) -> dict:
        t = t or now_ms()
        while t in self.times:  # unique times keep time-cursor paging lossless
            t += 1
        self.times.add(t)
        m = {"key": f"sms:{self.next_sms}", "thread": self.thread_for(addr), "addr": addr, "body": body,
             "time": t, "box": box, "sim": sim, "state": state}
        self.next_sms += 1
        self.msgs.append(m)
        return m

    # Block list (D-043): a blocked sender's texts and calls aren't shown on the iPhone
    def is_blocked(self, number) -> bool:
        return any(block_matches(e["n"], number or "") for e in self.blocked)

    def hidden(self, m) -> bool:
        return m["box"] == "in" and self.is_blocked(m["addr"])

    def set_blocked(self, add, remove) -> bool:
        before = list(self.blocked)
        self.blocked = [e for e in self.blocked if not any(block_matches(e["n"], r) for r in remove)]
        for number in add:
            if any(block_matches(number, r) for r in remove) or self.is_blocked(number):
                continue
            if len(self.blocked) < MAX_BLOCKED:
                self.blocked.append({"n": number, "time": now_ms()})
        return self.blocked != before

    def delete_calls(self, keys, everything) -> int:
        before = len(self.calls)
        self.calls = [] if everything else [(s, e) for s, e in self.calls if e["key"] not in keys]
        return before - len(self.calls)

    def thread_list(self):
        newest, count = {}, {}
        for m in self.msgs:
            if self.hidden(m):
                continue
            tid = m["thread"]
            count[tid] = count.get(tid, 0) + 1
            if tid not in newest or m["time"] > newest[tid]["time"]:
                newest[tid] = m
        items = [{"thread": tid, "addrs": list(self.threads[tid]), "snippet": m["body"], "time": m["time"],
                  "count": count[tid]} for tid, m in newest.items()]
        return sorted(items, key=lambda x: x["time"], reverse=True)

    def history(self, thread, before, after, limit):
        msgs = [m for m in self.msgs if (thread is None or m["thread"] == thread) and not self.hidden(m)]
        if after is not None:
            msgs = sorted((m for m in msgs if m["time"] > after), key=lambda m: m["time"])
        else:
            msgs = sorted((m for m in msgs if before is None or m["time"] < before), key=lambda m: m["time"], reverse=True)
        return fit(msgs, limit)

    # Contacts and call log, synced by change sequence number
    def add_contact(self, name, numbers, emails=(), starred=False) -> dict:
        self.cseq += 1
        c = {"key": f"c:{self.next_contact}", "name": name, "starred": starred, "numbers": numbers,
             "emails": list(emails)}
        self.next_contact += 1
        self.contacts[c["key"]] = (self.cseq, c)
        return c

    def delete_contact(self, key) -> bool:
        if key not in self.contacts:
            return False
        del self.contacts[key]
        self.cseq += 1
        self.deleted[key] = self.cseq
        return True

    def name_for(self, number):
        k = norm(number)
        for _, c in self.contacts.values():
            if any(norm(n["n"]) == k for n in c["numbers"]):
                return c["name"]
        return None

    def add_call(self, number, kind, t, dur, sim) -> dict:
        self.lseq += 1
        e = {"key": f"cl:{self.lseq}", "number": number, "name": self.name_for(number), "type": kind,
             "time": t, "dur": dur, "sim": sim}
        self.calls.append((self.lseq, e))
        return e

    def cursor(self, kind, seq):
        return f"{kind}.{self.epoch}.{seq}"

    def parse_cursor(self, kind, since):
        if since is None:
            return None
        m = re.fullmatch(rf"{kind}\.([0-9a-f]+)\.(\d+)", since) if isinstance(since, str) else None
        if not m or m.group(1) != self.epoch:
            log(f"  cursor {since!r} is not a {kind}-cursor from this run: sending a full sync")
            return None
        return int(m.group(2))

    def contacts_page(self, since):
        seq0 = self.parse_cursor("c", since)
        changes = [(s, "c", c) for s, c in self.contacts.values() if seq0 is None or s > seq0]
        if seq0 is not None:  # a full sync has nothing to delete
            changes += [(s, "d", k) for k, s in self.deleted.items() if s > seq0]
        changes.sort(key=lambda x: x[0])
        page, more = fit(changes, CONTACTS_PAGE, lambda x: len(dumps(x[2])))
        last = page[-1][0] if more else self.cseq
        return ([x[2] for x in page if x[1] == "c"], [x[2] for x in page if x[1] == "d"],
                self.cursor("c", last), more)

    def calllog_page(self, since):
        seq0 = self.parse_cursor("l", since)
        changes = [(s, e) for s, e in self.calls if (seq0 is None or s > seq0)
                   and not (e["type"] != "out" and self.is_blocked(e["number"]))]
        page, more = fit(changes, CALLLOG_PAGE, lambda x: len(dumps(x[1])))
        last = page[-1][0] if more else self.lseq
        return [e for _, e in page], self.cursor("l", last), more


# --- One connection --------------------------------------------------------------------------------

class Link:
    """One TCP connection from the iPhone app: optional pairing, the handshake, then records. `kind` is "tcp" (the
    debug link, standing in for Bluetooth) or "wifi" (the M5 Wi-Fi link: also BULK frames and the media channel)."""

    def __init__(self, bridge, reader, writer, kind="tcp"):
        self.b, self.reader, self.writer, self.kind = bridge, reader, writer, kind
        peer = writer.get_extra_info("peername") or ("?", 0)
        self.peer_ip = peer[0]
        self.name = f"{kind} {peer[0]}:{peer[1]}"
        self.k_tx = self.k_rx = None
        self.tx, self.rx = 0, -1  # next counter to send; last counter accepted
        self.bulk_tx_key = self.bulk_rx_key = self.media_keys = None
        self.bulk_tx, self.bulk_rx = 0, -1
        self.link_media_keys = None  # (B→R, R→B): MEDIA frames on the debug link (D-072)
        self.link_tx, self.link_rx = 0, -1
        self.link_records = self.link_packets = 0
        self.ready = self.closed = False
        self.tasks = []
        self.ping_id, self.pings = 0, {}
        self.last_rx = time.monotonic()

    async def run(self):
        why = "ended"
        try:
            ftype, body = await self.recv(HANDSHAKE_TIMEOUT)
            if ftype == F_PAIR_HELLO:
                log(f"← PAIR_HELLO ({self.kind})")
                ok, reply, detail = self.b.pair(body)
                self.send_frame(F_PAIR_OK if ok else F_PAIR_FAIL, dumps(reply))
                log(f"→ {'PAIR_OK' if ok else 'PAIR_FAIL reason=' + reply['reason']}  ({detail})")
                if not ok:
                    raise ProtocolError("pairing failed")
                ftype, body = await self.recv(HANDSHAKE_TIMEOUT)
            if ftype != F_HELLO:
                raise ProtocolError(f"expected HELLO, got {FRAME_NAMES[ftype]}")
            await self.handshake(body)
            while not self.closed:
                ftype, body = await self.recv(None)
                self.last_rx = time.monotonic()
                if ftype == F_BULK and self.kind == "wifi":
                    self.on_bulk(body)
                elif ftype == F_MEDIA and self.kind != "wifi":
                    self.on_link_media(body)
                elif ftype != F_RECORD:
                    raise ProtocolError(f"unexpected {FRAME_NAMES[ftype]} after the handshake")
                else:
                    self.on_record(body)
        except asyncio.IncompleteReadError:
            why = "the iPhone closed the connection"
        except asyncio.TimeoutError:
            why = "timed out waiting for the iPhone"
        except ProtocolError as e:
            why = str(e)
        except OSError as e:
            why = f"socket error: {e}"
        finally:
            self.close(why)

    async def recv(self, timeout):
        frame = await asyncio.wait_for(read_tcp_frame(self.reader), timeout)
        if frame[0] not in FRAME_NAMES:
            raise ProtocolError(f"unknown frame type 0x{frame[0]:02x}")
        return frame[0], frame[1:]

    async def handshake(self, hello_r):
        log("← HELLO")
        peer = self.b.peer
        if peer is None:
            raise ProtocolError("HELLO but no iPhone is paired; run `pair` and pair the app first")
        e_r = parse_hello(hello_r, ROLE_R)
        e_b = ec.generate_private_key(CURVE)
        hello_b = make_hello(ROLE_B, pub_bytes(e_b), secrets.token_bytes(32))
        self.send_frame(F_HELLO, hello_b)
        log("→ HELLO")
        _, okm, self.k_tx, self.k_rx = session_keys(bridge_ikm(self.b.identity, e_b, peer, e_r), hello_r, hello_b)
        if self.kind == "wifi":
            self.bulk_tx_key, self.bulk_rx_key = channel_keys(okm, "bulk")
            self.media_keys = channel_keys(okm, "media")
        else:
            self.link_media_keys = channel_keys(okm, "link media")
        self.send_record(b"")
        log("→ confirm")
        ftype, body = await self.recv(HANDSHAKE_TIMEOUT)
        if ftype != F_RECORD:
            raise ProtocolError(f"expected the confirm record, got {FRAME_NAMES[ftype]}")
        try:
            counter, plaintext = open_record(self.k_rx, DIR_R_TO_B, CONTROL_CHANNEL, body)
        except (InvalidTag, ValueError):
            raise ProtocolError("the iPhone's confirm doesn't decrypt: not the pinned peer (pair again?)") from None
        if counter != 0 or plaintext:
            raise ProtocolError("bad confirm (counter must be 0, plaintext empty)")
        self.rx, self.ready = 0, True
        log(f"← confirm ok: {self.kind} session ready with iPhone {fingerprint(peer)}")
        self.send({"t": "device.info", "name": "Pixel 8 (fake)", "platform": "android", "os": "15",
                   "app": self.b.app_version, "proto": VERSION})
        self.send(self.b.status())
        # Periodic status goes on the event session only; the Wi-Fi session has its own faster keep-alive.
        self.tasks = [asyncio.create_task(self.every(STATUS_EVERY,
                                                     lambda: self.b.link is self and self.send(self.b.status())))]
        if self.kind == "wifi":
            self.tasks.append(asyncio.create_task(self.every(5, self.wifi_keepalive)))
        else:
            self.tasks.append(asyncio.create_task(self.every(PING_EVERY, self.ping)))
        self.b.session_ready(self)

    def wifi_keepalive(self):
        """PROTOCOL.md, "Wi-Fi": ping after 15 s without receiving anything, close after 45 s of silence."""
        idle = time.monotonic() - self.last_rx
        if idle > WIFI_IDLE_LIMIT:
            self.close(f"nothing received over Wi-Fi for {idle:.0f} s")
        elif idle > WIFI_PING_AFTER:
            self.ping()

    def on_bulk(self, body):
        """A BULK frame: ordered like control records; anything wrong closes the connection."""
        if len(body) < 8 + 16:
            raise ProtocolError("bulk record too short")
        counter = struct.unpack(">Q", body[:8])[0]
        if counter <= self.bulk_rx:
            raise ProtocolError(f"replayed or reordered bulk record (counter {counter})")
        try:
            _, plaintext = open_record(self.bulk_rx_key, DIR_R_TO_B, BULK_CHANNEL, body)
        except InvalidTag:
            raise ProtocolError(f"bulk record {counter} doesn't decrypt") from None
        self.bulk_rx = counter
        self.b.files.on_bulk(self, plaintext)

    def on_link_media(self, body):
        """A MEDIA frame (call audio inside the session, D-072): ordered like control records; anything wrong closes
        the connection. The packets in it are echoed back in one MEDIA frame while `media echo` is on."""
        if len(body) < 8 + 16:
            raise ProtocolError("media record too short")
        counter = struct.unpack(">Q", body[:8])[0]
        if counter <= self.link_rx:
            raise ProtocolError(f"replayed or reordered media record (counter {counter})")
        try:
            _, plaintext = open_record(self.link_media_keys[1], DIR_R_TO_B, LINK_MEDIA_CHANNEL, body)
        except InvalidTag:
            raise ProtocolError(f"media record {counter} doesn't decrypt") from None
        self.link_rx = counter
        packets, at = [], 0
        while at < len(plaintext):
            if len(plaintext) - at < 2:
                log("media (link): a record that doesn't split into packets, ignored")
                return
            n = struct.unpack(">H", plaintext[at:at + 2])[0]
            if n < 3 or n > len(plaintext) - at - 2:
                log("media (link): a record that doesn't split into packets, ignored")
                return
            packets.append(plaintext[at + 2:at + 2 + n])
            at += 2 + n
        self.link_records += 1
        self.link_packets += len(packets)
        if self.link_records in (1, 10, 50) or self.link_records % 500 == 0:
            kinds = sorted({p[0] for p in packets})
            log(f"media (link): {self.link_records} record(s), {self.link_packets} packet(s) from the iPhone "
                f"(kinds {kinds}, {len(plaintext)} B last)")
        if self.b.media.echo:
            self.send_link_media(plaintext)

    def send_link_media(self, plaintext: bytes):
        self.send_frame(F_MEDIA, seal(self.link_media_keys[0], DIR_B_TO_R, self.link_tx, LINK_MEDIA_CHANNEL, plaintext))
        self.link_tx += 1

    def send_bulk(self, plaintext: bytes):
        self.send_frame(F_BULK, seal(self.bulk_tx_key, DIR_B_TO_R, self.bulk_tx, BULK_CHANNEL, plaintext))
        self.bulk_tx += 1

    def on_record(self, body):
        if len(body) < 8 + 16:
            raise ProtocolError("record too short")
        counter = struct.unpack(">Q", body[:8])[0]
        if counter <= self.rx:
            raise ProtocolError(f"replayed or reordered record (counter {counter}, last accepted {self.rx})")
        if counter >= COUNTER_LIMIT:
            raise ProtocolError("counter limit reached; reconnect to rekey")
        try:
            _, plaintext = open_record(self.k_rx, DIR_R_TO_B, CONTROL_CHANNEL, body)
        except InvalidTag:
            raise ProtocolError(f"record {counter} doesn't decrypt") from None
        self.rx = counter
        try:
            msg = json.loads(plaintext)
        except ValueError:
            log(f"← record {counter}: not UTF-8 JSON, ignored")
            return
        if not isinstance(msg, dict) or not isinstance(msg.get("t"), str):
            log(f"← record {counter}: not a JSON object with a string \"t\", ignored")
            return
        self.b.on_message(self, msg)

    def send_frame(self, ftype, body):
        frame = bytes([ftype]) + body
        if len(frame) > MAX_FRAME:
            raise ValueError("frame too large")
        self.writer.write(tcp_frame(frame))

    def send_record(self, plaintext: bytes):
        self.send_frame(F_RECORD, seal(self.k_tx, DIR_B_TO_R, self.tx, CONTROL_CHANNEL, plaintext))
        self.tx += 1

    def send(self, msg) -> bool:
        if not self.ready or self.closed:
            return False
        if self.tx >= COUNTER_LIMIT:
            self.close("counter limit reached; reconnect to rekey")
            return False
        plaintext = dumps(msg)
        if len(plaintext) > MAX_PLAINTEXT:
            log(f"! {msg.get('t')} is {len(plaintext)} bytes, too large for one frame; not sent")
            return False
        self.send_record(plaintext)
        log(f"→ {describe(msg)}  ({len(plaintext)} B{', ' + self.kind if self.b.wifi_port else ''})")
        return True

    async def every(self, seconds, fn):
        while True:
            await asyncio.sleep(seconds)
            fn()

    def ping(self):
        if self.pings:
            log(f"  {len(self.pings)} ping(s) to the iPhone still unanswered")
        self.ping_id += 1
        self.pings[self.ping_id] = time.monotonic()
        self.send({"t": "ping", "id": self.ping_id})

    def on_pong(self, mid):
        started = self.pings.pop(mid, None) if is_int(mid) else None
        log(f"  pong round trip {1000 * (time.monotonic() - started):.0f} ms" if started else "  pong for no ping")

    def close(self, why):
        if self.closed:
            return
        was_ready = self.ready
        self.closed, self.ready = True, False
        for task in self.tasks:
            task.cancel()
        self.writer.close()
        if was_ready:
            self.b.session_closed(self)
        log(f"connection {self.name} closed: {why}")


# --- Wi-Fi: media channel and files (M5) ----------------------------------------------------------

def plain_ip(ip: str) -> str:
    return ip[7:] if ip.startswith("::ffff:") else ip


class MediaServer(asyncio.DatagramProtocol):
    """The UDP media channel (PROTOCOL.md, "Media channel (UDP)") of the current Wi-Fi session: authenticates with its
    media keys and a 64-record replay window, learns the iPhone's address from authentic datagrams, answers probes
    with probes and (by default) echoes every packet back, so the app's MediaChannel can be checked end to end."""

    def __init__(self, bridge):
        self.b, self.transport, self.echo = bridge, None, True
        self.reset(None)

    def reset(self, link):
        self.link = link
        self.keys = link.media_keys if link else None
        self.window, self.tx, self.peer, self.open = ReplayWindow(), 0, None, False
        self.packets = self.echoed = self.dropped = self.probes = 0
        self.probe_times = []

    def connection_made(self, transport):
        self.transport = transport

    def datagram_received(self, data, addr):
        link = self.link
        if not self.keys or link is None or not link.ready or plain_ip(addr[0]) != plain_ip(link.peer_ip):
            self.dropped += 1
            return
        if not 24 <= len(data) <= MEDIA_MAX_PAYLOAD + 24:
            self.dropped += 1
            return
        counter = struct.unpack(">Q", data[:8])[0]
        if counter >= COUNTER_LIMIT or not self.window.may_accept(counter):
            self.dropped += 1
            return
        try:
            _, plaintext = open_record(self.keys[1], DIR_R_TO_B, MEDIA_CHANNEL, data)
        except (InvalidTag, ValueError):
            self.dropped += 1
            return
        self.window.mark(counter)
        self.peer = addr
        if not self.open:
            self.open = True
            log("media: channel open (first authentic datagram from the iPhone)")
        if not plaintext:
            self.probes += 1
            now = time.monotonic()
            self.probe_times = [t for t in self.probe_times if now - t < 1] + [now]
            if len(self.probe_times) <= 10:  # at most 10 probe answers per second
                self.send(b"")
            return
        self.packets += 1
        if self.packets in (1, 10, 50) or self.packets % 500 == 0:
            log(f"media: {self.packets} packet(s) from the iPhone ({len(plaintext)} B last)")
        if self.echo:
            self.send(plaintext)
            self.echoed += 1

    def send(self, payload: bytes) -> bool:
        if self.peer is None or self.transport is None or self.keys is None or self.tx >= COUNTER_LIMIT:
            return False
        self.transport.sendto(seal(self.keys[0], DIR_B_TO_R, self.tx, MEDIA_CHANNEL, payload), self.peer)
        self.tx += 1
        return True

    def summary(self) -> str:
        if self.link is None:
            return "media: no Wi-Fi session"
        return (f"media: {'open' if self.open else 'waiting for the first probe'}; from iPhone {self.packets} packets, "
                f"{self.probes} probes, {self.dropped} dropped; sent {self.tx}; echo {'on' if self.echo else 'off'}")


class FileTransfers:
    """Files both ways over the Wi-Fi session (PROTOCOL.md, "File transfer"): file.* messages, data in BULK records."""

    def __init__(self, bridge, folder):
        self.b, self.folder = bridge, Path(folder).expanduser()
        self.incoming, self.outgoing = {}, {}  # fid -> state
        self.next_fid = secrets.randbelow(1000) + 1

    def on_message(self, link, msg) -> bool:
        t, fid = msg["t"], msg.get("fid")
        if t not in ("file.offer", "file.answer", "file.done", "file.cancel"):
            return False
        if not is_int(fid) or not 0 <= fid <= 0xFFFFFFFF:
            log(f"  {t} without a valid fid ignored")
            return True
        if t == "file.offer":
            self.offered(link, msg, fid)
        elif t == "file.answer":
            out = self.outgoing.get(fid)
            if out and msg.get("accept") is True:
                log(f"  the iPhone accepted file {fid}; sending {out['size']} bytes")
                out["task"] = asyncio.create_task(self.stream(link, fid))
            elif out:
                log(f"  the iPhone declined file {fid}: {msg.get('reason')}")
                self.outgoing.pop(fid, None)
        elif t == "file.done":
            out = self.outgoing.pop(fid, None)
            if out:
                log(f"  file {fid} {'delivered' if msg.get('ok') else 'failed: ' + str(msg.get('reason'))} "
                    f"(sha256 {out['sha']})")
        elif t == "file.cancel":
            item = self.incoming.pop(fid, None)
            if item:
                item["f"].close()
                item["path"].unlink(missing_ok=True)
                log(f"  the iPhone cancelled file {fid}")
        return True

    def offered(self, link, msg, fid):
        name, size = msg.get("name"), msg.get("size")
        if not isinstance(name, str) or not is_int(size) or not 0 <= size <= FILE_MAX:
            link.send({"t": "file.answer", "fid": fid, "accept": False, "reason": "too_large"})
            return
        safe = re.sub(r"[/\\\x00-\x1f]", "", name).lstrip(".") or "file"
        self.folder.mkdir(parents=True, exist_ok=True)
        path = self.folder / safe
        n = 2
        while path.exists():
            path = self.folder / f"{Path(safe).stem} ({n}){Path(safe).suffix}"
            n += 1
        self.incoming[fid] = {"path": path, "f": open(path, "wb"), "size": size, "got": 0, "sha": hashlib.sha256()}
        link.send({"t": "file.answer", "fid": fid, "accept": True})
        log(f"  receiving file {fid}: {size} bytes -> {path}")
        if size == 0:
            self.finish(link, fid)

    def on_bulk(self, link, plaintext):
        if len(plaintext) < 14 or plaintext[0] != 0x01:
            return  # unknown bulk records are ignored
        fid, offset = struct.unpack(">IQ", plaintext[1:13])
        item = self.incoming.get(fid)
        if item is None:
            return
        data = plaintext[13:]
        if offset != item["got"] or item["got"] + len(data) > item["size"]:
            log(f"  file {fid}: bad offset or too much data")
            self.incoming.pop(fid)
            item["f"].close()
            item["path"].unlink(missing_ok=True)
            link.send({"t": "file.done", "fid": fid, "ok": False, "reason": "failed"})
            return
        item["f"].write(data)
        item["sha"].update(data)
        item["got"] += len(data)
        if item["got"] == item["size"]:
            self.finish(link, fid)

    def finish(self, link, fid):
        item = self.incoming.pop(fid)
        item["f"].close()
        link.send({"t": "file.done", "fid": fid, "ok": True})
        log(f"  received file {fid}: {item['size']} bytes, sha256 {item['sha'].hexdigest()} -> {item['path']}")

    def send(self, link, path: Path) -> str:
        data = path.read_bytes()
        if len(data) > FILE_MAX:
            raise CommandError("files up to 1 GiB")
        fid = self.next_fid
        self.next_fid += 1
        self.outgoing[fid] = {"data": data, "size": len(data), "sha": hashlib.sha256(data).hexdigest(), "task": None}
        link.send({"t": "file.offer", "fid": fid, "name": path.name, "size": len(data), "type": None})
        return f"offered file {fid} ({len(data)} bytes, sha256 {self.outgoing[fid]['sha']})"

    async def stream(self, link, fid):
        out = self.outgoing.get(fid)
        offset = 0
        while out is not None and offset < out["size"] and link.ready:
            chunk = out["data"][offset:offset + FILE_CHUNK]
            link.send_bulk(bytes([0x01]) + struct.pack(">IQ", fid, offset) + chunk)
            offset += len(chunk)
            await link.writer.drain()
        log(f"  file {fid}: all {offset} bytes written; waiting for file.done")

    def cancel(self, link) -> str:
        if not self.outgoing:
            return "nothing being sent"
        fid = next(iter(self.outgoing))
        out = self.outgoing.pop(fid)
        if out["task"]:
            out["task"].cancel()
        link.send({"t": "file.cancel", "fid": fid, "reason": "cancelled"})
        return f"cancelled file {fid}"

    def session_closed(self, link):
        for fid, item in list(self.incoming.items()):
            item["f"].close()
            item["path"].unlink(missing_ok=True)
            log(f"  file {fid} failed: the Wi-Fi session closed")
        self.incoming.clear()
        for out in self.outgoing.values():
            if out["task"]:
                out["task"].cancel()
        self.outgoing.clear()


def lan_address() -> str | None:
    """This Mac's private address on the default route (no packet is sent)."""
    import socket
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        ip = s.getsockname()[0]
    except OSError:
        return None
    finally:
        s.close()
    first, second = (int(x) for x in ip.split(".")[:2])
    private = first == 10 or (first == 172 and 16 <= second <= 31) or (first, second) in ((192, 168), (169, 254))
    return ip if private else None


# --- The fake bridge phone -------------------------------------------------------------------------

HELP = """commands (console or control socket):
  help                         this list
  pair                         new pairing link (valid 5 min, single use)
  status                       show the bridge state and push a status message
  sms <addr> <text...>         incoming SMS -> sms.new
  otp                          incoming bank SMS with a random 6-digit code
  call <addr> [name...]        incoming call -> call.incoming + call.state ringing
  answer                       answer the ringing call on the Android side -> active
  hangup                       the other party hangs up -> ended (remote)
  missed                       the ringing call stops ringing -> ended (missed)
  battery <0-100> [charging]   set the battery -> status
  signal <slot> <0-4>          set a SIM's signal -> status
  caps all|none | caps a,b,c | caps -sms.send,+ussd   set capabilities -> status
  version <x.y.z>              bridge app version -> status + device.info
  delcontact <key>             delete a contact (shows in contacts.sync "deleted")
  block <addr> | unblock <addr>   change the block list on the bridge -> block.list event (D-043)
  blocks                       show the block list
  calls                        the last 10 call-log entries (to see what calllog.delete removed)
  unpair                       send unpair, close and forget the iPhone
  disconnect [tcp|wifi]        close the connections (or just one)
  offer                        send wifi.offer again (Wi-Fi mode)
  sendfile <path>              offer a file to the iPhone over Wi-Fi (file.offer, then BULK records)
  cancelfile                   cancel the file being sent
  media [echo on|off|burst]    media channel state; echo on/off; burst sends 20 packets to the iPhone
  quit                         stop the fake bridge"""


class Bridge:
    def __init__(self, args):
        self.state_path = Path(args.state).expanduser()
        self.identity, self.device_id, self.peer = self.load_state()
        self.pub = pub_bytes(self.identity)
        self.store = Store(args.single_sim)
        self.sims = [dict(s) for s in SIM_SLOTS[:1 if args.single_sim else 2]]
        self.all_caps = [c for c in ALL_CAPS if not (args.single_sim and c == "dualsim")]
        self.wifi_port = args.wifi_port or None
        if self.wifi_port:
            self.all_caps += ["wifi", "files"]
        self.caps = list(self.all_caps)
        self.battery = {"level": 82, "charging": False}
        # status.net (D-073): the bridge phone's own hotspot, Bluetooth tethering and mobile data, as net.switch sets.
        self.net = {"hotspot": False, "bluetooth": False, "data": True}
        self.ringer = "normal"  # status.ringer (D-074)
        self.app_version = args.app_version
        self.pairing = None
        # Debug TCP stands in for Bluetooth; "wifi" is the M5 Wi-Fi link. One established session per transport.
        self.links = {"tcp": None, "wifi": None}
        if args.wifi_offer_ip == "auto":
            self.wifi_ips = [ip for ip in [lan_address()] if ip]
        else:
            self.wifi_ips = [ip for ip in (args.wifi_offer_ip or "").split(",") if ip]
        self.hotspot = args.hotspot.split(":", 1) if args.hotspot else None
        self.media = MediaServer(self)
        self.media_port = None
        self.files = FileTransfers(self, args.files_dir)
        self.calls, self.call_seq = {}, 0
        self.ussd_busy = False
        self.followups, self.in_request = [], False
        self.stopped = asyncio.Event()
        self.handlers = {
            "status.get": self.req_status, "ping": self.req_ping, "sms.threads": self.req_sms_threads,
            "sms.history": self.req_sms_history, "sms.send": self.req_sms_send, "call.dial": self.req_call_dial,
            "call.answer": self.req_call_answer, "call.reject": self.req_call_reject, "call.end": self.req_call_end,
            "call.dtmf": self.req_call_dtmf, "call.audio": self.req_call_audio,
            "contacts.sync": self.req_contacts_sync, "calllog.sync": self.req_calllog_sync,
            "contacts.add": self.req_contacts_add, "ussd.run": self.req_ussd_run,
            "calllog.delete": self.req_calllog_delete, "block.list": self.req_block_list,
            "block.set": self.req_block_set, "net.switch": self.req_net_switch,
            "sms.read": self.req_sms_read, "call.hold": self.req_call_hold, "contacts.update": self.req_contacts_update,
            "phone.find": self.req_phone_find, "phone.ringer": self.req_phone_ringer,
        }

    # State file
    def load_state(self):
        p = self.state_path
        if p.exists():
            try:
                d = json.loads(p.read_text())
                key = load_private(bytes.fromhex(d["identity"]))
                device_id = bytes.fromhex(d["device_id"])
                peer = bytes.fromhex(d["peer"]) if d.get("peer") else None
                if len(device_id) != 8:
                    raise ValueError("device_id must be 8 bytes")
                if peer:
                    load_public(peer)
                return key, device_id, peer
            except (ValueError, KeyError, TypeError) as e:
                sys.exit(f"can't use state file {p}: {e!r}. Fix it or start with --reset.")
        key, device_id = ec.generate_private_key(CURVE), secrets.token_bytes(8)
        self.write_state(key, device_id, None)
        return key, device_id, None

    def write_state(self, key, device_id, peer):
        p = self.state_path
        p.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        data = {"note": "fake_bridge.py dev state: B identity key, device id, pinned iPhone key",
                "identity": scalar_bytes(key).hex(), "device_id": device_id.hex(), "peer": peer.hex() if peer else None}
        tmp = p.with_name(p.name + ".tmp")
        with os.fdopen(os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as f:
            json.dump(data, f, indent=2)
            f.write("\n")
        os.replace(tmp, p)

    def forget_peer(self, why):
        self.peer = None
        self.write_state(self.identity, self.device_id, None)
        log(f"forgot the paired iPhone ({why})")

    @property
    def link(self):
        """The event session (PROTOCOL.md, "Two sessions at once"): debug TCP (for Bluetooth) when up, else Wi-Fi."""
        tcp, wifi = self.links["tcp"], self.links["wifi"]
        if tcp and tcp.ready:
            return tcp
        if wifi and wifi.ready:
            return wifi
        return tcp or wifi

    # Pairing
    def new_pairing(self) -> str:
        ps = secrets.token_bytes(32)
        self.pairing = {"ps": ps, "created": time.monotonic(), "used": False}
        uri = pairing_uri(self.device_id, self.pub, ps) + wifi_params(self.wifi_ips, self.wifi_port)
        if self.hotspot:
            uri += f"&ssid={b64u(self.hotspot[0].encode())}&pw={b64u(self.hotspot[1].encode())}"
        return uri

    def wifi_offer(self) -> dict:
        ssid, password = self.hotspot if self.hotspot else (None, None)
        return {"t": "wifi.offer", "ip": self.wifi_ips, "port": self.wifi_port, "ssid": ssid, "password": password,
                "band": None}

    def session_ready(self, link):
        """After device.info and status on a new session: wifi.media on Wi-Fi, wifi.offer on the event session."""
        if link.kind == "wifi":
            self.media.reset(link)
            link.send({"t": "wifi.media", "port": self.media_port})
        if self.wifi_port and self.link is link:
            link.send(self.wifi_offer())

    def session_closed(self, link):
        if link.kind == "wifi" and self.media.link is link:
            self.media.reset(None)
            self.files.session_closed(link)

    @staticmethod
    def banner(uri) -> str:
        bar = "=" * 78
        return (f"{bar}\nPAIRING LINK (valid 5 minutes, single use):\n\n  {uri}\n\n"
                f"Pair the Simulator's DEBUG build:\n\n  xcrun simctl openurl booted \"{uri}\"\n{bar}")

    def pair(self, body):
        """Handle PAIR_HELLO. Returns (ok, reply JSON, detail for the log)."""
        p = self.pairing
        try:
            if p is None:
                raise PairFail("expired", "no pairing link; run `pair`")
            if p["used"]:
                raise PairFail("used", "this pairing link was already used; run `pair`")
            if time.monotonic() - p["created"] > PAIR_TTL:
                raise PairFail("expired", "the pairing link is older than 5 minutes; run `pair`")
            pub_r, t = check_pair_hello(p["ps"], self.pub, body)
        except PairFail as e:
            return False, {"t": "pair_fail", "v": VERSION, "reason": e.reason}, str(e)
        reply = pair_ok_body(p["ps"], t, secrets.token_bytes(32))
        p.update(ps=None, used=True)
        self.peer = pub_r
        self.write_state(self.identity, self.device_id, pub_r)
        return True, reply, f"paired; pinned iPhone {fingerprint(pub_r)}"

    # Connections
    async def on_connect(self, reader, writer, kind="tcp"):
        link = Link(self, reader, writer, kind)
        if kind == "wifi" and not plain_ip(link.peer_ip).startswith(("127.", "10.", "192.168.", "169.254.", "fe80", "fd", "fc")) \
                and not re.match(r"172\.(1[6-9]|2\d|3[01])\.", plain_ip(link.peer_ip)) and link.peer_ip != "::1":
            log(f"refused a Wi-Fi connection from outside the local network ({link.peer_ip})")
            writer.close()
            return
        old = self.links[kind]
        if old:
            old.close("replaced by a new connection")
        self.links[kind] = link
        log(f"connection from {link.name}")
        try:
            await link.run()
        finally:
            if self.links[kind] is link:
                self.links[kind] = None

    async def on_connect_wifi(self, reader, writer):
        await self.on_connect(reader, writer, "wifi")

    def send_event(self, ev):
        if self.link and self.link.ready:
            self.link.send(ev)
        else:
            log(f"  (no session: {ev['t']} not delivered)")

    def emit(self, ev):
        """Send an event now, or right after the reply when inside a request handler."""
        if self.in_request:
            self.followups.append(ev)
        else:
            self.send_event(ev)

    def later(self, seconds, fn, *args):
        asyncio.get_running_loop().call_later(seconds, fn, *args)

    def status(self) -> dict:
        return {"t": "status", "battery": dict(self.battery), "sims": [dict(s) for s in self.sims],
                "app": self.app_version, "caps": list(self.caps), "net": dict(self.net), "ringer": self.ringer,
                "time": now_ms()}

    def push_status(self):
        if self.link and self.link.ready:
            self.link.send(self.status())

    # Messages from the iPhone
    def on_message(self, link, msg):
        t, mid = msg["t"], msg.get("id")
        log(f"← {describe(msg)}{'  (' + link.kind + ')' if self.wifi_port else ''}")
        if t == "pong":
            link.on_pong(mid)
            return
        if t == "device.info":
            log(f"  iPhone: {msg.get('name')} {msg.get('platform')} {msg.get('os')}, "
                f"app {msg.get('app')}, proto {msg.get('proto')}")
            return
        if t == "unpair":
            self.forget_peer("the iPhone sent unpair")
            for other in list(self.links.values()):
                if other:
                    other.close("unpaired by the iPhone")
            return
        if link.kind == "wifi" and self.files.on_message(link, msg):
            return
        handler = self.handlers.get(t)
        if handler is None:
            if mid is None:
                log(f"  unknown event {t!r} ignored")
            else:  # Clarifications: unknown request with an id -> error/unsupported
                link.send({"t": "error", "id": mid, "code": "unsupported", "msg": f"unknown request type {t!r}"})
            return
        if mid is None:
            log(f"  {t} without an id ignored")
            return
        if not is_int(mid):
            log("  warning: request ids should be integers")
        self.followups, self.in_request = [], True
        try:
            cap = CAP_FOR.get(t)
            if cap and cap not in self.caps:
                raise Reject("permission", f"capability {cap} is off on the bridge")
            reply = handler(link, msg)
        except Reject as e:
            reply, self.followups = {"t": "error", "code": e.code, "msg": e.msg}, []
        except Exception:
            traceback.print_exc()
            reply, self.followups = {"t": "error", "code": "failed", "msg": "fake bridge bug, see its console"}, []
        finally:
            self.in_request = False
        if reply is not None:
            link.send({"t": reply.pop("t"), "id": mid, **reply})
        for ev in self.followups:
            self.send_event(ev)
        self.followups = []

    def sim_arg(self, msg, default_flag) -> int:
        v = msg.get("sim")
        default = next((s["slot"] for s in self.sims if s[default_flag]), 0)
        if v is None:
            return default
        if not is_int(v) or v not in [s["slot"] for s in self.sims]:
            raise Reject("invalid", f"no SIM in slot {v!r}")
        if v != default and "dualsim" not in self.caps:
            raise Reject("permission", "capability dualsim is off on the bridge")
        return v

    def req_status(self, link, msg):
        return self.status()

    def req_ping(self, link, msg):
        return {"t": "pong"}

    def req_sms_threads(self, link, msg):
        limit, before = arg_int(msg, "limit", 50, lo=1), arg_int(msg, "before")
        page, more = fit([x for x in self.store.thread_list() if before is None or x["time"] < before], limit)
        return {"t": "sms.threads", "threads": page, "more": more}

    def req_sms_history(self, link, msg):
        thread, before, after = arg_int(msg, "thread"), arg_int(msg, "before"), arg_int(msg, "after")
        if before is not None and after is not None:
            raise Reject("invalid", "send before or after, not both")
        page, more = self.store.history(thread, before, after, arg_int(msg, "limit", 50, lo=1, hi=100))
        return {"t": "sms.history", "msgs": page, "more": more}

    def req_sms_send(self, link, msg):
        sim, to, body, cid = self.sim_arg(msg, "defaultSms"), arg_str(msg, "to").strip(), arg_str(msg, "body"), msg.get("cid")
        if re.search(r"[,;]", to):
            raise Reject("invalid", "to must be one address")
        if cid is None or isinstance(cid, (bool, list, dict)):
            raise Reject("invalid", "cid must be a string or number")
        m = self.store.add_msg(to, body, "out", sim, "pending")
        if "fail" in body.lower():
            self.later(1.0, self.sms_state, cid, m, "failed")
        else:
            self.later(1.0, self.sms_state, cid, m, "sent")
            self.later(2.0, self.sms_state, cid, m, "delivered")
        return {"t": "ok"}

    def sms_state(self, cid, m, state):
        m["state"] = state
        ev = {"t": "sms.state", "cid": cid, "state": state, "key": m["key"]}
        if state == "failed":
            ev["error"] = "generic failure"
        self.send_event(ev)

    # Calls
    def new_call(self, direction, number, sim) -> dict:
        self.call_seq += 1
        c = {"id": f"call-{self.call_seq}", "dir": direction, "number": number, "name": self.store.name_for(number),
             "sim": sim, "state": None, "since": None, "start": now_ms(), "answered": None}
        self.calls[c["id"]] = c
        return c

    def set_call(self, c, state, reason=None):
        t = now_ms()
        c["state"], c["since"] = state, t
        if state == "active" and c["answered"] is None:
            c["answered"] = t
        if "call.observe" in self.caps:
            ev = {"t": "call.state", "call": c["id"], "state": state, "dir": c["dir"], "number": c["number"],
                  "name": c["name"], "since": t}
            if reason:
                ev["reason"] = reason
            self.emit(ev)
        if state == "ended":
            del self.calls[c["id"]]
            dur = (t - c["answered"]) // 1000 if c["answered"] else 0
            kind = "out" if c["dir"] == "out" else "in" if c["answered"] else "rejected" if reason == "rejected" else "missed"
            e = self.store.add_call(c["number"], kind, c["start"], dur, c["sim"])
            log(f"  call log += {e['key']} ({kind}, {dur} s)")

    def call_arg(self, msg, need_ringing=False) -> dict:
        cid = msg.get("call")
        c = self.calls.get(cid) if isinstance(cid, (str, int)) and not isinstance(cid, bool) else None
        if c is None:
            raise Reject("invalid", f"no call {cid!r}")
        if need_ringing and not (c["dir"] == "in" and c["state"] == "ringing"):
            raise Reject("invalid", "the call is not ringing")
        return c

    def req_call_dial(self, link, msg):
        sim, number = self.sim_arg(msg, "defaultVoice"), arg_str(msg, "number").strip()
        if not re.fullmatch(r"[0-9+*#,;() .-]+", number):
            raise Reject("invalid", "not a dialable number")
        if self.calls:
            raise Reject("busy", "another call is in progress")
        c = self.new_call("out", number, sim)
        self.set_call(c, "dialing")
        self.later(3.0, self.call_connected, c)
        return {"t": "ok"}

    def call_connected(self, c):
        if self.calls.get(c["id"]) is c and c["state"] == "dialing":
            self.set_call(c, "active")

    def req_call_answer(self, link, msg):
        self.set_call(self.call_arg(msg, need_ringing=True), "active")
        return {"t": "ok"}

    def req_call_reject(self, link, msg):
        self.set_call(self.call_arg(msg, need_ringing=True), "ended", "rejected")
        return {"t": "ok"}

    def req_call_end(self, link, msg):
        c = self.call_arg(msg)
        self.set_call(c, "ended", "rejected" if c["state"] == "ringing" else "local")
        return {"t": "ok"}

    def req_call_dtmf(self, link, msg):
        c, digit = self.call_arg(msg), msg.get("digit")
        if not isinstance(digit, str) or not re.fullmatch(r"[0-9*#A-D]", digit):
            raise Reject("invalid", "digit must be one of 0-9 * # A-D")
        if c["state"] != "active":
            raise Reject("invalid", "the call is not active")
        return {"t": "ok"}

    def req_call_audio(self, link, msg):
        c, mute, speaker = self.call_arg(msg), arg_bool(msg, "mute"), arg_bool(msg, "speaker")
        log(f"  {c['id']} audio: mute={mute} speaker={speaker}")
        return {"t": "ok"}

    # Contacts, call log, USSD
    def req_contacts_sync(self, link, msg):
        since = msg.get("since")
        contacts, deleted, cursor, more = self.store.contacts_page(since)
        # PROTOCOL.md: a null or unknown cursor restarts with a full sync, marked "full".
        full = since is None or self.store.parse_cursor("c", since) is None
        return {"t": "contacts.sync", "contacts": contacts, "deleted": deleted, "cursor": cursor, "more": more,
                "full": full}

    def req_calllog_sync(self, link, msg):
        since = msg.get("since")
        calls, cursor, more = self.store.calllog_page(since)
        full = since is None or self.store.parse_cursor("l", since) is None
        return {"t": "calllog.sync", "calls": calls, "cursor": cursor, "more": more, "full": full}

    def req_calllog_delete(self, link, msg):
        everything = arg_bool(msg, "all") is True
        keys = msg.get("keys") or []
        if not isinstance(keys, list) or not all(isinstance(k, str) for k in keys) or len(keys) > 500:
            raise Reject("invalid", "keys must be a list of at most 500 call-log keys")
        if not everything and not keys:
            raise Reject("invalid", "keys missing")
        n = self.store.delete_calls(set(keys), everything)
        log(f"  deleted {n} call-log entries" + (" (all)" if everything else ""))
        return {"t": "ok"}

    def block_list(self) -> dict:
        return {"t": "block.list", "numbers": list(self.store.blocked)}

    def req_block_list(self, link, msg):
        return self.block_list()

    def req_net_switch(self, link, msg):
        """D-073: switches nothing real; remembers it and shows it in the status that follows."""
        what = msg.get("what")
        if what == "dataSim":
            slot = msg.get("sim")
            if not is_int(slot) or not any(s["slot"] == slot for s in self.sims):
                raise Reject("invalid", "no SIM in that slot")
            for s in self.sims:
                s["defaultData"] = s["slot"] == slot
            log(f"  data SIM is now SIM {slot + 1}")
        elif what in self.net:
            on = msg.get("on")
            if not isinstance(on, bool):
                raise Reject("invalid", "on missing")
            self.net[what] = on
            log(f"  {what} turned {'on' if on else 'off'}")
        else:
            raise Reject("invalid", "unknown switch")
        self.emit(self.status())
        return {"t": "ok"}

    # D-074: read sync, hold, contact edits, Find phone, the ringer. Logged, nothing real happens.
    def req_sms_read(self, link, msg):
        thread = msg.get("thread")
        if not is_int(thread) or thread <= 0:
            raise Reject("invalid", "bad thread")
        log(f"  thread {thread} marked read")
        return {"t": "ok"}

    def req_call_hold(self, link, msg):
        c = self.call_arg(msg)
        if not isinstance(msg.get("hold"), bool):
            raise Reject("invalid", "hold missing")
        self.set_call(c, "held" if msg["hold"] else "active")
        return {"t": "ok"}

    def req_contacts_update(self, link, msg):
        key = msg.get("key")
        if not isinstance(key, str) or not key.startswith("c:"):
            raise Reject("invalid", "bad key")
        changed = [k for k in ("name", "numbers", "starred") if k in msg]
        log(f"  contact {key} edited: {', '.join(changed) or 'nothing'}")
        return {"t": "ok"}

    def req_phone_find(self, link, msg):
        log(f"  {'ringing loudly' if msg.get('on', True) else 'stopped ringing'}")
        return {"t": "ok"}

    def req_phone_ringer(self, link, msg):
        mode = msg.get("mode")
        if mode not in ("normal", "vibrate", "silent"):
            raise Reject("invalid", "unknown mode")
        self.ringer = mode
        log(f"  ringer set to {mode}")
        self.emit(self.status())
        return {"t": "ok"}

    def req_block_set(self, link, msg):
        lists = []
        for key in ("add", "remove"):
            v = msg.get(key) or []
            if not isinstance(v, list) or len(v) > 100 or not all(
                    isinstance(x, str) and 0 < len(x.strip()) <= 64 for x in v):
                raise Reject("invalid", f"{key} must be a list of at most 100 numbers or sender names")
            lists.append([x.strip() for x in v])
        if not lists[0] and not lists[1]:
            raise Reject("invalid", "add or remove missing")
        if self.store.set_blocked(*lists):
            log(f"  block list changed ({len(self.store.blocked)} entries)")
        return self.block_list()

    def req_contacts_add(self, link, msg):
        name, numbers = arg_str(msg, "name").strip(), msg.get("numbers") or []
        if not isinstance(numbers, list) or not all(
                isinstance(x, dict) and isinstance(x.get("n"), str) and x["n"].strip() for x in numbers):
            raise Reject("invalid", 'numbers must be a list of {"n": …, "label": …}')
        c = self.store.add_contact(name, [{"n": x["n"].strip(), "label": x.get("label") if isinstance(
            x.get("label"), str) else "mobile"} for x in numbers])
        log(f"  added contact {c['key']}")
        return {"t": "ok"}

    def req_ussd_run(self, link, msg):
        sim, code = self.sim_arg(msg, "defaultVoice"), arg_str(msg, "code").strip()
        if not re.fullmatch(r"[0-9*#+]+", code):
            raise Reject("invalid", "not a USSD code")
        if self.ussd_busy:
            raise Reject("busy", "a USSD session is already running")
        self.ussd_busy = True
        self.later(1.5, self.ussd_done, link, msg["id"], sim, code)
        return None  # ussd.result with this id follows

    def ussd_done(self, link, mid, sim, code):
        self.ussd_busy = False
        text = ussd_text(code, sim)
        reply = {"t": "ussd.result", "id": mid, "ok": text is not None}
        reply.update({"text": text} if text else {"error": "USSD code not recognized"})
        if link.ready and not link.closed:  # replies follow their request's session
            link.send(reply)
        else:
            log("  (connection gone: ussd.result dropped)")

    # Console / control-socket commands
    def command(self, line: str, source: str) -> str:
        name, _, rest = line.strip().partition(" ")
        if not name:
            return ""
        fn = getattr(self, "cmd_" + name.lower(), None)
        if fn is None:
            return f"unknown command {name!r}; try `help`"
        if source != "stdin":
            log(f"[{source}] {name.lower()}")
        try:
            return fn(rest.strip(), source)
        except CommandError as e:
            return f"error: {e}"

    def cmd_help(self, rest, source):
        return HELP

    def cmd_pair(self, rest, source):
        text = self.banner(self.new_pairing())
        print(text, flush=True)
        return text if source != "stdin" else ""

    def cmd_status(self, rest, source):
        b = self.battery
        calls = ", ".join(f"{c['id']} {c['state']}" for c in self.calls.values()) or "none"
        links = [("session ready with " if link.ready else "handshaking with ") + link.name
                 for link in self.links.values() if link]
        lines = [
            f"paired: {'yes, iPhone ' + fingerprint(self.peer) if self.peer else 'no'}",
            "links: " + ("; ".join(links) or "none") + (f" (events on {self.link.kind})" if self.link else ""),
            self.media.summary() if self.wifi_port else "wifi: off",
            f"battery {b['level']}%{' charging' if b['charging'] else ''}; "
            + "; ".join(f"SIM{s['slot']} {s['carrier']} signal {s['signal']} {s['net']}" for s in self.sims),
            f"caps: {', '.join(self.caps) or '(none)'}",
            f"app {self.app_version}; live calls: {calls}",
            f"data: {len(self.store.threads)} threads, {len(self.store.msgs)} messages, "
            f"{len(self.store.contacts)} contacts, {len(self.store.calls)} call-log entries",
        ]
        self.push_status()
        return "\n".join(lines)

    def incoming_sms(self, addr, body):
        m = self.store.add_msg(addr, body, "in", 0, "received")
        if "sms.receive" not in self.caps:
            return f"stored {m['key']}; sms.receive is off, so no sms.new"
        if "block" in self.caps and self.store.is_blocked(addr):
            return f"stored {m['key']}; the sender is blocked, so no sms.new"
        self.send_event({"t": "sms.new", "msg": m})
        return f"incoming {m['key']} in thread {m['thread']}"

    def cmd_sms(self, rest, source):
        addr, _, text = rest.partition(" ")
        if not addr or not text.strip():
            raise CommandError("usage: sms <addr> <text...>")
        return self.incoming_sms(addr, text.strip())

    def cmd_otp(self, rest, source):
        code = f"{secrets.randbelow(10 ** 6):06d}"
        reply = self.incoming_sms(BANK, f"MyBank: Your verification code is {code}. Do not share this code with anyone.")
        return f"{reply} (code {code})"

    def live_call(self, ringing=False) -> dict:
        for c in self.calls.values():
            if not ringing or (c["dir"] == "in" and c["state"] == "ringing"):
                return c
        raise CommandError("no ringing call" if ringing else "no call in progress")

    def cmd_call(self, rest, source):
        addr, _, name = rest.partition(" ")
        if not addr:
            raise CommandError("usage: call <addr> [name...]")
        if self.calls:
            raise CommandError("a call is already in progress (hangup first)")
        if "block" in self.caps and self.store.is_blocked(addr):
            self.store.add_call(addr, "rejected", now_ms(), 0, 0)
            return "the caller is blocked: declined on the bridge, nothing sent to the iPhone"
        c = self.new_call("in", addr, 0)
        c["name"] = name.strip() or c["name"]
        if "call.observe" in self.caps:
            self.send_event({"t": "call.incoming", "call": c["id"], "sim": c["sim"], "from": addr, "name": c["name"]})
        self.set_call(c, "ringing")
        return f"{c['id']} ringing"

    def cmd_answer(self, rest, source):
        c = self.live_call(ringing=True)
        self.set_call(c, "active")
        return f"{c['id']} active"

    def cmd_hangup(self, rest, source):
        c = self.live_call()
        self.set_call(c, "ended", "remote")
        return f"{c['id']} ended (remote)"

    def cmd_missed(self, rest, source):
        c = self.live_call(ringing=True)
        self.set_call(c, "ended", "missed")
        return f"{c['id']} ended (missed)"

    def cmd_battery(self, rest, source):
        parts = rest.split()
        if not parts or not parts[0].isdigit() or not 0 <= int(parts[0]) <= 100 or parts[1:] not in ([], ["charging"]):
            raise CommandError("usage: battery <0-100> [charging]")
        self.battery = {"level": int(parts[0]), "charging": parts[1:] == ["charging"]}
        self.push_status()
        return f"battery {rest}"

    def cmd_signal(self, rest, source):
        parts = rest.split()
        slots = {str(s["slot"]): s for s in self.sims}
        if len(parts) != 2 or parts[0] not in slots or parts[1] not in list("01234"):
            raise CommandError(f"usage: signal <{'|'.join(slots)}> <0-4>")
        slots[parts[0]]["signal"] = int(parts[1])
        self.push_status()
        return f"SIM{parts[0]} signal {parts[1]}"

    def cmd_caps(self, rest, source):
        if rest in ("all", "none"):
            caps = list(self.all_caps) if rest == "all" else []
        else:
            items = [x.strip() for x in rest.split(",") if x.strip()]
            names = [x.lstrip("+-") for x in items]
            bad = [n for n in names if n not in self.all_caps]
            if not items or bad:
                raise CommandError(f"unknown caps {bad}; known: {', '.join(self.all_caps)}")
            if all(x[0] in "+-" for x in items):
                drop = {x[1:] for x in items if x[0] == "-"}
                add = {x[1:] for x in items if x[0] == "+"}
                caps = [c for c in self.all_caps if (c in self.caps or c in add) and c not in drop]
            else:
                caps = [c for c in self.all_caps if c in names]
        self.caps = caps
        self.push_status()
        return "caps: " + (", ".join(caps) or "(none)")

    def cmd_version(self, rest, source):
        if not re.fullmatch(r"\d+\.\d+\.\d+", rest):
            raise CommandError("usage: version <x.y.z>")
        self.app_version = rest
        if self.link and self.link.ready:
            self.link.send({"t": "device.info", "name": "Pixel 8 (fake)", "platform": "android", "os": "15",
                            "app": rest, "proto": VERSION})
        self.push_status()
        return f"app version {rest}"

    def cmd_block(self, rest, source):
        if not rest:
            raise CommandError("usage: block <addr>")
        if self.store.set_blocked([rest], []):
            self.send_event(self.block_list())  # changed on the bridge itself: the iPhone's copy follows
        return f"blocked; {len(self.store.blocked)} entries"

    def cmd_unblock(self, rest, source):
        if not rest:
            raise CommandError("usage: unblock <addr>")
        if self.store.set_blocked([], [rest]):
            self.send_event(self.block_list())
        return f"unblocked; {len(self.store.blocked)} entries"

    def cmd_blocks(self, rest, source):
        return "\n".join(e["n"] for e in self.store.blocked) or "(no blocked numbers)"

    def cmd_calls(self, rest, source):
        return "\n".join(f"{e['key']} {e['type']} {e['number']}" for _, e in self.store.calls[-10:]) or "(no calls)"

    def cmd_delcontact(self, rest, source):
        if not self.store.delete_contact(rest):
            raise CommandError(f"no contact {rest!r} (keys look like c:5)")
        return f"deleted {rest}"

    def cmd_unpair(self, rest, source):
        link = self.link
        if link and link.ready:
            link.send({"t": "unpair"})
        for other in list(self.links.values()):
            if other:
                other.close("unpair command")
        self.forget_peer("unpair command")
        return "unpaired; run `pair` for a new pairing link"

    def cmd_disconnect(self, rest, source):
        kinds = [rest] if rest in ("tcp", "wifi") else ["tcp", "wifi"]
        closed = []
        for kind in kinds:
            if self.links[kind]:
                self.links[kind].close("disconnect command")
                closed.append(kind)
        return f"disconnected {', '.join(closed)}" if closed else "not connected"

    def wifi_link(self):
        link = self.links["wifi"]
        if not (link and link.ready):
            raise CommandError("no Wi-Fi session")
        return link

    def cmd_offer(self, rest, source):
        if not self.wifi_port:
            raise CommandError("start with --wifi-port")
        if not (self.link and self.link.ready):
            raise CommandError("no session")
        self.link.send(self.wifi_offer())
        return f"wifi.offer sent on {self.link.kind}"

    def cmd_sendfile(self, rest, source):
        path = Path(rest).expanduser()
        if not rest or not path.is_file():
            raise CommandError("usage: sendfile <path to a file>")
        return self.files.send(self.wifi_link(), path)

    def cmd_cancelfile(self, rest, source):
        return self.files.cancel(self.wifi_link())

    def cmd_media(self, rest, source):
        if rest in ("echo on", "echo off"):
            self.media.echo = rest == "echo on"
        elif rest == "burst":
            if not self.media.open:
                raise CommandError("the media channel isn't open")
            sent = sum(self.media.send(b"burst" + struct.pack(">I", n) + bytes(155)) for n in range(20))
            return f"sent {sent} packets to the iPhone"
        elif rest:
            raise CommandError("usage: media [echo on|off|burst]")
        return self.media.summary()

    def cmd_quit(self, rest, source):
        self.stopped.set()
        return "bye"

    async def on_control(self, reader, writer):
        try:
            while not self.stopped.is_set():
                line = await reader.readline()
                if not line:
                    break
                reply = self.command(line.decode("utf-8", "replace"), "ctl")
                writer.write((reply.rstrip("\n") + "\n").encode() if reply else b"")
                await writer.drain()
        except OSError:
            pass
        finally:
            writer.close()


def stdin_loop(loop, bridge):
    """Read console commands in a thread (raw reads, so interpreter shutdown never waits on stdin)."""
    async def run(line):
        return bridge.command(line, "stdin")

    buf = b""
    while True:
        try:
            chunk = os.read(0, 4096)
        except OSError:
            return
        if not chunk:
            if os.isatty(0):  # Ctrl-D in a terminal quits; EOF from a pipe or /dev/null doesn't
                loop.call_soon_threadsafe(bridge.stopped.set)
            return
        buf += chunk
        while b"\n" in buf:
            line, buf = buf.split(b"\n", 1)
            try:
                reply = asyncio.run_coroutine_threadsafe(run(line.decode("utf-8", "replace")), loop).result()
            except RuntimeError:  # loop closed
                return
            if reply:
                print(reply, flush=True)


async def amain(args) -> int:
    bridge = Bridge(args)
    servers = []
    udp = None
    try:
        if not args.no_tcp:
            servers.append(await asyncio.start_server(bridge.on_connect, args.host, args.port))
        if args.wifi_port:
            # TCP and UDP on the same port number (separate port spaces); fails here if either is taken.
            servers.append(await asyncio.start_server(bridge.on_connect_wifi, args.wifi_host, args.wifi_port))
            udp, _ = await asyncio.get_running_loop().create_datagram_endpoint(
                lambda: bridge.media, local_addr=(args.wifi_host, args.wifi_port))
            bridge.media_port = args.wifi_port
        control = (await asyncio.start_server(bridge.on_control, "127.0.0.1", args.control_port)
                   if args.control_port else None)
    except OSError as e:
        print(f"can't listen: {e}", file=sys.stderr)
        return 1
    print(f"Pairline fake bridge (role B, protocol v{VERSION}). Dev tool only; all data is made up.")
    print(f"  state file:  {bridge.state_path}")
    print(f"  identity:    {fingerprint(bridge.pub)}   device id: {b64u(bridge.device_id)}")
    print(f"  link:        {'off (--no-tcp)' if args.no_tcp else f'tcp {args.host}:{args.port} (debug link, stands in for Bluetooth)'}")
    if args.wifi_port:
        print(f"  wifi:        tcp+udp {args.wifi_host}:{args.wifi_port}; offered addresses: "
              f"{', '.join(bridge.wifi_ips) or 'none'}; received files go to {bridge.files.folder}")
        if args.wifi_host not in ("127.0.0.1", "::1"):
            print("  note:        the Wi-Fi listener is reachable from your local network while this runs")
    print(f"  control:     {'tcp 127.0.0.1:%d (nc 127.0.0.1 %d)' % (args.control_port, args.control_port) if control else 'off'}")
    print(f"  paired with: {'iPhone ' + fingerprint(bridge.peer) + ' (the app can reconnect without pairing)' if bridge.peer else 'nobody yet'}")
    print(bridge.banner(bridge.new_pairing()))
    print("Type `help` for commands.", flush=True)
    use_stdin = not args.no_stdin and not (os.isatty(0) and os.tcgetpgrp(0) != os.getpgrp())
    if use_stdin:
        threading.Thread(target=stdin_loop, args=(asyncio.get_running_loop(), bridge), daemon=True).start()
    await bridge.stopped.wait()
    for link in list(bridge.links.values()):
        if link:
            link.close("quitting")
    for server in servers:
        server.close()
    if udp:
        udp.close()
    if control:
        control.close()
    await asyncio.sleep(0.05)  # let the transports flush and close
    return 0


# --- Self-test against protocol/test-vectors/v0.json -----------------------------------------------

def self_test() -> int:
    path = Path(__file__).resolve().parent.parent / "test-vectors" / "v0.json"
    v = json.loads(path.read_text())
    failures = []

    def check(name, got, want):
        ok = got == want
        print(f"{'ok  ' if ok else 'FAIL'} {name}")
        if not ok:
            failures.append(name)

    def must_raise(name, fn, *exc):
        try:
            fn()
        except exc:
            return check(name, True, True)
        check(name, "no error", "error")

    H = bytes.fromhex
    c = v["hkdf_rfc5869_case1"]
    check("HKDF RFC 5869 case 1", hkdf(H(c["ikm"]), H(c["salt"]), H(c["info"]), c["length"]).hex(), c["okm"])
    keys = {n: load_private(H(k["private"])) for n, k in v["keys"].items()}
    P = {n: pub_bytes(k) for n, k in keys.items()}
    for n, k in v["keys"].items():
        check(f"public key {n}", P[n].hex(), k["public"])
    e = v["ecdh"]
    for name, (a, b) in {"eB_eR": ("eB", "eR"), "sB_eR": ("sB", "eR"), "eB_sR": ("eB", "sR")}.items():
        check(f"ECDH {name} (B side)", ecdh(keys[a], P[b]).hex(), e[name])
        check(f"ECDH {name} (R side)", ecdh(keys[b], P[a]).hex(), e[name])

    pr = v["pairing"]
    ps, nonce_r, nonce_b = H(pr["pairing_secret"]), H(pr["nonce_r"]), H(pr["nonce_b"])
    t = pair_transcript(P["sB"], P["sR"], nonce_r)
    check("pairing transcript T", t.hex(), pr["transcript_t"])
    check("pairing macR", hmac256(ps, t).hex(), pr["mac_r"])
    check("pairing URI", pairing_uri(H(pr["device_id"]), P["sB"], ps), pr["qr_uri"])
    check("PAIR_HELLO accepted", check_pair_hello(ps, P["sB"], dumps(pr["pair_hello_json"])), (P["sR"], t))
    must_raise("PAIR_HELLO with a wrong secret rejected",
               lambda: check_pair_hello(b"\0" * 32, P["sB"], dumps(pr["pair_hello_json"])), PairFail)
    ok = pair_ok_body(ps, t, nonce_b)
    check("PAIR_OK body and macB", ok, pr["pair_ok_json"])
    check("pairing macB", unb64u(ok["mac"]).hex(), pr["mac_b"])

    s = v["session"]
    hello_r = make_hello(ROLE_R, P["eR"], H(s["rand_r"]))
    hello_b = make_hello(ROLE_B, P["eB"], H(s["rand_b"]))
    check("hello R", hello_r.hex(), s["hello_r"])
    check("hello B", hello_b.hex(), s["hello_b"])
    check("hello R frame", (bytes([F_HELLO]) + hello_r).hex(), s["hello_r_frame"])
    check("parse hello R", parse_hello(hello_r, ROLE_R), P["eR"])
    must_raise("hello with the wrong role rejected", lambda: parse_hello(hello_b, ROLE_R), ProtocolError)
    must_raise("hello with a bad length rejected", lambda: parse_hello(hello_r[:98], ROLE_R), ProtocolError)
    must_raise("hello with an invalid point rejected",
               lambda: parse_hello(hello_r[:3] + bytes(64) + hello_r[67:], ROLE_R), ProtocolError)
    ikm = bridge_ikm(keys["sB"], keys["eB"], P["sR"], P["eR"])
    check("ikm", ikm.hex(), s["ikm"])
    salt, okm, k_br, k_rb = session_keys(ikm, hello_r, hello_b)
    check("salt", salt.hex(), s["salt"])
    check("okm", okm.hex(), s["okm"])
    check("kBR", k_br.hex(), s["k_br"])
    check("kRB", k_rb.hex(), s["k_rb"])
    media, bulk = channel_keys(okm, "media"), channel_keys(okm, "bulk")
    check("media keys", (media[0].hex(), media[1].hex()), (s["media_k_br"], s["media_k_rb"]))
    check("bulk keys", (bulk[0].hex(), bulk[1].hex()), (s["bulk_k_br"], s["bulk_k_rb"]))
    check("confirm B->R", seal(k_br, DIR_B_TO_R, 0, 0, b"").hex(), s["confirm_b_to_r"])
    check("confirm R->B opens", open_record(k_rb, DIR_R_TO_B, 0, H(s["confirm_r_to_b"])), (0, b""))

    named = {"k_br": k_br, "k_rb": k_rb, "media_k_br": media[0], "media_k_rb": media[1], "bulk_k_br": bulk[0]}
    for r in v["records"]:
        pt = r["plaintext_utf8"].encode() if "plaintext_utf8" in r else H(r["plaintext_hex"])
        check(f"nonce {r['key']} #{r['counter']}", nonce(r["direction"], r["counter"]).hex(), r["nonce"])
        check(f"seal {r['key']} #{r['counter']}", seal(named[r["key"]], r["direction"], r["counter"], r["channel"], pt).hex(), r["wire"])
        check(f"open {r['key']} #{r['counter']}", open_record(named[r["key"]], r["direction"], r["channel"], H(r["wire"])), (r["counter"], pt))
    for r in v["must_fail"]:
        must_raise(f"must fail: {r['why']}",
                   lambda r=r: open_record(named[r["key"]], r["direction"], r["channel"], H(r["wire"])), InvalidTag)

    # M5: media replay window, bulk file data, the Wi-Fi pair link
    w, window, results = v["media_replay_window"], ReplayWindow(), []
    for step in w["steps"]:
        data = H(step["wire"])
        counter = struct.unpack(">Q", data[:8])[0]
        ok = window.may_accept(counter)
        if ok:
            try:
                open_record(named[w["key"]], w["direction"], w["channel"], data)
                window.mark(counter)
            except InvalidTag:
                ok = False
        results.append(ok)
    check("media replay window", results, [s["accept"] for s in w["steps"]])
    bf = v["bulk_file_data"]
    frame = H(bf["frame"])
    check("bulk frame type", frame[0], F_BULK)
    check("bulk record opens", open_record(bulk[0], DIR_B_TO_R, BULK_CHANNEL, frame[1:]), (0, H(bf["plaintext"])))
    check("bulk data layout", struct.unpack(">IQ", H(bf["plaintext"])[1:13]), (bf["fid"], bf["offset"]))
    pw = v["pair_uri_wifi"]
    check("pair link with Wi-Fi", pairing_uri(H(pr["device_id"]), P["sB"], ps) + wifi_params(pw["ip"], pw["port"])
          + f"&ssid={b64u(pw['ssid'].encode())}&pw={b64u(pw['pw'].encode())}", pw["uri"])

    f = v["framing"]
    check("TCP framing (write)", tcp_frame(H(f["frame"])).hex(), f["tcp"])

    async def read(data):
        reader = asyncio.StreamReader()
        reader.feed_data(data)
        reader.feed_eof()
        return await read_tcp_frame(reader)

    check("TCP framing (read)", asyncio.run(read(H(f["tcp"]))).hex(), f["frame"])
    must_raise("TCP frame over 65,535 bytes rejected", lambda: asyncio.run(read(struct.pack(">I", 65536))), ProtocolError)
    must_raise("TCP frame of 0 bytes rejected", lambda: asyncio.run(read(struct.pack(">I", 0))), ProtocolError)

    print(f"\n{'FAILED: ' + ', '.join(failures) if failures else 'all checks passed'}")
    return 1 if failures else 0


def main():
    p = argparse.ArgumentParser(description="Fake Pairline Android bridge (role B) over the debug TCP transport.",
                                epilog="Commands: run it and type `help`.")
    p.add_argument("--state", default="~/.pairline/fake_bridge.json", help="state file (default: %(default)s)")
    p.add_argument("--reset", action="store_true", help="delete the state file first (new identity, no paired iPhone)")
    p.add_argument("--host", default="127.0.0.1", help="listen address (default: %(default)s)")
    p.add_argument("--port", type=int, default=47800, help="link port (default: %(default)s)")
    p.add_argument("--control-port", type=int, default=47801, help="control socket on 127.0.0.1; 0 = off (default: %(default)s)")
    p.add_argument("--app-version", default="0.1.0", help="bridge app version to report (default: %(default)s)")
    p.add_argument("--single-sim", action="store_true", help="one SIM, no dualsim capability")
    p.add_argument("--no-stdin", action="store_true", help="don't read commands from the console")
    p.add_argument("--wifi-port", type=int, default=0,
                   help="also run the M5 Wi-Fi link: TCP and UDP (media) on this port, e.g. 47812; 0 = off")
    p.add_argument("--wifi-host", default="127.0.0.1",
                   help="Wi-Fi listen address (default: %(default)s; 0.0.0.0 to be found by DNS-SD from the LAN)")
    p.add_argument("--wifi-offer-ip", default=None,
                   help="comma-separated addresses for wifi.offer and the pair link, or 'auto' (this Mac's LAN address)")
    p.add_argument("--hotspot", default=None, metavar="SSID:PASSWORD",
                   help="pretend the bridge hotspot is on: ssid/password in wifi.offer and the pair link")
    p.add_argument("--files-dir", default="/tmp/pairline-fake-bridge-files", help="where received files go (default: %(default)s)")
    p.add_argument("--no-tcp", action="store_true",
                   help="no debug TCP link (Wi-Fi only, like a bridge that can't hold a Bluetooth link)")
    p.add_argument("--self-test", action="store_true", help="check the helpers against protocol/test-vectors/v0.json")
    args = p.parse_args()
    if args.self_test:
        sys.exit(self_test())
    if args.reset and Path(args.state).expanduser().exists():
        Path(args.state).expanduser().unlink()
        print(f"--reset: deleted {Path(args.state).expanduser()}")
    try:
        sys.exit(asyncio.run(amain(args)))
    except KeyboardInterrupt:
        print("\nbye")


if __name__ == "__main__":
    main()
