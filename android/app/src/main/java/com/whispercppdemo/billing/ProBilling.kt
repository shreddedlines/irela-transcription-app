package com.whispercppdemo.billing

import android.content.Context
import com.whispercppdemo.account.BackendAccountClient
import com.whispercppdemo.diag.Diag
import com.whispercppdemo.transcribe.provider.SharedPrefsInstallationCredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The one [SubscriptionController] for this process, created on first use.
 *
 * One Play connection and one purchase listener for the whole app: the Usage
 * and Upgrade screens share it, so a purchase is confirmed exactly once even
 * with both screens on the back stack. Its scope lives as long as the process.
 */
object ProBilling {

    @Volatile private var instance: SubscriptionController? = null

    fun controller(context: Context): SubscriptionController =
        instance ?: synchronized(this) {
            instance ?: SubscriptionController(
                PlayBillingGateway(context.applicationContext),
                BackendAccountClient(SharedPrefsInstallationCredentialStore(context.applicationContext))
            ).also {
                it.start(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
                instance = it
            }
        }

    /**
     * At launch, in cloud builds: confirm any Pro purchase the backend has not
     * acknowledged yet (bought offline, or whose confirmation failed). Starting
     * the controller does exactly that. Never allowed to affect startup.
     */
    fun confirmOutstanding(context: Context) {
        runCatching { controller(context) }
            .onFailure { Diag.w("Billing", "billing unavailable at launch", it) }
    }
}
