#!/usr/bin/env bash
# Release-blocking checks on the iOS app (AGENTS.md rules 2-3, D-018).
#
#   ios-policy-check.sh <path to a Release Pairline.app>
#
# - no third-party package managers or dependencies (the app uses Apple frameworks only)
# - no analytics, ads, crash-reporting, attribution or IP-lookup SDKs in the sources or the binary
# - the debug-only TCP transport, pairing-link handling and launch options are not in the Release binary
# - no App Transport Security exceptions
set -euo pipefail
export LC_ALL=C

app="${1:?usage: ios-policy-check.sh <Pairline.app>}"
root="$(cd "$(dirname "$0")/../.." && pwd)"
fail=0

echo "== Sources"
for file in Podfile Cartfile Package.swift Package.resolved; do
  if find "$root/ios" -name "$file" -not -path '*/build/*' -not -path '*/DerivedData/*' | grep -q .; then
    echo "::error::Found $file: third-party dependencies need a DECISIONS.md entry (AGENTS.md)."
    fail=1
  fi
done
if grep -rqs "XCRemoteSwiftPackageReference\|packages:" "$root/ios/project.yml"; then
  echo "::error::project.yml declares Swift packages."
  fail=1
fi

forbidden='Firebase|Crashlytics|Sentry|AppsFlyer|Adjust(Sdk)?\b|Branch\.io|Mixpanel|Amplitude|Segment|Bugsnag|Instabug|Datadog|NewRelic|AppCenter|FBSDK|FacebookCore|OneSignal|Flurry|YandexMobileMetrica|ipinfo|ipwho|Kochava|Singular|Embrace|PostHog|GoogleMobileAds|GoogleAnalytics'
if hits="$(grep -rEn "import ($forbidden)" "$root/ios" --include='*.swift' || true)" && [[ -n "$hits" ]]; then
  echo "::error::Forbidden SDK imports:"
  echo "$hits"
  fail=1
else
  echo "No forbidden SDK imports."
fi

echo "== Binary: $app"
binary="$app/$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$app/Info.plist")"
symbols="$(strings -a "$binary"; nm -U "$binary" 2>/dev/null || true)"
for marker in TCPConnector TCPTransport PairlinePairLink PairlineAccess PairlineTCPPort PairlineMarketing 127.0.0.1; do
  if grep -q "$marker" <<<"$symbols"; then
    echo "::error::The Release binary contains debug-only code: $marker (keep it behind #if DEBUG, D-018)."
    fail=1
  fi
done
if grep -Eiq "($forbidden)" <<<"$(otool -L "$binary" 2>/dev/null; ls "$app/Frameworks" 2>/dev/null || true)"; then
  echo "::error::A forbidden SDK is linked or embedded."
  fail=1
fi
if /usr/libexec/PlistBuddy -c 'Print :NSAppTransportSecurity' "$app/Info.plist" >/dev/null 2>&1; then
  echo "::error::Info.plist has App Transport Security exceptions."
  fail=1
fi
[[ "$fail" -eq 0 ]] && echo "Release binary passes the policy checks."
exit "$fail"
