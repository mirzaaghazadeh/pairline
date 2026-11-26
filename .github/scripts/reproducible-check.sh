#!/usr/bin/env bash
# Checks that a signed release APK is exactly a second, independent build of the same commit plus our signature,
# and writes one Markdown cell for the release notes (D-100).
#
# Copies the signature from <signed.apk> onto <rebuilt-unsigned.apk> with apksigcopier (the tool F-Droid uses for
# the same check) and compares the result with <signed.apk> byte for byte. Nothing else can differ: the signature
# covers every byte of the APK.
#
# Usage: reproducible-check.sh <signed.apk> <rebuilt-unsigned.apk> <rebuild-url> <cell-out>
# Needs apksigcopier on PATH. Never fails the build: a difference is a workflow warning that names the files that
# differ, and the cell says so, for the person reviewing the draft release.
set -euo pipefail
export LC_ALL=C

signed="$1"
rebuilt="$2"
url="$3"
out="$4"

finish() { # <cell> [warning]
  printf '%s\n' "$1" > "$out"
  if [ -n "${2:-}" ]; then echo "::warning title=Reproducible build::$2"; fi
  echo "Reproducible build: $1"
  exit 0
}

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

if apksigcopier copy "$signed" "$rebuilt" "$tmp/copied.apk" && cmp -s "$tmp/copied.apk" "$signed"; then
  finish "identical: [a second build]($url) of the same commit on another machine, with our signature copied onto it, is byte for byte this APK"
fi

# Which entries differ, for the warning (signature files exist only in the signed APK and are left out).
differ="$(python3 -I - "$signed" "$rebuilt" <<'EOF'
import re, sys, zipfile
signed, rebuilt = (zipfile.ZipFile(p) for p in sys.argv[1:3])
sig = re.compile(r"META-INF/([^/]+\.(SF|RSA|DSA|EC)|MANIFEST\.MF)$")
a = {i.filename: i for i in signed.infolist() if not sig.match(i.filename)}
b = {i.filename: i for i in rebuilt.infolist()}
names = sorted(n for n in a.keys() | b.keys() if n not in a or n not in b or (a[n].CRC, a[n].file_size) != (b[n].CRC, b[n].file_size))
print(", ".join(names[:10]) + (f" and {len(names) - 10} more" if len(names) > 10 else "") if names else "")
EOF
)" || differ=""
if [ -z "$differ" ]; then differ="no file's contents; the zip layout"; fi
finish "**differs** from [a second build]($url) of the same commit: $differ" \
  "The release APK isn't byte for byte a second build of the same commit. Differences: $differ. Check before publishing: $url"
