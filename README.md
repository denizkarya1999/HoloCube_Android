# HoloCube Android

![Device: Android 8.0+ phones](https://img.shields.io/badge/Device-Android%208.0%2B%20phones-3DDC84)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9-7F52FF)
![Model: YOLO26 nano](https://img.shields.io/badge/Model-YOLO26%20nano-6E40C9)
![Original PyTorch checkpoint](https://img.shields.io/badge/Model%20format-Original%20.pt-EF6C00)
![Runs on the phone](https://img.shields.io/badge/Inference-On%20device-238636)
![Structure: LiFind](https://img.shields.io/badge/App%20structure-LiFind-00274C)

The Android phone edition of [**HoloCube**](https://github.com/denizkarya1999/HoloCube),
the Meta Quest 3 / 3S object detection app. It sees the phone camera image, finds
objects, and draws labelled boxes with confidence percentages over the live preview.
The camera, model, and detection code all run on the phone. The APK embeds Python,
PyTorch, and the **original, unchanged `yolo26n.pt` checkpoint**; no model conversion,
ONNX, TorchScript, server, or model download is used, the same pipeline as the headset.

The app is built with the same technologies, UI, and code structure as
[**LiFind**](https://github.com/denizkarya1999/LiFind): Kotlin, Camera2 with a
`TextureView` preview, an OpenCV frame processor, a `PreferenceFragmentCompat`
settings page, a Material 3 theme in Michigan Blue (`#00274C`) and Michigan Maize
(`#FFCB05`), and JUnit tests.

**Website:** <https://denizkarya1999.github.io/> · **Lab:** <https://www.zhangxiao.me/>

`#HoloCube` `#Android` `#Kotlin` `#YOLO26` `#PyTorch` `#Chaquopy` `#OpenCV` `#Camera2`
`#ObjectDetection` `#ComputerVision` `#OnDeviceAI` `#OpticalIoT` `#PeppersGhost`
`#MixedReality` `#UMichDearborn` `#TrustworthyAIoTLab`

## What it does

Every headset function has a phone counterpart. Controller buttons become on-screen buttons.

| Quest 3 / 3S app | HoloCube Android |
| --- | --- |
| Passthrough camera → YOLO26 → AR boxes | Phone camera → YOLO26 → boxes over the live preview |
| **A**: toggle YOLO inference | **Start Inference / Stop Inference** button; "Inference Mode: Off" shows for three seconds |
| Hold **B**: YOLO data collection (MP4 with microphone in `Movies/HoloCube`) | **Record / Stop** button, red "Recording" badge, saved filename shown for six seconds |
| **X**: About HoloCube | **About** page with the same project details and tappable links |
| **Y**: Settings (confidence threshold, number of boxes) | **Settings** page with sliders, plus PyTorch CPU threads |
| **Menu**: Controller Guide | **Guide** dialog |
| Startup reference check of the original checkpoint | Same check; the status line reports model loading and errors |
| — | **Snapshot** (detect one frame and freeze it), zoom, front/back switch, **C** to clear, as in LiFind |

Labels use the model's 80 COCO classes, so the app recognizes everyday objects.
Detecting HoloCube optical tags needs custom training; tag decoding and the optical
data channel remain future work, as in the headset app.

## Research inspiration

Inspired by **Dr. Xiao Zhang, Li Xiao, and Matt W. Mutka**, *HoloCube: 3D Optical
IoT Connections via Software Defined Pepper's Ghost* (ICNP 2024).
[Read the paper](https://www.zhangxiao.me/data/ICNP24_HoloCube.pdf). This independent
prototype is not the authors' official implementation.

## Start here

Open this folder in Android Studio. Read these three files in order:

| Part | File | Its job |
| --- | --- | --- |
| **1. Main screen** | `app/src/main/java/com/developer27/holocube/mobile/MainActivity.kt` | Open the camera, grab preview frames, show results and controls. |
| **2. Frame processing** | `.../videoprocessing/VideoProcessor.kt` | Letterbox the frame, run the model, draw the boxes with OpenCV. |
| **3. Model** | `.../videoprocessing/YOLO26Helper.kt` | Own the PyTorch runtime, the labels, and the YOLO26 output parser. |

The complete flow is:

```text
Camera2 preview (TextureView) → MainActivity → VideoProcessor → list of DetectionResult
                                                     ↑                     ↓
                                    PyTorchWorker + holocube_runtime.py   OpenCV boxes + labels
                                    (original yolo26n.pt)                 over the preview
```

`MainActivity.processFrameWithVideoProcessor()` runs on every preview update while
inference is on. `VideoProcessor` processes one frame at a time on a coroutine, the
Python worker runs PyTorch on its own thread, and the annotated overlay comes back on
the main thread.

## Where things live

```text
HoloCube_Android/
├── app/src/main/
│   ├── java/com/developer27/holocube/mobile/
│   │   ├── MainActivity.kt                 Camera → detection → display, all controls
│   │   ├── AboutHoloCubeActivity.kt        Project details and links
│   │   ├── SettingsActivity.kt             Confidence, number of boxes, CPU threads
│   │   ├── camera/
│   │   │   ├── CameraHelper.kt             Camera2 open/close, preview, zoom, switch, record session
│   │   │   └── VideoRecorder.kt            MP4 + microphone to Movies/HoloCube
│   │   └── videoprocessing/
│   │       ├── VideoProcessor.kt           Frame → letterbox → PyTorch → OpenCV drawing
│   │       ├── YOLO26Helper.kt             Runtime, labels, YOLO26 row parser (no second NMS)
│   │       ├── PyTorchWorker.kt            One background thread for Python/PyTorch
│   │       ├── ImageLetterbox.kt           Fit the frame into 320 × 320; map boxes back
│   │       └── BitmapToRgbaTensor.kt       OpenCV letterbox → RGBA bytes for Python
│   ├── python/
│   │   ├── holocube_runtime.py             Pixels → PyTorch → detection rows
│   │   ├── checkpoint_runtime.py           Load the original .pt and run its layers
│   │   ├── yolo26n.pt                      Original YOLO26 nano checkpoint
│   │   ├── reference.f32 / reference.json  Startup parity fixture (bus image)
│   │   └── YOLO-LICENSE.txt                Ultralytics AGPL-3.0 license
│   ├── assets/
│   │   ├── coco.names.txt                  80 labels in class-ID order
│   │   └── provenance.json                 Source, runtime versions, checksum
│   └── res/                                LiFind-style layouts, preferences, theme
├── app/src/test/                           JUnit: letterbox geometry, YOLO26 parsing
├── app/src/androidTest/                    Device: checkpoint load, inference, camera lifecycle
├── Tools/setup-python.sh                   Isolated Python 3.8 for Chaquopy's pip step
├── Docs/DEVELOPMENT.md                     Versions, runtime flow, headset differences
├── VALIDATION.md                           Checks actually performed
├── .github/workflows/android.yml           CI: unit tests, debug APK, lint
└── LICENSE                                 AGPL-3.0 (see Credits and licenses)
```

## Technologies, matched to LiFind

| Area | LiFind | HoloCube Android |
| --- | --- | --- |
| Language and build | Kotlin, Gradle Kotlin DSL, ViewBinding | Same (Kotlin 1.9.22, AGP 8.7.1, Gradle 8.9) |
| Camera | Camera2 `CameraHelper` with `TextureView`, zoom, front/back | Same helper, plus a MediaRecorder session for data collection |
| Frame processing | `VideoProcessor` coroutine + OpenCV drawing | Same structure; OpenCV also letterboxes the model input |
| Model runtime | TFLite (LiteRT) interpreters | Embedded Python/PyTorch loading the **original** `.pt` (Chaquopy), as on the Quest |
| Output parsing | `YOLOLEDHelper.parseTFLite` | `YOLO26Helper.parseRows` (end-to-end head, no second NMS) |
| Settings | `PreferenceFragmentCompat`, yellow preference rows | Same, with `SeekBarPreference` sliders |
| About | ConstraintLayout card, U-M logo, lab link | Same, with HoloCube's fields, paper, and website |
| Theme | Material 3, splash screen, Michigan colours | Same |
| Tests | JUnit parser tests, device regression test | Same pattern |

## Build, install, and use

Android app ID: `com.developer27.holocube.mobile`. It installs alongside the
[HoloCube Transmitter](https://github.com/denizkarya1999/HoloCube/tree/main/Transmitter)
phone app.

1. Clone this repository and run `bash Tools/setup-python.sh` (creates an isolated
   Python 3.8 for Chaquopy's pip step). Open the folder in **Android Studio** with
   Android SDK 34 and a JDK 17, or build from a terminal:

   ```sh
   ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
   ```

2. Install on a phone with USB debugging enabled:

   ```sh
   adb -s PHONE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
   adb -s PHONE_SERIAL shell am start -n com.developer27.holocube.mobile/.MainActivity
   ```

3. Open **HoloCube Android** and allow camera access. The status line shows
   *Loading object detection…* while the checkpoint loads and its startup check runs,
   then *Ready*.
4. Press **Start Inference**. Boxes, labels, and confidence percentages appear over
   the preview, with the object count and inference time in the status line. Press
   **Stop Inference** to pause; *Inference Mode: Off* disappears after three seconds.
5. Press **Record** for YOLO data collection. Allow microphone access the first time.
   A red *Recording* badge appears; press **Stop** to save. MP4s are saved in
   `Movies/HoloCube` and the filename is shown for six seconds.
6. **Snapshot** detects one frame and freezes it; **C** clears and restarts the camera.
   **Settings** adjusts **Confidence Thresholds** (5–100 %) and **Number of boxes**
   (1–80) with sliders, and the PyTorch CPU thread count. **About** shows the project
   details. **Guide** lists the controls.

The weights and class names are included in the app; no server or model download is
needed. The debug APK is about 97 MB because it carries the Python and PyTorch runtime.

## Verify

```sh
./gradlew :app:testDebugUnitTest                      # letterbox and parser tests
ANDROID_SERIAL=PHONE_SERIAL ./gradlew :app:connectedDebugAndroidTest
adb -s PHONE_SERIAL logcat -s HoloCube                # HOLOCUBE_PT_* and HOLOCUBE_VIDEO_* markers
```

The device tests load the original checkpoint, confirm the startup reference check,
run two inferences, and exercise camera switching, pause, and resume. See
[VALIDATION.md](VALIDATION.md) for the recorded results.

## Labels

The model predicts the 80 COCO classes, with zero-based IDs following the order in
[coco.names.txt](app/src/main/assets/coco.names.txt), from `person` (0) to
`toothbrush` (79).

<details>
<summary>Show all 80 object labels</summary>

```text
person, bicycle, car, motorcycle, airplane, bus, train, truck, boat, traffic light,
fire hydrant, stop sign, parking meter, bench, bird, cat, dog, horse, sheep, cow,
elephant, bear, zebra, giraffe, backpack, umbrella, handbag, tie, suitcase, frisbee,
skis, snowboard, sports ball, kite, baseball bat, baseball glove, skateboard,
surfboard, tennis racket, bottle, wine glass, cup, fork, knife, spoon, bowl, banana,
apple, sandwich, orange, broccoli, carrot, hot dog, pizza, donut, cake, chair, couch,
potted plant, bed, dining table, toilet, tv, laptop, mouse, remote, keyboard, cell
phone, microwave, oven, toaster, sink, refrigerator, book, clock, vase, scissors,
teddy bear, hair drier, toothbrush
```

</details>

## Credits and licenses

- Python runtime, checkpoint, labels, fixtures, and wording come from the
  [HoloCube Quest app](https://github.com/denizkarya1999/HoloCube).
- Project structure, camera helper, processor layout, preferences, and About screen
  follow [LiFind](https://github.com/denizkarya1999/LiFind).
- The YOLO26 model and the adapted Python layer methods are distributed under
  [Ultralytics' AGPL-3.0 or Enterprise licensing](https://docs.ultralytics.com/models/yolo26/);
  the license is included beside the weights and as the repository [LICENSE](LICENSE).
- OpenCV (Apache-2.0), Chaquopy (MIT), Python, PyTorch, and NumPy keep their upstream
  licenses.
