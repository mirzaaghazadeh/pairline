#!/bin/zsh
# Checks the claims behind the App Store's Accessibility Nutrition Labels (D-091, D-096): Reduce Motion in the code,
# then the accessibility audit (PairlineAccessibilityTests) on every screen it can open in demo mode, in several modes,
# and a report of what needs fixing. Run it after UI changes and before every release.
#
#   ios/tools/accessibility-audit.sh          light, large text, Increase Contrast light and dark, dark, then large text
#                                             in Persian and Arabic (about 40 minutes)
#   ios/tools/accessibility-audit.sh quick    light, large text and Increase Contrast light, in English (about 15)
#
# Needs Xcode, XcodeGen and ImageMagick (`magick`). Makes its own simulator and deletes it at the end; the
# screenshots and logs stay in the folder it prints (A11Y_DIR to choose one). Exits 1 when something needs fixing
# that isn't in tools/accessibility-baseline.txt.
set -eu
here=${0:A:h}
ios=${here:h}
mode=${1:-full}
out=${A11Y_DIR:-$(mktemp -d -t pairline-a11y)}
mkdir -p $out
device=${A11Y_DEVICE:-iPhone 18 Pro}
large=UICTContentSizeCategoryAccessibilityXL

python3 -I $here/accessibility_report.py static $ios/Pairline || motion_failed=1

runtime=$(xcrun simctl list runtimes available | awk '/^iOS/ {id=$NF} END {print id}')
udid=$(xcrun simctl create "Pairline accessibility audit" "$device" "$runtime")
trap 'xcrun simctl shutdown $udid >/dev/null 2>&1; xcrun simctl delete $udid >/dev/null 2>&1' EXIT
xcrun simctl boot $udid
echo "Audit in $out (simulator $device, $runtime)"

cd $ios
xcodegen generate >/dev/null
xcodebuild -scheme Accessibility -destination "id=$udid" -derivedDataPath $out/build CODE_SIGN_IDENTITY=- \
  build-for-testing -quiet > $out/build.log 2>&1 || { echo "build failed: $out/build.log" >&2; exit 2; }
mkdir -p $out/shots

run() { # tag appearance increase-contrast size language
  xcrun simctl ui $udid appearance $2
  xcrun simctl ui $udid increase_contrast $3
  local -a settings=(TEST_RUNNER_A11Y_OUT=$out/shots TEST_RUNNER_A11Y_TAG=$1 TEST_RUNNER_A11Y_LANG=$5)
  [[ -n $4 ]] && settings+=(TEST_RUNNER_A11Y_SIZE=$4)
  env $settings xcodebuild -scheme Accessibility -destination "id=$udid" -derivedDataPath $out/build \
    CODE_SIGN_IDENTITY=- test-without-building > $out/$1.log 2>&1 || { echo "audit run '$1' failed: $out/$1.log" >&2; exit 2; }
  echo "  $1: $(grep -c '^A11Y|' $out/$1.log) findings on $(grep -c '^A11Y-SCREEN|' $out/$1.log) screens"
}

run light light disabled "" en
run large light disabled $large en
run hc-light light enabled "" en
if [[ $mode != quick ]]; then
  run hc-dark dark enabled "" en
  run dark dark disabled "" en
  run fa-large light disabled $large fa
  run ar-large light disabled $large ar
fi

echo
python3 -I $here/accessibility_report.py audit $out $here/accessibility-baseline.txt || exit 1
(( ${motion_failed:-0} == 0 )) || exit 1
