#!/usr/bin/env python3
"""A stand-in for the Android bridge in remote mode (D-092, docs/PROTOCOL.md "Remote mode (v1, D-092)"), for testing
the iPhone without an Android phone: remote pairing from an invitation, sealed events into the iPhone's mailboxes,
and the iPhone's requests answered from the R→B mailbox. Debug/test tool only; it talks to CloudKit Web Services
(api.apple-cloudkit.com) and nothing else.

State (outside the repo, mode 600): ~/.config/pairline/fake-remote-bridge.json (this fake bridge's identity key, the
pinned iPhone key, the mailboxes) and the web auth token chain "fakebridge".

    python3 -I protocol/tools/fake_remote_bridge.py pair 'pairline://invite?...'
    python3 -I protocol/tools/fake_remote_bridge.py sms --from +989121234567 --body 'Your code is 4821'
    python3 -I protocol/tools/fake_remote_bridge.py call --from +989121234567 [--missed]
    python3 -I protocol/tools/fake_remote_bridge.py serve --seconds 300     # answer the iPhone's requests
"""
import argparse
import base64
import json
import os
import secrets
import sys
import time
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cloudkit_mailbox as ckm  # noqa: E402
import gen_vectors_remote as rv  # noqa: E402
from gen_vectors import b64u, pub  # noqa: E402

from cryptography.hazmat.primitives import serialization  # noqa: E402
from cryptography.hazmat.primitives.asymmetric import ec  # noqa: E402

STATE = os.path.join(ckm.CONFIG_DIR, "fake-remote-bridge.json")
CHAIN = "fakebridge"


