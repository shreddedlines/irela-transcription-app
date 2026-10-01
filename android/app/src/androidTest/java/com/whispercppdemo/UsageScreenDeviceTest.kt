package com.whispercppdemo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.whispercppdemo.account.AccountProblem
import com.whispercppdemo.account.AccountStatus
import com.whispercppdemo.account.ClaimResult
import com.whispercppdemo.ui.theme.WhisperCppDemoTheme
import com.whispercppdemo.ui.usage.OWNER_CODE_INPUT_TAG
import com.whispercppdemo.ui.usage.USAGE_LOADING_TAG
import com.whispercppdemo.ui.usage.USAGE_PLAN_CARD_TAG
import com.whispercppdemo.ui.usage.UsageScreen
import com.whispercppdemo.ui.usage.UsageUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Usage screen on a device: an owner sees only "Unlimited"; a free user
 * sees minutes left, the reset date, today's transcriptions and the per-file
 * limit; a long press (not a tap) on the plan card opens the owner code.
 */
@RunWith(AndroidJUnit4::class)
class UsageScreenDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val claims = mutableListOf<String>()
    private val messages = mutableListOf<String>()
    private var retries = 0
    private var nextClaim = ClaimResult.GRANTED

    private val free = AccountStatus.Free("2026-09", 1200, 450.0, 0.0, 750.0,
        "2026-10-01T00:00:00+00:00", 3, 40, 3600, 104_857_600L, true)

    private fun show(state: UsageUiState, claiming: Boolean = false) {
        compose.setContent {
            WhisperCppDemoTheme {
                Box(Modifier.size(412.dp, 800.dp)) {
                    UsageScreen(state, claiming, onRetry = { retries++ },
                                onClaim = { code, done -> claims += code; done(nextClaim) },
                                onMessage = { messages += it })
                }
            }
        }
        compose.waitForIdle()
    }

    private fun count(text: String) =
        compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun openOwnerCode() {
        compose.onNodeWithTag(USAGE_PLAN_CARD_TAG).performTouchInput { longClick() }
        compose.waitForIdle()
    }

    // ---- what is shown ------------------------------------------------------------

    @Test
    fun anOwnerSeesOnlyUnlimited() {
        show(UsageUiState.Loaded(AccountStatus.Owner(true)))
        compose.onNodeWithText("Unlimited").assertIsDisplayed()
        compose.onNodeWithText("Your plan").assertIsDisplayed()
        listOf("min left", "free minutes", "transcription", "per file", "Resets").forEach {
            assertEquals("owner must not see '$it'", 0, count(it))
        }
    }

    @Test
    fun aFreeUserSeesMinutesResetDailyAndPerFile() {
        show(UsageUiState.Loaded(free))
        compose.onNodeWithText("Free plan").assertIsDisplayed()
        compose.onNodeWithText("12 min 30 s left").assertIsDisplayed()
        compose.onNodeWithText("of 20 free minutes this month").assertIsDisplayed()
        compose.onNodeWithText("Resets 1 October").assertIsDisplayed()
        compose.onNodeWithText("3 of 40 transcriptions in the last 24 hours").assertIsDisplayed()
        compose.onNodeWithText("Up to 60 minutes per file (100 MB)").assertIsDisplayed()
        assertEquals(0, count("Unlimited"))
    }

    @Test
    fun anExhaustedAllowanceIsExplainedNotShownAsAFailure() {
        show(UsageUiState.Loaded(free.copy(usedSeconds = 1200.0, remainingSeconds = 0.0)))
        compose.onNodeWithText("0 min left").assertIsDisplayed()
        compose.onNodeWithText("You have used your 20 free minutes for September. They reset on 1 October.")
            .assertIsDisplayed()
        assertEquals(0, count("Transcription failed"))
    }

    @Test
    fun aPausedServiceIsFlagged() {
        show(UsageUiState.Loaded(free.copy(serviceAvailable = false)))
        compose.onNodeWithText("Transcription is temporarily unavailable right now.").assertIsDisplayed()
    }

    @Test
    fun loadingShowsProgress() {
        show(UsageUiState.Loading)
        compose.onNodeWithTag(USAGE_LOADING_TAG).assertIsDisplayed()
        compose.onNodeWithText("Loading usage").assertIsDisplayed()
    }

    @Test
    fun aLoadErrorOffersTryAgainWhenItCanWork() {
        show(UsageUiState.Error(AccountProblem.OFFLINE))
        compose.onNodeWithText("Couldn't load usage").assertIsDisplayed()
        compose.onNodeWithText("No internet connection.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun aBuildWithoutTheServiceOffersNoTryAgain() {
        show(UsageUiState.Error(AccountProblem.NOT_CONFIGURED))
        compose.onNodeWithText("Usage isn't available in this build.").assertIsDisplayed()
        assertEquals(0, count("Try again"))
    }

    // ---- the hidden owner code ----------------------------------------------------

    @Test
    fun aTapDoesNotOpenTheOwnerCode() {
        show(UsageUiState.Loaded(free))
        compose.onNodeWithTag(USAGE_PLAN_CARD_TAG).performTouchInput { click() }
        compose.waitForIdle()
        assertEquals(0, count("Owner code"))
    }

    @Test
    fun nothingOnScreenAdvertisesTheOwnerCode() {
        show(UsageUiState.Loaded(free))
        listOf("Owner", "owner", "code").forEach { assertEquals("'$it' must not be visible", 0, count(it)) }
    }

    @Test
    fun aLongPressOpensTheOwnerCodeAndASuccessCloses() {
        show(UsageUiState.Loaded(free))
        openOwnerCode()
        compose.onNodeWithText("Owner code").assertIsDisplayed()
        compose.onNodeWithTag(OWNER_CODE_INPUT_TAG).performTextInput("secret-code-123")
        compose.onNodeWithText("Continue").performClick()
        compose.waitForIdle()
        assertEquals(listOf("secret-code-123"), claims)
        assertEquals(0, count("Owner code"))
        assertEquals(listOf(ClaimResult.GRANTED.message), messages)
    }

    @Test
    fun aRefusedCodeKeepsTheDialogOpenWithTheReason() {
        nextClaim = ClaimResult.INVALID
        show(UsageUiState.Loaded(free))
        openOwnerCode()
        compose.onNodeWithTag(OWNER_CODE_INPUT_TAG).performTextInput("wrong")
        compose.onNodeWithText("Continue").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Owner code").assertIsDisplayed()
        compose.onNodeWithText("That code is not valid.").assertIsDisplayed()
        assertTrue(messages.isEmpty())
    }

    @Test
    fun theOwnerCodeIsMasked() {
        show(UsageUiState.Loaded(AccountStatus.Owner(true)))
        openOwnerCode()
        compose.onNodeWithTag(OWNER_CODE_INPUT_TAG)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
    }

    @Test
    fun anEmptyCodeCannotBeSubmitted() {
        show(UsageUiState.Loaded(free))
        openOwnerCode()
        compose.onNodeWithText("Continue").performClick()
        compose.waitForIdle()
        assertTrue(claims.isEmpty())
    }
}
