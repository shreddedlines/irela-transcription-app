package com.whispercppdemo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.whispercppdemo.jobs.FailureReason
import com.whispercppdemo.jobs.JobAttempt
import com.whispercppdemo.jobs.failureMessageFor
import com.whispercppdemo.ui.common.FailureCategory
import com.whispercppdemo.ui.common.SelectionKeys
import com.whispercppdemo.ui.common.historyRowTag
import com.whispercppdemo.ui.history.HistoryScreen
import com.whispercppdemo.ui.processing.FailedScreen
import com.whispercppdemo.ui.theme.WhisperCppDemoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On a real device: a free-tier refusal reads as a limit, not a failure, and
 * offers no Try again. A generic provider failure is untouched.
 */
@RunWith(AndroidJUnit4::class)
class FreeLimitScreenDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val retried = mutableListOf<String>()
    // Compose state: HistoryScreen must recompose into selection mode.
    private var selection by androidx.compose.runtime.mutableStateOf(listOf<String>())

    private fun count(text: String) =
        compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun showOutcome(code: String, retryOffered: Boolean) {
        compose.setContent {
            WhisperCppDemoTheme {
                Box(Modifier.size(412.dp, 760.dp)) {
                    FailedScreen(
                        name = "Recording · Today, 2:14 pm",
                        reason = failureMessageFor(code),
                        category = FailureCategory.forReason(code),
                        onChooseAnother = {}, onBackHome = {},
                        onRetry = if (retryOffered) ({ retried += code }) else null
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun theMonthlyAllowanceRefusalShowsTheLimitAndNoTryAgain() {
        showOutcome(FailureReason.FREE_MONTHLY_ALLOWANCE, retryOffered = false)
        compose.onNodeWithText("Free limit reached").assertIsDisplayed()
        compose.onNodeWithText("You have used your 20 free minutes for this month.").assertIsDisplayed()
        assertEquals("Try again must not be shown", 0, count("Try again"))
        assertEquals("nothing calls it a failure", 0, count("Transcription failed"))
        compose.onNodeWithText("Back to Home").performScrollTo().assertIsDisplayed()
        assertTrue(retried.isEmpty())
    }

    @Test
    fun theNetworkCapRefusalReadsAsALimitToo() {
        showOutcome(FailureReason.FREE_NETWORK_DAILY_CAP, retryOffered = false)
        compose.onNodeWithText("Free limit reached").assertIsDisplayed()
        compose.onNodeWithText("The free transcription limit for this network has been reached today.")
            .assertIsDisplayed()
        assertEquals(0, count("Try again"))
    }

    @Test
    fun theFreeDailyBudgetRefusalReadsAsALimitToo() {
        showOutcome(FailureReason.FREE_DAILY_BUDGET, retryOffered = false)
        compose.onNodeWithText("Free limit reached").assertIsDisplayed()
        compose.onNodeWithText(
            "Free transcription is temporarily unavailable today. Please try again tomorrow.")
            .assertIsDisplayed()
        assertEquals(0, count("Try again"))
    }

    @Test
    fun aGenericProviderFailureStillOffersTryAgain() {
        showOutcome(FailureReason.PROVIDER_TERMINAL, retryOffered = true)
        compose.onNodeWithText("Transcription is unavailable right now").assertIsDisplayed()
        compose.onNodeWithText("Transcription failed.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(FailureReason.PROVIDER_TERMINAL), retried) }
    }

    @Test
    fun historyShowsTheRefusalWithoutOfferingTryAgain() {
        val now = System.currentTimeMillis()
        val attempts = listOf(
            JobAttempt("j1", "Recording", now - 60_000, cancelled = false,
                       reason = failureMessageFor(FailureReason.FREE_MONTHLY_ALLOWANCE),
                       retryable = false,                       // JobHistory blocks it
                       failureReason = FailureReason.FREE_MONTHLY_ALLOWANCE),
            JobAttempt("j2", "clip.ogg", now - 120_000, cancelled = false,
                       reason = failureMessageFor(FailureReason.PROVIDER_TERMINAL),
                       retryable = true, failureReason = FailureReason.PROVIDER_TERMINAL)
        )
        compose.setContent {
            WhisperCppDemoTheme {
                Box(Modifier.size(412.dp, 760.dp)) {
                    HistoryScreen(history = emptyList(), attempts = attempts, selection = selection,
                        onSelectionChange = { selection = it }, onOpenTranscript = {}, onOpenAttempt = {},
                        onEdit = {}, onRename = { _, _ -> }, onDelete = { _, _ -> },
                        onRetry = { retried += it.jobId }, onDeleteAll = {},
                        onStartTranscription = {}, onMessage = {})
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("You have used your 20 free minutes for this month.").assertIsDisplayed()
        // Selecting the refused attempt must not offer Try again in its menu.
        // The row's own long-press action, rather than a synthesised touch:
        // what the user's finger triggers, without depending on window focus.
        compose.onNodeWithTag(historyRowTag(SelectionKeys.attempt("j1")))
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        compose.onNodeWithContentDescription("More options").performClick()
        assertEquals("a refusal cannot be retried", 0, count("Try again"))
        compose.onNodeWithText("Remove").assertIsDisplayed()
        assertTrue(retried.isEmpty())
    }
}
