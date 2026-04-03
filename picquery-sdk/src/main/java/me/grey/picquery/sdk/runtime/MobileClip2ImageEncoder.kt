package me.grey.picquery.sdk.runtime

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.providers.NNAPIFlags
import android.graphics.Bitmap
import android.util.Log
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.EnumSet
import me.grey.picquery.sdk.model.PicQueryBackendPreference
import me.grey.picquery.sdk.model.PicQueryConfig
import me.grey.picquery.sdk.model.PicQueryRuntimeInfo
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.GpuDelegateFactory

internal interface ImageEmbeddingEncoder : Closeable {
    fun encode(bitmap: Bitmap): FloatArray
    fun encodeBatch(bitmaps: List<Bitmap>): List<FloatArray> = bitmaps.map(::encode)
    fun runtimeInfo(): PicQueryRuntimeInfo
}

internal object MobileClip2ImageEncoderFactory {
    fun create(config: PicQueryConfig): ImageEmbeddingEncoder {
        val extension = File(config.modelPaths.imageModelPath).extension.lowercase()
        return when (extension) {
            "tflite" -> TfliteMobileClip2ImageEncoder(config)
            "onnx", "ort" -> OnnxMobileClip2ImageEncoder(config)
            else -> error("Unsupported image model extension: .$extension")
        }
    }
}

private class TfliteMobileClip2ImageEncoder(
    config: PicQueryConfig
) : ImageEmbeddingEncoder {
    private val modelFile = File(config.modelPaths.imageModelPath)
    private val delegatePreference = config.backendPreference
    private var gpuDelegate: GpuDelegate? = null
    private val interpreter: Interpreter
    private val imageSize: Int
    private val channelFirst: Boolean
    private val runtimeInfo: PicQueryRuntimeInfo

    init {
        require(modelFile.exists()) { "Image model not found: ${modelFile.absolutePath}" }
        val options = Interpreter.Options()
        var backend = "CPU"
        var gpuActive = false

        if (delegatePreference != PicQueryBackendPreference.CPU) {
            val compatList = CompatibilityList()
            if (compatList.isDelegateSupportedOnThisDevice) {
                val delegateOptions = compatList.bestOptionsForThisDevice.apply {
                    forceBackend = GpuDelegateFactory.Options.GpuBackend.OPENCL
                }
                gpuDelegate = GpuDelegate(delegateOptions)
                options.addDelegate(gpuDelegate)
                backend = "GPU_OPENCL"
                gpuActive = true
            }
        }

        if (!gpuActive) {
            options.setNumThreads(config.cpuThreads)
        }

        interpreter = Interpreter(modelFile, options)
        val shape = interpreter.getInputTensor(0).shape()
        imageSize = when {
            shape.size >= 4 && shape[1] == 3 -> shape[2]
            shape.size >= 4 -> shape[1]
            else -> 256
        }
        channelFirst = shape.size >= 4 && shape[1] == 3
        runtimeInfo = PicQueryRuntimeInfo(
            imageBackend = backend,
            gpuRequested = delegatePreference != PicQueryBackendPreference.CPU,
            gpuActive = gpuActive
        )
    }

    override fun runtimeInfo(): PicQueryRuntimeInfo = runtimeInfo

    override fun encode(bitmap: Bitmap): FloatArray {
        val input = bitmap.toModelBuffer(imageSize, channelFirst)
        val outputShape = interpreter.getOutputTensor(0).shape()
        val outputSize = outputShape.fold(1) { acc, value -> acc * value }
        val output = ByteBuffer.allocateDirect(outputSize * 4).order(ByteOrder.nativeOrder())
        interpreter.run(input, output)
        output.rewind()
        return FloatArray(outputSize) { output.float }
    }

    override fun close() {
        interpreter.close()
        gpuDelegate?.close()
    }
}

