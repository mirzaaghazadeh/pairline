#!/bin/zsh
# Fills in one custom product page from custom-pages/<page>/ with the asc CLI, adding only what's missing:
#   - the page (found by NAME in page.env) and its editable version, created if they don't exist,
#   - each locale's promotional text (created, or updated to match <locale>/promotional_text.txt),
#   - each locale's screenshots for iPhone 6.7" and 6.1", iPad 13" and iPhone Duo, in page.env's ORDER, taken from
#     render.sh's out/<locale>/{large,medium,ipad,duo}/; a device size that already has screenshots is left alone,
#   - the search keywords in <locale>/keywords.txt (Apple accepts them only once the app is released in that locale;
#     each keyword can lead to one page, and it should come from the app's own keyword list).
# It never submits. Check the page in App Store Connect, then submit it there.
#
# Needs an asc auth profile for the team that owns the app (asc auth login …); pass it as ASC_PROFILE.
# Usage: ASC_PROFILE=<profile> docs/app-store/upload-custom-page.sh <page>   e.g. travel
set -eu
here=${0:A:h}
app=6838659501
page=${1:?Usage: upload-custom-page.sh <folder in custom-pages/>}
dir=$here/custom-pages/$page
: ${ASC_PROFILE:?Set ASC_PROFILE to the asc auth profile of the team that owns the app}
[[ -f $dir/page.env ]] || { echo "no $dir/page.env" >&2; exit 1; }
source $dir/page.env
order=(${=ORDER})
locales=(en-US ar-SA tr)
typeset -A sizes=(APP_IPHONE_67 large APP_IPHONE_61 medium APP_IPAD_PRO_3GEN_129 ipad APP_IPHONE_DUO duo)
json() { python3 -I -c "import json,sys; d=json.load(sys.stdin); print($1)" "${@:2}"; }
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

for locale in $locales; do
  n=$(python3 -I -c 'import sys; print(len(open(sys.argv[1]).read().strip()))' "$dir/$locale/promotional_text.txt")
  (( n <= 170 )) || { echo "$locale/promotional_text.txt is $n characters (at most 170)" >&2; exit 1; }
done
[[ -f $here/out/en-US/large/01.png ]] || $here/render.sh slides devices

id=$(asc product-pages custom-pages list --app $app --output json |
  json 'next((p["id"] for p in d["data"] if p["attributes"]["name"]==sys.argv[1]), "")' "$NAME")
[[ -n $id ]] || { id=$(asc product-pages custom-pages create --app $app --name "$NAME" --output json | json 'd["data"]["id"]'); echo "created page $id"; }
version=$(asc product-pages custom-pages versions list --custom-page-id $id --output json |
  json 'next((v["id"] for v in d["data"] if v["attributes"].get("state") in ("PREPARE_FOR_SUBMISSION", "REJECTED")), "")')
[[ -n $version ]] || version=$(asc product-pages custom-pages versions create --custom-page-id $id --output json | json 'd["data"]["id"]')
echo "page \"$NAME\": $id, version $version"

for locale in $locales; do
  text=$(<$dir/$locale/promotional_text.txt)
  loc=$(asc product-pages custom-pages localizations list --custom-page-version-id $version --output json |
    json 'next((l["id"] for l in d["data"] if l["attributes"]["locale"]==sys.argv[1]), "")' "$locale")
  if [[ -z $loc ]]; then
    loc=$(asc product-pages custom-pages localizations create --custom-page-version-id $version --locale $locale \
      --promotional-text "$text" --output json | json 'd["data"]["id"]')
    echo "$locale: created"
  else
    asc product-pages custom-pages localizations update --localization-id $loc --promotional-text "$text" --output json >/dev/null
  fi

  have=$(asc product-pages custom-pages localizations screenshot-sets list --localization-id $loc --output json |
    json '" ".join(s["attributes"]["screenshotDisplayType"] for s in d["data"])')
  for type folder in ${(kv)sizes}; do
    [[ " $have " == *" $type "* ]] && continue
    [[ -d $here/out/$locale/$folder ]] || { echo "$locale: no out/$locale/$folder, skipping $type" >&2; continue; }
    rm -rf $tmp/$type && mkdir -p $tmp/$type
    i=1; for s in $order; do cp $here/out/$locale/$folder/$s.png $tmp/$type/$(printf "%02d" $i).png; (( i++ )); done
    asc product-pages custom-pages localizations screenshot-sets upload --localization-id $loc --path $tmp/$type \
      --device-type $type --output json >/dev/null
    echo "$locale: $type screenshots uploaded"
  done

  if [[ -s $dir/$locale/keywords.txt ]]; then
    asc product-pages custom-pages localizations search-keywords add --localization-id $loc \
      --keywords "$(<$dir/$locale/keywords.txt)" --output json >/dev/null 2>$tmp/kw.err &&
      echo "$locale: keywords added" ||
      echo "$locale: keywords not added yet ($(head -c 160 $tmp/kw.err))"
  fi
done
echo "Not submitted. Review: https://appstoreconnect.apple.com/apps/$app/distribution/custom-product-pages"
