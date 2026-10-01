package com.whispercppdemo.media

/**
 * Enforced audio limits. Mirrors `shared/limits.json`, which is the
 * authoritative definition; `AudioLimitsParityTest` fails if the two drift.
 *
 * These REPLACE the old `LONG_AUDIO_WARN_MINUTES = 10`, which printed a
 * message and rejected nothing — a 30-minute import ran to completion with no
 * ceiling on time or provider spend.
 *
 * The client check exists to give the user an immediate, friendly answer
 * without a pointless upload. It is NOT the security boundary: the backend
 * re-checks every request, because anything in an installed app can be
 * modified.
 */
object AudioLimits {

    /** Keep in step with shared/limits.json. */
    const val VERSION = 1

    const val MAX_DURATION_SECONDS = 3600L          // 60 minutes (product requirement)
    const val MAX_UPLOAD_BYTES = 104_857_600L       // 100 MB

    /** For showing the user what the cap is, not for arithmetic. */
    const val MAX_DURATION_MINUTES = MAX_DURATION_SECONDS / 60

    /**
     * The product limit in milliseconds. Recordings are measured and capped
     * against this -- never against whole seconds, which accepted anything up
     * to 3600.999 s and let encoder padding push the upload past the backend.
     */
    const val MAX_DURATION_MS = MAX_DURATION_SECONDS * 1000

    /**
     * Framing the backend tolerates on the ENCODED file on top of the limit
     * (shared/limits.json `encoded_duration_tolerance_seconds`). The recorder
     * never captures more than [MAX_DURATION_MS] of audio; only AAC frame
     * padding may exceed it, and this bounds how much.
     */
    const val ENCODED_DURATION_TOLERANCE_MS = 500L

    /** Longest encoded duration an upload may have. */
    const val MAX_ENCODED_DURATION_MS = MAX_DURATION_MS + ENCODED_DURATION_TOLERANCE_MS

    sealed interface Verdict {
        object Ok : Verdict
        data class TooLong(val seconds: Long) : Verdict
        data class TooLarge(val bytes: Long) : Verdict
        object Empty : Verdict
    }

    /**
     * @param durationSeconds negative or zero when unknown — duration is not
     *        always available before decode, and an unknown duration must not
     *        be treated as a violation. The byte cap still applies, and the
     *        backend re-checks with the real duration.
     */
    fun check(bytes: Long, durationSeconds: Long = -1): Verdict = when {
        bytes <= 0L -> Verdict.Empty
        bytes > MAX_UPLOAD_BYTES -> Verdict.TooLarge(bytes)
        durationSeconds > MAX_DURATION_SECONDS -> Verdict.TooLong(durationSeconds)
        else -> Verdict.Ok
    }

    /**
     * The check made when a microphone recording stops.
     *
     * When the recording will be compressed before upload (CLOUD), the WAV's
     * own size is irrelevant -- the file that is uploaded is the compressed
     * one, and [check] runs again on it before any provider call. Applying the
     * byte cap to the WAV would reject every recording past ~54.6 minutes.
     * LOCAL reads the WAV itself, so it keeps the original check unchanged.
     */
    fun checkRecording(wavBytes: Long, durationMs: Long, compressedBeforeUpload: Boolean): Verdict {
        if (wavBytes <= 0L) return Verdict.Empty
        // Millisecond-accurate: 3600.001 s is over the limit.
        if (durationMs > MAX_DURATION_MS) return Verdict.TooLong((durationMs + 999) / 1000)
        if (!compressedBeforeUpload && wavBytes > MAX_UPLOAD_BYTES) return Verdict.TooLarge(wavBytes)
        return Verdict.Ok
    }

    /** Short, user-facing, no jargon and no numbers the user cannot act on. */
    fun message(v: Verdict): String? = when (v) {
        Verdict.Ok -> null
        Verdict.Empty -> "That file has no audio in it."
        is Verdict.TooLong ->
            "That recording is longer than $MAX_DURATION_MINUTES minutes. " +
                    "Try splitting it into shorter parts."
        is Verdict.TooLarge ->
            "That file is larger than ${MAX_UPLOAD_BYTES / 1024 / 1024} MB. " +
                    "Try a shorter recording."
    }
}
