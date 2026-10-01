package com.whispercppdemo

import com.whispercppdemo.jobs.FailureReason
import com.whispercppdemo.jobs.JobRunner
import com.whispercppdemo.jobs.JobState
import com.whispercppdemo.jobs.JobStore
import com.whispercppdemo.jobs.PreparedAudio
import com.whispercppdemo.transcribe.Reachability
import com.whispercppdemo.transcribe.provider.TranscriptionOutcome
import com.whispercppdemo.transcribe.provider.TranscriptionProvider
import com.whispercppdemo.transcribe.provider.TranscriptionRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Sharing audio with no connection.
 *
 * The old behaviour submitted the job, failed it, and cancelled it without
 * ever copying the audio -- so the one action the user needs when their
 * connection returns (Try again) could not work, because the share grant that
 * delivered the file dies with the sending task. This pins the fix: stage
 * first, fail second.
 */
class OfflineRetryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: JobStore
    private var clock = 1_000L

    @Before
    fun setUp() {
        store = JobStore(tmp.newFolder("jobs"))
        clock = 1_000L
    }

    private class NeverCalled : TranscriptionProvider {
        override val id = "mock"
        override val displayName = "Mock"
        var calls = 0
        override suspend fun transcribe(
            request: TranscriptionRequest, onProgress: (Float) -> Unit
        ): TranscriptionOutcome {
            calls++
            return TranscriptionOutcome.Success("should not happen", "mock")
        }
    }

    private fun runner(
        provider: TranscriptionProvider,
        prepare: suspend (com.whispercppdemo.jobs.JobRecord) -> PreparedAudio
    ) = JobRunner(
        store = store, provider = provider, prepare = prepare,
        persistTranscript = { _, _, _ -> "t-1" }, now = { clock }, sleep = { }
    )

    @Test
    fun `an offline share keeps its audio and can be retried`() = runBlocking {
        val staged = File(tmp.root, "clip.ogg").apply { writeBytes(ByteArray(512)) }
        val provider = NeverCalled()
        val r = runner(provider) { PreparedAudio(staged, "audio/ogg") }

        val job = r.submit("content://share/1", "clip.ogg")
        val failed = r.stageAndFail(job, FailureReason.OFFLINE)

        assertEquals(JobState.FAILED, failed.state)
        assertEquals(FailureReason.OFFLINE, failed.failureReason)
        assertEquals("the audio must have been copied", staged.absolutePath, failed.stagedPath)
        assertEquals("nothing may be sent while offline", 0, provider.calls)
        assertTrue("retry must be offered", r.canRetry(failed.id))

        // And the retry must actually reach the provider once back online.
        clock += 10
        val retried = r.retry(failed.id)
        assertNotNull(retried)
        val done = r.run(retried!!)
        assertEquals(JobState.COMPLETED, done.state)
        assertEquals(1, provider.calls)
    }

    @Test
    fun `unreadable audio offline fails as rejected, not as offline`() = runBlocking {
        val provider = NeverCalled()
        val r = runner(provider) { throw java.io.IOException("gone") }
        val job = r.submit("content://share/1", "clip.ogg")
        val failed = r.stageAndFail(job, FailureReason.OFFLINE)

        assertEquals(JobState.FAILED, failed.state)
        // The connection is not what is wrong here; saying so would send the
        // user to fix the wrong thing.
        assertEquals(FailureReason.AUDIO_REJECTED, failed.failureReason)
        assertFalse("no copy exists, so no retry", r.canRetry(failed.id))
        assertEquals(0, provider.calls)
    }

    @Test
    fun `the offline message does not promise an automatic retry`() {
        val msg = Reachability.INTERNET_UNAVAILABLE.userMessage
        // Nothing in the app resumes a job by itself; the user taps Try again.
        assertFalse(msg.contains("will be transcribed"))
        assertTrue(msg.contains("Try again"))
        assertTrue("the user must know the audio was kept", msg.contains("saved"))
    }

    @Test
    fun `every reachability failure tells the user what to do`() {
        listOf(
            Reachability.INTERNET_UNAVAILABLE,
            Reachability.BACKEND_UNAVAILABLE,
            Reachability.PROVIDER_UNAVAILABLE
        ).forEach {
            assertTrue("${it.name} must name an action", it.userMessage.contains("Try again"))
        }
        assertEquals("", Reachability.INTERNET_AVAILABLE.userMessage)
    }
}
