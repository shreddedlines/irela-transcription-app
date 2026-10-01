package com.whispercppdemo.billing

import kotlinx.coroutines.flow.Flow

/**
 * Irela Pro as sold on Google Play. The prices here are what Play Console is
 * configured with; the screen always shows Play's own localized price
 * ([ProOffer.formattedPrice]) and never these constants as a price.
 */
object ProCatalog {
    const val PRODUCT_ID = "irela_pro"
    const val MONTHLY = "monthly"
    const val ANNUAL = "annual"

    /** For the plan descriptions only. Enforced by the backend, not here. */
    const val HOURS_PER_CYCLE = 5
    const val HOURS_PER_DAY = 2
    const val JOBS_PER_DAY = 60

    const val PACKAGE_NAME = "com.whispercppdemo"

    /** Google Play's own subscription management page for Irela Pro. */
    const val MANAGE_URL =
        "https://play.google.com/store/account/subscriptions?sku=$PRODUCT_ID&package=$PACKAGE_NAME"
}

/** One way to buy Pro, as Play describes it right now. */
data class ProOffer(
    /** [ProCatalog.MONTHLY] or [ProCatalog.ANNUAL]. */
    val basePlanId: String,
    /** Play's localized price, e.g. "₹199.00". */
    val formattedPrice: String,
    /** ISO-8601 period, "P1M" or "P1Y". */
    val billingPeriod: String,
    /** Opaque; identifies this offer to launchBillingFlow. */
    val offerToken: String
)

enum class StorePurchaseState { PURCHASED, PENDING, UNSPECIFIED }

/** A subscription purchase as Play reports it. Never a grant by itself. */
data class StorePurchase(
    val purchaseToken: String,
    val productIds: List<String>,
    val state: StorePurchaseState,
    /** True once the backend has acknowledged it with Google. */
    val acknowledged: Boolean
) {
    val isPro: Boolean get() = ProCatalog.PRODUCT_ID in productIds
}

/** Why Play could not help right now. */
enum class BillingProblem {
    /** Play Billing is not available: no Play Store, or not installed from Play. */
    NOT_SUPPORTED,
    /** Play has no Irela Pro product for this app (not set up, or not in this country). */
    NOT_FOUND,
    /** The Play service or the network failed; worth trying again. */
    UNAVAILABLE,
    /** Anything else Play reported. */
    ERROR
}

/** What Play's purchase listener delivered. */
sealed interface PurchaseEvent {
    data class Updated(val purchases: List<StorePurchase>) : PurchaseEvent
    object UserCancelled : PurchaseEvent
    /** Play says this account already owns it: the answer is a restore. */
    object AlreadyOwned : PurchaseEvent
    data class Failed(val problem: BillingProblem) : PurchaseEvent
}

sealed interface OffersResult {
    data class Ok(val offers: List<ProOffer>) : OffersResult
    data class Unavailable(val problem: BillingProblem) : OffersResult
}

sealed interface OwnedResult {
    data class Ok(val purchases: List<StorePurchase>) : OwnedResult
    data class Unavailable(val problem: BillingProblem) : OwnedResult
}

sealed interface LaunchResult {
    /** Play's purchase sheet is showing; the outcome arrives as a [PurchaseEvent]. */
    object Launched : LaunchResult
    data class Failed(val problem: BillingProblem) : LaunchResult
}

/**
 * Everything the app needs from Google Play Billing, and nothing that decides
 * entitlement. A seam for tests: [SubscriptionController] is exercised with a
 * fake of this on the JVM.
 */
interface BillingGateway {
    /** Purchases delivered by Play's listener (buying, pending completions, renewals). */
    val events: Flow<PurchaseEvent>

    suspend fun proOffers(): OffersResult

    /**
     * Shows Play's purchase sheet. [host] is the foreground Activity (kept as
     * Any so the interface has no Android types). [obfuscatedAccountId] ties the
     * purchase to this installation for Google's fraud checks.
     */
    suspend fun launchPurchase(host: Any, offer: ProOffer, obfuscatedAccountId: String?): LaunchResult

    /** Subscriptions this Google account currently owns (restore). */
    suspend fun ownedPurchases(): OwnedResult
}
