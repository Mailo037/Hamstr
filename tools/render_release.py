"""Composes the picture on top of a release's notes from emulator screenshots.

    python tools/render_release.py <screenshots dir>

Expects the SHOTS (1080x2400, from tools/demo_screens.py) in that folder and writes
docs/images/releases/v<VERSION>.png. For every release, change VERSION, FEATURES and
SHOTS to what is new; older pictures stay in docs/images/releases.
"""
import sys
import pathlib
from PIL import Image, ImageDraw

from render_product import ACCENT, CANVAS, INK, MUTED, OUT, drop, font, glow, phone, raster, window

VERSION = "0.7.4"
FEATURES = [
    ("ALBUMS", "One row in the stash, the songs inside"),
    ("PLAYLISTS", "Hold one: play, share, rename, delete"),
    ("DESKTOP", "Right-click menus, icons for swipes"),
    ("SPOTIFY", "Locked songs found by their real name"),
]
# A desktop_*.png is drawn as a window; after it, phone shots stand in front of its corner, and a
# second window (a desktop_*.png or setup_*.png capture) sits at its lower left.
SHOTS = [("desktop_album.png", "Stash"), ("desktop_devices_crop.png", "Devices")]




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

    if SHOTS[0][0].startswith("desktop_"):
        drop(img, window(shots / SHOTS[0][0], 1180), (1080, 120), blur=50, alpha=200)
        # The phone stands on the bottom edge, so a sheet at the bottom of its screen shows whole.
        for i, (name, _) in enumerate(SHOTS[1:]):
            if name.startswith(("setup_", "desktop_")):
                # Lower left, over the sidebar's empty end, so the window's own content stays in view.
                f = window(shots / name, 540)
                drop(img, f, (890, img.height - f.height - 50), blur=50, alpha=220)
                continue
            f = phone(shots / name, 420)
            drop(img, f, (1900 - i * 460, img.height - f.height - 40), blur=50, alpha=220)
        return img.convert("RGB")

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
