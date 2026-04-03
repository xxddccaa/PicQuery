# PicQuery SDK / Demo

当前仓库已经新增两个模块：

- `picquery-sdk`：Android AAR，负责 MobileCLIP2 推理、向量索引和文本搜图。
- `demo-app`：演示 APK，使用同一个 SDK，支持导入外部模型文件、扫描本地相册并做文本搜图。

## 当前实现

- AAR 与模型文件分离
- 图像编码器走 LiteRT + GPU delegate（设备支持时优先 OpenCL GPU）
- 文本编码器走 ONNX Runtime
- Demo 支持：
  - 导入 `image model`（`.tflite`）
  - 导入 `text model`（`.ort`）
  - 请求相册权限
  - 构建本地图库索引
  - 文本搜索本地图片
  - 把索引缓存到应用私有目录，二次启动可直接加载

## 构建结果

```bash
./gradlew :picquery-sdk:assembleRelease :demo-app:assembleDebug
```

输出文件：

- AAR: `picquery-sdk/build/outputs/aar/picquery-sdk-release.aar`
- APK: `demo-app/build/outputs/apk/debug/demo-app-debug.apk`

## Demo 使用方式

1. 安装 `demo-app-debug.apk`
2. 启动后先导入两个模型：
   - 图像模型：`*.tflite`
   - 文本模型：`*.ort`
3. 授予相册权限
4. 点击 `Build index`
5. 输入自然语言进行搜索

模型会被复制到应用私有目录：

- `files/models/mobileclip2-s0/mobileclip2_image.tflite`
- `files/models/mobileclip2-s0/mobileclip2_text.ort`

索引缓存目录：

- `files/index/gallery-index.bin`
- `files/index/gallery-index.tsv`

## SDK 接入方式

应用侧初始化示例：

```kotlin
val engine = PicQueryEngine(
    context = context,
    config = PicQueryConfig(
        modelPaths = PicQueryModelPaths(
            imageModelPath = "/data/user/0/your.app/files/models/mobileclip2_image.tflite",
            textModelPath = "/data/user/0/your.app/files/models/mobileclip2_text.ort"
        )
    )
)
```

编码和搜索示例：

```kotlin
val index = PicQueryIndex()
engine.addToIndex(index, "image-1", bitmap)
val hits = engine.search("sunset beach", index, topK = 10)
```

## 现阶段约束

- 目前没有把模型下载器做进 demo，先采用“手动导入模型文件”方案
- 目前是 GPU 优先，没有接 Qualcomm QNN / NPU
- 索引缓存目前不做图库变更校验，图库有新增或删除时建议手动重建索引

## 后续建议

下一步建议继续做这三件事：

1. 增加模型下载器（GitHub Release / HuggingFace / OSS）
2. 增加图库增量索引，而不是每次全量重建
3. 实测并修正 MobileCLIP2 导出模型的输入布局与精度对齐
