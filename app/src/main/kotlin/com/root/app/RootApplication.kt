package com.root.app

import android.app.Application
import com.root.app.billing.BillingConfiguration
import com.root.app.billing.EntitlementStore

/**
 * Process entry point. Billing configuration is optional and guarded: an unconfigured
 * or invalid RevenueCat key leaves [BillingConfiguration.configure] returning false and
 * the app runs with offline practice only (see [BillingConfiguration.hasUsableKey]).
 * When configuration succeeds, an entitlement refresh is kicked off immediately so
 * cached premium/reward state is as current as possible before any screen needs it.
 */
class RootApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BillingConfiguration.configure(this)) {
            EntitlementStore(this).refresh()
        }
    }
}
