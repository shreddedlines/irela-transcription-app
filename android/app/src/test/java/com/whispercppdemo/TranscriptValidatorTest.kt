package com.whispercppdemo

import com.whispercppdemo.jobs.FailureReason
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Final ASR output must contain something meaningful to be saved.
 *
 * The device bug: a very short clip transcribed to "." and was stored as a
 * successful one-word transcript, because the gate only checked isBlank().
 */
class TranscriptValidatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun ok(s: String?) = assertTrue("must accept: '$s'", TranscriptValidator.isMeaningful(s))
    private fun no(s: String?) = assertFalse("must reject: '$s'", TranscriptValidator.isMeaningful(s))

    // ---- rejected ---------------------------------------------------------

    @Test fun `null is rejected`() = no(null)
    @Test fun `empty is rejected`() = no("")
    @Test fun `whitespace-only is rejected`() { no("   "); no("\n\t  \r\n"); no("  ") }

    @Test
    fun `punctuation-only is rejected`() {
        no("."); no("..."); no("…"); no(",.;:!?"); no("। ॥")   // Devanagari danda too
    }

    @Test
    fun `symbols-only is rejected`() {
        no("—"); no("-"); no("♪ ♪"); no("*"); no("()[]{}"); no("…—…")
    }

    // ---- accepted ---------------------------------------------------------

    @Test fun `english is accepted`() { ok("Namaste"); ok("a") }
    @Test fun `hindi is accepted`() { ok("नमस्ते"); ok("क") }
    @Test fun `numbers are accepted`() { ok("123"); ok("0"); ok("१२३") }   // Devanagari digits too

    @Test
    fun `mixed hindi and english is accepted`() {
        ok("जो companies हैं वह अपनी jobs डालती हैं")
        ok("Meeting साढ़े सात बजे")
    }

    @Test
    fun `real speech wrapped in punctuation is accepted`() {
        ok("... हाँ ..."); ok("— okay —"); ok("\"5\"")
    }

    @Test
    fun `other scripts are not filtered out`() {
        // No language-specific rules: any script's letters count.
        ok("வணக்கம்"); ok("ನಮಸ್ಕಾರ"); ok("مرحبا"); ok("你好"); ok("Привет")
    }

    // ---- wired into the real pipeline ------------------------------------

    private fun runOutcome(text: String): Pair<com.whispercppdemo.jobs.JobRecord, Int> = runBlocking {
        val store = JobStore(tmp.newFolder())
        val audio = File(tmp.root, "a${System.nanoTime()}.ogg").apply { writeBytes(ByteArray(512) { 3 }) }
        var persisted = 0
        val runner = JobRunner(
            store = store,
            provider = object : TranscriptionProvider {
                override val id = "mock"
                override val displayName = "Mock"
                override suspend fun transcribe(
                    request: TranscriptionRequest, onProgress: (Float) -> Unit
                ) = TranscriptionOutcome.Success(text, "mock")
            },
            prepare = { PreparedAudio(audio, "audio/ogg") },
            persistTranscript = { _, _, _ -> persisted++; "record" },
            sleep = { }
        )
        runner.run(runner.submit("content://x", "a.ogg")) to persisted
    }

    @Test
    fun `a punctuation-only result fails as no speech and writes nothing`() {
        listOf(".", "...", "—", "   ").forEach { text ->
            val (job, persisted) = runOutcome(text)
            assertEquals("'$text'", JobState.FAILED, job.state)
            assertEquals(FailureReason.EMPTY_TRANSCRIPT, job.failureReason)
            assertEquals("'$text' must not reach History", 0, persisted)
        }
    }

    @Test
    fun `the rejection maps to the existing no-speech message`() {
        assertEquals("No speech was found in this audio.",
                     com.whispercppdemo.jobs.failureMessageFor(FailureReason.EMPTY_TRANSCRIPT))
    }

    @Test
    fun `a meaningful result is still saved`() {
        listOf("नमस्ते", "123", "Namaste").forEach { text ->
            val (job, persisted) = runOutcome(text)
            assertEquals(JobState.COMPLETED, job.state)
            assertEquals(1, persisted)
        }
    }
}
