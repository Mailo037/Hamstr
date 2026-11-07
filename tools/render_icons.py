"""Writes the app's icons (shared/.../composeResources/drawable/ic_*.xml) from Phosphor.

    python tools/render_icons.py            # write every icon in ICONS
    python tools/render_icons.py preview    # build/icons.html: each icon at 24 dp and large

Fetches @phosphor-icons/core (MIT) with npm into build/phosphor once. A new icon is a line in
ICONS: the file's name without "ic_", and the Phosphor icon with its weight ("-bold" for line
glyphs, "-fill" for objects). Icons Phosphor doesn't have are drawn by hand and listed in DRAWN,
which this script leaves alone. See DESIGN.md, Icons.
"""
import pathlib
import re
import subprocess
import sys
import tarfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
DRAWABLE = ROOT / "shared/src/commonMain/composeResources/drawable"
PHOSPHOR = ROOT / "build/phosphor"
VERSION = "2.1.1"

ICONS = {
    "add": "plus-bold", "add_queue": "list-plus-bold", "arrow_back": "arrow-left-bold",
    "auto_awesome": "sparkle-fill", "bluetooth": "bluetooth-bold", "check": "check-bold",
    "check_circle": "check-circle-fill", "chevron_right": "caret-right-bold", "close": "x-bold",
    "cloud_download": "cloud-arrow-down-fill", "code": "code-bold", "computer": "laptop-fill",
    "content_paste": "clipboard-text-fill", "delete": "trash-fill", "description": "file-text-fill",
    "devices": "devices-fill", "download": "download-simple-bold", "drag_handle": "equals-bold",
    "edit": "pencil-simple-fill", "equalizer": "waveform-bold", "error": "warning-circle-fill",
    "expand_more": "caret-down-bold", "favorite": "heart-fill", "favorite_border": "heart-bold",
    "folder_open": "folder-open-fill", "fullscreen": "corners-out-bold", "fullscreen_exit": "corners-in-bold",
    "grid_view": "squares-four-fill", "help": "question-fill", "info": "info-fill", "link": "link-bold",
    "lyrics": "text-align-left-bold", "more_vert": "dots-three-vertical-bold", "movie": "film-strip-fill",
    "music_note": "music-note-fill", "open_in_new": "arrow-square-out-bold", "palette": "palette-fill",
    "pause": "pause-fill", "person": "user-fill", "pie_chart": "chart-pie-fill", "play_arrow": "play-fill",
    "play_next": "queue-bold", "playlist_add": "music-notes-plus-bold", "queue_music": "playlist-bold",
    "refresh": "arrow-clockwise-bold", "repeat": "repeat-bold", "repeat_one": "repeat-once-bold",
    "restore": "clock-counter-clockwise-bold", "search": "magnifying-glass-bold", "select_all": "checks-bold",
    "send": "paper-plane-tilt-fill", "settings": "gear-six-fill", "share": "share-network-fill",
    "shuffle": "shuffle-bold", "skip_next": "skip-forward-fill", "skip_previous": "skip-back-fill",
    "cast": "screencast-bold", "tv": "television-simple-fill", "speaker": "speaker-hifi-fill", "headphones": "headphones-fill",
    "smartphone": "device-mobile-fill", "sort": "funnel-simple-bold", "timer": "timer-fill",
    "upload": "upload-simple-bold", "visibility_off": "eye-slash-fill", "wifi": "wifi-high-bold",
}
# Drawn by hand on a 24 grid, strokes 2–2.25 with round caps and joins.
DRAWN = {"replay_10", "forward_10"}


def assets():
    if not PHOSPHOR.exists():
        PHOSPHOR.mkdir(parents=True)
        subprocess.run(["npm", "pack", f"@phosphor-icons/core@{VERSION}"], cwd=PHOSPHOR, check=True, shell=sys.platform == "win32")
        with tarfile.open(PHOSPHOR / f"phosphor-icons-core-{VERSION}.tgz") as t:
            t.extractall(PHOSPHOR, filter="data")
    return PHOSPHOR / "package/assets"


def vector(name):
    svg = (assets() / name.rsplit("-", 1)[1] / f"{name}.svg").read_text(encoding="utf-8")
    body = re.sub(r"^<svg[^>]*>|</svg>$", "", svg.strip())
    paths = re.findall(r'<path d="([^"]*)"/>', body)
    if re.sub(r'<path d="[^"]*"/>', "", body).strip():
        sys.exit(f"{name} has more than paths; draw it by hand")
    p = "\n".join(f'    <path android:fillColor="#FFFFFFFF" android:pathData="{d}" />' for d in paths)
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Phosphor "{name}" (MIT) -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="256" android:viewportHeight="256">
{p}
</vector>
'''


def svg(xml, size):
    """An Android vector as inline SVG, for the preview."""
    vw = re.search(r'viewportWidth="([\d.]+)"', xml).group(1)
    out = []
    for m in re.finditer(r"<path ([^>]*)/>", xml):
        a = dict(re.findall(r'android:(\w+)="([^"]*)"', m.group(1)))
        if "strokeColor" in a:
            out.append(f'<path d="{a["pathData"]}" fill="none" stroke="currentColor" stroke-width="{a["strokeWidth"]}"'
                       f' stroke-linecap="{a.get("strokeLineCap", "butt")}" stroke-linejoin="{a.get("strokeLineJoin", "miter")}"/>')
        else:
            out.append(f'<path d="{a["pathData"]}" fill="currentColor"/>')
    return f'<svg viewBox="0 0 {vw} {vw}" width="{size}" height="{size}">{"".join(out)}</svg>'


def preview():
    cells = []
    for f in sorted(DRAWABLE.glob("ic_*.xml")):
        x = f.read_text(encoding="utf-8")
        cells.append(f'<div class=c><div class=r>{svg(x, 24)}{svg(x, 64)}</div><small>{f.stem[3:]}</small></div>')
    out = ROOT / "build/icons.html"
    out.write_text(f'''<!doctype html><meta charset=utf-8><style>
body{{background:#141414;color:#eee;font:12px system-ui;margin:16px}}
.g{{display:grid;grid-template-columns:repeat(auto-fill,150px);gap:12px}}
.c{{background:#1e1e1e;border-radius:16px;padding:12px;text-align:center}}
.r{{display:flex;align-items:center;justify-content:center;gap:14px;margin-bottom:8px}}
.r svg:last-child{{color:#ff9a3c}} small{{color:#8a8a8a}}</style>
<div class=g>{"".join(cells)}</div>''', encoding="utf-8")
    print(out)


if __name__ == "__main__":
    if sys.argv[1:] == ["preview"]:
        preview()
        sys.exit()
    for icon, name in ICONS.items():
        (DRAWABLE / f"ic_{icon}.xml").write_text(vector(name), encoding="utf-8", newline="\n")
    missing = {f.stem[3:] for f in DRAWABLE.glob("ic_*.xml")} - ICONS.keys() - DRAWN
    if missing:
        print("not from here, check by hand:", ", ".join(sorted(missing)))
    print("ok")
