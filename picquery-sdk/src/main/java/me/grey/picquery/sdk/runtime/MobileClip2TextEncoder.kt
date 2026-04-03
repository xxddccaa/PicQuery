package me.grey.picquery.sdk.runtime

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.LongBuffer
import me.grey.picquery.sdk.model.PicQueryConfig

internal class MobileClip2TextEncoder(
    context: android.content.Context,
    config: PicQueryConfig
) : Closeable {
    private val modelFile = File(config.modelPaths.textModelPath)
    private val tokenizer = ClipTokenizer(context, config.tokenizerAssetName)
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        require(modelFile.exists()) { "Text model not found: ${modelFile.absolutePath}" }
        val options = OrtSession.SessionOptions()
        if (modelFile.extension.equals("ort", ignoreCase = true)) {
            options.addConfigEntry("session.load_model_format", "ORT")
        }
        session = environment.createSession(modelFile.absolutePath, options)
    }

    fun encode(text: String): FloatArray {
        val inputName = session.inputNames.first()
        val tokens = tokenizer.tokenize(text)
        val tensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(tokens), longArrayOf(1, tokens.size.toLong()))
        tensor.use { inputTensor ->
            session.run(mapOf(inputName to inputTensor)).use { result ->
                val output = result[0] as OnnxTensor
                val buffer = output.floatBuffer
                buffer.rewind()
                return FloatArray(buffer.remaining()) { buffer.get() }
            }
        }
    }

    override fun close() {
        session.close()
    }
}
