package com.root.app.billing

import android.content.Context
import android.util.Log
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.getCustomerInfoWith
import com.revenuecat.purchases.interfaces.UpdatedCustomerInfoListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Every facade shares one process-wide state; UI and repository access use the same truth. */
class EntitlementStore(context: Context) {
    private val backing = shared(context.applicationContext)

    val premium: StateFlow<Boolean> = backing.premium.asStateFlow()
    val refreshError: StateFlow<String?> = backing.error.asStateFlow()

    fun isPremium(): Boolean = backing.cachedAccess()

    fun refresh(onChanged: (Boolean) -> Unit = {}) {
        if (!BillingConfiguration.isReady) {
            onChanged(backing.cachedAccess())
            return
        }
        backing.connect()
        Purchases.sharedInstance.getCustomerInfoWith(
            onError = { error ->
                backing.error.value = "Access could not be refreshed. Saved access is being used."
                Log.w("RootBilling", "Customer info refresh failed: ${error.code}")
                onChanged(backing.cachedAccess())
            },
            onSuccess = { info ->
                recordCustomerInfo(info)
                onChanged(isPremium())
            },
        )
    }

    fun recordCustomerInfo(info: CustomerInfo) = backing.update(info)

    private class Backing(context: Context) {
        // Do not carry a Test Store entitlement into a differently configured production app.
        private val prefs = context.getSharedPreferences(
            "root_entitlements_${com.root.app.BuildConfig.REVENUECAT_API_KEY.hashCode()}",
            Context.MODE_PRIVATE,
        )
        val premium = MutableStateFlow(false)
        val error = MutableStateFlow<String?>(null)
        private var connected = false

        init {
            cachedAccess()
            connect()
        }

        @Synchronized
        fun cachedAccess(): Boolean {
            val expiration = prefs.getLong("expiration", 0L)
            val active = BillingConfiguration.hasUsableKey &&
                prefs.getBoolean("premium", false) &&
                isWithinExpiration(expiration)
            premium.value = active
            return active
        }

        @Synchronized
        fun connect() {
            if (connected || !BillingConfiguration.isReady) return
            Purchases.sharedInstance.updatedCustomerInfoListener =
                UpdatedCustomerInfoListener { info -> update(info) }
            connected = true
        }

        @Synchronized
        fun update(info: CustomerInfo) {
            // Two async paths (the update listener and a manual refresh()) can race,
            // and their responses can land out of order. A stale response landing after
            // a fresher one must not regress already-applied entitlement state -
            // requestDate is the store's own ordering signal for exactly this.
            val incomingRequestDate = info.requestDate.time
            val lastAppliedRequestDate = prefs.getLong("requestDate", 0L)
            if (incomingRequestDate < lastAppliedRequestDate) return
            val entitlement = info.entitlements[BillingConfiguration.ENTITLEMENT_ID]
            val active = entitlement?.isActive == true
            prefs.edit()
                .putBoolean("premium", active)
                .putLong("expiration", entitlement?.expirationDate?.time ?: 0L)
                .putLong("requestDate", incomingRequestDate)
                .apply()
            error.value = null
            premium.value = active
        }
    }

    companion object {
        @Volatile private var instance: Backing? = null

        private fun shared(context: Context): Backing =
            instance ?: synchronized(this) {
                instance ?: Backing(context).also { instance = it }
            }

        /** Reset in tests only, so each test starts from a clean process-wide state
         *  instead of leaking [Backing] across test methods within the same JVM. */
        internal fun resetForTest() {
            instance = null
        }
    }
}

/** A zero expiration means a non-expiring/lifetime entitlement (no expiration date was
 *  reported); anything else must still be in the future relative to [now]. Extracted as a
 *  pure function so the expiry rule itself is directly unit-testable, independent of the
 *  [BillingConfiguration.hasUsableKey] gate that the surrounding cache check also applies. */
internal fun isWithinExpiration(expiration: Long, now: Long = System.currentTimeMillis()): Boolean =
    expiration == 0L || expiration > now
