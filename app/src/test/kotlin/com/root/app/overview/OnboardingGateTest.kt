package com.root.app.overview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingGateTest {
    @Test fun neverSeenIsOffered() {
        assertTrue(OnboardingGate.shouldOffer(completedVersion = 0, currentVersion = 1))
    }

    @Test fun completedAtCurrentVersionIsNotReOffered() {
        assertFalse(OnboardingGate.shouldOffer(completedVersion = 1, currentVersion = 1))
    }

    @Test fun bumpingCurrentVersionReOffersOnceMore() {
        assertTrue(OnboardingGate.shouldOffer(completedVersion = 1, currentVersion = 2))
    }

    @Test fun skipAndCompletePersistTheIdenticalVersionMarker() {
        // A skip is a real, respected choice — not tracked any differently
        // from an actual completion for gating purposes.
        assertEquals(2, OnboardingGate.versionAfterResponse(currentVersion = 2))
    }
}
