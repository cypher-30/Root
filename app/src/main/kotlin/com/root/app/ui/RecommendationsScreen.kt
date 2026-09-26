package com.root.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.root.app.overview.OverviewRecommendations.Availability
import com.root.app.overview.OverviewRecommendations.Kind
import com.root.app.overview.OverviewRecommendations.Recommendation
import com.root.app.ui.icon.RootIcons
import com.root.app.ui.theme.RootTheme
import com.root.app.ui.theme.RootType

/**
 * Renders the "what to do next" list, using the deterministic [Recommendation]s
 * computed by the backend. Unavailable items are explicitly rendered in a disabled
 * visual state with their reason exposed as a caption, never silently hidden.
 */
@Composable
fun RecommendationsScreen(
    recommendations: List<Recommendation>,
    onAction: (Kind) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.padding(end = 12.dp)) {
                Icon(RootIcons.Back, contentDescription = "Back")
            }
            Text("WHAT TO DO NEXT", style = RootType.label)
        }

        Text(
            text = "Your path forward.",
            style = RootType.editorialTitle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 32.dp),
        )

        // List of Recommendations
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            recommendations.forEach { recommendation ->
                RecommendationItem(
                    recommendation = recommendation,
                ) {
                    if (recommendation.availability is Availability.Available) {
                        onAction(recommendation.kind)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecommendationItem(
    recommendation: Recommendation,
    onClick: () -> Unit,
) {
    val isAvailable = recommendation.availability is Availability.Available
    
    // A surface to hold the list item
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isAvailable) Modifier.clickable(role = Role.Button, onClick = onClick)
                else Modifier,
            ),
        shape = MaterialTheme.shapes.small,
        color = if (isAvailable) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (isAvailable) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = recommendation.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isAvailable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
                
                if (recommendation.availability is Availability.Unavailable) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = recommendation.availability.reason,
                        style = RootType.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
            
            if (isAvailable) {
                Icon(
                    imageVector = RootIcons.Back, // A placeholder for a forward arrow if we don't have one, or just omit it. Using back and rotating it isn't straightforward without modifier graphicLayer, but we can omit the trailing icon or use something else.
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp).rotate(180f),
                )
            } else {
                 Icon(
                    imageVector = RootIcons.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
        }
    }
}

@Preview(name = "Recommendations / Light", showBackground = true)
@Composable
private fun RecommendationsScreenLightPreview() {
    val mockRecommendations = listOf(
        Recommendation(Kind.PRACTICE_DUE, "Practice your due words", Availability.Available),
        Recommendation(Kind.CONTINUE_PRACTICING, "Keep practicing beyond today", Availability.Unavailable("You've covered everything available for now.")),
        Recommendation(Kind.WEEKLY_CHALLENGE, "Try this week's challenge", Availability.Unavailable("No challenge yet this week.")),
        Recommendation(Kind.RESUME_DRAFT, "Resume your unfinished word", Availability.Available),
        Recommendation(Kind.ADD_A_WORD, "Add a word of your own", Availability.Available),
        Recommendation(Kind.EXPLORE_PACKS, "Explore more packs", Availability.Available),
    )
    RootTheme(darkTheme = false) {
        RecommendationsScreen(
            recommendations = mockRecommendations,
            onAction = {},
            onBack = {},
        )
    }
}

@Preview(name = "Recommendations / Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun RecommendationsScreenDarkPreview() {
    val mockRecommendations = listOf(
        Recommendation(Kind.PRACTICE_DUE, "Practice your due words", Availability.Available),
        Recommendation(Kind.CONTINUE_PRACTICING, "Keep practicing beyond today", Availability.Unavailable("You've covered everything available for now.")),
    )
    RootTheme(darkTheme = true) {
        RecommendationsScreen(
            recommendations = mockRecommendations,
            onAction = {},
            onBack = {},
        )
    }
}
