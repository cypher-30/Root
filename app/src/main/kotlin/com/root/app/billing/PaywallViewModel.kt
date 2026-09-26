package com.root.app.billing

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.Offerings
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.ProductType
import com.revenuecat.purchases.PurchasesError
import com.revenuecat.purchases.getOfferingsWith
import com.revenuecat.purchases.models.StoreTransaction
import com.revenuecat.purchases.purchaseWith
import com.revenuecat.purchases.restorePurchasesWith
import com.root.app.data.AppDatabase
import com.root.app.data.ContentAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Seam around the [Purchases.sharedInstance] static singleton so [PaywallViewModel]'s
 *  state machine can be exercised deterministically in a JVM unit test with a fake,
 *  instead of only against the real store. */
interface PurchasesGateway {
    fun getOfferings(onError: (PurchasesError) -> Unit, onSuccess: (Offerings) -> Unit)
    fun purchase(
        activity: Activity,
        selected: Package,
        onError: (PurchasesError, Boolean) -> Unit,
        onSuccess: (StoreTransaction?, CustomerInfo) -> Unit,
    )
    fun restore(onError: (PurchasesError) -> Unit, onSuccess: (CustomerInfo) -> Unit)
}

object RevenueCatGateway : PurchasesGateway {
    override fun getOfferings(onError: (PurchasesError) -> Unit, onSuccess: (Offerings) -> Unit) {
        Purchases.sharedInstance.getOfferingsWith(onError = onError, onSuccess = onSuccess)
    }

    override fun purchase(
        activity: Activity,
        selected: Package,
        onError: (PurchasesError, Boolean) -> Unit,
        onSuccess: (StoreTransaction?, CustomerInfo) -> Unit,
    ) {
        Purchases.sharedInstance.purchaseWith(
            purchaseParams = PurchaseParams.Builder(activity, selected).build(),
            onError = onError,
            onSuccess = onSuccess,
        )
    }

    override fun restore(onError: (PurchasesError) -> Unit, onSuccess: (CustomerInfo) -> Unit) {
        Purchases.sharedInstance.restorePurchasesWith(onError = onError, onSuccess = onSuccess)
    }
}

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
    /** No reviewed premium content is installed, so nothing may be sold yet. */
    data object NothingToUnlock : PaywallState
}

class PaywallViewModel @JvmOverloads constructor(
    application: Application,
    private val gateway: PurchasesGateway = RevenueCatGateway,
    // Split out from BillingConfiguration.isReady so tests can drive the full state
    // machine without a real, configured RevenueCat SDK instance.
    private val isConfigured: () -> Boolean = { BillingConfiguration.isReady },
    private val premiumContentAvailable: suspend () -> Boolean = {
        ContentAccess.hasPremiumContent(AppDatabase.get(application))
    },
) : AndroidViewModel(application) {
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
        if (!isConfigured()) {
            mutableState.value = PaywallState.NotConfigured
            return
        }
        mutableState.value = PaywallState.Loading
        access.refresh()
        viewModelScope.launch {
            val sellable = try {
                premiumContentAvailable()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            when {
                access.isPremium() -> mutableState.value = PaywallState.Unlocked
                !sellable -> mutableState.value = PaywallState.NothingToUnlock
                else -> loadOfferings()
            }
        }
    }

    private fun loadOfferings() {
        gateway.getOfferings(
            onError = {
                if (!access.isPremium()) {
                    mutableState.value = PaywallState.Error(
                        "Plans could not be loaded. Check your connection and try again.",
                    )
                }
            },
            onSuccess = { offerings ->
                // This launch sells a one-time unlock only; never surface a subscription.
                packages = offerings.current?.availablePackages.orEmpty()
                    .filter { it.product.type != ProductType.SUBS }
                mutableState.value = when {
                    access.isPremium() -> PaywallState.Unlocked
                    packages.isEmpty() -> PaywallState.Error(
                        "The one-time unlock isn't available from the store yet. Free practice is still available.",
                    )
                    else -> PaywallState.Ready(packages)
                }
            },
        )
    }

    fun purchase(activity: Activity?, selected: Package) {
        if (busy() || mutableState.value is PaywallState.Loading) return
        if (!isConfigured()) {
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
        gateway.purchase(
            activity = activity,
            selected = selected,
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
        if (!isConfigured()) {
            mutableState.value = PaywallState.NotConfigured
            return
        }
        mutableState.value = PaywallState.Restoring(packages)
        gateway.restore(
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

