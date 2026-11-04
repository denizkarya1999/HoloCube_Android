# Build and development notes

HoloCube Android loads the trained `holocube_transmitter.pt` with Python `torch.load`
on the phone, exactly like the Quest app. Its SHA-256 is checked before loading, and the
loader refuses any checkpoint whose class mapping is not exactly `0: Transmitter`. The
supplied `.pt` is embedded unchanged; deployment performs no further export or conversion.
`checkpoint_runtime.py` is the same file as the headset's; it supplies inference-only
module methods adapted from Ultralytics 8.4.56 and restores the checkpoint's saved module
structure and weights directly.

| Component | Version |
| --- | --- |
| Language | Kotlin 1.9.22, JVM target 17 |
| Build | Android Gradle Plugin 8.7.1, Gradle 8.9, JDK 17 |
| Camera | Camera2 API with a `TextureView` preview (LiFind `CameraHelper` pattern) |
| Image processing | OpenCV 4.14.0 from Maven (`org.opencv:opencv`) |
| Python packaging | Chaquopy 16.1.0 |
| Embedded Python | 3.8 |
| Embedded PyTorch | 1.8.1, CPU (`torch-1.8.1-3-cp38-cp38-android_21_arm64_v8a`) |
| Embedded NumPy | 1.19.5 |
| Model | Trained single-class YOLO26n transmitter `.pt` checkpoint |
| Android | ARM64; minimum API 26 (Android 8.0), target API 34 |

Python 3.8 is the newest version with a prebuilt Android PyTorch wheel on Chaquopy's
package index, which is why the embedded runtime is this old. The same runtime has
passed the desktop and headset parity checks recorded in the Quest repository.

## Build

1. Install Android Studio or the command-line tools with **Android SDK 34** and
   **Build Tools 34.0.0**, plus a JDK 17. Set `sdk.dir` in a local `local.properties`
   file or export `ANDROID_HOME`.
2. Run `bash Tools/setup-python.sh`. It installs an isolated Python 3.8 under
   `.build-tools/`, which Chaquopy's pip step uses to fetch the Android wheels.
   `app/build.gradle.kts` finds it automatically; on another host pass
   `-Pholocube.buildPython=/path/to/python3.8` instead.
3. Build:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

The installable ARM64 APK is `app/build/outputs/apk/debug/app-debug.apk`.
The first build downloads the Chaquopy runtime, the PyTorch and NumPy Android
wheels, and OpenCV; later builds are offline. Pass `-PholocubeAbi=x86_64` for an
emulator build (the ARM wheel is not used then; do not ship that APK).

## Runtime flow

1. `MainActivity` opens the camera through `CameraHelper` and, while inference is on,
   grabs the displayed preview as a bitmap on every `TextureView` update (long edge
   limited to 960 px), at most once per 100 ms.
2. `VideoProcessor` converts the bitmap to an RGBA `Mat`, and `BitmapToRgbaTensor`
   letterboxes it into 320 × 320 with RGB 114 padding using OpenCV (`resize` with
   bilinear interpolation, then `copyMakeBorder`).
3. `PyTorchWorker` runs `holocube_runtime.detect_rgba` on one background thread.
   The Python code drops the alpha channel, normalizes to 0–1, and runs the transmitter
   checkpoint. Only one frame is processed at a time.
4. `TransmitterHelper.parseRows` reads the `[1,300,6]` rows `[x1,y1,x2,y2,confidence,class_id]`,
   removes weak, wrong-class, or invalid rows, maps the boxes through `ImageLetterbox`
   back to frame pixels, sorts by confidence, and keeps only the strongest one
   (`TransmitterHelper.MAX_DETECTIONS = 1`, the transmitter pipeline's rule; there is no
   user setting for it). No second NMS pass. The first selected box logs
   `HOLOCUBE_TRANSMITTER_SINGLE_BOX`. The parser itself takes the class count and box
   limit as parameters so that further models can reuse it.
