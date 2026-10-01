package com.whispercppdemo.ui.usage

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.whispercppdemo.account.BackendAccountClient
import com.whispercppdemo.account.ClaimResult
import com.whispercppdemo.billing.ProBilling
import com.whispercppdemo.billing.ProCatalog
import com.whispercppdemo.billing.PurchaseStatus
import com.whispercppdemo.billing.SubscriptionController
import com.whispercppdemo.diag.Diag
import com.whispercppdemo.transcribe.provider.SharedPrefsInstallationCredentialStore
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Backs the Usage screen. All logic is in [UsageController] and
 * [SubscriptionController]; this only runs them in the ViewModel's scope and
 * mirrors their state for Compose. Uses the same installation credential store
 * as transcription.
 */
class UsageViewModel(application: Application) : AndroidViewModel(application) {

    private val controller = UsageController(
        BackendAccountClient(SharedPrefsInstallationCredentialStore(application)))

    /** The process-wide Pro controller; null only if Play Billing cannot start. */
    private val billing: SubscriptionController? =
        runCatching { ProBilling.controller(application) }
            .onFailure { Diag.w("Usage", "billing unavailable", it) }.getOrNull()

    var state by mutableStateOf<UsageUiState>(UsageUiState.Loading)
        private set

    var claiming by mutableStateOf(false)
        private set

    var billingStatus by mutableStateOf<PurchaseStatus>(PurchaseStatus.Idle)
        private set

    val billingAvailable: Boolean get() = billing != null

    init {
        refresh()
        billing?.let { b ->
            viewModelScope.launch { b.state.collect { billingStatus = it.status } }
            // The plan changes only when the backend says so: reload /v1/me then.
            viewModelScope.launch { b.entitlementChanges.drop(1).collect { refresh() } }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            state = UsageUiState.Loading
            state = controller.refresh()
        }
    }

    fun claim(code: String, onResult: (ClaimResult) -> Unit) {
        if (claiming) return
        claiming = true
        viewModelScope.launch {
            val result = try {
                controller.claim(code)
            } finally {
                state = controller.state
                claiming = false
            }
            result?.let(onResult)
        }
    }

    fun restorePurchases() {
        val b = billing ?: return
        viewModelScope.launch { b.restore() }
    }

    /** Google Play's own page for this subscription: cancel, change payment, resubscribe. */
    fun manageSubscription() {
        runCatching {
            getApplication<Application>().startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(ProCatalog.MANAGE_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Diag.w("Usage", "could not open Play subscriptions", it) }
    }
}
