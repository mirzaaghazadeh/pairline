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

# Not byte for byte: compare file by file. Each file's stored bytes (as compressed in the APK), name, order, date and
# attributes; only the zip padding between files and the signature files (only in the signed APK) are left out.
# Releases up to 1.8.0 were signed without --alignment-preserved, so apksigner's padding is all that differs there.
differ="$(python3 -I - "$signed" "$rebuilt" <<'EOF'
import re, struct, sys, zipfile
sig = re.compile(r"META-INF/([^/]+\.(SF|RSA|DSA|EC)|MANIFEST\.MF)$")
def entries(path):
    with zipfile.ZipFile(path) as zf, open(path, "rb") as f:
        out = []
        for i in zf.infolist():
            if sig.match(i.filename):
                continue
            f.seek(i.header_offset + 26)
            n, m = struct.unpack("<HH", f.read(4))
            f.seek(n + m, 1)
            meta = (i.compress_type, i.CRC, i.compress_size, i.file_size, i.date_time, i.external_attr, i.flag_bits)
            out.append((i.filename, meta, f.read(i.compress_size)))
        return out
a, b = entries(sys.argv[1]), entries(sys.argv[2])
if [e[0] for e in a] != [e[0] for e in b] and sorted(e[0] for e in a) == sorted(e[0] for e in b):
    print("ORDER")
else:
    da, db = {e[0]: e[1:] for e in a}, {e[0]: e[1:] for e in b}
    names = sorted(k for k in da.keys() | db.keys() if da.get(k) != db.get(k))
    print(", ".join(names[:10]) + (f" and {len(names) - 10} more" if len(names) > 10 else "") if names else "SAME")
EOF
)" || differ="(the comparison failed)"
case "$differ" in
  SAME)
    finish "same files as [a second build]($url) of the same commit, each byte for byte; only the zip padding apksigner added when signing differs (signed before D-100)" \
      "Every file matches the second build, but the APK isn't byte for byte the same: apksigner padded the stored files when signing. Releases from D-100 on are signed with --alignment-preserved; if this is one, check the signing step: $url" ;;
  ORDER) differ="the files are the same but in another order" ;;
esac
finish "**differs** from [a second build]($url) of the same commit: $differ" \
  "The release APK isn't the same as a second build of the same commit. Differences: $differ. Check before publishing: $url"
