package com.whispercppdemo.jobs

/**
 * The one place a stored [FailureReason] becomes words a user reads.
 *
 * Shared rather than private to the service because History now renders the
 * same failures the Failed screen does, and two copies of this mapping would
 * drift into saying different things about the same job.
 */
fun failureMessageFor(reason: String?): String = when (reason) {
    FailureReason.INTERRUPTED -> "Transcription was interrupted. Tap to try again."
    FailureReason.RETRIES_EXHAUSTED -> "Transcription failed after several attempts."
    FailureReason.AUDIO_REJECTED -> "That audio could not be transcribed."
    FailureReason.PERSISTENCE_FAILED -> "The transcript could not be saved."
    FailureReason.EMPTY_TRANSCRIPT ->
        "No speech was found in this audio."
    FailureReason.OFFLINE ->
        "No internet connection, so this could not be transcribed. Tap Try again."
    FailureReason.PROVIDER_UNAVAILABLE ->
        "Transcription is temporarily unavailable. Your audio is saved -- " +
                "tap Try again in a few minutes."
    // Free-tier refusals: the backend states the limit, the app states it in
    // the user's words. Each one is terminal until the limit resets.
    FailureReason.FREE_MONTHLY_ALLOWANCE ->
        "You have used your 20 free minutes for this month."
    FailureReason.FREE_NETWORK_DAILY_CAP ->
        "The free transcription limit for this network has been reached today."
    FailureReason.FREE_DAILY_BUDGET ->
        "Free transcription is temporarily unavailable today. Please try again tomorrow."
    // Other backend refusals, each in the user's words instead of the generic
    // "Transcription failed". Temporary ones keep the audio for Try again.
    FailureReason.DAILY_QUOTA ->
        "You have reached today's transcription limit. Please try again tomorrow."
    FailureReason.RATE_LIMITED ->
        "Too many transcriptions in a short time. Wait a minute, then tap Try again."
    FailureReason.SERVICE_BUDGET ->
        "Transcription is temporarily unavailable. Your audio is saved -- please try again later."
    FailureReason.SERVICE_DISABLED ->
        "Transcription is paused right now. Your audio is saved -- please try again later."
    FailureReason.REVOKED ->
        "Transcription is no longer available on this device."
    FailureReason.OWNER_DAILY_LIMIT ->
        "You have reached today's usage limit on this device. It resets tomorrow."
    FailureReason.OWNER_MONTHLY_LIMIT ->
        "You have reached this month's usage limit on this device."
    FailureReason.PRO_DAILY_LIMIT ->
        "You have reached today's Pro limit of 2 hours. It resets at midnight. Your audio is saved."
    FailureReason.PRO_MONTHLY_LIMIT ->
        "You have used this billing period's 5 hours of Pro transcription. " +
                "Your audio is saved -- Try again returns when your hours reset."
    FailureReason.DISCLOSURE_REQUIRED ->
        "Your recording is saved. Review how transcription works, then tap Try again."
    else -> "Transcription failed."
}
