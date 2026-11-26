#!/usr/bin/env python3
"""Remote mode's mailbox from a non-Apple client (D-072): what the Android bridge does, in Python.

Talks only to CloudKit Web Services (api.apple-cloudkit.com, and Apple's webcourier long-poll host for the
`webcourier` command), on the private database of the iCloud user whose iPhone minted the web auth token.

Configuration lives outside the repo:
  ~/.config/pairline/cloudkit-development.env   CK_CONTAINER, CK_ENVIRONMENT, CK_API_TOKEN (mode 600)
  ~/.config/pairline/ck-webauth-<name>.token     the current web auth token of a chain (mode 600)

CloudKit web auth tokens are single-use: every response carries the next one (`X-Apple-CloudKit-Web-Auth-Token`),
which replaces the stored one before anything else happens, and requests on one chain never overlap.

Commands:
  import-token FILE [--chain NAME]        start a chain from a token the iPhone wrote (then delete FILE yourself)
  zones                                   list the private database's zones (proves private-database access)
  put [--zone ToPhone] [--size 200]       write one Mail record with random bytes; prints its name
  delete NAME [--zone ToPhone]            delete a record
  changes [--zone ToBridge]               list changed records in a zone
  latency --device UDID [--tries 20]      write records and time the iPhone's push (needs idevicesyslog over USB)
  latency --out FILE [--tries 20]         write records and note when; time them later with `match`
  match WRITES DELIVERIES                 pair `latency --out` writes with the iPhone's `-PairlineRemoteSpike report`
  tokens                                  is a token single-use, and is a second token from the iPhone independent?
  webcourier [--seconds 120]              register for web push and long-poll (does CloudKit push to a web client?)
"""

import argparse
import base64
import json
import os
import re
import secrets
import statistics
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

CONFIG_DIR = os.path.expanduser("~/.config/pairline")
HOST = "https://api.apple-cloudkit.com"
TOKEN_HEADERS = ("X-Apple-CloudKit-Web-Auth-Token", "X-Apple-CloudKit-Session")


def load_env():
    env = {}
    path = os.path.join(CONFIG_DIR, "cloudkit-development.env")
    with open(path) as f:
        for line in f:
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.strip().split("=", 1)
                env[k] = v
    return env


def write_secret(path, text):
    tmp = path + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(text)
    os.replace(tmp, path)


class CloudKit:
    def __init__(self, chain="main"):
        self.env = load_env()
        self.chain_path = os.path.join(CONFIG_DIR, f"ck-webauth-{chain}.token")
        self.lock = threading.Lock()
        self.requests = 0

    @property
    def token(self):
        with open(self.chain_path) as f:
            return f.read().strip()

    def url(self, scope, path, api="database"):
        e = self.env
        q = urllib.parse.urlencode({"ckAPIToken": e["CK_API_TOKEN"], "ckWebAuthToken": self.token},
                                   quote_via=urllib.parse.quote, safe="")
        return f"{HOST}/{api}/1/{e['CK_CONTAINER']}/{e['CK_ENVIRONMENT']}/{scope}/{path}?{q}" if scope else \
            f"{HOST}/{api}/1/{e['CK_CONTAINER']}/{e['CK_ENVIRONMENT']}/{path}?{q}"

    def call(self, path, body=None, scope="private", api="database"):
        """One request on the chain; stores the rotated token before returning. Returns (status, json, seconds)."""
        with self.lock:
            data = None if body is None else json.dumps(body).encode()
            req = urllib.request.Request(self.url(scope, path, api), data=data,
                                         method="POST" if data is not None else "GET",
                                         headers={"Content-Type": "application/json"})
            started = time.monotonic()
            try:
                with urllib.request.urlopen(req, timeout=30) as r:
                    status, headers, raw = r.status, r.headers, r.read()
            except urllib.error.HTTPError as err:
                status, headers, raw = err.code, err.headers, err.read()
            elapsed = time.monotonic() - started
            self.requests += 1
            rotated = next((headers.get(h) for h in TOKEN_HEADERS if headers.get(h)), None)
            if rotated:
                write_secret(self.chain_path, rotated)
            try:
                payload = json.loads(raw) if raw else {}
            except ValueError:
                payload = {"raw": raw[:200].decode("latin-1")}
            payload.setdefault("_rotated", bool(rotated))
            return status, payload, elapsed


def summarize(payload):
    """A response without the token-bearing fields."""
    out = dict(payload)
    out.pop("redirectURL", None)
    return out


