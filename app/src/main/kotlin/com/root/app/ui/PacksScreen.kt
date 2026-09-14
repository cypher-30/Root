package com.root.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.root.app.data.PackEntity

/** One row's worth of what the Packs screen needs — the pack itself, plus how many
 *  phrases it actually has, so an unresourced pack (Block 2 content not curated yet)
 *  reads honestly as "Coming soon" instead of a broken "0 of 0". */
data class PackRow(val pack: PackEntity, val phraseCount: Int)

/** Buckets pack order into a visible path — "Beginner" -> "Getting there" ->
 *  "Building fluency" — instead of a flat list. Deliberately NOT CEFR labels (no
 *  A1/B1/C1): those imply a real proficiency curriculum this app doesn't have and
 *  can't honestly claim. This is just "how far into the pack order you are," dressed
 *  as a path because that's a cheap, honest way to make ordering feel like progress. */
private fun tierFor(sortOrder: Int): String = when (sortOrder) {
    0, 1 -> "Beginner"
    2, 3 -> "Getting there"
    else -> "Building fluency"
}

@Composable
fun PacksScreen(languageName: String, rows: List<PackRow>, onPackClick: (PackEntity) -> Unit) {
    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
            Text("$languageName packs")

            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                rows.groupBy { tierFor(it.pack.sortOrder) }.forEach { (tier, tierRows) ->
                    item { Text(tier) }
                    items(tierRows) { row ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            val status = when {
                                !row.pack.isFree -> "Locked"
                                row.phraseCount == 0 -> "Coming soon"
                                else -> "${row.phraseCount} phrases"
                            }
                            // Always tappable — MainActivity routes a locked pack to
                            // the paywall and a free one to practice, rather than just
                            // disabling the row (which would leave locked packs with
                            // no way to discover the unlock at all).
                            Button(onClick = { onPackClick(row.pack) }) {
                                Text("${row.pack.theme} — $status")
                            }
                        }
                    }
                }
            }
        }
    }
}
