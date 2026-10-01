package com.whispercppdemo

import com.whispercppdemo.jobs.FailureReason
import com.whispercppdemo.jobs.JobRunner
import com.whispercppdemo.jobs.JobState
import com.whispercppdemo.jobs.JobStore
import com.whispercppdemo.jobs.PreparedAudio
import com.whispercppdemo.jobs.failureMessageFor
import com.whispercppdemo.transcribe.provider.DeepgramProvider
import com.whispercppdemo.transcribe.provider.InstallationCredential
import com.whispercppdemo.ui.common.FailureCategory
import com.whispercppdemo.ui.common.FailureTone
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The backend's refusals other than the free tier -- quota, rate limit,
 * budget, kill switch, revoked, owner limits -- each told to the user in its
 * own words instead of "Transcription failed", with Try again offered only
 * where it can work. The three free-tier messages are pinned unchanged.
 */
class BackendRefusalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Backend status, the backend's own error text, our message, Try again offered, heading. */
    private data class Case(val reason: String, val status: Int, val serverText: String,
                            val message: String, val retry: Boolean, val category: FailureCategory)

    private val cases = listOf(
        Case("quota", 429, "You have reached today's transcription limit. Try again tomorrow.",
             "You have reached today's transcription limit. Please try again tomorrow.",
             false, FailureCategory.DAILY_LIMIT),
        Case("rate_limited", 429, "Too many transcriptions in a short time. Try again in a minute.",
             "Too many transcriptions in a short time. Wait a minute, then tap Try again.",
             true, FailureCategory.BUSY),
        Case("budget", 503, "Transcription is temporarily unavailable.",
             "Transcription is temporarily unavailable. Your audio is saved -- please try again later.",
             true, FailureCategory.UNAVAILABLE),
        Case("disabled", 503, "Transcription is temporarily unavailable.",
             "Transcription is paused right now. Your audio is saved -- please try again later.",
             true, FailureCategory.UNAVAILABLE),
        Case("revoked", 403, "Transcription is not available for this installation.",
             "Transcription is no longer available on this device.",
             false, FailureCategory.NOT_AVAILABLE),
        Case("owner_daily_limit_exceeded", 429,
             "Today's usage limit for this device has been reached. It resets tomorrow.",
             "You have reached today's usage limit on this device. It resets tomorrow.",
             false, FailureCategory.DAILY_LIMIT),
        Case("owner_monthly_limit_exceeded", 429,
             "This month's usage limit for this device has been reached.",
             "You have reached this month's usage limit on this device.",
             false, FailureCategory.MONTHLY_LIMIT))

    // ---- the mapping ---------------------------------------------------------------

    @Test
    fun `each refusal is stored under the backend's own code`() {
        cases.forEach { assertEquals(it.reason, FailureReason.fromBackendReason(it.reason)) }
        assertNull(FailureReason.fromBackendReason("some_future_reason"))
        assertNull(FailureReason.fromBackendReason(null))
        assertNull("not a refusal", FailureReason.fromBackendReason("providers_unavailable"))
    }

    @Test
    fun `each refusal has its own clear message, never the generic one`() {
        cases.forEach {
            assertEquals(it.reason, it.message, failureMessageFor(it.reason))
            assertNotEquals(it.reason, "Transcription failed.", failureMessageFor(it.reason))
        }
        val all = FailureReason.BACKEND_REFUSALS.map(::failureMessageFor)
        assertEquals("every refusal reads differently", all.size, all.toSet().size)
    }

    @Test
    fun `the three free-tier messages are exactly as before`() {
        assertEquals("You have used your 20 free minutes for this month.",
                     failureMessageFor(FailureReason.FREE_MONTHLY_ALLOWANCE))
        assertEquals("The free transcription limit for this network has been reached today.",
                     failureMessageFor(FailureReason.FREE_NETWORK_DAILY_CAP))
        assertEquals("Free transcription is temporarily unavailable today. Please try again tomorrow.",
                     failureMessageFor(FailureReason.FREE_DAILY_BUDGET))
        assertEquals(setOf("monthly_free_allowance_exceeded", "free_daily_network_cap_exceeded",
                           "free_daily_budget_exceeded"), FailureReason.FREE_TIER_REFUSALS)
        FailureReason.FREE_TIER_REFUSALS.forEach {
            assertEquals(FailureCategory.FREE_LIMIT, FailureCategory.forReason(it))
            assertTrue(FailureReason.blocksRetry(it))
        }
    }

    @Test
    fun `Try again is withheld only where it cannot work`() {
        cases.forEach { assertEquals(it.reason, !it.retry, FailureReason.blocksRetry(it.reason)) }
    }

    @Test
    fun `the heading fits, for stored attempts and for the live Failed screen`() {
        cases.forEach {
            assertEquals(it.reason, it.category, FailureCategory.forReason(it.reason))
            // The live Failed state carries the message, not the code.
            assertEquals(it.reason, it.category, FailureCategory.forMessage(it.message))
        }
        assertEquals("Daily limit reached", FailureCategory.DAILY_LIMIT.title)
        assertEquals("Monthly limit reached", FailureCategory.MONTHLY_LIMIT.title)
        assertEquals("Please wait a moment", FailureCategory.BUSY.title)
        assertEquals("Not available on this device", FailureCategory.NOT_AVAILABLE.title)
        listOf(FailureCategory.DAILY_LIMIT, FailureCategory.MONTHLY_LIMIT, FailureCategory.BUSY)
            .forEach { assertEquals("a limit is not an error", FailureTone.NEUTRAL, it.tone) }
    }

    // ---- end to end through the real client -------------------------------------------

    private lateinit var backend: FakeBackend
    private lateinit var credentials: InMemoryInstallationCredentialStore
    private lateinit var store: JobStore
    private lateinit var audio: File
    private var clock = 1_000L

    @Before
    fun setUp() {
        backend = FakeBackend().start()
        credentials = InMemoryInstallationCredentialStore(InstallationCredential("install-0", "token-0"))
        store = JobStore(tmp.newFolder("jobs"))
        audio = File(tmp.root, "clip.ogg").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
    }

    @After
    fun tearDown() = backend.stop()

    private fun runner() = JobRunner(
        store = store, provider = DeepgramProvider(credentials),
        prepare = { PreparedAudio(audio, "audio/ogg") },
        persistTranscript = { job, _, _ -> "t-${job.id}" },
        now = { clock++ }, sleep = { })

    @Test
    fun `every refusal fails the job once, under its own code, keeping the token`() = runBlocking {
        cases.forEachIndexed { i, c ->
            backend.requests.clear()
            backend.route("POST /v1/transcribe",
                c.status to """{"error":"${c.serverText}","retryable":false,"reason":"${c.reason}"}""")
            val r = runner()
            val job = r.run(r.submit("content://refusal-$i", "clip.ogg"))
            assertEquals(c.reason, JobState.FAILED, job.state)
            assertEquals(c.reason, c.reason, job.failureReason)
            assertEquals("${c.reason}: uploaded once, never retried automatically",
                         1, backend.calls("POST /v1/transcribe").size)
            assertEquals("${c.reason}: Try again", c.retry, r.canRetry(job.id))
            assertEquals("${c.reason}: a refusal is not an auth failure", 0, credentials.clears)
            assertEquals(c.message, failureMessageFor(job.failureReason))
        }
    }

    @Test
    fun `a refusal survives process death with its code and retry rule`() = runBlocking {
        backend.route("POST /v1/transcribe",
            429 to """{"error":"x","retryable":false,"reason":"quota"}""")
        val job = runner().let { r -> r.run(r.submit("content://x", "clip.ogg")) }
        val reloaded = JobStore(File(tmp.root, "jobs")).get(job.id)!!
        assertEquals("quota", reloaded.failureReason)
        assertFalse(runner().canRetry(reloaded.id))
    }

    @Test
    fun `an unknown reason still gets the generic handling`() = runBlocking {
        backend.route("POST /v1/transcribe",
            429 to """{"error":"Something else","retryable":false,"reason":"some_future_reason"}""")
        val job = runner().let { r -> r.run(r.submit("content://x", "clip.ogg")) }
        assertEquals(FailureReason.PROVIDER_TERMINAL, job.failureReason)
    }
}
