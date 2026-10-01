package com.whispercppdemo

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whispercppdemo.recorder.Recordings
import com.whispercppdemo.ui.home.HOME_RECORD_TAG
import com.whispercppdemo.ui.recording.MODE_SHEET_TAG
import com.whispercppdemo.ui.recording.RecordMode
import com.whispercppdemo.ui.recording.modeRowTag
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real app, end to end on the device: Home -> Record audio -> "What do you
 * want to record?" -> the live Recording screen for that mode -> Discard.
 *
 * Records a few seconds from the real microphone and DISCARDS it, so no job is
 * created, nothing is transcribed and no provider is contacted. Requires
 * RECORD_AUDIO to be granted already (revoking it would kill the test process);
 * the not-granted and permanently-denied paths are covered by
 * OutcomeAndPermissionScreensTest and the manual device pass.
 */
@RunWith(AndroidJUnit4::class)
class RecordFlowDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun requireMicrophone() {
        assumeTrue("RECORD_AUDIO not granted",
                   context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }

    @After
    fun neverLeaveARecordingRunning() = runBlocking {
        runCatching { Recordings.controller(context).cancel() }
        Unit
    }

    private fun waitFor(matcher: androidx.compose.ui.test.SemanticsMatcher, timeoutMs: Long = 30_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun recordWith(mode: RecordMode, label: String) {
        // Record audio is enabled once the model is ready and no job is running.
        waitFor(hasTestTag(HOME_RECORD_TAG) and isEnabled(), 60_000)
        compose.onNodeWithTag(HOME_RECORD_TAG).performClick()

        waitFor(hasTestTag(MODE_SHEET_TAG))
        compose.onNodeWithText("What do you want to record?").assertIsDisplayed()
        compose.onNodeWithTag(modeRowTag(mode)).performClick()

        // Capture really started (checked on the recorder, not the UI: a live
        // meter and timer keep Compose permanently busy, which is correct).
        val controller = Recordings.controller(context)
        val deadline = System.currentTimeMillis() + 15_000
        while (controller.session.value == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("capture did not start for $mode", controller.session.value != null)

        // Freeze the live screen so it can be inspected: pause the SAME session
        // through the recorder, and drive the test clock by hand.
        runBlocking { controller.pause() }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithContentDescription("Stop recording").assertIsDisplayed()
        compose.onNodeWithText(label).assertIsDisplayed()
        compose.onNodeWithText("Up to 60:00").assertIsDisplayed()
        compose.onNodeWithText("Paused").assertIsDisplayed()

        // Under 5 s captured: discarded without a dialog, back to Home.
        compose.onNodeWithContentDescription("Discard recording").performClick()
        compose.mainClock.advanceTimeBy(1_500)
        compose.mainClock.autoAdvance = true
        waitFor(hasTestTag(HOME_RECORD_TAG), 15_000)
        compose.onNodeWithText("Turn audio into text").assertIsDisplayed()
        // Discard ends the session asynchronously (the recorder finalises first).
        val gone = System.currentTimeMillis() + 10_000
        while (controller.session.value != null && System.currentTimeMillis() < gone) Thread.sleep(50)
        assertTrue("the session is gone after discard", controller.session.value == null)
    }

    @Test
    fun voice_homeToSheetToRecordingAndBack() = recordWith(RecordMode.VOICE, "Voice")

    @Test
    fun conversation_homeToSheetToRecordingAndBack() = recordWith(RecordMode.CONVERSATION, "Conversation · Microphone")
}
