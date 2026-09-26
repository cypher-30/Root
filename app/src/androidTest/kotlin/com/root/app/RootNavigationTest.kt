package com.root.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/** End-to-end navigation checks against the real [MainActivity] (via
 *  [createAndroidComposeRule]): switching to the Shona starter through the language
 *  picker, and that every secondary destination is reachable with the theme switch
 *  applied in place. */
class RootNavigationTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(ValidationStateRule()).around(compose)

    private fun dismissOnboardingIfShown() {
        if (compose.onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Skip").performClick()
        }
    }

    @Test fun onboardingCanBeCompletedAndReopened() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("WELCOME TO ROOT").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("WELCOME TO ROOT").assertExists()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("HOW PRACTICE WORKS").assertExists()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("MAKE IT YOUR OWN").assertExists()
        compose.onNodeWithText("Begin practice").performClick()

        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("More options").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("More options").performScrollTo().performClick()
        compose.onNodeWithText("How Root works").performScrollTo().performClick()
        compose.onNodeWithText("WELCOME TO ROOT").assertExists()
        compose.onNodeWithText("Skip").performClick()
    }

    @Test fun shonaStarterCanBeOpenedFromTheLanguagePicker() {
        compose.waitUntil(15_000) {
            dismissOnboardingIfShown()
            compose.onAllNodesWithContentDescription("More options").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("More options").performScrollTo().performClick()
        compose.onNodeWithText("Language ·", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("Shona").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Hello (one person)").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Reveal the word").performScrollTo().performClick()
        compose.onNodeWithText("Mhoro").assertExists()
        compose.onNodeWithContentDescription("More options").performScrollTo().performClick()
        compose.onNodeWithText("Language · Shona").performScrollTo().performClick()
        compose.onNodeWithText("Dholuo").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Dholuo").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun secondaryScreensAreConnectedAndThemeSwitchesInPlace() {
        compose.waitUntil(15_000) {
            dismissOnboardingIfShown()
            compose.onAllNodesWithContentDescription("More options").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("More options").performScrollTo().performClick()
        compose.onNodeWithText("The design study").performScrollTo().performClick()
        compose.onNodeWithText("THE ROOT STUDY").assertExists()
        compose.onNodeWithContentDescription("Back").performScrollTo().performClick()
        compose.onNodeWithContentDescription("More options").performScrollTo().performClick()
        compose.onNodeWithText("Dark", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("Browse phrase packs").performScrollTo().performClick()
        compose.onNodeWithText("Greetings", substring = true).assertExists()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("More options").performScrollTo().performClick()
        compose.onNodeWithText("Add a word of your own").performScrollTo().performClick()
        // The contribution route must render a usable form, not navigate to a paywall.
        compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().let { check(it.size >= 3) }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("More options").performScrollTo().performClick()
        compose.onNodeWithText("Light", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("Unlock more words").performScrollTo().performClick()
        compose.onNodeWithText("Purchases are not set up yet.").performScrollTo().assertExists()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("More options").performScrollTo().performClick()
        compose.onNodeWithText("Teach someone one word").performScrollTo().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Share word card").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Share word card").performScrollTo().assertHasClickAction()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("I did this").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("A word put to use.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("A word put to use.").performScrollTo().assertExists()
    }
}
