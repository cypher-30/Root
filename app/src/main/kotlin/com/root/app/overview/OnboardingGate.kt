package com.root.app.overview

/**
 * Deterministic onboarding gating: whether to offer the optional onboarding
 * overview on this launch, and how a skip vs. a completed walkthrough is
 * persisted — pure functions over the same two ints [RootPreferences] already
 * stores, so this is testable without touching SharedPreferences at all.
 * Onboarding is always skippable (see docs/TEACHING_CONTRACTS.md): [skip] and
 * [complete] both persist the same [currentVersion] marker, because "the
 * learner has made their choice for this version" is the only fact that
 * matters for not re-offering it — *why* they left is not tracked here.
 */
object OnboardingGate {
    /** True only the first time (or the first time after [currentVersion] is
     *  bumped) a learner reaches the home screen — never re-offered on every
     *  launch once they've completed or explicitly skipped it. */
    fun shouldOffer(completedVersion: Int, currentVersion: Int): Boolean = completedVersion < currentVersion

    /** The persisted-version value after either finishing or skipping —
     *  identical for both, deliberately: a skip is a real, respected choice,
     *  not a lesser outcome that gets nagged again next launch. */
    fun versionAfterResponse(currentVersion: Int): Int = currentVersion
}
