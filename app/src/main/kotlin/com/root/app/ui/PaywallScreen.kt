package com.root.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.ProductType
import com.revenuecat.purchases.models.Period
import com.root.app.billing.PaywallState
import com.root.app.billing.PaywallViewModel
import com.root.app.ui.icon.RootIcons
import com.root.app.ui.theme.RootTheme
import com.root.app.ui.theme.RootType

/**
 * The premium unlock screen. Purely a view over [PaywallViewModel]'s [PaywallState]:
 * this composable holds no billing logic itself, so every store outcome (loading,
 * not-configured, ready, purchasing, restoring, unlocked, error) is exhaustively
 * handled in [PaywallContent] below.
 */
@Composable
fun PaywallScreen(onUnlocked: () -> Unit, onBack: () -> Unit = {}) {
    val model: PaywallViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val unlockedCallback by rememberUpdatedState(onUnlocked)
    LaunchedEffect(state is PaywallState.Unlocked) {
        if (state is PaywallState.Unlocked) unlockedCallback()
    }
    PaywallContent(
        state = state,
        onBack = onBack,
        onPurchase = { model.purchase(context.findActivity(), it) },
        onRestore = model::restore,
        onRetry = model::load,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.findActivity()
    else -> null
}

@Composable
private fun PaywallContent(
    state: PaywallState,
    onBack: () -> Unit,
    onPurchase: (Package) -> Unit = {},
    onRestore: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val busy = state is PaywallState.Purchasing || state is PaywallState.Restoring
    val packages = when (state) {
        is PaywallState.Ready -> state.packages
        is PaywallState.Purchasing -> state.packages
        is PaywallState.Restoring -> state.packages
        is PaywallState.Error -> state.packages
        else -> emptyList()
    }
    Scaffold { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) { Icon(RootIcons.Back, "Back") }
                    Text("ROOT / THE FULL COLLECTION", style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(36.dp))
                Text("More words.\nCloser to home.", style = RootType.heroAnswer)
                Spacer(Modifier.height(20.dp))
                Text(
                    "Make room for more of your language.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(32.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Benefit("Every available pack", "Open the premium phrases and languages in the collection.")
                Benefit("A practice that stays yours", "Your saved words and practice history remain on this device.")
                Benefit("No account for your daily words", "Core practice works offline. The store needs a connection.")
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(28.dp))
                Column(
                    Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (state) {
                        PaywallState.Loading -> LoadingMessage("Loading plans from the store")
                        PaywallState.NotConfigured -> {
                            Text("Purchases are not set up yet.", style = RootType.editorialTitle)
                            Text(
                                "This build has no configured store connection. Nothing can be purchased or unlocked here. " +
                                    "You can keep practicing the free collection.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        PaywallState.Unlocked -> {
                            Text("The collection is yours.", style = RootType.editorialTitle)
                            Text("Premium access is active.")
                        }
                        is PaywallState.Error -> {
                            Text(state.message, color = MaterialTheme.colorScheme.error)
                            OutlinedButton(onClick = onRetry, shape = RoundedCornerShape(4.dp)) {
                                Text("Reload plans")
                            }
                        }
                        is PaywallState.Ready -> {
                            state.notice?.let {
                                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (state.packages.isEmpty()) {
                                OutlinedButton(onClick = onRetry, shape = RoundedCornerShape(4.dp)) {
                                    Text("Reload plans")
                                }
                            }
                        }
                        is PaywallState.Restoring -> LoadingMessage("Restoring purchases")
                        is PaywallState.Purchasing -> LoadingMessage("Waiting for the store")
                    }
                }
                packages.forEach { plan ->
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(plan.product.title, style = MaterialTheme.typography.titleMedium)
                            Text(plan.priceLabel(), style = RootType.editorialTitle)
                            if (plan.product.description.isNotBlank()) {
                                Text(
                                    plan.product.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                if (plan.product.type == ProductType.SUBS) {
                                    "Regular subscription price. Renews unless cancelled in your store account. " +
                                        "The store confirms any introductory offer and final charge."
                                } else "One-time purchase. Confirm the final charge in the store.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = { onPurchase(plan) },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(4.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                            ) {
                                Text(
                                    if (state is PaywallState.Purchasing && state.packageId == plan.identifier) {
                                        "Opening store..."
                                    } else "Continue with ${plan.product.price.formatted}",
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (state !is PaywallState.NotConfigured && state !is PaywallState.Unlocked) {
                    TextButton(
                        onClick = onRestore,
                        enabled = !busy && state !is PaywallState.Loading,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Restore purchases") }
                }
                TextButton(
                    onClick = onBack,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state is PaywallState.Unlocked) "Back to your words" else "Keep the free collection") }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Coming-soon packs stay unavailable until their phrases are ready. " +
                        "Premium does not promise content that is not here yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun Benefit(title: String, detail: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(RootIcons.Check, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.tertiary)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LoadingMessage(message: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun Package.priceLabel(): String {
    val price = product.price.formatted
    val period = product.period ?: return if (product.type == ProductType.SUBS) "$price / subscription" else price
    val unit = when (period.unit) {
        Period.Unit.DAY -> "day"
        Period.Unit.WEEK -> "week"
        Period.Unit.MONTH -> "month"
        Period.Unit.YEAR -> "year"
        Period.Unit.UNKNOWN -> return "$price / ${period.iso8601}"
    }
    return if (period.value == 1) "$price / $unit" else "$price / ${period.value} ${unit}s"
}

@Preview(name = "Paywall / paper", showBackground = true)
@Preview(name = "Paywall / charcoal", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PaywallPreview() {
    RootTheme { PaywallContent(state = PaywallState.NotConfigured, onBack = {}) }
}
