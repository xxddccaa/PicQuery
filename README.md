# PicQuery

[中文](README_zh.md) | English

![cover_en](assets/cover_en.jpg)

PicQuery is an offline image search project for Android. It lets you search local photos with natural language such as "a laptop on the desk", "sunset by the sea", or "kitty in the grass".

This repository now contains two deliverables in one project:

- `picquery-sdk`: an Android AAR that exposes text-to-image search capabilities to other apps
- `demo-app`: a demo APK that uses the same SDK and can index your local gallery

## Highlights

- Fully offline image search on device
- Text-to-image retrieval based on CLIP / MobileCLIP style embeddings
- SDK and model files are separated
- Demo app can import or auto-detect external model files
- Index files are cached locally for faster relaunch
- Ready to evolve from CPU/ONNX to GPU/TFLite when image models are available

## Project Layout

```text
PicQuery/
  picquery-sdk/   # Android library (AAR)
  demo-app/       # Demo APK using the SDK
  models/         # Optional local model staging directory, not committed
  assets/         # README / store assets
```

## Current Architecture

### `picquery-sdk`

The SDK provides:

- `PicQueryEngine`: top-level API for encoding, indexing, and searching
- `PicQueryIndex`: in-memory vector index
- `PicQueryIndexStore`: local file persistence for vector indexes
- `ClipTokenizer`: bundled tokenizer asset (`bpe_vocab_gz`)

Runtime support:

- Text encoder: ONNX Runtime (`.onnx` / `.ort`)
- Image encoder:
  - `.tflite` -> LiteRT with GPU delegate preferred when available
  - `.onnx` / `.ort` -> ONNX Runtime

### `demo-app`

The demo app provides:

- Model import UI
- Local gallery indexing
- Text search over indexed local photos
- Cached gallery index persistence
- Automatic model path discovery for common app directories

## Model Strategy

This project intentionally keeps model files out of the AAR.

Why:

- model files are large
- different apps may want different model variants
- shipping models separately makes SDK reuse easier

### Where models are expected at runtime

For the demo app, the preferred runtime paths are:

- image model:
  - `/data/user/0/me.grey.picquery.demo/files/models/mobileclip2-s0/mobileclip2_image.onnx`
  - or `/data/user/0/me.grey.picquery.demo/files/models/mobileclip2-s0/mobileclip2_image.tflite`
- text model:
  - `/data/user/0/me.grey.picquery.demo/files/models/mobileclip2-s0/mobileclip2_text.onnx`
  - or `/data/user/0/me.grey.picquery.demo/files/models/mobileclip2-s0/mobileclip2_text.ort`

The demo can also import models from the system file picker and copy them into its own private directory.

## How To Use The Demo App

1. Install the demo APK
2. Provide the model files in one of these ways:
   - import them from the UI
   - push them into the app private files directory
3. Grant photo permission
4. Tap `Build index`
5. Enter a natural-language query and search

### Demo app storage layout

The demo app stores files here:

- models:
  - `files/models/mobileclip2-s0/`
- cached index:
  - `files/index/gallery-index.bin`
  - `files/index/gallery-index.tsv`

## Build

Build the AAR and demo APK:

```bash
./gradlew :picquery-sdk:assembleRelease :demo-app:assembleDebug
```

Artifacts:

- AAR: `picquery-sdk/build/outputs/aar/picquery-sdk-release.aar`
- APK: `demo-app/build/outputs/apk/debug/demo-app-debug.apk`

## SDK Integration Example

```kotlin
val engine = PicQueryEngine(
    context = context,
    config = PicQueryConfig(
        modelPaths = PicQueryModelPaths(
            imageModelPath = "/data/user/0/your.app/files/models/mobileclip2_image.onnx",
            textModelPath = "/data/user/0/your.app/files/models/mobileclip2_text.onnx"
        )
    )
)

val index = PicQueryIndex()
engine.addToIndex(index, "image-1", bitmap)
val hits = engine.search("sunset beach", index, topK = 10)
```

## Local Development Notes

- `models/` is a local staging directory for testing and adb workflows
- large model files should not be committed to the repository
- if GitHub blocks large files, publish them as Release assets instead

## FAQ

### Why is the app still asking me to import models?

The app only works after valid image and text model files are available at runtime. If they are not found in the app's private files directory, the demo falls back to asking for manual import.

### Does the current demo use Snapdragon NPU / QNN?

Not yet. The current working path is:

- image: TFLite GPU when a `.tflite` image model exists, otherwise ONNX Runtime
- text: ONNX Runtime

QNN was investigated, but the current implementation prioritizes a stable deliverable first.

## Acknowledgements

- [apple/ml-mobileclip](https://github.com/apple/ml-mobileclip)
- [openai/CLIP](https://github.com/openai/CLIP)
- [mazzzystar/Queryable](https://github.com/mazzzystar/Queryable)
- [IacobIonut01/Gallery](https://github.com/IacobIonut01/Gallery)

## License

This project is open-source under the MIT License.
