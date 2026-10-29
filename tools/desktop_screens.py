"""Takes the desktop app's screenshots on Windows, with the phone demo's downloads.

    python tools/desktop_screens.py [--serial emulator-5554] [--out build/screens]

Run tools/demo_screens.py first: this copies the seeded demo (its list, posters and media)
from the device into a separate data folder, starts the built app on it
(./gradlew :desktop:createDistributable) once per page and captures the window. Your own
desktop data stays untouched. Writes desktop_<page>.png next to the phone screenshots.
"""
import argparse
import json
import os
import pathlib
import subprocess
import tempfile
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent
APP = ROOT / "desktop/build/compose/binaries/main/app/Hamstr/Hamstr.exe"
PHONE_DEMO = "/storage/emulated/0/Android/data/app.hamstr/files/demo"
PAGES = [("stash", ""), ("devices", "Devices"), ("appearance", "Appearance"), ("about", "About")]

# PrintWindow with PW_RENDERFULLCONTENT also catches what Skia draws through Direct3D.
CAPTURE = r"""
param([int]$ProcId, [string]$Out)
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System; using System.Runtime.InteropServices;
public class W {
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr dc, uint f);
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  public struct RECT { public int L, T, R, B; }
}
"@
[W]::SetProcessDPIAware() | Out-Null
$h = (Get-Process -Id $ProcId).MainWindowHandle
$r = New-Object W+RECT; [W]::GetWindowRect($h, [ref]$r) | Out-Null
$bmp = New-Object System.Drawing.Bitmap ($r.R - $r.L), ($r.B - $r.T)
$g = [System.Drawing.Graphics]::FromImage($bmp); $dc = $g.GetHdc()
[W]::PrintWindow($h, $dc, 2) | Out-Null
$g.ReleaseHdc($dc); $bmp.Save($Out)
"""


def adb(serial, *args, binary=False):
    cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
    out = subprocess.run(cmd, capture_output=True, env={**os.environ, "MSYS_NO_PATHCONV": "1"}).stdout
    return out if binary else out.decode("utf8", "replace")


def demo_data(serial, work):
    """The phone demo's list with its paths pointing at a local copy of the media."""
    media = work / "media"
    adb(serial, "pull", PHONE_DEMO + "/.", str(media))
    items = json.loads(adb(serial, "exec-out", "run-as", "app.hamstr", "cat", "files/downloads.json"))

    def local(v):
        if isinstance(v, str) and PHONE_DEMO in v:
            return v.replace("file://" + PHONE_DEMO, media.as_uri()).replace(PHONE_DEMO, str(media))
        return v

    data = work / "data"
    data.mkdir(parents=True, exist_ok=True)
    (data / "downloads.json").write_text(json.dumps([{k: local(v) for k, v in it.items()} for it in items]), encoding="utf8")
    (data / "settings.properties").write_text("fx.intro=false\nappUpdates=false\nnearby=false\ndeviceName=Studio PC\n", encoding="utf8")
    return data


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial")
    ap.add_argument("--out", default=str(ROOT / "build/screens"))
    a = ap.parse_args()
    out = pathlib.Path(a.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    if not APP.exists():
        raise SystemExit("build the app first: ./gradlew :desktop:createDistributable")
    work = pathlib.Path(tempfile.mkdtemp(prefix="hamstr-desktop-"))
    data = demo_data(a.serial, work)
    script = work / "capture.ps1"
    script.write_text(CAPTURE, encoding="utf8")
    for name, page in PAGES:
        proc = subprocess.Popen([str(APP)], env={**os.environ, "HAMSTR_DATA": str(data), "HAMSTR_OPEN": page})
        try:
            time.sleep(20)  # until yt-dlp is ready and the stash says nothing about the engine
            # The launcher starts the app as its child; that one owns the window.
            pid = subprocess.run(
                ["powershell", "-NoProfile", "-Command", f"(Get-CimInstance Win32_Process -Filter 'ParentProcessId={proc.pid}' | Select-Object -First 1).ProcessId"],
                capture_output=True, text=True).stdout.strip() or str(proc.pid)
            path = out / f"desktop_{name}.png"
            subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(script), "-ProcId", pid, "-Out", str(path)], check=True)
            print("  ", path.name)
        finally:
            subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"], capture_output=True)
            proc.wait()


if __name__ == "__main__":
    main()
