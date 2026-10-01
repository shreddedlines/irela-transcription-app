package com.whispercppdemo

import com.whispercppdemo.jobs.FailureReason
import com.whispercppdemo.jobs.JobRunner
import com.whispercppdemo.jobs.JobState
import com.whispercppdemo.jobs.JobStore
import com.whispercppdemo.jobs.PreparedAudio
import com.whispercppdemo.jobs.RecordingStager
import com.whispercppdemo.media.WavInfo
import com.whispercppdemo.media.decodeWaveFile
import com.whispercppdemo.recorder.PcmSource
import com.whispercppdemo.recorder.RECORDER_SAMPLE_RATE
import com.whispercppdemo.recorder.Recorder
import com.whispercppdemo.recorder.RecorderState
import com.whispercppdemo.recorder.RecordingFiles
import com.whispercppdemo.recorder.WavWriter
import com.whispercppdemo.transcribe.provider.TranscriptionOutcome
import com.whispercppdemo.transcribe.provider.TranscriptionProvider
import com.whispercppdemo.transcribe.provider.TranscriptionRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The recorder streams PCM to disk instead of holding the recording in memory.
 *
 * Runs the real [Recorder] -- its thread, pause/resume monitor, stop and
 * cancel -- against a generated source, so an hour of audio takes seconds
 * and every lifecycle edge is repeatable. The microphone itself is covered on
 * the device by `LongRecordingDeviceTest`.
 */
class StreamingRecorderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        cacheDir = tmp.newFolder("cache")
    }

    /**
     * Deterministic sample values (the sample index modulo a prime), so the
     * file can be checked sample-for-sample. [realTimeFactor] > 0 sleeps to
     * pace reads for the lifecycle tests; 0 runs flat out.
     */
    private class GeneratedSource(
        override val bufferSamples: Int = 3200,
        private val paceMs: Long = 0,
        private val onRead: (Long) -> Unit = {}
    ) : PcmSource {
        val produced = AtomicLong(0)
        @Volatile var starts = 0
        @Volatile var stops = 0
        @Volatile var released = false
        @Volatile var dieAfterSamples = Long.MAX_VALUE

        override fun start() { starts++ }
        override fun stop() { stops++ }
        override fun release() { released = true }
        override fun read(buffer: ShortArray): Int {
            if (paceMs > 0) Thread.sleep(paceMs)
            val base = produced.get()
            if (base >= dieAfterSamples) throw SimulatedProcessDeath()
            for (i in buffer.indices) buffer[i] = ((base + i) % 30011 - 15005).toShort()
            produced.addAndGet(buffer.size.toLong())
            onRead(produced.get())
            return buffer.size
        }
    }

    private class SimulatedProcessDeath : Error("process killed")

    private fun awaitState(r: Recorder, s: RecorderState) {
        val deadline = System.currentTimeMillis() + 5_000
        while (r.state.value != s && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(s, r.state.value)
    }

    private fun awaitMillis(r: Recorder, atLeast: Long) {
        val deadline = System.currentTimeMillis() + 10_000
        while (r.recordedMillis.value < atLeast && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue("timer reached ${r.recordedMillis.value}", r.recordedMillis.value >= atLeast)
    }

    private fun assertValidHeader(f: File, expectedPcmBytes: Long) {
        val h = ByteBuffer.wrap(RandomAccessFile(f, "r").use { r -> ByteArray(44).also { r.readFully(it) } })
            .order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(h.array(), 0, 4))
        assertEquals((36 + expectedPcmBytes).toInt(), h.getInt(4))
        assertEquals("WAVE", String(h.array(), 8, 4))
        assertEquals("fmt ", String(h.array(), 12, 4))
        assertEquals(16, h.getInt(16))
        assertEquals(1, h.getShort(20).toInt())                  // PCM
        assertEquals(1, h.getShort(22).toInt())                  // mono
        assertEquals(RECORDER_SAMPLE_RATE, h.getInt(24))
        assertEquals(RECORDER_SAMPLE_RATE * 2, h.getInt(28))
        assertEquals(2, h.getShort(32).toInt())
        assertEquals(16, h.getShort(34).toInt())
        assertEquals("data", String(h.array(), 36, 4))
        assertEquals(expectedPcmBytes.toInt(), h.getInt(40))
        assertEquals(44 + expectedPcmBytes, f.length())
    }

    private fun record(seconds: Long): Triple<File, Recorder, GeneratedSource> = runBlocking {
        val target = RecordingFiles.newTarget(cacheDir)
        val done = CountDownLatch(1)
        val samples = seconds * RECORDER_SAMPLE_RATE
        val src = GeneratedSource(3200) { produced -> if (produced >= samples) done.countDown() }
        val r = Recorder({ src })
        r.startRecording(target, { throw AssertionError("recorder error", it) })
        assertTrue("recording did not reach $seconds s", done.await(10, TimeUnit.MINUTES))
        r.stopRecording()
        Triple(target, r, src)
    }

    // ---- short recording, header -------------------------------------------------

    @Test
    fun `short recording is a valid 16 kHz mono WAV holding every captured sample`() = runBlocking {
        val src = GeneratedSource(bufferSamples = 1600, paceMs = 2)
        val r = Recorder({ src })
        val target = RecordingFiles.newTarget(cacheDir)
        r.startRecording(target, { throw AssertionError(it) })
        awaitMillis(r, 1_000)
        assertFalse("the final name appears only after stop", target.exists())
        assertTrue(RecordingFiles.inProgressFor(target).isFile)

        r.stopRecording()
        assertEquals(RecorderState.IDLE, r.state.value)
        assertEquals(0f, r.amplitude.value)
        assertTrue(src.released)
        assertFalse(RecordingFiles.inProgressFor(target).exists())

        val pcm = src.produced.get() * 2
        assertValidHeader(target, pcm)
        assertEquals(src.produced.get() * 1000 / RECORDER_SAMPLE_RATE, r.recordedMillis.value)

        // Sample-for-sample, through the decoder LOCAL uses.
        val decoded = decodeWaveFile(target)
        assertEquals(src.produced.get().toInt(), decoded.size)
        for (i in listOf(0, 1, 1599, 1600, decoded.size - 1)) {
            val expected = ((i % 30011 - 15005) / 32767.0f).coerceIn(-1f..1f)
            assertEquals("sample $i", expected, decoded[i], 1e-6f)
        }
        // And through the reader the cloud compressor uses.
        assertEquals(pcm, WavInfo.read(target).dataBytes)
    }

    @Test
    fun `level meter reports the peak of each captured buffer`() = runBlocking {
        val src = GeneratedSource(bufferSamples = 1600, paceMs = 2)
        val r = Recorder({ src })
        val target = RecordingFiles.newTarget(cacheDir)
        r.startRecording(target, { throw AssertionError(it) })
        awaitMillis(r, 300)
        assertTrue("amplitude ${r.amplitude.value}", r.amplitude.value > 0f && r.amplitude.value <= 1f)
        r.stopRecording()
        assertEquals(0f, r.amplitude.value)
    }

    // ---- pause / resume -------------------------------------------------------------

    @Test
    fun `pause stops capture and the timer, resume appends to the same file`() = runBlocking {
        val src = GeneratedSource(bufferSamples = 800, paceMs = 2)
        val r = Recorder({ src })
        val target = RecordingFiles.newTarget(cacheDir)
        r.startRecording(target, { throw AssertionError(it) })
        awaitMillis(r, 300)

        r.pause()
        assertEquals(RecorderState.PAUSED, r.state.value)
        Thread.sleep(100)                          // let an in-flight read land
        val pausedAt = r.recordedMillis.value
        val producedAtPause = src.produced.get()
        Thread.sleep(300)
        assertEquals("timer frozen while paused", pausedAt, r.recordedMillis.value)
        assertEquals("nothing captured while paused", producedAtPause, src.produced.get())
        assertEquals(0f, r.amplitude.value)
        assertEquals(1, src.stops)

        r.resume()
        assertEquals(RecorderState.RECORDING, r.state.value)
        awaitMillis(r, pausedAt + 300)
        assertEquals(2, src.starts)

        r.stopRecording()
        assertValidHeader(target, src.produced.get() * 2)
        assertEquals(src.produced.get() * 1000 / RECORDER_SAMPLE_RATE, r.recordedMillis.value)
    }

    @Test
    fun `stop while paused finalises instead of hanging`() = runBlocking {
        val src = GeneratedSource(bufferSamples = 800, paceMs = 2)
        val r = Recorder({ src })
        val target = RecordingFiles.newTarget(cacheDir)
        r.startRecording(target, { throw AssertionError(it) })
        awaitMillis(r, 200)
        r.pause()
        r.stopRecording()
        assertEquals(RecorderState.IDLE, r.state.value)
        assertValidHeader(target, src.produced.get() * 2)
    }

    // ---- cancel ---------------------------------------------------------------------

    @Test
    fun `cancel deletes the partial recording and leaves no file behind`() = runBlocking {
        val src = GeneratedSource(bufferSamples = 800, paceMs = 2)
        val r = Recorder({ src })
        val target = RecordingFiles.newTarget(cacheDir)
        r.startRecording(target, { throw AssertionError(it) })
        awaitMillis(r, 300)
        r.cancelRecording()
        assertEquals(RecorderState.IDLE, r.state.value)
        assertFalse(target.exists())
        assertFalse(RecordingFiles.inProgressFor(target).exists())
        assertTrue(src.released)
        assertTrue(cacheDir.listFiles()!!.isEmpty())
    }

    @Test
    fun `cancel while paused also deletes everything`() = runBlocking {
        val src = GeneratedSource(bufferSamples = 800, paceMs = 2)
        val r = Recorder({ src })
        val target = RecordingFiles.newTarget(cacheDir)
        r.startRecording(target, { throw AssertionError(it) })
        awaitMillis(r, 200)
        r.pause()
        r.cancelRecording()
        assertTrue(cacheDir.listFiles()!!.isEmpty())
    }

    // ---- process death ------------------------------------------------------------------

    @Test
    fun `process death mid-recording leaves a recoverable WAV that becomes a retryable job`() = runBlocking {
        val src = GeneratedSource(bufferSamples = 1600).apply { dieAfterSamples = 20L * RECORDER_SAMPLE_RATE }
        val r = Recorder({ src })
        val target = RecordingFiles.newTarget(cacheDir)
        val died = CountDownLatch(1)
        val t = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> if (e is SimulatedProcessDeath) died.countDown() }
        try {
            r.startRecording(target, { throw AssertionError(it) })
            assertTrue(died.await(30, TimeUnit.SECONDS))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(t)
        }

        val partial = RecordingFiles.inProgressFor(target)
        assertTrue("audio is on disk, not only in memory", partial.isFile)
        assertFalse("never visible under the uploadable name", target.exists())
        // The header was kept current while recording: already a valid WAV.
        assertValidHeader(partial, 20L * RECORDER_SAMPLE_RATE * 2)

        // What the partial looks like to the upload paths: not a recording.
        val stager = RecordingStager(File(cacheDir, "imports"), cacheDir, { _, _, _ -> error("no") })
        assertFalse(stager.isRecordingWav(partial))

        // Next process: cold-start recovery.
        val store = JobStore(tmp.newFolder("jobs"))
        val recovered = RecordingFiles.recoverAbandoned(cacheDir, store, now = 5_000).single()
        assertEquals(JobState.FAILED, recovered.state)
        assertEquals(FailureReason.INTERRUPTED, recovered.failureReason)
        assertEquals(RecordingFiles.RECOVERED_NAME, recovered.displayName)
        assertEquals(target.absolutePath, recovered.stagedPath)
        assertFalse(partial.exists())
        assertValidHeader(target, 20L * RECORDER_SAMPLE_RATE * 2)
        assertTrue(stager.isRecordingWav(target))

        // Retry through the real JobRunner: the recovered WAV is what is sent.
        val sent = mutableListOf<File>()
        val provider = object : TranscriptionProvider {
            override val id = "mock"
            override val displayName = "Mock"
            override suspend fun transcribe(request: TranscriptionRequest, onProgress: (Float) -> Unit):
                    TranscriptionOutcome { sent += request.audio; onProgress(1f); return TranscriptionOutcome.Success("ok", "mock") }
        }
        val runner = JobRunner(store, provider,
            prepare = { job -> PreparedAudio(File(job.stagedPath!!), "audio/wav") },
            persistTranscript = { _, _, _ -> "t" }, now = { 6_000 }, sleep = { })
        assertTrue(runner.canRetry(recovered.id))
        val done = runner.run(runner.retry(recovered.id)!!)
        assertEquals(JobState.COMPLETED, done.state)
        assertEquals(target, sent.single())
    }

    @Test
    fun `a partial with its header not yet updated is repaired from the file length`() {
        val f = File(cacheDir, "recording-x.wav" + RecordingFiles.IN_PROGRESS_SUFFIX)
        val w = WavWriter(f)
        w.write(ShortArray(16_000) { 7 }, 16_000)
        w.close()
        // Death between appending samples and rewriting the header.
        RandomAccessFile(f, "rw").use { it.seek(f.length()); it.write(ByteArray(3201) { 1 }) }
        val store = JobStore(tmp.newFolder("jobs"))
        val job = RecordingFiles.recoverAbandoned(cacheDir, store, 1).single()
        assertValidHeader(File(job.stagedPath!!), 32_000L + 3200)
    }

    @Test
    fun `recovery ignores recordings a live recorder is writing and drops tiny fragments`() = runBlocking {
        val src = GeneratedSource(bufferSamples = 800, paceMs = 2)
        val r = Recorder({ src })
        val live = RecordingFiles.newTarget(cacheDir)
        r.startRecording(live, { throw AssertionError(it) })
        awaitMillis(r, 700)

        val fragment = File(cacheDir, "recording-y.wav" + RecordingFiles.IN_PROGRESS_SUFFIX)
        WavWriter(fragment).apply { write(ShortArray(100), 100); close() }

        val store = JobStore(tmp.newFolder("jobs"))
        assertTrue(RecordingFiles.recoverAbandoned(cacheDir, store, 1).isEmpty())
        assertFalse(fragment.exists())
        assertTrue("live recording untouched", RecordingFiles.inProgressFor(live).isFile)
        assertTrue(store.all().isEmpty())
        r.stopRecording()
        assertTrue(live.isFile)
    }

    // ---- long recordings and memory ---------------------------------------------------

    @Test
    fun `10-minute recording`() {
        val (f, r, src) = record(600)
        assertValidHeader(f, src.produced.get() * 2)
        assertTrue(f.length() >= 44 + 600L * 32_000)
        assertEquals(src.produced.get() * 1000 / RECORDER_SAMPLE_RATE, r.recordedMillis.value)
        assertEquals(src.produced.get() * 2, WavInfo.read(f).dataBytes)
    }

    @Test
    fun `30-minute recording`() {
        val (f, r, src) = record(1800)
        assertValidHeader(f, src.produced.get() * 2)
        assertTrue(f.length() >= 44 + 1800L * 32_000)
        assertTrue(r.recordedMillis.value >= 1_800_000)
    }

    @Test
    fun `60-minute recording completes without memory growth proportional to duration`() = runBlocking {
        val rt = Runtime.getRuntime()
        fun usedAfterGc(): Long {
            repeat(3) { System.gc(); Thread.sleep(20) }
            return rt.totalMemory() - rt.freeMemory()
        }
        val checkpoints = sortedMapOf<Long, Long>()     // audio seconds -> used heap
        val marks = listOf(60L, 600L, 1800L, 3600L).map { it * RECORDER_SAMPLE_RATE }.toMutableList()
        val done = CountDownLatch(1)
        val src = GeneratedSource(bufferSamples = 3200) { produced ->
            if (marks.isNotEmpty() && produced >= marks.first()) {
                checkpoints[marks.removeAt(0) / RECORDER_SAMPLE_RATE] = usedAfterGc()
                if (marks.isEmpty()) done.countDown()
            }
        }
        val r = Recorder({ src })
        val target = RecordingFiles.newTarget(cacheDir)
        r.startRecording(target, { throw AssertionError(it) })
        assertTrue(done.await(10, TimeUnit.MINUTES))
        r.stopRecording()

        val mb = checkpoints.mapValues { "%.1f MB".format(it.value / 1048576.0) }
        println("heap after GC by recorded seconds: $mb; heap max ${rt.maxMemory() / 1048576} MB")
        val growth = checkpoints.getValue(3600) - checkpoints.getValue(60)
        // The old in-memory list needed ~12-20 bytes per sample: 57.6M samples
        // = 700 MB-1.1 GB at 60 minutes. Streaming must stay flat.
        assertTrue("heap grew ${growth / 1048576.0} MB between 1 and 60 minutes: $mb",
                   growth < 8L * 1024 * 1024)

        assertTrue(r.recordedMillis.value >= 3_600_000)
        assertValidHeader(target, src.produced.get() * 2)
        assertTrue("115 MB of WAV on disk", target.length() > 115_000_000)
        assertNull(RecordingFiles.inProgressFor(target).takeIf { it.exists() })
    }
}
