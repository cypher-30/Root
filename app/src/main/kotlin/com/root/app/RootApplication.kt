package com.root.app

import android.app.Application
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration

/**
 * Block 0 of the plan lives here: "a fake purchase must succeed end-to-end before you
 * write any feature code." This is the wiring for that — it just needs a real key.
 *
 * Manual steps only you can do (need your own account/browser session):
 *   1. Sign up at https://app.revenuecat.com and create a new project ("Root").
 *   2. In the project, go to Apps > add a Test Store app (no Play/App Store account
 *      needed for this — that's the whole point of Next Gen's relaxed rules).
 *   3. Copy the Test Store API key (starts with "test_...") and paste it below,
 *      replacing the placeholder. Do NOT commit a real key to a public repo if you
 *      later switch to a real store's key — Test Store keys are fine to commit since
 *      they can't move real money, but treat this as a habit for later.
 *   4. Create one Entitlement (e.g. "premium") and one Product/Package behind it in
 *      the dashboard — Paywall.kt below expects an entitlement identifier of "premium".
 */
class RootApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Purchases.logLevel = LogLevel.DEBUG
        Purchases.configure(
            PurchasesConfiguration.Builder(
                context = this,
                apiKey = "test_REPLACE_WITH_YOUR_TEST_STORE_KEY",
            ).build()
        )
    }
}
