#!/usr/bin/env bash
# Fences network code in the Android app (AGENTS.md rule 2, D-041).
#
#   network-code-check.sh
#
# The release app may open connections in only these places:
# - app/pairline/android/update/   the update check and APK download, to GitHub only (hosts checked below)
# - app/pairline/android/link/wifi/  the Wi-Fi link to the paired iPhone, local addresses only
# - app/pairline/android/netshare/   opt-in internet sharing (M8): forwards only the iPhone's own traffic
# Debug-only code (src/debug) is exempt: it never ships (D-018).
set -euo pipefail
cd "$(dirname "$0")/../../android/app/src/main/java"

allowed='^app/pairline/android/(update|link/wifi|netshare)/'
# Classes and calls that open or prepare connections.
pattern='java\.net\.(Socket|ServerSocket|DatagramSocket|MulticastSocket|URL|HttpURLConnection|InetSocketAddress)|javax\.net\.ssl|\bHttpsURLConnection\b|\bopenConnection\(|\bSocketChannel\b|\bDatagramChannel\b|\bServerSocketChannel\b|okhttp|ktor|retrofit|volley|cronet'
fail=0

while IFS= read -r hit; do
  file="${hit%%:*}"
  if [[ ! "$file" =~ $allowed ]]; then
    echo "::error file=android/app/src/main/java/$file::Network code outside the allowed packages: ${hit#*:}"
    fail=1
  fi
done < <(grep -rnE "$pattern" --include='*.kt' --include='*.java' . | sed 's#^\./##' || true)

# The update package talks to GitHub and nothing else.
if [[ -d app/pairline/android/update ]]; then
  while IFS= read -r url; do
    host="$(sed -E 's#^https?://([^/:"]+).*#\1#' <<<"$url")"
    case "$host" in
      api.github.com|github.com|objects.githubusercontent.com|release-assets.githubusercontent.com) ;;
      *) echo "::error::The update package refers to an unexpected host: $host"; fail=1 ;;
    esac
  done < <(grep -rhoE 'https?://[^"[:space:]]+' app/pairline/android/update || true)
fi

[[ "$fail" -eq 0 ]] && echo "Network code is confined to update/, link/wifi/ and netshare/."
exit "$fail"
