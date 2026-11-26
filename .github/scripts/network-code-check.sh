#!/usr/bin/env bash
# Fences network code in the Android app (AGENTS.md rule 2, D-041).
#
#   network-code-check.sh
#
# The release app may open connections in only these places:
# - app/pairline/android/update/   the update check and APK download, to GitHub only (hosts checked below)
# - app/pairline/android/link/wifi/  the Wi-Fi link to the paired iPhone, local addresses only
# - app/pairline/android/netshare/   opt-in internet sharing (M8): forwards only the iPhone's own traffic
# - app/pairline/android/privileged/adb/  this phone's own Wireless debugging, on 127.0.0.1 only (D-054)
# - app/pairline/android/link/remote/  opt-in remote mode (D-092): CloudKit Web Services (hosts checked below), the
#                                       home router's port mapping (router/: NAT-PMP, PCP, UPnP on the LAN) and the
#                                       direct connection from the paired iPhone (DirectListener)
# Debug-only code (src/debug) is exempt: it never ships (D-018).
set -euo pipefail
cd "$(dirname "$0")/../../android/app/src/main/java"

allowed='^app/pairline/android/(update|link/wifi|link/remote|netshare|privileged/adb)/'
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

# Remote mode talks to Apple's CloudKit Web Services host and nothing else on the internet (the router and the
# iPhone are reached by address, never by a URL; UPnP fetches only LAN addresses next to the gateway, checked in
# link/remote/router/Upnp.kt).
if [[ -d app/pairline/android/link/remote ]]; then
  while IFS= read -r url; do
    host="$(sed -E 's#^https?://([^/:"]+).*#\1#' <<<"$url")"
    case "$host" in
      api.apple-cloudkit.com) ;;
      '$HOST') ;;  # the HOST constant, whose literal the check below verifies
      schemas.xmlsoap.org) ;;  # SOAP's XML namespace names in UPnP request bodies: names, never contacted
      *) echo "::error::The remote package refers to an unexpected host: $host"; fail=1 ;;
    esac
  done < <(grep -rhoE 'https?://[^"[:space:]]+' app/pairline/android/link/remote || true)
  if grep -rnE '"[a-z0-9.-]+\.(com|net|org|io|dev)"' app/pairline/android/link/remote | grep -v 'api\.apple-cloudkit\.com' | grep -q .; then
    echo "::error::The remote package names a host other than api.apple-cloudkit.com."
    fail=1
  fi
fi

[[ "$fail" -eq 0 ]] && echo "Network code is confined to update/, link/wifi/, link/remote/, netshare/ and privileged/adb/."
exit "$fail"
