# HoloCube Android validation

## In-Device Local File Inference — November 7, 2026

- HoloCube Android **1.2 (code 3)**, package `com.developer27.holocube.mobile`, installed
  over 1.1 on the connected **Pixel 8a** (`akita`, Android 17, API 37). Same checkpoint
  as 1.1 (SHA-256 `1b65b56b…`); the change is the new **Settings → Model Testing →
  In-Device Local File Inference** screen (`LocalFileInferenceActivity`), the
  `LocalInferenceModel` registry, `ACTION_VIEW` / `ACTION_SEND` image filters, and the
  shared `Settings.Inference.applyPreferences` helper.
- 18 frames of the pyramid dataset's testing split (five from each clip) plus the three
  parity-fixture frames were copied to the phone's `Pictures/HoloCube Test Dataset`
  with `adb push` and registered with the media store (`content call … scan_file`),
  so they appear in the system picker and in Photos.

### Passed on the development computer

- `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
  :app:lintDebug`: BUILD SUCCESSFUL; 15 unit tests passed, 0 failed (the three new
  `LocalInferenceModelTest` cases cover the default model, unknown-id fallback, and
  unique ids/names); lint 0 errors, 11 warnings (`ButtonStyle` / `Overdraw`, cosmetic).
  A first lint run flagged the `ACTION_VIEW` filter as a deep link without a scheme;
  explicit `content` and `file` schemes fixed it. Log: `Builds/build-local-inference.log`.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`, 98,986,578 bytes,
SHA-256 `8e4f6831e91fed4b43c087a8b71ae90c1e9729bf5b29b81d630b77752af4fe8e`.

Release APK (`./gradlew :app:assembleRelease`, `Builds/build-release-1.2.log`):
`Install HoloCube Android 1.2.apk`, 96,670,546 bytes, SHA-256
`852b8683a03d292b137a2ff4f0ca986a4cfa498aa8d1b3532684906ab136205d`; `aapt` reports
versionCode 3 / versionName 1.2, `arm64-v8a` only; the Python archive holds exactly one
`.pt` with the approved hash plus the `frame_01153` startup reference. It replaced the
1.0 file in the phone's `Apps` folder and in the Drive `Apps` folder (the 1.0 copy is in
Drive's trash), and a local copy is `Builds/Install HoloCube Android 1.2.apk`.

### Passed on the connected Pixel 8a

- Real user path: `am start -a android.intent.action.VIEW` with the media-store URI of
  `reference_angled_frame_03905.jpg` (as a file manager's "Open with HoloCube" would send)
  opened the screen; the model loaded (`HOLOCUBE_PT_DEVICE_PARITY_PASSED`,
  `HOLOCUBE_PT_READY`), inference ran (`HOLOCUBE_PT_CAMERA_FRAME: 300 detection rows in
  139 ms`, `HOLOCUBE_TRANSMITTER_SINGLE_BOX`), and the status line read
  **`reference_angled_frame_03905.jpg · 1280 × 720 · 1 transmitter · 133 ms`** with
  **`Transmitter 91% · box left 636, top 274, right 738, bottom 335 px`**; the annotated
  image showed one maize box on the pyramid. Read back with `uiautomator dump` and
  captured as `Screenshots/10.png` (131 ms on the capture run); the Settings page with
  the new **Model Testing** entry is `Screenshots/11.png`.
- New instrumented test `LocalFileInferenceTest.openingTheAngledFixtureShowsOneTransmitterBox`:
  **OK (1 test)** in 2.7 s on the shipped build (`Builds/androidTest-local-inference.log`).
  The angled fixture is written as a PNG, opened through the screen's own `openImage`,
  and yields exactly one Transmitter at the reference confidence (within 0.001) and
  position (within 1 px), with the status and detail lines reporting it.
- The ten earlier instrumented tests were not re-run on 1.2; the camera, recording,
  Transmitter-screen, and parser code did not change.
- The release APK was then installed over the debug build (`adb install -r`; package
  information confirms 1.2 / code 3) and its cold launch passed the startup reference
  check on the phone (`HOLOCUBE_PT_DEVICE_PARITY_PASSED`, `HOLOCUBE_PT_READY`).

### Remaining checks

- Picking an image through the system picker by hand (the **Open Image** button) and
  the Share-sheet route were not exercised in this session; the intent path they both
  end in was.
- Live camera detection of the physical transmitter.
- The instrumented tests were run against the debug build; the release build differs
  only in the build type (no minification), and its startup check passed on the phone.

---

## Pyramid-only Transmitter Recognition Model — November 6, 2026

- HoloCube Android **1.1 (code 2)**, package `com.developer27.holocube.mobile`. Built on
  the development workstation and installed over 1.0 on the connected **Pixel 8a**
  (`akita`, Android 17, API 37, arm64-v8a) on November 7, 2026. The screenshots in the
  sections below still show the 1.0 model except where noted.
- Model: the checkpoint supplied on November 6 (10,384,601 bytes), byte-identical to the
  Quest repository's file, embedded unchanged. One class, `0: Transmitter`, which now
  means the transmitter's **central pyramid** only; 1.0 boxed the whole assembly. Training
  run, dataset, and Ultralytics metrics are in `app/src/main/assets/provenance.json`
  (copied from the Quest repository).
- Checkpoint SHA-256:
  `1b65b56bb61c0102bb963e34126d1e58cb0c459f0f14dc5a232262ec62d0e260`
  (`checkpoint_runtime.py`, `TransmitterHelper`, `reference.json`, and both device-test
  fixtures carry it).
- Fixtures replaced with 1280 × 720 validation-split frames of the new dataset:
  `reference.*` (bright, `frame_01153`), `fixtures/angled` (`frame_03905`), and
  `fixtures/low_light` (`frame_02444`), reference boxes from Ultralytics 8.4.56 /
  PyTorch 2.12.0 on the unfused model. `checkpoint_runtime.py` and the fixture files are
  byte-for-byte the Quest repository's; `holocube_runtime.py` is unchanged.
- The Quest repository's `Tools/validate-pt.py`, run under PyTorch 1.8.1 (the embedded
  runtime's version), reproduced all three references within 0.001 confidence and
  1 original-image pixel and passed its blank-input, RGBA-orientation, reference-mismatch,
  and altered-checkpoint checks.

### Passed on the development computer

- `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
  :app:lintDebug`: BUILD SUCCESSFUL in 45 s; 12 unit tests passed, 0 failed; lint
  0 errors, 10 warnings (cosmetic, unchanged). Log: `Builds/build-pyramid.log`.
- APK packaging check (mirrors the Quest build guard): `assets/chaquopy/app.imy` contains
  exactly one `.pt`, `holocube_transmitter.pt`, whose SHA-256 matches the approved
  weights, plus `reference.f32` / `reference.json` for the new model; native code
  `arm64-v8a` only. The test APK carries the two new fixtures.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`, 97,863,704 bytes,
