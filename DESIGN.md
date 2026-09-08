# Nino · Harbor Beacon — Design & Review

World chosen via the impeccable concept-seed (key `5a31f487`, index 7, mode
operate). This file records the direction contract, the visual system, and the
finish-line review so the mock can be ported to Android with intent.

## Direction contract (embedded in `mock/index.html` first body comment)

- **THESIS** — Nino is a lighthouse, not a HUD. Guidance is a beam of light
  over dark water: calm and wide on a clear course, narrowing and hot when the
  path must be held. Detections are buoys; urgency is read as light, not as
  label shouting.
- **OWN-WORLD** — refuses the glass-shop default of blur + gradient text. Here
  light means *meaning*: the beam widens/tightens, the buoy glow escalates, and
  only one thing is ever lit up as the target.
- **STORY** — chat-room port authority. The radio log scrolls as a receipt,
  the radar is a sounding chart, the scan button re-reads the room like a depth
  reading, and the em dash is "NINO speaking".
- **FIRST VIEWPORT** — a calm dark sound: glimpse of Nino, the lamp, the room
  sensor chip (room/voice/camera status), and one warm invitation to begin.
- **FORM** — phone frame kept, collapses full-bleed under 560 px. One section
  at a time; guidance card + beam + course rule on top, console dock below.
- **FINISH** — nothing decorative. Instrument line labels read as data; urgent
  red is earned, never cosmetic; reduced-motion honored.

## Locked semantics (user-confirmed)

- teal = clear course · amber = close care · coral = standing stop
- Name stays **Nino**; palette is free outside those three duties.
- Audience: balanced — blind-user ergonomics + sighted examiners (capstone viva).
- Motion: one authored animation (feed scan + beam lick), everything else quiet.

## Visual system (`mock/styles.css`)

| Token | Value | Job |
| --- | --- | --- |
| `--sea-deep` | `#04080e` | frame/night water |
| `--sea` | `#08131f` | HUD ground |
| `--fog` | `#9db2c0` | body text on dark |
| `--steel` | `#1b2938` | console/dock surfaces |
| `--teal` | `#40e0c2` | clear course |
| `--amber` | `#ffb64b` | close care |
| `--coral` | `#ff5f52` | stop |
| `--mono` | `Martian Mono` | data, chart labels |
| `--sans` | `Archivo` | UI, phrases |

Urgency drives custom props on `body[data-urgency]`: `--u`, `--u-softrgba`,
`--beam-scale`, `--beam-a`. The guidance beam is a `scaleX` transform
(no layout animation), widths: ahead .72 / close .58 / critical .46.

### Components

- `.guidance` + `.beam-lick` — light card; `#metaState` reads CLEAR / CARE / STOP.
- `#courseRule` `.cr-tick` — 3-zone path ticks; active = lit, clear = ✓.
- `.water-card` — start card, focus screen, sheets.
- `.console` `.con-btn` — steel dock, spotlight active item.
- `.bx` buoys — detections: corner ticks + urgency glow, focus lock via `data-focus`.
- `.chart` `#radar` — sounding chart with sweep; reported at READ SCENE.
- `.water-toggle` / `.water-range` — voice switch / speech-rate slider.
- `.ticker` — radio log (grid-rows reveal, no max-height animation).

### Motion & a11y

- One authored animation: `.beam-lick` (re-fired by `pulseBeam()` on speech) +
  the radar sweep. `prefers-reduced-motion` disables both.
- `color-scheme: dark` themes browser surfaces; focus-visible rings on all
  interactive controls; `aria-live` on phrase + ticker; labels present for
  every control; buttons ≥ 44 px hit area.
- Contrast on all text ≥ 4.5:1 in the dark HUD.

## Finish-line review (impeccable)

- **Mechanical detector**: run once on `mock/index.html`, `mock/styles.css`,
  `mock/app.js` → **0 findings** (regex fallback; parser modules unavailable in
  this environment, so custom-property/contrast checks are an undercount, not a
  clean bill of health).
- Layout-animation warnings addressed: beam `width` → `scaleX`; ticker
  `max-height` → `grid-template-rows`.
- Static verification: `node --check app.js` passes; 57/57 `els` ids exist in
  `index.html`; CSS brace balance 336/336.
- Screenshots: `.impeccable/review/desktop.png` captured from the demo route
  (`?demo=1`). `live-hud.png` / `mobile.png` retained from the baseline run.

## Porting notes (Android)

- Beam = `LinearGradient` sweep drawn on the frame + width tied to urgency
  ints; course rule = three segment views toggled `is-active`/`is-clear`.
- Buoys = port `.bx` corner ticks + glow into `MultiBoxTracker.draw` (Canvas).
- Console dock = `FrameLayout`/`ConstraintLayout` button row with spotlight
  drawable; settings = `Drawable`-based panel (no GlassKit dependency).
- Fonts: bundle Archivo + Martian Mono (or `RobotoCondensed`/monospace fallback).