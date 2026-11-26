#!/usr/bin/env python3
"""Read-only check of the home router for Pairline's remote mode (D-092).

Asks the router, from a computer on the home network, whether it supports port mapping and what its external
address is. It never creates, changes or deletes a mapping:

- NAT-PMP (RFC 6886): the external-address request (opcode 0) only.
- PCP (RFC 6887): ANNOUNCE (opcode 0) only, which reports whether a PCP server answers.
- UPnP IGD: SSDP discovery, the device description, then GetExternalIPAddress, GetStatusInfo and
  GetConnectionTypeInfo (and GetFirewallStatus on an IPv6 firewall service). No AddPortMapping.

It talks only to the router (the default gateway, or --gateway) and to the SSDP multicast group on the local
network. Usage: python3 -I protocol/tools/router_check.py [--gateway 192.168.1.1] [--json]
"""

import argparse
import ipaddress
import json
import re
import socket
import struct
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

NATPMP_PORT = 5351
SSDP_ADDR = ("239.255.255.250", 1900)
IGD_SERVICES = (
    "urn:schemas-upnp-org:service:WANIPConnection:2",
    "urn:schemas-upnp-org:service:WANIPConnection:1",
    "urn:schemas-upnp-org:service:WANPPPConnection:1",
)
IPV6_FW_SERVICE = "urn:schemas-upnp-org:service:WANIPv6FirewallControl:1"
NATPMP_RESULTS = {0: "success", 1: "unsupported version", 2: "not authorized/refused", 3: "network failure",
                  4: "out of resources", 5: "unsupported opcode"}


def default_gateway():
    try:
        out = subprocess.run(["route", "-n", "get", "default"], capture_output=True, text=True, timeout=5).stdout
    except (OSError, subprocess.TimeoutExpired):
        return None
    m = re.search(r"gateway:\s*(\S+)", out)
    return m.group(1) if m else None


def local_ip_towards(gateway):
    """The address this computer uses to reach the gateway (no packet is sent: UDP connect only picks a route)."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect((gateway, NATPMP_PORT))
        return s.getsockname()[0]
    finally:
        s.close()


def classify_ipv4(addr):
    ip = ipaddress.IPv4Address(addr)
    if ip in ipaddress.IPv4Network("100.64.0.0/10"):
        return "carrier-grade NAT (100.64/10): not reachable from the internet"
    if ip.is_private:
        return "private: another NAT sits in front of this router (double NAT)"
    if ip.is_unspecified:
        return "unspecified (0.0.0.0): the router has no internet address right now"
    if ip.is_global:
        return "public"
    return "not public"


def udp_exchange(gateway, payload, timeout_total=3.0):
    """Send with RFC 6886 style retries (250 ms, doubling) and return the first reply from the gateway, or None."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        wait = 0.25
        deadline = time.monotonic() + timeout_total
        while time.monotonic() < deadline:
            s.sendto(payload, (gateway, NATPMP_PORT))
            s.settimeout(min(wait, max(0.01, deadline - time.monotonic())))
            try:
                while True:
                    data, src = s.recvfrom(1100)
                    if src[0] == gateway:
                        return data
            except socket.timeout:
                wait *= 2
        return None
    finally:
        s.close()


def check_natpmp(gateway):
    reply = udp_exchange(gateway, b"\x00\x00")
    if reply is None:
        return {"answered": False}
    res = {"answered": True, "raw_len": len(reply)}
    if len(reply) >= 12 and reply[0] == 0 and reply[1] == 128:
        code, epoch = struct.unpack("!HI", reply[2:8])
        res["result"] = NATPMP_RESULTS.get(code, f"code {code}")
        res["epoch_seconds"] = epoch
        if code == 0:
            ext = socket.inet_ntoa(reply[8:12])
            res["external_ipv4"] = ext
            res["external_kind"] = classify_ipv4(ext)
    else:
        res["result"] = f"unexpected reply (version {reply[0]}, opcode {reply[1]})"
    return res


