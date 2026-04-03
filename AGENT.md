# AGENT handoff

## Repo

- Project: `PicQuery`
- Local path: `/Users/xd/Documents/xiedong_dev/mac_code/android_mobile_clip/PicQuery`
- Public fork: `https://github.com/xxddccaa/PicQuery`
- Current branch: `master`

## What is already done

- Refactored the repo into:
  - `picquery-sdk/` Android AAR module
  - `demo-app/` Android demo APK module
- Rewrote `README.md` and published a GitHub release with:
  - demo APK
  - SDK AAR
  - external ONNX model assets
- Added MobileCLIP2 ONNX export tooling:
  - `script/mobileclip2/export_mobileclip2_onnx.py`
- Exported and verified a larger model locally:
  - model family: `MobileCLIP2-B`
  - local export dir: `models/mobileclip2-b/`
- Generated fixed-shape local ONNX copies for accelerator experiments:
  - `models/mobileclip2-b-fixed/mobileclip2_image.onnx`
  - `models/mobileclip2-b-fixed/mobileclip2_text.onnx`
- Build passes locally:
  - `./gradlew :picquery-sdk:assembleRelease :demo-app:assembleDebug`

## Current code changes not yet released to phone

### 1) Better model discovery and index invalidation

File: `demo-app/src/main/java/me/grey/picquery/demo/DemoViewModel.kt`

- demo no longer hardcodes only `models/mobileclip2-s0`
- recursively scans `files/models/` and app external files models directory
- when model paths change, cached gallery index is cleared automatically
- demo now requests `PicQueryBackendPreference.GPU`

### 2) ONNX image backend now tries Android accelerator first

File: `picquery-sdk/src/main/java/me/grey/picquery/sdk/runtime/MobileClip2ImageEncoder.kt`

- ONNX image encoder now tries NNAPI first for `.onnx` / `.ort` image models
- flags used:
  - `USE_FP16`
  - `USE_NCHW`
  - `CPU_DISABLED`
- if NNAPI session creation fails, it falls back to CPU automatically
- runtime info now reports:
  - `ONNX_NNAPI` when NNAPI session is active
  - `ONNX_CPU` when fallback happens
- startup log line added for backend confirmation

## Important reality check

- This repo currently has a working GPU delegate path only for image models in `.tflite`
- For the current MobileCLIP2-B ONNX image model, the new code is using ONNX Runtime + NNAPI as the best practical Android accelerator path without QNN
- NNAPI may route work to GPU / DSP / NPU depending on device driver support; it is not the same thing as a guaranteed pure GPU path
- I had not finished redeploying the new NNAPI build to the phone before this handoff request came in

## Local model status

These model files are intentionally not tracked by git:

- `models/mobileclip2-s0/`
- `models/mobileclip2-b/`
- `models/mobileclip2-b-fixed/`

Reason:

- model files are too large for normal git history
- release assets are the preferred distribution path

## Recommended next steps after pulling this commit

1. Reinstall the new demo APK to the phone
2. Push the fixed-shape `mobileclip2-b-fixed` models to the app private model directory
3. Launch the app and confirm logcat contains `Image encoder backend=ONNX_NNAPI`
4. Rebuild the gallery index and compare indexing speed against the previous CPU-only run
5. If NNAPI still does not help enough, next fallback options are:
   - convert image encoder to `.tflite` and use LiteRT GPU delegate
   - test smaller/faster image encoder variants for indexing, while keeping stronger text model

## Useful commands

### Build

```bash
./gradlew :picquery-sdk:assembleRelease :demo-app:assembleDebug
```

### Push fixed MobileCLIP2-B models to device app-private storage

```bash
adb push models/mobileclip2-b-fixed/mobileclip2_image.onnx /data/local/tmp/mobileclip2_b_image.onnx
adb push models/mobileclip2-b-fixed/mobileclip2_text.onnx /data/local/tmp/mobileclip2_b_text.onnx
adb shell run-as me.grey.picquery.demo mkdir -p /data/user/0/me.grey.picquery.demo/files/models/mobileclip2-b-fixed
adb shell run-as me.grey.picquery.demo cp /data/local/tmp/mobileclip2_b_image.onnx /data/user/0/me.grey.picquery.demo/files/models/mobileclip2-b-fixed/mobileclip2_image.onnx
adb shell run-as me.grey.picquery.demo cp /data/local/tmp/mobileclip2_b_text.onnx /data/user/0/me.grey.picquery.demo/files/models/mobileclip2-b-fixed/mobileclip2_text.onnx
```

### Point demo app to the fixed-shape models

```bash
printf '%s\n' "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>" '<map>' "    <string name='image_model_path'>/data/user/0/me.grey.picquery.demo/files/models/mobileclip2-b-fixed/mobileclip2_image.onnx</string>" "    <string name='text_model_path'>/data/user/0/me.grey.picquery.demo/files/models/mobileclip2-b-fixed/mobileclip2_text.onnx</string>" '</map>' | adb shell run-as me.grey.picquery.demo sh -c 'cat > /data/user/0/me.grey.picquery.demo/shared_prefs/picquery_demo.xml'
```

### Check backend in logcat

```bash
adb logcat -d | rg 'PicQueryOnnxImage|ONNX_NNAPI|ONNX_CPU'
```

## Notes for the next agent

- Do not commit anything under `models/`
- The latest clean published code in the fork was previously pushed as commit `e9ed47f`
- There are new unpushed changes in:
  - `demo-app/src/main/java/me/grey/picquery/demo/DemoViewModel.kt`
  - `picquery-sdk/src/main/java/me/grey/picquery/sdk/runtime/MobileClip2ImageEncoder.kt`
  - `script/mobileclip2/export_mobileclip2_onnx.py`
  - `AGENT.md`
