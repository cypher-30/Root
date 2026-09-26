package com.root.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.root.app.ui.brand.RootMark
import com.root.app.ui.icon.RootIcons
import com.root.app.ui.root.RootPath
import com.root.app.ui.theme.RootTheme
import com.root.app.ui.theme.RootType
import kotlinx.coroutines.launch

/**
 * Data model for an individual page in the onboarding walkthrough.
 */
data class OnboardingStep(
    val eyebrow: String,
    val title: String,
    val description: String,
    val pathProgress: Float,
    val pageType: OnboardingPageType,
)

enum class OnboardingPageType {
    WELCOME,
    PRACTICE_FLOW,
    PRIVACY_OWNERSHIP,
}

private val onboardingSteps = listOf(
    OnboardingStep(
        eyebrow = "WELCOME TO ROOT",
        title = "A quiet space for recall.",
        description = "Root is designed for intentional learning. No streak counters, no daily notifications, no artificial urgency—just quiet, honest practice.",
        pathProgress = 0.35f,
        pageType = OnboardingPageType.WELCOME,
    ),
    OnboardingStep(
        eyebrow = "HOW PRACTICE WORKS",
        title = "Test your memory, then reveal.",
        description = "Rate how clearly a phrase came to mind. Missed phrases return to the deck. Each honest recall helps your roots grow.",
        pathProgress = 0.70f,
        pageType = OnboardingPageType.PRACTICE_FLOW,
    ),
    OnboardingStep(
        eyebrow = "MAKE IT YOUR OWN",
        title = "Your language stays on your device.",
        description = "Explore curated phrase packs, complete structured lessons, or add your own native phrases. Everything stays private on this device.",
        pathProgress = 1.0f,
        pageType = OnboardingPageType.PRIVACY_OWNERSHIP,
    ),
)

/**
 * The full-screen onboarding walkthrough presented on initial launch (or when
 * the onboarding version is bumped). Walkthrough can be stepped through sequentially
 * or skipped at any moment; both call [onRespond] to record completion/skip identically.
 */
@Composable
fun OnboardingScreen(
    onRespond: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState { onboardingSteps.size }
    val coroutineScope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == (onboardingSteps.size - 1)

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            // Header: Brand mark on the left, "Skip" button on the right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RootMark(compact = true)
                TextButton(
                    onClick = onRespond,
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "Skip",
                        style = RootType.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Page Pager
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { page ->
                OnboardingPage(step = onboardingSteps[page])
            }

            // Bottom Navigation Controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // Page Indicator Dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(onboardingSteps.size) { index ->
                        val active = pagerState.currentPage == index
                        Box(
                            modifier = Modifier
                                .size(if (active) 24.dp else 8.dp, 8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                ),
                        )
                    }
                }

                // Primary & Secondary Action Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Back button (visible when page > 0)
                    if (pagerState.currentPage > 0) {
                        IconButton(
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                }
                            },
                        ) {
                            Icon(
                                imageVector = RootIcons.Back,
                                contentDescription = "Previous step",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.size(48.dp))
                    }

                    // Next / Begin practice button
                    Button(
                        onClick = {
                            if (isLastPage) {
                                onRespond()
                            } else {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                }
                            }
                        },
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = if (isLastPage) "Begin practice" else "Next",
                            style = RootType.promptLarge.copy(fontSize = 16.sp, lineHeight = 20.sp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingPage(step: OnboardingStep) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.Start,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = step.eyebrow,
                style = RootType.label,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                text = step.title,
                style = RootType.editorialTitle,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Visual Illustration Box
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
            ),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                when (step.pageType) {
                    OnboardingPageType.WELCOME -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            RootPath(
                                progress = step.pathProgress,
                                modifier = Modifier.size(96.dp),
                            )
                        }
                    }
                    OnboardingPageType.PRACTICE_FLOW -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(16.dp),
                        ) {
                            // Mock card preview
                            Surface(
                                modifier = Modifier.fillMaxWidth(0.85f),
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                ),
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        text = "Hello",
                                        style = RootType.promptLarge.copy(fontSize = 16.sp, lineHeight = 22.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Amosi",
                                        style = RootType.editorialTitle.copy(fontSize = 22.sp, lineHeight = 28.sp),
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                            // Mock outcome buttons
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                listOf("Missed", "Close", "Got it").forEach { label ->
                                    Box(
                                        modifier = Modifier
                                            .clip(MaterialTheme.shapes.small)
                                            .background(
                                                if (label == "Got it") MaterialTheme.colorScheme.tertiaryContainer
                                                else MaterialTheme.colorScheme.surfaceVariant,
                                            )
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                    ) {
                                        Text(
                                            text = label,
                                            style = RootType.meta,
                                            color = if (label == "Got it") MaterialTheme.colorScheme.onTertiaryContainer
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    OnboardingPageType.PRIVACY_OWNERSHIP -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RootPath(
                                progress = step.pathProgress,
                                modifier = Modifier.size(80.dp),
                            )
                            Text(
                                text = "PAPER & INK",
                                style = RootType.label,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Body Description
        Text(
            text = step.description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(name = "Onboarding Screen / Light", showBackground = true)
@Composable
private fun OnboardingScreenLightPreview() {
    RootTheme(darkTheme = false) {
        OnboardingScreen(onRespond = {})
    }
}

@Preview(name = "Onboarding Screen / Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun OnboardingScreenDarkPreview() {
    RootTheme(darkTheme = true) {
        OnboardingScreen(onRespond = {})
    }
}
