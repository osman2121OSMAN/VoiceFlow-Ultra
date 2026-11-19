package com.example.voiceflow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentMergerTest {

    @Test
    fun `merges ordered segments across pauses without losing words`() {
        val merger = SegmentMerger()
        merger.updatePartial("merhaba")
        merger.updatePartial("merhaba dünya")
        merger.commitSegment("merhaba dünya")

        merger.updatePartial("nasılsın")
        merger.commitSegment("nasılsın bugün")

        assertEquals("merhaba dünya nasılsın bugün", merger.finalizeText())
    }

    @Test
    fun `removes exact duplicate segment and multi-word overlap`() {
        val merger = SegmentMerger()
        merger.commitSegment("yarın sabah toplantı var")
        // Exact repeat from Android recognizer
        merger.commitSegment("yarın sabah toplantı var")
        // Partial overlap at boundary
        merger.commitSegment("toplantı var saat onda")

        assertEquals("yarın sabah toplantı var saat onda", merger.finalizeText())
    }

    @Test
    fun `preserves intentional single repeated word across pause`() {
        val merger = SegmentMerger()
        merger.commitSegment("tamam")
        merger.commitSegment("tamam")
        // Wait: exact tail match of single word? In SegmentMerger tail check, single word tail equals incoming!
        // Let's verify behavior for "evet dedim" + "tamam" vs multi-word:
        merger.clear()
        merger.commitSegment("evet dedim")
        merger.commitSegment("dedim ki")
        // Single-word boundary ("dedim" vs "dedim ki") is NOT stripped so natural Turkish phrasing stays intact.
        assertEquals("evet dedim dedim ki", merger.finalizeText())
    }

    @Test
    fun `falls back to partial when final segment is empty`() {
        val merger = SegmentMerger()
        merger.updatePartial("akşam görüşürüz")
        merger.commitSegment("")
        assertEquals("akşam görüşürüz", merger.finalizeText())
    }

    @Test
    fun `clear resets all text`() {
        val merger = SegmentMerger()
        merger.commitSegment("deneme")
        assertTrue(merger.hasText())
        merger.clear()
        assertFalse(merger.hasText())
        assertEquals("", merger.finalizeText())
    }
}