def from_b64u(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def load_state() -> dict:
    with open(STATE) as f:
        return json.load(f)


def save_state(state: dict):
    ckm.write_secret(STATE, json.dumps(state, indent=1))


def identity(state: dict):
    return serialization.load_pem_private_key(state["identity_pem"].encode(), password=None)


def client(state: dict) -> ckm.CloudKit:
    return ckm.CloudKit(CHAIN, environment=state["env"])


def put(ck: ckm.CloudKit, mailbox: str, sealed: bytes):
    """One record in the public backend: name = record id hex, sealed as base64url text (PROTOCOL.md)."""
    name = sealed[3:19].hex()
    fields = {"mailbox": {"value": mailbox}, "sealed": {"value": b64u(sealed)}}
    body = {"atomic": False, "operations": [{"operationType": "create",
            "record": {"recordType": "Mail", "recordName": name, "fields": fields}}]}
    status, payload, _ = ck.call("records/modify", body, scope="public")
    if status != 200 or any("serverErrorCode" in r for r in payload.get("records", [])):
        raise SystemExit(f"write failed: HTTP {status} {json.dumps(ckm.summarize(payload))[:300]}")


def fetch(ck: ckm.CloudKit, mailbox: str) -> list:
    body = {"query": {"recordType": "Mail", "filterBy": [{"fieldName": "mailbox", "comparator": "EQUALS",
                                                          "fieldValue": {"value": mailbox}}]}, "resultsLimit": 50}
    status, payload, _ = ck.call("records/query", body, scope="public")
    if status != 200:
        raise SystemExit(f"read failed: HTTP {status} {json.dumps(ckm.summarize(payload))[:300]}")
    out = []
    for r in payload.get("records", []):
        text = r.get("fields", {}).get("sealed", {}).get("value")
        if text:
            out.append((r["recordName"], from_b64u(text)))
    return out


def delete(ck: ckm.CloudKit, names: list):
    if names:
        ops = [{"operationType": "forceDelete", "record": {"recordName": n}} for n in names]
        ck.call("records/modify", {"operations": ops}, scope="public")


def now_ms() -> int:
    return int(time.time() * 1000)


def cmd_pair(args):
    query = dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(args.invite).query, keep_blank_values=True))
    if query.get("v") != "1" or query.get("b") != "public":
        raise SystemExit("only v=1 invitations with the public backend")
    if int(query["x"]) < time.time():
        raise SystemExit("the invitation has expired")
    secret, pub_r = from_b64u(query["s"]), from_b64u(query["k"])
    key = ec.generate_private_key(ec.SECP256R1())
    state = {
        "identity_pem": key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                          serialization.NoEncryption()).decode(),
        "device_id": secrets.token_bytes(8).hex(), "iphone_key": b64u(pub_r), "env": query["e"],
        "mb": {"b": query["m"], "a": query["a"], "q": query["q"]}, "paired": False,
    }
    save_state(state)
    os.makedirs(ckm.CONFIG_DIR, mode=0o700, exist_ok=True)
    ckm.write_secret(os.path.join(ckm.CONFIG_DIR, f"ck-webauth-{CHAIN}.token"), ckm.normalize_token(query["t"]))
    ck = client(state)
    pub_b, nonce_b = pub(key), secrets.token_bytes(32)
    hello = {"t": "rpair.hello", "k": b64u(pub_b), "id": b64u(bytes.fromhex(state["device_id"])),
             "c": b64u(rv.commit(pub_r, pub_b, nonce_b))}
    put(ck, state["mb"]["a"], rv.seal_invite(secret, rv.DIR_B, secrets.token_bytes(16), now_ms(),
                                             json.dumps(hello, separators=(",", ":")).encode()))
    print("sent rpair.hello; waiting for the iPhone's nonce…", flush=True)
    nonce_r = None
    deadline = time.time() + 600
    while nonce_r is None and time.time() < deadline:
        time.sleep(3)
        for name, sealed in fetch(ck, state["mb"]["b"]):
            try:
                msg = json.loads(rv.open_invite(secret, sealed))
            except Exception:  # noqa: BLE001 (not an invitation record)
                continue
            delete(ck, [name])
            if msg.get("t") == "rpair.nonce":
                nonce_r = from_b64u(msg["n"])
    if nonce_r is None or len(nonce_r) != 32:
        raise SystemExit("no valid rpair.nonce")
    reveal = {"t": "rpair.reveal", "n": b64u(nonce_b)}
    put(ck, state["mb"]["a"], rv.seal_invite(secret, rv.DIR_B, secrets.token_bytes(16), now_ms(),
                                             json.dumps(reveal, separators=(",", ":")).encode()))
    code = rv.code(secret, pub_r, pub_b, nonce_r, nonce_b)
    print(f"\n    code: {code[:3]} {code[3:]}\n", flush=True)
    if not args.yes and input("Do the codes match? [y/N] ").strip().lower() != "y":
        raise SystemExit("cancelled")
    put(ck, state["mb"]["a"], rv.seal_mailbox(key, pub_r, rv.DIR_B, secrets.token_bytes(16), now_ms(),
                                              b'{"t":"rpair.confirm"}'))
    print("sent rpair.confirm; waiting for the iPhone's…", flush=True)
    deadline = time.time() + 600
    while time.time() < deadline:
        time.sleep(3)
        for name, sealed in fetch(ck, state["mb"]["b"]):
            delete(ck, [name])
            try:
                msg = json.loads(rv.open_mailbox(key, pub_r, sealed))
            except Exception:  # noqa: BLE001
                continue
            if msg.get("t") == "rpair.confirm":
                state["paired"] = True
                save_state(state)
                print("paired")
                return
    raise SystemExit("no rpair.confirm from the iPhone")


def seal_to_iphone(state: dict, message: dict) -> bytes:
    return rv.seal_mailbox(identity(state), from_b64u(state["iphone_key"]), rv.DIR_B, secrets.token_bytes(16),
                           now_ms(), json.dumps(message, ensure_ascii=False, separators=(",", ":")).encode())


def cmd_sms(args):
    state = load_state()
    t = now_ms()
    msg = {"t": "sms.new", "msg": {"key": f"sms:{t}", "thread": args.thread, "addr": args.sender, "body": args.body,
                                   "time": t, "box": "in", "sim": 0, "state": "received"}}
    put(client(state), state["mb"]["a"], seal_to_iphone(state, msg))
    print("sent sms.new to the alert mailbox")


