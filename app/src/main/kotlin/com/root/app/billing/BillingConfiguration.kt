package com.root.app.billing

import android.content.Context
import android.util.Log
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.root.app.BuildConfig

/**
 * Supply ROOT_REVENUECAT_API_KEY as a Gradle property, not a source-code credential.
 * The dashboard's current offering holds one-time products named as in [PremiumPlan]:
 * `root_premium_all` grants "premium" (every language) and `root_premium_<key>`
 * grants "premium_<key>" (one language). See docs/PREMIUM.md.
 * Test Store requires Android SDK 9.9.0 or newer and is restricted to debug builds.
 * A blank/placeholder build remains usable for offline practice, but cannot prove a purchase.
 */
object BillingConfiguration {
    const val ENTITLEMENT_ID = com.root.app.data.PremiumAccess.ALL_LANGUAGES_ENTITLEMENT

    val hasUsableKey: Boolean
        get() {
            val key = BuildConfig.REVENUECAT_API_KEY.trim()
            val testStoreAllowed = BuildConfig.DEBUG || !key.startsWith("test_", ignoreCase = true)
            return testStoreAllowed && key.isNotEmpty() && listOf(
                "replace", "placeholder", "your_", "example", "changeme", "not_configured", "<", ">",
            ).none { key.contains(it, ignoreCase = true) }
        }

    val isReady: Boolean
        get() = hasUsableKey && Purchases.isConfigured

    fun configure(context: Context): Boolean {
        if (!hasUsableKey) return false
        if (Purchases.isConfigured) return true
        Purchases.logLevel = if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.WARN
        return try {
            Purchases.configure(
                PurchasesConfiguration.Builder(
                    context.applicationContext,
                    BuildConfig.REVENUECAT_API_KEY.trim(),
                ).build(),
            )
            true
        } catch (error: IllegalArgumentException) {
            Log.e("RootBilling", "Billing configuration is invalid; practice remains available.", error)
            false
        }
    }
}
