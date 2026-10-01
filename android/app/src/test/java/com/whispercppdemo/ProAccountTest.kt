package com.whispercppdemo

import com.whispercppdemo.account.AccountResult
import com.whispercppdemo.account.AccountStatus
import com.whispercppdemo.account.BackendAccountClient
import com.whispercppdemo.account.SubscriptionInfo
import com.whispercppdemo.account.SubscriptionState
import com.whispercppdemo.account.VerifyResult
import com.whispercppdemo.account.accountIdFor
import com.whispercppdemo.billing.ProCatalog
import com.whispercppdemo.billing.PurchaseStatus
import com.whispercppdemo.transcribe.provider.InstallationCredential
import com.whispercppdemo.ui.usage.UsageFormat
import com.whispercppdemo.ui.usage.restoreMessage
import com.whispercppdemo.ui.upgrade.UpgradeCopy
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Irela Pro on the account side: GET /v1/me for Pro (and for a free
 * installation with a non-entitling purchase), POST /v1/billing/verify over
 * real HTTP to a fake backend, the obfuscated account id, and the words the
 * Usage and Upgrade screens show.
 */
class ProAccountTest {

    private lateinit var backend: FakeBackend
    private lateinit var credentials: InMemoryInstallationCredentialStore

    @Before
    fun setUp() {
        backend = FakeBackend().start()
        credentials = InMemoryInstallationCredentialStore(InstallationCredential("install-0", "token-0"))
    }

    @After
    fun tearDown() = backend.stop()

    private val client get() = BackendAccountClient(credentials)

    private val proMe = """{"plan":"pro","unlimited":false,
        "subscription":{"state":"active","product_id":"irela_pro","base_plan":"monthly",
                        "expires_at":"2026-10-27T10:00:00+00:00","auto_renewing":true},
        "monthly":{"starts_at":"2026-09-27T15:30:00+05:30","resets_at":"2026-10-27T15:30:00+05:30",
                   "allowance_seconds":18000,"used_seconds":4200.5,"in_progress_seconds":0.0,"remaining_seconds":13799.5},
        "daily":{"used_seconds":1800.0,"allowance_seconds":7200,"resets_at":"2026-09-28T00:00:00+05:30",
                 "transcriptions_used":3,"transcriptions_limit":60,"window":"calendar_day"},
        "limits":{"max_duration_seconds":3600,"max_upload_bytes":104857600,"requests_per_minute":6},
        "service":{"available":true}}"""

    @Test
    fun `a Pro installation is parsed with its cycle, day and subscription`() = runBlocking {
        backend.route("GET /v1/me", 200 to proMe)
        val pro = (client.me() as AccountResult.Ok).status as AccountStatus.Pro
        assertEquals(SubscriptionState.ACTIVE, pro.subscription.state)
        assertEquals("monthly", pro.subscription.basePlan)
        assertEquals(18000, pro.allowanceSeconds)
        assertEquals(13799.5, pro.remainingSeconds, 0.0)
        assertEquals(7200, pro.dayAllowanceSeconds)
        assertEquals(60, pro.transcriptionsLimit)
        assertEquals("2026-10-27T15:30:00+05:30", pro.cycleResetsAt)
    }

    @Test
    fun `free and owner responses parse exactly as before`() = runBlocking {
        backend.route("GET /v1/me", 200 to """{"plan":"free","unlimited":false,
            "monthly":{"month":"2026-09","allowance_seconds":1200,"used_seconds":0.0,"in_progress_seconds":0.0,
                       "remaining_seconds":1200.0,"resets_at":"2026-10-01T00:00:00+05:30"},
            "daily":{"transcriptions_used":0,"transcriptions_limit":40,"window":"rolling_24h"},
            "limits":{"max_duration_seconds":3600,"max_upload_bytes":104857600,"requests_per_minute":6},
            "service":{"available":true}}""")
        val free = (client.me() as AccountResult.Ok).status as AccountStatus.Free
        assertNull(free.subscription)
        assertEquals(1200, free.allowanceSeconds)
        backend.route("GET /v1/me", 200 to """{"plan":"owner","unlimited":true,"monthly":null,"daily":null,
            "limits":{},"service":{"available":true}}""")
        assertTrue((client.me() as AccountResult.Ok).status is AccountStatus.Owner)
    }

