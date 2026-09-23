package com.root.app.billing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.EntitlementInfo
import com.revenuecat.purchases.EntitlementInfos
import com.revenuecat.purchases.OwnershipType
import com.revenuecat.purchases.PeriodType
import com.revenuecat.purchases.Store
import com.revenuecat.purchases.VerificationResult
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

/** [EntitlementStore.Backing] is a process-wide singleton by design (see its doc comment),
 *  so every test resets it and clears its backing prefs first - otherwise state would leak
 *  between test methods running in the same JVM. Covers the three failure modes the billing
 *  plan calls out: revocation, expiry, and out-of-order ("stale") async callbacks. Real
 *  Test Store purchase/restore evidence remains a separate, external, manually-verified gate
 *  (see PaywallViewModelTest's note) - this proves the local caching/ordering logic only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EntitlementStoreTest {
    private val application = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before
    fun resetSharedState() {
        EntitlementStore.resetForTest()
        application.getSharedPreferences(
            "root_entitlements_${com.root.app.BuildConfig.REVENUECAT_API_KEY.hashCode()}",
            Context.MODE_PRIVATE,
        ).edit().clear().apply()
    }

    @After
    fun resetAfter() {
        EntitlementStore.resetForTest()
    }

    private fun customerInfo(active: Boolean, requestDate: Date, expiration: Date? = null): CustomerInfo {
        val entitlements = if (active) {
            mapOf(
                BillingConfiguration.ENTITLEMENT_ID to EntitlementInfo(
                    BillingConfiguration.ENTITLEMENT_ID,
                    true,
                    true,
                    PeriodType.NORMAL,
                    Date(0),
                    Date(0),
                    expiration,
                    Store.PLAY_STORE,
                    "root-premium-monthly",
                    null,
                    false,
                    Date(0),
                    null,
                    OwnershipType.PURCHASED,
                    JSONObject(),
                    VerificationResult.NOT_REQUESTED,
                ),
            )
        } else {
            emptyMap()
        }
        return CustomerInfo(
            EntitlementInfos(entitlements),
            emptyMap(),
            emptyMap(),
            requestDate,
            1,
            Date(0),
            "test-user",
            null,
            null,
            JSONObject().put("subscriber", JSONObject()),
        )
    }

    @Test
    fun revocationDeactivatesPreviouslyActivePremium() {
        val store = EntitlementStore(application)
        store.recordCustomerInfo(customerInfo(active = true, requestDate = Date(1_000)))
        assertTrue(store.premium.value)

        store.recordCustomerInfo(customerInfo(active = false, requestDate = Date(2_000)))
        assertFalse("A later, revoked response must deactivate premium", store.premium.value)
    }

    @Test
    fun staleCallbackArrivingAfterANewerOneIsIgnored() {
        val store = EntitlementStore(application)
        store.recordCustomerInfo(customerInfo(active = true, requestDate = Date(5_000)))
        assertTrue(store.premium.value)

        // Simulates a delayed getCustomerInfoWith() response for an older request landing
        // after the update listener already applied a newer one.
        store.recordCustomerInfo(customerInfo(active = false, requestDate = Date(1_000)))
        assertTrue("A stale, older response must not regress already-applied state", store.premium.value)
    }

    @Test
    fun newerCallbackAfterAStaleOneStillApplies() {
        val store = EntitlementStore(application)
        store.recordCustomerInfo(customerInfo(active = true, requestDate = Date(5_000)))
        store.recordCustomerInfo(customerInfo(active = false, requestDate = Date(1_000))) // stale, ignored
        store.recordCustomerInfo(customerInfo(active = false, requestDate = Date(9_000))) // genuinely newer
        assertFalse("A genuinely newer response must still apply", store.premium.value)
    }

    @Test
    fun zeroExpirationNeverExpires() {
        assertTrue(isWithinExpiration(expiration = 0L, now = Long.MAX_VALUE))
    }

    @Test
    fun futureExpirationIsStillWithin() {
        assertTrue(isWithinExpiration(expiration = 10_000L, now = 5_000L))
    }

    @Test
    fun pastExpirationIsNoLongerWithin() {
        assertFalse(isWithinExpiration(expiration = 5_000L, now = 10_000L))
    }

    @Test
    fun expirationEqualToNowIsNoLongerWithin() {
        assertFalse(isWithinExpiration(expiration = 5_000L, now = 5_000L))
    }
}
