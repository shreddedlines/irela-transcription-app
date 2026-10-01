package com.whispercppdemo.jobs

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The lifecycle of one transcription request.
 *
 *   PENDING -> QUEUED -> UPLOADING -> TRANSCRIBING -> COMPLETED
 *                            \__ RETRYING __/
 *                                   |
 *                                 FAILED
 *   (any non-terminal) -> CANCELLED
 *
 * PENDING and QUEUED are distinct on purpose. PENDING means "persisted, not yet
 * admitted to the queue" — it is written before anything can fail, which is what
 * makes a request impossible to lose silently. QUEUED means "accepted, waiting
 * for the worker".
 */
enum class JobState {
    PENDING, QUEUED, UPLOADING, TRANSCRIBING, RETRYING,
    COMPLETED, FAILED, CANCELLED;

    /** Still expected to make progress. An orphan in any of these is a loss. */
    val active: Boolean
        get() = this == PENDING || this == QUEUED || this == UPLOADING ||
                this == TRANSCRIBING || this == RETRYING

    val terminal: Boolean get() = !active
}

/** Why a job ended without a transcript. Kept for the user, not just logs. */
object FailureReason {
    /** The process died while the job was in flight. */
    const val INTERRUPTED = "interrupted"
    const val RETRIES_EXHAUSTED = "retries_exhausted"
    const val AUDIO_REJECTED = "audio_rejected"
    const val PROVIDER_TERMINAL = "provider_terminal"

    /**
     * The backend refused the job to protect the free tier, before contacting
     * any provider. The codes ARE the backend's own machine-readable reasons,
     * so a refusal survives process death in the job record exactly as it was
     * received, and no translation table can drift.
     *
     * Retrying cannot succeed until the allowance resets, so Try again is not
     * offered for these until then (see [LimitReset]); the audio is kept.
     */
    const val FREE_MONTHLY_ALLOWANCE = "monthly_free_allowance_exceeded"
    const val FREE_NETWORK_DAILY_CAP = "free_daily_network_cap_exceeded"
    const val FREE_DAILY_BUDGET = "free_daily_budget_exceeded"

    val FREE_TIER_REFUSALS = setOf(
        FREE_MONTHLY_ALLOWANCE, FREE_NETWORK_DAILY_CAP, FREE_DAILY_BUDGET)

    /**
     * The backend's other refusals, also stated before any provider is
     * contacted (backend client_auth.py and owner.py). Stored under the
     * backend's own codes, like the free-tier ones, so each gets its own
     * message instead of "Transcription failed".
     */
    const val DAILY_QUOTA = "quota"
    const val RATE_LIMITED = "rate_limited"
    const val SERVICE_BUDGET = "budget"
    const val SERVICE_DISABLED = "disabled"
    const val REVOKED = "revoked"
    const val OWNER_DAILY_LIMIT = "owner_daily_limit_exceeded"
    const val OWNER_MONTHLY_LIMIT = "owner_monthly_limit_exceeded"
    /** Irela Pro's own ceilings (backend pro.py); each refusal carries resets_at. */
    const val PRO_DAILY_LIMIT = "pro_daily_limit_exceeded"
    const val PRO_MONTHLY_LIMIT = "pro_monthly_limit_exceeded"

    /** Refusals that cannot succeed until a limit resets, or ever: no Try again. */
    val LIMIT_REFUSALS = setOf(DAILY_QUOTA, REVOKED, OWNER_DAILY_LIMIT, OWNER_MONTHLY_LIMIT,
                               PRO_DAILY_LIMIT, PRO_MONTHLY_LIMIT)

    /**
     * Temporary service states. The audio is kept and Try again is offered,
     * exactly as before these had their own codes -- they were stored as
     * PROVIDER_TERMINAL, which never blocked a retry. Never retried
     * automatically: the backend marks them retryable=false.
     */
    val TEMPORARY_REFUSALS = setOf(RATE_LIMITED, SERVICE_BUDGET, SERVICE_DISABLED)

    /** Every backend refusal code the app knows. */
    val BACKEND_REFUSALS: Set<String> = FREE_TIER_REFUSALS + LIMIT_REFUSALS + TEMPORARY_REFUSALS

    /** The stored code for a backend refusal reason, or null if not one of ours. */
    fun fromBackendReason(reason: String?): String? =
        reason?.takeIf { it in BACKEND_REFUSALS }

    /**
     * True for a limit refusal: retrying is pointless until the limit resets
     * (or, for revoked, ever). Whether it still blocks at a given moment is
     * [LimitReset.blocksRetry].
     */
    fun blocksRetry(reason: String?): Boolean =
        reason in FREE_TIER_REFUSALS || reason in LIMIT_REFUSALS
    const val PERSISTENCE_FAILED = "persistence_failed"

    /**
     * The device had no usable network and the engine needs one. Distinct
     * from RETRIES_EXHAUSTED because nothing was ever sent: no attempt was
     * made, no money was spent, and the user can fix it themselves.
     */
    const val OFFLINE = "offline"

    /**
     * The engine returned nothing. Treated as a FAILURE rather than a
     * success, because a zero-character History record is indistinguishable
     * from a bug and tells the user nothing about what happened.
     */
    const val EMPTY_TRANSCRIPT = "empty_transcript"

    /**
     * Every transcription provider was unavailable, after the backend's own
     * retries and failover and our bounded waits. The audio is kept; Try
     * again works once a provider is back.
     */
    const val PROVIDER_UNAVAILABLE = "provider_unavailable"