def check_pcp(gateway, client_ip):
    # PCP ANNOUNCE: version 2, R=0 opcode 0, reserved, lifetime 0, client address as IPv4-mapped IPv6.
    client = b"\x00" * 10 + b"\xff\xff" + socket.inet_aton(client_ip)
    req = struct.pack("!BBHI", 2, 0, 0, 0) + client
    reply = udp_exchange(gateway, req)
    if reply is None:
        return {"answered": False}
    res = {"answered": True, "raw_len": len(reply)}
    if len(reply) >= 24 and reply[0] == 2 and reply[1] == 0x80:
        code = reply[3]
        res["result"] = "success" if code == 0 else f"result code {code}"
        res["epoch_seconds"] = struct.unpack("!I", reply[8:12])[0]
        res["supported"] = code == 0
    elif len(reply) >= 4 and reply[0] == 0:
        # A NAT-PMP-only server answers a version-2 request with UNSUPP_VERSION in NAT-PMP format.
        res["result"] = "answered as NAT-PMP only (unsupported version)"
        res["supported"] = False
    else:
        res["result"] = f"unexpected reply (version {reply[0]})"
        res["supported"] = False
    return res


def ssdp_search(gateway, timeout=3.0):
    found = {}
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    try:
        s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
        for st in ("urn:schemas-upnp-org:device:InternetGatewayDevice:2",
                   "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
                   "urn:schemas-upnp-org:service:WANIPConnection:1"):
            msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n"
                   f"MX: 2\r\nST: {st}\r\n\r\n").encode()
            s.sendto(msg, SSDP_ADDR)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            s.settimeout(max(0.05, deadline - time.monotonic()))
            try:
                data, src = s.recvfrom(4096)
            except socket.timeout:
                break
            headers = {}
            for line in data.decode("latin-1").split("\r\n")[1:]:
                if ":" in line:
                    k, v = line.split(":", 1)
                    headers[k.strip().lower()] = v.strip()
            loc = headers.get("location")
            if loc:
                found.setdefault(loc, {"from": src[0], "server": headers.get("server"), "st": headers.get("st")})
    finally:
        s.close()
    # Only the gateway's own description is followed; other devices' answers are listed, not contacted.
    return found


def soap(control_url, service, action, timeout=4.0):
    body = ('<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
            's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>'
            f'<u:{action} xmlns:u="{service}"/></s:Body></s:Envelope>').encode()
    req = urllib.request.Request(control_url, data=body, method="POST", headers={
        "Content-Type": 'text/xml; charset="utf-8"', "SOAPAction": f'"{service}#{action}"'})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            xml = r.read()
    except urllib.error.HTTPError as e:
        return {"error": f"HTTP {e.code}"}
    except OSError as e:
        return {"error": str(e)}
    out = {}
    try:
        root = ET.fromstring(xml)
        for el in root.iter():
            tag = el.tag.split("}")[-1]
            if tag.startswith("New"):
                out[tag[3:]] = (el.text or "").strip()
    except ET.ParseError:
        out["error"] = "unparseable reply"
    return out


