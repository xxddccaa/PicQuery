package me.grey.picquery.sdk.runtime

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.regex.Pattern
import java.util.zip.GZIPInputStream

private fun createCharDict(): Map<Int, Char> {
    val bytesList = mutableListOf<Int>()
    bytesList.addAll(33..126)
    bytesList.addAll(161..172)
    bytesList.addAll(174..255)
    val charList = bytesList.toMutableList()
    var n = 0
    for (b in 0..255) {
        if (b !in bytesList) {
            bytesList.add(b)
            charList.add(256 + n)
            n++
        }
    }
    return bytesList.zip(charList.map { it.toChar() }).toMap()
}

private fun readGzipAsset(context: Context, assetName: String): List<String> {
    context.assets.open(assetName).use { stream ->
        GZIPInputStream(stream).use { gzip ->
            BufferedReader(InputStreamReader(gzip, Charsets.UTF_8)).use { reader ->
                return reader.readLines()
            }
        }
    }
}

private fun getPairs(word: List<String>): Set<Pair<String, String>> {
    return word.zipWithNext().map { it.first to it.second }.toSet()
}

private fun whitespaceClean(text: String): String {
    return text.replace(Regex("\\s+"), " ").trim()
}

internal class ClipTokenizer(context: Context, bpePath: String) {
    companion object {
        private const val START_TOKEN = "<|startoftext|>"
        private const val END_TOKEN = "<|endoftext|>"
        private const val WORD_END = "</w>"
        private const val CONTEXT_LENGTH = 77

        private val PATTERN = Pattern.compile(
            "$START_TOKEN|$END_TOKEN|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+"
        )
    }

    private val byteEncoder = createCharDict()
    private val merges: List<Pair<String, String>>
    private val encoder: Map<String, Int>
    private val bpeRanks: Map<Pair<String, String>, Int>
    private val cache = mutableMapOf(
        START_TOKEN to START_TOKEN,
        END_TOKEN to END_TOKEN
    )

    init {
        val vocab = byteEncoder.values.map { it.toString() }.toMutableList()
        vocab.addAll(vocab.map { "$it$WORD_END" })

        val mergesFile = readGzipAsset(context, bpePath)
        merges = mergesFile.subList(1, 49152 - 256 - 2 + 1).map {
            val parts = it.split(" ")
            parts[0] to parts[1]
        }

        vocab.addAll(merges.map { it.first + it.second })
        vocab.addAll(listOf(START_TOKEN, END_TOKEN))

        encoder = vocab.withIndex().associate { indexedValue -> indexedValue.value to indexedValue.index }
        bpeRanks = merges.mapIndexed { index, pair -> pair to index }.toMap()
    }

    fun tokenize(text: String): LongArray {
        val sotToken = encoder.getValue(START_TOKEN)
        val eotToken = encoder.getValue(END_TOKEN)
        val tokens = mutableListOf<Int>()
        tokens.add(sotToken)
        tokens.addAll(encode(text))
        tokens.add(eotToken)

        if (tokens.size > CONTEXT_LENGTH) {
            tokens.subList(CONTEXT_LENGTH - 1, tokens.size).clear()
            tokens[CONTEXT_LENGTH - 1] = eotToken
        }

        return LongArray(CONTEXT_LENGTH) { index ->
            tokens.getOrElse(index) { 0 }.toLong()
        }
    }

    private fun encode(text: String): List<Int> {
        val cleanedText = whitespaceClean(text).lowercase()
        val matcher = PATTERN.matcher(cleanedText)
        val matches = mutableListOf<String>()
        while (matcher.find()) {
            matches.add(matcher.group())
        }

        val bpeTokens = mutableListOf<Int>()
        for (token in matches) {
            val encodedToken = token.toByteArray().map { byte -> byteEncoder[byte.toInt() and 0xFF] }.joinToString("")
            for (bpeToken in bpe(encodedToken).split(" ")) {
                bpeTokens.add(encoder.getValue(bpeToken))
            }
        }
        return bpeTokens
    }

    private fun bpe(token: String): String {
        cache[token]?.let { return it }

        var word = token.dropLast(1).map { it.toString() }.toMutableList().apply {
            add(token.last().toString() + WORD_END)
        }
        var pairs = getPairs(word)
        if (pairs.isEmpty()) return "$token$WORD_END"

        while (true) {
            val bigram = pairs.minByOrNull { bpeRanks[it] ?: Int.MAX_VALUE } ?: break
            if (bigram !in bpeRanks) break

            val (first, second) = bigram
            val newWord = mutableListOf<String>()
            var i = 0
            while (i < word.size) {
                val j = word.subList(i, word.size)
                    .indexOf(first)
                    .takeIf { it != -1 }
                    ?.plus(i)
                    ?: word.size
                newWord.addAll(word.subList(i, j))
                i = j

                if (i < word.size - 1 && word[i] == first && word[i + 1] == second) {
                    newWord.add(first + second)
                    i += 2
                } else if (i < word.size) {
                    newWord.add(word[i])
                    i++
                }
            }

            word = newWord
            if (word.size == 1) break
            pairs = getPairs(word)
        }

        return word.joinToString(" ").also { cache[token] = it }
    }
}