def cmd_import_token(args):
    with open(args.file) as f:
        token = f.read().strip()
    os.makedirs(CONFIG_DIR, mode=0o700, exist_ok=True)
    write_secret(os.path.join(CONFIG_DIR, f"ck-webauth-{args.chain}.token"), token)
    print(f"chain {args.chain}: token stored ({len(token)} characters)")


def cmd_zones(ck, args):
    status, payload, t = ck.call("zones/list")
    print(f"HTTP {status} in {t * 1000:.0f} ms, token rotated: {payload['_rotated']}")
    print(json.dumps(summarize(payload), indent=2)[:2000])
    return status == 200


def put_record(ck, zone, size):
    name = secrets.token_hex(16)
    body = {"zoneID": {"zoneName": zone}, "atomic": False, "operations": [{
        "operationType": "create",
        "record": {"recordType": "Mail", "recordName": name,
                   "fields": {"payload": {"value": base64.b64encode(secrets.token_bytes(size)).decode()}}}}]}
    status, payload, t = ck.call("records/modify", body)
    ok = status == 200 and not any("serverErrorCode" in r for r in payload.get("records", []))
    return name, ok, status, payload, t


def delete_record(ck, zone, name):
    body = {"zoneID": {"zoneName": zone}, "operations": [{
        "operationType": "forceDelete", "record": {"recordName": name}}]}
    return ck.call("records/modify", body)


def cmd_put(ck, args):
    name, ok, status, payload, t = put_record(ck, args.zone, args.size)
    print(f"{'ok' if ok else 'FAILED'}: HTTP {status} in {t * 1000:.0f} ms, record {name}, "
          f"token rotated: {payload['_rotated']}")
    if not ok:
        print(json.dumps(summarize(payload), indent=2)[:2000])
    return ok


def cmd_delete(ck, args):
    status, payload, t = delete_record(ck, args.zone, args.name)
    print(f"HTTP {status} in {t * 1000:.0f} ms: {json.dumps(summarize(payload))[:500]}")
    return status == 200


def cmd_changes(ck, args):
    status, payload, t = ck.call("changes/zone", {"zones": [{"zoneID": {"zoneName": args.zone}}]})
    print(f"HTTP {status} in {t * 1000:.0f} ms")
    for z in payload.get("zones", []):
        recs = z.get("records", [])
        print(f"zone {z.get('zoneID', {}).get('zoneName')}: {len(recs)} records, moreComing={z.get('moreComing')}")
        for r in recs[:20]:
            print(f"  {r.get('recordName')} deleted={r.get('deleted', False)} type={r.get('recordType')}")
    if status != 200:
        print(json.dumps(summarize(payload), indent=2)[:1500])
    return status == 200


class DeviceLog:
    """Streams the iPhone's system log and timestamps lines matching a pattern as they arrive on this Mac."""

    def __init__(self, udid, pattern):
        self.regex = re.compile(pattern)
        self.proc = subprocess.Popen(["idevicesyslog", "-u", udid, "--no-colors"], stdout=subprocess.PIPE,
                                     stderr=subprocess.DEVNULL, text=True, errors="replace", bufsize=1)
        self.hits = []
        self.cond = threading.Condition()
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        for line in self.proc.stdout:
            if self.regex.search(line):
                with self.cond:
                    self.hits.append((time.monotonic(), line.rstrip()))
                    self.cond.notify_all()

    def wait_after(self, t0, timeout):
        deadline = time.monotonic() + timeout
        with self.cond:
            while True:
                for t, line in self.hits:
                    if t >= t0:
                        return t, line
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return None, None
                self.cond.wait(remaining)

    def stop(self):
        self.proc.terminate()


def cmd_latency(ck, args):
    if not args.device:
        return latency_writes_only(ck, args)
    log = DeviceLog(args.device, args.match)
    time.sleep(2)
    results = []
    try:
        for i in range(args.tries):
            t0 = time.monotonic()
            name, ok, status, payload, t_http = put_record(ck, args.zone, args.size)
            if not ok:
                print(f"#{i + 1}: write failed HTTP {status}: {json.dumps(summarize(payload))[:400]}")
                results.append(None)
                break
            t_hit, line = log.wait_after(t0, args.timeout)
            delay = None if t_hit is None else t_hit - t0
            results.append(delay)
            shown = "no push seen" if delay is None else f"push after {delay:.2f} s"
            print(f"#{i + 1}: write {t_http * 1000:.0f} ms, {shown}", flush=True)
            if args.verbose and line:
                print("    " + line[:220])
            if not args.keep:
                delete_record(ck, args.zone, name)
            time.sleep(args.interval)
    finally:
        log.stop()
    seen = [d for d in results if d is not None]
    print(f"\n{len(seen)}/{len(results)} pushes seen; requests on the token chain: {ck.requests}")
    if seen:
        seen.sort()
        print(f"delay: min {seen[0]:.2f} s, median {statistics.median(seen):.2f} s, max {seen[-1]:.2f} s, "
              f"p90 {seen[int(0.9 * (len(seen) - 1))]:.2f} s")
    return bool(seen)


