package com.root.app.billing

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.getOfferingsWith
import com.revenuecat.purchases.purchaseWith
import com.revenuecat.purchases.restorePurchasesWith
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI-facing states for [PaywallScreen][com.root.app.ui.PaywallScreen]; a state
 *  machine rather than separate loading/error/data flags so the screen can render
 *  exhaustively with a single `when`. */
sealed interface PaywallState {
    data object Loading : PaywallState
    data class Ready(val packages: List<Package>, val notice: String? = null) : PaywallState
    data class Purchasing(val packages: List<Package>, val packageId: String) : PaywallState
    data class Restoring(val packages: List<Package>) : PaywallState
    data class Error(val message: String, val packages: List<Package> = emptyList()) : PaywallState
    data object Unlocked : PaywallState
    data object NotConfigured : PaywallState
}

class PaywallViewModel(application: Application) : AndroidViewModel(application) {
    private val access = EntitlementStore(application)
    private val mutableState = MutableStateFlow<PaywallState>(PaywallState.Loading)
    val state = mutableState.asStateFlow()
    // Cached so purchase()/restore() can validate a selected package is still one
    // that was actually offered, instead of trusting whatever the UI passes back.
    private var packages = emptyList<Package>()

    init {
        viewModelScope.launch {
            access.premium.collect { active ->
                if (active) mutableState.value = PaywallState.Unlocked
                else if (mutableState.value is PaywallState.Unlocked) load()
            }
        }
        load()
    }

    fun load() {
        if (busy()) return
        if (access.isPremium()) {
            mutableState.value = PaywallState.Unlocked
            return
        }
        if (!BillingConfiguration.isReady) {
            mutableState.value = PaywallState.NotConfigured
            return
        }
        mutableState.value = PaywallState.Loading
        access.refresh()
        Purchases.sharedInstance.getOfferingsWith(
            onError = {
                if (!access.isPremium()) {
                    mutableState.value = PaywallState.Error(
                        "Plans could not be loaded. Check your connection and try again.",
                    )
                }
            },
            onSuccess = { offerings ->
                packages = offerings.current?.availablePackages.orEmpty()
                mutableState.value = when {
                    access.isPremium() -> PaywallState.Unlocked
                    packages.isEmpty() -> PaywallState.Error(
                        "No plans are available from the store yet. Free practice is still available.",
                    )
                    else -> PaywallState.Ready(packages)
                }
            },
        )
    }

    fun purchase(activity: Activity?, selected: Package) {
        if (busy() || mutableState.value is PaywallState.Loading) return
        if (!BillingConfiguration.isReady) {
            mutableState.value = PaywallState.NotConfigured
            return
        }
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            mutableState.value = PaywallState.Error(
                "The store cannot open from this window. Reopen this screen and try again.",
                packages,
            )
            return
        }
        if (packages.none { it.identifier == selected.identifier }) {
            mutableState.value = PaywallState.Error("This plan is no longer available. Reload plans.")
            return
        }
        mutableState.value = PaywallState.Purchasing(packages, selected.identifier)
        Purchases.sharedInstance.purchaseWith(
            purchaseParams = PurchaseParams.Builder(activity, selected).build(),
            onError = { _, cancelled ->
                mutableState.value = if (access.isPremium()) PaywallState.Unlocked
                else if (cancelled) PaywallState.Ready(packages, "Purchase cancelled. Nothing changed.")
                else PaywallState.Error(
                    "The purchase could not be completed. If you were charged, restore purchases before trying again.",
                    packages,
                )
            },
            onSuccess = { _, info ->
                access.recordCustomerInfo(info)
                mutableState.value = if (access.isPremium()) PaywallState.Unlocked
                else PaywallState.Error(
                    "The store completed the purchase, but premium access is not active. " +
                        "Try restoring purchases. The store configuration may need attention.",
                    packages,
                )
            },
        )
    }

    fun restore() {
        if (busy() || mutableState.value is PaywallState.Loading) return
        if (!BillingConfiguration.isReady) {
            mutableState.value = PaywallState.NotConfigured
            return
        }
        mutableState.value = PaywallState.Restoring(packages)
        Purchases.sharedInstance.restorePurchasesWith(
            onError = {
                mutableState.value = if (access.isPremium()) PaywallState.Unlocked
                else PaywallState.Error(
                    "Purchases could not be restored. Check your connection and store account, then try again.",
                    packages,
                )
            },
            onSuccess = { info ->
                access.recordCustomerInfo(info)
                mutableState.value = if (access.isPremium()) PaywallState.Unlocked
                else PaywallState.Ready(packages, "No active premium purchase was found for this store account.")
            },
        )
    }

    private fun busy() = mutableState.value is PaywallState.Purchasing ||
        mutableState.value is PaywallState.Restoring
}
