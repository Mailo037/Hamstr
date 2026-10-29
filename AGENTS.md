# Working on Hamstr

Notes for anyone (human or agent) changing the code. The README is for people who use the app.

## Layout

Hamstr is one app on two kinds of device: the phone app (`app/`) and the desktop app for Windows, macOS and Linux (`desktop/`). Both are built from the shared module (`shared/`) with [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/), so a screen, a component or a rule written there shows up in both.

- `shared/`: everything both apps use. The Kotlin goes into `src/jvmCommonMain` (both targets are JVMs, so `java.io`, processes and `org.json` work there); `src/androidMain` and `src/desktopMain` hold the few `actual`s that differ (logging, settings storage, drawing ASCII glyphs, loading thumbnails, the reduce-motion switch).
  - `ui/`: Compose Foundation only, no Material components. The tokens live in `ui/theme`, the motion helpers and components in `ui/components`. The shared screens and sheets so far: the stash (`StashScreen`), the add sheet, `Overlay`, and the settings both apps show (`SettingsParts.kt`). The design rules are in [DESIGN.md](DESIGN.md).
  - `data/`: the download list (`DownloadStore`), settings (`Prefs`), resume positions, file naming, and `DownloadControl`, through which screens pause, resume and cancel.
  - `nearby/`: sending links to other Hamstr apps, like AirDrop (`Nearby`). Each app has a P-256 key; its id is the key's hash and every message is signed. `LanTransport` finds devices on the same network (multicast and broadcast beacons on UDP 47613, links over TCP); the first link from a device asks, "Always allow" trusts it, and links for a device that is away wait in the outbox in `nearby.json`. The sheets and the Devices settings are in `ui/NearbySheets.kt`.
  - `engine/`: what both engines decide the same way: formats and file names (`Formats`), reading yt-dlp's JSON (`MediaJson`), links (`MediaLinks`) and errors in plain words (`Failures`).
  - `src/commonMain/composeResources/`: all strings (`values/strings.xml`), fonts and icons. The phone app reads the same folder as its Android resources, so `R.string.x` and `Res.string.x` are the same text.
