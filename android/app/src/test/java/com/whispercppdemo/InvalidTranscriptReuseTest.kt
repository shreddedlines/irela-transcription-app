package com.whispercppdemo

import com.whispercppdemo.jobs.JobRunner
import com.whispercppdemo.jobs.JobState
import com.whispercppdemo.jobs.JobStore
import com.whispercppdemo.jobs.PreparedAudio
import com.whispercppdemo.transcribe.TranscriptValidator
import com.whispercppdemo.transcribe.provider.TranscriptionOutcome
import com.whispercppdemo.transcribe.provider.TranscriptionProvider
import com.whispercppdemo.transcribe.provider.TranscriptionRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A transcript saved before validation existed must never be reused.
 *
 * The device has one such record: its text is just ".". Content-hash dedupe
 * would hand it to a new share of the same audio, and the completion would
 * then report "The transcript could not be opened." The rule: an invalid twin
 * is not reusable, so the audio is transcribed afresh -- and the old record is
 * neither deleted nor reinterpreted as valid.
 */
class InvalidTranscriptReuseTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: JobStore
    /** Stand-in for History: record id -> text, as the repository would load it. */
    private val history = mutableMapOf<String, String>()
    private var clock = 1_000L

    @Before
    fun setUp() {
        store = JobStore(tmp.newFolder("jobs"))
        history.clear()
    }

    private fun counting(text: String) = object : TranscriptionProvider {
        override val id = "mock"
        override val displayName = "Mock"
        var calls = 0
        override suspend fun transcribe(
            request: TranscriptionRequest, onProgress: (Float) -> Unit
        ): TranscriptionOutcome { calls++; return TranscriptionOutcome.Success(text, "mock") }
    }

    private fun runner(p: TranscriptionProvider, audio: File) = JobRunner(
        store = store, provider = p,
        prepare = { PreparedAudio(audio, "audio/ogg") },
        persistTranscript = { _, text, _ -> "record-${history.size + 1}".also { history[it] = text } },
        now = { clock++ }, sleep = { },
        // Exactly what TranscriptionService passes.
        isReusableTranscript = { id -> TranscriptValidator.isMeaningful(history[id]) }
    )

    private fun audio() = File(tmp.root, "same.ogg").apply { writeBytes(ByteArray(2048) { 9 }) }

    /** A COMPLETED job whose record is the legacy "." -- as on the device. */
    private fun legacyInvalidTwin(audio: File): String {
        val first = runner(counting("placeholder"), audio)
        val done = runBlocking { first.run(first.submit("content://old", "same.ogg")) }
        history[done.transcriptId!!] = "."            // what the pre-validation build stored
        return done.transcriptId!!
    }

    @Test
    fun `an invalid legacy transcript is not reused -- the audio is transcribed afresh`() = runBlocking {
        val a = audio()
        val legacyId = legacyInvalidTwin(a)

        val fresh = counting("हाँ, meeting साढ़े सात बजे है")
        val r = runner(fresh, a)
        val job = r.run(r.submit("content://new", "same.ogg"))

        assertEquals("the engine must run instead of reusing '.'", 1, fresh.calls)
        assertEquals(JobState.COMPLETED, job.state)
        assertNotEquals(legacyId, job.transcriptId)
        assertEquals("हाँ, meeting साढ़े सात बजे है", history[job.transcriptId])
    }

    @Test
    fun `the legacy record is left in place, unchanged`() = runBlocking {
        val a = audio()
        val legacyId = legacyInvalidTwin(a)
        val r = runner(counting("real words"), a)
        r.run(r.submit("content://new", "same.ogg"))
        assertEquals("not deleted, not rewritten", ".", history[legacyId])
    }

    @Test
    fun `a valid twin is still reused -- dedupe is otherwise unchanged`() = runBlocking {
        val a = audio()
        val first = runner(counting("valid transcript"), a)
        val original = first.run(first.submit("content://old", "same.ogg"))

        val second = counting("must not run")
        val r = runner(second, a)
        val dup = r.run(r.submit("content://new", "same.ogg"))
        assertEquals(0, second.calls)
        assertEquals(original.transcriptId, dup.transcriptId)
    }

    @Test
    fun `a twin whose record is missing is not reused`() = runBlocking {
        val a = audio()
        val legacyId = legacyInvalidTwin(a)
        history.remove(legacyId)                      // record deleted by the user
        val fresh = counting("new text")
        val r = runner(fresh, a)
        r.run(r.submit("content://new", "same.ogg"))
        assertEquals(1, fresh.calls)
    }
}
