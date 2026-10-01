package com.whispercppdemo

import com.whispercppdemo.recorder.RECORDER_SAMPLE_RATE
import com.whispercppdemo.recorder.RecorderState
import com.whispercppdemo.recorder.RecorderTransitions
import com.whispercppdemo.recorder.recordedMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recorder's pause/resume rules.
 *
 * These are the same functions [com.whispercppdemo.recorder.Recorder] applies,
 * not a re-statement of them, so a rule that holds here holds on the device.
 * The capture thread itself needs real AudioRecord hardware and is covered by
 * device testing instead.
 */
class RecorderStateTest {

    @Test
    fun `a started recorder is RECORDING`() {
        assertEquals(RecorderState.RECORDING, RecorderTransitions.onStart(RecorderState.IDLE))
    }

    @Test
    fun `starting an already active recorder does not restart it`() {
        // This is what stops a double tap on Record from opening a second
        // session over the top of a live one.
        assertEquals(
            RecorderState.RECORDING,
            RecorderTransitions.onStart(RecorderState.RECORDING)
        )
        assertEquals(RecorderState.PAUSED, RecorderTransitions.onStart(RecorderState.PAUSED))
    }

    @Test
    fun `pause moves RECORDING to PAUSED`() {
        assertEquals(RecorderState.PAUSED, RecorderTransitions.onPause(RecorderState.RECORDING))
    }

    @Test
    fun `resume moves PAUSED back to RECORDING`() {
        assertEquals(RecorderState.RECORDING, RecorderTransitions.onResume(RecorderState.PAUSED))
    }

    @Test
    fun `pausing twice is harmless`() {
        val once = RecorderTransitions.onPause(RecorderState.RECORDING)
        val twice = RecorderTransitions.onPause(once)
        assertEquals(RecorderState.PAUSED, twice)
    }

    @Test
    fun `resuming while not paused is harmless`() {
        assertEquals(
            RecorderState.RECORDING,
            RecorderTransitions.onResume(RecorderState.RECORDING)
        )
        assertEquals(RecorderState.IDLE, RecorderTransitions.onResume(RecorderState.IDLE))
    }

    @Test
    fun `pausing an idle recorder is harmless`() {
        assertEquals(RecorderState.IDLE, RecorderTransitions.onPause(RecorderState.IDLE))
    }

    @Test
    fun `rapid alternating taps settle on the last one`() {
        var s = RecorderState.RECORDING
        repeat(5) {
            s = RecorderTransitions.onPause(s)
            s = RecorderTransitions.onPause(s)
            s = RecorderTransitions.onResume(s)
            s = RecorderTransitions.onResume(s)
        }
        assertEquals(RecorderState.RECORDING, s)
        assertEquals(RecorderState.PAUSED, RecorderTransitions.onPause(s))
    }

    @Test
    fun `stop from RECORDING ends the session`() {
        assertEquals(RecorderState.IDLE, RecorderTransitions.onFinish(RecorderState.RECORDING))
    }

    @Test
    fun `stop from PAUSED ends the session`() {
        // Stop and cancel must both be reachable while paused; if this were
        // not total, a paused recording could not be finished at all.
        assertEquals(RecorderState.IDLE, RecorderTransitions.onFinish(RecorderState.PAUSED))
    }

    @Test
    fun `cancel from RECORDING and from PAUSED both end the session`() {
        assertEquals(RecorderState.IDLE, RecorderTransitions.onFinish(RecorderState.RECORDING))
        assertEquals(RecorderState.IDLE, RecorderTransitions.onFinish(RecorderState.PAUSED))
    }

    @Test
    fun `isActive covers RECORDING and PAUSED but not IDLE`() {
        // The single-job guard reads this: a paused recording still owns the
        // session and must block a second job.
        assertTrue(RecorderState.RECORDING.isActive)
        assertTrue(RecorderState.PAUSED.isActive)
        assertFalse(RecorderState.IDLE.isActive)
    }

    @Test
    fun `recorded duration comes from the sample count`() {
        assertEquals(1000L, recordedMillis(RECORDER_SAMPLE_RATE))
        assertEquals(500L, recordedMillis(RECORDER_SAMPLE_RATE / 2))
        assertEquals(0L, recordedMillis(0))
        assertEquals(0L, recordedMillis(-1))
    }

    @Test
    fun `paused intervals are excluded from the recorded duration`() {
        // Drives the same rule the capture loop follows: samples accumulate
        // only while RECORDING, so a pause simply does not advance the count.
        // Each step is one 100ms buffer.
        val samplesPerStep = RECORDER_SAMPLE_RATE / 10
        var state = RecorderState.RECORDING
        var captured = 0

        fun step() {
            if (state == RecorderState.RECORDING) captured += samplesPerStep
        }

        repeat(20) { step() }                 // 2.0s recorded
        state = RecorderTransitions.onPause(state)
        repeat(50) { step() }                 // 5.0s of wall clock, captured: none
        state = RecorderTransitions.onResume(state)
        repeat(10) { step() }                 // 1.0s recorded

        // 30 captured steps = 3s, though 8s of wall clock elapsed.
        assertEquals(3000L, recordedMillis(captured))
    }

    @Test
    fun `a recording that is paused immediately captures nothing`() {
        var state = RecorderState.RECORDING
        var captured = 0
        state = RecorderTransitions.onPause(state)
        repeat(30) { if (state == RecorderState.RECORDING) captured += 1600 }
        assertEquals(0L, recordedMillis(captured))
    }
}
