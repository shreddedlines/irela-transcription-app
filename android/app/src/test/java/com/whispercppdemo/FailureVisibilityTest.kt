package com.whispercppdemo

import com.whispercppdemo.jobs.FailureReason
import com.whispercppdemo.jobs.JobState
import com.whispercppdemo.jobs.JobStore
import com.whispercppdemo.jobs.attempts
import com.whispercppdemo.jobs.failureMessageFor
import com.whispercppdemo.transcribe.TranscriptionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A failed or cancelled job must be visible to the user.
 *
 * The bug this closes: History listed only `TranscriptRecord`s, which exist
 * only for successful completions -- so a share that failed while the user was
 * back in WhatsApp left no trace anywhere in the app. These tests pin the
 * projection History now reads, and pin that Retry is offered exactly when it
 * would actually work.
 */
class FailureVisibilityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: JobStore
    private var clock = 1_000L

    @Before
    fun setUp() {
        store = JobStore(tmp.newFolder("jobs"))
        clock = 1_000L
    }

    private fun staged(name: String): File =
        File(tmp.root, name).apply { writeBytes(ByteArray(64)) }

    private fun job(
        name: String,
        state: JobState,
        reason: String? = null,
        stagedPath: String? = null,
        at: Long = clock++
    ) = store.update(
        store.create("content://x/$name", name, at).copy(
            state = state, failureReason = reason, stagedPath = stagedPath, updatedAt = at
        )
    )

    private fun list() = store.attempts(describe = { failureMessageFor(it) })

    // ---- what appears --------------------------------------------------

    @Test
    fun `failed and cancelled jobs are listed`() {
        job("a.ogg", JobState.FAILED, FailureReason.RETRIES_EXHAUSTED)
        job("b.ogg", JobState.CANCELLED)
        assertEquals(setOf("a.ogg", "b.ogg"), list().map { it.displayName }.toSet())
    }

    @Test
    fun `completed jobs are not listed -- their transcript already is`() {
        job("done.ogg", JobState.COMPLETED)
        assertTrue(list().isEmpty())
    }

    @Test
    fun `jobs still in flight are not listed`() {
        job("q.ogg", JobState.QUEUED)
        job("t.ogg", JobState.TRANSCRIBING)
        job("r.ogg", JobState.RETRYING)
        assertTrue(list().isEmpty())
    }

    @Test
    fun `newest first, matching the transcript list`() {
        job("old.ogg", JobState.FAILED, at = 100)
        job("new.ogg", JobState.FAILED, at = 900)
        assertEquals(listOf("new.ogg", "old.ogg"), list().map { it.displayName })
    }

    @Test
    fun `a blank display name still shows something`() {
        store.update(store.create("content://x", "", clock).copy(state = JobState.FAILED))
        assertEquals("Shared audio", list().single().displayName)
    }

    // ---- what it says --------------------------------------------------

    @Test
    fun `a failure carries the same wording the failed screen uses`() {
        job("a.ogg", JobState.FAILED, FailureReason.AUDIO_REJECTED)
        assertEquals(failureMessageFor(FailureReason.AUDIO_REJECTED), list().single().reason)
    }

    @Test
    fun `cancellation is not given a failure reason`() {
        job("b.ogg", JobState.CANCELLED, reason = FailureReason.INTERRUPTED)
        val a = list().single()
        assertTrue(a.cancelled)
        // The user did this on purpose; explaining it as an error would lie.
        assertNull(a.reason)
    }

    // ---- when retry is offered ------------------------------------------

    @Test
    fun `retry is offered only when the staged audio survived`() {
        val f = staged("kept.ogg")
        job("kept.ogg", JobState.FAILED, FailureReason.RETRIES_EXHAUSTED, f.absolutePath)
        job("gone.ogg", JobState.FAILED, FailureReason.RETRIES_EXHAUSTED,
            File(tmp.root, "missing.ogg").absolutePath)
        job("never.ogg", JobState.FAILED, FailureReason.RETRIES_EXHAUSTED, null)

        val byName = list().associateBy { it.displayName }
        assertTrue(byName.getValue("kept.ogg").retryable)
        assertFalse("a pruned copy must not offer retry",
                    byName.getValue("gone.ogg").retryable)
        assertFalse("a job that never staged must not offer retry",
                    byName.getValue("never.ogg").retryable)
    }

    @Test
    fun `a cancelled job with its audio can be retried too`() {
        val f = staged("c.ogg")
        job("c.ogg", JobState.CANCELLED, stagedPath = f.absolutePath)
        assertTrue(list().single().retryable)
    }

    // ---- the state the UI actually binds to -----------------------------

    @Test
    fun `terminal states default to offering no retry`() {
        // Any path that publishes a failure without a job behind it must
        // produce a screen with no retry button, not a button that cannot work.
        val f = TranscriptionState.Failed("x", "boom")
        assertNull(f.jobId)
        assertFalse(f.retryable)
        val c = TranscriptionState.Cancelled("x")
        assertNull(c.jobId)
        assertFalse(c.retryable)
    }

    @Test
    fun `terminal states are not treated as running`() {
        assertFalse(TranscriptionState.Failed("x", "boom", "j1", true).isRunning)
        assertFalse(TranscriptionState.Cancelled("x", "j1", true).isRunning)
    }
}
