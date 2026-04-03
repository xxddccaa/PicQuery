package me.grey.picquery.sdk.model

data class PicQueryRuntimeInfo(
    val imageBackend: String,
    val gpuRequested: Boolean,
    val gpuActive: Boolean
)
