package com.whispercppdemo

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.whispercppdemo.history.AutoNames
import com.whispercppdemo.history.TranscriptRecord
import com.whispercppdemo.history.TranscriptSource
import com.whispercppdemo.jobs.FailureReason
import com.whispercppdemo.jobs.JobAttempt
import com.whispercppdemo.ui.common.SelectionKeys
import com.whispercppdemo.ui.common.historyRowTag
import com.whispercppdemo.ui.history.HistoryScreen
import com.whispercppdemo.ui.history.SELECTION_COUNT_TAG
import com.whispercppdemo.ui.theme.WhisperCppDemoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * History, WhatsApp-style, on a real device: long press selects, taps toggle,
 * the ⋮ menu matches the selection, one confirmation deletes, Back leaves.
 * Rows render from invented records only; no real History is touched.
 */
@RunWith(AndroidJUnit4::class)
class HistorySelectionDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = System.currentTimeMillis()
    private val records = listOf(
        TranscriptRecord("t1", AutoNames.RECORDING, now - 60_000, "Hi, I'll be at the office by ten tomorrow.", TranscriptSource.RECORDING, 42_000),
        TranscriptRecord("t2", "Delivery follow-up", now - 3_600_000, "Order 4471 has not been delivered yet.", TranscriptSource.RECORDING, 38_000),
        TranscriptRecord("t3", "PTT-20260912-WA0014.opus", now - 7_200_000, "School fee deadline is next week.", TranscriptSource.IMPORT, 195_000)
    )
    private val attempts = listOf(
        JobAttempt("j1", "PTT-20260915-WA0031.opus", now - 120_000, cancelled = false,
                   reason = "No internet connection, so this could not be transcribed. Tap Try again.", retryable = true,
                   failureReason = FailureReason.OFFLINE)
    )

    private var selection by mutableStateOf(listOf<String>())
    private val opened = mutableListOf<String>()
    private val openedAttempts = mutableListOf<String>()
    private val edited = mutableListOf<String>()
    private val renamed = mutableListOf<Pair<String, String>>()
    private val deleted = mutableListOf<Pair<List<String>, List<String>>>()
    private val retried = mutableListOf<String>()
    private val messages = mutableListOf<String>()

    private fun history(width: Dp = 412.dp, height: Dp = 800.dp, fontScale: Float = 1f) {
        compose.setContent {
            WhisperCppDemoTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    Box(Modifier.size(width, height)) {
                        HistoryScreen(
                            history = records,
                            attempts = attempts,
                            selection = selection,
                            onSelectionChange = { selection = it },
                            onOpenTranscript = { opened += it.id },
                            onOpenAttempt = { openedAttempts += it.jobId },
                            onEdit = { edited += it.id },
                            onRename = { r, name -> renamed += r.id to name },
                            onDelete = { t, a -> deleted += t to a },
                            onRetry = { retried += it.jobId },
                            onDeleteAll = {},
                            onStartTranscription = {},
                            onMessage = { messages += it }
                        )
                    }
                }
            }
        }
    }

    private fun row(key: String) = compose.onNodeWithTag(historyRowTag(key))
    private fun hold(key: String) {
        // Lazy rows off screen are not composed yet: scroll the list to the row first.
        compose.onNodeWithTag(com.whispercppdemo.ui.history.HISTORY_LIST_TAG)
            .performScrollToNode(androidx.compose.ui.test.hasTestTag(historyRowTag(key)))
        row(key).performTouchInput { longClick() }
    }
    private fun count() = compose.onNodeWithTag(SELECTION_COUNT_TAG)
    private fun menu() = compose.onNodeWithContentDescription("More options").performClick()
    private fun hasNode(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun tapOpens_andAFailedRowOpensItsOutcome() {
        history()
        row(SelectionKeys.transcript("t2")).performClick()
        row(SelectionKeys.attempt("j1")).performClick()
        compose.runOnIdle {
            assertEquals(listOf("t2"), opened)
            assertEquals(listOf("j1"), openedAttempts)
            assertTrue(selection.isEmpty())
        }
    }

    @Test
    fun automaticAndStoredTitles() {
        history()
        assertTrue("untitled recording is titled by kind and time",
                   compose.onAllNodes(hasText("Recording · ", substring = true)).fetchSemanticsNodes().isNotEmpty())
        assertTrue(hasNode("Delivery follow-up"))
        assertTrue(hasNode("PTT-20260912-WA0014.opus"))
        assertTrue("no numbered recordings", !hasNode("Recording 1"))
    }

    @Test
    fun longPressSelects_tapsToggle_andNothingOpens() {
        history()
        hold(SelectionKeys.transcript("t1"))
        count().assertTextEquals("1 selected")
        row(SelectionKeys.transcript("t2")).performClick()
        row(SelectionKeys.transcript("t3")).performScrollTo().performClick()
        count().assertTextEquals("3 selected")
        row(SelectionKeys.transcript("t2")).performScrollTo().performClick()
        count().assertTextEquals("2 selected")
        compose.runOnIdle { assertTrue("selection mode never navigates", opened.isEmpty()) }
    }

    @Test
    fun deselectingTheLastRowLeavesSelectionMode() {
        history()
        hold(SelectionKeys.transcript("t1"))
        row(SelectionKeys.transcript("t1")).performClick()
        compose.runOnIdle { assertTrue(selection.isEmpty()) }
        compose.onNodeWithText("History").assertIsDisplayed()
    }

    @Test
    fun oneTranscriptMenu_editRenameDelete() {
        history()
        hold(SelectionKeys.transcript("t2"))
        menu()
        compose.onNodeWithText("Edit transcript").assertIsDisplayed()
        compose.onNodeWithText("Rename").assertIsDisplayed()
        compose.onNodeWithText("Delete").assertIsDisplayed()
        compose.onNodeWithText("Edit transcript").performClick()
        compose.runOnIdle { assertEquals(listOf("t2"), edited); assertTrue(selection.isEmpty()) }
    }

    @Test
    fun severalSelected_offerDeleteButNeverEditOrRename() {
        history()
        hold(SelectionKeys.transcript("t1"))
        row(SelectionKeys.transcript("t2")).performClick()
        menu()
        compose.onNodeWithText("Delete").assertIsDisplayed()
        compose.onNodeWithText("Select all").assertIsDisplayed()
        assertTrue(!hasNode("Edit transcript"))
        assertTrue(!hasNode("Rename"))
    }

    @Test
    fun aFailedRetryableItemOffersTryAgainAndRemove() {
        history()
        hold(SelectionKeys.attempt("j1"))
        menu()
        compose.onNodeWithText("Try again").assertIsDisplayed()
        compose.onNodeWithText("Remove").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        compose.runOnIdle { assertEquals(listOf("j1"), retried) }
    }

    @Test
    fun bulkDelete_isConfirmedOnce_andCancelKeepsTheSelection() {
        history()
        hold(SelectionKeys.transcript("t1"))
        row(SelectionKeys.transcript("t2")).performClick()
        row(SelectionKeys.transcript("t3")).performScrollTo().performClick()
        menu(); compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete 3 transcripts?").assertIsDisplayed()
        compose.onNodeWithText("This can't be undone.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(3, selection.size); assertTrue(deleted.isEmpty()) }
        menu(); compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete 3 transcripts?").assertIsDisplayed()
        // The menu has closed; the only exact "Delete" left is the dialog's confirm button.
        compose.onNodeWithText("Delete").performClick()
        compose.runOnIdle {
            assertEquals(1, deleted.size)
            assertEquals(setOf("t1", "t2", "t3"), deleted[0].first.toSet())
            assertTrue(deleted[0].second.isEmpty())
            assertTrue(selection.isEmpty())
            assertEquals("3 transcripts deleted", messages.last())
        }
    }

    @Test
    fun mixedDelete_isWordedForBoth() {
        history()
        hold(SelectionKeys.transcript("t1"))
        row(SelectionKeys.attempt("j1")).performClick()
        menu(); compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete 2 items?").assertIsDisplayed()
        compose.onNodeWithText("1 transcript and 1 failed or cancelled item. This can't be undone.").assertIsDisplayed()
    }

    @Test
    fun selectAllSelectsEveryVisibleRow() {
        history()
        hold(SelectionKeys.transcript("t1"))
        menu(); compose.onNodeWithText("Select all").performClick()
        count().assertTextEquals("4 selected")
    }

    @Test
    fun renameAnAutomaticTitle_unchangedKeepsItUntitled_changedStoresTheName() {
        history()
        hold(SelectionKeys.transcript("t1"))
        menu(); compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithText("Rename transcript").assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertTrue("saving the automatic title unchanged stores nothing", renamed.isEmpty()) }

        hold(SelectionKeys.transcript("t1"))
        menu(); compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithTag(com.whispercppdemo.ui.common.RENAME_INPUT_TAG).performTextReplacement("Client call notes")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals(listOf("t1" to "Client call notes"), renamed)
            assertEquals("Transcript renamed", messages.last())
        }
    }

    @Test
    fun backLeavesSelectionMode() {
        history()
        hold(SelectionKeys.transcript("t1"))
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertTrue(selection.isEmpty()) }
    }

    @Test
    fun exitButtonLeavesSelectionMode() {
        history()
        hold(SelectionKeys.transcript("t1"))
        compose.onNodeWithContentDescription("Exit selection").performClick()
        compose.runOnIdle { assertTrue(selection.isEmpty()) }
    }

    @Test
    fun selectedLookIsStatic_noRunningAnimations() {
        history()
        hold(SelectionKeys.transcript("t1"))
        compose.mainClock.autoAdvance = false
        row(SelectionKeys.transcript("t2")).performClick()
        // One frame after the toggle the state is already final: nothing is animating.
        compose.mainClock.advanceTimeByFrame()
        count().assertTextEquals("2 selected")
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        count().assertTextEquals("2 selected")
    }

    @Test
    fun landscapeAnd200PercentText_rowsAndMenuReachable() {
        history(width = 900.dp, height = 320.dp, fontScale = 2f)
        hold(SelectionKeys.transcript("t3"))
        count().assertTextEquals("1 selected")
        menu()
        compose.onNodeWithText("Delete").assertIsDisplayed()
    }
}
