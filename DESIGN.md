# Hamstr design

Rules for every screen of the app, taken from the components as they are built. Hamstr
stands on two older systems: Slate gives the tokens, radii, press feedback and travel
easing, Glyph the idea that the app is alive and that ASCII is an accent. Where a phone
needs something else (touch targets, sheets instead of dialogs, gestures), this file wins.

Values live in code, not here: colours, type and radii in `ui/theme/Theme.kt`, motion in
`ui/components/Motion.kt`, scroll fades in `ui/components/EdgeFade.kt`, sheets in
`ui/components/Sheet.kt`, chips in `ui/components/Chips.kt`. The last section lists the
places that break these rules.

## Principles

1. **Alive, never static.** Nothing appears, disappears or moves in a single frame.
   Lists make room, rows glide to their new place, icons swap with a scale crossfade.
2. **Every touch answers.** Pressing settles, dragging follows the finger 1:1,
   crossing a threshold ticks. Feedback starts with the input, not after it.
3. **Physical.** What can be dragged rubber-bands past its limits, stretches with its
   speed and springs back. Release decides: past the threshold or flung, it commits;
   otherwise it returns.
4. **Calm when idle.** Only the background raster and the mark's blink move on their
   own. Nothing pulses for attention; a pulse means work is happening (FFmpeg).
5. **Flat.** No shadows, no outlines, no decorative gradients. Surfaces separate by
   lightness, and rows inside a panel by a 1 dp line.
