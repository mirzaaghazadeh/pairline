#!/bin/zsh
# Uploads the iOS 27 product-page header and search-results images (out/<locale>/assets/, from render.sh) to the
# app's Asset Library with the asc CLI, and places each locale's pair on that locale's version localization.
# Asset Library images go through App Review with the version (asc review items add --item-type appAssetLibraryImages).
#
# Needs an asc auth profile for the team that owns the app (asc auth login …); pass it as ASC_PROFILE.
# Usage: ASC_PROFILE=<profile> docs/app-store/upload-assets.sh [version, default 1.0]
set -eu
here=${0:A:h}
app=6838659501
version=${1:-1.0}
: ${ASC_PROFILE:?Set ASC_PROFILE to the asc auth profile of the team that owns the app}
[[ -f $here/out/en-US/assets/header.png ]] || $here/render.sh

library=$(asc asset-library view --app $app --output json | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["data"]["id"])')
version_id=$(asc versions list --app $app --output json |
  python3 -I -c 'import json,sys; v=sys.argv[1]; print(next(x["id"] for x in json.load(sys.stdin)["data"] if x["attributes"]["versionString"]==v))' "$version")
echo "library $library, version $version ($version_id)"

upload() { # file -> image id
  asc asset-library images upload --library-id "$library" --file "$1" --output json |
    python3 -I -c 'import json,sys; print(json.load(sys.stdin)["imageId"])'
}

for locale in en-US ar-SA tr; do
  loc_id=$(asc localizations list --version "$version_id" --locale "$locale" --output json |
    python3 -I -c 'import json,sys; print(json.load(sys.stdin)["data"][0]["id"])')
  header=$(upload "$here/out/$locale/assets/header.png")
  search=$(upload "$here/out/$locale/assets/search.png")
  asc localizations placements create --localization-id "$loc_id" --image-id "$header" \
    --placement-type PRODUCT_PAGE_HEADER_ASSET --placement-group DEFAULT_PROFILE --output json >/dev/null
  asc localizations placements create --localization-id "$loc_id" --image-id "$search" \
    --placement-type APP_STORE_SEARCH_RESULTS_ASSET --placement-group DEFAULT_PROFILE --output json >/dev/null
  echo "$locale: header $header, search $search"
done