- `app/src/main/java/app/hamstr/`: what only the phone has.
  - `engine/`: the Cheek bridge, yt-dlp and FFmpeg through [youtubedl-android](https://github.com/JunkFood02/youtubedl-android), the warm yt-dlp process, the download queue (`DownloadService`) and its notifications.
  - `player/`: a Media3 ExoPlayer behind a `MediaSession`, plus the mini player, picture-in-picture and fullscreen.
  - `nearby/`: Bluetooth LE for Nearby (`BleTransport`: advertise, scan, a GATT server to read who a phone is and write links into) and the phone's notifications for received links (`PhoneNearby`). The desktop has Wi-Fi only so far; the JVM has no Bluetooth API.
  - `update/`: the self-updater, built on GitHub Releases and `PackageInstaller` sessions.
  - `ui/`: the screens that are still Android-only (library, player sheets, share sheet, most of settings).
- `desktop/`: the desktop window (`DesktopApp`), its download queue (`Queue`, the desktop's `DownloadService`), and how it finds yt-dlp, Cheek and FFmpeg (`Tools`).
- `tools/`: scripts that render the icon (also the desktop icons, `python tools/render_icon.py desktop`), product images and shortcuts.

### Adding something to both apps

- A new screen, sheet or component goes into `shared/src/jvmCommonMain` and uses `Res.string`, `Res.drawable` and `stringResource` from `org.jetbrains.compose.resources`. Both apps then have it; each only has to show it somewhere.
- Text goes into the shared `strings.xml`. Where the phone and the desktop need different words ("from your phone" / "from this computer"), add a `_desktop` string and pick it with `Platform.desktop`.
- Something only the system can do (open a file, share, notify) stays out of the shared code: pass it in as a callback (`onPlay`, `onFiles`) or add it to an interface like `DownloadControl` and implement it on both sides.
- A new setting goes into `Settings` and `Prefs` once; both apps save it. Show it in `SettingsParts.kt` when it means the same on both.
- Moving an Android screen into `shared`: move the file, switch `R.string` to `Res.string` and `androidx.compose.ui.res` to `org.jetbrains.compose.resources`, and replace what it needs from Android (a `Context`, `DownloadService`) with a callback or a shared interface.

## Build

You need JDK 21 and the Android SDK with platform 37 (the desktop build configures the Android modules too). The Windows setup also needs [Inno Setup 6](https://jrsoftware.org/isinfo.php).

```bash
./gradlew assembleDebug
```

The APKs end up in `app/build/outputs/apk/debug/`, one per ABI plus a universal one.

```bash
./gradlew :desktop:run
./gradlew :desktop:packageDistributionForCurrentOS
```

The first runs the desktop app (`HAMSTR_OPEN=Engine`, or any other settings page, starts it on that page for screenshots); the second builds its installer for the system you are on (a setup `.exe` on Windows, made by Inno Setup from `desktop/windows/hamstr.iss`; `.dmg` on macOS, `.deb` on Linux) into `desktop/build/compose/binaries/`. An installer can only be built on the system it is for. The Windows setup installs for the signed-in user into `%LOCALAPPDATA%\Programs\Hamstr`, without admin rights or Windows Installer (jpackage's MSI needed both, was slow and asked for restarts); it closes a running Hamstr itself and removes an MSI-installed 0.7.x once.

The desktop app installs its own updates (`desktop/.../Updater.kt`): a helper started outside Hamstr waits for it to quit, runs the setup silently (or swaps a portable copy's files, or the app from the `.dmg`; Linux installs the `.deb` through `pkexec`) and starts Hamstr again with `--updated=<version>`. To try that end to end, serve a release JSON like GitHub's and start an installed Hamstr with `HAMSTR_RELEASES=<its URL>`, `HAMSTR_UPDATE=now` (installs what it offers, unasked) and `HAMSTR_DATA=<a test folder>`. Both download yt-dlp, Cheek, FFmpeg and ffprobe for this system first (`fetchTools`, into `desktop/build/tools`), checked against the checksums each release publishes; the app copies them into its own folder on first start, where yt-dlp can update itself.

The app's version and the Cheek release both apps ship are in `gradle.properties` (`hamstr.version`, `hamstr.cheekVersion`). Cheek goes into the APK as `libcheek.so` per ABI; `cheek.dir` in `local.properties` points at a local Cheek checkout built with cargo-ndk instead. To use the pinned release even when `cheek.dir` is set, pass `-Pcheek.useRelease=true`. Downloaded Cheek binaries are checked against the release's `SHA256SUMS.txt` before packaging.

## Tests

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
```

The second needs a device or emulator; it covers the engine, Cheek's release binaries and file handling. Shared code is tested through the app's unit tests for now.

## Demo mode and screenshots

Debug builds carry a demo mode (`app/src/debug`) that swaps the download list for demo entries with drawn posters and media made by the bundled FFmpeg. Your own list comes back when you leave it.

```bash
adb shell am start -n app.hamstr/.demo.DemoActivity --es seed default
adb shell am start -n app.hamstr/.demo.DemoActivity --es seed off
```

`--es seed /sdcard/Download/my.json` loads your own seed in the format of `app/src/debug/assets/demo/default.json`; entries with the same `"playlist"` name become one of your playlists. `python tools/demo_screens.py` seeds the demo on a device, takes every screenshot and renders `docs/images` plus the release picture (`tools/render_release.py`).

## Releasing

Push a tag and GitHub Actions does the rest:

```bash
git tag v0.5.0
git push origin v0.5.0
```

Before tagging:

1. Set the default version in `gradle.properties` (`hamstr.version`) and, if a new Cheek is out, `hamstr.cheekVersion`.
2. Write `.github/release-notes/v<version>.md`. It starts with the what's-new picture, linked by the tag: `![What's new in Hamstr <version>](https://raw.githubusercontent.com/Mailo037/Hamstr/v<version>/docs/images/releases/v<version>.png)`.
3. Make that picture. Change `VERSION`, `FEATURES` and `SHOTS` in [`tools/render_release.py`](tools/render_release.py) to what is new, add any screen it needs to `tools/demo_screens.py` (and to the demo seed), install a debug build and run `python tools/demo_screens.py`. That also renders the README pictures again from the current UI. Look at `docs/images/releases/v<version>.png` before committing; older pictures stay. Every release gets one, a bugfix too.
4. Commit everything, including the picture, before the tag: the notes load it from the tagged commit.

Cheek has the same rule for its own releases (see its AGENTS.md). Ship a Cheek release first and wait until its binaries are published, because both Hamstr builds download them by `hamstr.cheekVersion`.

[`release.yml`](.github/workflows/release.yml) builds the APKs, signs them with the release key and publishes the release with checksums; then it builds the desktop app on Windows (setup `.exe` and a portable `.zip`), macOS and Linux, adds it, and writes one `SHA256SUMS.txt` over every file. The version comes from the tag. If `.github/release-notes/<tag>.md` exists, it becomes the release text; otherwise GitHub writes notes from the commits. Every installed phone app finds the release on its next start (it only looks at the `.apk` files). The desktop installers are not signed yet, so Windows SmartScreen and macOS Gatekeeper warn before the first start.

The key comes from four repository secrets: `HAMSTR_KEYSTORE_B64`, `HAMSTR_KEYSTORE_PASSWORD`, `HAMSTR_KEY_ALIAS` and `HAMSTR_KEY_PASSWORD`. Local release builds read the same values from `signing/keystore.properties`, which git ignores. Keep that key safe: an update only installs over an app signed with the same key.
