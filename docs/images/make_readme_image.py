# Builds the README banner (docs/images/readme-screens.png): a few app screenshots from docs/screenshots as framed
# phones on a soft gradient card. Run it again after retaking those screenshots:
#
#   python3 -I docs/images/make_readme_image.py docs/screenshots docs/images/readme-screens.png
#
# Needs Pillow (`pip install pillow`) and macOS's SF Pro font. A maintainer tool only; nothing here ships in the apps.
import sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

shots_dir, out = sys.argv[1], sys.argv[2]
SCREENS = [  # (file, caption)
    ("android/en/4-home.png", "Android: Pairline Bridge"),
    ("ios/en/04-messages.png", "SMS and one-time codes"),
    ("ios/en/06-incoming-call.png", "Calls ring on your iPhone"),
    ("ios/en/08-bridge-status.png", "SIM, signal and battery"),
]

W, H = 2400, 1350
PHONE_H = 1000  # screen height
BEZEL = 16
RADIUS = 64  # screen corner radius
GAP = 84
STAGGER = 44  # outer phones sit a little lower

# Card background: soft diagonal gradient with rounded corners.
def gradient(w, h, c1, c2):
    g = Image.new("RGB", (w, h))
    px = g.load()
    for y in range(h):
        for x in range(0, w):
            t = (x / w * 0.6 + y / h * 0.4)
            px[x, y] = tuple(int(a + (b - a) * t) for a, b in zip(c1, c2))
    return g

bg = gradient(W // 4, H // 4, (234, 241, 255), (243, 236, 255)).resize((W, H), Image.BICUBIC)
card = Image.new("RGBA", (W, H), (0, 0, 0, 0))
mask = Image.new("L", (W, H), 0)
ImageDraw.Draw(mask).rounded_rectangle((0, 0, W - 1, H - 1), radius=48, fill=255)
card.paste(bg, (0, 0), mask)

# Faint glow behind the centre.
glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
ImageDraw.Draw(glow).ellipse((W * 0.2, H * 0.1, W * 0.8, H * 0.95), fill=(120, 150, 255, 60))
card = Image.alpha_composite(card, glow.filter(ImageFilter.GaussianBlur(160)))

font = ImageFont.truetype("/System/Library/Fonts/SFNS.ttf", 38)
font.set_variation_by_name("Semibold")

phones = []
for f, caption in SCREENS:
    im = Image.open(f"{shots_dir}/{f}").convert("RGB")
    w = round(im.width * PHONE_H / im.height)
    phones.append((im.resize((w, PHONE_H), Image.LANCZOS), caption))

total = sum(p.width + 2 * BEZEL for p, _ in phones) + GAP * (len(phones) - 1)
x = (W - total) // 2
top = 110
for i, (screen, caption) in enumerate(phones):
    y = top + (STAGGER if i in (0, len(phones) - 1) else 0)
    fw, fh = screen.width + 2 * BEZEL, screen.height + 2 * BEZEL

    shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle((x + 10, y + 34, x + fw - 10, y + fh + 20), radius=RADIUS + BEZEL, fill=(30, 40, 90, 70))
    card = Image.alpha_composite(card, shadow.filter(ImageFilter.GaussianBlur(34)))

    frame = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(frame)
    d.rounded_rectangle((x, y, x + fw, y + fh), radius=RADIUS + BEZEL, fill=(14, 16, 22, 255))
    d.rounded_rectangle((x + 2, y + 2, x + fw - 2, y + fh - 2), radius=RADIUS + BEZEL - 2, outline=(70, 74, 86, 255), width=2)
    card = Image.alpha_composite(card, frame)

    smask = Image.new("L", screen.size, 0)
    ImageDraw.Draw(smask).rounded_rectangle((0, 0, screen.width - 1, screen.height - 1), radius=RADIUS, fill=255)
    card.paste(screen, (x + BEZEL, y + BEZEL), smask)

    d = ImageDraw.Draw(card)
    tw = d.textlength(caption, font=font)
    d.text((x + fw / 2 - tw / 2, y + fh + 40), caption, font=font, fill=(40, 48, 72))
    x += fw + GAP

card.save(out, optimize=True)
print(out, card.size)
