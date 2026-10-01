package com.whispercppdemo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.whispercppdemo.capture.BlockReason
import com.whispercppdemo.ui.recording.MODE_BLOCK_TAG
import com.whispercppdemo.ui.recording.RecordMode
import com.whispercppdemo.ui.recording.RecordModeSheet
import com.whispercppdemo.ui.recording.RecordModeSheetContent
import com.whispercppdemo.ui.recording.modeRowTag
import com.whispercppdemo.ui.theme.WhisperCppDemoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "What do you want to record?": the three real modes, Android's restrictions
 * stated honestly, call audio explained but never offered.
 */
@RunWith(AndroidJUnit4::class)
class RecordModeSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val chosen = mutableListOf<RecordMode>()
    private var closed = 0

    private fun content(
        playbackAvailable: Boolean = true,
        block: BlockReason? = null,
        width: Dp = 412.dp, height: Dp = 760.dp,
        fontScale: Float = 1f
    ) {
        compose.setContent {
            WhisperCppDemoTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    Box(Modifier.size(width, height)) {
                        RecordModeSheetContent(playbackAvailable, block, { chosen += it }, { closed++ })
                    }
                }
            }
        }
    }

    @Test
    fun offersVoiceConversationAndPlayback_withVoiceRecommended() {
        content()
        compose.onNodeWithText("What do you want to record?").assertIsDisplayed()
        compose.onNodeWithText("Recommended").assertIsDisplayed()
        compose.onNodeWithTag(modeRowTag(RecordMode.VOICE)).assertIsEnabled().performClick()
        compose.onNodeWithTag(modeRowTag(RecordMode.CONVERSATION)).assertIsEnabled().performClick()
        compose.onNodeWithTag(modeRowTag(RecordMode.PLAYBACK)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(RecordMode.VOICE, RecordMode.CONVERSATION, RecordMode.PLAYBACK), chosen) }
    }

    @Test
    fun playbackIsDisabledWithAReasonBelowAndroid10() {
        content(playbackAvailable = false)
        compose.onNodeWithTag(modeRowTag(RecordMode.PLAYBACK)).assertIsNotEnabled().performClick()
        compose.onNodeWithText("Not available on this phone. Needs Android 10 or newer.").assertIsDisplayed()
        compose.runOnIdle { assertTrue("a disabled row starts nothing", chosen.isEmpty()) }
    }

    @Test
    fun callAudioIsOnlyExplained() {
        content()
        assertEquals(0, compose.onAllNodes(hasText(BlockReason.PROTECTED_CALL_AUDIO.message)).fetchSemanticsNodes().size)
        compose.onNodeWithText("Recording a phone or WhatsApp call?").performClick()
        compose.onNodeWithText(BlockReason.PROTECTED_CALL_AUDIO.message).assertIsDisplayed()
        compose.runOnIdle { assertTrue("no capture mode is started for calls", chosen.isEmpty()) }
    }

    @Test
    fun aRefusedCaptureIsShownInTheSheet() {
        content(block = BlockReason.PLAYBACK_CAPTURE_DENIED)
        compose.onNodeWithTag(MODE_BLOCK_TAG).assertIsDisplayed()
        compose.onNodeWithText(BlockReason.PLAYBACK_CAPTURE_DENIED.message).assertIsDisplayed()
    }

    @Test
    fun closeButtonCloses() {
        content()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.runOnIdle { assertEquals(1, closed) }
    }

    @Test
    fun landscapeAnd200PercentText_everyOptionReachable() {
        content(width = 900.dp, height = 300.dp, fontScale = 2f)
        RecordMode.values().forEach { compose.onNodeWithTag(modeRowTag(it)).performScrollTo().assertIsDisplayed() }
        compose.onNodeWithText("Recording a phone or WhatsApp call?").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theInWindowSheetOpensAndItsScrimCloses() {
        var visible by mutableStateOf(true)
        compose.setContent {
            WhisperCppDemoTheme {
                Box(Modifier.size(412.dp, 760.dp)) {
                    RecordModeSheet(visible, playbackAvailable = true, block = null,
                                    onChoose = { chosen += it }, onDismiss = { closed++; visible = false })
                }
            }
        }
        compose.onNodeWithText("What do you want to record?").assertIsDisplayed()
        // Tap the scrim above the sheet (its centre is covered by the sheet itself).
        compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Close"))[0]
            .performTouchInput { click(androidx.compose.ui.geometry.Offset(centerX, 24f)) }
        compose.runOnIdle { assertEquals(1, closed) }
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodes(hasText("What do you want to record?")).fetchSemanticsNodes().size)
    }
}
