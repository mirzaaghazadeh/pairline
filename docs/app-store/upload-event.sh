#!/bin/zsh
# Creates or updates one in-app event from events/<event>/ with the asc CLI:
#   - the event itself from event.env (found again by its reference name, so running this twice updates it),
#   - its name and descriptions in en-US, ar-SA and tr from <locale>/*.txt,
#   - its card and details-page images (out/<locale>/events/<event>/, from render.sh), uploaded to the app's Asset
#     Library and placed on each locale (replacing what was placed before).
# It never submits. Check the event in App Store Connect (and its purchase requirement: the CLI only offers
# "no cost associated"), then: asc app-events submit --event-id <id> --app 6838659501 --confirm
#
# Needs an asc auth profile for the team that owns the app (asc auth login …); pass it as ASC_PROFILE.
# Usage: ASC_PROFILE=<profile> docs/app-store/upload-event.sh <event>   e.g. talk-on-iphone
set -eu
here=${0:A:h}
app=6838659501
event=${1:?Usage: upload-event.sh <folder in events/>}
dir=$here/events/$event
: ${ASC_PROFILE:?Set ASC_PROFILE to the asc auth profile of the team that owns the app}
[[ -f $dir/event.env ]] || { echo "no $dir/event.env" >&2; exit 1; }
source $dir/event.env
locales=(en-US ar-SA tr)
json() { python3 -I -c "import json,sys; d=json.load(sys.stdin); print($1)" "${@:2}"; }

for locale in $locales; do
  for f in name:30 short_description:50 long_description:120; do
    n=$(python3 -I -c 'import sys; print(len(open(sys.argv[1]).read().strip()))' "$dir/$locale/${f%%:*}.txt")
    (( n <= ${f##*:} )) || { echo "$locale/${f%%:*}.txt is $n characters (at most ${f##*:})" >&2; exit 1; }
  done
done
[[ -f $here/out/en-US/events/$event/card.png ]] || $here/render.sh events

opts=(--name "$REFERENCE_NAME" --event-type "$EVENT_TYPE" --purpose "$PURPOSE" --priority "$PRIORITY" --primary-locale en-US)
[[ -n ${DEEP_LINK:-} ]] && opts+=(--deep-link "$DEEP_LINK")
[[ -n ${PUBLISH_START:-} ]] && opts+=(--publish-start "$PUBLISH_START")
[[ -n ${START:-} ]] && opts+=(--start "$START")
[[ -n ${END:-} ]] && opts+=(--end "$END")
[[ -n ${TERRITORIES:-} ]] && opts+=(--territories "$TERRITORIES")

id=$(asc app-events list --app $app --paginate --output json |
  json 'next((e["id"] for e in d["data"] if e["attributes"].get("referenceName")==sys.argv[1]), "")' "$REFERENCE_NAME")
if [[ -z $id ]]; then
  id=$(asc app-events create --app $app $opts --output json | json 'd["data"]["id"]')
  echo "created event $id"
else
  asc app-events update --event-id $id $opts --output json >/dev/null
  echo "updated event $id"
fi

library=$(asc asset-library view --app $app --output json | json 'd["data"]["id"]')
upload() { # file -> Asset Library image id
  asc asset-library images upload --library-id "$library" --file "$1" --output json | json 'd["imageId"]'
}

for locale in $locales; do
  text=(--name "$(<$dir/$locale/name.txt)" --short-description "$(<$dir/$locale/short_description.txt)"
        --long-description "$(<$dir/$locale/long_description.txt)")
  loc=$(asc app-events localizations list --event-id $id --output json |
    json 'next((l["id"] for l in d["data"] if l["attributes"]["locale"]==sys.argv[1]), "")' "$locale")
  if [[ -z $loc ]]; then
    loc=$(asc app-events localizations create --event-id $id --locale $locale $text --output json | json 'd["data"]["id"]')
  else
    asc app-events localizations update --localization-id $loc $text --output json >/dev/null
  fi

  old=$(asc app-events localizations placements list --localization-id $loc --paginate --output json |
    json '",".join(p["id"] for p in d.get("data", []))')
  [[ -n $old ]] && asc app-events localizations placements delete --placement-ids "$old" --confirm --output json >/dev/null
  for kind type in card EVENT_CARD_ASSET details EVENT_DETAILS_PAGE_ASSET; do
    image=$(upload "$here/out/$locale/events/$event/$kind.png")
    asc app-events localizations placements create --localization-id $loc --image-id "$image" \
      --placement-type $type --placement-group DEFAULT_PROFILE --output json >/dev/null
  done
  echo "$locale: text and images"
done
echo "Not submitted. Review: https://appstoreconnect.apple.com/apps/$app/distribution/events"
