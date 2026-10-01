package com.whispercppdemo

import com.whispercppdemo.capture.BlockReason
import com.whispercppdemo.capture.CaptureDecision
import com.whispercppdemo.capture.CaptureEnvironment
import com.whispercppdemo.capture.CaptureFeasibility
import com.whispercppdemo.capture.CaptureMode
import com.whispercppdemo.capture.CaptureNotice
import com.whispercppdemo.capture.CapturePolicy
import com.whispercppdemo.capture.Support
import com.whispercppdemo.jobs.FailureReason
import com.whispercppdemo.jobs.JobRunner
import com.whispercppdemo.jobs.JobState
import com.whispercppdemo.jobs.JobStore
import com.whispercppdemo.jobs.RecordingStager
import com.whispercppdemo.media.AudioLimits
import com.whispercppdemo.media.WavInfo
import com.whispercppdemo.recorder.CaptureConditions
import com.whispercppdemo.recorder.CaptureUnavailableException
import com.whispercppdemo.recorder.FinishReason
import com.whispercppdemo.recorder.FinishedRecordingSink
import com.whispercppdemo.recorder.PcmSource
import com.whispercppdemo.recorder.Recorder
import com.whispercppdemo.recorder.RecorderState
import com.whispercppdemo.recorder.RecordingController
import com.whispercppdemo.recorder.RecordingFiles
import com.whispercppdemo.recorder.RecordingForeground
import com.whispercppdemo.transcribe.provider.FailureKind
import com.whispercppdemo.transcribe.provider.TranscriptionOutcome
import com.whispercppdemo.transcribe.provider.TranscriptionProvider
import com.whispercppdemo.transcribe.provider.TranscriptionRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import java.io.RandomAccessFile

/**
 * "Transcribe conversation": capture decisions, the recording controller's
 * lifecycle, and the handoff into the same job pipeline, all on the JVM with a
 * generated audio source. What Android actually does on the device is recorded
 * separately (CaptureDeviceTest and the feasibility table).
 */
class ConversationCaptureTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        cacheDir = tmp.newFolder("cache")
    }

    @After
    fun tearDown() = scope.cancel()

    // ---- fakes -----------------------------------------------------------------------------

    private class Source(
        override val bufferSamples: Int = 800,
        private val paceMs: Long = 2,
        var failAfterSamples: Long = Long.MAX_VALUE,
        val failOnStart: Boolean = false
    ) : PcmSource {
        @Volatile var produced = 0L
        override fun start() { if (failOnStart) throw CaptureUnavailableException("microphone busy") }
        override fun stop() {}
        override fun release() {}
        override fun read(buffer: ShortArray): Int {
            if (paceMs > 0) Thread.sleep(paceMs)
            if (produced >= failAfterSamples) return -3                    // AudioRecord.ERROR_INVALID_OPERATION
            buffer.fill(2000); produced += buffer.size
            return buffer.size
        }
    }

    private class Foreground(private val allow: Boolean = true) : RecordingForeground {
        var starts = mutableListOf<Pair<CaptureMode, Boolean>>()
        var stops = 0
        override fun start(mode: CaptureMode, conversation: Boolean): Boolean {
            starts += mode to conversation; return allow
        }
        override fun stop() { stops++ }
    }

    private class Conditions : CaptureConditions {
        override val callActive = MutableStateFlow(false)
        override val silenced = MutableStateFlow(false)
        var watched: Int? = null
        override fun watch(audioSessionId: Int?) { watched = audioSessionId }
        override fun unwatch() {}
    }

    private class Sink : FinishedRecordingSink {
        val received = mutableListOf<Triple<File, FinishReason, Boolean>>()
        override fun accept(file: File, reason: FinishReason, conversation: Boolean) {
            received += Triple(file, reason, conversation)
        }
    }

    private class Env(var mic: Boolean = true, var call: Boolean = false, override val sdkInt: Int = 36) : CaptureEnvironment {
        override fun hasMicrophonePermission() = mic
        override fun isCallActive() = call
    }

    private fun controller(source: PcmSource, fg: Foreground = Foreground(), cond: Conditions = Conditions(),
                           sink: Sink = Sink(), maxSamples: Long = com.whispercppdemo.recorder.MAX_RECORDING_SAMPLES) =
        RecordingController(Recorder({ source }, maxSamples), fg, cond, sink, scope)

    private fun waitUntil(ms: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(5)
        assertTrue("condition not reached", cond())
    }

    // ---- normal conversation recording ------------------------------------------------------

    @Test
    fun `normal conversation recording starts, streams and stops into a finished WAV`() = runBlocking {
        val fg = Foreground()
        val c = controller(Source(), fg)
        val target = RecordingFiles.newTarget(cacheDir)
        assertTrue(c.start(target, CaptureMode.MICROPHONE, conversation = true))
        assertEquals(listOf(CaptureMode.MICROPHONE to true), fg.starts)
        waitUntil { c.recordedMillis.value >= 500 }
        assertEquals(RecorderState.RECORDING, c.state.value)
        assertTrue(c.session.value!!.conversation)
        waitUntil { c.notices.value == listOf(CaptureNotice.MICROPHONE_ONLY) }

        val file = c.stop()
        assertEquals(target, file)
        assertEquals(RecorderState.IDLE, c.state.value)
        assertEquals(1, fg.stops)
        assertNull(c.session.value)
        assertTrue(WavInfo.read(file!!).dataBytes > 0)
        assertFalse(RecordingFiles.inProgressFor(target).exists())
    }

    @Test
    fun `a second start while recording is refused, not a second session`() = runBlocking {
        val c = controller(Source())
        assertTrue(c.start(RecordingFiles.newTarget(cacheDir), CaptureMode.MICROPHONE, true))
        assertFalse(c.start(RecordingFiles.newTarget(cacheDir), CaptureMode.MICROPHONE, true))
        c.cancel()
    }

    @Test
    fun `pause and resume keep one session and one file`() = runBlocking {
        val c = controller(Source())
        val target = RecordingFiles.newTarget(cacheDir)
        c.start(target, CaptureMode.MICROPHONE, true)
        waitUntil { c.recordedMillis.value >= 300 }
        c.pause()
        assertEquals(RecorderState.PAUSED, c.state.value)
        Thread.sleep(100)
        val frozen = c.recordedMillis.value
        Thread.sleep(200)
        assertEquals(frozen, c.recordedMillis.value)
        c.resume()
        waitUntil { c.recordedMillis.value >= frozen + 300 }
        assertEquals(target, c.stop())
    }

    @Test
    fun `cancel deletes the partial and hands nothing on`() = runBlocking {
        val fg = Foreground(); val sink = Sink()
        val c = controller(Source(), fg, sink = sink)
        val owner = mutableListOf<File?>()
        c.uiOwner = { f, _, _ -> owner += f }
        val target = RecordingFiles.newTarget(cacheDir)
        c.start(target, CaptureMode.MICROPHONE, true)
        waitUntil { c.recordedMillis.value >= 200 }
        c.cancel()
        assertTrue(cacheDir.listFiles()!!.isEmpty())
        assertTrue(sink.received.isEmpty() && owner.isEmpty())
        assertEquals(1, fg.stops)
    }

    // ---- 60-minute boundary ----------------------------------------------------------------------

    @Test
    fun `reaching the limit with no screen attached still creates a job, exactly once`() = runBlocking {
        val sink = Sink()
        val c = controller(Source(bufferSamples = 1600, paceMs = 0), sink = sink, maxSamples = 48_000)  // 3 s
        val target = RecordingFiles.newTarget(cacheDir)
        c.start(target, CaptureMode.MICROPHONE, true)
        waitUntil { sink.received.isNotEmpty() }
        Thread.sleep(200)
        assertEquals(1, sink.received.size)
        val (file, reason, conversation) = sink.received.single()
        assertEquals(target, file)
        assertEquals(FinishReason.LIMIT_REACHED, reason)
        assertTrue(conversation)
        assertEquals(96_000L, WavInfo.read(file).dataBytes)                       // exactly the cap
        assertEquals(RecorderState.IDLE, c.state.value)
    }

    @Test
    fun `reaching the limit with a screen attached goes to the screen, not the sink`() = runBlocking {
        val sink = Sink()
        val c = controller(Source(bufferSamples = 1600, paceMs = 0), sink = sink, maxSamples = 32_000)
        val owner = mutableListOf<Pair<File?, FinishReason>>()
        c.uiOwner = { f, r, _ -> owner += f to r }
        c.start(RecordingFiles.newTarget(cacheDir), CaptureMode.MICROPHONE, true)
        waitUntil { owner.isNotEmpty() }
        assertEquals(FinishReason.LIMIT_REACHED, owner.single().second)
        assertTrue(sink.received.isEmpty())
    }

    @Test
    fun `the real limit is 3_600_000 ms`() {
        assertEquals(AudioLimits.MAX_DURATION_MS * 16, com.whispercppdemo.recorder.MAX_RECORDING_SAMPLES)
    }

    // ---- unavailable input ----------------------------------------------------------------------

    @Test
    fun `microphone unavailable at start is a visible failure with nothing recorded`() = runBlocking {
        val sink = Sink()
        val c = controller(Source(failOnStart = true), sink = sink)
        val owner = mutableListOf<Pair<File?, FinishReason>>()
        c.uiOwner = { f, r, _ -> owner += f to r }
        c.start(RecordingFiles.newTarget(cacheDir), CaptureMode.MICROPHONE, true)
        waitUntil { owner.isNotEmpty() }
        assertEquals(null to FinishReason.CAPTURE_FAILED, owner.single())
        assertTrue(c.recorder.failure.value is CaptureUnavailableException)
        assertTrue(cacheDir.listFiles()!!.isEmpty())                               // no partial, no file
        assertTrue(sink.received.isEmpty())
        assertEquals(RecorderState.IDLE, c.state.value)
    }

    @Test
    fun `audio source lost mid-recording keeps what was captured`() = runBlocking {
        val sink = Sink()
        val c = controller(Source(failAfterSamples = 24_000), sink = sink)
        val target = RecordingFiles.newTarget(cacheDir)
        c.start(target, CaptureMode.MICROPHONE, true)
        waitUntil { sink.received.isNotEmpty() }
        val (file, reason, _) = sink.received.single()
        assertEquals(FinishReason.CAPTURE_FAILED, reason)
        assertTrue(WavInfo.read(file).dataBytes >= 48_000)                        // the audio survived
    }

    @Test
    fun `android refusing background recording is shown, and recording continues visibly`() = runBlocking {
        val c = controller(Source(), Foreground(allow = false))
        c.start(RecordingFiles.newTarget(cacheDir), CaptureMode.MICROPHONE, true)
        waitUntil { CaptureNotice.BACKGROUND_NOT_ALLOWED in c.notices.value }
        waitUntil { c.recordedMillis.value > 200 }
        assertNotNull(c.stop())
    }

    // ---- capture decisions ------------------------------------------------------------------------

    @Test
    fun `microphone permission denied blocks capture`() {
        assertEquals(CaptureDecision.Blocked(BlockReason.MICROPHONE_PERMISSION),
                     CapturePolicy.decide(CaptureMode.MICROPHONE, Env(mic = false)))
    }

    @Test
    fun `denied MediaProjection consent is a safe blocked state`() {
        assertEquals(CaptureDecision.NeedsSystemConsent, CapturePolicy.decide(CaptureMode.PLAYBACK, Env()))
        assertEquals(CaptureDecision.Blocked(BlockReason.PLAYBACK_CAPTURE_DENIED),
                     CapturePolicy.decide(CaptureMode.PLAYBACK, Env(), projectionGranted = false))
        val allowed = CapturePolicy.decide(CaptureMode.PLAYBACK, Env(), projectionGranted = true)
        assertEquals(CaptureDecision.Allowed(listOf(CaptureNotice.PLAYBACK_ONLY)), allowed)
    }

    @Test
    fun `playback capture unavailable before Android 10`() {
        assertEquals(CaptureDecision.Blocked(BlockReason.PLAYBACK_CAPTURE_UNSUPPORTED),
                     CapturePolicy.decide(CaptureMode.PLAYBACK, Env(sdkInt = 28), projectionGranted = true))
    }

    @Test
    fun `an active call is detected and stated, for both sources`() {
        assertEquals(CaptureDecision.Allowed(listOf(CaptureNotice.MICROPHONE_ONLY, CaptureNotice.CALL_ACTIVE_MICROPHONE)),
                     CapturePolicy.decide(CaptureMode.MICROPHONE, Env(call = true)))
        assertEquals(CaptureDecision.Allowed(listOf(CaptureNotice.PLAYBACK_ONLY, CaptureNotice.CALL_ACTIVE_PLAYBACK)),
                     CapturePolicy.decide(CaptureMode.PLAYBACK, Env(call = true), projectionGranted = true))
    }

    @Test
    fun `a call starting during recording, and system silencing, are shown live`() = runBlocking {
        val cond = Conditions()
        val c = controller(Source(), cond = cond)
        c.start(RecordingFiles.newTarget(cacheDir), CaptureMode.MICROPHONE, true)
        cond.callActive.value = true
        waitUntil { CaptureNotice.CALL_ACTIVE_MICROPHONE in c.notices.value }
        cond.silenced.value = true
        waitUntil { CaptureNotice.SILENCED_BY_SYSTEM in c.notices.value }
        cond.callActive.value = false; cond.silenced.value = false
        waitUntil { c.notices.value == listOf(CaptureNotice.MICROPHONE_ONLY) }
        c.stop()
        waitUntil { c.notices.value.isEmpty() }
    }

    @Test
    fun `asking to record both sides of a call gives a safe explanation and records nothing`() {
        val d = CapturePolicy.decideCallAudio()
        assertEquals(CaptureDecision.Blocked(BlockReason.PROTECTED_CALL_AUDIO), d)
        val message = (d as CaptureDecision.Blocked).reason.message
        assertTrue(message.contains("cannot capture both sides of a call"))
        assertTrue(cacheDir.listFiles()!!.isEmpty())
    }

    @Test
    fun `the feasibility audit never claims call capture`() {
        val s = CaptureFeasibility.scenarios.associateBy { it.id }
        assertEquals(Support.NOT_SUPPORTED_FOR_ORDINARY_THIRD_PARTY_APPS, s.getValue("cellular_call").support)
        assertEquals(Support.NOT_SUPPORTED_FOR_ORDINARY_THIRD_PARTY_APPS, s.getValue("voip_call").support)
        assertEquals(Support.DEVICE_DEPENDENT, s.getValue("speakerphone_during_call").support)
        assertEquals(Support.SUPPORTED, s.getValue("mic_visible").support)
        assertEquals(Support.PARTIALLY_SUPPORTED, s.getValue("playback_capture").support)
        assertTrue(CaptureFeasibility.scenarios.none {
            it.support == Support.SUPPORTED && it.title.contains("call", ignoreCase = true)
        })
    }

    // ---- into the job pipeline ---------------------------------------------------------------------

    @Test
    fun `no partial recording can reach an upload`() = runBlocking {
        val c = controller(Source())
        val target = RecordingFiles.newTarget(cacheDir)
        c.start(target, CaptureMode.MICROPHONE, true)
        waitUntil { c.recordedMillis.value >= 300 }
        val partial = RecordingFiles.inProgressFor(target)
        assertTrue(partial.isFile)
        val stager = RecordingStager(File(cacheDir, "imports"), cacheDir, { _, _, _ -> error("must not encode") })
        val job = com.whispercppdemo.jobs.JobRecord("j", "file://${partial.absolutePath}", "Conversation",
                                                    JobState.QUEUED, 0, 0, stagedPath = partial.absolutePath)
        assertNull(stager.recordingInput(job))
        c.cancel()
    }

    private class CloudScript(vararg steps: () -> TranscriptionOutcome) : TranscriptionProvider {
        override val id = "deepgram"
        override val displayName = "Cloud"
        val steps = steps.toMutableList()
        val keys = mutableListOf<String>()
        override suspend fun transcribe(request: TranscriptionRequest, onProgress: (Float) -> Unit):
                TranscriptionOutcome { keys += request.idempotencyKey; onProgress(1f)
            return if (steps.size > 1) steps.removeAt(0)() else steps[0]() }
    }

    @Test
    fun `provider outage during a conversation job keeps the recording, and retry after recovery transcribes it once`() = runBlocking {
        // Record a real (generated) conversation through the controller.
        val c = controller(Source())
        val target = RecordingFiles.newTarget(cacheDir)
        c.start(target, CaptureMode.MICROPHONE, true)
        waitUntil { c.recordedMillis.value >= 400 }
        val wav = c.stop()!!

        val store = JobStore(tmp.newFolder("jobs"))
        var encodes = 0
        val stager = RecordingStager(File(cacheDir, "imports"), cacheDir, { s, t, _ ->
            encodes++; RandomAccessFile(t, "rw").use { it.write(s.readBytes().copyOf(512)) }
        })
        fun runner(p: TranscriptionProvider) = JobRunner(store, p,
            prepare = { job -> stager.prepare(job) { input ->
                if (job.stagedPath != input.absolutePath) store.update(job.copy(stagedPath = input.absolutePath)) } },
            persistTranscript = { job, _, _ -> "t-${job.id}" }, sleep = { })

        val down = CloudScript({ TranscriptionOutcome.Failure(FailureKind.PROVIDER_UNAVAILABLE, "down",
                                                              503, reason = "providers_unavailable", retryAfterMs = 10_000) })
        val r1 = runner(down)
        val failed = r1.run(r1.submit("file://${wav.absolutePath}", "Conversation"))
        assertEquals(FailureReason.PROVIDER_UNAVAILABLE, failed.failureReason)
        assertTrue(r1.canRetry(failed.id))
        assertTrue(File(failed.stagedPath!!).isFile)                               // compressed audio kept

        val up = CloudScript({ TranscriptionOutcome.Success("आज की बैठक", "deepgram") })
        val r2 = runner(up)
        val done = r2.run(r2.retry(failed.id)!!)
        assertEquals(JobState.COMPLETED, done.state)
        assertEquals(1, encodes)                                                   // not re-encoded
        assertEquals(listOf(failed.idempotencyKey), up.keys)                        // same logical job
    }

    @Test
    fun `process death mid-conversation is recovered as a retryable job`() = runBlocking {
        val src = Source()
        val c = controller(src)
        val target = RecordingFiles.newTarget(cacheDir)
        c.start(target, CaptureMode.MICROPHONE, true)
        waitUntil { c.recordedMillis.value >= 800 }
        // Death: the process ends with the file still named .recording. (Here
        // the capture is abandoned via the registry so recovery treats it as a
        // dead process's file, exactly what a new process would see.)
        val partial = RecordingFiles.inProgressFor(target)
        RecordingFiles.markInactive(partial)
        val copy = File(cacheDir, "recording-dead.wav" + RecordingFiles.IN_PROGRESS_SUFFIX)
        partial.copyTo(copy)
        c.cancel()

        val store = JobStore(tmp.newFolder("jobs"))
        val recovered = RecordingFiles.recoverAbandoned(cacheDir, store, 1).single()
        assertEquals(JobState.FAILED, recovered.state)
        assertEquals(FailureReason.INTERRUPTED, recovered.failureReason)
        assertNotNull(recovered.stagedPath)
        assertTrue(WavInfo.read(File(recovered.stagedPath!!)).dataBytes > 0)
    }
}
