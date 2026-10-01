package com.whispercppdemo.jobs

import java.io.File

/**
 * A transcription attempt that produced no transcript.
 *
 * History has always shown only [com.whispercppdemo.history.TranscriptRecord]s,
 * which by construction are successful completions -- so a job that failed or
 * was cancelled left no trace anywhere the user could see it, and a share that
 * failed while the user was back in WhatsApp simply vanished. This is the
 * projection that makes those visible, deliberately kept to display fields:
 * the job record stays the source of truth.
 */
data class JobAttempt(
    val jobId: String,
    val displayName: String,
    val createdAt: Long,
    val cancelled: Boolean,
    val reason: String?,
    val retryable: Boolean,
    /**
     * The stored [FailureReason] code behind [reason], so an outcome screen can
     * title the failure accurately. Null for cancellations. Display only.
     */
    val failureReason: String? = null
)

/**
 * The failed and cancelled jobs worth showing, newest first.
 *
 * COMPLETED jobs are excluded: their transcript is already in History and
 * listing the job as well would double every successful entry. Active jobs are
 * excluded too -- those are the Processing screen's business, not History's.
 */
fun JobStore.attempts(
    describe: (String?) -> String?,
    exists: (String) -> Boolean = { File(it).isFile },
    now: Long = System.currentTimeMillis()
): List<JobAttempt> =
    all().asSequence()
        .filter { it.state == JobState.FAILED || it.state == JobState.CANCELLED }
        .sortedByDescending { it.createdAt }
        .map { job ->
            JobAttempt(
                jobId = job.id,
                displayName = job.displayName.ifBlank { "Shared audio" },
                createdAt = job.createdAt,
                cancelled = job.state == JobState.CANCELLED,
                // Cancellation is the user's own doing, so it needs no
                // explanation; a failure does.
                reason = if (job.state == JobState.CANCELLED) null
                         else describe(job.failureReason),
                // Same test the runner uses: the staged copy must still be
                // there, or Retry would fail the moment it was pressed.
                retryable = job.stagedPath?.let(exists) == true &&
                        !LimitReset.blocksRetry(job.failureReason, job.updatedAt, now,
                                                statedResetsAt = job.resetsAt),
                failureReason = if (job.state == JobState.CANCELLED) null else job.failureReason
            )
        }
        .toList()
