package com.whispercppdemo

import com.whispercppdemo.jobs.FailureReason
import com.whispercppdemo.jobs.JobRecord
import com.whispercppdemo.jobs.JobRunner
import com.whispercppdemo.jobs.JobState
import com.whispercppdemo.jobs.JobStore
import com.whispercppdemo.jobs.LimitReset
import com.whispercppdemo.jobs.PreparedAudio
import com.whispercppdemo.jobs.attempts
import com.whispercppdemo.jobs.failureMessageFor
import com.whispercppdemo.transcribe.provider.DeepgramProvider
import com.whispercppdemo.transcribe.provider.InstallationCredential
import com.whispercppdemo.ui.common.FailureCategory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/**
 * Irela Pro's limit refusals: stored under the backend's code WITH the
 * backend's reset instant (`resets_at`), Try again returning exactly then --
 * across process death -- and the recording kept throughout.
 */
class ProRefusalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var backend: FakeBackend
    private lateinit var credentials: InMemoryInstallationCredentialStore
    private lateinit var store: JobStore
    private lateinit var audio: File
    private var clock = 0L

    private val resetsIso = "2026-10-27T15:30:00+05:30"
    private val resetsMs = OffsetDateTime.parse(resetsIso).toInstant().toEpochMilli()
    private fun ist(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, LimitReset.ZONE).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        backend = FakeBackend().start()
        credentials = InMemoryInstallationCredentialStore(InstallationCredential("install-0", "token-0"))
        store = JobStore(tmp.newFolder("jobs"))
        audio = File(tmp.root, "rec-kept.m4a").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        clock = ist(2026, 10, 10, 12, 0)
    }

    @After
    fun tearDown() = backend.stop()

    private fun runner(s: JobStore = store) = JobRunner(
        store = s, provider = DeepgramProvider(credentials),
        prepare = { PreparedAudio(audio, "audio/mp4") },
        persistTranscript = { job, _, _ -> "t-${job.id}" },
        now = { clock }, sleep = { })

    private fun refuse(reason: String, resetsAt: String?) {
        val extra = resetsAt?.let { ""","resets_at":"$it"""" } ?: ""
        backend.route("POST /v1/transcribe",
            429 to """{"error":"limit","retryable":false,"reason":"$reason"$extra}""")
    }

    @Test
    fun `a Pro cycle refusal keeps the backend's reset instant and Try again returns exactly then`() = runBlocking {
        refuse(FailureReason.PRO_MONTHLY_LIMIT, resetsIso)
        val r = runner()
        val job = r.run(r.submit("content://x", "clip"))
        assertEquals(JobState.FAILED, job.state)
        assertEquals(FailureReason.PRO_MONTHLY_LIMIT, job.failureReason)
        assertEquals(resetsMs, job.resetsAt)
        assertEquals("uploaded once, never retried automatically", 1, backend.calls("POST /v1/transcribe").size)
        assertFalse(r.canRetry(job.id))
        clock = resetsMs - 1
        assertFalse(r.canRetry(job.id))
        clock = resetsMs
        assertTrue(r.canRetry(job.id))
        assertTrue("the recording is kept for that Try again", File(job.stagedPath!!).isFile)
    }

    @Test
    fun `the reset instant survives process death and drives History too`() = runBlocking {
        refuse(FailureReason.PRO_MONTHLY_LIMIT, resetsIso)
        val job = runner().let { r -> r.run(r.submit("content://x", "clip")) }
        val reloaded = JobStore(File(tmp.root, "jobs"))
        assertEquals(resetsMs, reloaded.get(job.id)!!.resetsAt)
        assertFalse(reloaded.attempts({ it }, now = resetsMs - 1).single().retryable)
        assertTrue(reloaded.attempts({ it }, now = resetsMs).single().retryable)
        clock = resetsMs
        assertTrue(runner(reloaded).canRetry(job.id))
    }

    @Test
    fun `a Pro daily refusal without resets_at falls back to India midnight`() = runBlocking {
        refuse(FailureReason.PRO_DAILY_LIMIT, null)
        val r = runner()
        val job = r.run(r.submit("content://x", "clip"))
        assertNull(job.resetsAt)
        clock = ist(2026, 10, 10, 23, 59)
        assertFalse(r.canRetry(job.id))
        clock = ist(2026, 10, 11)
        assertTrue(r.canRetry(job.id))
    }

    @Test
    fun `a Pro cycle refusal without resets_at is offered again a day later`() {
        val at = ist(2026, 10, 10, 12)
        assertTrue(LimitReset.blocksRetry(FailureReason.PRO_MONTHLY_LIMIT, at, at + 86_400_000L - 1))
        assertFalse(LimitReset.blocksRetry(FailureReason.PRO_MONTHLY_LIMIT, at, at + 86_400_000L))
    }

    @Test
    fun `a stated reset wins over the calendar rules, but never un-revokes`() {
        val at = ist(2026, 10, 10, 12)
        // Free monthly would wait for 1 November; a stated instant is used instead.
        assertFalse(LimitReset.blocksRetry(FailureReason.FREE_MONTHLY_ALLOWANCE, at, at + 1000,
                                           statedResetsAt = at + 1000))
        assertTrue(LimitReset.blocksRetry(FailureReason.REVOKED, at, at + 10_000_000_000L,
                                          statedResetsAt = at))
        assertFalse("non-limit failures are never blocked",
                    LimitReset.blocksRetry(FailureReason.OFFLINE, at, at, statedResetsAt = at + 1))
    }

    @Test
    fun `free and owner refusals behave exactly as before`() = runBlocking {
        refuse(FailureReason.FREE_MONTHLY_ALLOWANCE, null)
        val r = runner()
        val job = r.run(r.submit("content://x", "clip"))
        assertNull(job.resetsAt)
        clock = ist(2026, 10, 31, 23, 59)
        assertFalse(r.canRetry(job.id))
        clock = ist(2026, 11, 1)
        assertTrue(r.canRetry(job.id))
    }

    @Test
    fun `a job record without resetsAt still decodes, and one with it round-trips`() {
        val base = JobRecord("j", "file:///a", "n", JobState.FAILED, 1L, 2L,
                             failureReason = FailureReason.PRO_MONTHLY_LIMIT, stagedPath = "/a")
        assertEquals(base, JobRecord.decode(base.encode()))
        assertFalse(base.encode().contains("resetsAt="))
        val withReset = base.copy(resetsAt = resetsMs)
        assertEquals(withReset, JobRecord.decode(withReset.encode()))
    }

    @Test
    fun `Pro refusals read in their own words under the limit headings`() {
        val daily = failureMessageFor(FailureReason.PRO_DAILY_LIMIT)
        val cycle = failureMessageFor(FailureReason.PRO_MONTHLY_LIMIT)
        assertTrue(daily.contains("2 hours") && daily.contains("audio is saved"))
        assertTrue(cycle.contains("5 hours") && cycle.contains("audio is saved"))
        assertEquals(FailureCategory.DAILY_LIMIT, FailureCategory.forReason(FailureReason.PRO_DAILY_LIMIT))
        assertEquals(FailureCategory.MONTHLY_LIMIT, FailureCategory.forReason(FailureReason.PRO_MONTHLY_LIMIT))
        assertEquals(FailureCategory.MONTHLY_LIMIT, FailureCategory.forMessage(cycle))
        assertTrue(FailureReason.PRO_DAILY_LIMIT in FailureReason.BACKEND_REFUSALS)
        assertTrue(FailureReason.blocksRetry(FailureReason.PRO_MONTHLY_LIMIT))
    }
}
