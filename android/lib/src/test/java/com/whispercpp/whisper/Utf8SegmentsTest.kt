package com.whispercpp.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression fixtures for the native crash
 *
 *     JNI DETECTED ERROR IN APPLICATION: input is not valid Modified UTF-8:
 *     illegal continuation byte 0x20
 *
 * The bytes below are exactly what whisper's segment API can hand back: text
 * cut at token boundaries, so multi-byte characters straddle segments. Each
 * case asserts the decoder never throws, never drops a segment, keeps valid
 * text byte-for-byte identical, and stitches split characters back together.
 */
class Utf8SegmentsTest {

    private val R = Utf8Segments.REPLACEMENT
    private fun b(s: String) = s.toByteArray(Charsets.UTF_8)
    private fun decode(vararg parts: ByteArray) = Utf8Segments.decode(parts.toList())

    // ---- valid UTF-8 is unchanged ------------------------------------------

    @Test
    fun `ascii is unchanged`() {
        val r = decode(b("Hello"), b(" world"))
        assertEquals(listOf("Hello", " world"), r.segments)
        assertEquals(0, r.replacements); assertEquals(0, r.carriedBoundaries)
    }

    @Test
    fun `whole hindi segments are unchanged`() {
        val r = decode(b("नमस्ते"), b(" दुनिया"))
        assertEquals(listOf("नमस्ते", " दुनिया"), r.segments)
        assertEquals(0, r.replacements)
    }

    @Test
    fun `mixed hindi and english is unchanged`() {
        val text = "जो companies हैं वह अपनी jobs डालती हैं"
        assertEquals(listOf(text), decode(b(text)).segments)
    }

    @Test
    fun `four byte characters decode correctly -- NewStringUTF rejected these too`() {
        val r = decode(b("ok 🎙️ 😀"))
        assertEquals(listOf("ok 🎙️ 😀"), r.segments)
        assertEquals(0, r.replacements)
    }

    @Test
    fun `empty segments and no segments are fine`() {
        assertEquals(listOf("", "a", ""), decode(b(""), b("a"), b("")).segments)
        assertEquals(emptyList<String>(), Utf8Segments.decode(emptyList()).segments)
    }

    // ---- the boundary case: a character split across segments -------------

    @Test
    fun `a devanagari character split at every byte offset is stitched exactly`() {
        val text = "नमस्ते दुनिया"
        val bytes = b(text)
        for (cut in 1 until bytes.size) {
            val r = decode(bytes.copyOfRange(0, cut), bytes.copyOfRange(cut, bytes.size))
            assertEquals("cut at $cut", text, r.segments.joinToString(""))
            assertEquals("cut at $cut: no replacement", 0, r.replacements)
            assertFalse("cut at $cut", r.segments.joinToString("").contains(R))
        }
    }

    @Test
    fun `split across three segments is stitched`() {
        val ka = b("क")                          // E0 A4 95
        val r = decode(byteArrayOf(ka[0]), byteArrayOf(ka[1]), byteArrayOf(ka[2]), b("र"))
        assertEquals("कर", r.segments.joinToString(""))
        assertEquals(0, r.replacements)
    }

    @Test
    fun `a stitched boundary is counted`() {
        val bytes = b("हाँ")
        val r = decode(bytes.copyOfRange(0, 2), bytes.copyOfRange(2, bytes.size))
        assertEquals(1, r.carriedBoundaries)
    }

    // ---- genuinely malformed bytes degrade, never abort -------------------

    @Test
    fun `the exact device error -- lead byte followed by 0x20 -- is replaced, rest kept`() {
        // A three-byte lead (E0 A4) interrupted by a space: the byte sequence
        // NewStringUTF reported as "illegal continuation byte 0x20".
        val bad = b("हाँ") .copyOfRange(0, 2) + b(" okay")
        val r = decode(bad, b(" आगे"))
        val joined = r.segments.joinToString("")
        assertTrue(joined.startsWith(R.toString()))
        assertTrue("text after the bad byte survives", joined.contains(" okay"))
        assertTrue("next segment survives", joined.endsWith(" आगे"))
        assertEquals(1, r.replacements)
    }

    @Test
    fun `an incomplete character at the very end becomes one replacement`() {
        val bytes = b("ठीक है")
        val r = decode(b("अच्छा "), bytes.copyOfRange(0, bytes.size - 1))
        assertEquals("अच्छा ", r.segments[0])
        assertTrue(r.segments[1].startsWith("ठीक ह"))
        assertTrue(r.segments[1].endsWith(R.toString()))
        assertEquals(1, r.replacements)
    }

    @Test
    fun `carried bytes that the next segment does not continue are replaced, not dropped`() {
        val partial = b("क").copyOfRange(0, 2)         // lead + one continuation
        val r = decode(b("ab") + partial, b("cd"))
        assertEquals("ab", r.segments[0])
        assertEquals("${R}cd", r.segments[1])
        assertEquals(1, r.replacements)
    }

    @Test
    fun `stray continuation bytes are replaced and surrounding text kept`() {
        val r = decode(b("x") + byteArrayOf(0x80.toByte(), 0xBF.toByte()) + b("y"))
        val s = r.segments.single()
        assertTrue(s.startsWith("x")); assertTrue(s.endsWith("y"))
        assertTrue(s.contains(R))
    }

    @Test
    fun `invalid lead bytes are replaced`() {
        val r = decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + b("ok"))
        assertTrue(r.segments.single().endsWith("ok"))
        assertTrue(r.replacements >= 1)
    }

    @Test
    fun `overlong and surrogate encodings are rejected, not decoded`() {
        val overlong = byteArrayOf(0xC0.toByte(), 0xAF.toByte())          // overlong '/'
        val surrogate = byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte())
        val r = decode(overlong + b("a"), surrogate + b("b"))
        assertTrue(r.segments.joinToString("").endsWith("b"))
        assertFalse(r.segments.joinToString("").contains("/"))
        assertTrue(r.replacements >= 2)
    }

    @Test
    fun `a fuzzed stream of random bytes never throws and keeps segment count`() {
        val rnd = java.util.Random(20260913)
        repeat(500) {
            val parts = List(1 + rnd.nextInt(6)) {
                ByteArray(rnd.nextInt(24)).also { rnd.nextBytes(it) }
            }
            val r = Utf8Segments.decode(parts)
            assertEquals(parts.size, r.segments.size)
        }
    }

    @Test
    fun `a long hindi sentence split at random token boundaries round-trips`() {
        val text = "मैंने बोला कि KYC complete करने के बाद ही invoice generate होगा। " +
            "लक्ष्मी और नंदिनी 14 March को बेंगलुरु आ रही हैं।"
        val bytes = b(text)
        val rnd = java.util.Random(7)
        repeat(200) {
            val cuts = (1 until bytes.size).shuffled(rnd).take(rnd.nextInt(8)).sorted()
            val bounds = listOf(0) + cuts + listOf(bytes.size)
            val parts = bounds.zipWithNext { a, c -> bytes.copyOfRange(a, c) }
            val r = Utf8Segments.decode(parts)
            assertEquals(text, r.segments.joinToString(""))
            assertEquals(0, r.replacements)
        }
    }
}