def check_upnp(gateway):
    found = ssdp_search(gateway)
    res = {"answers": [{"location_host": urllib.parse.urlparse(k).hostname, **v} for k, v in found.items()]}
    gw_locs = [k for k in found if urllib.parse.urlparse(k).hostname == gateway]
    if not gw_locs:
        res["igd"] = False
        return res
    loc = gw_locs[0]
    try:
        with urllib.request.urlopen(loc, timeout=4) as r:
            desc = r.read()
    except OSError as e:
        res["igd"] = False
        res["error"] = f"description: {e}"
        return res
    root = ET.fromstring(desc)
    ns = {"d": "urn:schemas-upnp-org:device-1-0"}
    dev = root.find("d:device", ns)
    if dev is not None:
        res["model"] = " ".join(filter(None, [(dev.findtext("d:manufacturer", default="", namespaces=ns)),
                                              (dev.findtext("d:modelName", default="", namespaces=ns)),
                                              (dev.findtext("d:modelNumber", default="", namespaces=ns))]))
        res["device_type"] = dev.findtext("d:deviceType", default="", namespaces=ns)
    services = {}
    for svc in root.iter("{urn:schemas-upnp-org:device-1-0}service"):
        stype = svc.findtext("d:serviceType", default="", namespaces=ns)
        ctrl = svc.findtext("d:controlURL", default="", namespaces=ns)
        services[stype] = urllib.parse.urljoin(loc, ctrl)
    res["services"] = sorted(services)
    wan = next((s for s in IGD_SERVICES if s in services), None)
    res["igd"] = wan is not None
    if wan:
        res["wan_service"] = wan
        ext = soap(services[wan], wan, "GetExternalIPAddress")
        res["GetExternalIPAddress"] = ext
        if ext.get("ExternalIPAddress"):
            try:
                res["external_kind"] = classify_ipv4(ext["ExternalIPAddress"])
            except ValueError:
                pass
        res["GetStatusInfo"] = soap(services[wan], wan, "GetStatusInfo")
        res["GetConnectionTypeInfo"] = soap(services[wan], wan, "GetConnectionTypeInfo")
    if IPV6_FW_SERVICE in services:
        res["GetFirewallStatus"] = soap(services[IPV6_FW_SERVICE], IPV6_FW_SERVICE, "GetFirewallStatus")
    return res


def check_ipv6():
    try:
        out = subprocess.run(["ifconfig"], capture_output=True, text=True, timeout=5).stdout
    except (OSError, subprocess.TimeoutExpired):
        return {}
    iface = None
    global_addrs = []
    for line in out.splitlines():
        if line and not line[0].isspace():
            iface = line.split(":", 1)[0]
            continue
        m = re.match(r"\s+inet6 ([0-9a-f:]+)", line)
        if m and iface and not iface.startswith(("utun", "lo", "awdl", "llw", "bridge", "anpi", "ap")):
            ip = ipaddress.IPv6Address(m.group(1))
            if ip.is_global:
                kind = "temporary" if "temporary" in line else "stable"
                global_addrs.append({"iface": iface, "kind": kind,
                                     "prefix": str(ipaddress.IPv6Network(f"{ip}/64", strict=False))})
    return {"global_addresses": global_addrs, "has_global_ipv6": bool(global_addrs)}


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--gateway", default=None)
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()
    gw = args.gateway or default_gateway()
    if not gw:
        print("No default gateway found; pass --gateway.", file=sys.stderr)
        return 2
    client = local_ip_towards(gw)
    report = {"gateway": gw, "this_computer": client,
              "natpmp": check_natpmp(gw), "pcp": check_pcp(gw, client), "upnp": check_upnp(gw),
              "ipv6": check_ipv6()}
    ext = report["natpmp"].get("external_ipv4") or report["upnp"].get("GetExternalIPAddress", {}).get(
        "ExternalIPAddress")
    can_map = bool(report["natpmp"].get("external_ipv4")) or bool(report["pcp"].get("supported")) or bool(
        report["upnp"].get("igd"))
    public_v4 = bool(ext) and classify_ipv4(ext) == "public"
    report["summary"] = {
        "router_can_map_ports": can_map,
        "external_ipv4": ext,
        "external_ipv4_public": public_v4,
        "global_ipv6": report["ipv6"].get("has_global_ipv6", False),
        "direct_connection_possible": (can_map and public_v4) or report["ipv6"].get("has_global_ipv6", False),
    }
    if args.json:
        print(json.dumps(report, indent=2))
    else:
        for k, v in report.items():
            print(f"{k}: {json.dumps(v, indent=2)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
