package com.whispercppdemo

import com.whispercppdemo.history.TranscriptRecord
import com.whispercppdemo.history.TranscriptSource
import com.whispercppdemo.ui.common.canStartJob
import com.whispercppdemo.ui.common.awaitNewRecordId
import com.whispercppdemo.ui.common.newlyAddedRecordId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Regression tests for the two QA fixes that are decidable without a device:
 * the single-job rule, and identifying a finished job's record by identity
 * rather than by transcript text.
 */
class JobRoutingTest {

    private fun rec(id: String, text: String, createdAt: Long = 0L) =
        TranscriptRecord(id, "n", createdAt, text, TranscriptSource.RECORDING, null)

    // ---- single job at a time --------------------------------------------

    @Test
    fun `a job may start only when the model is ready and nothing is running`() {
        assertTrue(canStartJob(modelReady = true, isRecording = false, jobRunning = false))
    }

    @Test
    fun `home actions are disabled while the model is still loading`() {
        // canStartJob gates the BUTTONS only. The entry points deliberately do
        // NOT consult model readiness: a share intent always arrives during
        // cold start, and gating on readiness silently dropped those imports.
        assertFalse(canStartJob(modelReady = false, isRecording = false, jobRunning = false))
    }

    @Test
    fun `no job may start while recording`() {
        assertFalse(canStartJob(modelReady = true, isRecording = true, jobRunning = false))
    }

    @Test
    fun `no job may start while another transcription is running`() {
        // jobRunning covers BOTH sources: the service and the recording path
        // publish into the same store.
        assertFalse(canStartJob(modelReady = true, isRecording = false, jobRunning = true))
    }

    // ---- routing to the exact completed record ---------------------------

    @Test
    fun `identifies the record added since the job started`() {
        val before = setOf("a", "b")
        val after = listOf(rec("new", "hello"), rec("a", "x"), rec("b", "y"))
        assertEquals("new", newlyAddedRecordId(before, after))
    }

    @Test
    fun `two identical transcripts resolve to the NEW record, not the older one`() {
        // The exact case that broke text matching: same words, different runs.
        val older = rec("older", "Kya karna hai aapne zindagi mein?", createdAt = 100L)
        val newer = rec("newer", "Kya karna hai aapne zindagi mein?", createdAt = 200L)
        val id = newlyAddedRecordId(setOf(older.id), listOf(newer, older))
        assertEquals("newer", id)
    }

    @Test
    fun `returns null until the new record has actually loaded`() {
        // Done fires before refreshHistory() completes; nothing must route yet.
        val before = setOf("a", "b")
        val stillStale = listOf(rec("a", "x"), rec("b", "y"))
        assertNull(newlyAddedRecordId(before, stillStale))
    }

    @Test
    fun `first ever record is found when history started empty`() {
        assertEquals("only", newlyAddedRecordId(emptySet(), listOf(rec("only", "t"))))
    }

    @Test
    fun `empty history yields null`() {
        assertNull(newlyAddedRecordId(setOf("a"), emptyList()))
    }

    // ---- the persist race: Done is published BEFORE the record is written --

    @Test
    fun `waits for the record when Done arrives before the write lands`() = runBlocking {
        // Reproduces the terminal-screen defect: the first reloads still return
        // the pre-job list because TranscriptionService sets Done and only then
        // persists. Resolving once against that stale list returned null and
        // stranded the user on Processing.
        val before = setOf("a", "b")
        val stale = listOf(rec("a", "x"), rec("b", "y"))
        val settled = listOf(rec("fresh", "new transcript")) + stale
        var calls = 0
        val id = awaitNewRecordId(
            knownIdsAtStart = before,
            reload = { calls++; if (calls < 3) stale else settled },
            attempts = 10,
            delayMs = 1
        )
        assertEquals("fresh", id)
        assertTrue("should have retried past the stale reads", calls >= 3)
    }

    @Test
    fun `returns immediately when the record is already present`() = runBlocking {
        var calls = 0
        val id = awaitNewRecordId(
            knownIdsAtStart = setOf("a"),
            reload = { calls++; listOf(rec("new", "t"), rec("a", "x")) },
            attempts = 10,
            delayMs = 1
        )
        assertEquals("new", id)
        assertEquals(1, calls)
    }

    @Test
    fun `gives up rather than spinning forever`() = runBlocking {
        var calls = 0
        val id = awaitNewRecordId(
            knownIdsAtStart = setOf("a"),
            reload = { calls++; listOf(rec("a", "x")) },
            attempts = 4,
            delayMs = 1
        )
        assertNull(id)
        assertEquals(4, calls)
    }
}
