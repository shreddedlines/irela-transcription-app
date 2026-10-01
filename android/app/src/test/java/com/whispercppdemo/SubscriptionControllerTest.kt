package com.whispercppdemo

import com.whispercppdemo.account.AccountClient
import com.whispercppdemo.account.AccountResult
import com.whispercppdemo.account.ClaimResult
import com.whispercppdemo.account.SubscriptionInfo
import com.whispercppdemo.account.SubscriptionState
import com.whispercppdemo.account.VerifyResult
import com.whispercppdemo.billing.BillingGateway
import com.whispercppdemo.billing.BillingProblem
import com.whispercppdemo.billing.LaunchResult
import com.whispercppdemo.billing.OffersResult
import com.whispercppdemo.billing.OffersState
import com.whispercppdemo.billing.OwnedResult
import com.whispercppdemo.billing.ProCatalog
import com.whispercppdemo.billing.ProOffer
import com.whispercppdemo.billing.PurchaseEvent
import com.whispercppdemo.billing.PurchaseStatus
import com.whispercppdemo.billing.StorePurchase
import com.whispercppdemo.billing.StorePurchaseState
import com.whispercppdemo.billing.SubscriptionController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Buying, restoring and confirming Irela Pro, through [SubscriptionController]
 * with a fake Play and a fake backend. The recurring assertion: Pro is shown
 * only when the backend says so -- nothing Play reports grants it.
 */
class SubscriptionControllerTest {

    private val monthly = ProOffer(ProCatalog.MONTHLY, "₹199.00", "P1M", "offer-m")
    private val annual = ProOffer(ProCatalog.ANNUAL, "₹1,999.00", "P1Y", "offer-a")
    private val activeSub = SubscriptionInfo(SubscriptionState.ACTIVE, ProCatalog.PRODUCT_ID, "monthly",
                                             "2026-10-27T00:00:00+00:00", true)

    private class FakePlay : BillingGateway {
        val flow = MutableSharedFlow<PurchaseEvent>()
        override val events: Flow<PurchaseEvent> = flow
        var offers: OffersResult = OffersResult.Ok(emptyList())
        var owned: OwnedResult = OwnedResult.Ok(emptyList())
        var launch: LaunchResult = LaunchResult.Launched
        val launches = mutableListOf<Pair<ProOffer, String?>>()
        override suspend fun proOffers() = offers
        override suspend fun launchPurchase(host: Any, offer: ProOffer, obfuscatedAccountId: String?): LaunchResult {
            launches += offer to obfuscatedAccountId
            return launch
        }
        override suspend fun ownedPurchases() = owned
    }

    private class FakeAccount : AccountClient {
        val answers = ArrayDeque<VerifyResult>()
        var fallback: VerifyResult = VerifyResult.Unavailable
        val verified = mutableListOf<Pair<String, String>>()
        override suspend fun me(): AccountResult = error("not used")
        override suspend fun claimOwner(code: String): ClaimResult = error("not used")
        override suspend fun verifyPurchase(productId: String, purchaseToken: String): VerifyResult {
            verified += productId to purchaseToken
            return answers.removeFirstOrNull() ?: fallback
        }
        override suspend fun obfuscatedAccountId() = "acct-hash"
    }

    private val play = FakePlay()
    private val account = FakeAccount()
    private val sleeps = mutableListOf<Long>()
    private val c = SubscriptionController(play, account, sleep = { sleeps += it })

    private fun bought(token: String, product: String = ProCatalog.PRODUCT_ID, acknowledged: Boolean = false) =
        StorePurchase(token, listOf(product), StorePurchaseState.PURCHASED, acknowledged)

    private fun pending(token: String) =
        StorePurchase(token, listOf(ProCatalog.PRODUCT_ID), StorePurchaseState.PENDING, false)

    private val status get() = c.state.value.status

    // ---- offers -----------------------------------------------------------------------

