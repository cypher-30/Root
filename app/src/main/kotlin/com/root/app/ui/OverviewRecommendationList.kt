package com.root.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.root.app.overview.OverviewRecommendations
import com.root.app.ui.theme.RootType

/**
 * Honest, fixed-order "what next" actions backed by [OverviewRecommendations].
 * Unavailable items are still rendered with their reason instead of being hidden.
 */
@Composable
fun OverviewRecommendationList(
    recommendations: List<OverviewRecommendations.Recommendation>,
    onRecommendationClick: (OverviewRecommendations.Kind) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "WHAT TO DO NEXT",
    emptyLabel: String = "Checking what is ready.",
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = RootType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (recommendations.isEmpty()) {
            Text(emptyLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            recommendations.forEach { recommendation ->
                val available = recommendation.availability is OverviewRecommendations.Availability.Available
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = { onRecommendationClick(recommendation.kind) },
                        enabled = available,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(recommendation.title, modifier = Modifier.fillMaxWidth())
                    }
                    val reason = (recommendation.availability as? OverviewRecommendations.Availability.Unavailable)?.reason
                    if (reason != null) {
                        Text(
                            text = reason,
                            style = RootType.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            }
        }
    }
}

