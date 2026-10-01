package com.whispercppdemo

import com.whispercppdemo.jobs.FailureReason
import com.whispercppdemo.jobs.JobRecord
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Every route to COMPLETED must announce itself exactly once.
 *
 * The device bug: the dedupe short-circuit reached COMPLETED without going
 * through the transcription path that published the result, so the job was
 * finished on disk while the Processing screen span forever. The fix moves
 * publication onto the state transition, which every path shares -- so this
 * suite enumerates the paths and asserts each one emits a single COMPLETED
 * transition carrying a usable transcript id.
 */
class CompletionPublishTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: JobStore
    private var clock = 1_000L

    /** Every transition the service would mirror into the UI. */
    private val transitions = mutableListOf<JobRecord>()

    @Before
    fun setUp() {
        store = JobStore(tmp.newFolder("jobs"))
        clock = 1_000L
        transitions.clear()
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

    private fun audio(name: String, fill: Byte = 7): File =
        File(tmp.root, name).apply { writeBytes(ByteArray(4096) { fill }) }

    private var persisted = 0

    private fun runner(p: TranscriptionProvider, f: File) = JobRunner(
        store = store, provider = p,
        prepare = { PreparedAudio(f, "audio/ogg") },
        persistTranscript = { _, _, _ -> "transcript-${++persisted}" },
        onTransition = { transitions += it },
        now = { clock }, sleep = { }
    )

    private fun completions() = transitions.filter { it.state == JobState.COMPLETED }

    private fun assertPublishedExactlyOnce() {
        val done = completions()
        assertEquals("exactly one COMPLETED transition", 1, done.size)
        assertTrue("a completion the UI cannot render is a stuck spinner",
                   !done.single().transcriptId.isNullOrBlank())
    }

    // ---- 1. normal local transcription ----------------------------------

    @Test
    fun `normal transcription publishes completion once`() = runBlocking {
        val r = runner(Scripted(listOf(TranscriptionOutcome.Success("hello there", "apex-local"))),
                       audio("a.ogg"))
        val job = r.run(r.submit("content://x/1", "a.ogg"))
        assertEquals(JobState.COMPLETED, job.state)
        assertPublishedExactlyOnce()
    }

    // ---- 2. duplicate / content-hash reuse -------------------------------

    @Test
    fun `content-hash reuse publishes completion once`() = runBlocking {
        val f = audio("same.ogg")
        val first = Scripted(listOf(TranscriptionOutcome.Success("first pass", "apex-local")))
        runner(first, f).run(runner(first, f).submit("content://x/1", "same.ogg"))
        transitions.clear()

        // Identical bytes, new submission: must NOT reach the provider again.
        val second = Scripted(listOf(TranscriptionOutcome.Success("must not run", "apex-local")))
        val r2 = runner(second, f)
        clock += 10
        val job = r2.run(r2.submit("content://x/2", "same.ogg"))

        assertEquals(JobState.COMPLETED, job.state)
        assertEquals("dedupe must not call the provider", 0, second.calls)
        // This is the regression: the reused completion must still be announced.
        assertPublishedExactlyOnce()
        assertEquals("it reuses the original transcript",
                     "transcript-1", completions().single().transcriptId)
    }

    // ---- 3. retry after failure ------------------------------------------

    @Test
    fun `retry publishes completion once`() = runBlocking {
        val f = audio("r.ogg")
        val failing = Scripted(listOf(
            TranscriptionOutcome.Failure(FailureKind.TERMINAL_INPUT, "no", 422, "mock")))
        val r = runner(failing, f)
        val failed = r.run(r.submit("content://x/1", "r.ogg"))
        assertEquals(JobState.FAILED, failed.state)
        transitions.clear()

        clock += 10
        val retried = r.retry(failed.id)!!
        val ok = Scripted(listOf(TranscriptionOutcome.Success("second time", "apex-local")))
        val done = runner(ok, f).run(retried)

        assertEquals(JobState.COMPLETED, done.state)
        assertPublishedExactlyOnce()
    }

    // ---- 4. microphone transcription --------------------------------------

    @Test
    fun `microphone transcription publishes completion once`() = runBlocking {
        // Same pipeline, a file:// source instead of a content:// share.
        val f = audio("rec.wav", fill = 3)
        val r = runner(Scripted(listOf(TranscriptionOutcome.Success("spoken words", "apex-local"))), f)
        val job = r.run(r.submit("file:///data/cache/recording123.wav", "recording123"))
        assertEquals(JobState.COMPLETED, job.state)
        assertPublishedExactlyOnce()
    }

    // ---- 5. a future cloud provider ---------------------------------------

    @Test
    fun `cloud provider completion publishes once through the same path`() = runBlocking {
        val f = audio("c.ogg", fill = 9)
        val cloud = object : TranscriptionProvider {
            override val id = "deepgram"
            override val displayName = "Deepgram"
            override suspend fun transcribe(
                request: TranscriptionRequest, onProgress: (Float) -> Unit
            ): TranscriptionOutcome {
                onProgress(0.5f)      // upload progress
                onProgress(1f)        // upload done -> TRANSCRIBING
                return TranscriptionOutcome.Success("cloud transcript", "deepgram")
            }
        }
        val r = runner(cloud, f)
        val job = r.run(r.submit("content://x/9", "c.ogg"))
        assertEquals(JobState.COMPLETED, job.state)
        assertEquals("deepgram", job.providerId)
        assertPublishedExactlyOnce()
    }

    // ---- no completed job may leave the UI pending ------------------------

    @Test
    fun `every terminal outcome ends on a transition the UI can act on`() = runBlocking {
        // Success, dedupe, failure and cancellation all end on a transition
        // whose state is terminal -- never on a non-terminal state, which is
        // what leaves a spinner running.
        val f = audio("t.ogg", fill = 5)
        val r = runner(Scripted(listOf(TranscriptionOutcome.Success("done", "apex-local"))), f)
        r.run(r.submit("content://x/1", "t.ogg"))
        assertTrue(transitions.last().state.terminal)

        transitions.clear()
        val bad = Scripted(listOf(
            TranscriptionOutcome.Failure(FailureKind.TERMINAL_INPUT, "no", 422, "mock")))
        val r2 = runner(bad, audio("u.ogg", fill = 6))
        clock += 10
        r2.run(r2.submit("content://x/2", "u.ogg"))
        assertTrue(transitions.last().state.terminal)
    }

    // ---- empty transcripts are failures, not silent empty records --------

    @Test
    fun `an empty transcript fails instead of creating a zero-character record`() = runBlocking {
        val before = persisted
        val r = runner(Scripted(listOf(TranscriptionOutcome.Success("", "apex-local"))),
                       audio("silent.ogg", fill = 1))
        val job = r.run(r.submit("content://x/1", "silent.ogg"))

        assertEquals(JobState.FAILED, job.state)
        assertEquals(FailureReason.EMPTY_TRANSCRIPT, job.failureReason)
        assertEquals("nothing may be written to History", before, persisted)
        assertTrue("no COMPLETED transition at all", completions().isEmpty())
    }

    @Test
    fun `a whitespace-only transcript is treated the same way`() = runBlocking {
        val r = runner(Scripted(listOf(TranscriptionOutcome.Success("   \n\t ", "apex-local"))),
                       audio("ws.ogg", fill = 2))
        val job = r.run(r.submit("content://x/1", "ws.ogg"))
        assertEquals(JobState.FAILED, job.state)
        assertEquals(FailureReason.EMPTY_TRANSCRIPT, job.failureReason)
    }

    @Test
    fun `the empty-transcript failure is explained to the user`() {
        val msg = com.whispercppdemo.jobs.failureMessageFor(FailureReason.EMPTY_TRANSCRIPT)
        assertTrue(msg.isNotBlank())
        assertFalse("must not read like a crash", msg.contains("error", ignoreCase = true))
        assertTrue(msg.contains("speech", ignoreCase = true))
    }
}
