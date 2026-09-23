package com.root.app.overview

/**
 * Deterministic, actionable "what should I do next" recommendations for the
 * menu/completion overview — pure state in, pure list out, so the exact same
 * [OverviewSnapshot] always produces the exact same ordered result (no clock,
 * randomness, or hidden mutable state). Every recommendation is either
 * [Availability.Available] or explicitly [Availability.Unavailable] with a
 * human reason — a recommendation is never silently hidden because its soft
 * prerequisite (e.g. "a challenge exists this week") is not yet met; the
 * learner sees *why* it isn't offered rather than wondering if it is missing.
 */
object OverviewRecommendations {
    enum class Kind { PRACTICE_DUE, CONTINUE_PRACTICING, WEEKLY_CHALLENGE, RESUME_DRAFT, ADD_A_WORD, EXPLORE_PACKS }

    sealed interface Availability {
        object Available : Availability
        data class Unavailable(val reason: String) : Availability
    }

    data class Recommendation(val kind: Kind, val title: String, val availability: Availability)

    data class OverviewSnapshot(
        val hasActiveLanguage: Boolean,
        val dueCount: Int,
        val canPracticeMore: Boolean,
        val hasWeeklyChallenge: Boolean,
        val weeklyChallengeCompleted: Boolean,
        val hasOpenContributionDraft: Boolean,
        val hasInstalledPacks: Boolean,
    )

    /** Fixed priority order — the same [Kind]s always appear in this order
     *  regardless of availability, so a returning learner's list never
     *  visually reshuffles between visits for reasons unrelated to their
     *  actual progress. */
    fun recommend(snapshot: OverviewSnapshot): List<Recommendation> = listOf(
        practiceDue(snapshot),
        continuePracticing(snapshot),
        weeklyChallenge(snapshot),
        resumeDraft(snapshot),
        addAWord(snapshot),
        explorePacks(snapshot),
    )

    private fun practiceDue(s: OverviewSnapshot) = Recommendation(
        Kind.PRACTICE_DUE, "Practice your due words",
        when {
            !s.hasActiveLanguage -> Availability.Unavailable("Choose a language first.")
            s.dueCount <= 0 -> Availability.Unavailable("Nothing due right now.")
            else -> Availability.Available
        },
    )

    private fun continuePracticing(s: OverviewSnapshot) = Recommendation(
        Kind.CONTINUE_PRACTICING, "Keep practicing beyond today",
        if (s.canPracticeMore) Availability.Available
        else Availability.Unavailable("You've covered everything available for now."),
    )

    private fun weeklyChallenge(s: OverviewSnapshot) = Recommendation(
        Kind.WEEKLY_CHALLENGE, "Try this week's challenge",
        when {
            !s.hasWeeklyChallenge -> Availability.Unavailable("No challenge yet this week.")
            s.weeklyChallengeCompleted -> Availability.Unavailable("Already done for this week.")
            else -> Availability.Available
        },
    )

    private fun resumeDraft(s: OverviewSnapshot) = Recommendation(
        Kind.RESUME_DRAFT, "Resume your unfinished word",
        if (s.hasOpenContributionDraft) Availability.Available
        else Availability.Unavailable("No unfinished word waiting."),
    )

    private fun addAWord(s: OverviewSnapshot) = Recommendation(
        Kind.ADD_A_WORD, "Add a word of your own",
        Availability.Available, // No prerequisite — always offerable.
    )

    private fun explorePacks(s: OverviewSnapshot) = Recommendation(
        Kind.EXPLORE_PACKS, "Explore more packs",
        if (s.hasActiveLanguage) Availability.Available
        else Availability.Unavailable("Choose a language first."),
    )
}
