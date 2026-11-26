#!/usr/bin/env python3
"""Looks for known trackers in an APK the way εxodus Privacy does, and writes one Markdown cell for the release notes.

Same method and signatures as εxodus's own scanner (exodus-core, StaticAnalysis): dexdump every classes*.dex
(also inside nested APKs), take every class path from its output, and match it against the code signature of
each tracker in εxodus's public list, https://reports.exodus-privacy.eu.org/api/trackers (D-100). Standard
library only, so no third-party code runs next to the release. The only request is downloading that list.

Usage: trackers-scan.py [--fail-on-trackers] <apk> <cell-out>

Never fails because εxodus can't be reached: that's a workflow warning and a "not checked" cell. With
--fail-on-trackers (CI on every push) it exits 1 when a tracker is found; without it (releases) it warns.
Finds dexdump in build-tools $BUILD_TOOLS under $ANDROID_HOME, else the newest installed, else on PATH.
Run with python3 -I.
"""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request
import zipfile

TRACKERS_URL = "https://reports.exodus-privacy.eu.org/api/trackers"
TRACKERS_PAGE = "https://reports.exodus-privacy.eu.org/en/trackers/"
# εxodus's own patterns: class paths from dexdump's output, and the dex files inside an APK.
CLASS_PATH = re.compile(r"[A-Z]+((?:\w+\/)+\w+)")
DEX_NAME = re.compile(r"classes.*\.dex")
APK_NAME = re.compile(r".*\.apk")


def finish(out, cell, warning=None, code=0):
    with open(out, "w", encoding="utf-8") as f:
        f.write(cell + "\n")
    if warning:
        print(f"::warning title=Trackers::{warning}")
    print(f"Trackers: {cell}")
    sys.exit(code)


def find_dexdump():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or os.path.expanduser("~/Library/Android/sdk")
    tools = os.path.join(sdk, "build-tools")
    wanted = os.environ.get("BUILD_TOOLS")
    candidates = [wanted] if wanted else []
    if os.path.isdir(tools):
        def version_key(name):
            return [int(p) if p.isdigit() else 0 for p in re.split(r"[.-]", name)]
        candidates += sorted((d for d in os.listdir(tools) if "rc" not in d), key=version_key, reverse=True)
    for version in candidates:
        path = os.path.join(tools, version, "dexdump")
        if os.access(path, os.X_OK):
            return path
    return shutil.which("dexdump")


def load_trackers():
    last = None
    for attempt in range(3):
        try:
            request = urllib.request.Request(TRACKERS_URL, headers={"User-Agent": "trackers-scan"})
            with urllib.request.urlopen(request, timeout=60) as response:
                trackers = json.load(response)["trackers"].values()
            return [t for t in trackers if len(t.get("code_signature") or "") > 3]
        except Exception as e:  # network, HTTP or JSON: retried, then reported
            last = e
            time.sleep(10 * (attempt + 1))
    raise RuntimeError(f"couldn't load {TRACKERS_URL}: {last}")


def embedded_classes(apk, dexdump, depth=0):
    if depth > 10:  # nested-APK bomb
        return set()
    classes = set()
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(apk) as zf:
        for info in zf.infolist():
            if APK_NAME.search(info.filename):
                with zf.open(info) as nested:
                    classes |= embedded_classes(nested, dexdump, depth + 1)
            elif DEX_NAME.search(info.filename):
                path = zf.extract(info, tmp)
                output = subprocess.run([dexdump, path], check=True, capture_output=True).stdout
                classes |= set(CLASS_PATH.findall(output.decode(errors="ignore")))
    return classes


def main():
    args = sys.argv[1:]
    fail_on_trackers = "--fail-on-trackers" in args
    args = [a for a in args if a != "--fail-on-trackers"]
    if len(args) != 2:
        sys.exit("usage: trackers-scan.py [--fail-on-trackers] <apk> <cell-out>")
    apk, out = args

    dexdump = find_dexdump()
    if not dexdump:
        sys.exit("dexdump not found: install Android build-tools or set ANDROID_HOME")
    classes = embedded_classes(apk, dexdump)
    if not classes:
        sys.exit(f"no classes found in {apk}; the tracker check can't run")
    print(f"{len(classes)} class paths in {os.path.basename(apk)}")

    try:
        trackers = load_trackers()
    except RuntimeError as e:
        finish(out, "not checked (couldn't load εxodus's tracker list)", f"The tracker check didn't run: {e}")

    found = []
    for tracker in trackers:
        signature = re.compile(tracker["code_signature"])
        hit = next((c for c in sorted(classes) if signature.search(c)), None)
        if hit:
            found.append((tracker["name"], hit))
            print(f"Found {tracker['name']}: {hit} matches {tracker['code_signature']}")

    if not found:
        finish(out, f"none of the {len(trackers)} [known to εxodus]({TRACKERS_PAGE})")
    names = ", ".join(f"{name} (`{hit.replace('/', '.')}`)" for name, hit in found)
    cell = f"**{len(found)} found** among the {len(trackers)} [known to εxodus]({TRACKERS_PAGE}): {names}"
    finish(out, cell, f"εxodus's signatures match {len(found)} tracker(s): {names}", 1 if fail_on_trackers else 0)


if __name__ == "__main__":
    main()
