package com.whispercppdemo

import com.whispercppdemo.jobs.FailureReason
import com.whispercppdemo.jobs.JobRunner
import com.whispercppdemo.jobs.JobState
import com.whispercppdemo.jobs.JobStore
import com.whispercppdemo.jobs.PreparedAudio
import com.whispercppdemo.media.AudioLimits
import com.whispercppdemo.transcribe.provider.FailureKind
import com.whispercppdemo.transcribe.provider.TranscriptionOutcome
import com.whispercppdemo.transcribe.provider.TranscriptionProvider
import com.whispercppdemo.transcribe.provider.TranscriptionRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * Phase C: enforced limits, content-hash dedupe, and retry-cost safety.
 *
 * The recurring assertion is `provider.calls` — every one of these protections
 * exists to stop a provider request, so counting requests is the only check
 * that actually proves the protection works.
 */
class CostProtectionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: JobStore
    private var clock = 1_000L

    @Before
    fun setUp() {
        store = JobStore(tmp.newFolder("jobs"))
        clock = 1_000L
    }

    private class CountingProvider(
        private val outcome: TranscriptionOutcome =
            TranscriptionOutcome.Success("ok", "mock")
    ) : TranscriptionProvider {
        override val id = "mock"
        override val displayName = "Mock"
        var calls = 0
        override suspend fun transcribe(
            request: TranscriptionRequest,
            onProgress: (Float) -> Unit
        ): TranscriptionOutcome {
            calls++
            onProgress(1f)
            return outcome
        }
    }

    private fun file(name: String, bytes: ByteArray): File =
        File(tmp.root, name).apply { writeBytes(bytes) }

    private fun runner(
        p: TranscriptionProvider,
        f: File,
        transcriptId: String = "t-1"
    ) = JobRunner(
        store = store, provider = p,
        prepare = { PreparedAudio(f, "audio/wav") },
        persistTranscript = { _, _, _ -> transcriptId },
        now = { clock }, sleep = { }
    )

    // ---- 1. hard limits ---------------------------------------------------

    @Test
    fun `limit values match the enforced contract`() {
        assertEquals(3600L, AudioLimits.MAX_DURATION_SECONDS)
        assertEquals(60L, AudioLimits.MAX_DURATION_MINUTES)
        assertEquals(104_857_600L, AudioLimits.MAX_UPLOAD_BYTES)
    }

    @Test
    fun `sixty minutes exactly is accepted and one second more is not`() {
        assertEquals(AudioLimits.Verdict.Ok, AudioLimits.check(1000L, 59L * 60 + 59))
        assertEquals(AudioLimits.Verdict.Ok, AudioLimits.check(1000L, 60L * 60))
        assertEquals(AudioLimits.Verdict.TooLong(3601L), AudioLimits.check(1000L, 60L * 60 + 1))
    }

    @Test
    fun `over-duration message names the 60 minute limit`() {
        assertEquals(
            "That recording is longer than 60 minutes. Try splitting it into shorter parts.",
            AudioLimits.message(AudioLimits.check(1000L, 3601L))
        )
    }

    @Test
    fun `oversized file is rejected before any provider call`() = runBlocking {
        val p = CountingProvider()
        // Sparse file: length() exceeds the cap without allocating 100 MB.
        val big = File(tmp.root, "big.wav")
        RandomAccessFile(big, "rw").use { it.setLength(AudioLimits.MAX_UPLOAD_BYTES + 1) }
        val r = runner(p, big)
        val job = r.run(r.submit("content://big", "big.wav"))
        assertEquals(JobState.FAILED, job.state)
        assertEquals(FailureReason.AUDIO_REJECTED, job.failureReason)
        assertEquals("provider must never be called", 0, p.calls)
    }

    @Test
    fun `over-duration audio is rejected by the limit check`() {
        val v = AudioLimits.check(1000L, AudioLimits.MAX_DURATION_SECONDS + 1)
        assertTrue(v is AudioLimits.Verdict.TooLong)
        assertNotNull(AudioLimits.message(v))
    }

    @Test
    fun `unknown duration is not treated as a violation`() {
        assertEquals(AudioLimits.Verdict.Ok, AudioLimits.check(1000L, -1))
    }

    @Test
    fun `empty audio is rejected`() {
        assertTrue(AudioLimits.check(0L) is AudioLimits.Verdict.Empty)
    }

    @Test
    fun `limit messages are user-safe and actionable`() {
        listOf(
            AudioLimits.check(0L),
            AudioLimits.check(AudioLimits.MAX_UPLOAD_BYTES + 1),
            AudioLimits.check(10L, AudioLimits.MAX_DURATION_SECONDS + 1)
        ).forEach { v ->
            val m = AudioLimits.message(v)!!
            assertTrue(m.length in 10..140)
            assertTrue(!m.contains("Exception") && !m.contains("null"))
        }
        assertNull(AudioLimits.message(AudioLimits.Verdict.Ok))
    }

    // ---- 2. content-hash dedupe ------------------------------------------

    @Test
    fun `duplicate completed audio reuses the transcript without a provider call`() =
        runBlocking {
            val audio = file("a.wav", ByteArray(2048) { 7 })

            val p1 = CountingProvider()
            val first = runner(p1, audio, "t-first").let { r ->
                r.run(r.submit("content://one", "a.wav"))
            }
            assertEquals(JobState.COMPLETED, first.state)
            assertEquals(1, p1.calls)
            assertNotNull("hash must be recorded", first.contentHash)

            // Same bytes arriving by a different URI must not be paid for twice.
            val p2 = CountingProvider()
            val second = runner(p2, audio, "t-second").let { r ->
                r.run(r.submit("content://two", "a.wav"))
            }
            assertEquals(JobState.COMPLETED, second.state)
            assertEquals("no second provider request", 0, p2.calls)
            assertEquals("reuses the existing transcript", "t-first", second.transcriptId)
        }

    @Test
    fun `different audio is not deduped`() = runBlocking {
        val p = CountingProvider()
        runner(p, file("x.wav", ByteArray(512) { 1 })).let { r ->
            r.run(r.submit("content://x", "x.wav"))
        }
        runner(p, file("y.wav", ByteArray(512) { 2 })).let { r ->
            r.run(r.submit("content://y", "y.wav"))
        }
        assertEquals(2, p.calls)
    }

    @Test
    fun `terminal failure still allows a deliberate re-transcribe`() = runBlocking {
        val audio = file("a.wav", ByteArray(1024) { 3 })
        val failing = CountingProvider(
            TranscriptionOutcome.Failure(FailureKind.TERMINAL_INPUT, "bad", 422, "mock")
        )
        val failed = runner(failing, audio).let { r ->
            r.run(r.submit("content://a", "a.wav"))
        }
        assertEquals(JobState.FAILED, failed.state)

        // The user tries again on purpose. Dedupe must not block this.
        val ok = CountingProvider()
        val retry = runner(ok, audio, "t-retry").let { r ->
            r.run(r.submit("content://a", "a.wav"))
        }
        assertEquals(JobState.COMPLETED, retry.state)
        assertEquals("re-transcribe must reach the provider", 1, ok.calls)
    }

    @Test
    fun `cancelled audio can be re-submitted`() = runBlocking {
        val audio = file("a.wav", ByteArray(256) { 9 })
        val r1 = runner(CountingProvider(), audio)
        val j = r1.submit("content://a", "a.wav")
        r1.cancel(j.id)

        val p = CountingProvider()
        val again = runner(p, audio, "t-2").let { r ->
            r.run(r.submit("content://a", "a.wav"))
        }
        assertEquals(JobState.COMPLETED, again.state)
        assertEquals(1, p.calls)
    }

    // ---- 5. retry / cost safety ------------------------------------------

    @Test
    fun `provider call count never exceeds the attempt bound`() = runBlocking {
        val cases = mapOf(
            FailureKind.TERMINAL_INPUT to 1,
            FailureKind.TERMINAL_AUTH to 1,
            FailureKind.TERMINAL_UNKNOWN to 1,
            FailureKind.RATE_LIMITED to 3,
            FailureKind.RETRYABLE_SERVER to 3,
            FailureKind.RETRYABLE_NETWORK to 3,
            FailureKind.TIMEOUT to 3
        )
        for ((kind, expected) in cases) {
            store = JobStore(tmp.newFolder("jobs-$kind"))
            val p = CountingProvider(
                TranscriptionOutcome.Failure(kind, "x", null, "mock")
            )
            val r = runner(p, file("c-$kind.wav", ByteArray(64) { 1 }))
            r.run(r.submit("content://c", "c.wav"))
            assertEquals("$kind should attempt $expected time(s)", expected, p.calls)
        }
    }
}
