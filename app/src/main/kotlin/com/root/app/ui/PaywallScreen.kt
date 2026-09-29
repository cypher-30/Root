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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.platform.LocalUriHandler
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
import com.root.app.BuildConfig
import com.root.app.billing.PaywallState
import com.root.app.billing.PaywallViewModel
import com.root.app.billing.PremiumOffer
import com.root.app.billing.PremiumPlan
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
fun PaywallScreen(languageName: String?, onUnlocked: () -> Unit, onBack: () -> Unit = {}) {
    val model: PaywallViewModel = viewModel(key = "paywall-$languageName", factory = PaywallViewModel.factory(languageName))
    val state by model.state.collectAsStateWithLifecycle()
    val redeemed by model.redeemed.collectAsStateWithLifecycle()
    val redeemError by model.redeemError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val unlockedCallback by rememberUpdatedState(onUnlocked)
    // Leave only when this visit unlocked premium; opened while owned, the screen stays so a code can be removed.
    var sawLocked by remember { mutableStateOf(false) }
    LaunchedEffect(state is PaywallState.Unlocked) {
        if (state !is PaywallState.Unlocked) sawLocked = true
        else if (sawLocked) unlockedCallback()
    }
    PaywallContent(
        state = state,
        languageName = languageName,
        onBack = onBack,
        onPurchase = { model.purchase(context.findActivity(), it) },
        onRestore = model::restore,
        onRetry = model::load,
        redeemed = redeemed,
        redeemError = redeemError,
        onRedeem = model::redeem,
        onRemoveCode = model::removeRedeemCode,
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
    languageName: String?,
    onBack: () -> Unit,
    onPurchase: (Package) -> Unit = {},
    onRestore: () -> Unit = {},
    onRetry: () -> Unit = {},
    redeemed: Boolean = false,
    redeemError: String? = null,
    onRedeem: (String) -> Unit = {},
    onRemoveCode: () -> Unit = {},
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
                    Text("ROOT PREMIUM", style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(36.dp))
                Text("More words.\nCloser to home.", style = RootType.heroAnswer)
                Spacer(Modifier.height(20.dp))
                Text(
                    "Premium opens extra phrase sets in each language, like words for the people you love " +
                        "and phrases that keep a conversation going. The starter sets stay free.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(32.dp))
                Text("WHAT PREMIUM INCLUDES", style = RootType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PremiumOffer.included(languageName).forEach { (title, detail) -> Benefit(title, detail) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(20.dp))
                Text("ALWAYS FREE", style = RootType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(
                    PremiumOffer.alwaysFree,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Once the store returns a real plan, its localized price replaces this.
                if (packages.isEmpty() && state !is PaywallState.Unlocked) {
                    Spacer(Modifier.height(28.dp))
                    PlannedPrices(languageName)
                }
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
                        PaywallState.NothingToUnlock -> {
                            Text("No premium sets yet.", style = RootType.editorialTitle)
                            Text(
                                "There are no premium sets in the app yet, so nothing is for sale. " +
                                    "Sets appear here only once they're in the app.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        PaywallState.Unlocked -> {
                            Text("Premium is yours.", style = RootType.editorialTitle)
                            Text(
                                when {
                                    redeemed -> "Every premium set is open with a showcase code on this device."
                                    languageName != null -> "Premium for $languageName is active."
                                    else -> "Premium for every language is active."
                                },
                            )
                            if (redeemed) {
                                OutlinedButton(onClick = onRemoveCode, shape = RoundedCornerShape(4.dp)) {
                                    Text("Remove code")
                                }
                            }
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
                    val kind = PremiumPlan.of(plan) ?: return@forEach
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
                            Text(PremiumOffer.planTitle(kind, languageName), style = MaterialTheme.typography.titleMedium)
                            Text(plan.priceLabel(), style = RootType.editorialTitle)
                            Text(
                                PremiumOffer.planDetail(kind, languageName),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "One-time purchase, not a subscription. Confirm the final charge in the store.",
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
                if (state !is PaywallState.Unlocked) {
                    Spacer(Modifier.height(24.dp))
                    RedeemCodeField(enabled = !busy, error = redeemError, onRedeem = onRedeem)
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
                    "You only ever pay for sets that are already in the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Restoring purchases brings back premium access for your store account. " +
                        "It can't bring back your own words, recordings, or practice history, which are kept only on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PolicyLinks()
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

/** A showcase code (see [com.root.app.billing.RedeemCode]) for judges and demos. */
@Composable
private fun RedeemCodeField(enabled: Boolean, error: String?, onRedeem: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    fun submit() { focus.clearFocus(); onRedeem(code) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("HAVE A CODE?", style = RootType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("Redeem code") },
                singleLine = true,
                isError = error != null,
                enabled = enabled,
                shape = MaterialTheme.shapes.small,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.weight(1f).testTag("redeem-code"),
            )
            OutlinedButton(onClick = { submit() }, enabled = enabled, shape = RoundedCornerShape(4.dp)) { Text("Redeem") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun PolicyLinks() {
    val uriHandler = LocalUriHandler.current
    val links = listOf(
        "Privacy policy" to BuildConfig.PRIVACY_POLICY_URL,
        "Terms" to BuildConfig.TERMS_URL,
        "Support" to BuildConfig.SUPPORT_URL,
    ).filter { it.second.isNotBlank() }
    if (links.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        links.forEach { (label, url) ->
            TextButton(onClick = { runCatching { uriHandler.openUri(url) } }, shape = RoundedCornerShape(4.dp)) {
                Text(label)
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
private fun PlannedPrices(languageName: String?) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("PLANNED PRICES", style = RootType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PlannedPriceRow(if (languageName != null) "$languageName only" else "One language", PremiumOffer.plannedLanguagePrice)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PlannedPriceRow("All languages", PremiumOffer.plannedAllLanguagesPrice)
            Text(
                PremiumOffer.plannedPriceNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlannedPriceRow(title: String, price: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text("once", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(price, style = RootType.editorialTitle)
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
    RootTheme { PaywallContent(state = PaywallState.NotConfigured, languageName = "Dholuo", onBack = {}) }
}
