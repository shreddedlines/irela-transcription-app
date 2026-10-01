package com.whispercppdemo

import com.whispercppdemo.history.TranscriptRecord
import com.whispercppdemo.history.TranscriptSource
import com.whispercppdemo.jobs.JobRunner
import com.whispercppdemo.jobs.JobState
import com.whispercppdemo.jobs.JobStore
import com.whispercppdemo.jobs.PreparedAudio
import com.whispercppdemo.transcribe.TranscriptionState
import com.whispercppdemo.transcribe.TranscriptionStore
import com.whispercppdemo.transcribe.provider.FailureKind
import com.whispercppdemo.transcribe.provider.TranscriptionOutcome
import com.whispercppdemo.transcribe.provider.TranscriptionProvider
import com.whispercppdemo.transcribe.provider.TranscriptionRequest
import com.whispercppdemo.ui.common.completedRecordIdFor
import com.whispercppdemo.ui.common.isStrandedOnProcessing
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
 * Opening the result of a finished job.
 *
 * The device bug: a duplicate share reused an existing transcript, so the
 * "which record is new?" search that identifies a completion found nothing,
 * `completedRecordId` stayed null, and the user was left on the Processing
 * screen reading "No transcription in progress."
 *
 * The fix makes every completion carry its own `transcriptId`. These tests
 * cover the four completion shapes and the dead-end that used to follow.
 */
class CompletionNavigationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: JobStore
    private var clock = 1_000L
    private var persisted = 0

    /** What the service publishes, in order. */
    private val published = mutableListOf<TranscriptionState>()

    @Before
    fun setUp() {
        store = JobStore(tmp.newFolder("jobs"))
        clock = 1_000L
        persisted = 0
        published.clear()
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

    /**
     * Mirrors `TranscriptionService.publish` for COMPLETED: the transition
     * carries the id, and Done is built from the persisted record.
     */
    private fun publish(job: com.whispercppdemo.jobs.JobRecord) {
        if (job.state != JobState.COMPLETED) return
        val text = texts[job.transcriptId] ?: return
        published += TranscriptionState.Done(job.displayName, text, job.transcriptId)
    }

    private val texts = mutableMapOf<String, String>()

    private fun runner(p: TranscriptionProvider, f: File) = JobRunner(
        store = store, provider = p,
        prepare = { PreparedAudio(f, "audio/ogg") },
        persistTranscript = { _, text, _ ->
            val id = "record-${++persisted}"
            texts[id] = text
            id
        },
        onTransition = { publish(it) },
        now = { clock }, sleep = { }
    )

    private fun lastDone() = published.filterIsInstance<TranscriptionState.Done>().last()

    /** What the ViewModel does with a Done. */
    private fun resolve(done: TranscriptionState.Done, fallback: String? = null): String? =
        runBlocking {
            completedRecordIdFor(done, refresh = { }, fallback = { fallback })
        }

    // ---- 1. a duplicate opens the EXISTING transcript --------------------

    @Test
    fun `duplicate share opens the existing transcript`() = runBlocking {
        val f = audio("same.ogg")
        val first = Scripted(listOf(TranscriptionOutcome.Success("the transcript", "apex-local")))
        val r1 = runner(first, f)
        val original = r1.run(r1.submit("content://x/1", "same.ogg"))
        val originalId = original.transcriptId
        assertNotNull(originalId)

        // Same bytes again.
        clock += 10
        val second = Scripted(listOf(TranscriptionOutcome.Success("must not run", "apex-local")))
        val r2 = runner(second, f)
        val dup = r2.run(r2.submit("content://x/2", "same.ogg"))

        assertEquals(JobState.COMPLETED, dup.state)
        assertEquals("dedupe must not call the provider", 0, second.calls)

        val done = lastDone()
        assertEquals("the reused record must be named", originalId, done.transcriptId)

        // The old search would return null here: the record is not new.
        val bySearch: String? = null
        assertEquals("must resolve to the existing record, not null",
                     originalId, resolve(done, fallback = bySearch))
    }

    @Test
    fun `the old new-record search cannot answer for a duplicate`() {
        // Pins WHY the id is needed: with the record already in the baseline,
        // "the record that was not there before" does not exist.
        val existing = listOf(
            TranscriptRecord("record-1", "clip", 1L, "text", TranscriptSource.IMPORT, null)
        )
        val baseline = existing.map { it.id }.toSet()
        val found = com.whispercppdemo.ui.common.newlyAddedRecordId(baseline, existing)
        assertNull("this is the bug the transcriptId replaces", found)
    }

    // ---- 2. a normal completion still opens its new transcript -----------

    @Test
    fun `normal completion opens its new transcript`() = runBlocking {
        val r = runner(Scripted(listOf(TranscriptionOutcome.Success("fresh text", "apex-local"))),
                       audio("a.ogg"))
        val job = r.run(r.submit("content://x/1", "a.ogg"))
        val done = lastDone()
        assertEquals(job.transcriptId, done.transcriptId)
        assertEquals(job.transcriptId, resolve(done))
    }

    // ---- 3. a retry opens the transcript the retry produced --------------

    @Test
    fun `retry completion opens the new transcript`() = runBlocking {
        val f = audio("r.ogg", fill = 3)
        val failing = Scripted(listOf(
            TranscriptionOutcome.Failure(FailureKind.TERMINAL_INPUT, "no", 422, "mock")))
        val r = runner(failing, f)
        val failed = r.run(r.submit("content://x/1", "r.ogg"))
        assertEquals(JobState.FAILED, failed.state)

        clock += 10
        val retried = r.retry(failed.id)!!
        val ok = Scripted(listOf(TranscriptionOutcome.Success("second time", "apex-local")))
        val done = runner(ok, f).run(retried)

        assertEquals(JobState.COMPLETED, done.state)
        val published = lastDone()
        assertEquals(done.transcriptId, published.transcriptId)
        assertEquals(done.transcriptId, resolve(published))
        assertNull("the failed original produced no transcript", failed.transcriptId)
    }

    // ---- 4. the id survives process/Activity recreation ------------------

    @Test
    fun `process recreation does not lose the completion id`() = runBlocking {
        val r = runner(Scripted(listOf(TranscriptionOutcome.Success("kept", "apex-local"))),
                       audio("k.ogg", fill = 5))
        val job = r.run(r.submit("content://x/1", "k.ogg"))

        // The store is process-wide and outlives the Activity; a recreated
        // ViewModel re-reads exactly this.
        TranscriptionStore.setState(lastDone())
        val afterRecreation = TranscriptionStore.state.value
        assertTrue(afterRecreation is TranscriptionState.Done)
        assertEquals(job.transcriptId,
                     (afterRecreation as TranscriptionState.Done).transcriptId)
        // A fresh observer with an EMPTY history baseline -- which is exactly
        // what a recreated ViewModel starts with -- still resolves it.
        assertEquals(job.transcriptId, resolve(afterRecreation, fallback = null))
        TranscriptionStore.setState(TranscriptionState.Idle)
    }

    @Test
    fun `the id is reread from the job record, not held in memory`() = runBlocking {
        val r = runner(Scripted(listOf(TranscriptionOutcome.Success("persisted", "apex-local"))),
                       audio("p.ogg", fill = 6))
        val job = r.run(r.submit("content://x/1", "p.ogg"))
        // A brand-new JobStore over the same directory: what a restarted
        // process would see.
        val reopened = JobStore(File(tmp.root, "jobs"))
        assertEquals(job.transcriptId, reopened.get(job.id)!!.transcriptId)
    }

    // ---- the Processing screen can never be a dead end -------------------

    @Test
    fun `a resolved completion keeps the user off the processing screen`() {
        val done = TranscriptionState.Done("clip", "text", "record-1")
        assertFalse(isStrandedOnProcessing(done, "record-1"))
    }

    @Test
    fun `an unresolvable completion leaves processing rather than stranding`() {
        val done = TranscriptionState.Done("clip", "text", null)
        assertTrue(isStrandedOnProcessing(done, null))
    }

    @Test
    fun `idle on the processing screen always leaves`() {
        // This is literally the "No transcription in progress." state.
        assertTrue(isStrandedOnProcessing(TranscriptionState.Idle, null))
    }

    @Test
    fun `terminal failures are not stranded -- they have their own screens`() {
        assertFalse(isStrandedOnProcessing(
            TranscriptionState.Failed("c", "boom", "j1", true), null))
        assertFalse(isStrandedOnProcessing(
            TranscriptionState.Cancelled("c", "j1", true), null))
    }

    @Test
    fun `every running state stays on processing, where cancel works`() {
        listOf(
            TranscriptionState.Queued("c", 0),
            TranscriptionState.Staging("c"),
            TranscriptionState.Uploading("c", 0.5f),
            TranscriptionState.Decoding("c", 0.5f),
            TranscriptionState.Transcribing("c", 1, 3),
            TranscriptionState.Retrying("c", 1, 3)
        ).forEach {
            assertTrue("${it::class.simpleName} must be running", it.isRunning)
            assertFalse("${it::class.simpleName} must stay", isStrandedOnProcessing(it, null))
        }
    }

    @Test
    fun `no state both strands the user and disables the bottom bar`() {
        // The dead end was the conjunction: a screen with nothing to show AND
        // an inert nav bar. The bar is enabled exactly when nothing is
        // running, so a stranded state must always have an enabled bar.
        val all = listOf(
            TranscriptionState.Idle,
            TranscriptionState.Queued("c", 0),
            TranscriptionState.Staging("c"),
            TranscriptionState.Uploading("c", null),
            TranscriptionState.Decoding("c", 0f),
            TranscriptionState.Transcribing("c", 1, 2),
            TranscriptionState.Retrying("c", 1, 3),
            TranscriptionState.Done("c", "t", "record-1"),
            TranscriptionState.Done("c", "t", null),
            TranscriptionState.Failed("c", "boom"),
            TranscriptionState.Cancelled("c")
        )
        all.forEach { state ->
            val barEnabled = !state.isRunning          // AppNavHost.navEnabled
            val stranded = isStrandedOnProcessing(state, null)
            assertFalse(
                "${state::class.simpleName} strands the user with an inert bar",
                stranded && !barEnabled
            )
        }
    }
}
