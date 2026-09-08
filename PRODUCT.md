# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

Existing Android/Gradle Java app (`app/`) plus a stateless, framework-free browser mock (`mock/`, HTML/CSS/JS) that previews the redesigned HUD and is ported into `app/src/main/` later. No bundler, no framework in the mock.

## Users

Primary: visually impaired people walking through indoor spaces (classroom, home, corridors) holding an Android phone as a camera. They do not watch the screen; voice is the primary output and guidance must never nag.

Secondary (confirmed): capstone examiners and viva judges who watch the HUD on-screen while the demo runs. The on-screen banner/boxes/radar are the "sighted mirror" of what was spoken, so the demo is legible to them. The redesign weighs both audiences — end-user ergonomics first, demonstration clarity always readable.

## Product Purpose

Turn a silent "object locator" camera app into a genuine navigation assistant: detect obstacles on-device, estimate proximity from bounding-box size, and speak short, calm, directional instructions ("person ahead", "chair on your left", "stop — person very close ahead") — throttled so it never talks over itself.

## Positioning

A fully offline, free, on-device vision + voice navigation assistant whose edge is *measured speech*: it announces only the single most relevant object, only when it changes, at a slow rate, with escalation (ahead → close → critical). No cloud, no API keys, no listening servers.

## Operating Context

- The judged artifact is a capstone viva: the mock runs in a browser on a laptop (or real phone), simulated classroom detections animate over a live webcam, and spoken guidance plays via the Web Speech API.
- The same phrase/zone/urgency logic is ported to the Android app (`NavigationGuidance.java`, `VoiceNavigator.java`, `MultiBoxTracker`).
- Confidence states: camera pending → denied/simulated (a geometric classroom scene) → live feed.
- Examinees control guidance via on-screen dock, settings sheet, and app tiles (Find / Read text / Describe).

## Capabilities and Constraints

- Detection: TFLite on-device. Today MobileNet SSD (COCO); retraining toward a 13-class classroom set (EfficientDet-Lite0) recorded in `training/Nino_Trainer.ipynb`.
- Zones: left / center / right by bounding-box center; urgency from box height (>60% very close = stop, >30% close). Indoor/outdoor walkable.
- Speech: Android TextToSpeech / Web Speech; cooldown 3.2 s, 1.4 s when critical; same phrase not repeated within 8 s.
- Hard constraints: 100% free + offline, no internet dependency, no API keys, remains buildable as a normal Android Studio Gradle project, logic kept simple and explainable for the viva.
- Mock constraint: stateless, no build step, low-tech baseline available (camera optional; simulated scene fallback).

## Brand Commitments

- Name: **Nino** — "sight guide." Brand mark is authored SVG (an eye/north-arrow glyph).
- Surface: deep near-black operate stage; the camera feed is the subject, everything else is lit glass.
- Confirmed color semantics (locked): teal = clear/ahead, amber = care/close, coral = stop/critical. Palette beyond these three duties is free to restyle.
- Voice refers to objects in calm, slow, single-directive sentences; on-screen mirror always present for sighted examiners.
- Presentation: the app is shown inside a drawn phone on a desktop stage; keep this frame.

## Evidence on Hand

- `README.md`, `description.md`, `mock/README.md` (feature + porting map).
- Screenshots of the incumbent mock: `.impeccable/review/mobile.png`, `.impeccable/review/live-hud.png`, `.impeccable/review/desktop.png`.
- Mock implementation: `mock/index.html`, `mock/styles.css`, `mock/app.js`.
- Android implementation of the same logic to be mirrored: `app/src/main/java/com/nino/app/navigation/`, `tracking/`, `res/layout/`.

## Product Principles

1. Calm whisper, never a shout — one directive at a time, slow, and quiet when nothing changed.
2. The voice owns the message; the screen mirrors it rather than adding chatter.
3. Offline and trustworthy — free, on-device, no network, no black boxes (rule-based logic a student can defend).
4. Demonstration clarity — a sighted examiner must instantly see *which* object was announced and *which* direction is safe.
5. Escalation is honest — the UI and voice share one urgency model (ahead/close/critical) and never disagree.

## Accessibility & Inclusion

Built for blind and low-vision users: voice-first output, measured speech rate (default 0.9×), large calib‑contrast guidance, and a legible HUD for low-vision + sighted-examiner reading. Reduced-motion respected. Pinch/zoom safe; controls are large touch targets.