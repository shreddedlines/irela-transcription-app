package com.whispercppdemo.ui.common

import com.whispercppdemo.history.TranscriptRecord
import com.whispercppdemo.transcribe.TranscriptionState
import kotlinx.coroutines.delay

/**
 * Whether the UI may start a new transcription job.
 *
 * One job at a time. [jobRunning] covers BOTH sources because the foreground
 * service and the recording path publish into the same TranscriptionStore, and
 * [isRecording] additionally blocks while audio is still being captured.
 * Without this, Home's actions stayed enabled during an import and a second
 * job could be queued behind the engine mutex, leaving the Processing screen
 * flipping between two jobs.
 */
internal fun canStartJob(
    modelReady: Boolean,
    isRecording: Boolean,
    jobRunning: Boolean
): Boolean = modelReady && !isRecording && !jobRunning

/**
 * The record a just-finished job produced, identified by IDENTITY rather than
 * by transcript text.
 *
 * [knownIdsAtStart] is the set of record ids that existed when the job began,
 * so the newly written record is simply the one that was not there before.
 * Matching on text broke for two recordings that transcribe identically (easy
 * for short clips): the older record won and the user was shown the wrong
 * transcript.
 *
 * Returns null until exactly the new record is present, so callers can wait
 * for the history reload rather than routing to a stale entry.
 */
internal fun newlyAddedRecordId(
    knownIdsAtStart: Set<String>,
    history: List<TranscriptRecord>
): String? = history.firstOrNull { it.id !in knownIdsAtStart }?.id

/**
 * Waits for the record a finished job produced to actually appear.
 *
 * TranscriptionService publishes Done BEFORE it persists the record, so
 * reloading history the instant Done arrives races the write and can observe
 * the pre-job list. Resolving once against that stale list left
 * completedRecordId null forever and the user stranded on Processing.
 *
 * Reloads until the new id shows up, then gives up rather than spinning. The
 * service ordering is frozen, so waiting here is the smallest correct fix.
 */
internal suspend fun awaitNewRecordId(
    knownIdsAtStart: Set<String>,
    reload: suspend () -> List<TranscriptRecord>,
    attempts: Int = 20,
    delayMs: Long = 100
): String? {
    repeat(attempts) {
        newlyAddedRecordId(knownIdsAtStart, reload())?.let { return it }
        delay(delayMs)
    }
    return null
}

/**
 * The record a finished job should open.
 *
 * [TranscriptionState.Done.transcriptId] is authoritative and is preferred
 * whenever present: it names the record this job produced OR reused. The
 * [fallback] search exists only for the legacy in-ViewModel paths that publish
 * no id, and it cannot answer for a duplicate -- the reused record already
 * existed when the job began, so "the record that is new" does not exist.
 *
 * [refresh] runs before returning an authoritative id so the record is loaded
 * by the time the navigator opens it.
 */
internal suspend fun completedRecordIdFor(
    done: TranscriptionState.Done,
    refresh: suspend () -> Unit,
    fallback: suspend () -> String?
): String? {
    val id = done.transcriptId
    if (!id.isNullOrBlank()) {
        refresh()
        return id
    }
    return fallback()
}

/**
 * True when the Processing screen is showing a job that is not running and the
 * navigator has nowhere else to send the user -- the dead end that showed
 * "No transcription in progress." with an inert bottom bar.
 *
 * Failed and Cancelled are excluded because they have their own terminal
 * screens to route to. A Done WITH a resolved record routes to that record.
 * Everything else means: leave.
 */
internal fun isStrandedOnProcessing(
    state: TranscriptionState,
    completedRecordId: String?
): Boolean = when {
    state.isRunning -> false                       // live screen, Cancel works
    state is TranscriptionState.Failed -> false    // -> Failed screen
    state is TranscriptionState.Cancelled -> false // -> Cancelled screen
    state is TranscriptionState.Done -> completedRecordId == null
    else -> true                                   // Idle: nothing to show
}
