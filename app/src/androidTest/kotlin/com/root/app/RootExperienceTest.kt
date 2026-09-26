package com.root.app

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.root.app.data.ConfidenceLevel
import com.root.app.data.PhraseEntity
import com.root.app.ui.DesignStudyScreen
import com.root.app.ui.OnboardingScreen
import com.root.app.ui.PracticeScreen
import com.root.app.ui.SessionCompleteScreen
import com.root.app.ui.theme.RootTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Compose UI tests for reveal/rating accessibility (both themes), the design
 *  study's logo/launch-frame display, and completion copy that avoids
 *  score-pressure language. Uses [createComposeRule] (no real Activity/navigation)
 *  since these target individual screens in isolation. */
class RootExperienceTest {
    @get:Rule val compose = createComposeRule()
    private val phrase = PhraseEntity(id = "ui-test", packId = "sample", prompt = "Hello", answer = "Amosi", audioAsset = null)

    @Test fun revealAndAllButtonOutcomesAreAccessible() {
        val turn = mutableIntStateOf(0)
        val ratings = mutableListOf<ConfidenceLevel>()
        compose.setContent {
            RootTheme {
                Surface {
                    PracticeScreen(phrase, "Dholuo", 0, turn.intValue,
                        onRate = { ratings.add(it); turn.intValue++; true }, onMore = {}, onTeach = {})
                }
            }
        }
        listOf("Missed", "Close", "Got it").forEach { label ->
            compose.onNodeWithText("Reveal the word").performScrollTo().performClick()
            compose.onNodeWithText("Amosi").assertExists()
            compose.onNodeWithText(label).performScrollTo().performClick()
            compose.waitForIdle()
        }
        assertEquals(ConfidenceLevel.entries.toList(), ratings)
    }

    @Test fun darkModeHasTheSameRevealAndRatingPath() {
        compose.setContent {
            RootTheme(darkTheme = true) {
                Surface { PracticeScreen(phrase, "Dholuo", 0, 0, { true }, {}, {}) }
            }
        }
        compose.onNodeWithText("Reveal the word").performScrollTo().performClick()
        compose.onNodeWithText("Amosi").assertExists()
        compose.onNodeWithText("Missed").assertHasClickAction()
        compose.onNodeWithText("Close").assertHasClickAction()
        compose.onNodeWithText("Got it").assertHasClickAction()
    }

    @Test fun designStudyShowsLogoAtIconScaleAndLaunchFrames() {
        compose.setContent {
            RootTheme {
                Surface { DesignStudyScreen({}, {}) }
            }
        }
        compose.onNodeWithText("Root").assertExists()
        compose.onNodeWithText("Seed").assertExists()
        compose.onNodeWithText("Taking root").assertExists()
        compose.onNodeWithText("Settled").assertExists()
        compose.onNodeWithText("Replay launch").performScrollTo().assertHasClickAction()
    }

    @Test fun completionOffersClosureWithoutScorePressure() {
        compose.setContent {
            RootTheme {
                Surface { SessionCompleteScreen(2, 2, true, "Dholuo", null, {}, {}, {}) }
            }
        }
        compose.onNodeWithText("Enough for today.").assertExists()
        compose.onNodeWithText("Close this session").performScrollTo().assertHasClickAction()
    }

    @Test fun onboardingScreenStepsCanBeNavigatedAndSkipped() {
        var responded = false
        compose.setContent {
            RootTheme {
                Surface { OnboardingScreen(onRespond = { responded = true }) }
            }
        }
        compose.onNodeWithText("WELCOME TO ROOT").assertExists()
        compose.onNodeWithText("A quiet space for recall.").assertExists()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("HOW PRACTICE WORKS").assertExists()
        compose.onNodeWithText("Test your memory, then reveal.").assertExists()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("MAKE IT YOUR OWN").assertExists()
        compose.onNodeWithText("Your words stay on your device.").assertExists()
        compose.onNodeWithText("Begin practice").performClick()
        assertTrue(responded)
    }

    @Test fun onboardingScreenCanBeSkippedImmediately() {
        var responded = false
        compose.setContent {
            RootTheme(darkTheme = true) {
                Surface { OnboardingScreen(onRespond = { responded = true }) }
            }
        }
        compose.onNodeWithText("Skip").performClick()
        assertTrue(responded)
    }
}
