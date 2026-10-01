package com.whispercppdemo.billing

import com.whispercppdemo.account.AccountClient
import com.whispercppdemo.account.SubscriptionInfo
import com.whispercppdemo.account.VerifyResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Whether Play can sell Pro right now, and at what price. */
sealed interface OffersState {
    object Loading : OffersState
    data class Ready(val offers: List<ProOffer>) : OffersState
    data class Unavailable(val problem: BillingProblem) : OffersState
}

/** Where the current purchase or restore stands. Every "Pro" here is the backend's word. */
sealed interface PurchaseStatus {
    object Idle : PurchaseStatus
    /** Play's purchase sheet is opening or showing. */
    object Launching : PurchaseStatus
    /** Paid with a method that settles later (UPI, cash). Pro starts when it does. */
    object Pending : PurchaseStatus
    /** Asking the backend (and through it, Google) to confirm. */
    object Verifying : PurchaseStatus
    /** The backend verified the purchase: this installation is Pro. */
    data class Active(val subscription: SubscriptionInfo?) : PurchaseStatus
    /** The backend verified it, but it does not give Pro right now. */
    data class NotActive(val subscription: SubscriptionInfo?) : PurchaseStatus
    object Cancelled : PurchaseStatus
    object NothingToRestore : PurchaseStatus
    data class Failed(val message: String, val canRetry: Boolean) : PurchaseStatus
}

data class ProState(
    val offers: OffersState = OffersState.Loading,
    val status: PurchaseStatus = PurchaseStatus.Idle
) {
    val busy: Boolean get() = status == PurchaseStatus.Launching || status == PurchaseStatus.Verifying
}

/**
 * Buying, restoring and confirming Irela Pro. Free of Android so every path is
 * a JVM test; one instance per process ([ProBilling]) so a purchase is
 * confirmed exactly once however many screens are open.
 *
 * THE RULE: nothing here grants Pro. A purchase Play reports is sent to the
 * backend, which asks Google; only the backend's answer (and GET /v1/me after
 * it) can make this installation Pro. A purchase that cannot be confirmed now
 * is confirmed later -- with backoff, and again on the next launch -- because
 * Play keeps listing it and the backend acknowledges it only once verified.
 *
 * Nothing here touches recordings or transcripts. A plan change only changes
 * how much new transcription the backend allows.
 */
