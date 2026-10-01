package com.whispercppdemo

import com.whispercppdemo.jobs.FailureReason
import com.whispercppdemo.jobs.JobRecord
import com.whispercppdemo.jobs.JobRunner
import com.whispercppdemo.jobs.JobState
import com.whispercppdemo.jobs.JobStore
import com.whispercppdemo.jobs.LimitReset
import com.whispercppdemo.jobs.RecordingStager
import com.whispercppdemo.jobs.StorageJanitor
import com.whispercppdemo.jobs.attempts
import com.whispercppdemo.jobs.disposableStagedFiles
import com.whispercppdemo.jobs.failureMessageFor
import com.whispercppdemo.jobs.prunableImportCopies
import com.whispercppdemo.media.encodeWaveFile
import com.whispercppdemo.recorder.RecordingFiles
import com.whispercppdemo.recorder.RecordingStorage
import com.whispercppdemo.recorder.WavWriter
import com.whispercppdemo.transcribe.provider.FailureKind
import com.whispercppdemo.transcribe.provider.TranscriptionOutcome
import com.whispercppdemo.transcribe.provider.TranscriptionProvider
import com.whispercppdemo.transcribe.provider.TranscriptionRequest
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
import java.time.ZonedDateTime

/**
 * A user's recording is never lost because transcription did not succeed.
 *
 * Recordings live in permanent app storage (filesDir/recordings), survive every
 * automatic cleanup for as long as their job has not completed -- failed,
 * refused by a limit, provider unavailable, offline, interrupted, restarted,
 * eight days later -- and a limit refusal becomes retryable once its limit
 * resets. Exercised through the real JobRunner, JobStore, RecordingStager and
 * StorageJanitor, with the service's housekeeping applied exactly as it runs.
 */
class RecordingRetentionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var cacheDir: File
    private lateinit var importsDir: File
    private lateinit var recordingsDir: File
    private lateinit var uploadDir: File
    private lateinit var store: JobStore

    private val day = 24L * 60 * 60 * 1000

    /** Wall-clock millis for a moment in the allowance time zone (India). */
    private fun ist(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, LimitReset.ZONE).toInstant().toEpochMilli()

    private var clock = 0L

    @Before
    fun setUp() {
        filesDir = tmp.newFolder("files")
        cacheDir = tmp.newFolder("cache")
        importsDir = File(cacheDir, "imports").apply { mkdirs() }
        recordingsDir = RecordingStorage.recordingsDir(filesDir).apply { mkdirs() }
        uploadDir = RecordingStorage.uploadDir(filesDir)
        store = JobStore(File(filesDir, "jobs"))
        clock = ist(2026, 9, 24, 15, 0)
    }

    // ---- fixtures ---------------------------------------------------------

    private class ScriptedProvider : TranscriptionProvider {
        override val id = "mock"
        override val displayName = "Mock"
        var calls = 0
        var next: () -> TranscriptionOutcome = { TranscriptionOutcome.Success("hello there", "mock") }
        override suspend fun transcribe(request: TranscriptionRequest,
                                        onProgress: (Float) -> Unit): TranscriptionOutcome {
            calls++
            onProgress(1f)
            return next()
        }
    }

    private fun stager() = RecordingStager(uploadDir, recordingsDir, { source, target, _ ->
        target.writeBytes(source.readBytes().copyOf(512))     // stand-in for the AAC encoder
    })

    /** The same prepare wiring as TranscriptionService.prepareRecordingForCloud. */
    private fun runner(provider: TranscriptionProvider): JobRunner {
        val stager = stager()
        return JobRunner(
            store = store, provider = provider,
            prepare = { job ->
                stager.prepare(job) { input ->
                    if (job.stagedPath != input.absolutePath)
                        store.update(job.copy(stagedPath = input.absolutePath))
                }
            },
            persistTranscript = { job, _, _ -> "t-${job.id}" },
            now = { clock }, sleep = { }
        )
    }

    /** A finished recording exactly where the recorder now writes one. */
    private fun recording(): File =
        RecordingFiles.newTarget(recordingsDir).also { encodeWaveFile(it, ShortArray(16_000)) }

    private fun submit(r: JobRunner, wav: File): JobRecord = r.submit("file://${wav.absolutePath}", "Recording")

    /**
     * Everything that may delete audio on its own, as the app runs it: the
     * service's post-drain cleanup, its 7-day prune, and the cold-start janitor.
     */
    private fun housekeeping(tracked: List<File> = emptyList()) {
        val jobs = store.all()
        disposableStagedFiles(tracked, jobs).forEach { it.delete() }
        prunableImportCopies(store.all(), importsDir, clock - 7 * day).forEach { it.delete() }
        val plan = StorageJanitor.plan(recordingsDir, importsDir, store.all(), emptySet(), clock,
                                       stagingDirs = listOf(uploadDir))
        StorageJanitor.apply(plan, store, clock)
    }

    private fun refusal(code: String) = TranscriptionOutcome.Failure(
        FailureKind.TERMINAL_AUTH, failureMessageFor(code), httpStatus = 429, reason = code)

    private fun assertAudioKept(job: JobRecord) {
        assertNotNull("the job must still point at its audio", job.stagedPath)
        val staged = File(job.stagedPath!!)
        assertTrue("audio must still exist: $staged", staged.isFile && staged.length() > 0)
        assertTrue("audio must be in permanent storage: $staged",
                   staged.absolutePath.startsWith(filesDir.absolutePath))
    }

    private fun failWith(outcome: () -> TranscriptionOutcome): Pair<JobRunner, JobRecord> = runBlocking {
        val provider = ScriptedProvider().apply { next = outcome }
        val r = runner(provider)
        val wav = recording()
        val failed = r.run(submit(r, wav))
        assertEquals(JobState.FAILED, failed.state)
        housekeeping(listOf(wav, File(failed.stagedPath!!)))
        r to failed
    }

    // ---- where recordings live ------------------------------------------------

    @Test
    fun `recordings and their upload copies live in permanent storage, not the cache`() = runBlocking {
        assertEquals(File(filesDir, "recordings"), RecordingStorage.recordingsDir(filesDir))
        assertEquals(File(filesDir, "recordings/upload"), RecordingStorage.uploadDir(filesDir))
        val r = runner(ScriptedProvider().apply { next = { refusal(FailureReason.FREE_MONTHLY_ALLOWANCE) } })
        val failed = r.run(submit(r, recording()))
        assertEquals(uploadDir.absolutePath, File(failed.stagedPath!!).parentFile!!.absolutePath)
        assertTrue("nothing may be written to the cache", cacheDir.walk().none { it.isFile })
    }

    // ---- failures and refusals: the audio is kept, now and 8+ days later ------

    @Test
    fun `every failure and refusal keeps the recording, including eight days later`() {
        val cases = listOf(
            FailureReason.FREE_MONTHLY_ALLOWANCE to { refusal(FailureReason.FREE_MONTHLY_ALLOWANCE) },
            FailureReason.FREE_NETWORK_DAILY_CAP to { refusal(FailureReason.FREE_NETWORK_DAILY_CAP) },
            FailureReason.FREE_DAILY_BUDGET to { refusal(FailureReason.FREE_DAILY_BUDGET) },
            FailureReason.DAILY_QUOTA to { refusal(FailureReason.DAILY_QUOTA) },
            FailureReason.OWNER_MONTHLY_LIMIT to { refusal(FailureReason.OWNER_MONTHLY_LIMIT) },
            FailureReason.REVOKED to { refusal(FailureReason.REVOKED) },
            FailureReason.SERVICE_BUDGET to { refusal(FailureReason.SERVICE_BUDGET) },
            // Deepgram and AssemblyAI both down behind the backend.
            FailureReason.PROVIDER_UNAVAILABLE to {
                TranscriptionOutcome.Failure(FailureKind.PROVIDER_UNAVAILABLE, "unavailable", 503)
            },
            // A provider error that persists through every in-place retry.
            FailureReason.RETRIES_EXHAUSTED to {
                TranscriptionOutcome.Failure(FailureKind.RETRYABLE_SERVER, "error", 502)
            },
            // A provider's own terminal error (e.g. its API quota exhausted).
            FailureReason.PROVIDER_TERMINAL to {
                TranscriptionOutcome.Failure(FailureKind.TERMINAL_UNKNOWN, "provider refused", 500)
            }
        )
        cases.forEach { (expected, outcome) ->
            clock = ist(2026, 9, 24, 15, 0)
            val (_, failed) = failWith(outcome)
            assertEquals(expected, failed.failureReason)
            assertAudioKept(store.get(failed.id)!!)
            clock += 8 * day
            housekeeping()
            assertAudioKept(store.get(failed.id)!!)
        }
    }

    @Test
    fun `offline keeps the recording and Retry works, eight days later too`() = runBlocking {
        val r = runner(ScriptedProvider())
        val job = submit(r, recording())
        val failed = r.stageAndFail(job, FailureReason.OFFLINE)
        assertEquals(FailureReason.OFFLINE, failed.failureReason)
        housekeeping()
        clock += 8 * day
        housekeeping()
        assertAudioKept(store.get(failed.id)!!)
        assertTrue(r.canRetry(failed.id))
    }

    // ---- interrupted and restart --------------------------------------------------

    @Test
    fun `a job killed mid-transcription is recovered with its audio and stays retryable`() = runBlocking {
        val r = runner(ScriptedProvider())
        val job = submit(r, recording())
        // The process dies after compression, mid-upload.
        val prepared = stager().prepare(job) { }
        store.update(job.moveTo(JobState.TRANSCRIBING, clock, stagedPath = prepared.file.absolutePath))

        val restarted = runner(ScriptedProvider())
        val orphan = restarted.recoverOrphans().single()
        assertEquals(FailureReason.INTERRUPTED, orphan.failureReason)
        housekeeping()
        clock += 8 * day
        housekeeping()
        assertAudioKept(store.get(orphan.id)!!)
        assertTrue(restarted.canRetry(orphan.id))
    }

    @Test
    fun `a recording the process died writing is recovered from permanent storage`() {
        val target = RecordingFiles.newTarget(recordingsDir)
        val partial = RecordingFiles.inProgressFor(target)
        encodeWaveFile(partial, ShortArray(16_000))
        val recovered = RecordingFiles.recoverAbandoned(recordingsDir, store, clock).single()
        assertEquals(FailureReason.INTERRUPTED, recovered.failureReason)
        clock += 8 * day
        housekeeping()
        assertAudioKept(store.get(recovered.id)!!)
        assertTrue(runner(ScriptedProvider()).canRetry(recovered.id))
    }

    @Test
    fun `a finished recording no job knows about becomes a retryable job, not a deletion`() {
        val wav = recording()
        wav.setLastModified(clock - StorageJanitor.ORPHAN_GRACE_MS - 1)
        housekeeping()
        val job = store.all().single()
        assertEquals(wav.absolutePath, job.stagedPath)
        assertTrue(wav.isFile)
    }

    // ---- limits reset: Try again comes back ----------------------------------------

    @Test
    fun `a monthly refusal becomes retryable on the 1st and the retry completes`() = runBlocking {
        val provider = ScriptedProvider().apply { next = { refusal(FailureReason.FREE_MONTHLY_ALLOWANCE) } }
        val r = runner(provider)
        val wav = recording()
        val failed = r.run(submit(r, wav))

        clock = ist(2026, 9, 30, 23, 59)
        assertFalse("still September: the limit has not reset", r.canRetry(failed.id))
        assertFalse(store.attempts({ it }, now = clock).single().retryable)

        clock = ist(2026, 10, 1, 0, 0)
        assertTrue("1 October: the allowance has reset", r.canRetry(failed.id))
        assertTrue(store.attempts({ it }, now = clock).single().retryable)

        provider.next = { TranscriptionOutcome.Success("hello there", "mock") }
        val retried = r.retry(failed.id)!!
        val done = r.run(retried)
        assertEquals(JobState.COMPLETED, done.state)
        assertEquals("the retry re-sent the kept audio, not a new recording",
                     failed.stagedPath, done.stagedPath)
    }

    @Test
    fun `daily refusals reset at midnight and the rolling quota after 24 hours`() {
        listOf(FailureReason.FREE_NETWORK_DAILY_CAP, FailureReason.FREE_DAILY_BUDGET,
               FailureReason.OWNER_DAILY_LIMIT).forEach { code ->
            clock = ist(2026, 9, 24, 15, 0)
            val (r, failed) = failWith { refusal(code) }
            clock = ist(2026, 9, 24, 23, 59)
            assertFalse(code, r.canRetry(failed.id))
            clock = ist(2026, 9, 25, 0, 0)
            assertTrue(code, r.canRetry(failed.id))
        }
        clock = ist(2026, 9, 24, 15, 0)
        val refusedAt = clock
        val (r, failed) = failWith { refusal(FailureReason.DAILY_QUOTA) }
        clock = refusedAt + day - 1
        assertFalse(r.canRetry(failed.id))
        clock = refusedAt + day
        assertTrue(r.canRetry(failed.id))
    }

    @Test
    fun `a revoked installation never offers Try again, but the audio is still kept`() {
        val (r, failed) = failWith { refusal(FailureReason.REVOKED) }
        clock += 60 * day
        housekeeping()
        assertFalse(r.canRetry(failed.id))
        assertAudioKept(store.get(failed.id)!!)
    }

    @Test
    fun `reset times follow the backend's calendar windows`() {
        val refused = ist(2026, 9, 24, 15, 0)
        assertEquals(ist(2026, 10, 1), LimitReset.resetsAt(FailureReason.FREE_MONTHLY_ALLOWANCE, refused))
        assertEquals(ist(2026, 10, 1), LimitReset.resetsAt(FailureReason.OWNER_MONTHLY_LIMIT, refused))
        assertEquals(ist(2026, 9, 25), LimitReset.resetsAt(FailureReason.FREE_NETWORK_DAILY_CAP, refused))
        assertEquals(ist(2026, 9, 25), LimitReset.resetsAt(FailureReason.FREE_DAILY_BUDGET, refused))
        assertEquals(ist(2026, 9, 25), LimitReset.resetsAt(FailureReason.OWNER_DAILY_LIMIT, refused))
        assertEquals(refused + day, LimitReset.resetsAt(FailureReason.DAILY_QUOTA, refused))
        assertNull(LimitReset.resetsAt(FailureReason.REVOKED, refused))
        // December rolls into January.
        assertEquals(ist(2027, 1, 1), LimitReset.resetsAt(FailureReason.FREE_MONTHLY_ALLOWANCE,
                                                          ist(2026, 12, 31, 23, 30)))
        // A refusal at 01:30 on the 1st in India (still the 30th in UTC) is in
        // October's window, so it resets on 1 November.
        assertEquals(ist(2026, 11, 1), LimitReset.resetsAt(FailureReason.FREE_MONTHLY_ALLOWANCE,
                                                           ist(2026, 10, 1, 1, 30)))
        // Not limit refusals: never blocked, whatever the time.
        listOf(FailureReason.OFFLINE, FailureReason.INTERRUPTED, FailureReason.PROVIDER_TERMINAL,
               FailureReason.SERVICE_BUDGET, null).forEach {
            assertFalse("$it", LimitReset.blocksRetry(it, refused, refused))
        }
    }

    // ---- cleanup still removes what is genuinely finished ------------------------

    @Test
    fun `a successful transcription is unchanged and its audio is cleaned up`() = runBlocking {
        val r = runner(ScriptedProvider())
        val wav = recording()
        val done = r.run(submit(r, wav))
        assertEquals(JobState.COMPLETED, done.state)
        assertEquals("t-${done.id}", done.transcriptId)
        val compressed = File(done.stagedPath!!)
        housekeeping(listOf(wav, compressed))
        assertFalse(wav.exists())
        assertFalse(compressed.exists())
    }

    @Test
    fun `the janitor keeps a failed job's upload copy and removes partials and completed copies`() {
        val kept = File(uploadDir.apply { mkdirs() }, "rec-failed.m4a").apply { writeText("aac") }
        val done = File(uploadDir, "rec-done.m4a").apply { writeText("aac") }
        val partial = File(uploadDir, "rec-x.m4a.partial").apply { writeText("half") }
        store.update(JobRecord("failed", "file:///gone.wav", "Recording", JobState.FAILED, 1, clock,
                               failureReason = FailureReason.FREE_MONTHLY_ALLOWANCE,
                               stagedPath = kept.absolutePath))
        store.update(JobRecord("done", "file:///gone2.wav", "Recording", JobState.COMPLETED, 1, clock,
                               transcriptId = "t", stagedPath = done.absolutePath))
        housekeeping()
        assertTrue(kept.isFile)
        assertFalse(done.exists())
        assertFalse(partial.exists())
    }

    @Test
    fun `the 7-day prune removes only old imported copies, never recordings`() {
        val oldImport = File(importsDir, "a.ogg").apply { writeText("x") }
        val newImport = File(importsDir, "b.ogg").apply { writeText("x") }
        val rec = File(uploadDir.apply { mkdirs() }, "rec-c.m4a").apply { writeText("x") }
        val wav = recording()
        fun failed(id: String, f: File, at: Long) = JobRecord(id, "content://x", "n", JobState.FAILED, 1, at,
            failureReason = FailureReason.PROVIDER_TERMINAL, stagedPath = f.absolutePath)
        val jobs = listOf(failed("i1", oldImport, clock - 8 * day), failed("i2", newImport, clock - day),
                          failed("r1", rec, clock - 30 * day), failed("r2", wav, clock - 30 * day))
        assertEquals(listOf(oldImport), prunableImportCopies(jobs, importsDir, clock - 7 * day))
    }

    // ---- moving recordings an earlier version kept in the cache -----------------

    @Test
    fun `cache-era recordings are moved to permanent storage and their jobs repointed`() {
        val oldWav = File(cacheDir, "recording-a.wav").also { encodeWaveFile(it, ShortArray(16_000)) }
        val oldM4a = File(importsDir, "rec-j2.m4a").apply { writeText("aac") }
        val oldPartial = File(cacheDir, "recording-b.wav${RecordingFiles.IN_PROGRESS_SUFFIX}")
            .also { encodeWaveFile(it, ShortArray(16_000)) }
        val foreignImport = File(importsDir, "0f1e.ogg").apply { writeText("shared") }
        store.update(JobRecord("j1", "file://${oldWav.absolutePath}", "Recording", JobState.FAILED, 1, 111,
                               failureReason = FailureReason.OFFLINE, stagedPath = oldWav.absolutePath))
        store.update(JobRecord("j2", "file:///data/old/recording-z.wav", "Recording", JobState.FAILED, 1, 222,
                               failureReason = FailureReason.FREE_MONTHLY_ALLOWANCE,
                               stagedPath = oldM4a.absolutePath))

        assertEquals(3, RecordingStorage.migrateFromCache(cacheDir, filesDir, store))

        val j1 = store.get("j1")!!
        val j2 = store.get("j2")!!
        assertEquals(File(recordingsDir, "recording-a.wav").absolutePath, j1.stagedPath)
        assertEquals("file://${File(recordingsDir, "recording-a.wav").absolutePath}", j1.sourceUri)
        assertEquals(File(uploadDir, "rec-j2.m4a").absolutePath, j2.stagedPath)
        assertEquals("the refusal time is kept, so its reset is unchanged", 222L, j2.updatedAt)
        assertEquals(111L, j1.updatedAt)
        assertTrue(File(j1.stagedPath!!).isFile && File(j2.stagedPath!!).isFile)
        assertTrue(File(recordingsDir, oldPartial.name).isFile)
        assertFalse(oldWav.exists() || oldM4a.exists() || oldPartial.exists())
        assertTrue("imported copies are not recordings and stay put", foreignImport.isFile)

        assertEquals("a second run finds nothing to move", 0,
                     RecordingStorage.migrateFromCache(cacheDir, filesDir, store))
        // The moved in-progress file is then recovered like any other.
        assertEquals(1, RecordingFiles.recoverAbandoned(recordingsDir, store, clock).size)
    }

    @Test
    fun `a recording still being written is never moved`() {
        val live = File(cacheDir, "recording-live.wav${RecordingFiles.IN_PROGRESS_SUFFIX}")
            .also { encodeWaveFile(it, ShortArray(1_000)) }
        RecordingFiles.markActive(live)
        try {
            assertEquals(0, RecordingStorage.migrateFromCache(cacheDir, filesDir, store))
            assertTrue(live.isFile)
        } finally {
            RecordingFiles.markInactive(live)
        }
    }

    @Test
    fun `a moved recording in progress repairs into a playable WAV`() {
        val partial = File(cacheDir, "recording-p.wav${RecordingFiles.IN_PROGRESS_SUFFIX}")
            .also { encodeWaveFile(it, ShortArray(16_000)) }
        RecordingStorage.migrateFromCache(cacheDir, filesDir, store)
        val moved = File(recordingsDir, partial.name)
        assertTrue(WavWriter.repair(moved) > 0)
    }
}
