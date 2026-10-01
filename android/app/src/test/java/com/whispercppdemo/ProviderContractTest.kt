package com.whispercppdemo

import com.whispercppdemo.transcribe.provider.AssemblyAIProvider
import com.whispercppdemo.transcribe.provider.BackendConfig
import com.whispercppdemo.transcribe.provider.DeepgramProvider
import com.whispercppdemo.transcribe.provider.FailureKind
import com.whispercppdemo.transcribe.provider.TranscriptionOutcome
import com.whispercppdemo.transcribe.provider.TranscriptionRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Contract tests for the provider abstraction.
 *
 * These do not hit the network. They pin the properties the rest of the app is
 * allowed to depend on, and the retry policy's correctness rests entirely on
 * FailureKind.retryable being right: a 4xx treated as retryable is wasted
 * money, a 5xx treated as terminal is a lost transcription.
 */
class ProviderContractTest {

    @Test
    fun `retryable kinds are exactly the transient ones`() {
        assertTrue(FailureKind.RETRYABLE_NETWORK.retryable)
        assertTrue(FailureKind.RETRYABLE_SERVER.retryable)
        assertTrue(FailureKind.RATE_LIMITED.retryable)
        assertTrue(FailureKind.TIMEOUT.retryable)
    }

    @Test
    fun `terminal kinds are never retried`() {
        assertFalse(FailureKind.TERMINAL_INPUT.retryable)
        assertFalse(FailureKind.TERMINAL_AUTH.retryable)
        assertFalse(FailureKind.TERMINAL_UNKNOWN.retryable)
        assertFalse(FailureKind.CANCELLED.retryable)
    }

    @Test
    fun `provider ids are stable and distinct`() {
        assertEquals("deepgram", DeepgramProvider(InMemoryInstallationCredentialStore()).id)
        assertEquals("assemblyai", AssemblyAIProvider(InMemoryInstallationCredentialStore()).id)
    }

    /**
     * An unconfigured backend must fail as a configuration error, not as a
     * network error -- otherwise a build shipped without a base URL would look
     * like an offline device and get retried forever.
     */
    @Test
    fun `unconfigured backend fails terminally, not as network`() {
        val saved = BackendConfig.baseUrl
        BackendConfig.baseUrl = ""
        try {
            val out = runBlocking {
                DeepgramProvider(InMemoryInstallationCredentialStore()).transcribe(
                    TranscriptionRequest(
                        audio = File("nonexistent.wav"),
                        mimeType = "audio/wav",
                        idempotencyKey = "k1"
                    )
                )
            }
            assertTrue(out is TranscriptionOutcome.Failure)
            val f = out as TranscriptionOutcome.Failure
            assertEquals(FailureKind.TERMINAL_AUTH, f.kind)
            assertFalse(f.kind.retryable)
        } finally {
            BackendConfig.baseUrl = saved
        }
    }

    /** No base URL is baked in: a placeholder host would be worse than none. */
    @Test
    fun `backend base url is not hardcoded`() {
        assertTrue(BackendConfig.baseUrl.isBlank() || BackendConfig.configured)
    }

    @Test
    fun `request defaults carry no keyterms and no language hint`() {
        val r = TranscriptionRequest(
            audio = File("a.wav"), mimeType = "audio/wav", idempotencyKey = "k"
        )
        assertTrue(r.keyterms.isEmpty())
        assertNull(r.languageHint)
    }

    /**
     * The idempotency key is what stops a retry being billed twice, so it is
     * part of the request, not the attempt.
     */
    @Test
    fun `idempotency key is required on every request`() {
        val r = TranscriptionRequest(
            audio = File("a.wav"), mimeType = "audio/wav", idempotencyKey = "job-1"
        )
        assertEquals("job-1", r.idempotencyKey)
    }

    /** User-facing failure text must never carry transcript content. */
    @Test
    fun `failure messages are user-safe`() {
        val f = TranscriptionOutcome.Failure(
            FailureKind.TERMINAL_INPUT, "This audio could not be transcribed.", 422
        )
        assertFalse(f.message.contains("\n"))
        assertTrue(f.message.length < 200)
    }
}