def cmd_call(args):
    state = load_state()
    if args.missed:
        msg = {"t": "call.state", "call": 1, "state": "ended", "dir": "in", "number": args.sender, "name": None,
               "since": now_ms() - 20000, "reason": "missed"}
    else:
        msg = {"t": "call.incoming", "call": 1, "sim": 0, "from": args.sender, "name": None}
    put(client(state), state["mb"]["a"], seal_to_iphone(state, msg))
    print(f"sent {msg['t']} to the alert mailbox")


def fake_status(request_id=None) -> dict:
    """A plausible bridge status (made-up values), as the real bridge would send it."""
    status = {"t": "status", "battery": {"level": 76, "charging": False}, "app": "1.0-fake",
              "caps": ["sms.receive", "sms.send", "sms.history", "call.observe"], "time": now_ms(),
              "sims": [{"slot": 0, "carrier": "Test Carrier", "label": "SIM 1", "number": None, "signal": 3,
                        "net": "LTE", "roaming": False, "defaultSms": True, "defaultVoice": True}]}
    if request_id is not None:
        status["id"] = request_id
    return status


def fake_home_check(request_id):
    """`remote.check`'s reply (PROTOCOL.md, "Remote messages"): a router that answers UPnP with a public address."""
    return {"t": "remote.check", "id": request_id, "direct": "likely", "reason": None, "router": "upnp", "ipv6": False}


def cmd_serve(args):
    """Answers the iPhone's requests from the R→B mailbox: `ok` for most, a status for status.get, a fake sms.state
    for sms.send. Starts by introducing itself (device.info and status), as the real bridge does when remote mode
    starts."""
    state = load_state()
    ck, key, pub_r = client(state), identity(state), from_b64u(state["iphone_key"])
    info = {"t": "device.info", "name": "Test bridge (fake)", "platform": "android", "os": "16", "app": "1.0-fake",
            "proto": 1}
    put(ck, state["mb"]["q"], seal_to_iphone(state, info))
    put(ck, state["mb"]["q"], seal_to_iphone(state, fake_status()))
    print("sent device.info and status to the quiet mailbox", flush=True)
    deadline = time.time() + args.seconds
    while time.time() < deadline:
        for name, sealed in fetch(ck, state["mb"]["b"]):
            delete(ck, [name])
            try:
                msg = json.loads(rv.open_mailbox(key, pub_r, sealed))
            except Exception:  # noqa: BLE001
                print("dropped a record that didn't open")
                continue
            kind = msg.get("t")
            # cid tells a retry (same cid) from a new message; the text itself isn't printed.
            print(f"got {kind} (id {msg.get('id')}, cid {msg.get('cid')}) at {time.strftime('%H:%M:%S')}", flush=True)
            if kind == "remote.token" and msg.get("token"):
                ckm.write_secret(ck.chain_path, ckm.normalize_token(msg["token"]))
            if kind == "status.get":
                put(ck, state["mb"]["q"], seal_to_iphone(state, fake_status(msg.get("id"))))
            elif kind == "remote.check" and msg.get("id") is not None:
                # A canned "Check my home connection" answer; this stand-in never touches a router.
                put(ck, state["mb"]["q"], seal_to_iphone(state, fake_home_check(msg["id"])))
            elif msg.get("id") is not None:
                put(ck, state["mb"]["q"], seal_to_iphone(state, {"t": "ok", "id": msg["id"]}))
            if kind == "sms.send":
                put(ck, state["mb"]["q"], seal_to_iphone(state, {"t": "sms.state", "cid": msg.get("cid"),
                                                                   "state": "sent"}))
        time.sleep(args.interval)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("pair"); p.add_argument("invite"); p.add_argument("--yes", action="store_true")
    p = sub.add_parser("sms"); p.add_argument("--from", dest="sender", required=True)
    p.add_argument("--body", required=True); p.add_argument("--thread", type=int, default=900)
    p = sub.add_parser("call"); p.add_argument("--from", dest="sender", required=True)
    p.add_argument("--missed", action="store_true")
    p = sub.add_parser("serve"); p.add_argument("--seconds", type=int, default=300)
    p.add_argument("--interval", type=float, default=5)
    args = ap.parse_args()
    {"pair": cmd_pair, "sms": cmd_sms, "call": cmd_call, "serve": cmd_serve}[args.cmd](args)


if __name__ == "__main__":
    main()