    @Test
    fun `a free installation with a purchase on hold says why`() = runBlocking {
        backend.route("GET /v1/me", 200 to """{"plan":"free","unlimited":false,
            "monthly":{"month":"2026-09","allowance_seconds":1200,"used_seconds":0.0,"in_progress_seconds":0.0,
                       "remaining_seconds":1200.0,"resets_at":"2026-10-01T00:00:00+05:30"},
            "daily":{"transcriptions_used":0,"transcriptions_limit":40,"window":"rolling_24h"},
            "limits":{"max_duration_seconds":3600,"max_upload_bytes":104857600},
            "service":{"available":true},
            "subscription":{"state":"on_hold","product_id":"irela_pro","base_plan":"monthly",
                            "expires_at":null,"auto_renewing":true}}""")
        val free = (client.me() as AccountResult.Ok).status as AccountStatus.Free
        assertEquals(SubscriptionState.ON_HOLD, free.subscription!!.state)
        assertNull(free.subscription!!.expiresAt)
        assertTrue(UsageFormat.subscriptionNotice(free.subscription!!)!!.contains("on hold"))
        assertTrue(UsageFormat.canManage(free.subscription))
    }

    @Test
    fun `verify sends the purchase with this installation's token and maps every answer`() = runBlocking {
        val sub = """{"state":"active","product_id":"irela_pro","base_plan":"annual",
                      "expires_at":"2027-09-27T00:00:00+00:00","auto_renewing":true}"""
        val cases = listOf(
            (200 to """{"plan":"pro","subscription":$sub}""") to VerifyResult.Pro(
                SubscriptionInfo(SubscriptionState.ACTIVE, "irela_pro", "annual", "2027-09-27T00:00:00+00:00", true)),
            (200 to """{"plan":"free","subscription":${sub.replace("active", "pending")}}""") to VerifyResult.NotEntitled(
                SubscriptionInfo(SubscriptionState.PENDING, "irela_pro", "annual", "2027-09-27T00:00:00+00:00", true)),
            (400 to """{"reason":"purchase_invalid"}""") to VerifyResult.Invalid,
            (403 to """{"reason":"revoked"}""") to VerifyResult.Revoked,
            (404 to """{"reason":"not_found"}""") to VerifyResult.NotAvailable,
            (409 to """{"reason":"subscription_in_use"}""") to VerifyResult.InUse,
            (429 to """{"reason":"billing_rate_limited"}""") to VerifyResult.RateLimited,
            (503 to """{"reason":"billing_unavailable"}""") to VerifyResult.Unavailable,
            (200 to "not json") to VerifyResult.Unavailable)
        for ((reply, expected) in cases) {
            backend.route("POST /v1/billing/verify", reply)
            assertEquals("${reply.first}", expected, client.verifyPurchase(ProCatalog.PRODUCT_ID, "tok-123"))
        }
        val sent = backend.calls("POST /v1/billing/verify").last()
        assertEquals("Bearer token-0", sent.authorization)
        val body = JSONObject(sent.body)
        assertEquals("irela_pro", body.getString("product_id"))
        assertEquals("tok-123", body.getString("purchase_token"))
    }

    @Test
    fun `verify without a backend is not available, never Pro`() = runBlocking {
        backend.stop()
        assertEquals(VerifyResult.NotAvailable, client.verifyPurchase(ProCatalog.PRODUCT_ID, "t"))
        backend = FakeBackend().start()
    }

