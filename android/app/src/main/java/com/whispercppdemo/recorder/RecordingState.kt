package com.whispercppdemo.recorder

/**
 * The recorder's lifecycle, owned here and mirrored (never re-derived) by the
 * ViewModel and the UI.
 *
 * [PAUSED] is a real capture state, not a UI flag: the hardware is stopped and
 * the capture thread is parked, so no audio at all is being taken in. See
 * [Recorder] for how the transition is applied.
 */
enum class RecorderState {
    /** No session. Nothing captured, nothing to finalise. */
    IDLE,

    /** Capturing, appending to the session's buffer. */
    RECORDING,

    /** Session alive, hardware stopped. No audio is being captured. */
    PAUSED;

    /** True while a session exists, whether or not it is currently capturing. */
    val isActive: Boolean get() = this != IDLE
}

/**
 * The transition rules, kept pure so every one of them is exercised by JVM
 * tests rather than only on a device.
 *
 * All four are total and idempotent: pausing twice, resuming when not paused,
 * or stopping something already stopped are defined no-ops. That is what makes
 * rapid taps safe -- the guard is the state itself, not a debounce timer.
 */
object RecorderTransitions {

    /** Starting is only meaningful from [RecorderState.IDLE]. */
    fun onStart(current: RecorderState): RecorderState =
        if (current == RecorderState.IDLE) RecorderState.RECORDING else current

    /** Pausing an active capture. A second pause changes nothing. */
    fun onPause(current: RecorderState): RecorderState =
        if (current == RecorderState.RECORDING) RecorderState.PAUSED else current

    /** Resuming the SAME session. Resuming while not paused changes nothing. */
    fun onResume(current: RecorderState): RecorderState =
        if (current == RecorderState.PAUSED) RecorderState.RECORDING else current

    /**
     * Stop and cancel share a transition: both end the session. They differ
     * only in what the caller does with the finished file, and both are
     * reachable from [RecorderState.RECORDING] and [RecorderState.PAUSED].
     */
    fun onFinish(current: RecorderState): RecorderState = RecorderState.IDLE
}

/** Sample rate of the capture path. Matches the transcription pipeline. */
const val RECORDER_SAMPLE_RATE = 16000

/**
 * Recorded duration, derived from the samples actually captured.
 *
 * This is the only duration the product uses, and it is why paused time is
 * excluded for free: the capture thread appends nothing while paused, so a
 * pause simply does not advance the sample count. Wall-clock elapsed time
 * (now - recordingStartedAt) would include pauses and is deliberately not used.
 */
fun recordedMillis(sampleCount: Int, sampleRate: Int = RECORDER_SAMPLE_RATE): Long =
    recordedMillis(sampleCount.toLong(), sampleRate)

fun recordedMillis(sampleCount: Long, sampleRate: Int = RECORDER_SAMPLE_RATE): Long =
    if (sampleCount <= 0 || sampleRate <= 0) 0L
    else sampleCount * 1000L / sampleRate