class SubscriptionController(
    private val gateway: BillingGateway,
    private val account: AccountClient,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val retryDelaysMs: List<Long> = listOf(2_000L, 5_000L, 15_000L)
) {
    private val _state = MutableStateFlow(ProState())
    val state: StateFlow<ProState> = _state.asStateFlow()

    /** Bumped whenever the backend's answer may have changed the plan: reload /v1/me. */
    private val _entitlementChanges = MutableStateFlow(0)
    val entitlementChanges: StateFlow<Int> = _entitlementChanges.asStateFlow()

    private val verifying = Mutex()

    /** Listens to Play's purchase updates and confirms anything left unconfirmed. */
    fun start(scope: CoroutineScope) {
        scope.launch { gateway.events.collect { handle(it) } }
        scope.launch { reconcile() }
    }

    suspend fun loadOffers() {
        _state.update { it.copy(offers = OffersState.Loading) }
        val offers = when (val r = gateway.proOffers()) {
            is OffersResult.Ok -> OffersState.Ready(r.offers)
            is OffersResult.Unavailable -> OffersState.Unavailable(r.problem)
        }
        _state.update { it.copy(offers = offers) }
    }

    /** Opens Play's purchase sheet for [offer]. The outcome arrives through [handle]. */
    suspend fun purchase(host: Any, offer: ProOffer) {
        if (_state.value.busy) return
        setStatus(PurchaseStatus.Launching)
        val accountId = runCatching { account.obfuscatedAccountId() }.getOrNull()
        when (val r = gateway.launchPurchase(host, offer, accountId)) {
            LaunchResult.Launched -> Unit
            is LaunchResult.Failed -> setStatus(PurchaseStatus.Failed(messageFor(r.problem), true))
        }
    }

    suspend fun handle(event: PurchaseEvent) {
        when (event) {
            is PurchaseEvent.Updated -> {
                val pro = event.purchases.filter { it.isPro }
                val bought = pro.filter { it.state == StorePurchaseState.PURCHASED }
                when {
                    bought.isNotEmpty() -> verify(bought.map { it.purchaseToken }, announce = true)
                    pro.any { it.state == StorePurchaseState.PENDING } -> setStatus(PurchaseStatus.Pending)
                    else -> if (_state.value.status == PurchaseStatus.Launching) setStatus(PurchaseStatus.Idle)
                }
            }
            PurchaseEvent.UserCancelled ->
                if (_state.value.status == PurchaseStatus.Launching) setStatus(PurchaseStatus.Cancelled)
            PurchaseEvent.AlreadyOwned -> restore()
            is PurchaseEvent.Failed -> setStatus(PurchaseStatus.Failed(messageFor(event.problem), true))
        }
    }

    /** "Restore purchases": finds this Google account's Irela Pro and confirms it here. */
    suspend fun restore() {
        if (_state.value.status == PurchaseStatus.Verifying) return
        setStatus(PurchaseStatus.Verifying)
        when (val owned = gateway.ownedPurchases()) {
            is OwnedResult.Unavailable ->
                setStatus(PurchaseStatus.Failed(messageFor(owned.problem), true))
            is OwnedResult.Ok -> {
                val pro = owned.purchases.filter { it.isPro }
                val bought = pro.filter { it.state == StorePurchaseState.PURCHASED }
                when {
                    bought.isNotEmpty() -> verify(bought.map { it.purchaseToken }, announce = true)
                    pro.any { it.state == StorePurchaseState.PENDING } -> setStatus(PurchaseStatus.Pending)
                    else -> setStatus(PurchaseStatus.NothingToRestore)
                }
            }
        }
    }

    /**
     * Silently confirms purchases the backend has not acknowledged yet -- one
     * made while offline, or whose confirmation failed. Changes what the screen
     * says only if it turns out to be Pro.
     */
    suspend fun reconcile() {
        val owned = gateway.ownedPurchases() as? OwnedResult.Ok ?: return
        val unconfirmed = owned.purchases.filter {
            it.isPro && it.state == StorePurchaseState.PURCHASED && !it.acknowledged
        }
        if (unconfirmed.isNotEmpty()) verify(unconfirmed.map { it.purchaseToken }, announce = false)
    }

    fun dismiss() {
        if (!_state.value.busy) setStatus(PurchaseStatus.Idle)
    }

    // ---- confirmation ----------------------------------------------------------

    private suspend fun verify(tokens: List<String>, announce: Boolean) = verifying.withLock {
        if (announce) setStatus(PurchaseStatus.Verifying)
        var best: PurchaseStatus? = null
        for (token in tokens.distinct()) {
            val outcome = verifyWithRetry(token)
            best = better(best, outcome)
            if (best is PurchaseStatus.Active) break
        }
        val final = best ?: PurchaseStatus.Idle
        if (final is PurchaseStatus.Active || final is PurchaseStatus.NotActive) {
            _entitlementChanges.update { it + 1 }
        }
        if (announce || final is PurchaseStatus.Active) setStatus(final)
    }

    private suspend fun verifyWithRetry(token: String): PurchaseStatus {
        var attempt = 0
        while (true) {
            val r = runCatching { account.verifyPurchase(ProCatalog.PRODUCT_ID, token) }
                .getOrElse { VerifyResult.Unavailable }
            val transient = r == VerifyResult.Unavailable || r == VerifyResult.Offline
            if (!transient || attempt >= retryDelaysMs.size) return statusFor(r)
            sleep(retryDelaysMs[attempt++])
        }
    }

    private fun setStatus(status: PurchaseStatus) = _state.update { it.copy(status = status) }

    companion object {
        /** Active beats anything; a definite answer beats a failure. */
        internal fun better(a: PurchaseStatus?, b: PurchaseStatus): PurchaseStatus = when {
            a == null -> b
            a is PurchaseStatus.Active -> a
            b is PurchaseStatus.Active -> b
            a is PurchaseStatus.NotActive -> a
            b is PurchaseStatus.NotActive -> b
            else -> a
        }

        internal fun statusFor(r: VerifyResult): PurchaseStatus = when (r) {
            is VerifyResult.Pro -> PurchaseStatus.Active(r.subscription)
            is VerifyResult.NotEntitled -> PurchaseStatus.NotActive(r.subscription)
            VerifyResult.Invalid -> PurchaseStatus.Failed(
                "Google Play couldn't confirm this purchase. If you were charged, it will be refunded automatically.", false)
            VerifyResult.InUse -> PurchaseStatus.Failed(
                "This subscription is being used on another device. It can be moved here again later.", false)
            VerifyResult.NotAvailable -> PurchaseStatus.Failed(
                "Irela Pro isn't available yet. You have not been charged for Pro on this device.", false)
            VerifyResult.Revoked -> PurchaseStatus.Failed(
                "Transcription is no longer available on this device.", false)
            VerifyResult.RateLimited -> PurchaseStatus.Failed(
                "Too many attempts. Please try again in a while.", true)
            VerifyResult.Offline -> PurchaseStatus.Failed(
                "No internet connection. Your purchase is safe -- Irela will confirm it when you're back online.", true)
            VerifyResult.Unavailable -> PurchaseStatus.Failed(
                "Couldn't confirm your purchase yet. It is safe -- Irela will check again, " +
                        "including the next time you open it.", true)
        }

        internal fun messageFor(problem: BillingProblem): String = when (problem) {
            BillingProblem.NOT_SUPPORTED ->
                "Google Play purchases aren't available on this device. Install Irela from Google Play to subscribe."
            BillingProblem.NOT_FOUND -> "Irela Pro isn't available to buy yet."
            BillingProblem.UNAVAILABLE -> "Couldn't reach Google Play. Check your connection and try again."
            BillingProblem.ERROR -> "Google Play couldn't complete that. Please try again."
        }
    }
}
