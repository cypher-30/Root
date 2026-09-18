package com.root.app.billing

import android.content.Context
import android.util.Log
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.root.app.BuildConfig

/**
 * Supply ROOT_REVENUECAT_API_KEY as a Gradle property, not a source-code credential.
 * The dashboard must publish a current offering whose products grant "premium".
 * Test Store requires Android SDK 9.9.0 or newer and is restricted to debug builds.
 * A blank/placeholder build remains usable for offline practice, but cannot prove a purchase.
 */
object BillingConfiguration {
    const val ENTITLEMENT_ID = "premium"

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
