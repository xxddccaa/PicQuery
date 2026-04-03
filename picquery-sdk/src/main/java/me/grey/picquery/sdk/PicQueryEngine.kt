package me.grey.picquery.sdk

import android.content.Context
import android.graphics.Bitmap
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.grey.picquery.sdk.model.PicQueryConfig
import me.grey.picquery.sdk.model.PicQueryRuntimeInfo
import me.grey.picquery.sdk.runtime.ImageEmbeddingEncoder
import me.grey.picquery.sdk.runtime.MobileClip2ImageEncoderFactory
import me.grey.picquery.sdk.runtime.MobileClip2TextEncoder
import me.grey.picquery.sdk.search.PicQueryIndex
import me.grey.picquery.sdk.search.PicQuerySearchHit

class PicQueryEngine(
    context: Context,
    private val config: PicQueryConfig
) : Closeable {
    private val appContext = context.applicationContext
    private val textEncoder = MobileClip2TextEncoder(appContext, config)
    private val imageEncoder: ImageEmbeddingEncoder = MobileClip2ImageEncoderFactory.create(config)

    suspend fun warmup() {
        withContext(Dispatchers.Default) {
            textEncoder.encode("warmup")
        }
    }

    suspend fun encodeImage(bitmap: Bitmap): FloatArray {
        return withContext(Dispatchers.Default) {
            normalize(imageEncoder.encode(bitmap))
        }
    }

    suspend fun encodeImages(bitmaps: List<Bitmap>): List<FloatArray> {
        return withContext(Dispatchers.Default) {
            imageEncoder.encodeBatch(bitmaps).map(::normalize)
        }
    }

    fun encodeText(text: String): FloatArray {
        return normalize(textEncoder.encode(text))
    }

    suspend fun addToIndex(index: PicQueryIndex, id: String, bitmap: Bitmap) {
        index.add(id, encodeImage(bitmap))
    }

    suspend fun buildIndex(
        images: List<Pair<String, Bitmap>>,
        onProgress: ((processed: Int, total: Int) -> Unit)? = null
    ): PicQueryIndex {
        val index = PicQueryIndex()
        val total = images.size
        images.forEachIndexed { position, (id, bitmap) ->
            index.add(id, encodeImage(bitmap))
            onProgress?.invoke(position + 1, total)
        }
        return index
    }

    fun search(
        query: String,
        index: PicQueryIndex,
        topK: Int = 20
    ): List<PicQuerySearchHit> {
        return searchByEmbedding(encodeText(query), index, topK)
    }

    fun searchByEmbedding(
        queryEmbedding: FloatArray,
        index: PicQueryIndex,
        topK: Int = 20
    ): List<PicQuerySearchHit> {
        return index.entries
            .asSequence()
            .map { PicQuerySearchHit(it.id, dot(queryEmbedding, it.embedding)) }
            .sortedByDescending { it.score }
            .take(topK.coerceAtLeast(1))
            .toList()
    }

    fun runtimeInfo(): PicQueryRuntimeInfo {
        return imageEncoder.runtimeInfo()
    }

    override fun close() {
        imageEncoder.close()
        textEncoder.close()
    }

    private fun normalize(vector: FloatArray): FloatArray {
        var sum = 0f
        vector.forEach { value ->
            sum += value * value
        }
        val norm = kotlin.math.sqrt(sum.toDouble()).toFloat()
        if (norm <= 0f) return vector
        return FloatArray(vector.size) { index -> vector[index] / norm }
    }

    private fun dot(left: FloatArray, right: FloatArray): Float {
        require(left.size == right.size) {
            "Embedding dimension mismatch: ${left.size} vs ${right.size}"
        }
        var sum = 0f
        for (index in left.indices) {
            sum += left[index] * right[index]
        }
        return sum
    }
}
