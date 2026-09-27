package com.root.app.ui.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import com.root.app.ui.icon.RootIcons

/**
 * The four top-level, always-visible destinations (see docs/DESIGN.md's
 * navigation section). Practice is the recall deck (unchanged core loop);
 * Learn is the active language's visible lesson path; Explore is phrase-pack
 * and teaching-unit discovery plus local search; Profile holds personal
 * progress, "Your words," drafts, and settings. All four exist from first
 * launch, including with no installed content — an empty state still shows
 * its own tab rather than hiding it.
 */
enum class RootDestination(val route: String, val label: String, val icon: ImageVector) {
    Practice("practice", "Practice", RootIcons.TabPractice),
    Learn("learn", "Learn", RootIcons.TabLearn),
    Explore("explore", "Explore", RootIcons.TabExplore),
    Profile("profile", "Profile", RootIcons.TabProfile),
    ;

    companion object {
        val all = entries
        fun fromRoute(route: String?): RootDestination? = all.firstOrNull { it.route == route }
    }
}
