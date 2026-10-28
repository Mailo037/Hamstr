"""Takes the product screenshots on a device or emulator through the debug build's demo
mode, then renders the README and release pictures from them.

    python tools/demo_screens.py [--serial emulator-5554] [--seed default] [--out build/screens]

Needs a debug build installed (./gradlew installDebug) and adb on the PATH. The demo
replaces the list until `adb shell am start -n app.hamstr/.demo.DemoActivity --es seed off`.
"""
import argparse
import pathlib
import re
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent
PKG = "app.hamstr"


class Device:
    def __init__(self, serial):
        self.base = ["adb"] + (["-s", serial] if serial else [])

    def sh(self, *args):
        return subprocess.run(self.base + ["shell", *args], capture_output=True, text=True).stdout

    def nodes(self):
        self.sh("uiautomator", "dump", "/sdcard/hamstr-ui.xml")
        xml = subprocess.run(self.base + ["exec-out", "cat", "/sdcard/hamstr-ui.xml"], capture_output=True).stdout.decode("utf8", "replace")
        for n in re.findall(r"<node [^>]*>", xml):
            text = re.search(r' text="([^"]*)"', n).group(1)
            desc = re.search(r'content-desc="([^"]*)"', n).group(1)
            x0, y0, x1, y1 = map(int, re.findall(r"\d+", re.search(r'bounds="([^"]*)"', n).group(1)))
            yield text, desc, ((x0 + x1) // 2, (y0 + y1) // 2)

    def find(self, label, timeout=30):
        end = time.time() + timeout
        while time.time() < end:
            for text, desc, center in self.nodes():
                if label in (text, desc):
                    return center
            time.sleep(1)
        sys.exit(f"'{label}' did not show up")

    def present(self, label):
        return next((center for text, desc, center in self.nodes() if label in (text, desc)), None)

    def tap(self, label, long=False):
        x, y = self.find(label)
        if long:
            self.sh("input", "swipe", str(x), str(y), str(x), str(y), "800")
        else:
            self.sh("input", "tap", str(x), str(y))

    def shot(self, path, settle=1.5):
        time.sleep(settle)
        path.write_bytes(subprocess.run(self.base + ["exec-out", "screencap", "-p"], capture_output=True).stdout)
        print("  ", path.name)

    def status_bar(self, on):
        # SystemUI's demo mode: a fixed clock, full battery and signal, no notification icons.
        if not on:
            self.sh("am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", "exit")
            return
        self.sh("settings", "put", "global", "sysui_demo_allowed", "1")
        for command, extras in (("enter", {}), ("clock", {"hhmm": "0941"}), ("battery", {"level": "100", "plugged": "false"}),
                                ("network", {"wifi": "show", "level": "4", "mobile": "show"}), ("notifications", {"visible": "false"})):
            args = ["am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", command]
            for key, value in extras.items():
                args += ["-e", key, value]
            self.sh(*args)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial")
    ap.add_argument("--seed", default="default")
    ap.add_argument("--keep", action="store_true", help="reuse the demo that is already seeded")
    ap.add_argument("--out", default=str(ROOT / "build/screens"))
    ap.add_argument("--share", default="https://www.youtube.com/watch?v=hmstrDemo08", help="link the seed resolves for the share sheet")
    ap.add_argument("--play", default="Night Train Through the Alps", help="stashed title to open in the player and file menu")
    ap.add_argument("--playlist", default="Slow evenings", help="playlist from the seed to open")
    ap.add_argument("--song", default="Lo-fi Beats for Rainy Evenings", help="song from that playlist to show in the music player")
    a = ap.parse_args()
    out = pathlib.Path(a.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    d = Device(a.serial)

    d.sh("settings", "put", "system", "accelerometer_rotation", "0")
    d.sh("settings", "put", "system", "user_rotation", "0")
    if a.keep:
        d.sh("am", "start", "-n", f"{PKG}/.MainActivity")
    else:
        print("seeding the demo (FFmpeg makes the media, this takes a few minutes)")
        d.sh("am", "force-stop", PKG)
        ready = f"/sdcard/Android/data/{PKG}/files/demo/.ready"
        d.sh("rm", "-f", ready)
        d.sh("am", "start", "-n", f"{PKG}/.demo.DemoActivity", "--es", "seed", a.seed)
        end = time.time() + 600
        while "No such file" in d.sh("ls", ready) or not d.sh("ls", ready).strip():
            if time.time() > end:
                sys.exit("the demo did not finish seeding")
            time.sleep(3)
    d.find(a.play)
    d.status_bar(True)
    try:
        if close := d.present("Close player"):
            d.sh("input", "tap", *map(str, close))
        d.tap("Stash")
        d.find("Stashed")
        d.shot(out / "stash.png", settle=3)

        d.sh("am", "start", "-a", "android.intent.action.SEND", "-t", "text/plain",
             "--es", "android.intent.extra.TEXT", a.share, "-n", f"{PKG}/.ShareActivity")
        d.shot(out / "share.png", settle=5)
        d.sh("input", "keyevent", "BACK")
        time.sleep(1)

        d.tap(a.play)
        d.find("Minimize")
        d.shot(out / "player.png", settle=2.5)
        d.tap("Queue")
        d.tap("Close player")  # the mini player stays out of the other pictures

        d.tap(a.play, long=True)
        d.find("File info")
        d.shot(out / "menu.png")
        d.tap("File info")
        d.shot(out / "info.png")
        d.sh("input", "keyevent", "BACK")
        time.sleep(1)

        d.tap("Library")
        d.shot(out / "library.png", settle=4)
        d.tap(a.playlist)
        d.find("Shuffle")
        d.shot(out / "playlist.png")
        d.tap(a.song)
        d.find("Minimize")
        d.shot(out / "music.png", settle=3)
        d.tap("Queue")
        d.shot(out / "queue.png")
        d.tap("Close player")
        d.tap("Settings")
        d.shot(out / "settings.png", settle=2.5)
        d.tap("Stash")
    finally:
        d.status_bar(False)

    tools = ROOT / "tools"
    for script in ("render_product.py", "render_release.py"):
        subprocess.run([sys.executable, str(tools / script), str(out)], check=True, cwd=tools)


if __name__ == "__main__":
    main()
