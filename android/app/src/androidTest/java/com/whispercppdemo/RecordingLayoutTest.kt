package com.whispercppdemo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.whispercppdemo.recorder.RecorderState
import com.whispercppdemo.ui.common.formatElapsed
import com.whispercppdemo.ui.recording.NEAR_LIMIT_MS
import com.whispercppdemo.ui.recording.NEAR_LIMIT_NOTICE
import com.whispercppdemo.ui.recording.RECORDING_NOTICE_TAG
import com.whispercppdemo.ui.recording.RECORDING_TIMER_TAG
import com.whispercppdemo.ui.recording.RecordMode
import com.whispercppdemo.ui.recording.RecordingNotice
import com.whispercppdemo.ui.recording.RecordingScreen
import com.whispercppdemo.ui.theme.WhisperCppDemoTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Recording controls must be reachable at any viewport height and text size,
 * the mode must be stated, and the 60-minute boundary must be shown honestly.
 *
 * The screen runs infinite animations (the pulsing dot, the live meter), which
 * would stop the test clock from idling, so the clock is driven by hand.
 */
@RunWith(AndroidJUnit4::class)
class RecordingLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private var stops = 0
    private var pauseToggles = 0
    private var discards = 0

    @Before
    fun manualClock() {
        compose.mainClock.autoAdvance = false
    }

    private fun recordingAt(
        width: Dp, height: Dp,
        state: RecorderState = RecorderState.RECORDING,
        elapsedMs: Long = 12_000L,
        mode: RecordMode = RecordMode.VOICE,
        notices: List<RecordingNotice> = emptyList(),
        fontScale: Float = 1f
    ) {
        compose.setContent {
            WhisperCppDemoTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    Box(Modifier.size(width, height)) {
                        RecordingScreen(
                            state = state,
                            amplitude = MutableStateFlow(0.5f),
                            recordedMillis = MutableStateFlow(elapsedMs),
                            onStop = { stops++ },
                            onPauseToggle = { pauseToggles++ },
                            onDiscard = { discards++ },
                            mode = mode,
                            notices = notices,
                            continuesInBackground = true
                        )
                    }
                }
            }
        }
        frames()
    }

    private fun frames(n: Int = 3) = repeat(n) { compose.mainClock.advanceTimeByFrame() }
    private fun control(desc: String): SemanticsNodeInteraction = compose.onNodeWithContentDescription(desc)
    private fun settleScroll() = compose.mainClock.advanceTimeBy(2_000)

    private fun reachAndTap(desc: String) {
        control(desc).performScrollTo()
        settleScroll()
        control(desc).assertIsDisplayed().assertHasClickAction().performClick()
        frames()
    }

    // ---- portrait ---------------------------------------------------------------

    @Test
    fun portrait_allControlsAndTheModeVisibleWithoutScrolling() {
        recordingAt(412.dp, 760.dp, mode = RecordMode.CONVERSATION)
        control("Discard recording").assertIsDisplayed()
        control("Stop recording").assertIsDisplayed().assertIsEnabled()
        control("Pause recording").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Conversation · Microphone").assertIsDisplayed()
        compose.onNodeWithText("Up to 60:00").assertIsDisplayed()
        compose.onNodeWithText("Recording continues if you switch apps.").assertIsDisplayed()
    }

    @Test
    fun portrait_stopAndPauseInvokeTheirCallbacks() {
        recordingAt(412.dp, 760.dp)
        control("Pause recording").performClick(); frames()
        control("Stop recording").performClick(); frames()
        assertEquals(1, pauseToggles)
        assertEquals(1, stops)
    }

    @Test
    fun discard_asksFirstOnceSomethingWasRecorded() {
        recordingAt(412.dp, 760.dp, elapsedMs = 12_000L)
        control("Discard recording").performClick(); frames()
        compose.onNodeWithText("Discard this recording?").assertIsDisplayed()
        assertEquals("nothing is discarded before confirming", 0, discards)
        compose.onNodeWithText("Keep recording").performClick(); frames()
        assertEquals(0, discards)
        control("Discard recording").performClick(); frames()
        compose.onNodeWithText("Discard").performClick(); frames()
        assertEquals(1, discards)
    }

    @Test
    fun discard_aVeryShortRecordingNeedsNoDialog() {
        recordingAt(412.dp, 760.dp, elapsedMs = 2_000L)
        control("Discard recording").performClick(); frames()
        assertEquals(1, discards)
    }

    @Test
    fun paused_statesItAndOffersResume() {
        recordingAt(412.dp, 760.dp, state = RecorderState.PAUSED)
        compose.onNodeWithText("Paused. Nothing is being recorded.").assertIsDisplayed()
        control("Resume recording").assertIsDisplayed().performClick(); frames()
        assertEquals(1, pauseToggles)
    }

    @Test
    fun captureNoticesAreShown() {
        recordingAt(412.dp, 760.dp, notices = listOf(RecordingNotice("A call appears to be active.")))
        compose.onNodeWithTag(RECORDING_NOTICE_TAG).assertIsDisplayed()
    }

    // ---- 60-minute boundary --------------------------------------------------------

    @Test
    fun noLimitWarningBeforeFiftyFiveMinutes() {
        recordingAt(412.dp, 760.dp, elapsedMs = NEAR_LIMIT_MS - 1_000)
        assertEquals(0, compose.onAllNodes(hasText(NEAR_LIMIT_NOTICE)).fetchSemanticsNodes().size)
        compose.onNodeWithTag(RECORDING_TIMER_TAG).assertTextEquals("54:59")
    }

    @Test
    fun limitWarningFromFiftyFiveMinutes() {
        recordingAt(412.dp, 760.dp, elapsedMs = NEAR_LIMIT_MS)
        compose.onNodeWithText(NEAR_LIMIT_NOTICE).performScrollTo(); settleScroll()
        compose.onNodeWithText(NEAR_LIMIT_NOTICE).assertIsDisplayed()
    }

    @Test
    fun timerReadsSixtyMinutesAtTheLimit() {
        recordingAt(412.dp, 760.dp, elapsedMs = 60 * 60_000L)
        compose.onNodeWithTag(RECORDING_TIMER_TAG).assertTextEquals(formatElapsed(60 * 60_000L))
        compose.onNodeWithTag(RECORDING_TIMER_TAG).assertTextEquals("60:00")
    }

    // ---- landscape ------------------------------------------------------------------

    @Test
    fun landscape_stopIsReachableAndWorks() {
        recordingAt(900.dp, 230.dp)
        reachAndTap("Stop recording")
        assertEquals(1, stops)
    }

    @Test
    fun landscape_pauseIsReachableAndWorks() {
        recordingAt(900.dp, 230.dp)
        reachAndTap("Pause recording")
        assertEquals(1, pauseToggles)
    }

    @Test
    fun landscape_resumeIsReachableWhilePaused() {
        recordingAt(900.dp, 230.dp, state = RecorderState.PAUSED)
        reachAndTap("Resume recording")
        reachAndTap("Stop recording")
        assertEquals(1, pauseToggles)
        assertEquals(1, stops)
    }

    @Test
    fun veryShortViewport_allControlsStillReachable() {
        recordingAt(600.dp, 160.dp, elapsedMs = 1_000L)
        reachAndTap("Discard recording")
        reachAndTap("Stop recording")
        reachAndTap("Pause recording")
        assertEquals(1, discards)
        assertEquals(1, stops)
        assertEquals(1, pauseToggles)
    }

    // ---- 200% text -----------------------------------------------------------------

    @Test
    fun text200_portrait_allControlsReachable() {
        recordingAt(412.dp, 760.dp, fontScale = 2f)
        reachAndTap("Stop recording")
        reachAndTap("Pause recording")
        assertEquals(1, stops)
        assertEquals(1, pauseToggles)
    }

}
