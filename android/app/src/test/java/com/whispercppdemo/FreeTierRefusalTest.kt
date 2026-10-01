package com.whispercppdemo

import com.whispercppdemo.jobs.FailureReason
import com.whispercppdemo.jobs.JobAttempt
import com.whispercppdemo.jobs.JobRecord
import com.whispercppdemo.jobs.JobState
import com.whispercppdemo.jobs.JobStore
import com.whispercppdemo.jobs.attempts
import com.whispercppdemo.jobs.failureMessageFor
import com.whispercppdemo.ui.common.FailureCategory
import com.whispercppdemo.ui.common.FailureTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Server-side free-tier refusals are a limit, not a failure: they carry the
 * backend's own reason, say which limit was reached, are titled neutrally, and
 * never offer Try again (retrying cannot succeed until the limit resets).
 */
class FreeTierRefusalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val expected = mapOf(
        FailureReason.FREE_MONTHLY_ALLOWANCE to
            "You have used your 20 free minutes for this month.",
        FailureReason.FREE_NETWORK_DAILY_CAP to
            "The free transcription limit for this network has been reached today.",
        FailureReason.FREE_DAILY_BUDGET to
            "Free transcription is temporarily unavailable today. Please try again tomorrow."
    )

    @Test
    fun theCodesAreTheBackendsOwnReasons() {
        assertEquals(setOf("monthly_free_allowance_exceeded", "free_daily_network_cap_exceeded",
                           "free_daily_budget_exceeded"), FailureReason.FREE_TIER_REFUSALS)
        FailureReason.FREE_TIER_REFUSALS.forEach {
            assertEquals(it, FailureReason.fromBackendReason(it))
        }
        assertEquals(null, FailureReason.fromBackendReason("some_future_reason"))
        assertEquals(null, FailureReason.fromBackendReason(null))
    }

    @Test
    fun eachRefusalHasItsOwnUserFacingMessage() {
        expected.forEach { (code, message) -> assertEquals(message, failureMessageFor(code)) }
        assertEquals(3, expected.values.toSet().size)
    }

    @Test
    fun theTitleIsNeutralAndSharedByAllThree() {
        expected.forEach { (code, message) ->
            assertEquals(FailureCategory.FREE_LIMIT, FailureCategory.forReason(code))
            assertEquals("Free limit reached", FailureCategory.forReason(code).title)
            assertEquals(FailureTone.NEUTRAL, FailureCategory.forReason(code).tone)
            // The live Failed state carries the message, not the code.
            assertEquals(FailureCategory.FREE_LIMIT, FailureCategory.forMessage(message))
        }
    }

    @Test
    fun retryIsBlockedForRefusalsAndUnaffectedForEverythingElse() {
        FailureReason.FREE_TIER_REFUSALS.forEach { assertTrue(FailureReason.blocksRetry(it)) }
        listOf(FailureReason.PROVIDER_TERMINAL, FailureReason.RETRIES_EXHAUSTED,
               FailureReason.OFFLINE, FailureReason.AUDIO_REJECTED, FailureReason.INTERRUPTED,
               FailureReason.EMPTY_TRANSCRIPT, FailureReason.PERSISTENCE_FAILED, null)
            .forEach { assertFalse("$it", FailureReason.blocksRetry(it)) }
    }

    // ---- History ----------------------------------------------------------

    private fun failedJob(id: String, reason: String): JobRecord {
        val staged = File(tmp.root, "$id.aac").apply { writeText("audio") }
        return JobRecord(id = id, sourceUri = "file://$staged", displayName = "clip.aac",
                         state = JobState.FAILED, createdAt = 1L, updatedAt = 2L,
                         failureReason = reason, stagedPath = staged.absolutePath)
    }

    private fun attemptsOf(vararg jobs: JobRecord): List<JobAttempt> {
        val store = JobStore(tmp.newFolder())
        jobs.forEach { store.update(it) }
        // Seen right after the refusal (updatedAt = 2): its limit has not reset.
        // Retry after the reset is RecordingRetentionTest's subject.
        return store.attempts({ failureMessageFor(it) }, now = 2L)
    }

    @Test
    fun aRefusedJobInHistoryShowsTheLimitAndNoTryAgain() {
        expected.forEach { (code, message) ->
            val a = attemptsOf(failedJob("j-$code", code)).single()
            assertEquals(message, a.reason)
            assertEquals(code, a.failureReason)
            assertFalse("Try again must not be offered for $code", a.retryable)
            assertEquals(FailureCategory.FREE_LIMIT, FailureCategory.forReason(a.failureReason))
        }
    }

    @Test
    fun aGenericProviderFailureInHistoryIsUnchanged() {
        val a = attemptsOf(failedJob("j-generic", FailureReason.PROVIDER_TERMINAL)).single()
        assertEquals("Transcription failed.", a.reason)
        assertTrue("its staged audio still makes Try again available", a.retryable)
        assertEquals(FailureCategory.UNAVAILABLE, FailureCategory.forReason(a.failureReason))
    }

    @Test
    fun aRefusalIsPersistedAndRestoredFromTheStore() {
        val store = JobStore(tmp.newFolder("jobs"))
        val job = store.create("content://x", "clip.aac", 1L)
        store.update(job.copy(state = JobState.FAILED,
                              failureReason = FailureReason.FREE_MONTHLY_ALLOWANCE))
        val restored = JobStore(File(tmp.root, "jobs")).get(job.id)!!    // a restarted process
        assertEquals(FailureReason.FREE_MONTHLY_ALLOWANCE, restored.failureReason)
        assertEquals(expected[FailureReason.FREE_MONTHLY_ALLOWANCE], failureMessageFor(restored.failureReason))
    }
}
