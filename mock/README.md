# Nino · Sight-Guide HUD (UI Mock)

A browser mock of the **Nino** app's new UI — a liquid-glass vision-assist HUD over a live
camera feed, with simulated classroom detections and real spoken guidance
(Web Speech API). Built to preview the redesigned UI on a laptop and to be ported
into the Android app (`app/src/main/`).

## UI fabric: GlassKit (vendored)

The premium iOS-26 Liquid Glass / visionOS look is **GlassKit**
(`vendor/glasskit/`, MIT, Jungherz GmbH). It is linked directly — pure CSS, no
build step — and re-themed for Nino by overriding its design tokens in
`styles.css` (`--gl-color-primary` → teal, surface/glow/border tokens, blur,
radius). Adopted components:

| Nino element                | GlassKit component                         |
| --------------------------- | ------------------------------------------ |
| Guidance banner + start card| `.glass-card` + `.glass-card--glow`        |
| Bottom dock                 | `.glass-tab-bar--floating` + `.glass-tab-bar-dock` (spotlight active item) |
| Voice switch                | `.glass-toggle`                             |
| Speech-rate slider          | `.glass-range`                              |
| Chips / telemetry pills     | GlassKit surfaces + insets + shadows        |

Keep this mock stateless: no framework, no bundler.

## Run

No build step, no dependencies. From this folder:

```bash
# any static server works
python -m http.server 5173
```

Then open <http://localhost:5173>. (localhost is a secure context, so the
webcam and speech APIs work.)

- Click **Begin guidance** — your browser asks for the camera.
- The HUD streams your webcam; simulated detections from the 13-class
  classroom set animate on top. The moving "person" drifts toward you, so you can
  watch guidance escalate calmly: `chair on your left` → `careful, person ahead`
  → `stop — person ahead`.
- If you deny the camera, a simulated geometric classroom (whiteboard, window,
  door, ceiling fan, desk) keeps the UI previewable.
- `http://localhost:5173/?demo=1` starts the HUD immediately (no button click,
  no camera required) — handy for screenshots and slide demos.

## Features

- **One voice, one object at a time** — the HUD speaks only the single most
  relevant object, and only *when it changes* (new object, zone change, or an
  urgency escalation). Identical object + identical urgency = silence, so the
  voice never nags. Cooldown 3.2 s, 1.4 s when something is directly in the way.
- **READ SCENE** (top chip, dock, or tap the radar) — a calm spoken summary:
  `scene — one person, two chairs and desks, boards, clear path ahead`.
- **Auto scene-reads** every 30 s (14 s in Assist mode) so the room is re-read
  at a walking pace; the radar + banner reconfirm it visually.
- **Radar scene map** — live top-down blips colored by urgency, with the sweep
  line marking "still scanning". Empty zones show ✓ in the path bar.
- **Guidance modes** — *Balanced* (default), *Minimal* (only speaks when your
  path is blocked), *Assist* (more scene-reads for unfamiliar rooms).
- **Focus lock** — the box that is currently being spoken about is lit brighter
  with a colored token, so sighted viewers always know *which* object you're
  navigating by.
- **Spoken-word ticker** — a quiet receipt of the last four things said, with
  timestamps, so guidance history is visible in demos/exams.
- **Speech rate slider** (0.6–1.2×, default 0.9) for slower, measured speech;
  settings sheet holds threads, telemetry (fps / inference ms), and the 13-class
  list.

## What the mock mirrors (for the viva / porting)

| Android file                            | Mock equivalent (`app.js`)                     |
| --------------------------------------- | ---------------------------------------------- |
| `navigation/NavigationGuidance.java`    | `pickDominant / buildPhrase / zoneWorst / scanScene` |
| `navigation/VoiceNavigator.java`        | `speak / evaluateGuidance` (3.2 s / 1.4 s / change-only, flush) |
| `DetectorActivity` guidance banner      | top guidance banner + path bar                 |
| `MultiBoxTracker` overlay boxes         | `.bx` boxes with corner ticks, urgency color, focus lock |
| `layout_bottom_sheet.xml` settings      | glass settings sheet                           |
| 3-zone + urgency logic (left/center/right) | zone meter → path bar + radar blips (cyan/amber/red) |

Same thresholds as the app: box height `> 60%` = **very close (stop)**,
`> 30%` = **close**, thirds of the frame = zones.

## Integration notes (APK)

- The real app ships `detect.tflite` (a COCO model today) — after retraining in
  Colab this becomes the 13-class classroom model. `DetectorActivity` reads
  `labelmap.txt` and auto-detects the label offset.
- The HUD colors/type can be reproduced in Android with layered
  `ConstraintLayout`/`FrameLayout` overlays, a `Canvas`-drawn box view (port
  `.bx` corner ticks into `MultiBoxTracker.draw`), and a `Drawable`-based glass
  panel for the settings sheet.
- Throttling/phrase logic is already implemented in the app; this mock only
  *visualizes* it.