    @Test
    fun `offers come from Play, or say why they cannot`() = runBlocking {
        play.offers = OffersResult.Ok(listOf(monthly, annual))
        c.loadOffers()
        assertEquals(OffersState.Ready(listOf(monthly, annual)), c.state.value.offers)
        play.offers = OffersResult.Unavailable(BillingProblem.NOT_SUPPORTED)
        c.loadOffers()
        assertEquals(OffersState.Unavailable(BillingProblem.NOT_SUPPORTED), c.state.value.offers)
    }

    // ---- buying -------------------------------------------------------------------------

    @Test
    fun `a purchase is Pro only after the backend verifies it`() = runBlocking {
        c.purchase(Any(), monthly)
        assertEquals(PurchaseStatus.Launching, status)
        assertEquals(listOf(monthly to "acct-hash"), play.launches)
        assertTrue("nothing asked of the backend yet", account.verified.isEmpty())
        account.answers += VerifyResult.Pro(activeSub)
        c.handle(PurchaseEvent.Updated(listOf(bought("tok-1"))))
        assertEquals(listOf(ProCatalog.PRODUCT_ID to "tok-1"), account.verified)
        assertEquals(PurchaseStatus.Active(activeSub), status)
        assertEquals(1, c.entitlementChanges.value)
    }

    @Test
    fun `Play saying purchased is never enough -- a backend refusal stays not Pro`() = runBlocking {
        for (answer in listOf(VerifyResult.NotAvailable, VerifyResult.Invalid, VerifyResult.InUse,
                              VerifyResult.Revoked)) {
            account.answers += answer
            c.handle(PurchaseEvent.Updated(listOf(bought("tok-$answer"))))
            assertTrue("$answer", status is PurchaseStatus.Failed)
            assertFalse("$answer: not retryable", (status as PurchaseStatus.Failed).canRetry)
        }
        assertEquals("no entitlement change without the backend's yes", 0, c.entitlementChanges.value)
    }

    @Test
    fun `a verified purchase that is not entitled is reported as such`() = runBlocking {
        val onHold = activeSub.copy(state = SubscriptionState.ON_HOLD)
        account.answers += VerifyResult.NotEntitled(onHold)
        c.handle(PurchaseEvent.Updated(listOf(bought("tok"))))
        assertEquals(PurchaseStatus.NotActive(onHold), status)
        assertEquals("the plan may have changed: reload", 1, c.entitlementChanges.value)
    }

    @Test
    fun `a pending purchase waits, and is verified when Play completes it`() = runBlocking {
        c.purchase(Any(), monthly)
        c.handle(PurchaseEvent.Updated(listOf(pending("tok"))))
        assertEquals(PurchaseStatus.Pending, status)
        assertTrue("pending is never sent for verification", account.verified.isEmpty())
        account.answers += VerifyResult.Pro(activeSub)
        c.handle(PurchaseEvent.Updated(listOf(bought("tok"))))
        assertEquals(PurchaseStatus.Active(activeSub), status)
    }

    @Test
    fun `cancelling and Play failures are reported, and nothing is verified`() = runBlocking {
        c.purchase(Any(), monthly)
        c.handle(PurchaseEvent.UserCancelled)
        assertEquals(PurchaseStatus.Cancelled, status)
        c.handle(PurchaseEvent.Failed(BillingProblem.UNAVAILABLE))
        assertEquals(PurchaseStatus.Failed(SubscriptionController.messageFor(BillingProblem.UNAVAILABLE), true), status)
        play.launch = LaunchResult.Failed(BillingProblem.NOT_SUPPORTED)
        c.dismiss()
        c.purchase(Any(), annual)
        assertEquals(PurchaseStatus.Failed(SubscriptionController.messageFor(BillingProblem.NOT_SUPPORTED), true), status)
        assertTrue(account.verified.isEmpty())
    }