def latency_writes_only(ck, args):
    """Writes records and notes this Mac's wall-clock time around each write, for `match`."""
    writes = []
    for i in range(args.tries):
        start = time.time()
        name, ok, status, payload, t_http = put_record(ck, args.zone, args.size)
        end = time.time()
        if not ok:
            print(f"#{i + 1}: write failed HTTP {status}: {json.dumps(summarize(payload))[:400]}")
            break
        writes.append({"record": name, "start": start, "end": end})
        deleted = ""
        if not args.keep:
            time.sleep(args.delete_after)
            status, _, _ = delete_record(ck, args.zone, name)
            deleted = f", deleted (HTTP {status})"
            writes[-1]["deleted"] = time.time()
        print(f"#{i + 1}: write {t_http * 1000:.0f} ms{deleted}", flush=True)
        with open(args.out, "w") as f:
            json.dump({"writes": writes}, f, indent=1)
        if i + 1 < args.tries:
            time.sleep(args.interval)
    print(f"{len(writes)} records written; requests on the token chain: {ck.requests}; notes in {args.out}")
    return len(writes) == args.tries


def cmd_match(args):
    with open(args.writes) as f:
        writes = json.load(f)["writes"]
    with open(args.deliveries) as f:
        report = json.load(f)
    deliveries = report["deliveries"]
    used = set()
    delays = []
    for i, w in enumerate(writes):
        next_start = writes[i + 1]["start"] if i + 1 < len(writes) else float("inf")
        hit = None
        for j, d in enumerate(deliveries):
            if j in used:
                continue
            if d.get("record") == w["record"] or (not d.get("record") and w["start"] - 2 <= d["delivered"] < next_start):
                hit = j
                break
        if hit is None:
            print(f"#{i + 1}: no notification")
            continue
        used.add(hit)
        d = deliveries[hit]
        delay = d["delivered"] - w["end"]
        delays.append(delay)
        extra = f", payload in push: {d['payloadInPush']}" if "payloadInPush" in d else ""
        print(f"#{i + 1}: {delay:+.2f} s after the write returned ({d['delivered'] - w['start']:+.2f} s after it "
              f"started), subscription {d.get('subscription')}{extra}")
    others = [d for j, d in enumerate(deliveries) if j not in used]
    print(f"\n{len(delays)}/{len(writes)} writes produced a notification; {len(others)} other notifications "
          f"(types {sorted({d.get('type') for d in others})}, reasons {sorted({d.get('reason') for d in others if 'reason' in d})})")
    if delays:
        s = sorted(delays)
        print(f"delay after the write returned: min {s[0]:.2f} s, median {statistics.median(s):.2f} s, "
              f"max {s[-1]:.2f} s, p90 {s[int(0.9 * (len(s) - 1))]:.2f} s")
        print("(Mac and iPhone clocks both follow Apple's time servers; expect a few tenths of a second of error.)")
    return bool(delays)


def cmd_tokens(ck, args):
    """Single use: replay a token that was already spent. Independence: a spare token minted after the main one."""
    spent = ck.token
    status, payload, _ = ck.call("zones/list")
    print(f"main chain: HTTP {status}, rotated: {payload['_rotated']}")
    current = ck.token
    write_secret(ck.chain_path, spent)
    status, payload, _ = ck.call("zones/list")
    print(f"replaying the spent token: HTTP {status} {payload.get('serverErrorCode', '')} {payload.get('reason', '')}")
    if status == 200:
        print(f"  the replay was accepted (rotated: {payload['_rotated']}); the chain continues from the replay")
    else:
        write_secret(ck.chain_path, current)
    status, payload, _ = ck.call("zones/list")
    print(f"main chain after the replay: HTTP {status} {payload.get('serverErrorCode', '')}")
    spare_path = os.path.join(CONFIG_DIR, "ck-webauth-spare.token")
    if os.path.exists(spare_path):
        spare = CloudKit("spare")
        status, payload, _ = spare.call("zones/list")
        print(f"spare chain (minted after main, unused until now): HTTP {status} "
              f"{payload.get('serverErrorCode', '')} rotated: {payload['_rotated']}")
        status, payload, _ = ck.call("zones/list")
        print(f"main chain after the spare was used: HTTP {status} {payload.get('serverErrorCode', '')}")
    else:
        print("no spare chain imported (import-token --chain spare FILE)")
    return True


