package com.whispercppdemo

import com.whispercppdemo.jobs.FailureReason
import com.whispercppdemo.jobs.JobRunner
import com.whispercppdemo.jobs.JobState
import com.whispercppdemo.jobs.JobStore
import com.whispercppdemo.jobs.PreparedAudio
import com.whispercppdemo.transcribe.provider.FailureKind
import com.whispercppdemo.transcribe.provider.TranscriptionOutcome
import com.whispercppdemo.transcribe.provider.TranscriptionProvider
import com.whispercppdemo.transcribe.provider.TranscriptionRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The user-initiated retry path.
 *
 * The thing that makes this non-trivial: a share URI cannot be re-read after
 * the receiving task ends, so Retry can only work against our own staged copy.
 * These tests pin that the copy is retained for retryable jobs, that Retry
 * produces a real second attempt, and that the button is never offered when it
 * could not actually work.
 */
class RetryPathTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: JobStore
    private var clock = 1_000L

    @Before
    fun setUp() {
        store = JobStore(tmp.newFolder("jobs"))
        clock = 1_000L
    }

    private class Scripted(private val outcomes: List<TranscriptionOutcome>) :
        TranscriptionProvider {
        override val id = "mock"
        override val displayName = "Mock"
        var calls = 0
        override suspend fun transcribe(
            request: TranscriptionRequest, onProgress: (Float) -> Unit
        ): TranscriptionOutcome {
            onProgress(1f)
            return outcomes[minOf(calls++, outcomes.size - 1)]
        }
    }

    private fun staged(name: String = "clip.ogg"): File =
        File(tmp.root, name).apply { writeBytes(ByteArray(2048) { 4 }) }

    private fun runner(p: TranscriptionProvider, f: File, tid: String = "t-1") =
        JobRunner(
            store = store, provider = p,
            prepare = { PreparedAudio(f, "audio/ogg") },
            persistTranscript = { _, _, _ -> tid },
            now = { clock }, sleep = { }
        )

    // ---- staged copy retention -------------------------------------------

    @Test
    fun `failed job records where its audio was staged`() = runBlocking {
        val f = staged()
        val p = Scripted(listOf(
            TranscriptionOutcome.Failure(FailureKind.TERMINAL_INPUT, "no", 422, "mock")))
        val r = runner(p, f)
        val job = r.run(r.submit("content://share/1", "clip.ogg"))
        assertEquals(JobState.FAILED, job.state)
        assertEquals(f.absolutePath, job.stagedPath)
    }

    @Test
    fun `retry is offered only when the audio is actually still there`() = runBlocking {
        val f = staged()
        val p = Scripted(listOf(
            TranscriptionOutcome.Failure(FailureKind.TERMINAL_INPUT, "no", 422, "mock")))
        val r = runner(p, f)
        val job = r.run(r.submit("content://share/1", "clip.ogg"))

        assertTrue("audio present -> retry offered", r.canRetry(job.id))
        f.delete()
        assertFalse("audio gone -> retry must NOT be offered", r.canRetry(job.id))
        assertNull("and retry must refuse rather than fail later", r.retry(job.id))
    }

    @Test
    fun `completed jobs are never offered a retry`() = runBlocking {
        val f = staged()
        val r = runner(Scripted(listOf(TranscriptionOutcome.Success("ok", "mock"))), f)
        val job = r.run(r.submit("content://share/1", "clip.ogg"))
        assertEquals(JobState.COMPLETED, job.state)
        assertFalse(r.canRetry(job.id))
    }

    @Test
    fun `a running job is not retryable`() {
        val j = store.create("content://x", "clip.ogg", clock)
        val r = runner(Scripted(listOf(TranscriptionOutcome.Success("ok", "mock"))), staged())
        assertFalse(r.canRetry(j.id))
    }

    // ---- the retry itself -------------------------------------------------

    @Test
    fun `retry creates a new job and reaches the provider again`() = runBlocking {
        val f = staged()
        val failing = Scripted(listOf(
            TranscriptionOutcome.Failure(FailureKind.TERMINAL_INPUT, "no", 422, "mock")))
        val r1 = runner(failing, f)
        val failed = r1.run(r1.submit("content://share/1", "clip.ogg"))
        assertEquals(JobState.FAILED, failed.state)

        clock += 10
        val retried = r1.retry(failed.id)
        assertNotNull(retried)
        assertFalse("a retry is a NEW job", retried!!.id == failed.id)
        assertEquals(JobState.QUEUED, retried.state)
        // It re-sends our copy, not the dead share URI.
        assertTrue(retried.sourceUri.startsWith("file://"))

        val ok = Scripted(listOf(TranscriptionOutcome.Success("second time", "mock")))
        val done = runner(ok, f, "t-retry").run(retried)
        assertEquals(JobState.COMPLETED, done.state)
        assertEquals(1, ok.calls)
        assertEquals("t-retry", done.transcriptId)
    }

    @Test
    fun `the original failure stays in the record after a retry`() = runBlocking {
        val f = staged()
        val r = runner(Scripted(listOf(
            TranscriptionOutcome.Failure(FailureKind.TERMINAL_INPUT, "no", 422, "mock"))), f)
        val failed = r.run(r.submit("content://share/1", "clip.ogg"))
        clock += 10
        r.retry(failed.id)

        // History must show what actually happened: one failure, one retry.
        val original = store.get(failed.id)!!
        assertEquals(JobState.FAILED, original.state)
        assertEquals(FailureReason.AUDIO_REJECTED, original.failureReason)
        assertEquals(2, store.all().size)
    }

    @Test
    fun `retry is not blocked by content-hash dedupe`() = runBlocking {
        // The same bytes were already transcribed -- but that attempt FAILED,
        // so dedupe must not short-circuit the deliberate retry.
        val f = staged()
        val r = runner(Scripted(listOf(
            TranscriptionOutcome.Failure(FailureKind.TERMINAL_INPUT, "no", 422, "mock"))), f)
        val failed = r.run(r.submit("content://share/1", "clip.ogg"))
        clock += 10

        val ok = Scripted(listOf(TranscriptionOutcome.Success("now fine", "mock")))
        val retried = r.retry(failed.id)!!
        val done = runner(ok, f, "t-2").run(retried)
        assertEquals(JobState.COMPLETED, done.state)
        assertEquals("retry must reach the provider", 1, ok.calls)
    }

    @Test
    fun `cancelled jobs keep their audio so they can be retried too`() = runBlocking {
        val f = staged()
        val r = runner(Scripted(listOf(TranscriptionOutcome.Success("ok", "mock"))), f)
        val job = r.submit("content://share/1", "clip.ogg")
        // Give it a staged path the way a real run would.
        store.update(store.get(job.id)!!.copy(stagedPath = f.absolutePath))
        r.cancel(job.id)
        assertEquals(JobState.CANCELLED, store.get(job.id)!!.state)
        assertTrue(r.canRetry(job.id))
    }
}