    @Test
    fun `other products are ignored`() = runBlocking {
        c.handle(PurchaseEvent.Updated(listOf(bought("tok", product = "something_else"))))
        assertTrue(account.verified.isEmpty())
        assertEquals(PurchaseStatus.Idle, status)
    }

    // ---- confirmation retries ----------------------------------------------------------

    @Test
    fun `a backend outage is retried with backoff until it answers`() = runBlocking {
        account.answers += listOf(VerifyResult.Unavailable, VerifyResult.Offline, VerifyResult.Pro(activeSub))
        c.handle(PurchaseEvent.Updated(listOf(bought("tok"))))
        assertEquals(PurchaseStatus.Active(activeSub), status)
        assertEquals(listOf(2_000L, 5_000L), sleeps)
        assertEquals(3, account.verified.size)
    }

    @Test
    fun `a purchase that cannot be confirmed yet says it is safe and is retryable`() = runBlocking {
        account.fallback = VerifyResult.Unavailable
        c.handle(PurchaseEvent.Updated(listOf(bought("tok"))))
        val s = status as PurchaseStatus.Failed
        assertTrue(s.canRetry && s.message.contains("safe"))
        assertEquals("bounded: 1 try + 3 retries", 4, account.verified.size)
        assertEquals(0, c.entitlementChanges.value)
    }

    @Test
    fun `an unconfirmed purchase is confirmed silently on the next launch`() = runBlocking {
        play.owned = OwnedResult.Ok(listOf(bought("old-acked", acknowledged = true), bought("unconfirmed"),
                                           pending("still-pending"), bought("x", product = "other")))
        account.answers += VerifyResult.Pro(activeSub)
        c.reconcile()
        assertEquals("only the unacknowledged Pro purchase", listOf(ProCatalog.PRODUCT_ID to "unconfirmed"),
                     account.verified)
        assertEquals(PurchaseStatus.Active(activeSub), status)
    }

    @Test
    fun `a silent reconcile that fails changes nothing on screen`() = runBlocking {
        play.owned = OwnedResult.Ok(listOf(bought("unconfirmed")))
        account.fallback = VerifyResult.Unavailable
        c.reconcile()
        assertEquals(PurchaseStatus.Idle, status)
    }

    // ---- restore ------------------------------------------------------------------------

    @Test
    fun `restore finds this account's purchase and asks the backend`() = runBlocking {
        play.owned = OwnedResult.Ok(listOf(bought("tok", acknowledged = true)))
        account.answers += VerifyResult.Pro(activeSub)
        c.restore()
        assertEquals(PurchaseStatus.Active(activeSub), status)
        assertEquals(listOf(ProCatalog.PRODUCT_ID to "tok"), account.verified)
    }

    @Test
    fun `restore with nothing owned, pending, or Play unavailable says so`() = runBlocking {
        c.restore()
        assertEquals(PurchaseStatus.NothingToRestore, status)
        play.owned = OwnedResult.Ok(listOf(pending("p")))
        c.restore()
        assertEquals(PurchaseStatus.Pending, status)
        play.owned = OwnedResult.Unavailable(BillingProblem.UNAVAILABLE)
        c.restore()
        assertTrue(status is PurchaseStatus.Failed)
        assertTrue(account.verified.isEmpty())
    }

    @Test
    fun `already owned becomes a restore`() = runBlocking {
        play.owned = OwnedResult.Ok(listOf(bought("tok")))
        account.answers += VerifyResult.Pro(activeSub)
        c.handle(PurchaseEvent.AlreadyOwned)
        assertEquals(PurchaseStatus.Active(activeSub), status)
    }

    @Test
    fun `the best answer wins across several purchases`() = runBlocking {
        account.answers += listOf(VerifyResult.Invalid, VerifyResult.Pro(activeSub))
        c.handle(PurchaseEvent.Updated(listOf(bought("a"), bought("b"), bought("b"))))
        assertEquals(PurchaseStatus.Active(activeSub), status)
        assertEquals("duplicates verified once", 2, account.verified.size)
    }
}
