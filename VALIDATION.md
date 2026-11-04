# HoloCube Android validation

Verified on November 4, 2026, on the development workstation and a connected
**Google Pixel 8a** (Android API 37, arm64-v8a). Results below are filled in from the
actual build log, unit-test report, and device logcat of that session.

## Model and app

- App: HoloCube Android 1.0 (code 1), package `com.developer27.holocube.mobile`.
- Model: original Ultralytics `yolo26n.pt`; no ONNX, TorchScript, TFLite, or other export.
- Checkpoint SHA-256: `9b09cc8bf347f0fc8a5f7657480587f25db09b34bf33b0652110fb03a8ad4fef`
  (identical to the Quest repository's file).
- Embedded runtime: Python 3.8, PyTorch 1.8.1, NumPy 1.19.5, Chaquopy 16.1.0.
- Input `[1,3,320,320]` RGB floats in `[0,1]`; output `[1,300,6]` end-to-end rows.
- `checkpoint_runtime.py` is byte-for-byte the Quest app's file. `holocube_runtime.py`
  differs only in pixel order (top row first; no vertical flip).

## Passed on the development computer

- Kotlin compilation with no warnings; `./gradlew :app:assembleDebug :app:testDebugUnitTest`
  finished in 3 min 50 s on the first (downloading) build.
- Unit tests: 10 passed, 0 failed (`ImageLetterboxTest` 4, `YoloParserTest` 5,
  `ExampleUnitTest` 1): letterbox scale/padding for 1280 × 720 and the 810 × 1080
  reference image, model-to-frame box mapping with clamping, confidence/class checks,
  detection limit and ordering, invalid-row rejection, wrong-shape rejection, and no
  second NMS pass (overlapping boxes are both kept).
- Android lint (`:app:lintDebug`): 0 errors, 9 warnings (6 `ButtonStyle`, 3 `Overdraw`,
  both cosmetic and inherited from the LiFind layouts); the first run's 31 warnings were
  reduced by removing unused resources, adding a monochrome launcher layer, and marking
  the intentional portrait lock and ARM-only ABI.
- APK inspection: `lib/arm64-v8a` holds `libopencv_java4.so`, `libpython3.8.so`, and
  the Chaquopy bridge; `assets/chaquopy/requirements-common.imy` (44.9 MB) carries the
  PyTorch 1.8.1 and NumPy 1.19.5 wheels; `assets/chaquopy/app.imy` (5.3 MB) carries the
  Python sources, `yolo26n.pt`, and the reference fixture. Native code: `arm64-v8a` only.
- Manifest: `com.developer27.holocube.mobile`, minSdk 26, targetSdk 34, permissions
  CAMERA, RECORD_AUDIO, and WRITE_EXTERNAL_STORAGE (Android 9 and older only).
- `bash Tools/setup-python.sh` created `.build-tools/pt/bin/python` (CPython 3.8.20).

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`, 96,794,009 bytes,
SHA-256 `e9d507b17fab0b608a5f0fdb0acff8faef652971b7350a26a6ff7c68feb99c57`. (An intermediate incremental package came out at 141.7 MB with the
same entries; a clean package step restored the normal size. Chaquopy's `.imy` archives
and the native libraries are stored uncompressed by design.)

## Passed on the connected Pixel 8a

Device: Google Pixel 8a (`akita`), Android 17 build `CP3A.261005.005`, API 37, arm64-v8a.

- `adb install -r` of the debug APK succeeded; camera and microphone permissions were
  granted with `pm grant` for the unattended launch.
- Cold launch after install: Chaquopy extracted the Python runtime, NumPy, and the
  PyTorch libraries, then the checkpoint loaded and the startup reference check passed
  about 13.7 s after `am start` (14:33:54.4 → 14:34:08.1). No crash was logged.
- Device log markers:

```text
HOLOCUBE_PT_DEVICE_PARITY_PASSED: original checkpoint; five reference detections match.
HOLOCUBE_PT_READY: original YOLO26n checkpoint loaded on device
HOLOCUBE_PT_CAMERA_FRAME: 300 detection rows in 154 ms
```

- Process memory after loading the model and camera preview: about 244 MB PSS.
- Instrumented tests (`am instrument` with the four non-UI tests, device locked):
  **OK (4 tests)** in 2.6 s on a warm second launch: letterbox output is 320 × 320 × 4
  bytes with RGB 114 padding and R, G, B order; 80 labels from `person` to `toothbrush`;
  `VideoProcessor` loads the original checkpoint, runs a 640 × 480 black frame annotated
  in place and a white frame as a transparent overlay, returns frames of the same size,
  and does not mutate an already returned frame. The first inference in that process
  took **154 ms** (one sample including Python input/output handling; the headset's
  first frame took 155 ms). The same four tests passed again on the final APK
  (**OK (4 tests)**, first inference 141 ms).
- Not yet exercised on this device: the live camera preview, box overlay alignment,
  Record/Stop MP4 saving, Settings and About screens, and the camera-lifecycle
  instrumented test. The phone was locked during this session, so UI screens could
  not be opened or captured.

## Remaining checks

- Sustained frame rate, memory, and thermal behaviour over long sessions.
- Front-camera recordings on devices whose sensor orientation differs from 270°.
- Android 8–9 legacy storage path for recordings (only Android 15+ was available).
- Other phones and emulators (x86_64 builds need `-PholocubeAbi=x86_64`).
