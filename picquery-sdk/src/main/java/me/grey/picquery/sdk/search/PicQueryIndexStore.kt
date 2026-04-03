package me.grey.picquery.sdk.search

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

object PicQueryIndexStore {
    private const val MAGIC = "PQIX1"

    fun save(file: File, index: PicQueryIndex) {
        file.parentFile?.mkdirs()
        DataOutputStream(BufferedOutputStream(file.outputStream())).use { output ->
            output.writeUTF(MAGIC)
            output.writeInt(index.size)
            index.entries.forEach { entry ->
                output.writeUTF(entry.id)
                output.writeInt(entry.embedding.size)
                entry.embedding.forEach(output::writeFloat)
            }
        }
    }

    fun load(file: File): PicQueryIndex {
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            val magic = input.readUTF()
            require(magic == MAGIC) { "Unsupported PicQuery index format: $magic" }
            val count = input.readInt()
            val entries = ArrayList<PicQueryIndexEntry>(count)
            repeat(count) {
                val id = input.readUTF()
                val size = input.readInt()
                val embedding = FloatArray(size)
                for (index in 0 until size) {
                    embedding[index] = input.readFloat()
                }
                entries.add(PicQueryIndexEntry(id, embedding))
            }
            return PicQueryIndex(entries)
        }
    }
}
