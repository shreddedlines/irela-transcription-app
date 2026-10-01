package com.whispercppdemo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.whispercppdemo.account.AccountStatus
import com.whispercppdemo.account.SubscriptionInfo
import com.whispercppdemo.account.SubscriptionState
import com.whispercppdemo.billing.BillingProblem
import com.whispercppdemo.billing.OffersState
import com.whispercppdemo.billing.ProOffer
import com.whispercppdemo.billing.ProState
import com.whispercppdemo.billing.PurchaseStatus
import com.whispercppdemo.ui.theme.WhisperCppDemoTheme
import com.whispercppdemo.ui.upgrade.UPGRADE_SUBSCRIBE_TAG
import com.whispercppdemo.ui.upgrade.UpgradeScreen
import com.whispercppdemo.ui.upgrade.upgradeOfferTag
import com.whispercppdemo.ui.usage.USAGE_MANAGE_TAG
import com.whispercppdemo.ui.usage.USAGE_PRO_DETAILS_TAG
import com.whispercppdemo.ui.usage.USAGE_RESTORE_TAG
import com.whispercppdemo.ui.usage.USAGE_SUBSCRIPTION_NOTICE_TAG
import com.whispercppdemo.ui.usage.USAGE_UPGRADE_TAG
import com.whispercppdemo.ui.usage.UsageScreen
import com.whispercppdemo.ui.usage.UsageUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Irela Pro on a device: the Pro Usage card, subscription notices, the plan
 * actions (Upgrade / Manage / Restore), and the Upgrade screen with Play's own
 * prices. Owner and free screens without billing stay exactly as before.
 */
