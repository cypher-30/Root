package com.root.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.root.app.data.ContentAccess
import com.root.app.data.PackEntity
import com.root.app.data.ReferralPrefs
import com.root.app.ui.icon.RootIcons
import com.root.app.ui.theme.RootTheme
import com.root.app.ui.theme.RootType

data class PackRow(val pack: PackEntity, val phraseCount: Int)

/**
 * Lists packs for the active language, showing lock/coming-soon/ready state derived
 * from [phraseCount], [premium], [rewardUnlocked], and [languagePremium]. A pack row
 * is only clickable when it has content ([phraseCount] > 0); an unlocked-but-empty
 * pack is not a valid destination and a locked-but-populated pack routes to the
 * paywall instead of practice (see the caller in MainActivity).
 */
@Composable
fun PacksScreen(
    languageName: String,
    rows: List<PackRow>,
    onPackClick: (PackEntity) -> Unit,
    onBack: () -> Unit = {},
    premium: Boolean = false,
    rewardUnlocked: Boolean = false,
    onLanguageClick: () -> Unit = {},
    onContribute: (() -> Unit)? = null,
    languagePremium: Boolean = false,
) {
    Scaffold { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            ) {
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(RootIcons.Back, contentDescription = "Back")
                        }
                        Text("THE COLLECTION", style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.height(32.dp))
                    Text("Words for\nordinary days.", style = RootType.editorialTitle)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "A little language for the moments that matter.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(20.dp))
                    TextButton(onClick = onLanguageClick, shape = RoundedCornerShape(4.dp)) {
                        Text("$languageName / Change language")
                    }
                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (rows.isEmpty()) {
                    item {
                        Column(
                            Modifier.padding(vertical = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("A collection in the making.", style = RootType.editorialTitle)
                            Text(
                                "There are no packs here yet. Choose another language" +
                                    if (onContribute != null) ", or add a phrase you know." else ".",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(rows.sortedBy { it.pack.sortOrder }, key = { it.pack.id }) { row ->
                    val available = row.phraseCount > 0
                    val reward = rewardUnlocked && row.pack.id == ReferralPrefs.REWARD_PACK_ID
                    val personal = row.pack.id == ContentAccess.userPackId(row.pack.languageId)
                    val unlocked = personal || premium || (!languagePremium && (row.pack.isFree || reward))
                    val status = when {
                        !available -> if (unlocked) "Coming soon" else "Locked · Coming soon"
                        unlocked && reward && !premium && !row.pack.isFree -> "Unlocked by sharing"
                        unlocked -> "Ready to practice"
                        else -> "Locked"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                enabled = available,
                                role = Role.Button,
                                onClickLabel = if (unlocked) "Practice ${row.pack.theme}" else "View premium plans",
                                onClick = { onPackClick(row.pack) },
                            )
                            .padding(vertical = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                row.pack.theme,
                                style = RootType.editorialTitle,
                                color = if (available) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                if (available || !unlocked) {
                                    "${row.phraseCount} ${if (row.phraseCount == 1) "phrase" else "phrases"} / $status"
                                } else status,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (available) {
                            Icon(
                                if (unlocked) RootIcons.Play else RootIcons.Lock,
                                contentDescription = null,
                                tint = if (unlocked) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                item {
                    Spacer(Modifier.height(28.dp))
                    Text(
                        "Learn what is here. Return as the collection grows.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    onContribute?.let { contribute ->
                        Spacer(Modifier.height(20.dp))
                        OutlinedButton(onClick = contribute, shape = RoundedCornerShape(4.dp)) {
                            Text("Add a phrase you know")
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Preview(name = "Packs / paper", showBackground = true)
@Preview(name = "Packs / charcoal", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PacksPreview() {
    RootTheme {
        PacksScreen(
            languageName = "Dholuo",
            rows = listOf(
                PackRow(PackEntity("greetings", "dholuo", "Greetings", 0, true), 12),
                PackRow(PackEntity(ReferralPrefs.REWARD_PACK_ID, "dholuo", "Market", 2, false), 8),
                PackRow(PackEntity("stories", "dholuo", "Stories", 3, false), 0),
            ),
            onPackClick = {},
            rewardUnlocked = true,
        )
    }
}
