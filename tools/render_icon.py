"""Renders the Hamstr ASCII mark (same grid as ui/components/HamstrMark.kt) to PNGs."""
import sys, pathlib
from PIL import Image, ImageDraw, ImageFont

GRID = [
    "...###....###...",
    "..#####..#####..",
    "...##########...",
    "..############..",
    ".##############.",
    ".##############.",
    "####0######0####",
    "################",
    "#***########***#",
    ".***########***.",
    "..############..",
    "....########....",
]
INK, ACCENT, BG = (238, 238, 238), (255, 154, 60), (10, 10, 9)
ROOT = pathlib.Path(__file__).resolve().parent.parent
FONT = ROOT / "app/src/main/res/font/jetbrains_mono_600.ttf"


def render(size, content_frac, background=True):
    img = Image.new("RGBA", (size, size), BG + (255,) if background else (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    cols, rows = len(GRID[0]), len(GRID)
    cell = size * content_frac / cols
    font = ImageFont.truetype(str(FONT), int(cell * 1.15))
    ox, oy = (size - cell * cols) / 2, (size - cell * rows * 1.1) / 2
    for r, line in enumerate(GRID):
        for c, ch in enumerate(line):
            if ch == ".":
                continue
            color = ACCENT if ch == "*" else INK
            d.text((ox + c * cell + cell / 2, oy + r * cell * 1.1 + cell * 0.55), ch, font=font, fill=color, anchor="mm")
    return img


if __name__ == "__main__":
    res = ROOT / "app/src/main/res"
    dens = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
    for name, k in dens.items():
        out = res / f"mipmap-{name}"
        out.mkdir(parents=True, exist_ok=True)
        # Adaptive foreground: 108dp canvas, mark inside the 66dp safe zone.
        render(int(108 * k), 0.52, background=False).save(out / "ic_launcher_foreground.png")
        # Legacy square icon for API 26 launchers that ignore adaptive icons.
        render(int(48 * k), 0.78).save(out / "ic_launcher.png")
    render(1024, 0.7).save(ROOT / "design/assets/icon_mark.png")
    print("ok")
