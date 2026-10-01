package com.whispercppdemo.ui.nav

/**
 * Navigation destinations.
 *
 * Home starts new work (Record audio opens the record-mode sheet, not a
 * route); History is the complete browser.
 */
object Dest {
    const val HOME = "home"
    const val HISTORY = "history"
    const val RECORDING = "recording"

    /** Asked before a capture mode starts when RECORD_AUDIO is not granted. */
    const val PERMISSION = "permission"
    const val PROCESSING = "processing"
    const val CANCELLED = "cancelled"
    const val FAILED = "failed"

    /** Shown once, before the first audio is ever uploaded. */
    const val DISCLOSURE = "disclosure"

    /** This installation's plan and usage, from History's options menu. Cloud builds only. */
    const val USAGE = "usage"

    /** Buying Irela Pro, from Usage. Cloud builds only. */
    const val UPGRADE = "upgrade"

    /** Transcript detail, addressed by the record's stable id. */
    const val DETAIL_ARG = "recordId"
    const val DETAIL_ROUTE = "detail/{$DETAIL_ARG}"
    fun detail(recordId: String) = "detail/$recordId"

    /** Plain text editor for one transcript, same id. */
    const val EDIT_ROUTE = "edit/{$DETAIL_ARG}"
    fun edit(recordId: String) = "edit/$recordId"

    /** The outcome screen for a stored failed or cancelled attempt from History. */
    const val ATTEMPT_ARG = "jobId"
    const val ATTEMPT_ROUTE = "attempt/{$ATTEMPT_ARG}"
    fun attempt(jobId: String) = "attempt/$jobId"
}
