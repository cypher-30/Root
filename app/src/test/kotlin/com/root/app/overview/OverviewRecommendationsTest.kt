package com.root.app.overview

import com.root.app.overview.OverviewRecommendations.Availability
import com.root.app.overview.OverviewRecommendations.Kind
import com.root.app.overview.OverviewRecommendations.OverviewSnapshot
import com.root.app.overview.OverviewRecommendations.recommend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverviewRecommendationsTest {
    private fun empty() = OverviewSnapshot(
        hasActiveLanguage = false, dueCount = 0, canPracticeMore = false,
        hasWeeklyChallenge = false, weeklyChallengeCompleted = false,
        hasOpenContributionDraft = false, hasInstalledPacks = false,
    )

    @Test fun sameSnapshotAlwaysProducesTheSameOrderedResult() {
        val snapshot = empty().copy(hasActiveLanguage = true, dueCount = 3)
        assertEquals(recommend(snapshot), recommend(snapshot))
        assertEquals(
            listOf(Kind.PRACTICE_DUE, Kind.CONTINUE_PRACTICING, Kind.WEEKLY_CHALLENGE,
                Kind.RESUME_DRAFT, Kind.ADD_A_WORD, Kind.EXPLORE_PACKS),
            recommend(snapshot).map { it.kind },
        )
    }

    @Test fun nothingDueIsHonestlyUnavailableNotHidden() {
        val result = recommend(empty().copy(hasActiveLanguage = true, dueCount = 0))
        val practice = result.first { it.kind == Kind.PRACTICE_DUE }
        assertTrue(practice.availability is Availability.Unavailable)
        assertEquals("Nothing due right now.", (practice.availability as Availability.Unavailable).reason)
    }

    @Test fun dueWordsMakePracticeAvailable() {
        val result = recommend(empty().copy(hasActiveLanguage = true, dueCount = 5))
        assertEquals(Availability.Available, result.first { it.kind == Kind.PRACTICE_DUE }.availability)
    }

    @Test fun noLanguageChosenMakesEveryLanguageScopedRecommendationUnavailable() {
        val result = recommend(empty())
        assertTrue(result.first { it.kind == Kind.PRACTICE_DUE }.availability is Availability.Unavailable)
        assertTrue(result.first { it.kind == Kind.EXPLORE_PACKS }.availability is Availability.Unavailable)
    }

    @Test fun weeklyChallengeIsUnavailableWhenAbsentOrAlreadyCompletedWithDistinctReasons() {
        val noChallenge = recommend(empty()).first { it.kind == Kind.WEEKLY_CHALLENGE }
        val doneChallenge = recommend(empty().copy(hasWeeklyChallenge = true, weeklyChallengeCompleted = true))
            .first { it.kind == Kind.WEEKLY_CHALLENGE }
        assertEquals("No challenge yet this week.", (noChallenge.availability as Availability.Unavailable).reason)
        assertEquals("Already done for this week.", (doneChallenge.availability as Availability.Unavailable).reason)
    }

    @Test fun addingAWordHasNoPrerequisiteAndIsAlwaysAvailable() {
        assertEquals(Availability.Available, recommend(empty()).first { it.kind == Kind.ADD_A_WORD }.availability)
    }

    @Test fun resumingADraftIsOnlyAvailableWhenOneActuallyExists() {
        assertTrue(recommend(empty()).first { it.kind == Kind.RESUME_DRAFT }.availability is Availability.Unavailable)
        assertEquals(
            Availability.Available,
            recommend(empty().copy(hasOpenContributionDraft = true)).first { it.kind == Kind.RESUME_DRAFT }.availability,
        )
    }
}