SHA-256 `e085a734cbfd364b5b2f064770f0a30b78ced50eae9e671d6a3a533ae8b494e5`.

### Passed on the connected Pixel 8a

- `adb install -r` of the debug and test APKs over 1.0 succeeded (package information
  confirms 1.1 / code 2); camera and microphone permissions granted with `pm grant`.
- Cold launch with the screen locked: the checkpoint loaded and the startup reference
  check passed on the phone (`HOLOCUBE_PT_DEVICE_PARITY_PASSED`, `HOLOCUBE_PT_READY`);
  no crash reported.
- Instrumented tests (`am instrument`, the six non-UI tests): **OK (6 tests)** in 3.3 s,
  log `Builds/androidTest-pyramid-nonui.log`:
  - letterbox output is 320 × 320 × 4 bytes with RGB 114 padding in R, G, B order;
  - the class list is exactly `Transmitter`;
  - **on-device parity**: the new angled and low-light frames, sent through the app's own
    RGBA path, reproduce the Ultralytics 8.4.56 / PyTorch 2.12.0 boxes within 0.001
    confidence and 1 original-image pixel (a quarter of a model-input pixel at 1280 wide);
  - **end to end**: the angled frame as a bitmap goes through OpenCV letterboxing, the
    Python runtime, the single-box parser, and the OpenCV drawing, yielding exactly one
    Transmitter at confidence 0.913 on the pyramid, with a maize outline on the frame
    (saved as `Screenshots/9.png`); `HOLOCUBE_TRANSMITTER_SINGLE_BOX` logged;
  - `VideoProcessor` loads the checkpoint and processes a black and a white frame without
    mutating an earlier result; first inference `HOLOCUBE_PT_CAMERA_FRAME: 300 detection
    rows in 155 ms` (one sample, not sustained FPS);
  - the instrumentation context resolves the app package.
