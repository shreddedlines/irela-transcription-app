package com.whispercppdemo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.whispercppdemo.history.TranscriptRecord
import com.whispercppdemo.history.TranscriptSource
import com.whispercppdemo.ui.detail.TranscriptDetailScreen
import com.whispercppdemo.ui.history.HistoryScreen
import com.whispercppdemo.ui.theme.WhisperCppDemoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A cloud-completed recording stores the provider's measured duration
 * (28.93 s in the first real run), and History and the transcript screen show
 * it. A record without a duration still shows no invented one.
 */
@RunWith(AndroidJUnit4::class)
class CloudRecordingDurationDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = System.currentTimeMillis()
    private val cloudRecording = TranscriptRecord("c1", "Recording", now - 60_000,
        "Order 4471 has not been delivered yet.", TranscriptSource.RECORDING, 28_930L)
    private val unknownLength = TranscriptRecord("c2", "Recording", now - 120_000,
        "School fee deadline is next week.", TranscriptSource.RECORDING, null)

    private fun count(text: String) =
        compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun historyRowShowsTheCloudRecordingsRealDuration() {
        compose.setContent {
            WhisperCppDemoTheme {
                Box(Modifier.size(412.dp, 800.dp)) {
                    HistoryScreen(history = listOf(cloudRecording, unknownLength), attempts = emptyList(),
                        selection = emptyList(), onSelectionChange = {}, onOpenTranscript = {}, onOpenAttempt = {},
                        onEdit = {}, onRename = { _, _ -> }, onDelete = { _, _ -> }, onRetry = {},
                        onDeleteAll = {}, onStartTranscription = {}, onMessage = {})
                }
            }
        }
        compose.waitForIdle()
        assertEquals("exactly the cloud recording shows 0:28", 1, count("0:28"))
        assertEquals("no row shows a placeholder duration", 0, count("-0:0"))
    }

    @Test
    fun transcriptScreenShowsTheDuration() {
        compose.setContent {
            WhisperCppDemoTheme { Box(Modifier.size(412.dp, 800.dp)) { TranscriptDetailScreen(cloudRecording) } }
        }
        compose.waitForIdle()
        assertTrue(count("0:28") >= 1)
    }
}
