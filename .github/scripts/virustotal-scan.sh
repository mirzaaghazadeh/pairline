#!/usr/bin/env bash
# Uploads one release file to VirusTotal, waits for the scan, and writes one Markdown cell for the release notes:
# a link to the public report and how many engines flagged the file (D-039).
#
# Only the release file itself is sent; it's the same file anyone can download from the GitHub Release.
# Needs VT_API_KEY in the environment (GitHub Actions secret), curl and jq.
#
# Usage: virustotal-scan.sh <file> <cell-out>
# Never fails the build: a missing key, an API error or a slow scan is reported in the cell and as a
# workflow warning, so the person reviewing the draft release sees it.
set -euo pipefail

file="$1"
out="$2"
api="https://www.virustotal.com/api/v3"
sha="$({ sha256sum "$file" 2>/dev/null || shasum -a 256 "$file"; } | cut -d' ' -f1)"
report="https://www.virustotal.com/gui/file/$sha"

# The free API allows 4 requests a minute: one upload, then a poll every 30 s for up to 10 minutes.
polls=20
interval=30

finish() { # <cell> [warning]
  printf '%s\n' "$1" > "$out"
  if [ -n "${2:-}" ]; then echo "::warning title=VirusTotal::$2"; fi
  echo "VirusTotal: $1"
  exit 0
}

if [ -z "${VT_API_KEY:-}" ]; then
  finish "not scanned (no API key)" "VT_API_KEY isn't set, so the APK wasn't scanned. See docs/RELEASING.md."
fi

# curl retries timeouts and HTTP 408/429/5xx. The key goes in a header and GitHub masks it in logs.
vt() { curl -sS --fail --retry 3 --retry-delay 20 --max-time 300 -H "x-apikey: $VT_API_KEY" "$@"; }

upload_url="$api/files"
if [ "$(wc -c < "$file")" -gt 32000000 ]; then # bigger files need a one-time upload URL
  upload_url="$(vt "$api/files/upload_url" | jq -er .data)" ||
    finish "not scanned (upload failed)" "Couldn't get a VirusTotal upload URL."
fi
analysis="$(vt -X POST -F "file=@$file" "$upload_url" | jq -er .data.id)" ||
  finish "not scanned (upload failed)" "Uploading the APK to VirusTotal failed."
echo "Uploaded $(basename "$file") (SHA-256 $sha), analysis $analysis"

status=""
for _ in $(seq "$polls"); do
  sleep "$interval"
  json="$(vt "$api/analyses/$analysis")" || continue
  status="$(jq -r '.data.attributes.status // empty' <<<"$json")"
  echo "Status: $status"
  [ "$status" = completed ] && break
done

if [ "$status" != completed ]; then
  finish "[report]($report) (the scan hadn't finished when this release was built)" \
    "The VirusTotal scan didn't finish in $((polls * interval / 60)) minutes. Check the report before publishing: $report"
fi

stats="$(jq -r '.data.attributes.stats | [.malicious, .suspicious, .malicious + .suspicious + .undetected + .harmless]
  | map(. // 0) | @tsv' <<<"$json")" ||
  finish "[report]($report)" "Couldn't read the VirusTotal results. Check the report before publishing: $report"
read -r malicious suspicious total <<<"$stats"

cell="[$malicious / $total engines flagged it]($report)"
if [ "$suspicious" -gt 0 ]; then cell="$cell, $suspicious marked it suspicious"; fi

if [ $((malicious + suspicious)) -gt 0 ]; then
  finish "$cell" "$malicious engine(s) flagged the APK as malicious and $suspicious as suspicious. Read the report before publishing: $report"
fi
finish "$cell"
