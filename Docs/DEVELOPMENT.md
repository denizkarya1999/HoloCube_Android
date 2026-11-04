# Build and development notes

HoloCube Android loads the original `yolo26n.pt` with Python `torch.load` on the phone,
exactly like the Quest app. Its SHA-256 is checked before loading. There is no model
export or conversion. `checkpoint_runtime.py` is the same file as the headset's; it
supplies inference-only module methods adapted from Ultralytics 8.4.56 and restores the
checkpoint's saved module structure and weights directly.

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
| Model | Original Ultralytics YOLO26n `.pt` checkpoint |
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
   The Python code drops the alpha channel, normalizes to 0–1, and runs the original
   checkpoint. Only one frame is processed at a time.
4. `YOLO26Helper.parseRows` reads the `[1,300,6]` rows `[x1,y1,x2,y2,confidence,class_id]`,
   removes weak or invalid rows, maps the boxes through `ImageLetterbox` back to frame
   pixels, sorts by confidence, and keeps at most **Number of boxes**. No second NMS pass.
5. `VideoProcessor` draws maize boxes with blue label chips (`label  87%`) on a
   transparent overlay the size of the frame and hands it to `processedFrameView`, which
   sits over the live preview. **Snapshot** draws onto the frame itself and freezes it.

Input is `[1,3,320,320]` float32; output is `[1,300,6]`. The startup check in
`holocube_runtime.initialize` runs the bundled reference tensor and must reproduce the
five recorded detections before camera frames are accepted.

## Differences from the headset

| Headset | Phone |
| --- | --- |
| Passthrough frames with camera pose and scene depth | Camera2 preview; boxes are 2D over the preview |
| Unity GPU letterbox shader, bottom-first RGBA readback | OpenCV letterbox, top-first RGBA bytes |
| Controller buttons | On-screen buttons, joystick settings become sliders |
| Hold B to record | Tap Record, tap Stop |
| 2 PyTorch threads | 4 by default, adjustable in Settings |

## Checkpoints

The source URL, hash, and runtime versions are recorded in
`app/src/main/assets/provenance.json`, copied from the Quest repository. The loader
supports this bundled YOLO26n checkpoint only. For custom weights or another YOLO
architecture, update the allowed checkpoint hash, supported layer methods, class
names, and the reference fixture together.

## Validation

- `./gradlew :app:testDebugUnitTest`: letterbox geometry and YOLO26 row parsing.
- `./gradlew :app:connectedDebugAndroidTest` with a phone attached: letterbox pixel
  layout, label order, a real checkpoint load plus two inferences on device, and
  camera switching/pause/resume survival.
- Device log markers while the app runs: `HOLOCUBE_PT_DEVICE_PARITY_PASSED`,
  `HOLOCUBE_PT_READY`, `HOLOCUBE_PT_CAMERA_FRAME`, `HOLOCUBE_VIDEO_STARTED`,
  `HOLOCUBE_VIDEO_SAVING`, `HOLOCUBE_VIDEO_SAVED`.

See [recorded validation](../VALIDATION.md) for the checks actually performed.

## Sources and licenses

- [HoloCube Quest app](https://github.com/denizkarya1999/HoloCube): Python runtime,
  checkpoint, labels, fixtures, and UI wording.
- [LiFind](https://github.com/denizkarya1999/LiFind): Kotlin project structure,
  Camera2 helper, OpenCV frame processing, preferences, and About screen.
- [Ultralytics module source, version 8.4.56](https://github.com/ultralytics/ultralytics/tree/v8.4.56/ultralytics/nn).
- [Chaquopy version compatibility](https://chaquo.com/chaquopy/doc/current/versions.html).
- [Android PyTorch package](https://chaquo.com/pypi-13.1/torch/).
