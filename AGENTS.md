# Working on Hamstr

Notes for anyone (human or agent) changing the code. The README is for people who use the app.

## Layout

- `app/src/main/java/app/hamstr/engine/`: the Cheek bridge, yt-dlp and FFmpeg through [youtubedl-android](https://github.com/JunkFood02/youtubedl-android), the warm yt-dlp process, the download queue and its notifications.
- `player/`: a Media3 ExoPlayer behind a `MediaSession`, plus the mini player, picture-in-picture and fullscreen.
- `update/`: the self-updater, built on GitHub Releases and `PackageInstaller` sessions.
- `ui/`: Compose Foundation only, no Material components. The tokens live in `ui/theme`, the motion helpers in `ui/components`. User-facing text goes into `res/values/strings.xml`. The design rules are in [DESIGN.md](DESIGN.md).
- `tools/`: scripts that render the icon, product images and shortcuts.

## Build

You need JDK 21 and the Android SDK with platform 37.

```bash
./gradlew assembleDebug
```

The APKs end up in `app/build/outputs/apk/debug/`, one per ABI plus a universal one.

Cheek goes into the APK as `libcheek.so` per ABI. Builds take the release pinned in `app/build.gradle.kts` (`cheekVersion`); `cheek.dir` in `local.properties` points at a local Cheek checkout built with cargo-ndk instead. To use the pinned release even when `cheek.dir` is set, pass `-Pcheek.useRelease=true`. Downloaded Cheek binaries are checked against the release's `SHA256SUMS.txt` before packaging.

## Tests

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
```

The second needs a device or emulator; it covers the engine, Cheek's release binaries and file handling.

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

1. Set the default version in `app/build.gradle.kts` (`hamstr.version`) and, if a new Cheek is out, `cheekVersion`.
2. Write `.github/release-notes/v<version>.md`. It starts with the what's-new picture, linked by the tag: `![What's new in Hamstr <version>](https://raw.githubusercontent.com/Mailo037/Hamstr/v<version>/docs/images/releases/v<version>.png)`.
3. Make that picture. Change `VERSION`, `FEATURES` and `SHOTS` in [`tools/render_release.py`](tools/render_release.py) to what is new, add any screen it needs to `tools/demo_screens.py` (and to the demo seed), install a debug build and run `python tools/demo_screens.py`. That also renders the README pictures again from the current UI. Look at `docs/images/releases/v<version>.png` before committing; older pictures stay. Every release gets one, a bugfix too.
4. Commit everything, including the picture, before the tag: the notes load it from the tagged commit.

Cheek has the same rule for its own releases (see its AGENTS.md). Ship a Cheek release first and wait until its binaries are published, because the Hamstr build downloads them by `cheekVersion`.

[`release.yml`](.github/workflows/release.yml) builds the APKs, signs them with the release key and publishes the release with checksums. The version comes from the tag. If `.github/release-notes/<tag>.md` exists, it becomes the release text; otherwise GitHub writes notes from the commits. Every installed app finds the release on its next start.

The key comes from four repository secrets: `HAMSTR_KEYSTORE_B64`, `HAMSTR_KEYSTORE_PASSWORD`, `HAMSTR_KEY_ALIAS` and `HAMSTR_KEY_PASSWORD`. Local release builds read the same values from `signing/keystore.properties`, which git ignores. Keep that key safe: an update only installs over an app signed with the same key.
