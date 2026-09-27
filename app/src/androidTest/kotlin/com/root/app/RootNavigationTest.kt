package com.root.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/** End-to-end navigation checks against the real [MainActivity] (via
 *  [createAndroidComposeRule]): switching to the Shona starter through the language
 *  picker, and that every secondary destination is reachable via the Profile tab,
 *  with the theme switch applied in place. */
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
            compose.onAllNodesWithContentDescription("Profile").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Profile").performClick()
        compose.onNodeWithText("How Root works").performScrollTo().performClick()
        compose.onNodeWithText("WELCOME TO ROOT").assertExists()
        compose.onNodeWithText("Skip").performClick()
    }

    @Test fun shonaStarterCanBeOpenedFromTheLanguagePicker() {
        compose.waitUntil(15_000) {
            dismissOnboardingIfShown()
            compose.onAllNodesWithContentDescription("Profile").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Profile").performClick()
        compose.onNodeWithText("Language ·", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("Shona").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Hello (one person)").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Reveal the word").performScrollTo().performClick()
        compose.onNodeWithText("Mhoro").assertExists()
        compose.onNodeWithContentDescription("Profile").performClick()
        compose.onNodeWithText("Language · Shona").performScrollTo().performClick()
        compose.onNodeWithText("Dholuo").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Dholuo").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun secondaryScreensAreConnectedAndThemeSwitchesInPlace() {
        compose.waitUntil(15_000) {
            dismissOnboardingIfShown()
            compose.onAllNodesWithContentDescription("Profile").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Profile").performClick()
        compose.onNodeWithText("Dark", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("Explore").performClick()
        compose.onNodeWithText("Greetings", substring = true).assertExists()
        compose.onNodeWithText("MY NOTEBOOK").performClick()
        compose.onNodeWithTag("explore-list").performScrollToNode(hasText("Add a word of your own"))
        compose.onNodeWithText("Add a word of your own").performScrollTo().performClick()
        // The contribution route must render a usable form, not navigate to a paywall.
        compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().let { check(it.size >= 3) }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("Profile").performClick()
        compose.onNodeWithText("Light", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("Root Premium").performScrollTo().performClick()
        compose.onNodeWithText("Purchases are not set up yet.").performScrollTo().assertExists()
        compose.onNodeWithText("US$4.99").performScrollTo().assertExists()
        compose.onNodeWithText("US$9.99").performScrollTo().assertExists()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        // Back from the paywall returns to Profile, which has no Profile header icon.
        compose.onAllNodesWithContentDescription("Profile").fetchSemanticsNodes()
            .takeIf { it.isNotEmpty() }?.let { compose.onNodeWithContentDescription("Profile").performClick() }
        compose.onNodeWithText("Teach someone one word").performScrollTo().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Share word card").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Share word card").performScrollTo().assertHasClickAction()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        // The weekly challenge card lives on Practice; the share flow started from Profile.
        compose.onNodeWithText("Practice").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("I did this").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("I did this").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("A word put to use.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("A word put to use.").performScrollTo().assertExists()
    }

    @Test fun soundScreenOpensFromProfileWithSoundOnByDefault() {
        compose.waitUntil(15_000) {
            dismissOnboardingIfShown()
            compose.onAllNodesWithContentDescription("Profile").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Profile").performClick()
        val effects = isToggleable() and hasText("Sound effects")
        val startup = isToggleable() and hasText("Startup sound")
        // Profile shows one Sound entry; its settings live one level in, and the Lab one more.
        compose.onNodeWithText("Sound · On · 100%").performScrollTo().assertHasClickAction()
        check(compose.onAllNodes(effects).fetchSemanticsNodes().isEmpty())

        compose.onNodeWithText("Sound · On · 100%").performScrollTo().performClick()
        compose.onNode(effects).performScrollTo().assertIsOn()
        compose.onNode(startup).performScrollTo().assertIsOn().assertIsEnabled()
        compose.onNode(hasContentDescription("Sound effect volume") and hasStateDescription("100 percent"))
            .performScrollTo().assertExists()
        check(compose.onAllNodesWithText("Listen before you choose.").fetchSemanticsNodes().isEmpty())

        compose.onNodeWithText("Sound Lab").performScrollTo().performClick()
        compose.onNodeWithText("Listen before you choose.").assertExists()
        check(compose.onAllNodes(effects).fetchSemanticsNodes().isEmpty()) { "the Lab is for listening only" }
        compose.onNodeWithText("Watch the opening with sound").performScrollTo().assertHasClickAction()
        compose.onNodeWithContentDescription("Play Root growth").performScrollTo().assertHasClickAction()
        compose.onNodeWithContentDescription("Play Root motif A").performScrollTo().assertHasClickAction()
        compose.onNodeWithContentDescription("Play Root motif B").performScrollTo().assertHasClickAction()
        // A preview either plays or explains why the device kept it quiet; it never changes settings.
        compose.onNodeWithContentDescription("Play Reveal").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Playing Reveal.").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Reveal stayed quiet:", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        compose.onNode(effects).performScrollTo().assertIsOn().performClick()
        compose.onNode(startup).performScrollTo().assertIsNotEnabled()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Sound · Off").performScrollTo().assertExists()
        val preferences = com.root.app.data.RootPreferences(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext,
        )
        check(!preferences.soundEffectsEnabled && preferences.startupSoundEnabled)
    }
}