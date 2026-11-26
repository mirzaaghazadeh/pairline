#!/bin/zsh
# Renders the README banner: readme-banner.html -> readme-banner.png (2560 x 1280, rounded corners).
# Needs Google Chrome and ImageMagick (`brew install imagemagick`). Run it after retaking the screenshots in
# docs/screenshots or changing the banner's text:
#
#   docs/images/render.sh
set -eu
here=${0:A:h}
chrome="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

"$chrome" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=2 \
  --run-all-compositor-stages-before-draw --window-size=1280,640 --virtual-time-budget=5000 \
  --screenshot="$tmp/shot.png" "file://$here/readme-banner.html" >/dev/null 2>&1

# Round the corners (transparent outside, so it sits well on GitHub's light and dark themes), then keep the file small.
r=56
magick "$tmp/shot.png" -crop 2560x1280+0+0 +repage \
  \( +clone -alpha extract -draw "fill black polygon 0,0 0,$r $r,0 fill white circle $r,$r $r,0" \
     \( +clone -flip \) -compose Multiply -composite \( +clone -flop \) -compose Multiply -composite \) \
  -alpha off -compose CopyOpacity -composite \
  -strip -define png:compression-level=9 -define png:compression-filter=5 "$here/readme-banner.png"
echo "$here/readme-banner.png"
