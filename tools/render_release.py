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

VERSION = "0.10.2"
FEATURES = [
    ("BIG LISTS STAY SMOOTH", "Progress redraws only what moves"),
    ("PLAYLISTS IN ONE STEP", "Added, paused and resumed at once"),
    ("LIGHTER IN THE BACKGROUND", "Fewer notifications, alarms and writes"),
]
# A desktop_*.png is drawn as a window; after it, phone shots stand in front of its corner, and a
# second window (a desktop_*.png or setup_*.png capture) sits at its lower left.
# A panel_*.png (a transparent picture of its own, not a phone) sits on the right as it is.
# 0.10.0 to 0.10.2: phone screens rendered by ReleaseScreensTest (shared/build/release); no phone or emulator
# was connected, and this computer's JDK has no jpackage for the desktop captures.
SHOTS = [("phone_stash.png", "Stash"), ("phone_following.png", "Following")]


def sharing_panel(shots):
    """Compose the real SharingSheetTest captures, without their empty black canvas."""
    panel = Image.new("RGBA", (1120, 800), (0, 0, 0, 0))
    d = ImageDraw.Draw(panel)
    for x, name, label in [(0, "share_link.png", "LINK"), (580, "share_file.png", "FILE")]:
        source = Image.open(shots / name).convert("RGB")
        source = source.crop(source.getbbox()).convert("RGBA")
        source.thumbnail((520, 620), Image.Resampling.LANCZOS)
        # The screenshot includes the sheet's actual rounded shape.
        mask = source.convert("RGB").point(lambda value: 255 if value else 0).convert("L")
        source.putalpha(mask.point(lambda value: 255 if value else 0))
        panel.alpha_composite(source, (x, 130))
        d.text((x + 8, 58), label, font=font("jetbrains_mono_600.ttf", 28), fill=ACCENT)
    panel.save(shots / "panel_sharing.png")




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

    if SHOTS[0][0].startswith("panel_"):
        f = Image.open(shots / SHOTS[0][0]).convert("RGBA")
        img.alpha_composite(f, (W - f.width - 100, (H - f.height) // 2))
        return img.convert("RGB")

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
    shots = pathlib.Path(sys.argv[1])
    if SHOTS[0][0] == "panel_sharing.png":
        sharing_panel(shots)
    release(shots).save(out / f"v{VERSION}.png", optimize=True)
    print("ok")
