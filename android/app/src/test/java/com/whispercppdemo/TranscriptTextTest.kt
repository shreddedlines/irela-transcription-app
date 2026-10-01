package com.whispercppdemo

import com.whispercppdemo.history.TranscriptRecord
import com.whispercppdemo.history.TranscriptSource
import com.whispercppdemo.ui.common.matchesQuery
import com.whispercppdemo.ui.common.sanitizeFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTextTest {

    private fun rec(
        name: String = "Recorded audio",
        text: String = "hello world",
        id: String = "a"
    ) = TranscriptRecord(
        id = id,
        displayName = name,
        createdAt = 1_000L,
        text = text,
        source = TranscriptSource.RECORDING,
        durationMs = 1_000L
    )

    // ---- search ----------------------------------------------------------

    @Test
    fun `an empty query matches everything so History returns to normal`() {
        assertTrue(matchesQuery(rec(), ""))
        assertTrue(matchesQuery(rec(), "   "))
    }

    @Test
    fun `search is case-insensitive`() {
        val r = rec(text = "Aap Har Bhi To Thoda Correlation")
        assertTrue(matchesQuery(r, "correlation"))
        assertTrue(matchesQuery(r, "CORRELATION"))
        assertTrue(matchesQuery(r, "CoRrElAtIoN"))
    }

    @Test
    fun `search matches the transcript body, not just the title`() {
        val r = rec(name = "Recorded audio", text = "jo companies hain vo scale up kar rahi hain")
        assertTrue("body match", matchesQuery(r, "companies"))
        assertTrue("title match", matchesQuery(r, "recorded"))
        assertFalse(matchesQuery(r, "elephant"))
    }

    @Test
    fun `Devanagari search works`() {
        val r = rec(text = "आज हम स्थानीय ट्रांसक्रिप्शन की बात करेंगे")
        assertTrue(matchesQuery(r, "स्थानीय"))
        assertTrue(matchesQuery(r, "ट्रांसक्रिप्शन"))
        assertFalse(matchesQuery(r, "कुत्ता"))
    }

    @Test
    fun `Devanagari matches across composed and decomposed spellings`() {
        // Same word, different Unicode normalisation on each side.
        val composed = "हिन्दी"          // हिन्दी
        val decomposed = java.text.Normalizer.normalize(
            composed, java.text.Normalizer.Form.NFD
        )
        assertTrue(matchesQuery(rec(text = composed), decomposed))
        assertTrue(matchesQuery(rec(text = decomposed), composed))
    }

    @Test
    fun `Hinglish Latin search works`() {
        val r = rec(text = "Aap thoda sa AI se study karke aao pahle")
        assertTrue(matchesQuery(r, "thoda"))
        assertTrue(matchesQuery(r, "AI se"))
        assertFalse(matchesQuery(r, "zebra"))
    }

    @Test
    fun `search does not modify the record`() {
        val r = rec(text = "Original TEXT")
        matchesQuery(r, "text")
        assertEquals("Original TEXT", r.text)
        assertEquals("Recorded audio", r.displayName)
    }

    @Test
    fun `an edited transcript is found by its new content`() {
        val edited = rec(text = "hello world").copy(text = "completely different wording")
        assertTrue(matchesQuery(edited, "different"))
        assertFalse(matchesQuery(edited, "hello"))
    }

    // ---- share file names ------------------------------------------------

    @Test
    fun `path separators and reserved characters are replaced`() {
        assertEquals("a_b_c", sanitizeFileName("a/b\\c"))
        assertEquals("no_colon", sanitizeFileName("no:colon"))
        assertEquals("q_mark", sanitizeFileName("q?mark"))
        assertEquals("star", sanitizeFileName("star*"))
    }

    @Test
    fun `directory traversal cannot survive sanitisation`() {
        val out = sanitizeFileName("../../etc/passwd")
        assertFalse(out.contains("/"))
        assertFalse(out.contains(".."))
    }

    @Test
    fun `spaces and hyphens become underscores`() {
        assertEquals("my_voice_note", sanitizeFileName("my voice note"))
        assertEquals("a_b", sanitizeFileName("a-b"))
    }

    @Test
    fun `a title with no usable characters falls back`() {
        assertEquals("transcript", sanitizeFileName(""))
        assertEquals("transcript", sanitizeFileName("   "))
        assertEquals("transcript", sanitizeFileName("..."))
    }

    @Test
    fun `long titles are truncated`() {
        val out = sanitizeFileName("x".repeat(500))
        assertTrue("length ${out.length}", out.length <= 60)
    }

    @Test
    fun `devanagari titles survive sanitisation`() {
        assertEquals("हिन्दी_नोट", sanitizeFileName("हिन्दी नोट"))
    }
}