6. **ASCII is an accent.** A normal, polished touch UI with characters in a few
   chosen places (see [ASCII](#ascii)).
7. **Every effect can be switched off.** Each decorative effect has a switch in
   Settings > Motion & effects, and the system's reduce-motion turns travel into snaps.

## Colour

### Surfaces

Depth comes from lightness: each layer is one step lighter than the one it sits on.

| Token | Used for |
| --- | --- |
| `canvas` | Screen background, under the raster glow. |
| `overlay` | Panels on the canvas: groups, tab bar, side bar, search field, sheets. |
| `surface` | Things on an overlay: cards and rows inside sheets, chips, neutral buttons, icon tiles, text fields in sheets. |
| `raised` | What floats over content: the snack, the update banner. |
| `hover` | Selection pills (tab bar, segmented control), the switch track when off. |
| `black` | Behind pictures and video, so letterboxing is truly dark. |

Inside a panel, tracks sink instead of rising: the segmented control's track takes the
step *below* its parent (`canvas` in an `overlay` group or sheet), so the pill reads as
lying in it. On the bare canvas, where nothing is lower, a track is `overlay` like any
panel (the library's filter, the search field).

### Text and icons

| Token | Used for |
| --- | --- |
| `ink` | Titles, row titles, values, active icons. |
| `muted` | Supporting text, inactive icons, swipe labels. |
| `ghost` | Hints, placeholders, meta lines, small labels, chevrons. |
| `accent` | The one thing to do or the thing that is on. |
| `red` | Errors and destructive actions only. |
| `green` | Only the done check. |

Meta lines join facts with ` · ` in `ghost`: `channel · 212 MB · 3 h ago`.

### Accent

One accent, chosen by the user; six bases (Graphite, OLED, Cocoa, Midnight, Moss, Plum). `onAccent` is
derived from the accent's luminance, so text on accent stays readable for any choice.
Colour changes from Settings ease over 260 ms instead of snapping.

- Never hardcode the orange in UI code; read `Hamstr.colors.accent`.
- The hamster outside the app wears it too: widgets draw the mark with accent cheeks
  (`AppIcon.mark`), shortcuts are drawn in it, and the launcher icon switches to the
  activity-alias of the nearest preset (and the chosen icon style) once the app is left.
- At most one filled accent button per sheet: the primary action.
- Accent marks: the primary button, selected chips, progress, the play button,
  category icons, the text cursor and selection, a search icon once something is typed,
  a sort button that is not on its default.

## Type

Inter for everything people read, JetBrains Mono for what machines produce.

| Style | Size | Used for |
| --- | --- | --- |
| `hero` | 36 | The stash total, nothing else. |
| `title` | 21 | Screen and sub page titles. |
| `section` | 17 | Sheet titles, section headers, empty state titles. |
| `bodyMedium` | 14 / 500 | Row titles, file titles. |
| `body` | 14 | Setting titles, running text. |
| `button` | 15 / 600 | Button labels. |
| `small` | 12 | Subtitles, meta lines, snack text. |
| `mono` | 12 | Percentages, times (`1:23 / 4:56`), links, file names, info values, engine messages. |
| `badge` | 10.5 mono | `[ BADGES ]` and small group labels above chip rows. |

- Numbers that change while you look at them are mono, so they don't jitter.
- Capitals only inside `[ ]` badges; everything else is sentence case.
- Text that can overflow sets `maxLines` and ellipsizes (`Txt` does this).

## Shape

### Radii

| Token | dp | Used for |
| --- | --- | --- |
| `Radii.sheet` | 28 | Top corners of every sheet. |
| `Radii.panel` | 20 | Groups (`Group`, `GroupedItem`). |
| `Radii.row` | 16 | Rows, cards inside sheets. |
| `Radii.control` | 14 | Buttons, fields, segmented track. |
| `Radii.thumb` | 10 | Square thumbnails in rows. |

Sizes in use without a token yet: 4 (sheet handle), 6 (badges on pictures), 8 (small
text buttons), 10 (chips), 12 (icon buttons and icon tiles), 14 (16:9 pictures, the
snack), 16 (action tiles), 18 (menu group, mini player, update banner), 22 (tab bar,
side bar). A new radius picks one of these; if it repeats, it becomes a token.

**Nested corners are concentric:** a child's radius is the parent's radius minus the
padding between them, where the padding is small (up to about 8 dp).

| Parent | Padding | Child |
| --- | --- | --- |
| Group 20 | 4 | rows 16 |
| Tab bar 22 | 5 | pill 17 |
| Segmented track 14 | 4 | pill 10 |
| Mini player 18 | 4 | media 14 |

### Spacing and sizes

- Work on the 4 dp grid. Screens have a 12 dp gutter; headers and titles sit 6 dp
  further in. Sheets pad 16 dp at the sides, 12 dp on top and 16 dp at the bottom.
- Between rows in a group 2 dp, between chips 6 dp, between buttons 8 dp, before a
  section header 18 dp. Scroll content ends with a 24 dp spacer.
- **Every touch target is at least 48 dp**, even when the visible thing is smaller.
  `IconButton` shows 34–38 dp and gives the finger 48 dp. Rows are at least 48 dp tall.
- Buttons are 46 dp high, fields in sheets 52 dp, the search field 44 dp, segmented
  options 36 dp, tab bar items 54 dp.
- Pictures: 52 dp squares in rows, 16:9 tiles in the library and in previews, 64 × 36 dp
  in pick lists.

## Scrolling

1. **Every scroll container fades the edges it can still scroll towards.** Use
   `Modifier.edgeFade(state, orientation)`. An edge with nothing behind it stays crisp;
   its fade grows in over 180 ms as soon as there is something. This holds for screens,
   sheets, lists inside sheets and horizontal chip rows alike; only the tab pager is
   exempt.
2. **Order matters.** `edgeFade` goes after the background and clip and before
   `verticalScroll` / `horizontalScroll`, so the content fades and the surface does not.
3. **Under floating chrome, pass the insets.** Screen lists get
   `edgeFade(..., insets = contentPadding)`: content disappears under the status bar and
   the tab bar, and the fade starts at their inner edge. Anything that moves the whole
   list (pull to stash) goes after the fade, so the fade stays put.
4. **Fade length:** 28 dp (the default) on screens and chip rows, 22 dp inside sheets.
5. **Sheets scroll only when they have to.** `Sheet` is at most 90 % of the screen tall
   and its body scrolls (`sheetScroll()`) only when the content overflows, so a drag on
   a sheet that fits still pulls the sheet down. Anything else that scrolls only
   sometimes uses the same trick (`enabled = state.maxValue in 1 until Int.MAX_VALUE`).
6. **A sheet with its own list** uses `Sheet(scroll = false)` and gives the list
   `Modifier.weight(1f, fill = false)`: the list takes the room that is left, with its own
   fade, and the buttons under it stay put. When the header is tall (playlist and
   episode pickers), it rides in the list as its first item (`PickList(header = …)`), so a
   short screen scrolls it away. One scroller per axis: never a list inside a scrolling
   sheet, never a fixed height in dp.
7. **Horizontal rows never wrap.** Chips, playlist tiles and colour swatches scroll
   sideways in one line, with a fade.
8. **Lists animate their items.** Lazy items use `animateItem` with a 320 ms fade in,
   a 160 ms fade out and placement on `spring(0.8, StiffnessMediumLow)`, and stable keys.

## Components

### Sheets

Everything modal is a bottom sheet inside `Overlay`; there are no centred dialogs.

- `Overlay` dims the screen with black at 55 %, springs the sheet in, closes on back,
  on a tap on the backdrop, and on a drag down past 28 % of its height or a fling. Pulled
  up, the sheet stretches against heavy resistance. The backdrop lightens with the drag.
- Every sheet is a `Sheet { }` (`ui/components/Sheet.kt`). It brings the `overlay`
  background, `Radii.sheet` corners, the 36 × 4 dp handle, the padding, room for the
  system bars and the keyboard, swallows taps so they don't reach the backdrop, and
  eases its height when the content changes. Don't build the scaffold by hand.
- The title is a `SheetHeader`: `section` title, an optional `ghost` line under it, the
  mark or a picture in front, an accent badge after it. Sheets that open on a picture
  (the share sheet, a running download's menu) start with the picture instead.
- Text fields on a sheet are `SheetField`: 52 dp, `surface`, an icon that turns accent
  once the input is good, a clear button, an optional fixed suffix (`.mp4`).
- Inside a sheet, rows and cards are `surface`; groups of rows are `surface` panels.
- Buttons go at the bottom: Cancel (neutral, weight 1) before the primary (weight 2);
  a lone primary fills the width.

### Groups and rows

- Rows that belong together sit on one panel: `Group` (overlay, `Radii.panel`, 4 dp
  padding, 2 dp between rows). Settings rows are separated by `Divider`, a 1 dp `line`
  inset 12 dp. In lazy lists, `GroupedItem` builds the panel per item so every row can
  animate on its own, with the outer corners on the first and last.
- Row anatomy: a leading picture (52 dp) or icon tile (38 dp, `surface`, 12 dp radius,
  accent icon), 12–14 dp gap, title over a meta line, then one trailing thing: a
  control, a percentage, a menu button or a chevron.
- A setting row is tappable as a whole; tapping the row of a switch toggles it.
- A row that is active or picked lifts one surface step (`overlay` → `surface`).

### Buttons

- `HButton`: Primary (accent), Neutral (surface) or Ghost (transparent), 46 dp,
  `Radii.control`, optional 18 dp icon. Disabled is a `surface` button with `ghost`
  text, never a faded primary.
- `IconButton`: every icon-only control, always with a label for screen readers.
- `PlayPause`: an accent circle; the icon swaps with the scale crossfade.
- Text-only actions inside rows (Show all, Update, Undo) are accent text on a
  transparent, at least 40 dp tall hit area.

### Press feedback

- Standalone things you press (buttons, icon buttons, tiles, cards, chips, banners) use
  `pressable`: they settle by 1 dp and scale to 0.97 (0.92 for icon buttons, 0.9 for the
  play button) on a quick spring. It takes an `onLongClick` too (library tiles).
- Rows inside a panel use `clickable`, which dims; a scaling row would pull away from
  its neighbours.

### Choosing

| Situation | Component |
| --- | --- |
| One of 2–4 short options | `Segmented`: the pill can be tapped or dragged. |
| One of many, or a varying list | `Chips`: one line, scrolls sideways. For several at once, `Chip`s in a `ChipRow`. |
| On or off | `HSwitch` in a `SettingRow`. |
| Ticking items in a list | `[×]` / `[ ]` in mono, and the row lifts to `surface`. |
| Selecting tiles in a grid | The round select mark in the tile's corner. |
| A player mode (repeat, shuffle) | `ToggleChip`, tinted with 18 % accent while on. |

Colours of anything selectable blend over 220 ms with `animateSelection`, never jump.
A chip is `surface` while off on a panel or sheet, and `overlay` on the bare canvas (pass
`background`). It shows at 36 dp inside a 48 dp touch area, so a chip row is 48 dp tall:
leave 6 dp less space around it.

### Progress and status

- `ProgressLine`: 3 dp, `line` track, accent fill. Progress glides on
  `spring(StiffnessLow)` instead of jumping between yt-dlp's updates.
- Grey (`ghost`) while paused or waiting for the network; full and pulsing while FFmpeg
  works on the file.
- A thumbnail doubles as progress: the real picture wipes in from the top with a 1 dp
  accent edge, ASCII below it.
- A resume position is a 3 dp accent bar along the bottom edge of the picture.
- Status is never colour alone: a failed row also says what failed, in words.

### Messages, empty states, errors

- The snack: `raised`, 14 dp corners, above the tab bar, one short line and at most one
  action (Undo, Open, Retry) in accent. It stays 2.6 s, or as long as Undo is possible.
- Empty states: the mark at 96 dp, a `section` title, one `muted` sentence, centred.
- Errors: what happened in plain words (`Failures.explain`), a `muted` hint, the
  engine's own message in mono behind *Details*, and a way out (try again, update
  yt-dlp, copy the error). Never the raw message alone.

### ASCII

ASCII appears in exactly these places:

- The background raster: a faint character field under the accent glow at the top of
  the canvas, drifting at about 15 fps. The only ambient motion.
- `AsciiImage`: thumbnails drawn as coloured characters while they load, with a
  falling-character rain when there is no picture yet.
- `DecodeText`: text that *arrives* (screen titles, the stash total, sheet titles, the
  title of a running download) decodes in from noise. Not for every text. Screen readers
  get the real text.
- `[ BADGES ]` for facts about a thing: site, quality, duration, state.
- The mark, the `[×]` checkboxes, and the mono *or* between two ways in.

No ASCII buttons, borders, box drawing or whole ASCII screens.

### Pictures and scrims

Over arbitrary pictures and video, black scrims keep text readable: a bottom gradient up
to 78 % under text on a preview, 72 % / 78 % at the top and bottom of the landscape
player, 55–65 % pills behind times and the seek label. Labels on pictures (quality,
length, state) are a `PictureLabel`: white mono on black at 65 %, 6 dp corners, in
brackets when it is a state (`badge = true`). Scrims are functional; they never appear on
plain surfaces.

The only decorative gradient is the accent glow at the top of the canvas.

## Motion

### Timing

Recurring motion has a name in `Specs` (`ui/components/Motion.kt`); use it instead of
writing the numbers again. A spec that is used once stays inline, with a comment saying
why (the mark's choreography, the sheet's softer entry, the switch thumb).

| Spec | Value | Used for |
| --- | --- | --- |
| `press` | spring (0.55, 900) | Press feedback, with a 1 dp drop over 90 ms |
| `swap` | spring (0.5, 600) | A state icon scaling in; `iconSwap()` is the whole crossfade |
| `pop` | spring (0.4, 500) | Arrivals with an overshoot: done check, armed swipe, chosen swatch |
| `settle` | spring (0.5, 480) | Back to rest after a drag: a short, visible wobble |
| `arrive` | spring (0.62, 520) | A selection pill reaching its option |
| `enter` | spring (0.86, 420) | A sub page sliding in, the pager moving to a tab |
| `glide` | spring (StiffnessLow) | Progress and other values that follow data |
| `place` | spring (0.8, StiffnessMediumLow) | Rows moving to their new place |
| `listIn` / `listOut` | 320 / 160 ms | Rows fading in and out |
| `blend` | 220 ms | Selection colours, a sheet changing height, a chevron turning |
| `appear` / `disappear` | 160 / 120 ms | Content fading in and out |
| `leave` | 200 ms on `TravelEasing` | A sheet, page or row travelling off screen |

Not in `Specs` because they belong to one component: the sheet comes up on spring
(0.78, 360), a little softer than a page; the edge fade appears over 180 ms; the theme
eases over 260 ms when the accent or base changes.

- Entries spring, exits are short tweens: leaving is faster than arriving.
- Motion driven by a gesture continues with the gesture's velocity (`initialVelocity`).
- Tweens that travel use `TravelEasing`, Slate's `cubic-bezier(.22, .61, .36, 1)`.

### Gestures

- A drag follows the finger 1:1 in the direction it is meant for; the other direction
  resists (`rubberBand`), it never stops dead.
- Things that travel stretch along their direction with their speed
  (`rememberVelocityStretch` + `stretchX` / `stretchY`): the tab pill, the switch thumb,
  the seek thumb, swipe rows (they tilt), the sheet (barely). Small things stretch up to
  30–45 %, big ones 5 %.
- Thresholds sit at about 26–30 % of the width or height, or a fling. Arming pops the
  icon and ticks; disarming ticks again; releasing armed confirms.
- Every gesture has a visible equivalent: swipe actions are also in the file menu, pull
  to stash is also the + button, dragging a sub page away is also the back button.
- Destructive swipes fly the row out and offer Undo in the snack.

### Haptics

Always through `LocalHapticFeedback`, so the switch in Settings silences them.

| Type | When |
| --- | --- |
| `SegmentTick` | Moving into another option, toggling a selection, disarming |
| `GestureThresholdActivate` | A drag arms (swipe row, pull to stash) |
| `Confirm` | An armed gesture is released and runs |
| `ToggleOn` / `ToggleOff` | A switch flips |
| `ContextClick` | Play / pause |
| `GestureEnd` | A sub page is dragged away |

### Switches and reduced motion

- Every decorative effect reads its flag from `LocalFx` (intro, raster, rain, decode,
  ASCII thumbnails, moods, stretch) and has a row in Settings > Motion & effects.
- `LocalReduceMotion` is true when the system's animator scale is 0: travel snaps,
  decorative loops don't start, and nothing else breaks.
- Every endless loop checks one of the two before it starts.

## Layout

- Upright: the tab bar floats 12 dp above the bottom, the mini player above it, and
  screens get matching bottom padding. Sideways: tabs and mini player move into a 216 dp
  side bar on the left, so screens keep their full height.
- Swiping sideways anywhere changes tabs; the tab pill and the pager follow each other.
- A screen starts with a header row: the title on the left, icon buttons on the right.
- Settings are split into category pages, never one long page. The root has search; a
  hit opens its page and flashes the setting.
- The player is one surface that morphs between the mini bar and the full view; held
  sideways, a video fills the screen with its controls over scrims.
- The mini bar: tap opens, up opens, sideways turns the queue (the neighbour slides in,
  the buttons stay), down stops, a hold shows the queue. Its visible equivalents are the
  next button and the queue sheet's *Close player*. Floating, docked (a lip on the tab bar,
  `overlay`) or slim, per Settings > Appearance.
- A song's page follows music apps: a square cover, the title in `title`, the artist with
  the heart beside it, then shuffle, previous, play, next, repeat in one row and the sleep
  timer, speed, lyrics and queue under it. A lit mode is accent with a dot under it.
- Settings > Appearance > Size scales the density (0.9, 1, 1.1), so every dp and sp moves together.

## Text and accessibility

- User-facing text lives in `res/values/strings.xml`.
- Icon-only controls carry a label; decorative text that animates (decode) exposes the
  real text to screen readers.
- Colour never carries meaning alone, and touch targets stay at 48 dp.

## Known deviations

When something has to break a rule above, note it here with the file and line and the
reason, so the next change can fix it or turn it into a rule.

- `player/PlayerHost.kt` (`rememberCoverColor`): a song's full view has a gradient from its
  cover's colour into the canvas. A second decorative gradient besides the accent glow,
  asked for so a song feels like its cover.
- `ui/LibraryScreen.kt`: song rows swipe (play next, favourite), so a sideways drag that
  starts on one doesn't change tabs. The Stash rows already did this. Grid tiles don't
  swipe: half a screen wide, they slid over their neighbours.
