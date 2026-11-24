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

# The result, as {stats, results}, from a finished analysis of our upload, else from the report VirusTotal already
# has for this file (it answers 409 to a second upload of a file it's still busy with, and a re-scan needs no upload).
result=""

upload_url="$api/files"
if [ "$(wc -c < "$file")" -gt 32000000 ]; then # bigger files need a one-time upload URL
  upload_url="$(vt "$api/files/upload_url" | jq -er .data)" || upload_url=""
fi
analysis=""
if [ -n "$upload_url" ]; then
  analysis="$(vt -X POST -F "file=@$file" "$upload_url" | jq -er .data.id)" || analysis=""
fi

if [ -n "$analysis" ]; then
  echo "Uploaded $(basename "$file") (SHA-256 $sha), analysis $analysis"
  for _ in $(seq "$polls"); do
    sleep "$interval"
    json="$(vt "$api/analyses/$analysis")" || continue
    status="$(jq -r '.data.attributes.status // empty' <<<"$json")"
    echo "Status: $status"
    if [ "$status" = completed ]; then
      result="$(jq -c '.data.attributes | {stats, results}' <<<"$json")"
      break
    fi
  done
else
  echo "Upload failed; looking for an earlier VirusTotal report of the same file"
fi

if [ -z "$result" ] && json="$(vt "$api/files/$sha" 2>/dev/null)" &&
  [ -n "$(jq -r '.data.attributes.last_analysis_date // empty' <<<"$json")" ]; then
  echo "Using VirusTotal's latest report for this file"
  result="$(jq -c '.data.attributes | {stats: .last_analysis_stats, results: .last_analysis_results}' <<<"$json")"
fi

if [ -z "$result" ]; then
  if [ -z "$analysis" ]; then
    finish "not scanned (upload failed)" "Uploading the APK to VirusTotal failed, and VirusTotal has no report for it yet."
  fi
  finish "[report]($report) (the scan hadn't finished when this release was built)" \
    "The VirusTotal scan didn't finish in $((polls * interval / 60)) minutes. Check the report before publishing: $report"
fi
json="$result"

stats="$(jq -r '.stats | [.malicious, .suspicious, .malicious + .suspicious + .undetected + .harmless]
  | map(. // 0) | @tsv' <<<"$json")" ||
  finish "[report]($report)" "Couldn't read the VirusTotal results. Check the report before publishing: $report"
read -r malicious suspicious total <<<"$stats"

cell="[$malicious / $total engines flagged it]($report)"
if [ "$suspicious" -gt 0 ]; then cell="$cell, $suspicious marked it suspicious"; fi

if [ $((malicious + suspicious)) -gt 0 ]; then
  # e.g. "Ikarus: malicious (Trojan.AndroidOS.X)"; the names tell a known false positive from a real problem.
  engines="$(jq -r '[.results // {} | to_entries[]
    | select(.value.category == "malicious" or .value.category == "suspicious")
    | "\(.key): \(.value.category) (\(.value.result // "no name"))"] | join("; ")' <<<"$json" || true)"
  finish "$cell" "$malicious engine(s) flagged the APK as malicious and $suspicious as suspicious${engines:+ ($engines)}. Read the report before publishing: $report"
fi
finish "$cell"
