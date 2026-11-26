#!/bin/zsh
# Uploads the App Store listing to App Store Connect with fastlane deliver: the text in metadata/ and the screenshots
# render.sh writes to out/ (English, Arabic and Turkish). It doesn't upload a build, doesn't
# change the version and doesn't submit for review.
#
# Needs:
#  - the app record for app.pairline.ios in App Store Connect (Apps > + > New App), with a version in
#    "Prepare for Submission";
#  - an App Store Connect API key with the Admin or App Manager role, described in a JSON file kept OUTSIDE the repo:
#      {"key_id": "ABC123DEFG", "issuer_id": "00000000-0000-0000-0000-000000000000",
#       "key_filepath": "/Users/you/.appstoreconnect/private_keys/AuthKey_ABC123DEFG.p8"}
#
# Usage: ASC_API_KEY_JSON=~/.appstoreconnect/pairline.json docs/app-store/upload.sh
# Text only (keep the uploaded screenshots): SKIP_SCREENSHOTS=true ASC_API_KEY_JSON=… docs/app-store/upload.sh
set -eu
here=${0:A:h}
: ${ASC_API_KEY_JSON:?Set ASC_API_KEY_JSON to the API key JSON file (see the comment at the top)}
# The review notes carry a link to the two-phone video (D-097); never send App Review the placeholder.
if grep -q VIDEO_LINK_HERE "$here/metadata/review_information/notes.txt"; then
  print -u2 "metadata/review_information/notes.txt still says VIDEO_LINK_HERE: put the review video's link there first."
  exit 1
fi
[[ -d $here/out/en-US/large ]] || $here/render.sh

# deliver reads one folder per locale and tells the display size from each image's pixel size.
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
for locale in en-US ar-SA tr; do
  mkdir -p "$stage/$locale"
  for size in large medium; do
    for f in $here/out/$locale/$size/*.png; do cp "$f" "$stage/$locale/$size-${f:t}"; done
  done
done

# deliver only runs non-interactively from a folder that holds metadata/ (otherwise it offers to set itself up).
cd "$here"
fastlane deliver \
  --api_key_path "$ASC_API_KEY_JSON" \
  --app_identifier app.pairline.ios \
  --platform ios \
  --metadata_path "$here/metadata" \
  --screenshots_path "$stage" \
  --overwrite_screenshots true \
  --skip_screenshots "${SKIP_SCREENSHOTS:-false}" \
  --skip_binary_upload true \
  --skip_app_version_update true \
  --submit_for_review false \
  --run_precheck_before_submit false \
  --force true
