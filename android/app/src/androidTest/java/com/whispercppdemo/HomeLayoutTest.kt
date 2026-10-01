package com.whispercppdemo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.whispercppdemo.transcribe.Engine
import com.whispercppdemo.ui.home.HomeScreen
import com.whispercppdemo.ui.theme.WhisperCppDemoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Home's actions must be reachable at any viewport height and text size, and
 * Home carries exactly the approved Concept A contents.
 *
 * Real Compose layout at fixed sizes. The landscape size is at least as tight
 * as the content area on the test device.
 */
@RunWith(AndroidJUnit4::class)
class HomeLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private var recorded = 0
    private var imported = 0

    private fun homeAt(width: Dp, height: Dp, fontScale: Float = 1f, canTranscribe: Boolean = true, preparing: Boolean = false) {
        compose.setContent {
            WhisperCppDemoTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    Box(Modifier.size(width, height)) {
                        HomeScreen(
                            canTranscribe = canTranscribe,
                            onRecord = { recorded++ },
                            onImport = { imported++ },
                            preparing = preparing,
                            engine = Engine.LOCAL
                        )
                    }
                }
            }
        }
    }

    // ---- portrait ---------------------------------------------------------------

    @Test
    fun portrait_approvedContentsVisibleWithoutScrolling() {
        homeAt(412.dp, 760.dp)
        compose.onNodeWithText("AUDIO TO TEXT").assertIsDisplayed()
        compose.onNodeWithText("Turn audio into text").assertIsDisplayed()
        compose.onNodeWithText("Capture a voice note, meeting, or conversation.").assertIsDisplayed()
        compose.onNodeWithText("Record audio").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Import audio").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Transcribed on this phone.").assertIsDisplayed()
    }

    @Test
    fun portrait_noConversationButtonAndNoSourceRow() {
        homeAt(412.dp, 760.dp)
        assertEquals(0, compose.onAllNodesWithTextCount("Transcribe conversation"))
        assertEquals(0, compose.onAllNodesWithTextCount("Using microphone"))
        assertEquals(0, compose.onAllNodesWithTextCount("Change"))
        assertEquals(0, compose.onAllNodesWithTextCount("No transcripts yet"))
    }

    @Test
    fun recordAudioOpensTheSheetCallbackOnce() {
        homeAt(412.dp, 760.dp)
        compose.onNodeWithText("Record audio").performClick()
        compose.runOnIdle { assertEquals(1, recorded); assertEquals(0, imported) }
    }

    @Test
    fun whileTheModelLoads_actionsAreDisabledAndSaySo() {
        homeAt(412.dp, 760.dp, canTranscribe = false, preparing = true)
        compose.onNodeWithText("Getting ready…").assertIsDisplayed()
        compose.onNodeWithText("Record audio").assertIsNotEnabled()
    }

    // ---- landscape ----------------------------------------------------------------

    @Test
    fun landscape_recordAudioIsReachableAndWorks() {
        homeAt(900.dp, 300.dp)
        compose.onNodeWithText("Record audio").performScrollTo().assertIsDisplayed().assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, recorded) }
    }

    @Test
    fun landscape_importAudioIsReachableAndWorks() {
        homeAt(900.dp, 300.dp)
        compose.onNodeWithText("Import audio").performScrollTo().assertIsDisplayed().assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, imported) }
    }

    @Test
    fun veryShortViewport_bothActionsStillReachable() {
        homeAt(600.dp, 180.dp)
        compose.onNodeWithText("Record audio").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Import audio").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun landscape_privacyLineStillReachable() {
        homeAt(900.dp, 300.dp)
        compose.onNodeWithText("Transcribed on this phone.").performScrollTo().assertIsDisplayed()
    }

    // ---- 200% text ------------------------------------------------------------------

    @Test
    fun text200_portrait_everythingReachableAndTappable() {
        homeAt(412.dp, 760.dp, fontScale = 2f)
        compose.onNodeWithText("Turn audio into text").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Record audio").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Import audio").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Transcribed on this phone.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, recorded); assertEquals(1, imported) }
    }

    @Test
    fun text200_landscape_actionsReachable() {
        homeAt(900.dp, 300.dp, fontScale = 2f)
        compose.onNodeWithText("Record audio").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Import audio").performScrollTo().assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text, substring = true)).fetchSemanticsNodes().size
}
