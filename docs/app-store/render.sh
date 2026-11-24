#!/bin/zsh
# Renders the App Store screenshots from slides.html with headless Chrome and ImageMagick:
#   out/<locale>/large/NN.png   1320 x 2868  (iPhone with Dynamic Island, large display)
#   out/<locale>/medium/NN.png  1206 x 2622  (iPhone with Dynamic Island, medium display: the required size)
# and the in-app purchase promotional images from iap.html: out/iap/{full,trial}.png (1024 x 1024).
# Locales: en-US, ar-SA and tr-TR (App Store Connect locale codes).
# Every output is flattened: App Store Connect rejects images with an alpha channel.
set -eu
here=${0:A:h}
chrome="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
out=$here/out
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

shoot() { # url width height output
  # Headless Chrome sometimes captures before every tile is drawn, leaving a white block. The backgrounds are dark
  # everywhere along the bottom edge, so a light bottom corner means a bad capture: retry.
  local attempt
  for attempt in 1 2 3 4 5; do
    "$chrome" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
      --run-all-compositor-stages-before-draw --window-size="$2,$3" --virtual-time-budget=5000 \
      --screenshot="$tmp/shot.png" "$1" >/dev/null 2>&1
    magick "$tmp/shot.png" -crop "${2}x${3}+0+0" +repage -background '#0b1d3d' -alpha remove -alpha off "$4"
    local light=$(magick "$4" -gravity southeast -crop 200x40+0+0 +repage -format "%[fx:maxima.r>0.9 && maxima.g>0.9 ? 1 : 0]" info:)
    [[ "$light" == 0 ]] && return 0
    echo "  retrying $4 (incomplete capture)"
  done
  echo "error: $4 still has an incomplete capture" >&2
  return 1
}

typeset -A locales=(en en-US ar ar-SA tr tr-TR)
for lang locale in ${(kv)locales}; do
  mkdir -p "$out/$locale/large" "$out/$locale/medium"
  for s in 1 2 3 4 5 6 7; do
    n=$(printf "%02d" $s)
    shoot "file://$here/slides.html#lang=$lang&s=$s" 1320 2868 "$out/$locale/large/$n.png"
    magick "$out/$locale/large/$n.png" -resize '1206x2622!' "$out/$locale/medium/$n.png"
    echo "$locale $n"
  done
done

mkdir -p "$out/iap"
for kind in full trial; do
  shoot "file://$here/iap.html#kind=$kind" 1024 1024 "$out/iap/$kind.png"
  echo "iap $kind"
done