private class OnnxMobileClip2ImageEncoder(
    config: PicQueryConfig
) : ImageEmbeddingEncoder {
    companion object {
        private const val TAG = "PicQueryOnnxImage"
    }

    private val modelFile = File(config.modelPaths.imageModelPath)
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val imageSize: Int
    private val channelFirst: Boolean
    private val runtimeInfo: PicQueryRuntimeInfo

    init {
        require(modelFile.exists()) { "Image model not found: ${modelFile.absolutePath}" }
        val nnapiRequested = config.backendPreference != PicQueryBackendPreference.CPU
        var backend = "ONNX_CPU"
        var gpuActive = false
        session = if (nnapiRequested) {
            createNnapiSession(config)?.also {
                backend = "ONNX_NNAPI"
                gpuActive = true
            } ?: createCpuSession(config)
        } else {
            createCpuSession(config)
        }
        val tensorInfo = session.inputInfo.entries.first().value.info as TensorInfo
        val shape = tensorInfo.shape
        imageSize = when {
            shape.size >= 4 && shape[1] == 3L -> shape[2].toInt()
            shape.size >= 4 -> shape[1].toInt()
            else -> 256
        }
        channelFirst = shape.size >= 4 && shape[1] == 3L
        runtimeInfo = PicQueryRuntimeInfo(
            imageBackend = backend,
            gpuRequested = nnapiRequested,
            gpuActive = gpuActive
        )
        Log.i(TAG, "Image encoder backend=$backend imageSize=$imageSize model=${modelFile.name}")
    }

    override fun runtimeInfo(): PicQueryRuntimeInfo {
        return runtimeInfo
    }

    override fun encode(bitmap: Bitmap): FloatArray {
        val buffer = bitmap.toModelFloatBuffer(imageSize, channelFirst)
        val inputName = session.inputNames.first()
        val inputShape = if (channelFirst) {
            longArrayOf(1, 3L, imageSize.toLong(), imageSize.toLong())
        } else {
            longArrayOf(1, imageSize.toLong(), imageSize.toLong(), 3L)
        }
        val tensor = OnnxTensor.createTensor(
            environment,
            buffer,
            inputShape
        )
        tensor.use { inputTensor ->
            session.run(mapOf(inputName to inputTensor)).use { result ->
                val output = result[0] as OnnxTensor
                val outputBuffer = output.floatBuffer
                outputBuffer.rewind()
                return FloatArray(outputBuffer.remaining()) { outputBuffer.get() }
            }
        }
    }

    override fun close() {
        session.close()
    }

    private fun createCpuSession(config: PicQueryConfig): OrtSession {
        val options = createSessionOptions(config)
        return try {
            environment.createSession(modelFile.absolutePath, options)
        } finally {
            options.close()
        }
    }

    private fun createNnapiSession(config: PicQueryConfig): OrtSession? {
        val options = createSessionOptions(config)
        return try {
            options.addNnapi(
                EnumSet.of(
                    NNAPIFlags.USE_FP16,
                    NNAPIFlags.USE_NCHW,
                    NNAPIFlags.CPU_DISABLED
                )
            )
            environment.createSession(modelFile.absolutePath, options)
        } catch (t: Throwable) {
            Log.w(TAG, "NNAPI session unavailable, falling back to CPU: ${t.message}")
            null
        } finally {
            options.close()
        }
    }

    private fun createSessionOptions(config: PicQueryConfig): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(config.cpuThreads)
            try {
                setInterOpNumThreads(1)
            } catch (_: OrtException) {
                // Older runtimes may reject this tuning hint.
            }
            try {
                setSymbolicDimensionValue("batch", 1)
            } catch (_: OrtException) {
                // Fixed-shape models do not have a symbolic batch dimension.
            }
            if (modelFile.extension.equals("ort", ignoreCase = true)) {
                addConfigEntry("session.load_model_format", "ORT")
            }
        }
    }
}

private fun Bitmap.toModelBuffer(size: Int, channelFirst: Boolean): ByteBuffer {
    val scaled = if (width == size && height == size) this else Bitmap.createScaledBitmap(this, size, size, true)
    val pixels = IntArray(size * size)
    scaled.getPixels(pixels, 0, size, 0, 0, size, size)
    val buffer = ByteBuffer.allocateDirect(size * size * 3 * 4).order(ByteOrder.nativeOrder())

    if (channelFirst) {
        for (channel in 0 until 3) {
            for (pixel in pixels) {
                val value = when (channel) {
                    0 -> ((pixel shr 16) and 0xFF) / 255f
                    1 -> ((pixel shr 8) and 0xFF) / 255f
                    else -> (pixel and 0xFF) / 255f
                }
                buffer.putFloat(value)
            }
        }
    } else {
        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255f)
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255f)
            buffer.putFloat((pixel and 0xFF) / 255f)
        }
    }

    buffer.rewind()
    return buffer
}

private fun Bitmap.toModelFloatBuffer(size: Int, channelFirst: Boolean): FloatBuffer {
    return toModelBuffer(size, channelFirst).asFloatBuffer()
}