def cmd_webcourier(ck, args):
    env = ck.env["CK_ENVIRONMENT"]
    status, created, _ = ck.call("tokens/create", {"apnsEnvironment": env}, scope=None, api="device")
    print(f"tokens/create: HTTP {status}, keys {sorted(k for k in created if not k.startswith('_'))}")
    if status != 200 or "webcourierURL" not in created:
        print(json.dumps(summarize(created), indent=2)[:1000])
        return False
    status, reg, _ = ck.call("tokens/register", {"apnsEnvironment": env, "apnsToken": created["apnsToken"]},
                             scope=None, api="device")
    print(f"tokens/register: HTTP {status} {json.dumps(summarize(reg))[:300]}")
    sub = {"operations": [{"operationType": "replace", "subscription": {
        "subscriptionID": "to-bridge-web", "subscriptionType": "zone", "zoneID": {"zoneName": args.zone},
        "notificationInfo": {"shouldSendContentAvailable": True}}}]}
    status, subs, _ = ck.call("subscriptions/modify", sub)
    print(f"subscriptions/modify: HTTP {status} {json.dumps(summarize(subs))[:400]}")
    deadline = time.monotonic() + args.seconds
    url = created["webcourierURL"]
    print(f"long-polling Apple's webcourier for {args.seconds} s; write to zone {args.zone} from the iPhone now")
    while time.monotonic() < deadline:
        started = time.monotonic()
        try:
            with urllib.request.urlopen(url, timeout=max(5, deadline - time.monotonic())) as r:
                body = r.read()
            print(f"+{time.monotonic() - started:.1f} s webcourier: HTTP {r.status} {body[:300]!r}", flush=True)
        except urllib.error.HTTPError as err:
            print(f"webcourier HTTP {err.code}: {err.read()[:200]!r}")
            time.sleep(1)
        except OSError as err:
            print(f"webcourier: {err}")
    return True


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--chain", default="main", help="which stored token chain to use")
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("import-token"); p.add_argument("file")
    sub.add_parser("zones")
    p = sub.add_parser("put"); p.add_argument("--zone", default="ToPhone"); p.add_argument("--size", type=int, default=200)
    p = sub.add_parser("delete"); p.add_argument("name"); p.add_argument("--zone", default="ToPhone")
    p = sub.add_parser("changes"); p.add_argument("--zone", default="ToBridge")
    p = sub.add_parser("latency")
    p.add_argument("--device", help="UDID of a USB-connected iPhone whose system log shows the push")
    p.add_argument("--out", default=os.path.join(CONFIG_DIR, "latency-writes.json"),
                   help="without --device: where to note the writes for `match`")
    p.add_argument("--tries", type=int, default=20)
    p.add_argument("--interval", type=float, default=15)
    p.add_argument("--timeout", type=float, default=60)
    p.add_argument("--zone", default="ToPhone")
    p.add_argument("--size", type=int, default=200)
    p.add_argument("--keep", action="store_true", help="don't delete records after each try")
    p.add_argument("--delete-after", type=float, default=5, help="without --device: seconds before deleting")
    p.add_argument("--verbose", action="store_true")
    p.add_argument("--match", default=r"apsd.*(app\.pairline\.ios|icloud-container)",
                   help="regex for the iPhone log line that shows the push arrived")
    p = sub.add_parser("match"); p.add_argument("writes"); p.add_argument("deliveries")
    sub.add_parser("tokens")
    p = sub.add_parser("webcourier"); p.add_argument("--seconds", type=int, default=120)
    p.add_argument("--zone", default="ToBridge")
    args = ap.parse_args()
    if args.cmd == "import-token":
        cmd_import_token(args)
        return 0
    if args.cmd == "match":
        return 0 if cmd_match(args) else 1
    ck = CloudKit(args.chain)
    ok = {"zones": cmd_zones, "put": cmd_put, "delete": cmd_delete, "changes": cmd_changes,
          "latency": cmd_latency, "tokens": cmd_tokens, "webcourier": cmd_webcourier}[args.cmd](ck, args)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
