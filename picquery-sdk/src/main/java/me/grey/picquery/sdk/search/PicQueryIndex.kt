package me.grey.picquery.sdk.search

class PicQueryIndex(
    internal val entries: MutableList<PicQueryIndexEntry> = mutableListOf()
) {
    val size: Int
        get() = entries.size

    fun add(id: String, embedding: FloatArray) {
        entries.add(PicQueryIndexEntry(id, embedding))
    }

    fun clear() {
        entries.clear()
    }

    fun snapshot(): List<PicQueryIndexEntry> = entries.toList()
}
