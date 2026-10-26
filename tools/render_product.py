"""Composes the README product images from emulator screenshots.

    python tools/render_product.py <screenshots dir>

Expects stash.png, share.png, player.png, library.png, settings.png (1080x2400) in that
folder and writes docs/images/hero.png and docs/images/screens.png.
"""
import random
import sys
import pathlib
from PIL import Image, ImageDraw, ImageFilter, ImageFont

from render_icon import render as render_mark

ROOT = pathlib.Path(__file__).resolve().parent.parent
FONTS = ROOT / "app/src/main/res/font"
OUT = ROOT / "docs/images"

CANVAS, OVERLAY, LINE = (20, 20, 20), (30, 30, 30), (54, 54, 54)
INK, MUTED, GHOST = (238, 238, 238), (170, 170, 170), (138, 138, 138)
ACCENT, BLACK = (255, 154, 60), (10, 10, 9)
NOISE = " .:-=+*#"


def font(name, size):
    return ImageFont.truetype(str(FONTS / name), size)


def raster(img, cell, height, seed=7):
    """Slate canvas with Glyph's character raster, a band that fades out downwards."""
    rnd = random.Random(seed)
    w, _ = img.size
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    f = font("jetbrains_mono_400.ttf", int(cell * 0.85))
    for y in range(0, height, cell):
        k = (1 - y / height) ** 1.4
        for x in range(0, w, cell):
            if rnd.random() > 0.55 * k:
                continue
            ch = NOISE[1 + min(len(NOISE) - 2, int(k * (len(NOISE) - 1) * rnd.uniform(0.3, 1.1)))]
            hot = rnd.random() < 0.05
            d.text((x + cell / 2, y + cell / 2), ch, font=f, fill=(ACCENT if hot else INK) + (int(10 + 46 * k),), anchor="mm")
    img.alpha_composite(layer)


def glow(img, at, radius, color, alpha):
    w, h = img.size
    g = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(g).ellipse((at[0] - radius, at[1] - radius, at[0] + radius, at[1] + radius), fill=color + (alpha,))
    img.alpha_composite(g.filter(ImageFilter.GaussianBlur(radius * 0.6)))


def phone(shot, width):
    """A screenshot in a plain dark device frame with rounded corners."""
    s = Image.open(shot).convert("RGBA")
    bezel = max(6, width // 42)
    inner_w = width - 2 * bezel
    inner_h = round(s.height * inner_w / s.width)
    s = s.resize((inner_w, inner_h), Image.LANCZOS)
    h = inner_h + 2 * bezel
    r_out, r_in = int(width * 0.13), int(width * 0.13) - bezel
    frame = Image.new("RGBA", (width, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(frame)
    d.rounded_rectangle((0, 0, width - 1, h - 1), r_out, fill=BLACK + (255,), outline=LINE + (255,), width=max(2, width // 220))
    mask = Image.new("L", (inner_w, inner_h), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, inner_w - 1, inner_h - 1), r_in, fill=255)
    frame.paste(s, (bezel, bezel), mask)
    return frame


def drop(img, frame, xy, blur=40, alpha=170):
    x, y = xy
    shadow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    m = Image.new("L", frame.size, 0)
    m.paste(frame.getchannel("A"))
    sh = Image.new("RGBA", frame.size, (0, 0, 0, alpha))
    shadow.paste(sh, (x + blur // 3, y + blur // 2), m)
    img.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(blur)))
    img.alpha_composite(frame, (x, y))


def badge(d, xy, text, f, color):
    d.text(xy, f"[ {text.upper()} ]", font=f, fill=color)
    return d.textlength(f"[ {text.upper()} ]  ", font=f)


def hero(shots):
    W, H = 2400, 1260
    img = Image.new("RGBA", (W, H), CANVAS + (255,))
    glow(img, (300, 0), 560, ACCENT, 40)
    glow(img, (1750, 560), 640, ACCENT, 22)
    raster(img, 30, 560)
    d = ImageDraw.Draw(img)

    tile = Image.new("RGBA", (190, 190), (0, 0, 0, 0))
    ImageDraw.Draw(tile).rounded_rectangle((0, 0, 189, 189), 46, fill=BLACK + (255,), outline=LINE + (255,), width=2)
    tile.alpha_composite(render_mark(190, 0.74, background=False))
    drop(img, tile, (140, 210), blur=24, alpha=150)
    d.text((132, 440), "Hamstr", font=font("inter_600.ttf", 168), fill=INK)
    d.text((142, 668), "Share a link. Keep the file.", font=font("inter_500.ttf", 58), fill=INK)
    sub = font("inter_400.ttf", 38)
    d.text((142, 762), "yt-dlp and FFmpeg run on the phone.", font=sub, fill=MUTED)
    d.text((142, 814), "No server, no account, no Material.", font=sub, fill=MUTED)
    bf = font("jetbrains_mono_600.ttf", 30)
    x = 142
    for label, hot in (("Android 8+", False), ("1800+ sites", True), ("GPL-3.0", False)):
        x += badge(d, (x, 930), label, bf, ACCENT if hot else GHOST)

    pw = 500
    back_l, back_r = (phone(shots / n, pw) for n in ("share.png", "player.png"))
    drop(img, back_l, (1150, 230))
    drop(img, back_r, (1850, 230))
    big = phone(shots / "stash.png", 570)
    drop(img, big, (1465, 100), blur=50, alpha=210)
    # Fade the phones out at the bottom edge into the canvas.
    fade = Image.new("RGBA", (W, 300), (0, 0, 0, 0))
    fd = ImageDraw.Draw(fade)
    for i in range(300):
        fd.line((0, i, W, i), fill=CANVAS + (int(255 * (i / 299) ** 1.8),))
    img.alpha_composite(fade, (0, H - 300))
    return img.convert("RGB")


def row(shots):
    names = [("stash.png", "Stash"), ("share.png", "Share"), ("player.png", "Play"), ("library.png", "Library"), ("settings.png", "Settings")]
    pw, gap, pad = 420, 56, 90
    W = pad * 2 + pw * len(names) + gap * (len(names) - 1)
    H = 70 + phone(shots / names[0][0], pw).height + 120
    img = Image.new("RGBA", (W, H), CANVAS + (255,))
    glow(img, (W // 2, 120), 700, ACCENT, 26)
    raster(img, 30, 380, seed=3)
    d = ImageDraw.Draw(img)
    bf = font("jetbrains_mono_600.ttf", 30)
    for i, (n, label) in enumerate(names):
        f = phone(shots / n, pw)
        x = pad + i * (pw + gap)
        drop(img, f, (x, 70), blur=34, alpha=150)
        t = f"[ {label.upper()} ]"
        d.text((x + pw / 2, 70 + f.height + 50), t, font=bf, fill=ACCENT if i == 0 else MUTED, anchor="mm")
    return img.convert("RGB")


if __name__ == "__main__":
    shots = pathlib.Path(sys.argv[1])
    OUT.mkdir(parents=True, exist_ok=True)
    hero(shots).save(OUT / "hero.png", optimize=True)
    row(shots).save(OUT / "screens.png", optimize=True)
    print("ok")
