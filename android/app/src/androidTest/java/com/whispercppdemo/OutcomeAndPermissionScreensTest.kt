package com.whispercppdemo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.whispercppdemo.jobs.FailureReason
import com.whispercppdemo.jobs.failureMessageFor
import com.whispercppdemo.transcribe.Engine
import com.whispercppdemo.ui.common.FailureCategory
import com.whispercppdemo.ui.permission.MicrophonePermissionScreen
import com.whispercppdemo.ui.processing.CancelledScreen
import com.whispercppdemo.ui.processing.FailedScreen
import com.whispercppdemo.ui.theme.WhisperCppDemoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Failed, interrupted and cancelled outcomes (live and stored), and both permission states. */
@RunWith(AndroidJUnit4::class)
class OutcomeAndPermissionScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private var retries = 0
    private var chooses = 0
    private var homes = 0
    private var allows = 0
    private var settings = 0
    private var notNows = 0

    private fun host(width: Dp = 412.dp, height: Dp = 760.dp, fontScale: Float = 1f, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            WhisperCppDemoTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    Box(Modifier.size(width, height)) { content() }
                }
            }
        }
    }

    @Test
    fun storedOfflineFailure_titledAccurately_withTryAgain() {
        host {
            FailedScreen(
                name = "PTT-20260915-WA0031.opus",
                reason = failureMessageFor(FailureReason.OFFLINE),
                category = FailureCategory.forReason(FailureReason.OFFLINE),
                onChooseAnother = { chooses++ }, onBackHome = { homes++ }, onRetry = { retries++ }
            )
        }
        compose.onNodeWithText("No internet connection").assertIsDisplayed()
        compose.onNodeWithText(failureMessageFor(FailureReason.OFFLINE)).assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithText("Choose another file").performClick()
        compose.onNodeWithText("Back to Home").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(1, chooses); assertEquals(1, homes) }
    }

    @Test
    fun interruptedFailure_liveMessageGetsItsTitle() {
        host {
            FailedScreen(name = "Conversation · Today, 10:42 AM", reason = failureMessageFor(FailureReason.INTERRUPTED),
                         onChooseAnother = {}, onBackHome = {}, onRetry = {})
        }
        compose.onNodeWithText("Transcription was interrupted").assertIsDisplayed()
        compose.onNodeWithText("Conversation · Today, 10:42 AM").assertIsDisplayed()
    }

    @Test
    fun unusableAudio_withoutRetry_offersChooseAnotherFirst() {
        host {
            FailedScreen(name = "Lecture full.mp3", reason = failureMessageFor(FailureReason.AUDIO_REJECTED),
                         onChooseAnother = { chooses++ }, onBackHome = { homes++ }, onRetry = null)
        }
        compose.onNodeWithText("Couldn't use this audio").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("Try again")).fetchSemanticsNodes().size)
        compose.onNodeWithText("Choose another file").performClick()
        compose.onNodeWithText("Back to Home").performClick()
        compose.runOnIdle { assertEquals(1, chooses); assertEquals(1, homes) }
    }

    @Test
    fun technicalDetailsShowOnlyRealState() {
        host {
            FailedScreen(name = "clip.ogg", reason = failureMessageFor(FailureReason.EMPTY_TRANSCRIPT),
                         onChooseAnother = {}, onBackHome = {}, onRetry = {})
        }
        compose.onNodeWithText("Show technical details").performScrollTo().performClick()
        compose.onNodeWithText("Reported").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("File").assertIsDisplayed()
    }

    @Test
    fun cancelled_isCalm_andRetriesWhenTheAudioIsKept() {
        host {
            CancelledScreen(name = "Recording · Yesterday, 9:30 PM", onBackHome = { homes++ },
                            onChooseAnother = { chooses++ }, onRetry = { retries++ })
        }
        compose.onNodeWithText("Transcription cancelled").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun outcomeActionsReachableInLandscapeAt200Percent() {
        host(width = 900.dp, height = 300.dp, fontScale = 2f) {
            FailedScreen(name = "clip.ogg", reason = failureMessageFor(FailureReason.OFFLINE),
                         onChooseAnother = { chooses++ }, onBackHome = { homes++ }, onRetry = { retries++ })
        }
        compose.onNodeWithText("Try again").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Back to Home").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun permissionNotYetAsked_explainsAndAsks() {
        host {
            MicrophonePermissionScreen(permanentlyDenied = false, onAllow = { allows++ }, onOpenSettings = { settings++ },
                                       onNotNow = { notNows++ }, engine = Engine.LOCAL)
        }
        compose.onNodeWithText("Allow microphone access").assertIsDisplayed()
        compose.onNodeWithText("Recording uses your microphone. Audio stays on your phone.").assertIsDisplayed()
        compose.onNodeWithText("Allow microphone").performClick()
        compose.onNodeWithText("Not now").performClick()
        compose.runOnIdle { assertEquals(1, allows); assertEquals(0, settings); assertEquals(1, notNows) }
    }

    @Test
    fun permissionPermanentlyDenied_sendsToSettings() {
        host {
            MicrophonePermissionScreen(permanentlyDenied = true, onAllow = { allows++ }, onOpenSettings = { settings++ },
                                       onNotNow = { notNows++ }, engine = Engine.CLOUD)
        }
        compose.onNodeWithText("Microphone access is off").assertIsDisplayed()
        compose.onNodeWithText("Open app settings").performClick()
        compose.runOnIdle { assertEquals(0, allows); assertEquals(1, settings) }
    }

    @Test
    fun permissionActionsReachableInLandscapeAt200Percent() {
        host(width = 900.dp, height = 300.dp, fontScale = 2f) {
            MicrophonePermissionScreen(permanentlyDenied = false, onAllow = { allows++ }, onOpenSettings = {},
                                       onNotNow = {}, engine = Engine.LOCAL)
        }
        compose.onNodeWithText("Allow microphone").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, allows) }
    }
}