- With the phone unlocked, the four UI instrumented tests: **OK (4 tests)** in 12.8 s,
  log `Builds/androidTest-pyramid-ui.log`: `cameraSurvivesSwitchingAndPausing` (three
  camera switches and clears, pause and resume cycles, inference start then pause and
  resume, during which a live camera frame returned 300 rows in 170 ms) and the three
  `TransmitterScreenTest` tests (whole image fitted in landscape with keep-awake and full
  brightness across recreation; About Us opens and Back restores the image; controls
  auto-hide and reopen). All ten instrumented tests therefore pass on the shipped
  1.1 build.

Device log markers:

```text
HOLOCUBE_PT_DEVICE_PARITY_PASSED: Transmitter Recognition Model; reference boxes match.
HOLOCUBE_PT_READY: Transmitter Recognition Model loaded on device; class 0: Transmitter
HOLOCUBE_PT_CAMERA_FRAME: 300 detection rows in 155 ms
HOLOCUBE_TRANSMITTER_SINGLE_BOX: 1 detection selected; highest confidence; limit 1.
```

### Remaining checks

- Live camera detection of the physical transmitter, and whether the smaller pyramid
  box is usable at phone-camera distances.
- A release build; `Builds/Install HoloCube Android 1.0.apk` is still the 1.0 model.

---

## Earlier releases

Verified on November 4, 2026, on the development workstation and a connected
**Google Pixel 8a** (`akita`, Android 17 build `CP3A.261005.005`, API 37, arm64-v8a).
Results come from the actual build logs, unit-test reports, device logcat, and
instrumented test runs of that session.

## Combined app with the Transmitter — November 4, 2026

Update later the same day: the launcher label changed from "HoloCube Android" to
**HoloCube** (`app_name`); the app ID, version, and everything else are unchanged.
The release APK was rebuilt and redeployed; see the device list below for where.

Changes since the transmitter-recognition edition: About keeps only the project
details (the **HoloCube** title opens the website); the Snapshot button and the
"Number of boxes" settings row are gone; the standalone HoloCube Transmitter app is
merged in as the **Transmitter** button; the three round buttons auto-size their
labels to one line.

### Passed on the development computer

- `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
  :app:lintDebug`: build successful; 12 unit tests passed; lint 0 errors, 10 warnings
  (6 `ButtonStyle`, 4 `Overdraw`, cosmetic).
