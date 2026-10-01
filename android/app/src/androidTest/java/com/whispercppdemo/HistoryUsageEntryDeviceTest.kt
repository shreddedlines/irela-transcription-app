package com.whispercppdemo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.whispercppdemo.history.TranscriptRecord
import com.whispercppdemo.history.TranscriptSource
import com.whispercppdemo.ui.common.SelectionKeys
import com.whispercppdemo.ui.common.historyRowTag
import com.whispercppdemo.ui.history.HistoryScreen
import com.whispercppdemo.ui.theme.WhisperCppDemoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Usage is reached from History's ⋮ menu -- including on an empty History, so
 * a new installation can see its free minutes -- never from selection mode,
 * and not at all when the build passes no Usage action.
 */
@RunWith(AndroidJUnit4::class)
class HistoryUsageEntryDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private var opened = 0
    private var selection by mutableStateOf(listOf<String>())
    private val record = TranscriptRecord("t1", "Meeting notes", System.currentTimeMillis() - 60_000,
                                          "Order 4471 has not been delivered yet.", TranscriptSource.RECORDING, 42_000)

    private fun show(history: List<TranscriptRecord>, withUsage: Boolean) {
        compose.setContent {
            WhisperCppDemoTheme {
                Box(Modifier.size(412.dp, 800.dp)) {
                    HistoryScreen(history = history, attempts = emptyList(), selection = selection,
                        onSelectionChange = { selection = it }, onOpenTranscript = {}, onOpenAttempt = {},
                        onEdit = {}, onRename = { _, _ -> }, onDelete = { _, _ -> }, onRetry = {},
                        onDeleteAll = {}, onStartTranscription = {},
                        onOpenUsage = if (withUsage) ({ opened++ }) else null)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun count(text: String) =
        compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun anEmptyHistoryWithoutUsageIsUnchanged() {
        show(emptyList(), withUsage = false)
        compose.onNodeWithText("No transcripts yet").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithContentDescription("History options").fetchSemanticsNodes().size)
    }

    @Test
    fun anEmptyHistoryOffersUsageFromItsMenu() {
        show(emptyList(), withUsage = true)
        compose.onNodeWithText("No transcripts yet").assertIsDisplayed()
        compose.onNodeWithContentDescription("History options").performClick()
        compose.onNodeWithText("Usage").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun theHistoryMenuListsUsageAlongsideTheListActions() {
        show(listOf(record), withUsage = true)
        compose.onNodeWithContentDescription("History options").performClick()
        compose.onNodeWithText("Usage").assertIsDisplayed()
        compose.onNodeWithText("Delete all transcripts").assertIsDisplayed()
        compose.onNodeWithText("Usage").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun withoutUsageTheHistoryMenuIsUnchanged() {
        show(listOf(record), withUsage = false)
        compose.onNodeWithContentDescription("History options").performClick()
        compose.onNodeWithText("Delete all transcripts").assertIsDisplayed()
        assertEquals(0, count("Usage"))
    }

    @Test
    fun selectionModeNeverOffersUsage() {
        show(listOf(record), withUsage = true)
        compose.onNodeWithTag(historyRowTag(SelectionKeys.transcript("t1")))
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Delete").assertIsDisplayed()
        assertEquals(0, count("Usage"))
    }
}
