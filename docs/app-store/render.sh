#!/bin/zsh
# Renders the App Store screenshots from slides.html with headless Chrome and ImageMagick:
#   out/<locale>/large/NN.png   1320 x 2868  (iPhone with Dynamic Island, large display)
#   out/<locale>/medium/NN.png  1206 x 2622  (iPhone with Dynamic Island, medium display: the required size)
#   out/<locale>/assets/header.png  3840 x 1646  (iOS 27 product page header, Asset Library)
#   out/<locale>/assets/search.png  3840 x 2560  (iOS 27 search results asset, Asset Library)
# the in-app purchase promotional images from iap.html: out/iap/{full,trial}.png (1024 x 1024),
# and each in-app event's images from events.html (one per folder in events/):
#   out/<locale>/events/<event>/card.png     3840 x 2160  (event card)
#   out/<locale>/events/<event>/details.png  2160 x 3840  (event details page)
# Locales: en-US, ar-SA and tr (App Store Connect locale codes; Turkish has no region).
# Every output is flattened: App Store Connect rejects images with an alpha channel.
# Usage: render.sh [slides] [devices] [assets] [iap] [events]   (no arguments renders everything)
set -eu
here=${0:A:h}
chrome="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
out=$here/out
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
parts=("$@")
(( $#parts )) || parts=(slides devices assets iap events)
want() { (( ${parts[(Ie)$1]} )) }

shoot() { # url width height output
  # Headless Chrome sometimes captures before every tile is drawn, leaving a white block. The backgrounds are dark
  # everywhere along the bottom edge, so a light bottom corner means a bad capture: retry.
  local attempt
  for attempt in 1 2 3 4 5; do
    "$chrome" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
      --run-all-compositor-stages-before-draw --window-size="$2,$3" --virtual-time-budget=5000 \
      --screenshot="$tmp/shot.png" "$1" >/dev/null 2>&1
    magick "$tmp/shot.png" -crop "${2}x${3}+0+0" +repage -background '#0b1d3d' -alpha remove -alpha off "$4"
    # Brightness, not separate channels: ImageMagick stores an all-white capture as grayscale, which has no green.
    local light=$(magick "$4" -gravity southeast -crop 200x40+0+0 +repage -colorspace Gray -format "%[fx:maxima>0.9 ? 1 : 0]" info:)
    [[ "$light" == 0 ]] && return 0
    echo "  retrying $4 (incomplete capture)"
  done
  echo "error: $4 still has an incomplete capture" >&2
  return 1
}

typeset -A locales=(en en-US ar ar-SA tr tr)
want slides && for lang locale in ${(kv)locales}; do
  mkdir -p "$out/$locale/large" "$out/$locale/medium"
  for s in 1 2 3 4 5 6 7; do
    n=$(printf "%02d" $s)
    shoot "file://$here/slides.html#lang=$lang&s=$s" 1320 2868 "$out/$locale/large/$n.png"
    magick "$out/$locale/large/$n.png" -resize '1206x2622!' "$out/$locale/medium/$n.png"
    echo "$locale $n"
  done
done

# The same slides for iPad 13-inch and iPhone Duo (inner display, open), from docs/screenshots/{ipad,duo}:
#   out/<locale>/ipad/NN.png  2064 x 2752     out/<locale>/duo/NN.png  2853 x 2007
typeset -A sizes=(ipad "2064 2752" duo "2853 2007")
want devices && for lang locale in ${(kv)locales}; do
  for device in ipad duo; do
    [[ -d $here/../screenshots/$device/$lang ]] || continue
    mkdir -p "$out/$locale/$device"
    read w h <<< "${sizes[$device]}"
    for s in 1 2 3 4 5 6 7; do
      n=$(printf "%02d" $s)
      shoot "file://$here/slides.html#lang=$lang&s=$s&device=$device" $w $h "$out/$locale/$device/$n.png"
    done
    echo "$locale $device"
  done
done

want assets && for lang locale in ${(kv)locales}; do
  mkdir -p "$out/$locale/assets"
  shoot "file://$here/assets.html#lang=$lang&kind=header" 3840 1646 "$out/$locale/assets/header.png"
  shoot "file://$here/assets.html#lang=$lang&kind=search" 3840 2560 "$out/$locale/assets/search.png"
  echo "$locale header + search"
done

want iap && for kind in full trial; do
  mkdir -p "$out/iap"
  shoot "file://$here/iap.html#kind=$kind" 1024 1024 "$out/iap/$kind.png"
  echo "iap $kind"
done

want events && for event in $here/events/*(N/:t); do
  for lang locale in ${(kv)locales}; do
    mkdir -p "$out/$locale/events/$event"
    shoot "file://$here/events.html#event=$event&lang=$lang&kind=card" 3840 2160 "$out/$locale/events/$event/card.png"
    shoot "file://$here/events.html#event=$event&lang=$lang&kind=details" 2160 3840 "$out/$locale/events/$event/details.png"
    echo "$locale event $event"
  done
done
true
