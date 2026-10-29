"""Composes the picture on top of a release's notes from emulator screenshots.

    python tools/render_release.py <screenshots dir>

Expects the SHOTS (1080x2400, from tools/demo_screens.py) in that folder and writes
docs/images/releases/v<VERSION>.png. For every release, change VERSION, FEATURES and
SHOTS to what is new; older pictures stay in docs/images/releases.
"""
import sys
import pathlib
from PIL import Image, ImageDraw

from render_product import ACCENT, CANVAS, INK, MUTED, OUT, drop, font, glow, phone, raster

VERSION = "0.7.0"
FEATURES = [
    ("NEARBY", "Send a link to your other Hamstr, like AirDrop"),
    ("WI-FI + BLUETOOTH", "Finds devices by itself, asks once, then trusts"),
    ("DESKTOP", "Hamstr for Windows, macOS and Linux"),
    ("SMALL FIXES", "No more stray backslashes in the text"),
]
SHOTS = [("send.png", "Send"), ("share_send.png", "Share"), ("menu.png", "Menu")]



def release(shots):
    W, H = 2400, 1100
    img = Image.new("RGBA", (W, H), CANVAS + (255,))
    glow(img, (300, 0), 560, ACCENT, 40)
    glow(img, (1750, 560), 640, ACCENT, 22)
    raster(img, 30, 420, seed=11)
    d = ImageDraw.Draw(img)
    d.text((132, 120), "Hamstr", font=font("inter_600.ttf", 150), fill=INK)
    d.text((142, 320), f"[ v{VERSION} ]", font=font("jetbrains_mono_600.ttf", 44), fill=ACCENT)
    d.text((142, 410), "What's new", font=font("inter_500.ttf", 62), fill=INK)
    y = 540
    for title, sub in FEATURES:
        d.text((142, y), "*", font=font("jetbrains_mono_600.ttf", 32), fill=ACCENT)
        d.text((186, y), title, font=font("jetbrains_mono_600.ttf", 32), fill=INK)
        d.text((186, y + 46), sub, font=font("inter_400.ttf", 34), fill=MUTED)
        y += 132

    pw, gap, x0, top = 400, 44, 1020, 90
    bf = font("jetbrains_mono_600.ttf", 28)
    for i, (name, label) in enumerate(SHOTS):
        f = phone(shots / name, pw)
        x = x0 + i * (pw + gap)
        drop(img, f, (x, top), blur=34, alpha=160)
        d = ImageDraw.Draw(img)
        d.text((x + pw / 2, top + f.height + 44), f"[ {label.upper()} ]", font=bf, fill=ACCENT if i == 0 else MUTED, anchor="mm")
    return img.convert("RGB")


if __name__ == "__main__":
    out = OUT / "releases"
    out.mkdir(parents=True, exist_ok=True)
    release(pathlib.Path(sys.argv[1])).save(out / f"v{VERSION}.png", optimize=True)
    print("ok")
