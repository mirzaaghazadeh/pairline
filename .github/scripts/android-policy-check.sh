#!/usr/bin/env bash
# Release-blocking checks on a built Android APK (AGENTS.md rules 2-3, docs/ROADMAP.md M0).
#
#   android-policy-check.sh <apk> [release]
#
# - INTERNET is allowed (D-041); network-code-check.sh confines the code that uses it
# - no analytics, ads, crash-reporting or attribution SDK classes in the dex
# - with "release": the APK must not be debuggable
#
# Needs ANDROID_HOME (or ANDROID_SDK_ROOT). Uses build-tools $BUILD_TOOLS if set, else the newest installed.
set -euo pipefail
export LC_ALL=C  # dexdump output isn't always valid UTF-8

apk="${1:?usage: android-policy-check.sh <apk> [release]}"
mode="${2:-}"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
if [[ -n "${BUILD_TOOLS:-}" ]]; then
  bt="$sdk/build-tools/$BUILD_TOOLS"
else
  bt="$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d | grep -v -- '-rc' | sort -V | tail -1)"
fi
fail=0

echo "== Checking $(basename "$apk") with build-tools $(basename "$bt")"

perms="$("$bt/aapt2" dump permissions "$apk")"
echo "$perms"

# Package prefixes of analytics, ads, crash-reporting, attribution and IP-lookup SDKs (AGENTS.md rule 3).
# Release builds keep class names (-dontobfuscate in app/proguard-rules.pro), which this check relies on.
forbidden='L(com/google/firebase|com/google/android/gms/(ads|analytics|measurement)|com/google/android/ump|com/crashlytics|io/fabric|io/sentry|com/appsflyer|com/adjust/sdk|io/branch|com/mixpanel|com/amplitude|com/segment/analytics|com/bugsnag|com/instabug|com/datadog|com/newrelic|com/microsoft/appcenter|com/facebook|com/onesignal|com/flurry|com/yandex/metrica|io/ipinfo|com/kochava|com/singular|io/embrace|com/heapanalytics|com/posthog)/'
classes="$("$bt/dexdump" "$apk" 2>/dev/null | sed -n "s/^ *Class descriptor *: *'\(.*\)'/\1/p")"
count="$(grep -c . <<<"$classes" || true)"
if [[ "$count" -eq 0 ]]; then
  echo "::error::No classes found in the APK; the forbidden-SDK check can't run."
  fail=1
fi
if hits="$(grep -E "^$forbidden" <<<"$classes" | sed -E 's#^L(([^/]+/){2,3}).*#\1#' | sort -u)" && [[ -n "$hits" ]]; then
  echo "::error::Forbidden SDK classes found:"
  echo "$hits"
  fail=1
else
  echo "No forbidden SDKs among $count classes."
fi

if [[ "$mode" == "release" ]]; then
  badging="$("$bt/aapt2" dump badging "$apk")"  # captured first: grep -q + pipefail would hit SIGPIPE
  if grep -q '^application-debuggable' <<<"$badging"; then
    echo "::error::The release APK is debuggable."
    fail=1
  else
    echo "Not debuggable."
  fi
fi

exit "$fail"
