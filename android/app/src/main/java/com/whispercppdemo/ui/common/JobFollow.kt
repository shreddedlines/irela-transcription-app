package com.whispercppdemo.ui.common

import com.whispercppdemo.transcribe.TranscriptionState

/**
 * Which job a terminal event belongs to, and what the navigator may do with it.
 *
 * The bug this replaces: navigation keyed off a single `jobRequested` boolean.
 * When a new job B started while job A's FAILED or CANCELLED state was still
 * the latest one observed, the navigator could read A's stale terminal state
 * together with B's freshly-set flag, route A's failure, and clear the flag --
 * so when B later completed, nothing was waiting for it and the app went Home
 * instead of opening B's transcript. It was timing-dependent because the
 * ViewModel's copy of the service state is updated asynchronously.
 *
 * The invariant enforced here instead:
 *
 *     event(jobId = X) can only affect navigation for job X.
 *
 * Every job gets its id from the UI BEFORE it is submitted, so there is no
 * window in which the UI is following a job whose identity it does not know,
 * and no timing assumption anywhere.
 */

/** The job a terminal state belongs to; running states are not routed by id. */
fun TranscriptionState.terminalJobId(): String? = when (this) {
    is TranscriptionState.Done -> jobId
    is TranscriptionState.Failed -> jobId
    is TranscriptionState.Cancelled -> jobId
    else -> null
}

/**
 * True when [state] is a terminal event for the job the UI is following.
 *
 * [aliases] maps a requested id to the job the service actually ran. That
 * happens exactly once: the same source shared again while its first job is
 * still active is attached to the existing job rather than duplicated.
 */
fun ownsEvent(
    state: TranscriptionState,
    followedJobId: String?,
    aliases: Map<String, String>
): Boolean {
    val eventId = state.terminalJobId() ?: return false
    val followed = followedJobId ?: return false
    return eventId == followed || aliases[followed] == eventId
}

/** What the navigator should do about a non-running state. */
sealed interface TerminalRoute {
    object None : TerminalRoute
    data class OpenTranscript(val recordId: String) : TerminalRoute
    object ShowFailed : TerminalRoute
    object ShowCancelled : TerminalRoute
    /** Nothing is running and nothing is being followed: leave Processing. */
    object LeaveProcessing : TerminalRoute
}

/**
 * The single navigation decision for terminal states.
 *
 * - An event for the followed job routes to that job's outcome. A Done waits
 *   for [completedRecordId], which the ViewModel sets only for that same job.
 * - An event for any other job does nothing while a job is being followed:
 *   the followed job's own events are still to come.
 * - With nothing followed, a non-running state never strands the user on
 *   Processing.
 */
fun terminalRoute(
    state: TranscriptionState,
    followedJobId: String?,
    aliases: Map<String, String>,
    completedRecordId: String?,
    onProcessing: Boolean
): TerminalRoute {
    if (state.isRunning) return TerminalRoute.None
    if (ownsEvent(state, followedJobId, aliases)) {
        return when (state) {
            is TranscriptionState.Done ->
                completedRecordId?.let { TerminalRoute.OpenTranscript(it) } ?: TerminalRoute.None
            is TranscriptionState.Failed -> TerminalRoute.ShowFailed
            is TranscriptionState.Cancelled -> TerminalRoute.ShowCancelled
            else -> TerminalRoute.None
        }
    }
    if (followedJobId != null) return TerminalRoute.None
    return if (onProcessing) TerminalRoute.LeaveProcessing else TerminalRoute.None
}
