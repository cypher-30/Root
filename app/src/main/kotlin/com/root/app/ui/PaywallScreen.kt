package com.root.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.getOfferingsWith
import com.revenuecat.purchases.purchaseWith

/**
 * This screen IS the Block 0 gate: "a fake purchase must succeed end-to-end before you
 * write any feature code." Wire your Test Store API key into RootApplication.kt first,
 * then run this screen and tap the button — a successful purchase + unlocked state
 * proves the whole Next Gen contest requirement (SDK powering a real IAP) works before
 * you sink time into content or polish.
 */
sealed interface PaywallState {
    data object Loading : PaywallState
    data class Ready(val packageTitle: String) : PaywallState
    data object Unlocked : PaywallState
    data class Error(val message: String) : PaywallState
}

@Composable
fun PaywallScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<PaywallState>(PaywallState.Loading) }

    // Fetch the current offering once. In Test Store this is whatever product/package
    // you set up against the "premium" entitlement in the dashboard.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        Purchases.sharedInstance.getOfferingsWith(
            onError = { state = PaywallState.Error(it.message) },
            onSuccess = { offerings ->
                val pkg = offerings.current?.availablePackages?.firstOrNull()
                state = if (pkg != null) {
                    PaywallState.Ready(pkg.product.title)
                } else {
                    PaywallState.Error("No packages configured yet — add one in the RevenueCat dashboard.")
                }
            },
        )
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val s = state) {
                is PaywallState.Loading -> CircularProgressIndicator()
                is PaywallState.Error -> Text("Paywall not ready yet: ${s.message}")
                is PaywallState.Unlocked -> Text("Unlocked. All packs available.")
                is PaywallState.Ready -> {
                    Text("Unlock all language packs")
                    Text(s.packageTitle)
                    Button(onClick = {
                        Purchases.sharedInstance.getOfferingsWith(
                            onError = { state = PaywallState.Error(it.message) },
                            onSuccess = { offerings ->
                                val pkg = offerings.current?.availablePackages?.firstOrNull()
                                if (pkg == null) {
                                    state = PaywallState.Error("Package disappeared — re-check the dashboard.")
                                    return@getOfferingsWith
                                }
                                Purchases.sharedInstance.purchaseWith(
                                    purchaseParams = com.revenuecat.purchases.PurchaseParams.Builder(
                                        context as android.app.Activity,
                                        pkg,
                                    ).build(),
                                    onError = { error, _ -> state = PaywallState.Error(error.message) },
                                    onSuccess = { _, customerInfo ->
                                        state = if (customerInfo.entitlements["premium"]?.isActive == true) {
                                            onUnlocked()
                                            PaywallState.Unlocked
                                        } else {
                                            PaywallState.Error("Purchase went through but entitlement 'premium' isn't active — check the entitlement is attached to this package in the dashboard.")
                                        }
                                    },
                                )
                            },
                        )
                    }) {
                        Text("Buy")
                    }
                }
            }
        }
    }
}
