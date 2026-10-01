package com.whispercppdemo

import com.whispercppdemo.ui.common.formatEta
import com.whispercppdemo.ui.common.transcriptionEtaMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The remaining-time estimate.
 *
 * `chunk` is 1-based and names the chunk in flight, so `chunk - 1` are done.
 * Two rules matter:
 *
 *  - it refuses to guess before a chunk has actually completed, so the UI
 *    keeps showing "Processing audio..." rather than a fabricated number;
 *  - it falls between chunk boundaries. Averaging against a moving "now"
 *    instead would make the number tick UPWARD whenever a chunk ran long.
 */
class TranscriptionEtaTest {

    @Test
    fun `no estimate before the first chunk completes`() {
        // chunk 1 is in flight; nothing has finished yet.
        assertNull(transcriptionEtaMs(chunk = 1, chunks = 8, 5_000, 0))
    }

    @Test
    fun `no estimate from the initial zero state`() {
        assertNull(transcriptionEtaMs(chunk = 0, chunks = 0, 0, 0))
    }

    @Test
    fun `no estimate when the elapsed time is unknown`() {
        assertNull(transcriptionEtaMs(chunk = 3, chunks = 8, elapsedAtChunkStartMs = 0, sinceChunkStartMs = 0))
    }

    @Test
    fun `estimate uses the measured average of completed chunks`() {
        // 2 done in 10 s -> 5 s each; 6 remain -> 30 s.
        assertEquals(30_000L, transcriptionEtaMs(chunk = 3, chunks = 8, 10_000, 0))
    }

    @Test
    fun `estimate falls between chunk boundaries`() {
        val a = transcriptionEtaMs(chunk = 3, chunks = 8, 10_000, 0)!!
        val b = transcriptionEtaMs(chunk = 3, chunks = 8, 10_000, 2_000)!!
        val c = transcriptionEtaMs(chunk = 3, chunks = 8, 10_000, 4_000)!!
        assertEquals(30_000L, a)
        assertEquals(28_000L, b)
        assertEquals(26_000L, c)
        assertTrue("must decrease", a > b && b > c)
    }

    @Test
    fun `estimate re-bases when a slower chunk completes`() {
        // 1 done in 5 s -> 3 remain of 4 -> 15 s.
        assertEquals(15_000L, transcriptionEtaMs(chunk = 2, chunks = 4, 5_000, 0))
        // 2 done but 20 s spent -> 10 s each -> 2 remain -> 20 s.
        assertEquals(20_000L, transcriptionEtaMs(chunk = 3, chunks = 4, 20_000, 0))
    }

    @Test
    fun `estimate never goes negative when a chunk overruns`() {
        // Projection was 5 s but the chunk has already burned 100 s.
        assertEquals(0L, transcriptionEtaMs(chunk = 4, chunks = 4, 15_000, 100_000)!!)
    }

    @Test
    fun `the final chunk reports nothing remaining`() {
        // 3 of 3 done means no chunks are left to wait for.
        assertEquals(0L, transcriptionEtaMs(chunk = 4, chunks = 3, 30_000, 0))
    }

    @Test
    fun `a single chunk job never produces an estimate`() {
        // Only one chunk exists, so it is always in flight and never measured.
        assertNull(transcriptionEtaMs(chunk = 1, chunks = 1, 3_000, 500))
    }

    @Test
    fun `formatting is explicitly approximate`() {
        assertEquals("About 30 seconds remaining", formatEta(30_000))
        assertEquals("About 25 seconds remaining", formatEta(24_100))
    }

    @Test
    fun `short remainders read as less than five seconds`() {
        assertEquals("Less than 5 seconds remaining", formatEta(0))
        assertEquals("Less than 5 seconds remaining", formatEta(2_400))
    }

    @Test
    fun `estimates stay sensible for very long inputs`() {
        // 60 minutes of audio at 30 s chunks is 120 chunks. 10 done in 10 min
        // -> 60 s each -> 110 remaining -> 110 min.
        val eta = transcriptionEtaMs(chunk = 11, chunks = 120, 10L * 60 * 1000, 0)!!
        assertEquals(110L * 60 * 1000, eta)
        assertEquals("About 110 minutes remaining", formatEta(eta))
    }

    @Test
    fun `the estimate crosses cleanly from minutes to seconds`() {
        assertEquals("About 2 minutes remaining", formatEta(61_000))
        assertEquals("About a minute remaining", formatEta(60_000))
        assertEquals("About 59 seconds remaining", formatEta(59_000))
        assertEquals("About 20 seconds remaining", formatEta(20_000))
        assertEquals("Less than 5 seconds remaining", formatEta(1_000))
    }

    @Test
    fun `formatting switches to minutes`() {
        assertEquals("About a minute remaining", formatEta(60_000))
        assertEquals("About 2 minutes remaining", formatEta(90_000))
        assertEquals("About 5 minutes remaining", formatEta(4L * 60 * 1000 + 30_000))
    }
}
