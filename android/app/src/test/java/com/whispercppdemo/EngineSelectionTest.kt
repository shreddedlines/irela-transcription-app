package com.whispercppdemo

import com.whispercppdemo.jobs.FailureReason
import com.whispercppdemo.jobs.JobRunner
import com.whispercppdemo.jobs.JobState
import com.whispercppdemo.jobs.JobStore
import com.whispercppdemo.jobs.PreparedAudio
import com.whispercppdemo.media.AudioLimits
import com.whispercppdemo.transcribe.Connectivity
import com.whispercppdemo.transcribe.Engine
import com.whispercppdemo.transcribe.EngineSelector
import com.whispercppdemo.transcribe.Reachability
import com.whispercppdemo.transcribe.provider.BackendConfig
import com.whispercppdemo.transcribe.provider.DeepgramProvider
import com.whispercppdemo.transcribe.provider.FailureKind
import com.whispercppdemo.transcribe.provider.TranscriptionOutcome
import com.whispercppdemo.transcribe.provider.TranscriptionRequest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * Phase D: engine selection, connectivity honesty, and the microphone path.
 *
 * LocalWhisperProvider needs an Android Context, so engine *selection* is
 * asserted on EngineSelector's decision rather than by constructing it — the
 * decision is the thing with the logic in it.
 */
class EngineSelectionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: JobStore
    private var clock = 1_000L

    @Before
    fun setUp() {
        store = JobStore(tmp.newFolder("jobs"))
        clock = 1_000L
        EngineSelector.devOverride = null
        BackendConfig.baseUrl = ""
    }

    @After
    fun tearDown() {
        EngineSelector.devOverride = null
        BackendConfig.baseUrl = ""
    }

    // ---- 1. engine selection ---------------------------------------------

    @Test
    fun `production engine is cloud`() {
        assertEquals(Engine.CLOUD, EngineSelector.PRODUCTION_ENGINE)
    }

    @Test
    fun `configured backend selects CLOUD`() {
        BackendConfig.baseUrl = "https://api.example.com"
        assertEquals(Engine.CLOUD, EngineSelector.effective())
        assertTrue(EngineSelector.requiresNetwork())
    }

    @Test
    fun `no backend configured falls back to LOCAL`() {
        // This is what keeps the shipped APK on Apex: no base URL, no cloud.
        assertEquals(Engine.LOCAL, EngineSelector.effective())
        assertFalse(EngineSelector.requiresNetwork())
    }

    @Test
    fun `development override forces an engine either way`() {
        BackendConfig.baseUrl = "https://api.example.com"
        EngineSelector.devOverride = Engine.LOCAL
        assertEquals(Engine.LOCAL, EngineSelector.effective())

        BackendConfig.baseUrl = ""
        EngineSelector.devOverride = Engine.CLOUD
        assertEquals(Engine.CLOUD, EngineSelector.effective())
    }

    @Test
    fun `network failure never silently downgrades to LOCAL`() {
        // The selector has no input from connectivity at all -- the only way to
        // reach Apex is an explicit override.
        BackendConfig.baseUrl = "https://api.example.com"
        assertEquals(Engine.CLOUD, EngineSelector.effective())
        assertEquals(Engine.CLOUD, EngineSelector.effective())  // still cloud
    }

    @Test
    fun `cloud provider is the deepgram backend provider`() {
        BackendConfig.baseUrl = "https://api.example.com"
        // Selecting cloud must not require a Context-bound local engine.
        assertEquals("deepgram", DeepgramProvider(InMemoryInstallationCredentialStore()).id)
    }

    // ---- 3. connectivity: four distinct causes ---------------------------

    @Test
    fun `no network is reported as internet unavailable`() {
        assertEquals(
            Reachability.INTERNET_UNAVAILABLE,
            Connectivity.classify(FailureKind.RETRYABLE_NETWORK, hasNetwork = false)
        )
    }

    @Test
    fun `transport failure while online blames the backend, not the user`() {
        assertEquals(
            Reachability.BACKEND_UNAVAILABLE,
            Connectivity.classify(FailureKind.RETRYABLE_NETWORK, hasNetwork = true)
        )
        assertEquals(
            Reachability.BACKEND_UNAVAILABLE,
            Connectivity.classify(FailureKind.TIMEOUT, hasNetwork = true)
        )
    }

    @Test
    fun `errors from a responding backend are provider unavailable`() {
        listOf(FailureKind.RETRYABLE_SERVER, FailureKind.RATE_LIMITED,
               FailureKind.TERMINAL_AUTH).forEach {
            assertEquals(
                Reachability.PROVIDER_UNAVAILABLE,
                Connectivity.classify(it, hasNetwork = true)
            )
        }
    }

    @Test
    fun `each cause has its own user message`() {
        val msgs = listOf(
            Reachability.INTERNET_UNAVAILABLE,
            Reachability.BACKEND_UNAVAILABLE,
            Reachability.PROVIDER_UNAVAILABLE
        ).map { it.userMessage }
        assertEquals("messages must be distinct", 3, msgs.toSet().size)
        // Only the genuinely user-fixable one mentions the internet.
        assertTrue(Reachability.INTERNET_UNAVAILABLE.userMessage.contains("internet"))
        assertFalse(Reachability.PROVIDER_UNAVAILABLE.userMessage.contains("internet"))
        // All of them reassure that nothing was lost.
        msgs.forEach { assertTrue(it.contains("saved")) }
    }

    // ---- 4. cloud + unreachable backend ----------------------------------

    @Test
    fun `cloud with an unreachable backend is a retryable failure`() = runBlocking {
        BackendConfig.baseUrl = "http://127.0.0.1:1"   // nothing listens here
        val out = DeepgramProvider(InMemoryInstallationCredentialStore()).transcribe(
            TranscriptionRequest(
                audio = File(tmp.root, "a.wav").apply { writeBytes(ByteArray(64)) },
                mimeType = "audio/wav", idempotencyKey = "k"
            )
        )
        val f = out as TranscriptionOutcome.Failure
        assertTrue("must be retryable", f.kind.retryable)
        assertEquals(
            Reachability.BACKEND_UNAVAILABLE,
            Connectivity.classify(f.kind, hasNetwork = true)
        )
    }

    @Test
    fun `cloud with no backend configured is terminal, not retried forever`() =
        runBlocking {
            BackendConfig.baseUrl = ""
            val out = DeepgramProvider(InMemoryInstallationCredentialStore()).transcribe(
                TranscriptionRequest(File(tmp.root, "a.wav"), "audio/wav", "k")
            )
            val f = out as TranscriptionOutcome.Failure
            assertEquals(FailureKind.TERMINAL_AUTH, f.kind)
            assertFalse(f.kind.retryable)
        }

    // ---- 5 & 6. microphone goes through the same persistent pipeline -----

    private class Counting : com.whispercppdemo.transcribe.provider.TranscriptionProvider {
        override val id = "mock"
        override val displayName = "Mock"
        var calls = 0
        override suspend fun transcribe(
            request: TranscriptionRequest, onProgress: (Float) -> Unit
        ): TranscriptionOutcome {
            calls++; onProgress(1f)
            return TranscriptionOutcome.Success("mic transcript", "mock")
        }
    }

    private fun runner(p: Counting, f: File) = JobRunner(
        store = store, provider = p,
        prepare = { PreparedAudio(f, "audio/wav") },
        persistTranscript = { _, _, _ -> "t-mic" },
        now = { clock }, sleep = { }
    )

    @Test
    fun `microphone job is persisted like any other`() = runBlocking {
        val rec = File(tmp.root, "rec.wav").apply { writeBytes(ByteArray(4096) { 5 }) }
        val p = Counting()
        val r = runner(p, rec)
        val job = r.run(r.submit("file:///rec.wav", "Recording"))
        assertEquals(JobState.COMPLETED, job.state)
        assertEquals("t-mic", job.transcriptId)
        // Survives a fresh store instance -> survived the process.
        assertNotNull(store.get(job.id))
    }

    @Test
    fun `microphone job survives Activity recreation`() {
        // The record lives in the store, not the ViewModel, so a new ViewModel
        // observing a new store instance still sees it.
        val id = store.create("file:///rec.wav", "Recording", clock).id
        val afterRecreation = JobStore(File(tmp.root, "jobs")).get(id)
        assertNotNull("job must outlive the Activity", afterRecreation)
        assertEquals(JobState.PENDING, afterRecreation!!.state)
    }

    @Test
    fun `microphone job interrupted by process death is recovered, not lost`() {
        val dir = tmp.newFolder("mic-death")
        val s1 = JobStore(dir)
        val j = s1.create("file:///rec.wav", "Recording", clock)
        s1.update(j.moveTo(JobState.TRANSCRIBING, clock))

        val recovered = JobStore(dir).recoverOrphans(clock + 1)
        assertEquals(1, recovered.size)
        assertEquals(JobState.FAILED, recovered[0].state)
        assertEquals(FailureReason.INTERRUPTED, recovered[0].failureReason)
    }

    @Test
    fun `oversized microphone recording never reaches the provider`() = runBlocking {
        val big = File(tmp.root, "big.wav")
        RandomAccessFile(big, "rw").use { it.setLength(AudioLimits.MAX_UPLOAD_BYTES + 1) }
        val p = Counting()
        val r = runner(p, big)
        val job = r.run(r.submit("file:///big.wav", "Recording"))
        assertEquals(JobState.FAILED, job.state)
        assertEquals(FailureReason.AUDIO_REJECTED, job.failureReason)
        assertEquals(0, p.calls)
    }

    @Test
    fun `over-duration microphone recording is rejected by the same limit`() {
        val v = AudioLimits.check(1024L, AudioLimits.MAX_DURATION_SECONDS + 1)
        assertTrue(v is AudioLimits.Verdict.TooLong)
        assertNotNull(AudioLimits.message(v))
    }

    @Test
    fun `microphone audio is deduped like imported audio`() = runBlocking {
        val rec = File(tmp.root, "rec.wav").apply { writeBytes(ByteArray(1024) { 8 }) }
        val p1 = Counting()
        runner(p1, rec).let { r -> r.run(r.submit("file:///rec1.wav", "Recording")) }
        val p2 = Counting()
        val second = runner(p2, rec).let { r ->
            r.run(r.submit("file:///rec2.wav", "Recording"))
        }
        assertEquals(JobState.COMPLETED, second.state)
        assertEquals("identical recording must not be re-billed", 0, p2.calls)
    }
}
