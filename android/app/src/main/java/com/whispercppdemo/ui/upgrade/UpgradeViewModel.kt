package com.whispercppdemo.ui.upgrade

import android.app.Activity
import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.whispercppdemo.billing.BillingProblem
import com.whispercppdemo.billing.OffersState
import com.whispercppdemo.billing.ProBilling
import com.whispercppdemo.billing.ProOffer
import com.whispercppdemo.billing.ProState
import com.whispercppdemo.billing.SubscriptionController
import com.whispercppdemo.diag.Diag
import kotlinx.coroutines.launch

/** Runs the shared [SubscriptionController] for the Upgrade screen. */
class UpgradeViewModel(application: Application) : AndroidViewModel(application) {

    private val billing: SubscriptionController? =
        runCatching { ProBilling.controller(application) }
            .onFailure { Diag.w("Upgrade", "billing unavailable", it) }.getOrNull()

    var state by mutableStateOf(
        if (billing == null) ProState(offers = OffersState.Unavailable(BillingProblem.NOT_SUPPORTED))
        else ProState())
        private set

    init {
        billing?.let { b ->
            viewModelScope.launch { b.state.collect { state = it } }
            // A finished purchase or restore from an earlier visit is not news now.
            b.dismiss()
            loadOffers()
        }
    }

    fun loadOffers() {
        val b = billing ?: return
        viewModelScope.launch { b.loadOffers() }
    }

    fun subscribe(activity: Activity, offer: ProOffer) {
        val b = billing ?: return
        viewModelScope.launch { b.purchase(activity, offer) }
    }

    fun restore() {
        val b = billing ?: return
        viewModelScope.launch { b.restore() }
    }
}
