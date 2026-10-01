package com.whispercppdemo.transcribe.provider

import java.io.File

/**
 * The app's only view of a transcription backend.
 *
 * Everything above this interface — the service, the job queue, the UI — is
 * provider-agnostic. Adding AssemblyAI fallback, or a second provider for
 * languages Deepgram does not serve, must not require touching a call site.
 *
 * NOTE ON CREDENTIALS: no implementation of this interface may hold a provider
 * API key. A key shipped inside an APK is extractable, always. Implementations
 * talk to our own backend proxy, which holds the credentials and is the only
 * place a provider's frozen configuration is authoritative.
 */
interface TranscriptionProvider {

    /** Stable id used in logs, job records and routing tables. */
    val id: String

    /** Human-readable name for user-visible error messages. */
    val displayName: String

    /**
     * Transcribes [request]. Never throws for an expected failure — a failure
     * is a value, so the caller can classify and decide on retry or fallback.
     * [onProgress] reports upload fraction 0..1 where the transport can
     * measure it, and is not called at all where it cannot: the UI shows an
     * indeterminate state rather than an invented number.
     */
    suspend fun transcribe(
        request: TranscriptionRequest,
        onProgress: (Float) -> Unit = {}
    ): TranscriptionOutcome

    /**
     * Where an already-uploaded job stands, WITHOUT sending the audio again --
     * used while the backend reports it is still processing. Null means "no
     * record of it / not supported": the caller uploads again, and the shared
     * idempotency key still prevents a second provider charge.
     */
    suspend fun checkStatus(request: TranscriptionRequest): TranscriptionOutcome? = null
}

/**
 * One transcription request.
 *
 * [idempotencyKey] is what stops a retry from being billed twice. It is
 * derived from the audio content and the job, not from the attempt, so all
 * attempts of one job carry the same key and the backend can collapse them.
 */
data class TranscriptionRequest(
    val audio: File,
    val mimeType: String,
    val idempotencyKey: String,
    /** BCP-47-ish hint. null lets the provider decide. */
    val languageHint: String? = null,
    /**
     * Proper nouns and domain terms to bias recognition toward. Measured
     * effect: without them a name in the audio can be dropped silently
     * (`वेंकटेश` vanished from an otherwise correct transcript); supplying it
     * restored the name on every codec with a one-word transcript diff.
     *
     * The app does not yet generate these automatically — this is the
     * insertion point, deliberately left for the caller to fill.
     */
    val keyterms: List<String> = emptyList()
)

sealed interface TranscriptionOutcome {

    data class Success(
        val text: String,
        val providerId: String,
        val detectedLanguage: String? = null,
        val providerMillis: Long? = null,
        /**
         * Length of the audio that was transcribed, as measured by whoever
         * transcribed it (the backend reports the provider's own figure; the
         * local engine counts decoded samples). Null when not reported. Never
         * a wall-clock time.
         */
        val audioDurationMs: Long? = null
    ) : TranscriptionOutcome

    data class Failure(
        val kind: FailureKind,
        /** Safe to show a user. Never contains transcript content. */
        val message: String,
        val httpStatus: Int? = null,
        val providerId: String? = null,
        /** The backend's machine-readable reason, when it gave one. */
        val reason: String? = null,
        /** How long the backend asked the client to wait, when it said. */
        val retryAfterMs: Long? = null,
        /** When a limit refusal's limit resets (epoch ms), from `resets_at`. */
        val resetsAtMs: Long? = null
    ) : TranscriptionOutcome
}

/**
 * How a failure should be handled. The distinction is the whole point: a 4xx
 * retried is wasted money, and a 5xx not retried is a lost transcription.
 */
enum class FailureKind {
    /** Transport failed before the provider saw it. Retry. */
    RETRYABLE_NETWORK,

    /** Provider 5xx. Retry with backoff. */
    RETRYABLE_SERVER,

    /** 429. Retry after a longer wait; never immediately. */
    RATE_LIMITED,

    /** Exceeded our own or the provider's time budget. Retry once. */
    TIMEOUT,

    /**
     * Every transcription provider behind the backend is unavailable right
     * now (outage, exhausted credits, rate limit). The backend has already
     * retried and failed over; the audio is fine. Wait as told, a bounded
     * number of times, then stop with the audio kept for Try again.
     */
    PROVIDER_UNAVAILABLE,

    /**
     * The backend is still transcribing this job (a long file, or a provider
     * that is slow today) and asked us to check back. Asking again with the
     * same idempotency key joins the running work -- it is never started twice.
     */
    PROCESSING,

    /** Audio is unusable: unsupported, corrupt, empty, too long. Do not retry. */
    TERMINAL_INPUT,

    /** Auth/quota/billing on our side. Do not retry; alert us, not the user. */
    TERMINAL_AUTH,

    /** Anything unclassified. Do not retry automatically. */
    TERMINAL_UNKNOWN,

    /** The user cancelled. Not an error. */
    CANCELLED;

    val retryable: Boolean
        get() = this == RETRYABLE_NETWORK || this == RETRYABLE_SERVER ||
                this == RATE_LIMITED || this == TIMEOUT ||
                this == PROVIDER_UNAVAILABLE || this == PROCESSING
}