@RunWith(AndroidJUnit4::class)
class ProScreensDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val events = mutableListOf<String>()
    private val sub = SubscriptionInfo(SubscriptionState.ACTIVE, "irela_pro", "monthly",
                                       "2026-10-27T10:00:00+00:00", true)
    private val pro = AccountStatus.Pro(sub, "2026-09-27T15:30:00+05:30", "2026-10-27T15:30:00+05:30",
        18000, 4200.0, 0.0, 13800.0, 1800.0, 7200, "2026-09-28T00:00:00+05:30", 3, 60, 3600,
        104_857_600L, true)
    private val free = AccountStatus.Free("2026-09", 1200, 450.0, 0.0, 750.0,
        "2026-10-01T00:00:00+00:00", 3, 40, 3600, 104_857_600L, true)

    private fun usage(status: AccountStatus, billing: Boolean = true,
                      billingStatus: PurchaseStatus = PurchaseStatus.Idle) {
        compose.setContent {
            WhisperCppDemoTheme {
                Box(Modifier.size(412.dp, 900.dp)) {
                    UsageScreen(UsageUiState.Loaded(status), false, onRetry = {}, onClaim = { _, _ -> },
                        onUpgrade = if (billing) ({ events += "upgrade" }) else null,
                        onManageSubscription = if (billing) ({ events += "manage" }) else null,
                        onRestorePurchases = if (billing) ({ events += "restore" }) else null,
                        billingStatus = billingStatus)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun count(text: String) =
        compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun proShowsHoursLeftTodayAndRenewal() {
        usage(pro)
        compose.onNodeWithText("Irela Pro").assertIsDisplayed()
        compose.onNodeWithText("3 h 50 min left").assertIsDisplayed()
        compose.onNodeWithText("of 5 hours this billing period").assertIsDisplayed()
        compose.onNodeWithTag(USAGE_PRO_DETAILS_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("30 min of 2 h used today").assertIsDisplayed()
        compose.onNodeWithText("Monthly plan. Renews on 27 October.").assertIsDisplayed()
        assertEquals("a Pro user is not offered Pro", 0,
                     compose.onAllNodes(hasText("Upgrade to Pro")).fetchSemanticsNodes().size)
        compose.onNodeWithTag(USAGE_MANAGE_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(USAGE_RESTORE_TAG).performScrollTo().performClick()
        assertEquals(listOf("manage", "restore"), events)
    }

    @Test
    fun gracePeriodWarnsAndOffersManage() {
        usage(pro.copy(subscription = sub.copy(state = SubscriptionState.GRACE_PERIOD)))
        compose.onNodeWithTag(USAGE_SUBSCRIPTION_NOTICE_TAG).assertIsDisplayed()
        assertEquals(1, count("Update it in Google Play"))
        compose.onNodeWithTag(USAGE_MANAGE_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun freeWithAPurchaseOnHoldExplainsItAndOffersManageAndUpgrade() {
        usage(free.copy(subscription = sub.copy(state = SubscriptionState.ON_HOLD)))
        compose.onNodeWithTag(USAGE_SUBSCRIPTION_NOTICE_TAG).assertIsDisplayed()
        compose.onNodeWithText("12 min 30 s left").assertIsDisplayed()          // free minutes as before
        compose.onNodeWithTag(USAGE_MANAGE_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(USAGE_UPGRADE_TAG).performScrollTo().performClick()
        assertEquals(listOf("upgrade"), events)
    }

    @Test
    fun freeWithoutBillingIsExactlyTheOldScreen() {
        usage(free, billing = false)
        assertEquals(0, count("Upgrade to Pro") + count("Restore purchases") + count("Manage subscription"))
    }

    @Test
    fun ownerNeverSeesBillingActions() {
        usage(AccountStatus.Owner(true))
        compose.onNodeWithText("Unlimited").assertIsDisplayed()
        assertEquals(0, count("Upgrade to Pro") + count("Restore purchases") + count("Manage subscription"))
    }

    @Test
    fun restoreOutcomeIsShown() {
        usage(free, billingStatus = PurchaseStatus.NothingToRestore)
        assertEquals(1, count("No Irela Pro purchase was found"))
    }

    // ---- Upgrade ------------------------------------------------------------------------

    private val monthly = ProOffer("monthly", "₹199.00", "P1M", "m")
    private val annual = ProOffer("annual", "₹1,999.00", "P1Y", "a")
    private val bought = mutableListOf<ProOffer>()

    private fun upgrade(state: ProState) {
        compose.setContent {
            WhisperCppDemoTheme {
                Box(Modifier.size(412.dp, 900.dp)) {
                    UpgradeScreen(state, onSubscribe = { bought += it }, onRestore = { events += "restore" },
                                  onRetryOffers = { events += "retry" }, onDone = { events += "done" })
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun upgradeShowsPlaysPricesAndBuysTheChosenPlan() {
        upgrade(ProState(OffersState.Ready(listOf(monthly, annual))))
        compose.onNodeWithText("₹199.00 / month").assertIsDisplayed()
        compose.onNodeWithText("₹1,999.00 / year").performScrollTo().assertIsDisplayed()
        assertEquals(1, count("never deleted"))
        compose.onNodeWithTag(upgradeOfferTag("annual")).performScrollTo().performClick()
        compose.onNodeWithTag(UPGRADE_SUBSCRIBE_TAG).performScrollTo().performClick()
        assertEquals(listOf(annual), bought)
    }

    @Test
    fun upgradeWhilePlayIsUnavailableSaysSoAndCannotBuy() {
        upgrade(ProState(OffersState.Unavailable(BillingProblem.NOT_SUPPORTED)))
        compose.onNodeWithText("Pro can't be bought right now").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("Subscribe", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun upgradeWhileConfirmingCannotBuyTwice() {
        upgrade(ProState(OffersState.Ready(listOf(monthly)), PurchaseStatus.Verifying))
        compose.onNodeWithText("Confirming your purchase…").assertIsDisplayed()
        compose.onNodeWithTag(UPGRADE_SUBSCRIBE_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun upgradeActiveShowsDoneOnly() {
        upgrade(ProState(OffersState.Ready(listOf(monthly)), PurchaseStatus.Active(null)))
        compose.onNodeWithText("Irela Pro is active on this device.").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        assertEquals(listOf("done"), events)
        assertEquals(0, compose.onAllNodes(hasText("Subscribe", substring = true)).fetchSemanticsNodes().size)
    }
}
