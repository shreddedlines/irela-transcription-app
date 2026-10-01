package com.whispercppdemo.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.whispercppdemo.diag.Diag
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val LOG_TAG = "Billing"

/**
 * Google Play Billing Library 8, wrapped in coroutines. One instance per
 * process (see [ProBilling]). Reports what Play says; never grants anything:
 * a purchase becomes Pro only when the backend verifies it.
 *
 * Logs response codes and counts only -- never a purchase token or order id.
 */
class PlayBillingGateway(context: Context) : BillingGateway {

    private val _events = MutableSharedFlow<PurchaseEvent>(
        extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: Flow<PurchaseEvent> = _events.asSharedFlow()

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener { result, purchases -> _events.tryEmit(eventFor(result, purchases)) }
        // Pending purchases are how UPI and cash payments work in India.
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    /** Last product details, needed to launch a purchase for an offer. */
    @Volatile private var details: ProductDetails? = null
    private val connectLock = Mutex()

    private suspend fun connected(): BillingProblem? = connectLock.withLock {
        if (client.isReady) return null
        val done = CompletableDeferred<BillingResult>()
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) { done.complete(result) }
            override fun onBillingServiceDisconnected() {
                if (!done.isCompleted) done.complete(BillingResult.newBuilder()
                    .setResponseCode(BillingResponseCode.SERVICE_DISCONNECTED).build())
            }
        })
        val result = withTimeoutOrNull(15_000) { done.await() }
        val code = result?.responseCode ?: BillingResponseCode.SERVICE_UNAVAILABLE
        Diag.d(LOG_TAG, "setup code=$code")
        if (code == BillingResponseCode.OK) null else problemFor(code)
    }

    override suspend fun proOffers(): OffersResult {
        connected()?.let { return OffersResult.Unavailable(it) }
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(ProCatalog.PRODUCT_ID)
                .setProductType(BillingClient.ProductType.SUBS).build()
        )).build()
        return suspendCancellableCoroutine { cont ->
            client.queryProductDetailsAsync(params) { result, found ->
                val list = found.productDetailsList
                Diag.d(LOG_TAG, "product details code=${result.responseCode} found=${list.size}")
                val product = list.firstOrNull { it.productId == ProCatalog.PRODUCT_ID }
                cont.resume(when {
                    result.responseCode != BillingResponseCode.OK ->
                        OffersResult.Unavailable(problemFor(result.responseCode))
                    product == null -> OffersResult.Unavailable(BillingProblem.NOT_FOUND)
                    else -> {
                        details = product
                        val offers = offersOf(product)
                        if (offers.isEmpty()) OffersResult.Unavailable(BillingProblem.NOT_FOUND)
                        else OffersResult.Ok(offers)
                    }
                })
            }
        }
    }

    override suspend fun launchPurchase(host: Any, offer: ProOffer,
                                        obfuscatedAccountId: String?): LaunchResult {
        val activity = host as? Activity ?: return LaunchResult.Failed(BillingProblem.ERROR)
        connected()?.let { return LaunchResult.Failed(it) }
        val product = details ?: run {
            proOffers()
            details
        } ?: return LaunchResult.Failed(BillingProblem.NOT_FOUND)
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(product)
                    .setOfferToken(offer.offerToken)
                    .build()))
            .apply { obfuscatedAccountId?.let { setObfuscatedAccountId(it) } }
            .build()
        val result = client.launchBillingFlow(activity, params)
        Diag.d(LOG_TAG, "launch code=${result.responseCode}")
        return when (result.responseCode) {
            BillingResponseCode.OK -> LaunchResult.Launched
            BillingResponseCode.ITEM_ALREADY_OWNED -> {
                _events.tryEmit(PurchaseEvent.AlreadyOwned)
                LaunchResult.Launched
            }
            BillingResponseCode.USER_CANCELED -> {
                _events.tryEmit(PurchaseEvent.UserCancelled)
                LaunchResult.Launched
            }
            else -> LaunchResult.Failed(problemFor(result.responseCode))
        }
    }

    override suspend fun ownedPurchases(): OwnedResult {
        connected()?.let { return OwnedResult.Unavailable(it) }
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS).build()
        return suspendCancellableCoroutine { cont ->
            client.queryPurchasesAsync(params) { result, purchases ->
                Diag.d(LOG_TAG, "owned code=${result.responseCode} count=${purchases.size}")
                cont.resume(if (result.responseCode == BillingResponseCode.OK)
                    OwnedResult.Ok(purchases.map(::storePurchase))
                else OwnedResult.Unavailable(problemFor(result.responseCode)))
            }
        }
    }

    private fun eventFor(result: BillingResult, purchases: List<Purchase>?): PurchaseEvent {
        Diag.d(LOG_TAG, "purchases updated code=${result.responseCode} count=${purchases?.size ?: 0}")
        return when (result.responseCode) {
            BillingResponseCode.OK -> PurchaseEvent.Updated(purchases.orEmpty().map(::storePurchase))
            BillingResponseCode.USER_CANCELED -> PurchaseEvent.UserCancelled
            BillingResponseCode.ITEM_ALREADY_OWNED -> PurchaseEvent.AlreadyOwned
            else -> PurchaseEvent.Failed(problemFor(result.responseCode))
        }
    }

    companion object {
        internal fun problemFor(code: Int): BillingProblem = when (code) {
            BillingResponseCode.BILLING_UNAVAILABLE,
            BillingResponseCode.FEATURE_NOT_SUPPORTED -> BillingProblem.NOT_SUPPORTED
            BillingResponseCode.ITEM_UNAVAILABLE -> BillingProblem.NOT_FOUND
            BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingResponseCode.SERVICE_DISCONNECTED,
            BillingResponseCode.NETWORK_ERROR -> BillingProblem.UNAVAILABLE
            else -> BillingProblem.ERROR
        }

        private fun storePurchase(p: Purchase) = StorePurchase(
            purchaseToken = p.purchaseToken,
            productIds = p.products,
            state = when (p.purchaseState) {
                Purchase.PurchaseState.PURCHASED -> StorePurchaseState.PURCHASED
                Purchase.PurchaseState.PENDING -> StorePurchaseState.PENDING
                else -> StorePurchaseState.UNSPECIFIED
            },
            acknowledged = p.isAcknowledged
        )

        /** One offer per base plan: the base plan itself, never a promotional offer. */
        private fun offersOf(product: ProductDetails): List<ProOffer> =
            product.subscriptionOfferDetails.orEmpty()
                .filter { it.offerId == null && it.basePlanId in setOf(ProCatalog.MONTHLY, ProCatalog.ANNUAL) }
                .mapNotNull { o ->
                    val recurring = o.pricingPhases.pricingPhaseList.lastOrNull() ?: return@mapNotNull null
                    ProOffer(o.basePlanId, recurring.formattedPrice, recurring.billingPeriod, o.offerToken)
                }
                .sortedBy { if (it.basePlanId == ProCatalog.MONTHLY) 0 else 1 }
    }
}