- Transmitter pattern `drawable-nodpi/transmitter_frame.png` SHA-256
  `7b25031ad241596fe8f22cfa3d14c8229c419091e95fff5116146b389f6d6c6e`, identical to the
  file the standalone app shipped.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`, 103,082,140 bytes,
SHA-256 `c65a872004784b0f03b099520c81a40414bb33d39e7124f4f65c3fcc03dffcd6`.

### Passed on the connected Pixel 8a

- Final APK installed; cold launch passed the startup reference
  (`HOLOCUBE_PT_DEVICE_PARITY_PASSED`, `HOLOCUBE_PT_READY`).
- Six non-UI instrumented tests on the final APK: **OK (6 tests)**, first inference
  163 ms, `HOLOCUBE_TRANSMITTER_SINGLE_BOX` logged by the end-to-end test.
- On the build immediately before the label auto-sizing (same code otherwise):
  all seven `HoloCubeRegressionTest`/`ExampleInstrumentedTest` tests passed, including
  `cameraSurvivesSwitchingAndPausing` (three camera switches and clears, pause and
  resume cycles, inference start then pause and resume), and the three ported
  `TransmitterScreenTest` tests passed: whole 1920 × 1200 image fitted in landscape
  with the keep-screen-on flag and `BRIGHTNESS_OVERRIDE_FULL` set, system bars hidden,
  state kept across recreation; About Us opens the app's About page and Back restores
  the image only; the About Us button auto-hides after four seconds and reopens on tap.
  Espresso's touch injection does not work on Android 17 (it needs a removed hidden
  `InputManager` method), so these tests drive views with `performClick`.
- Final APK, phone unlocked: `cameraSurvivesSwitchingAndPausing` and the three
  `TransmitterScreenTest` tests passed (**OK (4 tests)**), so all ten instrumented
  tests pass on the shipped build. A launch then ran live inference (first frame
  **89 ms**, status line **0 transmitters · 149 ms** facing the ceiling) and a
  three-second recording saved `Movies/HoloCube/HoloCube_20261104_154517_045.mp4`
  with `HOLOCUBE_VIDEO_STARTED`, `HOLOCUBE_VIDEO_SAVING`, and `HOLOCUBE_VIDEO_SAVED`.
- Screenshots (in `Screenshots/`, numbered 1–7; 8 is the annotated frame): the three round buttons show **Transmitter**,
  **Start Inference** / **Stop Inference**, and **Record** / **Stop** on one line each;
  the recording badge and paused status; the Settings page with the confidence slider
  and CPU threads only; the About page with only the project details and
  **Platform: Android Smartphone + Meta Quest 3S**; the Guide dialog; and the
  Transmitter screen full screen in landscape with About Us revealed by a tap.

### Remaining checks

- A live camera detection of the physical transmitter on the phone.
- Sustained frame rate, memory, and thermal behaviour over long sessions.
- Front-camera recordings on devices whose sensor orientation differs from 270°.
- Android 8–9 legacy storage path for recordings (only Android 17 was available).
- Other phones and emulators (x86_64 builds need `-PholocubeAbi=x86_64`).

## Transmitter Recognition edition — November 4, 2026

### Model and app

- App: HoloCube Android 1.0 (code 1), package `com.developer27.holocube.mobile`.
- Model: the trained **HoloCube Transmitter Recognition Model** `holocube_transmitter.pt`
  (10,386,814 bytes), embedded unchanged. One class, `0: Transmitter`, YOLO26n
  architecture. No ONNX, TorchScript, TFLite, or other export.
- Checkpoint SHA-256: `3da988ae9e3aa5a66b175adb6275463d21f48828faac5ad77ebd0465570e7cf5`
  (identical to the Quest repository's file; the loader and the startup reference
  both check this hash).
- Embedded runtime: Python 3.8, PyTorch 1.8.1, NumPy 1.19.5, Chaquopy 16.1.0.
- Input `[1,3,320,320]` RGB floats in `[0,1]`; output `[1,300,6]` end-to-end rows.
- `checkpoint_runtime.py` is byte-for-byte the Quest app's file (class mapping check
  included). `holocube_runtime.py` differs only in pixel order (top row first).
- The pipeline keeps only the highest-confidence valid Transmitter above the
  threshold (`TransmitterHelper.MAX_DETECTIONS = 1`, fixed; Settings shows
  **Number of boxes (fixed): 1**).

### Passed on the development computer

- Kotlin compilation with no warnings; `./gradlew :app:testDebugUnitTest
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` finished in 10 s
  on the incremental build (the first build of the day took 3 min 50 s with downloads).
- Unit tests: 12 passed, 0 failed (`YoloParserTest` 7, `ImageLetterboxTest` 4,
  `ExampleUnitTest` 1): the strongest valid Transmitter wins over deliberately
  unsorted candidates while a stronger wrong-class row and a stronger inverted box
  are rejected; no confident row means no box; limit and ordering when more boxes are
  requested; no second NMS pass; invalid-row rejection; wrong-shape rejection;
  letterbox scale/padding and box mapping.
- Android lint: 0 errors, 9 warnings (6 `ButtonStyle`, 3 `Overdraw`, cosmetic and
  inherited from the LiFind layouts).
- APK packaging check (mirrors the Quest build guard): the Python archive
  `assets/chaquopy/app.imy` contains exactly one `.pt`, `holocube_transmitter.pt`,
  whose SHA-256 matches the approved weights, plus `reference.f32`/`reference.json`;
  the former `yolo26n.pt` is not packaged. Native code: `arm64-v8a` only.
- Manifest: `com.developer27.holocube.mobile`, minSdk 26, targetSdk 34, permissions
  CAMERA, RECORD_AUDIO, and WRITE_EXTERNAL_STORAGE (Android 9 and older only).
- `bash Tools/setup-python.sh` created `.build-tools/pt/bin/python` (CPython 3.8.20).

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`, 103,081,912 bytes,
SHA-256 `789116288d8e3331163840e64d9b2d6db345b3111ccccf73b62dcdfca6059a0f`.
Chaquopy's `.imy` archives and the native libraries are stored uncompressed by design.