    @Test
    fun `the obfuscated account id matches the backend's and never is the raw id`() = runBlocking {
        // billing.account_id_for("install-0") computed by the backend's own code.
        val backendValue = "b60c85083e95cb0d2f6e9992612ae48c85ea794ee43d409c6b802aedab338028"
        assertEquals(backendValue, accountIdFor("install-0"))
        assertEquals(backendValue, client.obfuscatedAccountId())
    }

    // ---- the words ----------------------------------------------------------------------

    @Test
    fun `Pro usage reads in hours and minutes, rounded down`() {
        assertEquals("3 h 49 min left", UsageFormat.hoursLeft(13799.5))
        assertEquals("45 min left", UsageFormat.hoursLeft(45 * 60 + 59.0))
        assertEquals("2 h left", UsageFormat.hoursLeft(7200.0))
        assertEquals("0 min left", UsageFormat.hoursLeft(-5.0))
        assertEquals("of 5 hours this billing period", UsageFormat.ofProAllowance(18000))
        assertEquals("30 min of 2 h used today", UsageFormat.proToday(1800.0, 7200))
    }

    @Test
    fun `subscription lines and notices for every state`() {
        val base = SubscriptionInfo(SubscriptionState.ACTIVE, "irela_pro", "monthly", "2026-10-27T10:00:00+00:00", true)
        assertEquals("Monthly plan. Renews on 27 October.", UsageFormat.subscriptionLine(base))
        assertEquals("Monthly plan. Ends on 27 October; it won't renew.",
                     UsageFormat.subscriptionLine(base.copy(autoRenewing = false)))
        assertEquals("Cancelled. Pro stays until 27 October.",
                     UsageFormat.subscriptionLine(base.copy(state = SubscriptionState.CANCELED)))
        assertNull(UsageFormat.subscriptionNotice(base))
        for (s in listOf(SubscriptionState.GRACE_PERIOD, SubscriptionState.ON_HOLD, SubscriptionState.PAUSED,
                         SubscriptionState.PENDING, SubscriptionState.EXPIRED, SubscriptionState.REVOKED)) {
            assertTrue("$s", UsageFormat.subscriptionNotice(base.copy(state = s)) != null)
        }
        assertTrue(UsageFormat.subscriptionNotice(base.copy(state = SubscriptionState.GRACE_PERIOD))!!
                       .contains("Update it in Google Play"))
        assertTrue(UsageFormat.canManage(base) && !UsageFormat.canManage(base.copy(state = SubscriptionState.EXPIRED)))
    }

    @Test
    fun `the upgrade screen promises what the backend enforces and keeps data`() {
        assertEquals("5 hours of transcription every billing period", UpgradeCopy.BENEFITS[0])
        assertEquals("Up to 2 hours and 60 transcriptions a day", UpgradeCopy.BENEFITS[1])
        assertTrue(UpgradeCopy.BENEFITS.any { it.contains("never deleted") })
        val m = com.whispercppdemo.billing.ProOffer("monthly", "₹199.00", "P1M", "t")
        val y = com.whispercppdemo.billing.ProOffer("annual", "₹1,999.00", "P1Y", "t")
        assertEquals("₹199.00 / month", UpgradeCopy.perPeriod(m))
        assertEquals("₹1,999.00 / year", UpgradeCopy.perPeriod(y))
        assertEquals("Irela Pro is active on this device.",
                     UpgradeCopy.statusMessage(PurchaseStatus.Active(null)))
        assertTrue(UpgradeCopy.statusMessage(PurchaseStatus.Pending)!!.contains("pending"))
        assertTrue(UpgradeCopy.statusMessage(PurchaseStatus.Cancelled)!!.contains("not been charged"))
        assertNull(UpgradeCopy.statusMessage(PurchaseStatus.Idle))
        assertEquals("No Irela Pro purchase was found for this Google account.",
                     restoreMessage(PurchaseStatus.NothingToRestore))
    }
}
