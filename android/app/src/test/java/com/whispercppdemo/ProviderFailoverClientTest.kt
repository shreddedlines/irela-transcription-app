package com.whispercppdemo

import com.whispercppdemo.jobs.FailureReason
import com.whispercppdemo.jobs.JobRecord
import com.whispercppdemo.jobs.JobRunner
import com.whispercppdemo.jobs.JobState
import com.whispercppdemo.jobs.JobStore
import com.whispercppdemo.jobs.PreparedAudio
import com.whispercppdemo.jobs.RetryPolicy
import com.whispercppdemo.transcribe.provider.FailureKind
import com.whispercppdemo.transcribe.provider.TranscriptionOutcome
import com.whispercppdemo.transcribe.provider.TranscriptionProvider
import com.whispercppdemo.transcribe.provider.TranscriptionRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What the app does when the backend's provider router reports an outage or
 * slow processing. The router itself (Deepgram preferred, AssemblyAI backup,
 * health, recovery) is tested in backend/tests/test_provider_router.py; this is
 * the client half: bounded waits, no lost audio, no duplicate work, no crash.
 */
class ProviderFailoverClientTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var jobsDir: File
    private lateinit var store: JobStore
    private lateinit var audio: File
    private val sleeps = mutableListOf<Long>()
    private var prepares = 0
    private var clock = 1_000L

    @Before
    fun setUp() {
        jobsDir = tmp.newFolder("jobs")
        store = JobStore(jobsDir)
        audio = File(tmp.root, "rec-x.m4a").apply { writeBytes(ByteArray(8192) { (it % 251).toByte() }) }
        sleeps.clear(); prepares = 0
    }

    private class Scripted(vararg steps: () -> TranscriptionOutcome) : TranscriptionProvider {
        override val id = "deepgram"
        override val displayName = "Cloud"
        val steps = steps.toMutableList()
        val uploads = mutableListOf<String>()        // idempotency keys, one per upload
        val polls = mutableListOf<String>()
        var statusScript = mutableListOf<() -> TranscriptionOutcome?>()
        private fun next() = if (steps.size > 1) steps.removeAt(0)() else steps[0]()
        override suspend fun transcribe(request: TranscriptionRequest, onProgress: (Float) -> Unit):
                TranscriptionOutcome { uploads += request.idempotencyKey; onProgress(1f); return next() }
        override suspend fun checkStatus(request: TranscriptionRequest): TranscriptionOutcome? {
            polls += request.idempotencyKey
            return if (statusScript.isEmpty()) null
                   else if (statusScript.size > 1) statusScript.removeAt(0)() else statusScript[0]()
        }
    }

    private fun unavailable(retryAfterMs: Long? = 30_000) = TranscriptionOutcome.Failure(
        FailureKind.PROVIDER_UNAVAILABLE, "unavailable", 503, "deepgram",
        reason = "providers_unavailable", retryAfterMs = retryAfterMs)

    private fun processing() = TranscriptionOutcome.Failure(
        FailureKind.PROCESSING, "processing", 503, "deepgram", reason = "processing", retryAfterMs = 5_000)

    private fun ok(provider: String = "deepgram") = TranscriptionOutcome.Success("नमस्ते, कल 5 बजे", provider)

    private fun runner(p: TranscriptionProvider, s: JobStore = store) = JobRunner(
        store = s, provider = p,
        prepare = { prepares++; PreparedAudio(audio, "audio/mp4") },
        persistTranscript = { job, _, _ -> "t-${job.id}" },
        now = { clock++ }, sleep = { sleeps += it })

    // ---- outage ---------------------------------------------------------------------------

    @Test
    fun `all providers unavailable leaves a persisted, retryable job with its audio`() = runBlocking {
        val p = Scripted({ unavailable() })
        val r = runner(p)
        val job = r.run(r.submit("file://${audio.absolutePath}", "Conversation"))

        assertEquals(JobState.FAILED, job.state)
        assertEquals(FailureReason.PROVIDER_UNAVAILABLE, job.failureReason)
        assertEquals(1 + RetryPolicy.MAX_PROVIDER_UNAVAILABLE_WAITS, p.uploads.size)   // bounded
        assertEquals(listOf(30_000L, 30_000L), sleeps)                                   // obeyed Retry-After
        assertTrue(audio.isFile)
        assertEquals(audio.absolutePath, job.stagedPath)
        assertTrue(r.canRetry(job.id))
        assertEquals("Transcription is temporarily unavailable. Your audio is saved -- tap Try again in a few minutes.",
                     com.whispercppdemo.jobs.failureMessageFor(job.failureReason))
    }

    @Test
    fun `waits are capped even if the backend asks for an hour`() = runBlocking {
        val r = runner(Scripted({ unavailable(retryAfterMs = 3_600_000) }))
        r.run(r.submit("file://a", "x"))
        assertTrue(sleeps.all { it <= RetryPolicy.PROVIDER_UNAVAILABLE_MAX_WAIT_MS })
    }

    @Test
    fun `outage then backup provider success completes with the backup recorded`() = runBlocking {
        val p = Scripted({ unavailable() }, { ok(provider = "assemblyai") })
        val r = runner(p)
        val job = r.run(r.submit("file://a", "x"))
        assertEquals(JobState.COMPLETED, job.state)
        assertEquals("assemblyai", job.providerId)
        assertEquals(1, p.uploads.toSet().size)                                          // one logical job
    }

    @Test
    fun `provider waits do not use up the transient-error retry budget`() = runBlocking {
        val p = Scripted({ unavailable() }, { unavailable() },
            { TranscriptionOutcome.Failure(FailureKind.RETRYABLE_SERVER, "5xx") },
            { TranscriptionOutcome.Failure(FailureKind.RETRYABLE_SERVER, "5xx") }, { ok() })
        val r = runner(p)
        assertEquals(JobState.COMPLETED, r.run(r.submit("file://a", "x")).state)
    }

    // ---- processing: poll, never re-upload ----------------------------------------------------

    @Test
    fun `still processing is polled without re-uploading or re-preparing`() = runBlocking {
        val p = Scripted({ processing() }).apply {
            statusScript = mutableListOf({ processing() }, { processing() }, { ok() })
        }
        val r = runner(p)
        val job = r.run(r.submit("file://a", "x"))
        assertEquals(JobState.COMPLETED, job.state)
        assertEquals(1, p.uploads.size)                                                  // audio sent once
        assertEquals(3, p.polls.size)
        assertEquals(1, prepares)
        assertEquals(setOf(job.idempotencyKey), (p.uploads + p.polls).toSet())
    }

    @Test
    fun `a poll the backend does not recognise falls back to one upload with the same key`() = runBlocking {
        val p = Scripted({ processing() }, { ok() }).apply { statusScript = mutableListOf({ null }) }
        val r = runner(p)
        val job = r.run(r.submit("file://a", "x"))
        assertEquals(JobState.COMPLETED, job.state)
        assertEquals(listOf(job.idempotencyKey, job.idempotencyKey), p.uploads)
    }

    @Test
    fun `processing polls are bounded`() = runBlocking {
        val p = Scripted({ processing() }).apply { statusScript = mutableListOf({ processing() }) }
        val r = runner(p)
        val job = r.run(r.submit("file://a", "x"))
        assertEquals(JobState.FAILED, job.state)
        assertEquals(RetryPolicy.MAX_PROCESSING_POLLS, p.polls.size)
        assertTrue(r.canRetry(job.id))
    }

    // ---- retry, restart, idempotency ---------------------------------------------------------

    @Test
    fun `retry after the outage keeps the original idempotency key`() = runBlocking {
        val down = Scripted({ unavailable() })
        val r1 = runner(down)
        val failed = r1.run(r1.submit("file://a", "x"))

        val up = Scripted({ ok() })
        val r2 = runner(up)
        val retried = r2.retry(failed.id)!!
        assertEquals(failed.idempotencyKey, retried.idempotencyKey)
        val done = r2.run(retried)
        assertEquals(JobState.COMPLETED, done.state)
        // If the first attempt had in fact finished server-side, this key
        // replays that result instead of paying a provider again.
        assertEquals(listOf(failed.idempotencyKey), up.uploads)
    }

    @Test
    fun `retry of an empty transcript gets a fresh key so it really re-transcribes`() = runBlocking {
        val r = runner(Scripted({ TranscriptionOutcome.Success("", "deepgram") }))
        val empty = r.run(r.submit("file://a", "x"))
        assertEquals(FailureReason.EMPTY_TRANSCRIPT, empty.failureReason)
        val retried = r.retry(empty.id)!!
        assertTrue(retried.idempotencyKey != empty.idempotencyKey)
    }

    @Test
    fun `process restart during an outage keeps the job retryable`() = runBlocking {
        val r = runner(Scripted({ unavailable() }))
        val failed = r.run(r.submit("file://a", "x"))

        val reopened = JobStore(jobsDir)                     // new process
        val again = reopened.get(failed.id)!!
        assertEquals(FailureReason.PROVIDER_UNAVAILABLE, again.failureReason)
        val r2 = runner(Scripted({ ok() }), reopened)
        assertTrue(r2.canRetry(failed.id))
        val retried = r2.retry(failed.id)!!
        assertEquals(failed.idempotencyKey, JobStore(jobsDir).get(retried.id)!!.idempotencyKey)
        assertEquals(JobState.COMPLETED, r2.run(retried).state)
    }

    @Test
    fun `records written before the idempotency field still decode with key = id`() {
        val legacy = "v=1\nid=abc\nuri=file%3A%2F%2Fx\nname=x\nstate=FAILED\ncreatedAt=1\nupdatedAt=2\n" +
                "attempt=0\nfailureReason=offline\ntranscriptId=\nproviderId=\ncontentHash=\nstagedPath=\n"
        val j = JobRecord.decode(legacy)
        assertNull(j.idempotencyToken)
        assertEquals("abc", j.idempotencyKey)
        assertEquals(j, JobRecord.decode(j.encode()))
    }

    @Test
    fun `input errors are terminal and never waited on`() = runBlocking {
        val p = Scripted({ TranscriptionOutcome.Failure(FailureKind.TERMINAL_INPUT, "bad audio", 422) })
        val r = runner(p)
        val job = r.run(r.submit("file://a", "x"))
        assertEquals(FailureReason.AUDIO_REJECTED, job.failureReason)
        assertEquals(1, p.uploads.size)
        assertTrue(sleeps.isEmpty())
    }
}
