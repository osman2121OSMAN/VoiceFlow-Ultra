package com.example.voiceflow.core

import java.util.Locale

class SegmentMerger {
    private val committedWords = mutableListOf<String>()
    private var activePartial: String = ""

    fun clear() {
        committedWords.clear()
        activePartial = ""
    }

    fun updatePartial(partialText: String) {
        activePartial = partialText.trim().replace(WHITESPACE_REGEX, " ")
    }

    fun commitSegment(finalText: String) {
        val candidate = finalText.trim().replace(WHITESPACE_REGEX, " ")
            .ifBlank { activePartial }
        activePartial = ""
        if (candidate.isBlank()) return

        val candidateWords = candidate.split(' ').filter { it.isNotBlank() }
        if (candidateWords.isEmpty()) return

        val overlap = findOverlapWordCount(committedWords, candidateWords)
        val remaining = if (overlap > 0) candidateWords.drop(overlap) else candidateWords
        committedWords.addAll(remaining)
    }

    fun buildPreviewText(): String {
        val committed = committedWords.joinToString(" ")
        if (activePartial.isBlank()) return committed
        if (committed.isBlank()) return activePartial

        val partialWords = activePartial.split(' ').filter { it.isNotBlank() }
        val overlap = findOverlapWordCount(committedWords, partialWords)
        val remaining = if (overlap > 0) partialWords.drop(overlap) else partialWords
        return if (remaining.isEmpty()) {
            committed
        } else {
            "$committed ${remaining.joinToString(" ")}"
        }
    }

    fun finalizeText(): String {
        if (activePartial.isNotBlank()) {
            commitSegment(activePartial)
        }
        return committedWords.joinToString(" ").trim()
    }

    fun hasText(): Boolean = committedWords.isNotEmpty() || activePartial.isNotBlank()

    private fun findOverlapWordCount(existing: List<String>, incoming: List<String>): Int {
        if (existing.isEmpty() || incoming.isEmpty()) return 0

        // Exact full-segment repeat check (common on Android when onResults fires twice)
        if (incoming.size <= existing.size) {
            val tail = existing.takeLast(incoming.size)
            if (wordsEqual(tail, incoming)) {
                return incoming.size
            }
        }

        // Suffix-prefix multi-word overlap (k >= 2 prevents stripping intentional single repeated words)
        val maxCheck = minOf(existing.size, incoming.size, MAX_OVERLAP_WORDS)
        for (k in maxCheck downTo 2) {
            val suffix = existing.takeLast(k)
            val prefix = incoming.take(k)
            if (wordsEqual(suffix, prefix)) {
                return k
            }
        }
        return 0
    }

    private fun wordsEqual(a: List<String>, b: List<String>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            if (!normalizeWord(a[i]).equals(normalizeWord(b[i]), ignoreCase = true)) {
                return false
            }
        }
        return true
    }

    private fun normalizeWord(word: String): String {
        return word.lowercase(TR_LOCALE).trimEnd('.', ',', '!', '?', ';', ':')
    }

    companion object {
        private const val MAX_OVERLAP_WORDS = 8
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val TR_LOCALE = Locale.forLanguageTag("tr-TR")
    }
}
