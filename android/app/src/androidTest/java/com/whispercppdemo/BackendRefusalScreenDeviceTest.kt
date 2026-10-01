package com.whispercppdemo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.whispercppdemo.jobs.FailureReason
import com.whispercppdemo.jobs.failureMessageFor
import com.whispercppdemo.ui.common.FailureCategory
import com.whispercppdemo.ui.processing.FailedScreen
import com.whispercppdemo.ui.theme.WhisperCppDemoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On a device: each backend refusal reads as what it is, with its own heading
 * and message, and Try again only where it can work -- never the generic
 * "Transcription failed".
 */
@RunWith(AndroidJUnit4::class)
class BackendRefusalScreenDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private var retries = 0

    private fun show(code: String) {
        compose.setContent {
            WhisperCppDemoTheme {
                Box(Modifier.size(412.dp, 760.dp)) {
                    FailedScreen(
                        name = "Recording · Today, 2:14 pm",
                        reason = failureMessageFor(code),
                        category = FailureCategory.forReason(code),
                        onChooseAnother = {}, onBackHome = {},
                        onRetry = if (FailureReason.blocksRetry(code)) null else ({ retries++ })
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun count(text: String) =
        compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().size

    /**
     * The retry ACTIONS: clickable nodes labelled exactly "Try again". Not a
     * text search -- some messages tell the user to "tap Try again" (as the
     * offline one always has), and that instruction is not a second action.
     */
    private fun retryActions() =
        compose.onAllNodes(hasText("Try again") and hasClickAction())

    private fun check(code: String, heading: String, tryAgain: Boolean) {
        show(code)
        compose.onNodeWithText(heading).assertIsDisplayed()
        compose.onNodeWithText(failureMessageFor(code)).assertIsDisplayed()
        assertEquals("$code: retry actions", if (tryAgain) 1 else 0,
                     retryActions().fetchSemanticsNodes().size)
        if (tryAgain) {
            retryActions()[0].performScrollTo().performClick()
            compose.runOnIdle { assertEquals("$code: the one Try again retries", 1, retries) }
        }
        assertEquals("$code: never the generic failure", 0, count("Transcription failed."))
        compose.onNodeWithText("Back to Home").performScrollTo().assertIsDisplayed()
    }

    @Test fun dailyQuota() = check(FailureReason.DAILY_QUOTA, "Daily limit reached", tryAgain = false)
    @Test fun rateLimited() {
        check(FailureReason.RATE_LIMITED, "Please wait a moment", tryAgain = true)
        // The message names the button it refers to; it is text, not an action.
        compose.onNodeWithText(failureMessageFor(FailureReason.RATE_LIMITED)).assertHasNoClickAction()
    }
    @Test fun serviceBudget() = check(FailureReason.SERVICE_BUDGET, "Transcription is unavailable right now", tryAgain = true)
    @Test fun serviceDisabled() = check(FailureReason.SERVICE_DISABLED, "Transcription is unavailable right now", tryAgain = true)
    @Test fun revoked() = check(FailureReason.REVOKED, "Not available on this device", tryAgain = false)
    @Test fun ownerDailyLimit() = check(FailureReason.OWNER_DAILY_LIMIT, "Daily limit reached", tryAgain = false)
    @Test fun ownerMonthlyLimit() = check(FailureReason.OWNER_MONTHLY_LIMIT, "Monthly limit reached", tryAgain = false)
}
