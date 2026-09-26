package com.root.app.billing

import android.app.Activity
import androidx.test.core.app.ApplicationProvider
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.EntitlementInfo
import com.revenuecat.purchases.EntitlementInfos
import com.revenuecat.purchases.Offering
import com.revenuecat.purchases.Offerings
import com.revenuecat.purchases.OwnershipType
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.PackageType
import com.revenuecat.purchases.PeriodType
import com.revenuecat.purchases.PresentedOfferingContext
import com.revenuecat.purchases.PurchasesError
import com.revenuecat.purchases.PurchasesErrorCode
import com.revenuecat.purchases.Store
import com.revenuecat.purchases.VerificationResult
import com.revenuecat.purchases.models.Period
import com.revenuecat.purchases.models.Price
import com.revenuecat.purchases.models.PurchasingData
import com.revenuecat.purchases.models.StoreProduct
import com.revenuecat.purchases.models.StoreTransaction
import com.revenuecat.purchases.models.SubscriptionOption
import com.revenuecat.purchases.models.SubscriptionOptions
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

/** Drives [PaywallViewModel]'s state machine deterministically with a fake
 *  [PurchasesGateway], instead of the real RevenueCat SDK/store (see the plan's
 *  billing package note: real Test Store purchase/restore evidence is a separate,
 *  external, manually-verified gate — this covers the local state transitions and
 *  environment guards that *can* be proven in a JVM unit test). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaywallViewModelTest {
    private val application = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun fakePackage(
        id: String,
        productType: com.revenuecat.purchases.ProductType = com.revenuecat.purchases.ProductType.INAPP,
    ): Package {
        val product = object : StoreProduct {
            override val id = "$id-product"
            override val type = productType
            override val price = Price("$1.99", 1_990_000L, "USD")
            override val name = id
            override val title = id
            override val description = id
            override val period: Period? = null
            override val subscriptionOptions: SubscriptionOptions? = null
            override val defaultOption: SubscriptionOption? = null
            override val purchasingData = object : PurchasingData {
                override val productId = "$id-product"
                override val productType = productType
            }
            override val presentedOfferingIdentifier: String? = null
            override val presentedOfferingContext: PresentedOfferingContext? = null
            override val sku = "$id-product"
            override fun copyWithOfferingId(offeringId: String) = this
            override fun copyWithPresentedOfferingContext(offeringContext: PresentedOfferingContext?) = this
        }
        return Package(id, PackageType.LIFETIME, product, "default")
    }

    private fun fakeOfferings(packages: List<Package>): Offerings {
        val offering = Offering("default", "Default", emptyMap(), packages)
        return Offerings(offering, mapOf("default" to offering))
    }

    private fun fakeCustomerInfo(active: Boolean): CustomerInfo {
        val entitlements = if (active) {
            mapOf(
                BillingConfiguration.ENTITLEMENT_ID to EntitlementInfo(
                    BillingConfiguration.ENTITLEMENT_ID,
                    true,
                    true,
                    PeriodType.NORMAL,
                    Date(),
                    Date(),
                    null,
                    Store.PLAY_STORE,
                    "root-premium-lifetime",
                    null,
                    false,
                    Date(),
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
            Date(),
            1,
            Date(),
            "test-user",
            null,
            null,
            JSONObject().put("subscriber", JSONObject()),
        )
    }

    private sealed class Outcome<out T> {
        data class Ok<T>(val value: T) : Outcome<T>()
        data class Err(val error: PurchasesError) : Outcome<Nothing>()
    }

    private inner class FakeGateway : PurchasesGateway {
        var offeringsResult: Outcome<Offerings> = Outcome.Ok(fakeOfferings(emptyList()))
        var purchaseResult: Outcome<Pair<StoreTransaction?, CustomerInfo>>? = null
        var purchaseCancelled = false
        var restoreResult: Outcome<CustomerInfo>? = null
        var purchaseCalls = 0
        var restoreCalls = 0

        override fun getOfferings(onError: (PurchasesError) -> Unit, onSuccess: (Offerings) -> Unit) {
            when (val result = offeringsResult) {
                is Outcome.Ok -> onSuccess(result.value)
                is Outcome.Err -> onError(result.error)
            }
        }

        override fun purchase(
            activity: Activity,
            selected: Package,
            onError: (PurchasesError, Boolean) -> Unit,
            onSuccess: (StoreTransaction?, CustomerInfo) -> Unit,
        ) {
            purchaseCalls++
            when (val result = purchaseResult) {
                is Outcome.Ok -> onSuccess(result.value.first, result.value.second)
                is Outcome.Err -> onError(result.error, purchaseCancelled)
                null -> Unit
            }
        }

        override fun restore(onError: (PurchasesError) -> Unit, onSuccess: (CustomerInfo) -> Unit) {
            restoreCalls++
            when (val result = restoreResult) {
                is Outcome.Ok -> onSuccess(result.value)
                is Outcome.Err -> onError(result.error)
                null -> Unit
            }
        }
    }

    private fun viewModel(gateway: FakeGateway, configured: Boolean = true, premiumContent: Boolean = true) =
        PaywallViewModel(application, gateway, isConfigured = { configured }, premiumContentAvailable = { premiumContent })

    @Test
    fun nothingIsSoldWithoutPremiumContent() {
        val gateway = FakeGateway()
        gateway.offeringsResult = Outcome.Ok(fakeOfferings(listOf(fakePackage("lifetime"))))
        val vm = viewModel(gateway, premiumContent = false)
        assertEquals(PaywallState.NothingToUnlock, vm.state.value)
    }

    @Test
    fun subscriptionPackagesAreNeverOffered() {
        val gateway = FakeGateway()
        gateway.offeringsResult = Outcome.Ok(
            fakeOfferings(listOf(fakePackage("monthly", com.revenuecat.purchases.ProductType.SUBS), fakePackage("lifetime"))),
        )
        val vm = viewModel(gateway)
        val ready = vm.state.value as PaywallState.Ready
        assertEquals(listOf("lifetime"), ready.packages.map { it.identifier })
    }

    @Test
    fun notConfiguredWhenEnvironmentIsNotReady() {
        val vm = viewModel(FakeGateway(), configured = false)
        assertEquals(PaywallState.NotConfigured, vm.state.value)
    }

    @Test
    fun readyWithOfferedPackagesOnSuccessfulLoad() {
        val gateway = FakeGateway()
        gateway.offeringsResult = Outcome.Ok(fakeOfferings(listOf(fakePackage("lifetime"))))
        val vm = viewModel(gateway)
        val ready = vm.state.value as PaywallState.Ready
        assertEquals(listOf("lifetime"), ready.packages.map { it.identifier })
    }

    @Test
    fun errorStateWhenNoPackagesAreOffered() {
        val gateway = FakeGateway()
        gateway.offeringsResult = Outcome.Ok(fakeOfferings(emptyList()))
        val vm = viewModel(gateway)
        assertTrue(vm.state.value is PaywallState.Error)
    }

    @Test
    fun errorStateWhenOfferingsFailToLoad() {
        val gateway = FakeGateway()
        gateway.offeringsResult = Outcome.Err(PurchasesError(PurchasesErrorCode.NetworkError, "offline"))
        val vm = viewModel(gateway)
        assertTrue(vm.state.value is PaywallState.Error)
    }

    @Test
    fun purchaseRejectsAPackageThatWasNotOffered() {
        val gateway = FakeGateway()
        gateway.offeringsResult = Outcome.Ok(fakeOfferings(listOf(fakePackage("lifetime"))))
        val vm = viewModel(gateway)
        vm.purchase(robolectricActivity(), fakePackage("stale"))
        assertTrue(vm.state.value is PaywallState.Error)
        assertEquals(0, gateway.purchaseCalls)
    }

    @Test
    fun cancelledPurchaseReturnsToReadyWithANotice() {
        val gateway = FakeGateway()
        val offered = fakePackage("lifetime")
        gateway.offeringsResult = Outcome.Ok(fakeOfferings(listOf(offered)))
        val vm = viewModel(gateway)
        gateway.purchaseResult = Outcome.Err(PurchasesError(PurchasesErrorCode.PurchaseCancelledError, "cancelled"))
        gateway.purchaseCancelled = true
        vm.purchase(robolectricActivity(), offered)
        val ready = vm.state.value as PaywallState.Ready
        assertEquals("Purchase cancelled. Nothing changed.", ready.notice)
    }

    @Test
    fun failedPurchaseSurfacesAnErrorState() {
        val gateway = FakeGateway()
        val offered = fakePackage("lifetime")
        gateway.offeringsResult = Outcome.Ok(fakeOfferings(listOf(offered)))
        val vm = viewModel(gateway)
        gateway.purchaseResult = Outcome.Err(PurchasesError(PurchasesErrorCode.StoreProblemError, "down"))
        gateway.purchaseCancelled = false
        vm.purchase(robolectricActivity(), offered)
        assertTrue(vm.state.value is PaywallState.Error)
    }

    @Test
    fun failedRestoreSurfacesAnErrorState() {
        val gateway = FakeGateway()
        gateway.offeringsResult = Outcome.Ok(fakeOfferings(listOf(fakePackage("lifetime"))))
        val vm = viewModel(gateway)
        gateway.restoreResult = Outcome.Err(PurchasesError(PurchasesErrorCode.NetworkError, "offline"))
        vm.restore()
        assertTrue(vm.state.value is PaywallState.Error)
    }

    @Test
    fun restoreWithNoActivePurchaseReturnsToReadyWithANotice() {
        val gateway = FakeGateway()
        gateway.offeringsResult = Outcome.Ok(fakeOfferings(listOf(fakePackage("lifetime"))))
        val vm = viewModel(gateway)
        gateway.restoreResult = Outcome.Ok(fakeCustomerInfo(active = false))
        vm.restore()
        val ready = vm.state.value as PaywallState.Ready
        assertEquals("No active premium purchase was found for this store account.", ready.notice)
    }

    @Test
    fun actionsAreIgnoredWhileAPurchaseIsInFlight() {
        val gateway = FakeGateway()
        val offered = fakePackage("lifetime")
        gateway.offeringsResult = Outcome.Ok(fakeOfferings(listOf(offered)))
        val vm = viewModel(gateway)
        gateway.purchaseResult = null // never resolves -> stays Purchasing
        vm.purchase(robolectricActivity(), offered)
        assertTrue(vm.state.value is PaywallState.Purchasing)
        vm.restore()
        vm.load()
        assertEquals(0, gateway.restoreCalls)
        assertTrue("Still purchasing; load()/restore() must be ignored", vm.state.value is PaywallState.Purchasing)
    }

    private fun robolectricActivity(): Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get()
}