5. `VideoProcessor` draws a maize box with a blue label chip (`Transmitter  98%`) on a
   transparent overlay the size of the frame and hands it to `processedFrameView`, which
   sits over the live preview. The device tests draw onto the frame itself instead.

Input is `[1,3,320,320]` float32; output is `[1,300,6]`. The startup check in
`holocube_runtime.initialize` verifies that the reference belongs to this checkpoint,
runs the bundled bright transmitter frame, and must reproduce the recorded box within
0.001 confidence and 1 original-image pixel before camera frames are accepted.

## Transmitter screen

`transmitter/TransmitterActivity` is the former standalone HoloCube Transmitter app.
`FullscreenActivity` keeps the screen on with a window flag (no wake lock), requests
`BRIGHTNESS_OVERRIDE_FULL` for that window only, draws into display cutouts with
symmetric safe padding, and hides the system bars (swipe to reveal them). The pattern
`drawable-nodpi/transmitter_frame.png` (1920 × 1200) is fitted without cropping or
stretching; About Us opens the app's About page. The screen is landscape
(`sensorLandscape`); the camera activity pauses and closes the camera while it is shown.

## Differences from the headset

| Headset | Phone |
| --- | --- |
| Passthrough frames with camera pose and scene depth | Camera2 preview; boxes are 2D over the preview |
| Unity GPU letterbox shader, bottom-first RGBA readback | OpenCV letterbox, top-first RGBA bytes |
| Controller buttons | On-screen buttons, joystick settings become sliders |
| Hold B to record | Tap Record, tap Stop |
| 2 PyTorch threads | 4 by default, adjustable in Settings |
| Joystick adjusts confidence; Settings shows the fixed box count | Slider adjusts confidence; no box-count row |

## Checkpoints

The supplied file's provenance, dataset context, hash, and runtime versions are recorded
in `app/src/main/assets/provenance.json`, copied from the Quest repository. The loader
supports only this bundled transmitter checkpoint. For retrained weights, update the
allowed checkpoint hash in `checkpoint_runtime.py` and `TransmitterHelper`, the class
name, and the reference fixtures (`reference.*` plus the two device-test fixtures)
together; the parity checks will refuse a mismatched set.

## Validation

- `./gradlew :app:testDebugUnitTest`: letterbox geometry and single-box row parsing.
- `./gradlew :app:connectedDebugAndroidTest` with a phone attached: letterbox pixel
  layout, the single class name, on-device parity of the angled and low-light
  validation frames against the Ultralytics boxes through the app's RGBA path, a real
  checkpoint load plus two inferences, and camera switching/pause/resume survival.
- Device log markers while the app runs: `HOLOCUBE_PT_DEVICE_PARITY_PASSED`,
  `HOLOCUBE_PT_READY`, `HOLOCUBE_PT_CAMERA_FRAME`, `HOLOCUBE_TRANSMITTER_SINGLE_BOX`,
  `HOLOCUBE_VIDEO_STARTED`, `HOLOCUBE_VIDEO_SAVING`, `HOLOCUBE_VIDEO_SAVED`.

See [recorded validation](../VALIDATION.md) for the checks actually performed.

## Sources and licenses

- [HoloCube Quest app](https://github.com/denizkarya1999/HoloCube): Python runtime,
  trained transmitter checkpoint, class name, parity fixtures, and UI wording.
- [LiFind](https://github.com/denizkarya1999/LiFind): Kotlin project structure,
  Camera2 helper, OpenCV frame processing, preferences, and About screen.
- [Ultralytics module source, version 8.4.56](https://github.com/ultralytics/ultralytics/tree/v8.4.56/ultralytics/nn).
- [Chaquopy version compatibility](https://chaquo.com/chaquopy/doc/current/versions.html).
- [Android PyTorch package](https://chaquo.com/pypi-13.1/torch/).
