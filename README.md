# Nino — Navigation Assistant for Visually Impaired People

An Android app that helps visually challenged people walk indoors and outdoors. Using a live camera feed and on-device computer vision, it detects people and household objects, estimates how close they are, and **speaks short navigation instructions** out loud so the user never has to look at the screen.

## How it works

1. **Computer Vision** — An on-device ML model (MobileNet SSD, trained on the COCO dataset, quantized) runs on the live camera feed on every frame and returns bounding boxes for detected objects.
2. **Directional zones** — Each detected object is placed into one of three horizontal zones based on where its bounding box sits on the screen:
   - **Left** → "on your left"
   - **Center** → "ahead"
   - **Right** → "on your right"
3. **Distance / urgency** — A rough closeness estimate is derived from bounding box height (a taller box means a nearer object):
   - box height > 60% of frame height → **Very close** (stop)
   - box height > 30% of frame height → **Close**
   - otherwise → **Ahead**
4. **Voice assistance** — Android's built-in `TextToSpeech` engine (English, on-device) speaks a short phrase that both names the object **and tells the user the safe action**, e.g.:
   - *"person ahead"*
   - *"chair on your left, move right"*
   - *"table on your right, move left"*
   - *"person close ahead, slow down"*
   - *"person very close ahead, stop"*
5. **Sensible speech pacing** — A cooldown (2.5 s) prevents the app from talking over itself every frame. Urgent "very close" obstacles interrupt sooner (0.9 s), and the exact same phrase is not repeated for 8 seconds so the app only re-announces when something meaningfully changes. When several objects are visible, only the most urgent (closest, then most confident) one is announced.
6. **Voice toggle** — A "Voice" switch in the settings panel (bottom sheet) mutes/unmutes spoken feedback with one tap, keeping the on-screen state updated.

The on-screen overlay still shows live bounding boxes, labels and confidence, and a status banner mirrors the last spoken instruction — handy for demonstrating to sighted examiners.

## Technology

- Android app written in Java, built with Gradle / Android Studio
- On-device ML inference with a quantized MobileNet SSD model on COCO
- Android `TextToSpeech` for voice output
- **100% free and offline** — no cloud APIs, no API keys, no internet dependency. Detection and speech both run entirely on-device.

## Project layout

```
app/src/main/java/com/nino/app/
├── DetectorActivity.java        # detection loop; feeds results to VoiceNavigator
├── CameraActivity.java          # camera pipeline (Camera2 / legacy)
├── tflite/                      # ML inference + Classifier interface
├── tracking/                    # visual bounding-box tracker / overlay
└── navigation/                  # guidance logic + voice output
    ├── NavigationGuidance.java  # zones, urgency, spoken phrases (pure logic)
    └── VoiceNavigator.java      # TextToSpeech + cooldown/throttling
```

## Building

* Clone this repository.
* Open it in Android Studio (or build from the terminal with `./gradlew :app:assembleDebug`).
* The app targets `compileSdkVersion 28` and needs the Android SDK Platform 28 + Build Tools 28.0.3.
* Connect an Android device (camera required) and press Run.

The pretrained model and labels ship inside the repo at `app/src/main/assets/` (`detect.tflite`, `labelmap.txt`), so no model download is needed at build time.

## Model

Quantized MobileNet SSD v1, COCO-trained — bundled in `app/src/main/assets/detect.tflite`.

## Task list / roadmap

- [x] Basic app (camera + on-device object detection)
- [x] Voice assistance (TextToSpeech, on-device, free/offline)
- [x] Directional zones (left / center / right)
- [x] Rough distance / urgency estimation from bounding box size
- [x] Speech cooldown + throttling (no audio spam)
- [x] Natural spoken navigation phrases
- [x] Voice toggle (mute/unmute) in the settings panel
- [ ] Haptic / vibration feedback
- [ ] Distance calibration to real-world units
- [ ] Obstacle path planning / safe-direction suggestion

## Contributors

* Madhesh Y
