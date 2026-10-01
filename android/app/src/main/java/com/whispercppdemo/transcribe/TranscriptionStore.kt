package com.whispercppdemo.transcribe

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Coarse job state, kept outside the Activity so recreation cannot lose it. */
sealed interface TranscriptionState {
    object Idle : TranscriptionState

    /** Persisted and accepted, waiting for the worker. [ahead] = jobs in front. */
    data class Queued(val name: String?, val ahead: Int = 0) : TranscriptionState

    data class Staging(val name: String?) : TranscriptionState

    /**
     * Sending audio to the provider. [progress] is the real transmitted
     * fraction, or null when the transport cannot report one -- in which case
     * the UI shows an indeterminate bar rather than inventing a number.
     */
    data class Uploading(val name: String?, val progress: Float?) : TranscriptionState

    /**
     * A transient failure is being retried. [attempt] is 1-based and
     * [maxAttempts] is the bound, so the UI can say "attempt 2 of 3" instead of
     * implying an unbounded loop.
     */
    data class Retrying(
        val name: String?,
        val attempt: Int,
        val maxAttempts: Int,
        val reason: String? = null
    ) : TranscriptionState
    data class Decoding(val name: String?, val progress: Float) : TranscriptionState
    /**
     * [startedAtMs] is the wall clock at which chunk 1 began, or 0 when not
     * known yet. Wall clock rather than elapsed so it survives Activity
     * recreation -- the UI derives the remaining-time estimate from it.
     */
    data class Transcribing(
        val name: String?,
        val chunk: Int,
        val chunks: Int,
        val startedAtMs: Long = 0L
    ) : TranscriptionState
    /**
     * A finished transcription.
     *
     * [transcriptId] is the record this job produced OR reused, and it is the
     * only reliable way to open the result. The UI used to find the record by
     * looking for one that had not existed when the job started -- which works
     * for a fresh transcription and cannot work at all for a duplicate, where
     * the record was already there. That left completed duplicates stranded on
     * the Processing screen.
     *
     * Null only on the legacy in-ViewModel paths that persist their own
     * record; those still fall back to the search.
     */
    data class Done(
        val name: String?,
        val text: String,
        val transcriptId: String? = null,
        /** The job this completion belongs to. See ui/common/JobFollow.kt. */
        val jobId: String? = null
    ) : TranscriptionState
    /**
     * [jobId] identifies the persisted record behind this failure and
     * [retryable] is the runner's own answer to "would Retry actually work" --
     * which depends on our staged copy still existing, not on the failure
     * kind. Both default to "no retry" so any path that publishes a failure
     * without a job behind it simply offers no button, rather than offering
     * one that cannot work.
     */
    data class Failed(
        val name: String?,
        val reason: String,
        val jobId: String? = null,
        val retryable: Boolean = false
    ) : TranscriptionState

    data class Cancelled(
        val name: String?,
        val jobId: String? = null,
        val retryable: Boolean = false
    ) : TranscriptionState

    /**
     * Work is outstanding. Queued and Retrying count: the user's request is
     * still alive, so the UI must not offer to start another one or claim the
     * app is idle.
     */
    val isRunning: Boolean
        get() = this is Queued || this is Staging || this is Uploading ||
                this is Decoding || this is Transcribing || this is Retrying
}

/**
 * Process-wide transcription state and log.
 *
 * Deliberately NOT owned by the Activity or its ViewModel. The Activity is
 * destroyed on rotation and while the user is away in WhatsApp; the service
 * and this store are not. The UI observes; it never owns the job.
 *
 * Survives: Activity recreation, backgrounding, returning to the app.
 * Does NOT survive: process death (the decoded PCM is gone with it, so the
 * job cannot be resumed and is not restarted -- see START_NOT_STICKY).
 */
object TranscriptionStore {

    private val _state = MutableStateFlow<TranscriptionState>(TranscriptionState.Idle)
    val state: StateFlow<TranscriptionState> = _state.asStateFlow()

    /**
     * Requested job id -> the job the service actually ran. Written only when
     * a request is attached to an already-active job for the same source, so
     * the UI following the requested id still recognises that job's events.
     */
    private val _aliases = MutableStateFlow<Map<String, String>>(emptyMap())
    val aliases: StateFlow<Map<String, String>> = _aliases.asStateFlow()

    fun alias(requestedJobId: String, actualJobId: String) {
        if (requestedJobId != actualJobId) _aliases.update { it + (requestedJobId to actualJobId) }
    }

    private val _log = MutableStateFlow("")
    val log: StateFlow<String> = _log.asStateFlow()

    fun setState(next: TranscriptionState) {
        _state.value = next
    }

    fun append(message: String) {
        _log.update { it + message }
    }

    fun clearLog() {
        _log.value = ""
    }
}
