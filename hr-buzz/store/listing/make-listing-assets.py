#!/usr/bin/env python3
"""Builds the Play listing images from the repo's own assets.

usage: make-listing-assets.py REPO_DIR OUT_DIR
  screenshots: crop status bar and home indicator so the ratio is within Play's 2:1 limit
  icon-512.png: the launcher heart (res/drawable/ic_launcher_foreground.xml) on the icon background
  feature-1024x500.png: dark brand background, heart, name, one-line tagline
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

repo, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)

# Colours from res/values/colors.xml
BG = (0x0E, 0x0E, 0x10)
TEXT = (0xF2, 0xF2, 0xF2)
TEXT2 = (0xA0, 0xA0, 0xA8)
HEART = (0xE5, 0x39, 0x35)
ICON_BG = (0xFF, 0xFF, 0xFF)

BOLD = "/usr/share/fonts/liberation-sans-fonts/LiberationSans-Bold.ttf"
REGULAR = "/usr/share/fonts/liberation-sans-fonts/LiberationSans-Regular.ttf"


def cubic(p0, p1, p2, p3, n=24):
    pts = []
    for i in range(1, n + 1):
        t = i / n
        u = 1 - t
        pts.append((u**3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t**3 * p3[0],
                    u**3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t**3 * p3[1]))
    return pts


def heart_points():
    """The 24-unit heart path from ic_launcher_foreground.xml, flattened to a polygon."""
    pts = [(12, 21.35), (10.55, 20.03)]
    pts += cubic((10.55, 20.03), (5.4, 15.36), (2, 12.28), (2, 8.5))
    pts += cubic((2, 8.5), (2, 5.42), (4.42, 3), (7.5, 3))
    pts += cubic((7.5, 3), (9.24, 3), (10.91, 3.81), (12, 5.09))
    pts += cubic((12, 5.09), (13.09, 3.81), (14.76, 3), (16.5, 3))
    pts += cubic((16.5, 3), (19.58, 3), (22, 5.42), (22, 8.5))
    pts += cubic((22, 8.5), (22, 12.28), (18.6, 15.36), (13.45, 20.04))
    pts.append((12, 21.35))
    return pts


def draw_heart(img, cx, cy, height, fill, ss=4):
    """Draw the heart centred on (cx, cy), `height` px tall, anti-aliased by supersampling."""
    w, h = img.size
    layer = Image.new("L", (w * ss, h * ss), 0)
    d = ImageDraw.Draw(layer)
    scale = height * ss / 18.35  # path spans y 3 .. 21.35
    ox = cx * ss - 12 * scale
    oy = cy * ss - 12.175 * scale
    d.polygon([(ox + x * scale, oy + y * scale) for x, y in heart_points()], fill=255)
    layer = layer.resize((w, h), Image.LANCZOS)
    img.paste(Image.new("RGB", (w, h), fill), (0, 0), layer)


# --- screenshots -----------------------------------------------------------
os.makedirs(os.path.join(out, "screenshots"), exist_ok=True)
for name in ("home", "training", "summary", "route"):
    im = Image.open(os.path.join(repo, f"screenshot-{name}.png")).convert("RGB")
    w, h = im.size
    keep = 2 * w                      # Play: long side at most twice the short side
    top = 140                         # status bar
    im = im.crop((0, top, w, top + keep))
    im.save(os.path.join(out, "screenshots", f"{name}.png"))

# --- icon ------------------------------------------------------------------
icon = Image.new("RGB", (512, 512), ICON_BG)
draw_heart(icon, 256, 256, 300, HEART)
icon.save(os.path.join(out, "icon-512.png"))

# --- feature graphic -------------------------------------------------------
fg = Image.new("RGB", (1024, 500), BG)
draw_heart(fg, 200, 250, 230, HEART)
d = ImageDraw.Draw(fg)
d.text((360, 150), "HR Buzz", font=ImageFont.truetype(BOLD, 110), fill=TEXT)
d.text((364, 285), "A heart-rate alarm for your chest strap", font=ImageFont.truetype(REGULAR, 34), fill=TEXT2)
d.text((364, 335), "Zones, voice guidance. No account, no ads.", font=ImageFont.truetype(REGULAR, 28), fill=TEXT2)
fg.save(os.path.join(out, "feature-1024x500.png"))
print("done")
