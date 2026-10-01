package com.whispercpp.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for whisper segment concatenation.
 *
 * Before the fix, segments were appended with no separator. whisper segments
 * usually carry their own leading space, but when one does not the boundary
 * fuses two words into a single bogus token -- observed on device as
 * "koAapko" (from "...us chiz ko" + "Aapko doonga") and "par.Aap" (from
 * "...us par." + "Aap thoda"). Fused tokens corrupt word counts and can never
 * be matched by the seam de-duplicator.
 */
class SegmentJoinTest {

    // ---- the reported failures ------------------------------------------

    @Test
    fun `adjacent segments without whitespace do not fuse`() {
        val joined = joinSegments(listOf("...us chiz ko", "Aapko doonga basically"))
        assertFalse("segments fused into one token", joined.contains("koAapko"))
        assertEquals("...us chiz ko Aapko doonga basically", joined)
    }

    @Test
    fun `punctuation boundary without whitespace does not fuse`() {
        val joined = joinSegments(listOf("Thik hai to us par.", "Aap thoda"))
        assertFalse("segments fused into one token", joined.contains("par.Aap"))
        assertEquals("Thik hai to us par. Aap thoda", joined)
    }

    @Test
    fun `fused boundary would break word tokenisation`() {
        // The concrete downstream damage: a fused boundary yields one bogus
        // token instead of two real words.
        val fused = listOf("Thik hai to us par.", "Aap thoda")
            .joinToString("")                       // old behaviour
            .trim().split(Regex("\\s+"))
        val joined = joinSegments(listOf("Thik hai to us par.", "Aap thoda"))
            .trim().split(Regex("\\s+"))
        assertTrue("old behaviour should produce the fused token", fused.contains("par.Aap"))
        assertFalse(joined.contains("par.Aap"))
        assertEquals(listOf("Thik", "hai", "to", "us", "par.", "Aap", "thoda"), joined)
    }

    // ---- existing behaviour must be preserved ---------------------------

    @Test
    fun `leading space supplied by the segment is not doubled`() {
        val joined = joinSegments(listOf(" Jaise ki jo", " obvious log hain"))
        assertEquals(" Jaise ki jo obvious log hain", joined)
    }

    @Test
    fun `trailing space on the accumulator is not doubled`() {
        val joined = joinSegments(listOf("Jaise ki jo ", "obvious log hain"))
        assertEquals("Jaise ki jo obvious log hain", joined)
    }

    @Test
    fun `segment text is never trimmed or altered`() {
        // Interior spacing, punctuation and case must survive verbatim.
        val segments = listOf(" Hello,  world.", " It's  fine — really?")
        assertEquals(" Hello,  world. It's  fine — really?", joinSegments(segments))
    }

    @Test
    fun `single segment is returned unchanged`() {
        assertEquals(" Jaise ki jo", joinSegments(listOf(" Jaise ki jo")))
        assertEquals("Jaise ki jo", joinSegments(listOf("Jaise ki jo")))
    }

    @Test
    fun `no separator is prepended before the first segment`() {
        assertEquals("Aapko", joinSegments(listOf("Aapko")))
    }

    // ---- edge cases -----------------------------------------------------

    @Test
    fun `empty list yields empty string`() {
        assertEquals("", joinSegments(emptyList()))
    }

    @Test
    fun `an empty segment neither adds nor suppresses a separator`() {
        // "a" and "b" still need separating; the empty segment contributes
        // nothing of its own and must not double the space either.
        assertEquals("a b", joinSegments(listOf("a", "", "b")))
        assertEquals("a b", joinSegments(listOf("a ", "", "b")))
        assertEquals("a b", joinSegments(listOf("a", "", " b")))
        assertEquals("", joinSegments(listOf("", "")))
    }

    @Test
    fun `newline and tab count as whitespace`() {
        assertEquals("a\nb", joinSegments(listOf("a\n", "b")))
        assertEquals("a\tb", joinSegments(listOf("a", "\tb")))
    }

    @Test
    fun `many segments join pairwise`() {
        assertEquals(
            "one two three four",
            joinSegments(listOf("one", " two", "three", " four"))
        )
    }

    // ---- the predicate itself -------------------------------------------

    @Test
    fun `needsSeparator is true only when neither side supplies whitespace`() {
        assertTrue(needsSeparator("ko", "Aapko"))
        assertFalse(needsSeparator("ko ", "Aapko"))
        assertFalse(needsSeparator("ko", " Aapko"))
        assertFalse(needsSeparator("ko ", " Aapko"))
        assertFalse("nothing accumulated yet", needsSeparator("", "Aapko"))
        assertFalse("empty segment", needsSeparator("ko", ""))
    }
}
