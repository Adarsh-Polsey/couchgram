"""Renders Couchgram's launcher images into res/drawable-xhdpi/.

Fire TV ignores android:banner for sideloaded apps and draws the *icon* squeezed into a square in
the middle of a grey tile (a wide icon gets distorted, not stretched), so the icon is a square:
"Couch" over "gram" on the rose-to-violet gradient. The 16:9 banner is for launchers that use it.
After changing them, Fire TV only picks them up after: uninstall, install, `pm trim-caches 999G`,
open Your Apps, reboot. Usage:
    python3 docs/logo.py app/src/main/res/drawable-xhdpi
"""
from PIL import Image, ImageDraw, ImageFont
import sys

TTC = "/System/Library/Fonts/Avenir Next.ttc"
ROSE, VIOLET = (214, 64, 122), (108, 84, 214)  # deep enough for white text
SHADOW = (40, 20, 70)


def face(style, size):
    for i in range(12):
        try:
            f = ImageFont.truetype(TTC, size, index=i)
        except OSError:
            break
        if f.getname()[1] == style:
            return f
    raise SystemExit("Avenir Next " + style + " not found")


def gradient(w, h):
    img = Image.new("RGB", (w, h))
    px = img.load()
    for x in range(w):
        for y in range(h):
            t = (x / (w - 1)) * 0.6 + (y / (h - 1)) * 0.4
            px[x, y] = tuple(round(ROSE[i] + (VIOLET[i] - ROSE[i]) * t) for i in range(3))
    return img


def centred(d, text, font, cx, cy):
    left, top, right, bottom = d.textbbox((0, 0), text, font=font)
    x, y = cx - (right - left) / 2 - left, cy - (bottom - top) / 2 - top
    d.text((x + 2, y + 3), text, font=font, fill=SHADOW)
    d.text((x, y), text, font=font, fill=(255, 255, 255))


out = sys.argv[1]

# Banner, 320x180dp at xhdpi.
W, H = 640, 360
banner = gradient(W, H)
centred(ImageDraw.Draw(banner), "Couchgram", face("Bold", 88), W / 2, H / 2)
banner.save(out + "/banner.png")

# Icon, 96dp square at xhdpi (192px): two lines, rounded corners.
S = 192
icon = Image.new("RGBA", (S, S), (0, 0, 0, 0))
mask = Image.new("L", (S, S), 0)
ImageDraw.Draw(mask).rounded_rectangle((0, 0, S - 1, S - 1), radius=36, fill=255)
icon.paste(gradient(S, S).convert("RGBA"), (0, 0), mask)
d = ImageDraw.Draw(icon)
f = face("Bold", 48)
centred(d, "Couch", f, S / 2, S * 0.34)
centred(d, "gram", f, S / 2, S * 0.68)
icon.save(out + "/ic_launcher.png")
print("ok")
