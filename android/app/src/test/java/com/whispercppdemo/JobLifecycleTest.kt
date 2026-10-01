package com.whispercppdemo

import com.whispercppdemo.jobs.AudioRejected
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Job lifecycle tests. No Android, no device, no deployed backend.
 *
 * The provider boundary is mocked, so every failure branch is exercised here: silent drops, process death at each state, orphan
 * recovery, cancellation, retry exhaustion, terminal vs transient errors,
 * duplicate delivery, and persistence failure.
 */
class JobLifecycleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: JobStore
    private var clock = 1_000L
    private val slept = mutableListOf<Long>()
    private val transitions = mutableListOf<Pair<String, JobState>>()

    @Before
    fun setUp() {
        store = JobStore(tmp.newFolder("jobs"))
        clock = 1_000L
        slept.clear()
        transitions.clear()
    }

    // ---- test doubles -----------------------------------------------------

    /** Returns a scripted sequence of outcomes, one per attempt. */
    private class ScriptedProvider(
        private val script: List<TranscriptionOutcome>
    ) : TranscriptionProvider {
        override val id = "mock"
        override val displayName = "Mock"
        var calls = 0
        val idempotencyKeys = mutableListOf<String>()

        override suspend fun transcribe(
            request: TranscriptionRequest,
            onProgress: (Float) -> Unit
        ): TranscriptionOutcome {
            idempotencyKeys += request.idempotencyKey
            onProgress(0.5f)
            onProgress(1f)          // upload complete -> TRANSCRIBING
            return script[minOf(calls++, script.size - 1)]
        }
    }

    private fun ok(text: String = "hello") =
        TranscriptionOutcome.Success(text, "mock")

    private fun fail(kind: FailureKind) =
        TranscriptionOutcome.Failure(kind, "mock failure", null, "mock")

    private fun runner(
        provider: TranscriptionProvider,
        prepare: suspend (JobRecord) -> PreparedAudio = { audio() },
        persist: suspend (JobRecord, String, Long?) -> String = { _, _, _ -> "t-1" }
    ) = JobRunner(
        store = store, provider = provider, prepare = prepare,
        persistTranscript = persist,
        onTransition = { transitions += it.id to it.state },
        now = { clock },
        sleep = { slept += it }
    )

    private fun audio(): PreparedAudio {
        val f = File(tmp.root, "a.wav").apply { if (!exists()) writeText("x") }
        return PreparedAudio(f, "audio/wav")
    }

    // ---- 1. PENDING is persisted before anything can fail -----------------

    @Test
    fun `submit persists the request immediately`() {
        val r = runner(ScriptedProvider(listOf(ok())))
        val job = r.submit("content://a", "a.opus")
        assertEquals(JobState.QUEUED, job.state)
        // Both states were observed, PENDING first.
        assertEquals(JobState.PENDING, transitions.first().second)
        assertNotNull(store.get(job.id))
    }

    // ---- 2. two simultaneous imports: neither is dropped ------------------

    @Test
    fun `two simultaneous imports both survive`() {
        val r = runner(ScriptedProvider(listOf(ok())))
        val a = r.submit("content://a", "a.opus")
        clock += 5                                   // second share arrives later
        val b = r.submit("content://b", "b.opus")
        assertFalse("distinct jobs", a.id == b.id)
        assertEquals(2, store.active().size)
        // Arrival order preserved: neither is dropped, first in is first out.
        assertEquals(a.id, r.nextQueued()!!.id)
    }

    @Test
    fun `queue order is deterministic even within one millisecond`() {
        val r = runner(ScriptedProvider(listOf(ok())))
        // Same frozen clock: ties must still resolve the same way every time.
        r.submit("content://a", "a.opus")
        r.submit("content://b", "b.opus")
        val picks = (1..5).map { r.nextQueued()!!.id }.toSet()
        assertEquals("tie-break must be stable", 1, picks.size)
    }

    // ---- duplicate intent delivery ---------------------------------------

    @Test
    fun `duplicate submission of the same source returns the same job`() {
        val r = runner(ScriptedProvider(listOf(ok())))
        val first = r.submit("content://same", "x.opus")
        val second = r.submit("content://same", "x.opus")
        assertEquals(first.id, second.id)
        assertEquals(1, store.active().size)
    }

    @Test
    fun `duplicate protection lapses once the job is terminal`() = runBlocking {
        val r = runner(ScriptedProvider(listOf(ok())))
        val first = r.submit("content://same", "x.opus")
        r.run(first)
        val again = r.submit("content://same", "x.opus")
        assertFalse(first.id == again.id)     // re-transcribing is allowed
    }

    // ---- 3 & 4. process death at every major state -> orphan recovery -----

    @Test
    fun `orphans are recovered from every active state`() {
        for (state in JobState.values().filter { it.active }) {
            val s = JobStore(tmp.newFolder("jobs-$state"))
            val j = s.create("content://x", "x", clock)
            s.update(j.moveTo(state, clock))

            // simulate restart: a fresh store over the same directory
            val recovered = JobStore(File(s.let { _ -> tmp.root }, "jobs-$state"))
                .recoverOrphans(clock + 1)

            assertEquals("$state should be recovered", 1, recovered.size)
            assertEquals(JobState.FAILED, recovered[0].state)
            assertEquals(FailureReason.INTERRUPTED, recovered[0].failureReason)
        }
    }

    @Test
    fun `terminal jobs are untouched by orphan recovery`() {
        val j = store.create("content://x", "x", clock)
        store.update(j.moveTo(JobState.COMPLETED, clock, transcriptId = "t-9"))
        assertTrue(store.recoverOrphans(clock + 1).isEmpty())
        assertEquals(JobState.COMPLETED, store.get(j.id)!!.state)
    }

    @Test
    fun `job records survive a restart`() {
        val dir = tmp.newFolder("persisted")
        val id = JobStore(dir).create("content://keep", "keep.opus", clock).id
        val reloaded = JobStore(dir).get(id)     // brand-new instance
        assertNotNull(reloaded)
        assertEquals("keep.opus", reloaded!!.displayName)
        assertEquals(JobState.PENDING, reloaded.state)
    }

    // ---- 5. FAILED and CANCELLED persist ---------------------------------

    @Test
    fun `cancellation is persisted, not silently discarded`() {
        val r = runner(ScriptedProvider(listOf(ok())))
        val job = r.submit("content://a", "a.opus")
        val cancelled = r.cancel(job.id)!!
        assertEquals(JobState.CANCELLED, cancelled.state)
        assertEquals(JobState.CANCELLED, store.get(job.id)!!.state)
    }

    @Test
    fun `cancelling an already terminal job is a no-op`() = runBlocking {
        val r = runner(ScriptedProvider(listOf(ok())))
        val job = r.run(r.submit("content://a", "a.opus"))
        assertEquals(JobState.COMPLETED, job.state)
        assertEquals(JobState.COMPLETED, r.cancel(job.id)!!.state)
    }

    @Test
    fun `a cancel during backoff is honoured instead of retrying`() = runBlocking {
        val p = ScriptedProvider(listOf(fail(FailureKind.RETRYABLE_SERVER), ok()))
        var cancelledOnce = false
        val r = JobRunner(store, p, { audio() }, { _, _, _ -> "t-1" },
            onTransition = {
                // cancel the moment it enters backoff
                if (it.state == JobState.RETRYING && !cancelledOnce) {
                    cancelledOnce = true
                    store.update(it.moveTo(JobState.CANCELLED, clock))
                }
            },
            now = { clock }, sleep = { slept += it })
        val out = r.run(r.submit("content://a", "a.opus"))
        assertEquals(JobState.CANCELLED, out.state)
        assertEquals(1, p.calls)      // never retried
    }

    // ---- 6. retry: classification, bound, backoff, idempotency -----------

    @Test
    fun `transient failure is retried and can succeed`() = runBlocking {
        val p = ScriptedProvider(listOf(fail(FailureKind.RETRYABLE_SERVER), ok()))
        val out = runner(p).run(runner(p).submit("content://a", "a.opus"))
        assertEquals(JobState.COMPLETED, out.state)
        assertEquals(2, p.calls)
        assertEquals(listOf(2_000L), slept)
    }

    @Test
    fun `retries are bounded at two and then fail`() = runBlocking {
        val p = ScriptedProvider(listOf(fail(FailureKind.RETRYABLE_SERVER)))
        val r = runner(p)
        val out = r.run(r.submit("content://a", "a.opus"))
        assertEquals(JobState.FAILED, out.state)
        assertEquals(FailureReason.RETRIES_EXHAUSTED, out.failureReason)
        assertEquals(1 + RetryPolicy.MAX_RETRIES, p.calls)   // 3 attempts total
        assertEquals(listOf(2_000L, 4_000L), slept)          // exponential
    }

    @Test
    fun `terminal provider error is not retried`() = runBlocking {
        val p = ScriptedProvider(listOf(fail(FailureKind.TERMINAL_INPUT)))
        val r = runner(p)
        val out = r.run(r.submit("content://a", "a.opus"))
        assertEquals(JobState.FAILED, out.state)
        assertEquals(FailureReason.AUDIO_REJECTED, out.failureReason)
        assertEquals(1, p.calls)
        assertTrue(slept.isEmpty())
    }

    @Test
    fun `auth failure is terminal and never retried`() = runBlocking {
        val p = ScriptedProvider(listOf(fail(FailureKind.TERMINAL_AUTH)))
        val r = runner(p)
        val out = r.run(r.submit("content://a", "a.opus"))
        assertEquals(FailureReason.PROVIDER_TERMINAL, out.failureReason)
        assertEquals(1, p.calls)
    }

    @Test
    fun `every attempt of one job shares one idempotency key`() = runBlocking {
        val p = ScriptedProvider(listOf(fail(FailureKind.RATE_LIMITED)))
        val r = runner(p)
        val job = r.submit("content://a", "a.opus")
        r.run(job)
        assertEquals(3, p.idempotencyKeys.size)
        assertEquals(1, p.idempotencyKeys.toSet().size)      // never re-billed
        assertEquals(job.id, p.idempotencyKeys.first())
    }

    // ---- 8. real progress drives the UPLOADING -> TRANSCRIBING switch ----

    @Test
    fun `transcribing state is entered only when upload actually completes`() =
        runBlocking {
            val r = runner(ScriptedProvider(listOf(ok())))
            val seen = mutableListOf<Float>()
            r.run(r.submit("content://a", "a.opus")) { seen += it }
            val states = transitions.map { it.second }
            assertTrue(states.containsAll(listOf(
                JobState.PENDING, JobState.QUEUED, JobState.UPLOADING,
                JobState.TRANSCRIBING, JobState.COMPLETED)))
            assertTrue(states.indexOf(JobState.UPLOADING) <
                    states.indexOf(JobState.TRANSCRIBING))
            // progress came from the transport, not invented
            assertEquals(listOf(0.5f, 1f), seen)
        }

    // ---- 9. persistence failure must surface -----------------------------

    @Test
    fun `transcript persistence failure is reported, not swallowed`() = runBlocking {
        val r = runner(ScriptedProvider(listOf(ok("some text")))) { _, _, _ ->
            throw IOException("disk full")
        }
        val out = r.run(r.submit("content://a", "a.opus"))
        assertEquals(JobState.FAILED, out.state)
        assertEquals(FailureReason.PERSISTENCE_FAILED, out.failureReason)
    }

    @Test
    fun `unusable audio fails terminally without calling the provider`() =
        runBlocking {
            val p = ScriptedProvider(listOf(ok()))
            val r = runner(p, prepare = { throw AudioRejected("corrupt") })
            val out = r.run(r.submit("content://a", "a.opus"))
            assertEquals(JobState.FAILED, out.state)
            assertEquals(FailureReason.AUDIO_REJECTED, out.failureReason)
            assertEquals(0, p.calls)
        }

    @Test
    fun `job store create failure throws instead of losing the request`() {
        val file = tmp.newFile("not-a-dir")
        val broken = JobStore(File(file, "jobs"))
        try {
            broken.create("content://a", "a", clock)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("cannot create job dir"))
        }
    }

    // ---- record round-trip ------------------------------------------------

    @Test
    fun `records with awkward names round-trip`() {
        val name = "voice = note\nwith\ttabs & 100%"
        val j = store.create("content://x?a=1&b=2", name, clock)
        val back = store.get(j.id)!!
        assertEquals(name, back.displayName)
        assertEquals("content://x?a=1&b=2", back.sourceUri)
    }

    @Test
    fun `state classification is exhaustive and consistent`() {
        JobState.values().forEach { assertTrue(it.active != it.terminal) }
        assertTrue(JobState.COMPLETED.terminal)
        assertTrue(JobState.FAILED.terminal)
        assertTrue(JobState.CANCELLED.terminal)
        assertTrue(JobState.RETRYING.active)
    }
}
