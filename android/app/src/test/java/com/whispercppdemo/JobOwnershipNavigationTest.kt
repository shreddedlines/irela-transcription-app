package com.whispercppdemo

import com.whispercppdemo.jobs.JobRecord
import com.whispercppdemo.jobs.JobRunner
import com.whispercppdemo.jobs.JobState
import com.whispercppdemo.jobs.JobStore
import com.whispercppdemo.jobs.PreparedAudio
import com.whispercppdemo.transcribe.TranscriptionState
import com.whispercppdemo.transcribe.provider.FailureKind
import com.whispercppdemo.transcribe.provider.TranscriptionOutcome
import com.whispercppdemo.transcribe.provider.TranscriptionProvider
import com.whispercppdemo.transcribe.provider.TranscriptionRequest
import com.whispercppdemo.ui.common.TerminalRoute
import com.whispercppdemo.ui.common.ownsEvent
import com.whispercppdemo.ui.common.terminalRoute
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * event(jobId = X) can only affect navigation for job X.
 *
 * The device bug: job A failed (or was cancelled), job B was started, B
 * completed, and the app went Home instead of opening B's transcript. A's
 * stale terminal state had been routed after B's "job requested" flag was set,
 * consuming it, so B's completion was treated as nobody's.
 *
 * Jobs, ids and transcripts here come from the real JobRunner and JobStore.
 * The UI side is the real ownsEvent()/terminalRoute() the navigator calls,
 * driven by [Ui], which holds exactly the state MainScreenViewModel holds:
 * the followed id, the resolved record id, and the lagging mirror of the
 * service state.
 */
class JobOwnershipNavigationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: JobStore
    private var clock = 1_000L
    private var records = 0

    /** Every state the service published, newest last. */
    private val published = mutableListOf<TranscriptionState>()
    private val aliases = mutableMapOf<String, String>()

    @Before
    fun setUp() {
        store = JobStore(tmp.newFolder("jobs"))
        clock = 1_000L; records = 0
        published.clear(); aliases.clear()
    }

    // ---- the service side: real runner, the service's terminal mapping ---

    private fun provider(vararg outcomes: TranscriptionOutcome) = object : TranscriptionProvider {
        override val id = "mock"
        override val displayName = "Mock"
        var calls = 0
        override suspend fun transcribe(
            request: TranscriptionRequest, onProgress: (Float) -> Unit
        ): TranscriptionOutcome = outcomes[minOf(calls++, outcomes.size - 1)]
    }

    /** TranscriptionService.publish for the terminal transitions it maps. */
    private fun publish(job: JobRecord) {
        published += when (job.state) {
            JobState.COMPLETED -> TranscriptionState.Done(job.displayName, "text", job.transcriptId, job.id)
            JobState.FAILED -> TranscriptionState.Failed(job.displayName, "failed", job.id, true)
            JobState.CANCELLED -> TranscriptionState.Cancelled(job.displayName, job.id, true)
            else -> TranscriptionState.Uploading(job.displayName, null)
        }
    }

    private fun runner(p: TranscriptionProvider, audio: File) = JobRunner(
        store = store, provider = p,
        prepare = { PreparedAudio(audio, "audio/ogg") },
        persistTranscript = { _, _, _ -> "record-${++records}" },
        onTransition = { publish(it) },
        now = { clock++ }, sleep = { }
    )

    private fun audio(name: String, fill: Byte): File =
        File(tmp.root, name).apply { writeBytes(ByteArray(2048) { fill }) }

    private val fail = TranscriptionOutcome.Failure(FailureKind.TERMINAL_INPUT, "bad", 422, "mock")
    private fun ok() = TranscriptionOutcome.Success("hello", "mock")

    // ---- the UI side ------------------------------------------------------

    /** The navigator's view of one screen's lifetime. */
    private inner class Ui {
        var followed: String? = null
        var completedRecordId: String? = null
        var onProcessing = false
        val routed = mutableListOf<TerminalRoute>()

        /** MainScreenViewModel.beginJob: the id exists before submission. */
        fun begin(): String = UUID.randomUUID().toString().also {
            followed = it; completedRecordId = null
        }

        /** The ViewModel's Done collector. */
        fun collect(state: TranscriptionState) {
            if (state is TranscriptionState.Done && ownsEvent(state, followed, aliases)) {
                completedRecordId = state.transcriptId
            }
        }

        /** One evaluation of AppNavHost's LaunchedEffect for [state]. */
        fun route(state: TranscriptionState): TerminalRoute {
            val r = terminalRoute(state, followed, aliases, completedRecordId, onProcessing)
            when (r) {
                is TerminalRoute.OpenTranscript, TerminalRoute.ShowFailed,
                TerminalRoute.ShowCancelled -> { followed = null; completedRecordId = null }
                else -> Unit
            }
            if (r != TerminalRoute.None) routed += r
            return r
        }

        /**
         * The navigator sees the state first; the collector then resolves a
         * Done's record, which re-fires the effect. Returns the route that
         * acted, or None if neither evaluation did.
         */
        fun deliver(state: TranscriptionState): TerminalRoute {
            val first = route(state)
            if (first != TerminalRoute.None) return first
            collect(state)
            return route(state)
        }
    }

    // ---- A fails, B completes -> B's transcript ---------------------------

    private fun failThenComplete(firstEnds: (JobRunner, JobRecord) -> JobRecord,
                                 expectedFirstRoute: TerminalRoute) = runBlocking {
        val ui = Ui()

        // Job A.
        val aId = ui.begin()
        val rA = runner(provider(fail), audio("a.ogg", 1))
        val a = firstEnds(rA, rA.submit("content://a", "a.ogg", aId))
        assertEquals(aId, a.id)
        val aTerminal = published.last()
        assertEquals(expectedFirstRoute, ui.deliver(aTerminal))

        // Job B, started while A's terminal state is still the latest one the
        // (lagging) screen has seen.
        ui.onProcessing = true
        val bId = ui.begin()
        assertEquals("A's stale terminal must not act for B",
                     TerminalRoute.None, ui.route(aTerminal))
        assertEquals("and must not consume B", bId, ui.followed)

        val rB = runner(provider(ok()), audio("b.ogg", 2))
        val b = rB.run(rB.submit("content://b", "b.ogg", bId))
        assertEquals(JobState.COMPLETED, b.state)

        val route = ui.deliver(published.last())
        assertEquals(TerminalRoute.OpenTranscript(b.transcriptId!!), route)
    }

    @Test
    fun `failed job A then completed job B opens B's transcript`() =
        failThenComplete({ r, j -> runBlocking { r.run(j) } }, TerminalRoute.ShowFailed)

    @Test
    fun `cancelled job A then completed job B opens B's transcript`() =
        failThenComplete({ r, j -> r.cancel(j.id)!! }, TerminalRoute.ShowCancelled)

    @Test
    fun `the exact device race - A's failure never routed before B began`() = runBlocking {
        // The user left A's flow before its failure was routed; B starts; the
        // screen then evaluates A's FAILED with B's follow in place.
        val ui = Ui()
        val aId = ui.begin()
        val rA = runner(provider(fail), audio("a.ogg", 1))
        rA.run(rA.submit("content://a", "a.ogg", aId))
        val staleFailedA = published.last()

        val bId = ui.begin()                           // A never routed
        ui.onProcessing = true
        assertEquals(TerminalRoute.None, ui.route(staleFailedA))
        assertEquals(bId, ui.followed)

        val rB = runner(provider(ok()), audio("b.ogg", 2))
        val b = rB.run(rB.submit("content://b", "b.ogg", bId))
        assertEquals(TerminalRoute.OpenTranscript(b.transcriptId!!), ui.deliver(published.last()))
        assertFalse("Home must never be chosen for B",
                    ui.routed.contains(TerminalRoute.LeaveProcessing))
    }

    // ---- the other completion shapes after a failure ----------------------

    @Test
    fun `dedupe completion after a failure opens the reused transcript`() = runBlocking {
        val ui = Ui()
        // Earlier successful job C with audio X.
        val same = audio("same.ogg", 7)
        val cId = ui.begin()
        val rC = runner(provider(ok()), same)
        val c = rC.run(rC.submit("content://c", "same.ogg", cId))
        ui.deliver(published.last())

        // Job A fails on different audio.
        val aId = ui.begin()
        val rA = runner(provider(fail), audio("a.ogg", 1))
        rA.run(rA.submit("content://a", "a.ogg", aId))
        val staleA = published.last()
        ui.deliver(staleA)

        // Job B: the same audio as C.
        val bId = ui.begin()
        assertEquals(TerminalRoute.None, ui.route(staleA))
        val noCall = provider(TranscriptionOutcome.Success("must not run", "mock"))
        val rB = runner(noCall, same)
        clock += 10
        val b = rB.run(rB.submit("content://b", "same.ogg", bId))
        assertEquals(JobState.COMPLETED, b.state)
        assertEquals("dedupe reuses C's record", c.transcriptId, b.transcriptId)
        assertEquals(TerminalRoute.OpenTranscript(c.transcriptId!!), ui.deliver(published.last()))
    }

    @Test
    fun `retry completion after a failure opens the retry's transcript`() = runBlocking {
        val ui = Ui()
        val staged = audio("r.ogg", 4)
        val aId = ui.begin()
        val r = runner(provider(fail), staged)
        val a = r.run(r.submit("content://a", "r.ogg", aId))
        val staleA = published.last()
        ui.deliver(staleA)

        // Retry: the UI chooses the new attempt's id, as retryJob does.
        val bId = ui.begin()
        val retried = r.retry(a.id, bId)!!
        assertEquals("the retry runs under the id the UI chose", bId, retried.id)
        assertEquals(TerminalRoute.None, ui.route(staleA))

        val b = runner(provider(ok()), staged).run(retried)
        assertEquals(JobState.COMPLETED, b.state)
        assertEquals(TerminalRoute.OpenTranscript(b.transcriptId!!), ui.deliver(published.last()))
    }

    @Test
    fun `process recreation between the two jobs`() = runBlocking {
        // A fails in one process; the process dies; a fresh screen starts B.
        val first = Ui()
        val aId = first.begin()
        val rA = runner(provider(fail), audio("a.ogg", 1))
        rA.run(rA.submit("content://a", "a.ogg", aId))
        val staleA = published.last()

        // New process: new screen state, and recovery republishes A's failure.
        val second = Ui().apply { onProcessing = false }
        assertEquals("nothing followed, not on Processing: no route",
                     TerminalRoute.None, second.route(staleA))

        val bId = second.begin()
        second.onProcessing = true
        assertEquals(TerminalRoute.None, second.route(staleA))
        // Jobs persisted by the old process are visible to the new one.
        val reopened = JobStore(File(tmp.root, "jobs"))
        assertEquals(JobState.FAILED, reopened.get(aId)!!.state)

        val rB = runner(provider(ok()), audio("b.ogg", 2))
        val b = rB.run(rB.submit("content://b", "b.ogg", bId))
        assertEquals(TerminalRoute.OpenTranscript(b.transcriptId!!), second.deliver(published.last()))
    }

    @Test
    fun `recovery of an older orphan does not hijack the job being followed`() = runBlocking {
        val ui = Ui()
        val bId = ui.begin()
        ui.onProcessing = true
        // An orphan from a dead process is recovered while B runs.
        val orphan = store.update(store.create("content://o", "o.ogg", clock++).copy(state = JobState.TRANSCRIBING))
        store.recoverOrphans(clock).forEach { publish(it) }
        assertEquals(orphan.id, (published.last() as TranscriptionState.Failed).jobId)
        assertEquals(TerminalRoute.None, ui.route(published.last()))
        assertEquals(bId, ui.followed)
    }

    // ---- other jobs' events, aliases, legacy events -----------------------

    @Test
    fun `another job's completion does nothing while following a queued job`() {
        val ui = Ui().apply { onProcessing = true }
        ui.begin()
        val otherDone = TranscriptionState.Done("c.ogg", "text", "record-9", "job-c")
        assertEquals(TerminalRoute.None, ui.deliver(otherDone))
        assertEquals(null, ui.completedRecordId)
    }

    @Test
    fun `a request attached to an existing active job follows that job`() = runBlocking {
        val ui = Ui()
        val src = audio("s.ogg", 5)
        val r = runner(provider(ok()), src)
        val existing = r.submit("content://same", "s.ogg", "job-existing")

        val requested = ui.begin()
        val joined = r.submit("content://same", "s.ogg", requested)
        assertEquals("same active source is not duplicated", existing.id, joined.id)
        aliases[requested] = joined.id                 // TranscriptionStore.alias

        val done = r.run(joined)
        assertEquals(TerminalRoute.OpenTranscript(done.transcriptId!!), ui.deliver(published.last()))
    }

    @Test
    fun `an event with no job id is never owned`() {
        assertFalse(ownsEvent(TranscriptionState.Failed("x", "boom"), "job-b", emptyMap()))
        assertFalse(ownsEvent(TranscriptionState.Done("x", "t", "r"), "job-b", emptyMap()))
    }

    @Test
    fun `nothing followed and nothing running leaves Processing`() {
        assertEquals(TerminalRoute.LeaveProcessing,
            terminalRoute(TranscriptionState.Idle, null, emptyMap(), null, onProcessing = true))
        assertEquals(TerminalRoute.LeaveProcessing,
            terminalRoute(TranscriptionState.Failed("x", "old", "job-a"), null, emptyMap(), null, true))
    }

    @Test
    fun `running states never route terminally`() {
        assertEquals(TerminalRoute.None,
            terminalRoute(TranscriptionState.Transcribing("x", 1, 2), "job-b", emptyMap(), null, true))
    }

    @Test
    fun `an owned completion waits for its record rather than leaving`() {
        val done = TranscriptionState.Done("b", "t", "record-b", "job-b")
        assertEquals(TerminalRoute.None,
            terminalRoute(done, "job-b", emptyMap(), completedRecordId = null, onProcessing = true))
        assertTrue(terminalRoute(done, "job-b", emptyMap(), "record-b", true)
            is TerminalRoute.OpenTranscript)
    }
}