### Passed on the connected Pixel 8a

- `adb install -r` of the debug and test APKs succeeded; camera and microphone
  permissions were granted with `pm grant` for the unattended run.
- Cold launch: the checkpoint loaded and the startup reference check passed; the
  first live camera call returned 300 rows in **97 ms** (one worker-call sample,
  not sustained FPS). The status line showed **HoloCube · 0 transmitters · 184 ms**
  while the camera looked at a dark desk, with no box drawn.
- Instrumented tests (`am instrument`, six non-UI tests): **OK (6 tests)** in 4.0 s:
  - letterbox output is 320 × 320 × 4 bytes with RGB 114 padding in R, G, B order;
  - the class list is exactly `Transmitter`;
  - **on-device parity**: the angled and low-light validation frames, sent through
    the app's own RGBA path, reproduce the independent Ultralytics 8.4.56 /
    PyTorch 2.12.0 boxes within 0.001 confidence and 1 original-image pixel;
  - **end to end**: the angled frame as a bitmap goes through OpenCV letterboxing,
    the Python runtime, the single-box parser, and the OpenCV drawing, yielding
    exactly one Transmitter at confidence 0.759 at the reference position with a
    maize outline on the frame (saved as `Screenshots/8.png`);
  - `VideoProcessor` loads the checkpoint, processes a black frame annotated in place
    and a white frame as an overlay, returns frames of the same size, and does not
    mutate an already returned frame;
  - the instrumentation context resolves the app package.
- UI checked by screenshot: the ready status, the live-inference status line, the
  Settings page (confidence slider, fixed box count, CPU threads), the About page
  (**Platform: Android Smartphone + Meta Quest 3S**, model line, paper and website
  rows), and the Guide dialog. Earlier in the session, with the general detector,
  Record/Stop saved `Movies/HoloCube/HoloCube_20261104_145711_916.mp4` (9.7 MB)
  and logged `HOLOCUBE_VIDEO_STARTED`, `HOLOCUBE_VIDEO_SAVING`, `HOLOCUBE_VIDEO_SAVED`;
  the recording code did not change in this edition.

Device log markers:

```text
HOLOCUBE_PT_DEVICE_PARITY_PASSED: Transmitter Recognition Model; reference boxes match.
HOLOCUBE_PT_READY: Transmitter Recognition Model loaded on device; class 0: Transmitter
HOLOCUBE_PT_CAMERA_FRAME: 300 detection rows in 97 ms
HOLOCUBE_TRANSMITTER_SINGLE_BOX: 1 detection selected; highest confidence; limit 1.
```

### Remaining checks

- A live camera detection of the physical transmitter on the phone: the parity and
  end-to-end tests use validation frames from the dataset; nobody pointed the phone
  at a transmitter during this session.
- The camera-lifecycle instrumented test (`cameraSurvivesSwitchingAndPausing`) was
  not run on this edition.
- Sustained frame rate, memory, and thermal behaviour over long sessions.
- Front-camera recordings on devices whose sensor orientation differs from 270°.
- Android 8–9 legacy storage path for recordings (only Android 17 was available).
- Other phones and emulators (x86_64 builds need `-PholocubeAbi=x86_64`).

## Earlier in the same session: general YOLO26n detector

The first build of this repository embedded the original `yolo26n.pt`
(SHA-256 `9b09cc8bf347f0fc8a5f7657480587f25db09b34bf33b0652110fb03a8ad4fef`,
80 COCO classes). On the same Pixel 8a it passed its five-detection startup reference,
the four non-UI instrumented tests, a live camera run, and a six-second recording,
with first-frame times of 141–154 ms. That model was then replaced to match the
headset's transmitter-only release; it is no longer packaged.
