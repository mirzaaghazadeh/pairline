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
for marker in TCPConnector TCPTransport PairlinePairLink PairlineAccess PairlineRemoteAccess PairlineTCPPort PairlineMarketing PairlineSendSMS PairlineDial PairlineRemoteSpike remote-spike-token 127.0.0.1; do
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

# Apple's privacy manifest in every bundle (D-097): no tracking and no collected data, as "Data Not Collected" says.
for bundle in "$app" "$app"/PlugIns/*.appex; do
  [[ -d "$bundle" ]] || continue
  manifest="$bundle/PrivacyInfo.xcprivacy"
  if [[ ! -f "$manifest" ]]; then
    echo "::error::$(basename "$bundle") has no PrivacyInfo.xcprivacy (D-097)."
    fail=1
  elif [[ "$(/usr/libexec/PlistBuddy -c 'Print :NSPrivacyTracking' "$manifest" 2>/dev/null)" != "false" ]] ||
       [[ "$(/usr/libexec/PlistBuddy -c 'Print :NSPrivacyCollectedDataTypes' "$manifest" 2>/dev/null | grep -c Dict)" != "0" ]]; then
    echo "::error::$(basename "$bundle")'s privacy manifest must say no tracking and no collected data (D-097)."
    fail=1
  fi
done
[[ "$fail" -eq 0 ]] && echo "Release binary passes the policy checks."
exit "$fail"
