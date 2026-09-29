package com.root.app.billing

import android.content.Context
import android.util.Log
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.getCustomerInfoWith
import com.revenuecat.purchases.interfaces.UpdatedCustomerInfoListener
import com.root.app.data.PremiumAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Every facade shares one process-wide state; UI and repository access use the same truth.
 *  Tracks every active Root entitlement: the all-languages bundle and each single language. */
class EntitlementStore(context: Context) {
    private val backing = shared(context.applicationContext)

    val access: StateFlow<PremiumAccess> = backing.access.asStateFlow()
    val refreshError: StateFlow<String?> = backing.error.asStateFlow()
    /** True while a [RedeemCode] is active on this device. */
    val redeemed: StateFlow<Boolean> = backing.redeemed.asStateFlow()

    fun current(): PremiumAccess = backing.cachedAccess()

    fun refresh(onChanged: (PremiumAccess) -> Unit = {}) {
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
                onChanged(current())
            },
        )
    }

    fun recordCustomerInfo(info: CustomerInfo) = backing.update(info)

    /** Grants every language when [code] is valid; returns false otherwise. */
    fun redeem(code: String): Boolean = RedeemCode.isValid(code).also { if (it) backing.setRedeemed(true) }

    /** Removes the code. Store purchases are untouched. */
    fun removeRedeemCode() = backing.setRedeemed(false)

    private class Backing(context: Context) {
        // Do not carry a Test Store entitlement into a differently configured production app.
        private val prefs = context.getSharedPreferences(
            "root_entitlements_${com.root.app.BuildConfig.REVENUECAT_API_KEY.hashCode()}",
            Context.MODE_PRIVATE,
        )
        // Kept apart from store entitlements so a code works without any RevenueCat key.
        private val codePrefs = context.getSharedPreferences("root_redeem_code", Context.MODE_PRIVATE)
        val access = MutableStateFlow(PremiumAccess.NONE)
        val error = MutableStateFlow<String?>(null)
        val redeemed = MutableStateFlow(codePrefs.getBoolean(KEY_REDEEMED, false))
        private var connected = false

        init {
            cachedAccess()
            connect()
        }

        /** Saved as "entitlementId|expirationMillis" (0 = lifetime). Older builds saved
         *  a single "premium" flag, which was the all-languages entitlement. */
        private fun savedEntitlements(): Map<String, Long> {
            val saved = prefs.getStringSet(KEY_ENTITLEMENTS, null)
            if (saved == null) {
                return if (prefs.getBoolean("premium", false)) {
                    mapOf(PremiumAccess.ALL_LANGUAGES_ENTITLEMENT to prefs.getLong("expiration", 0L))
                } else emptyMap()
            }
            return saved.mapNotNull { entry ->
                val id = entry.substringBeforeLast('|')
                entry.substringAfterLast('|', "").toLongOrNull()?.let { id to it }
            }.toMap()
        }

        @Synchronized
        fun cachedAccess(): PremiumAccess {
            val store = if (!BillingConfiguration.hasUsableKey) PremiumAccess.NONE
            else PremiumAccess.fromEntitlements(savedEntitlements().filterValues { isWithinExpiration(it) }.keys)
            val active = withCode(store)
            access.value = active
            return active
        }

        private fun withCode(store: PremiumAccess) = if (redeemed.value) store.copy(allLanguages = true) else store

        @Synchronized
        fun setRedeemed(value: Boolean) {
            codePrefs.edit().putBoolean(KEY_REDEEMED, value).apply()
            redeemed.value = value
            cachedAccess()
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
            val active = info.entitlements.active.filterKeys { PremiumAccess.isRootEntitlement(it) }
            prefs.edit()
                .putStringSet(KEY_ENTITLEMENTS, active.map { (id, e) -> "$id|${e.expirationDate?.time ?: 0L}" }.toSet())
                .remove("premium")
                .remove("expiration")
                .putLong("requestDate", incomingRequestDate)
                .apply()
            error.value = null
            access.value = withCode(PremiumAccess.fromEntitlements(active.keys))
        }
    }

    companion object {
        private const val KEY_ENTITLEMENTS = "entitlements"
        private const val KEY_REDEEMED = "showcase_code_redeemed"
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
