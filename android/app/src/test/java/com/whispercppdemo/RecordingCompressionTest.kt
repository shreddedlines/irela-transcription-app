package com.whispercppdemo

import com.whispercppdemo.jobs.FailureReason
import com.whispercppdemo.jobs.JobRecord
import com.whispercppdemo.jobs.JobRunner
import com.whispercppdemo.jobs.JobState
import com.whispercppdemo.jobs.JobStore
import com.whispercppdemo.jobs.RecordingStager
import com.whispercppdemo.jobs.disposableStagedFiles
import com.whispercppdemo.media.AudioLimits
import com.whispercppdemo.media.RecordingCompression
import com.whispercppdemo.media.WavInfo
import com.whispercppdemo.media.encodeWaveFile
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * Microphone recordings are compressed before a CLOUD upload so a 60-minute
 * recording fits the unchanged 100 MB request limit.
 *
 * The encoder itself needs MediaCodec and is exercised on a device by
 * `AacRecordingEncoderTest`. Everything around it -- limits, staging, process
 * death, retry, dedupe and cleanup -- is exercised here through the real
 * JobRunner and JobStore, with an encoder stand-in that writes a file of the
 * size the real encoder is bounded by.
 */
class RecordingCompressionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var jobsDir: File
    private lateinit var cacheDir: File
    private lateinit var importsDir: File
    private lateinit var store: JobStore
    private var clock = 1_000L

    /** Encoder stand-in: output sized to the encoder's upper bound. */
    private var compressions = 0
    private var compressBehaviour: suspend (File, File) -> Unit = { source, target ->
        compressions++
        val seconds = (WavInfo.read(source).dataBytes) / (RecordingCompression.SAMPLE_RATE * 2L)
        RandomAccessFile(target, "rw").use {
            it.write(source.name.toByteArray())      // distinct content per recording
            it.setLength(RecordingCompression.upperBoundBytes(seconds))
        }
    }

    private class SimulatedProcessDeath : Error("process killed")

    private class RecordingProvider : TranscriptionProvider {
        override val id = "mock"
        override val displayName = "Mock"
        val sent = mutableListOf<Pair<File, String>>()
        var script: MutableList<() -> TranscriptionOutcome> = mutableListOf()
        override suspend fun transcribe(
            request: TranscriptionRequest, onProgress: (Float) -> Unit
        ): TranscriptionOutcome {
            sent += request.audio to request.mimeType
            assertTrue("uploads must never read a partial file", request.audio.isFile &&
                    !request.audio.name.endsWith(RecordingStager.PARTIAL_SUFFIX))
            onProgress(1f)
            return script.removeFirstOrNull()?.invoke()
                ?: TranscriptionOutcome.Success("नमस्ते, meeting at 5", "mock")
        }
    }

    @Before
    fun setUp() {
        jobsDir = tmp.newFolder("jobs")
        cacheDir = tmp.newFolder("cache")
        importsDir = File(cacheDir, "imports")
        store = JobStore(jobsDir)
        compressions = 0
    }

    private fun stager() = RecordingStager(importsDir, cacheDir, { s, t, _ -> compressBehaviour(s, t) })

    /**
     * The same prepare wiring as TranscriptionService.prepareRecordingForCloud:
     * the input is pinned into the job record before encoding starts.
     */
    private fun runner(provider: TranscriptionProvider, jobStore: JobStore = store): JobRunner {
        val stager = stager()
        return JobRunner(
            store = jobStore, provider = provider,
            prepare = { job ->
                stager.prepare(job) { input ->
                    if (job.stagedPath != input.absolutePath) {
                        jobStore.update(job.copy(stagedPath = input.absolutePath))
                    }
                }
            },
            persistTranscript = { job, _, _ -> "t-${job.id}" },
            now = { clock++ }, sleep = { }
        )
    }

    /** A recorder temp file of [seconds] of audio. Sparse: no 115 MB allocation. */
    private fun recording(seconds: Long): File {
        val f = File.createTempFile("recording", "wav", cacheDir)
        val pcm = seconds * RecordingCompression.SAMPLE_RATE * 2
        RandomAccessFile(f, "rw").use { raf ->
            raf.write(recorderHeader(pcm))
            raf.setLength(44 + pcm)
        }
        return f
    }

    /** Byte-for-byte what RiffWaveHelper.encodeWaveFile writes for [pcm] bytes. */
    private fun recorderHeader(pcm: Long): ByteArray {
        val probe = File(tmp.root, "probe.wav")
        encodeWaveFile(probe, ShortArray(64))
        val h = probe.readBytes().copyOf(44)
        fun put32(at: Int, v: Long) { for (i in 0..3) h[at + i] = ((v shr (8 * i)) and 0xFF).toByte() }
        put32(4, pcm - 8); put32(40, pcm - 44)       // the recorder's own arithmetic
        return h
    }

    private fun submit(runner: JobRunner, wav: File): JobRecord =
        runner.submit("file://${wav.absolutePath}", "Recording")

    // ---- limits at stop --------------------------------------------------------

    private fun cloudVerdict(seconds: Long) = AudioLimits.checkRecording(
        RecordingCompression.wavBytes(seconds), seconds * 1000, compressedBeforeUpload = true)

    @Test
    fun `54 min 36 s recording is accepted`() {
        assertEquals(AudioLimits.Verdict.Ok, cloudVerdict(54L * 60 + 36))
        // One second later the WAV itself is past 100 MB -- still accepted,
        // because the WAV is not what is uploaded.
        assertTrue(RecordingCompression.wavBytes(54L * 60 + 37) > AudioLimits.MAX_UPLOAD_BYTES)
        assertEquals(AudioLimits.Verdict.Ok, cloudVerdict(54L * 60 + 37))
    }

    @Test
    fun `59 min 59 s recording is accepted`() {
        assertEquals(AudioLimits.Verdict.Ok, cloudVerdict(59L * 60 + 59))
    }

    @Test
    fun `60 min 00 s recording is accepted`() {
        assertEquals(AudioLimits.Verdict.Ok, cloudVerdict(3600L))
    }

    @Test
    fun `past 60 min 00 s is rejected with the duration message`() {
        val v = cloudVerdict(3601L)
        assertEquals(AudioLimits.Verdict.TooLong(3601L), v)
        assertEquals("That recording is longer than 60 minutes. Try splitting it into shorter parts.",
                     AudioLimits.message(v))
    }

    @Test
    fun `LOCAL recording limits are unchanged`() {
        fun local(s: Long) = AudioLimits.checkRecording(RecordingCompression.wavBytes(s), s * 1000, false)
        assertEquals(AudioLimits.Verdict.Ok, local(54L * 60 + 36))
        assertTrue(local(54L * 60 + 37) is AudioLimits.Verdict.TooLarge)
        assertEquals(AudioLimits.check(RecordingCompression.wavBytes(600), 600), local(600))
    }

    @Test
    fun `60-minute compressed recording stays comfortably below 100 MB`() {
        val bound = RecordingCompression.upperBoundBytes(3600)
        val mb = bound / (1024.0 * 1024.0)
        assertTrue("upper bound $mb MB", bound < AudioLimits.MAX_UPLOAD_BYTES / 2)
        // Nominal payload: 64 kbit/s x 3600 s = 28.8 MB (27.5 MiB).
        assertEquals(28_800_000L, 3600L * RecordingCompression.BIT_RATE / 8)
    }

    // ---- through the real JobRunner ----------------------------------------------

    @Test
    fun `54_36, 59_59 and 60_00 recordings upload a compressed file under the byte cap`() = runBlocking {
        for (seconds in listOf(54L * 60 + 36, 59L * 60 + 59, 3600L)) {
            val provider = RecordingProvider()
            val r = runner(provider)
            val wav = recording(seconds)
            val done = r.run(submit(r, wav))

            assertEquals("$seconds s", JobState.COMPLETED, done.state)
            assertEquals(1, provider.sent.size)
            val (file, mime) = provider.sent.single()
            assertEquals("audio/mp4", mime)
            assertTrue(file.name.endsWith(".m4a"))
            assertTrue("$seconds s uploaded ${file.length()} bytes",
                       file.length() < AudioLimits.MAX_UPLOAD_BYTES)
            assertTrue("the WAV is never uploaded", provider.sent.none { it.first == wav })
        }
    }

    @Test
    fun `imports are not treated as recordings`() {
        val s = stager()
        fun job(uri: String, staged: String? = null) =
            JobRecord(id = "j", sourceUri = uri, displayName = "x", state = JobState.QUEUED,
                      createdAt = 0, updatedAt = 0, stagedPath = staged)
        assertNull(s.recordingInput(job("content://com.whatsapp/audio/1")))
        val importCopy = File(importsDir.apply { mkdirs() }, "abc.ogg").apply { writeBytes(ByteArray(10)) }
        assertNull(s.recordingInput(job("file://${importCopy.absolutePath}", importCopy.absolutePath)))
        val elsewhere = File(tmp.newFolder("other"), "recording1wav").apply { writeBytes(ByteArray(10)) }
        assertNull(s.recordingInput(job("file://${elsewhere.absolutePath}")))
        // A recording still being written is never an upload input.
        val inProgress = File(cacheDir, "recording-z.wav.recording").apply { writeBytes(ByteArray(100)) }
        assertNull(s.recordingInput(job("file://${inProgress.absolutePath}", inProgress.absolutePath)))
    }

    // ---- process death ------------------------------------------------------------

    @Test
    fun `process death during compression leaves a retryable job and no usable partial`() = runBlocking {
        val wav = recording(60)
        compressBehaviour = { _, target ->
            compressions++
            target.writeBytes(ByteArray(4096))       // half-written output
            throw SimulatedProcessDeath()
        }
        val dying = runner(RecordingProvider())
        val job = submit(dying, wav)
        try { dying.run(job); fail("process should have died") } catch (_: SimulatedProcessDeath) { }

        // What the next process finds on disk.
        val onDisk = store.get(job.id)!!
        assertEquals(JobState.UPLOADING, onDisk.state)
        assertEquals("input pinned before encoding", wav.absolutePath, onDisk.stagedPath)
        val partials = importsDir.listFiles()!!.filter { it.name.endsWith(RecordingStager.PARTIAL_SUFFIX) }
        assertEquals(1, partials.size)

        // Next process: fresh store handle, fresh runner, same files.
        compressBehaviour = { s, t -> compressions++; RandomAccessFile(t, "rw").use { it.write(s.name.toByteArray()); it.setLength(500_000) } }
        val reopened = JobStore(jobsDir)
        val provider = RecordingProvider()
        val next = runner(provider, reopened)
        val recovered = next.recoverOrphans().single()
        assertEquals(JobState.FAILED, recovered.state)
        assertEquals(FailureReason.INTERRUPTED, recovered.failureReason)
        assertTrue("Retry must work after death mid-compression", next.canRetry(job.id))

        // The cold-start sweep keeps only what unfinished jobs reference.
        val keep = reopened.all().filter { it.state != JobState.COMPLETED }.mapNotNull { it.stagedPath }.toSet()
        assertTrue(partials.none { it.absolutePath in keep })
        assertTrue(wav.absolutePath in keep)

        val done = next.run(next.retry(job.id)!!)
        assertEquals(JobState.COMPLETED, done.state)
        assertEquals("compressed again exactly once, from the pinned WAV", 2, compressions)
        assertTrue(provider.sent.single().first.name.endsWith(".m4a"))
    }

    @Test
    fun `process death after compression retries with the same compressed file`() = runBlocking {
        val wav = recording(60)
        val dyingProvider = object : TranscriptionProvider {
            override val id = "mock"
            override val displayName = "Mock"
            override suspend fun transcribe(request: TranscriptionRequest, onProgress: (Float) -> Unit):
                    TranscriptionOutcome = throw SimulatedProcessDeath()   // killed mid-upload
        }
        val dying = runner(dyingProvider)
        val job = submit(dying, wav)
        try { dying.run(job); fail("process should have died") } catch (_: SimulatedProcessDeath) { }
        assertEquals(1, compressions)
        val compressed = File(store.get(job.id)!!.stagedPath!!)
        assertTrue(compressed.name.endsWith(".m4a") && compressed.isFile)

        val reopened = JobStore(jobsDir)
        val provider = RecordingProvider()
        val next = runner(provider, reopened)
        next.recoverOrphans()
        assertTrue(next.canRetry(job.id))
        val done = next.run(next.retry(job.id)!!)

        assertEquals(JobState.COMPLETED, done.state)
        assertEquals("no second compression", 1, compressions)
        assertEquals(compressed.absolutePath, provider.sent.single().first.absolutePath)
    }

    // ---- retry ---------------------------------------------------------------------

    @Test
    fun `in-place retries reuse the compressed file without re-encoding`() = runBlocking {
        val provider = RecordingProvider().apply {
            script = mutableListOf(
                { TranscriptionOutcome.Failure(FailureKind.RETRYABLE_SERVER, "busy") },
                { TranscriptionOutcome.Failure(FailureKind.TIMEOUT, "slow") }
            )
        }
        val r = runner(provider)
        val done = r.run(submit(r, recording(120)))
        assertEquals(JobState.COMPLETED, done.state)
        assertEquals(3, provider.sent.size)
        assertEquals(1, compressions)
        assertEquals("every attempt sends the same file", 1, provider.sent.map { it.first }.toSet().size)
    }

    @Test
    fun `user retry after a failure reuses the staged compressed file`() = runBlocking {
        val provider = RecordingProvider().apply {
            script = MutableList(3) { { TranscriptionOutcome.Failure(FailureKind.RETRYABLE_NETWORK, "offline") } }
        }
        val r = runner(provider)
        val failed = r.run(submit(r, recording(120)))
        assertEquals(JobState.FAILED, failed.state)
        assertTrue(failed.stagedPath!!.endsWith(".m4a"))
        assertTrue(r.canRetry(failed.id))

        val done = r.run(r.retry(failed.id)!!)
        assertEquals(JobState.COMPLETED, done.state)
        assertEquals("no duplicate compression on retry", 1, compressions)
        assertEquals(1, provider.sent.map { it.first }.toSet().size)
    }

    @Test
    fun `cancel during compression leaves no partial and stays retryable`() = runBlocking {
        val wav = recording(60)
        compressBehaviour = { _, target ->
            compressions++
            target.writeBytes(ByteArray(1024))
            throw CancellationException("cancelled by user")
        }
        val r = runner(RecordingProvider())
        val job = submit(r, wav)
        try { r.run(job); fail("expected cancellation") } catch (_: CancellationException) { }

        val cancelled = store.get(job.id)!!
        assertEquals(JobState.CANCELLED, cancelled.state)
        assertEquals(wav.absolutePath, cancelled.stagedPath)
        assertTrue(importsDir.listFiles().orEmpty().none { it.name.endsWith(RecordingStager.PARTIAL_SUFFIX) })
        assertTrue(r.canRetry(job.id))
    }

    @Test
    fun `an encoder failure is a visible failure, not a hang or a partial upload`() = runBlocking {
        compressBehaviour = { _, t -> compressions++; t.writeBytes(ByteArray(10)); throw IllegalStateException("codec") }
        val provider = RecordingProvider()
        val r = runner(provider)
        val failed = r.run(submit(r, recording(60)))
        assertEquals(JobState.FAILED, failed.state)
        assertEquals(FailureReason.AUDIO_REJECTED, failed.failureReason)
        assertTrue(provider.sent.isEmpty())
        assertTrue(importsDir.listFiles().orEmpty().isEmpty())
    }

    // ---- dedupe, persistence, cleanup -------------------------------------------------

    @Test
    fun `dedupe still works on the uploaded bytes`() = runBlocking {
        val provider = RecordingProvider()
        val r = runner(provider)
        val first = r.run(submit(r, recording(60)))
        assertEquals(JobState.COMPLETED, first.state)
        assertNotNull(first.contentHash)

        // A second job over the identical compressed bytes reuses the transcript.
        val copy = File(importsDir, "rec-copy.m4a")
        File(first.stagedPath!!).copyTo(copy)
        val twin = store.create("file://${copy.absolutePath}", "Recording", clock++)
        val seeded = store.update(twin.copy(stagedPath = copy.absolutePath, state = JobState.QUEUED))
        val reused = r.run(seeded)
        assertEquals(JobState.COMPLETED, reused.state)
        assertEquals(first.transcriptId, reused.transcriptId)
        assertEquals("no second provider call", 1, provider.sent.size)
    }

    @Test
    fun `completed recording's WAV and compressed file are both disposable`() = runBlocking {
        val wav = recording(60)
        val r = runner(RecordingProvider())
        val done = r.run(submit(r, wav))
        val compressed = File(done.stagedPath!!)
        assertEquals(listOf(wav, compressed), disposableStagedFiles(listOf(wav, compressed), store.all()))
    }

    @Test
    fun `failed recording keeps only the compressed file for Retry`() = runBlocking {
        val wav = recording(60)
        val provider = RecordingProvider().apply {
            script = MutableList(3) { { TranscriptionOutcome.Failure(FailureKind.RETRYABLE_SERVER, "down") } }
        }
        val r = runner(provider)
        val failed = r.run(submit(r, wav))
        val compressed = File(failed.stagedPath!!)
        assertEquals("the WAV is no longer needed", listOf(wav),
                     disposableStagedFiles(listOf(wav, compressed), store.all()))
    }

    @Test
    fun `recorder WAV header is read with its full PCM length`() {
        val f = File(tmp.root, "real.wav")
        encodeWaveFile(f, ShortArray(16_000) { (it % 200).toShort() })
        val info = WavInfo.read(f)
        assertEquals(16_000, info.sampleRate)
        assertEquals(1, info.channels)
        assertEquals(44L, info.dataOffset)
        assertEquals("all samples, despite the header under-declaring by 44 bytes", 32_000L, info.dataBytes)
        assertFalse(info.dataBytes % 2 != 0L)
    }
}
