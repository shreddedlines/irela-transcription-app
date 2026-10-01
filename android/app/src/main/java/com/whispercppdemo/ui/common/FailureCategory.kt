package com.whispercppdemo.ui.common

import com.whispercppdemo.capture.BlockReason
import com.whispercppdemo.jobs.FailureReason
import com.whispercppdemo.jobs.failureMessageFor
import com.whispercppdemo.media.AudioLimits
import com.whispercppdemo.transcribe.Reachability

/**
 * The heading shown above a failure, from what actually failed.
 *
 * The body of the Failed screen is still the service's own message, unchanged;
 * this only chooses a short, accurate title and tone for it, so the user can
 * tell "no internet" from "this file can't be used" at a glance.
 */
enum class FailureTone { NEUTRAL, WARNING, ERROR }

enum class FailureCategory(val title: String, val tone: FailureTone) {
    OFFLINE("No internet connection", FailureTone.WARNING),
    UNAVAILABLE("Transcription is unavailable right now", FailureTone.WARNING),
    INTERRUPTED("Transcription was interrupted", FailureTone.NEUTRAL),
    NO_SPEECH("No speech found", FailureTone.NEUTRAL),
    UNUSABLE_AUDIO("Couldn't use this audio", FailureTone.ERROR),
    NOT_SAVED("Couldn't save the transcript", FailureTone.ERROR),
    MICROPHONE("Couldn't record", FailureTone.ERROR),
    RECORDING_ACTIVE("A recording is in progress", FailureTone.NEUTRAL),
    /** A server-side free-tier limit, not a failure: nothing went wrong. */
    FREE_LIMIT("Free limit reached", FailureTone.NEUTRAL),
    /** Today's transcription count, or an owner's daily limit. */
    DAILY_LIMIT("Daily limit reached", FailureTone.NEUTRAL),
    MONTHLY_LIMIT("Monthly limit reached", FailureTone.NEUTRAL),
    /** Too many requests in a minute: wait, then Try again. */
    BUSY("Please wait a moment", FailureTone.NEUTRAL),
    /** This installation was revoked by the service. */
    NOT_AVAILABLE("Not available on this device", FailureTone.ERROR),
    GENERIC("Transcription failed", FailureTone.ERROR);

    companion object {
        /** From a stored job's failure code (History attempts). */
        fun forReason(code: String?): FailureCategory = when (code) {
            FailureReason.OFFLINE -> OFFLINE
            FailureReason.RETRIES_EXHAUSTED, FailureReason.PROVIDER_TERMINAL,
            FailureReason.PROVIDER_UNAVAILABLE -> UNAVAILABLE
            FailureReason.INTERRUPTED -> INTERRUPTED
            FailureReason.EMPTY_TRANSCRIPT -> NO_SPEECH
            FailureReason.AUDIO_REJECTED -> UNUSABLE_AUDIO
            FailureReason.PERSISTENCE_FAILED -> NOT_SAVED
            in FailureReason.FREE_TIER_REFUSALS -> FREE_LIMIT
            FailureReason.DAILY_QUOTA, FailureReason.OWNER_DAILY_LIMIT,
            FailureReason.PRO_DAILY_LIMIT -> DAILY_LIMIT
            FailureReason.OWNER_MONTHLY_LIMIT, FailureReason.PRO_MONTHLY_LIMIT -> MONTHLY_LIMIT
            FailureReason.RATE_LIMITED -> BUSY
            FailureReason.SERVICE_BUDGET, FailureReason.SERVICE_DISABLED -> UNAVAILABLE
            FailureReason.REVOKED -> NOT_AVAILABLE
            else -> GENERIC
        }

        /**
         * From the message a live Failed state carries (that state holds text,
         * not a code). Service messages are matched against the same functions
         * and enums that produce them; the two ViewModel literals and the
         * import/limit prefixes are covered by FailureCategoryTest, so a wording
         * change there fails a test instead of silently mis-titling a screen.
         */
        fun forMessage(message: String?): FailureCategory {
            if (message == null) return GENERIC
            val byReason = (listOf(
                FailureReason.OFFLINE, FailureReason.RETRIES_EXHAUSTED, FailureReason.INTERRUPTED,
                FailureReason.EMPTY_TRANSCRIPT, FailureReason.AUDIO_REJECTED, FailureReason.PERSISTENCE_FAILED,
                FailureReason.PROVIDER_UNAVAILABLE
            ) + FailureReason.BACKEND_REFUSALS).firstOrNull { failureMessageFor(it) == message }
            if (byReason != null) return forReason(byReason)
            return when (message) {
                Reachability.INTERNET_UNAVAILABLE.userMessage -> OFFLINE
                Reachability.BACKEND_UNAVAILABLE.userMessage,
                Reachability.PROVIDER_UNAVAILABLE.userMessage -> UNAVAILABLE
                BlockReason.MICROPHONE_UNAVAILABLE.message -> MICROPHONE
                "The recording could not be saved. Please try again." -> MICROPHONE
                "Finish or cancel the recording first, then share again." -> RECORDING_ACTIVE
                else -> if (AudioLimits.message(AudioLimits.Verdict.Empty) == message ||
                    message.startsWith("That recording is longer than") ||
                    message.startsWith("That file is larger than") ||
                    message.startsWith("That file type") ||
                    message.startsWith("That audio could not") ||
                    message.startsWith("That file has no audio") ||
                    message.startsWith("That file contains no audio")) UNUSABLE_AUDIO else GENERIC
            }
        }
    }
}