    /**
     * A recording finished while nobody had yet agreed to how cloud
     * transcription works. Kept, not sent: it transcribes on Try again after
     * the disclosure is accepted.
     */
    const val DISCLOSURE_REQUIRED = "disclosure_required"
}

/**
 * One persisted job.
 *
 * [id] doubles as the idempotency key for every attempt of this job, so a
 * retry can never be billed twice: all attempts carry the same key and the
 * backend collapses them. It is per logical job, never per attempt.
 */
data class JobRecord(
    val id: String,
    val sourceUri: String,
    val displayName: String,
    val state: JobState,
    val createdAt: Long,
    val updatedAt: Long,
    /** Completed attempts. 0 on the first try; retry 1 and 2 follow. */
    val attempt: Int = 0,
    val failureReason: String? = null,
    /** Id of the TranscriptRecord this job produced, once COMPLETED. */
    val transcriptId: String? = null,
    /** Provider that produced (or last attempted) the result. */
    val providerId: String? = null,
    /**
     * Hash of the audio bytes, known only after staging. Used to avoid paying
     * a provider twice for the same audio arriving by different URIs.
     */
    val contentHash: String? = null,
    /**
     * Our own copy of the audio, kept for FAILED jobs so Retry can work.
     *
     * The original share URI cannot be re-read: an ACTION_SEND grant lives only
     * as long as the receiving task, so by the time a user taps Retry the
     * permission is usually gone. Retrying our staged copy is the only way the
     * button can be honest.
     */
    val stagedPath: String? = null,
    /**
     * The idempotency key inherited from the job this one retries. Null for an
     * original job, whose key is its id.
     */
    val idempotencyToken: String? = null,
    /**
     * When the limit that refused this job resets (epoch ms), as the backend
     * stated it (`resets_at`). Only Pro refusals carry one -- a billing cycle
     * cannot be derived on the phone; the others fall back to LimitReset's
     * calendar rules. Null for everything else.
     */
    val resetsAt: Long? = null
) {
    val idempotencyKey: String get() = idempotencyToken ?: id

    fun moveTo(state: JobState, now: Long,
               failureReason: String? = this.failureReason,
               transcriptId: String? = this.transcriptId,
               providerId: String? = this.providerId,
               attempt: Int = this.attempt,
               contentHash: String? = this.contentHash,
               stagedPath: String? = this.stagedPath): JobRecord =
        copy(state = state, updatedAt = now, failureReason = failureReason,
             transcriptId = transcriptId, providerId = providerId,
             attempt = attempt, contentHash = contentHash,
             stagedPath = stagedPath)

    // ---- serialisation ----------------------------------------------------
    // Same shape as TranscriptHistoryRepository: one small text file per
    // record, fields URL-encoded so a display name containing a newline or '='
    // round-trips. Free of Android types so the whole lifecycle is exercisable
    // by ordinary JVM unit tests.

    fun encode(): String = buildString {
        append("v=").append(FORMAT_VERSION).append('\n')
        append("id=").append(esc(id)).append('\n')
        append("uri=").append(esc(sourceUri)).append('\n')
        append("name=").append(esc(displayName)).append('\n')
        append("state=").append(state.name).append('\n')
        append("createdAt=").append(createdAt).append('\n')
        append("updatedAt=").append(updatedAt).append('\n')
        append("attempt=").append(attempt).append('\n')
        append("failureReason=").append(esc(failureReason ?: "")).append('\n')
        append("transcriptId=").append(esc(transcriptId ?: "")).append('\n')
        append("providerId=").append(esc(providerId ?: "")).append('\n')
        append("contentHash=").append(esc(contentHash ?: "")).append('\n')
        append("stagedPath=").append(esc(stagedPath ?: "")).append('\n')
        // Optional and absent from records written before it existed, so
        // existing job files still decode (same FORMAT_VERSION).
        if (idempotencyToken != null) append("idem=").append(esc(idempotencyToken)).append('\n')
        // Likewise optional: older records decode with no reset instant.
        if (resetsAt != null) append("resetsAt=").append(resetsAt).append('\n')
    }

    companion object {
        const val FORMAT_VERSION = "1"
        private const val CHARSET = "UTF-8"

        private fun esc(s: String) = URLEncoder.encode(s, CHARSET)
        private fun unesc(s: String) = URLDecoder.decode(s, CHARSET)

        fun decode(raw: String): JobRecord {
            val f = raw.lineSequence().filter { it.isNotEmpty() }.mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
            }.toMap()
            require(f["v"] == FORMAT_VERSION) { "unsupported job record version" }
            return JobRecord(
                id = unesc(requireNotNull(f["id"])),
                sourceUri = unesc(f["uri"].orEmpty()),
                displayName = unesc(f["name"].orEmpty()),
                state = JobState.valueOf(requireNotNull(f["state"])),
                createdAt = requireNotNull(f["createdAt"]).toLong(),
                updatedAt = requireNotNull(f["updatedAt"]).toLong(),
                attempt = f["attempt"]?.toIntOrNull() ?: 0,
                failureReason = unesc(f["failureReason"].orEmpty()).ifBlank { null },
                transcriptId = unesc(f["transcriptId"].orEmpty()).ifBlank { null },
                providerId = unesc(f["providerId"].orEmpty()).ifBlank { null },
                contentHash = unesc(f["contentHash"].orEmpty()).ifBlank { null },
                stagedPath = unesc(f["stagedPath"].orEmpty()).ifBlank { null },
                idempotencyToken = unesc(f["idem"].orEmpty()).ifBlank { null },
                resetsAt = f["resetsAt"]?.toLongOrNull()
            )
        }
    }
}
