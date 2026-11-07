# Working on Hamstr

Notes for anyone (human or agent) changing the code. The README is for people who use the app.

## Layout

Hamstr is one app on two kinds of device: the phone app (`app/`) and the desktop app for Windows, macOS and Linux (`desktop/`). Both are built from the shared module (`shared/`) with [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/), so a screen, a component or a rule written there shows up in both.

- `shared/`: everything both apps use. The Kotlin goes into `src/jvmCommonMain` (both targets are JVMs, so `java.io`, processes and `org.json` work there); `src/androidMain` and `src/desktopMain` hold the few `actual`s that differ (logging, settings storage, drawing ASCII glyphs, loading thumbnails, the reduce-motion switch).
  - `ui/`: Compose Foundation only, no Material components. The tokens live in `ui/theme`, the motion helpers and components in `ui/components`. On the desktop `Overlay` and `Sheet` turn into centred modals by themselves, and the pointer gets its own pieces: right-click and button menus (`Menu.kt`) and tooltips (`Tooltip.kt`, built into `IconButton`). The shared screens and sheets so far: the stash (`StashScreen`), the add sheet, `Overlay`, the settings both apps show (`SettingsParts.kt`, the equalizer in `EqualizerPanel.kt`, Data & backup with the storage ring in `DataSettings.kt`) and the first-start tour (`Tour.kt`, its pictures in `TourScenes.kt`). `SettingsPanelsTest` renders the equalizer and Data & backup into `shared/build/settings`. The design rules are in [DESIGN.md](DESIGN.md).
  - `data/`: the download list (`DownloadStore`), settings (`Prefs`), resume positions, file naming, `DownloadControl`, through which screens pause, resume, cancel, retry (after updating yt-dlp) and start held downloads, and `Backup` (everything but the files and Nearby's key in one JSON file; each platform adds its own extras and picks the file). `Schedule` decides whether a download waits for the night or the charger (the stages `WaitingForNight` and `WaitingForCharger`; `anytime` is "Start now"), `Subscriptions` keeps the followed channels and playlists (`subscriptions.json`): each platform's queue reads them through `DownloadControl.feed` every four hours and queues what is new, at most ten per look.
  - `audio/`: the equalizer (`Equalizer`, its presets, and `EqFilter`, which both players run their decoded sound through: ExoPlayer as an audio processor, the desktop on FFmpeg's PCM).
  - `nearby/`: sending links and files to other Hamstr apps, like AirDrop (`Nearby`). Each app has a P-256 key; its id is the key's hash. Beacons and messages are signed, a message names its receiver and expires after ten minutes, and it is sealed to the receiver's key (`Seal`: ECDH, HKDF, AES-GCM), so nobody else on the network can read it; it only counts as delivered when the receiver signs a receipt for it. Files follow an offer on the same connection as numbered AES-GCM frames. `LanTransport` finds devices on the same network (multicast and broadcast beacons on UDP 47613, messages over TCP); `Ble` is Hamstr's Bluetooth LE service, the same on both apps (what its GATT characteristics carry, `BleServer` answering them, `GattLink` for the side that connects), with files over a stream characteristic in smaller frames when there is no shared network; the first link or file from a device asks, "Always allow" trusts it, and links for a device that is away wait in the outbox in `nearby.json`. The protocol is version 2; apps from before 0.9.3 don't see the newer ones. The sheets and the Devices settings are in `ui/NearbySheets.kt`.
  - `cast/`: "Play on", playing on a TV or speaker on the same network, without Google's SDK (`Cast` finds devices and holds the `CastSession`). `Dlna` (SSDP and SOAP over `LanHttp`, so the phone's cleartext rule stays strict; Sonos too), `Chromecast` (the Cast protocol over TLS, the Default Media Receiver), `AirPlay` (the video protocol, no pairing yet) and `Mpd`; `Mdns` finds the last three. `MediaServer` serves the file under a random path with byte ranges; `Convert` makes a copy the device plays when it doesn't play the original (FFmpeg: sound only, remux into MP4, or H.264/AAC), cached in `Cast.cache`. A session sends commands on their own queue, apart from the status poll, and of several of a kind only the newest (`CastSession`). The phone's session holds a `CastPlayer` (app/player) instead of ExoPlayer while it casts, so the notification and the volume keys steer the device; the desktop's player turns into the remote. Other Hamstr apps on the network are devices too (`HamstrCast`): the sender's `Link` sends Nearby's sealed, signed `play` commands (`Nearby.command`, answered by a receipt that carries the reply as `x_…` fields), the file goes from `MediaServer` as for a TV, and the receiver streams it into its own player through a `HamstrCast.Stage` (`DesktopStage` into an `FfPlayer` on the link, `PhoneStage` into ExoPlayer through `LanDataSource`, since the phone refuses plain http), so both sides steer it; a device that isn't trusted asks first, and the receiver closing it or playing something else ends the sender's session (`CastLink.Released`). Beacons say `"play": 1` for apps that take it. `CastSheet.kt` is the sheet; `CastTest` runs the protocols against fake devices, `HamstrCastTest` the Hamstr one, and `CastSheetTest` renders the sheet into `shared/build/cast`.
  - `engine/`: what both engines decide the same way: formats and file names (`Formats`), reading yt-dlp's JSON (`MediaJson`), links (`MediaLinks`), errors in plain words (`Failures`), and whose updates Hamstr runs (`ReleaseKeys`: the public keys that sign Hamstr's and Cheek's checksums, and `Versions`).
  - `src/commonMain/composeResources/`: all strings (`values/strings.xml`), fonts and icons. The phone app reads the same folder as its Android resources, so `R.string.x` and `Res.string.x` are the same text.
- `app/src/main/java/app/hamstr/`: what only the phone has.
  - `engine/`: `Wakeups`, one exact alarm that brings the stopped queue back when the night begins or a followed channel is due (an exact alarm may start a foreground service from the background; the service doesn't sit there for hours). The Cheek bridge, yt-dlp and FFmpeg through [youtubedl-android](https://github.com/JunkFood02/youtubedl-android), the warm yt-dlp process, the download queue (`DownloadService`) and its notifications. On Android 14+ a user-initiated transfer job (`DownloadJob`) holds the queue when the downloads were started in Hamstr, so Android 15's six hours a day for data-sync services don't apply; otherwise the service runs in the foreground as before.
  - `player/`: a Media3 ExoPlayer behind a `MediaSession`, plus the mini player, picture-in-picture and fullscreen. The equalizer sits in its audio sink (`EqAudio.kt`); for a crossfade a second player starts the next song and the session moves over to it (`Crossfade`).
  - `nearby/`: Bluetooth LE for Nearby (`BleTransport`: advertising, scanning and the GATT server on Android's APIs, the protocol from `shared/…/nearby/Ble.kt`) and the phone's notifications for received links (`PhoneNearby`).
  - `update/`: the self-updater, built on GitHub Releases and `PackageInstaller` sessions. `UpdateDownloadService` downloads with a foreground notification; the verified APK stays in private files until the user chooses to install it.
  - `ui/`: the screens that are still Android-only (library, player sheets, share sheet, most of settings).
- `desktop/`: the desktop window (`DesktopApp`), its download queue (`Queue`, the desktop's `DownloadService`), how it finds yt-dlp, Cheek and FFmpeg (`Tools`) and keeps them fresh (`ToolUpdates`: yt-dlp and signed Cheek releases once a day), the library (`DesktopLibrary`), the player (`FfPlayer` decodes through the bundled FFmpeg, `DesktopPlayer` shows it) and Bluetooth (`DesktopBluetooth`). The JVM has no Bluetooth, so `desktop/ble` is a small Rust helper (btleplug to scan and connect, ble-peripheral-rust to advertise and serve) that the app runs next to it and speaks JSON lines with; it only moves bytes, and what they mean stays in `Ble.kt`.
- `tools/`: scripts that render the icon (also the desktop icons, `python tools/render_icon.py desktop`), the app's icons (`render_icons.py`, see Icons in DESIGN.md), product images and shortcuts.

### Adding something to both apps

- A new screen, sheet or component goes into `shared/src/jvmCommonMain` and uses `Res.string`, `Res.drawable` and `stringResource` from `org.jetbrains.compose.resources`. Both apps then have it; each only has to show it somewhere.
- Text goes into the shared `strings.xml`. Where the phone and the desktop need different words ("from your phone" / "from this computer"), add a `_desktop` string and pick it with `Platform.desktop`.
- Hamstr speaks English, German, Spanish, French, Italian, Portuguese (Brazil), Dutch, Polish, Turkish, Russian, Japanese, Korean and Simplified Chinese: one `values-xx/strings.xml` per language next to `values/`, which both apps pick by the system language, unless one is picked in Settings › Appearance (`LanguageBlock`; the list is `AppLanguages`). Android 13 and later keep that pick in the system as the per-app language (`app/.../AppLanguage.kt`, from `app/src/main/res/xml/locales_config.xml`); older phones keep it in `Prefs.language` and wrap each activity and service in it; the desktop sets the default locale and draws the window again. A string missing from a language shows in English, so a new string can go into `values/` first; add it to the others before a release. Keep every placeholder (`%1$s`, `%1$d`, a plain `%`), write ’ instead of a straight apostrophe and typographic quotes instead of `"` (aapt2 rejects or swallows those), and give plurals the forms the language needs (Polish and Russian: one, few, many, other; Japanese, Korean and Chinese: other only). A new language also goes into `locales_config.xml` and `AppLanguages`.
- Something only the system can do (open a file, share, notify) stays out of the shared code: pass it in as a callback (`onPlay`, `onFiles`) or add it to an interface like `DownloadControl` and implement it on both sides.
- A new setting goes into `Settings` and `Prefs` once; both apps save it. Show it in `SettingsParts.kt` when it means the same on both.
- Screen readers: a row inside a `SwipeRow` puts `swipeActions()` on its clickable, so its swipe actions are its accessibility actions too; the row of a switch passes `checked` to `SettingRow`, so it reads as one switch with its title.
- Moving an Android screen into `shared`: move the file, switch `R.string` to `Res.string` and `androidx.compose.ui.res` to `org.jetbrains.compose.resources`, and replace what it needs from Android (a `Context`, `DownloadService`) with a callback or a shared interface.

## Build

You need JDK 21 and the Android SDK with platform 37 (the desktop build configures the Android modules too). The Windows setup also needs [Inno Setup 6](https://jrsoftware.org/isinfo.php). The desktop's Bluetooth helper needs [Rust](https://rustup.rs) (and `libdbus-1-dev` on Linux); without cargo the desktop app is built without Bluetooth.

```bash
./gradlew assembleDebug
```

The APKs end up in `app/build/outputs/apk/debug/`, one per ABI plus a universal one.

```bash
./gradlew :desktop:run
./gradlew :desktop:packageDistributionForCurrentOS
```

The first runs the desktop app (`HAMSTR_OPEN=Engine`, or any other settings page, starts it on that page for screenshots; `HAMSTR_TOUR=3` opens the tour on its third page); the second builds its installer for the system you are on (a setup `.exe` on Windows, made by Inno Setup from `desktop/windows/hamstr.iss`; `.dmg` on macOS, `.deb` on Linux) into `desktop/build/compose/binaries/`. An installer can only be built on the system it is for. The Windows setup installs for the signed-in user into `%LOCALAPPDATA%\Programs\Hamstr`, without admin rights or Windows Installer (jpackage's MSI needed both, was slow and asked for restarts); it closes a running Hamstr itself and removes an MSI-installed 0.7.x once.

The Bluetooth helper is built by `buildBle` (cargo, release) and shipped next to yt-dlp and FFmpeg (`bundleTools`). `-Phamstr.cargo="cargo +stable-x86_64-pc-windows-gnu"` builds it with another toolchain, e.g. on Windows without MSVC; release builds pass `-Phamstr.requireBle=true`, so a missing cargo fails them instead of shipping without Bluetooth. CI builds and lints the helper on all three systems.

The desktop app installs its own updates (`desktop/.../Updater.kt`): a helper started outside Hamstr waits for it to quit, runs the setup silently (or swaps a portable copy's files, or the app from the `.dmg`; Linux installs the `.deb` through `pkexec`) and starts Hamstr again with `--updated=<version>`. To try that end to end, serve a release JSON like GitHub's and start an installed Hamstr with `HAMSTR_RELEASES=<its URL>`, `HAMSTR_UPDATE=now` (installs what it offers, unasked) and `HAMSTR_DATA=<a test folder>`. Both download yt-dlp, Cheek, FFmpeg and ffprobe for this system first (`fetchTools`, into `desktop/build/tools`), checked against the checksums each release publishes; the app copies them into its own folder on first start, where yt-dlp can update itself.

The app's version and the Cheek release both apps ship are in `gradle.properties` (`hamstr.version`, `hamstr.cheekVersion`). Cheek goes into the APK as `libcheek.so` per ABI; `cheek.dir` in `local.properties` points at a local Cheek checkout built with cargo-ndk instead. To use the pinned release even when `cheek.dir` is set, pass `-Pcheek.useRelease=true`. Downloaded Cheek binaries are checked against the release's `SHA256SUMS.txt` before packaging.

## Tests

```bash
./gradlew testDebugUnitTest :shared:desktopTest :desktop:test
./gradlew connectedDebugAndroidTest
```

The first runs the phone's unit tests, the shared code's on the desktop JVM (Nearby's protocol, the download list, the engine's rules, the shared screens) and the desktop app's own; its player test plays a clip through the FFmpeg `fetchTools` put into `desktop/build/tools` and skips without it. Its Bluetooth test starts the helper `buildBle` left in `desktop/build/ble` on this computer's adapter, and skips without it. The second needs a device or emulator; it covers the engine, Cheek's release binaries and file handling.

## Demo mode and screenshots

Debug builds carry a demo mode (`app/src/debug`) that swaps the download list for demo entries with drawn posters and media made by the bundled FFmpeg. Your own list comes back when you leave it.

```bash
adb shell am start -n app.hamstr/.demo.DemoActivity --es seed default
adb shell am start -n app.hamstr/.demo.DemoActivity --es seed off
```

`--es seed /sdcard/Download/my.json` loads your own seed in the format of `app/src/debug/assets/demo/default.json`; entries with the same `"playlist"` name become one of your playlists. `python tools/demo_screens.py` seeds the demo on a device, takes every screenshot and renders `docs/images` plus the release picture (`tools/render_release.py`).

For updater screenshots, also build and install `:app:assembleDebugAndroidTest`, then run `python tools/demo_screens.py --updates`. It runs `BackgroundUpdateTest` against a throttled local HTTP fixture: continuing with the screen off, ready-notification handoff, cancellation and a truncated download. Use a separate emulator for this; it stages a test update but never installs it. For release pictures, build both debug APKs with `-Phamstr.version=<previous-version>`; the fixture offers the next bugfix version.

### The tour

A first start (no downloads yet, `toured` not set) opens the tour over the app; Settings › About plays it again. Each page pairs a sentence with a picture drawn from the app's own pieces (`TourScenes.kt`), so the pictures follow the accent, the background and every effect switch, and never go stale like a screenshot would. When a screen in the app changes, change its picture with it. `TourStep` lists the pages; `phoneOnly` ones don't show on the computer, and a `desktopText` replaces the phone's words there.

`./gradlew :shared:desktopTest --tests app.hamstr.ui.TourTest` checks the buttons and renders every page as the phone and the desktop show it into `shared/build/tour`; look at them after changing a page.

## Releasing

Push a tag and GitHub Actions does the rest:

```bash
git tag v0.5.0
git push origin v0.5.0
```

Without a terminal (or from a session that may not push tags): Actions › Release › Run workflow on `main`, with the version (`0.5.0`). That run refuses a version that isn't `1.2.3`-shaped, a branch other than `main`, a version that differs from `hamstr.version` in `gradle.properties`, and a tag that already exists; it builds, makes the tag itself right before publishing (a failed build leaves no tag) and goes on like a pushed tag. Don't create the release by hand on GitHub's release page: the workflow creates it and stops if one exists.

Before tagging:

1. Set the default version in `gradle.properties` (`hamstr.version`) and, if a new Cheek is out, `hamstr.cheekVersion`.
2. Write `.github/release-notes/v<version>.md`. It starts with the what's-new picture, linked by the tag: `![What's new in Hamstr <version>](https://raw.githubusercontent.com/Mailo037/Hamstr/v<version>/docs/images/releases/v<version>.png)`.
3. Keep the notes to this format, strictly: the phone's update sheet and the test `ReleaseNotesTest` read them (`ReleaseNotes.parse`), and the phone shows only its part.

   ```markdown
   ![What's new in Hamstr <version>](<picture URL, as above>)

   One or two sentences: what this release is about, in plain words. Shown on both apps.

   ### Phone and computer

   - **Short title.** One to three sentences, what the user notices and where to find it.

   ### Phone

   - **Short title.** …

   ### Computer

   - **Short title.** …

   ### Details

   How it works, version codes, the Cheek version, what was tested and what wasn't.
   ```

   Only these four `###` sections, in this order; leave out one that has no points, but `### Details` is always there. Every point is one `- **Title.** Text.` line: the title ends with a full stop inside the bold, the text says what changes for the user, without class names, file names or test talk (that goes into Details). The picture, another device's section and Details only show on GitHub. Write for people, not for reviewers: short, concrete, no "fixed an issue where".
4. Make that picture. Change `VERSION`, `FEATURES` and `SHOTS` in [`tools/render_release.py`](tools/render_release.py) to what is new, add any screen it needs to `tools/demo_screens.py` (and to the demo seed), install a debug build and run `python tools/demo_screens.py`. That also renders the README pictures again from the current UI. Look at `docs/images/releases/v<version>.png` before committing; older pictures stay. Every release gets one, a bugfix too.
5. Commit everything, including the picture, before the tag: the notes load it from the tagged commit.

Cheek has the same rule for its own releases (see its AGENTS.md). Ship a Cheek release first and wait until its binaries are published, because both Hamstr builds download them by `hamstr.cheekVersion`.

[`release.yml`](.github/workflows/release.yml) builds the APKs, signs them with the release key and publishes the release with checksums; then it builds the desktop app on Windows (setup `.exe` and a portable `.zip`), macOS and Linux, adds it, and writes one `SHA256SUMS.txt` over every file. The version comes from the tag. If `.github/release-notes/<tag>.md` exists, it becomes the release text; otherwise GitHub writes notes from the commits. Every installed phone app finds the release on its next start (it only looks at the `.apk` files). The desktop installers are not signed yet, so Windows SmartScreen and macOS Gatekeeper warn before the first start.

The key comes from four repository secrets: `HAMSTR_KEYSTORE_B64`, `HAMSTR_KEYSTORE_PASSWORD`, `HAMSTR_KEY_ALIAS` and `HAMSTR_KEY_PASSWORD`. Local release builds read the same values from `signing/keystore.properties`, which git ignores. Keep that key safe: an update only installs over an app signed with the same key.

The desktop installers have no such signature yet, so the checksums carry one: the `checksums` job signs `SHA256SUMS.txt` with `HAMSTR_SIGNING_KEY` (the PEM of `signing/hamstr-release-key.pem`, a P-256 key) into `SHA256SUMS.txt.sig`, and the desktop app installs only an installer listed in checksums that `ReleaseKeys.HAMSTR` verifies. Until a release has both, the app shows it as on its way. Cheek's releases are signed the same way with `CHEEK_SIGNING_KEY` in the cheek repository, and both apps update Cheek only to signed releases. A new key means a new public key in `ReleaseKeys` and a Hamstr release before the first release it signs. To try a desktop update end to end, sign the test release's checksums with a test key and pass its public key (base64 SubjectPublicKeyInfo) as `HAMSTR_RELEASE_KEY` next to `HAMSTR_RELEASES`.
