package me.grey.picquery.sdk.model

data class PicQueryConfig(
    val modelPaths: PicQueryModelPaths,
    val backendPreference: PicQueryBackendPreference = PicQueryBackendPreference.AUTO,
    val tokenizerAssetName: String = "bpe_vocab_gz",
    val cpuThreads: Int = 4
)
