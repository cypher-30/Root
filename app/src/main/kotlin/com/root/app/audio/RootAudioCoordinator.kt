package com.root.app.audio

/**
 * Main-thread arbiter between speech (reference/learner playback, recording,
 * an active reel sequence) and optional interaction sounds. Speech always
 * wins: claiming speech synchronously stops any effect before a player or
 * microphone is prepared, and effects never start while any owner holds it.
 *
 * Ownership is a set of owner objects rather than a counter, so a stale or
 * repeated release can only remove its own claim, never a newer owner's.
 */
internal object RootAudioCoordinator {
    private val speechOwners = mutableSetOf<Any>()
    private val effectStoppers = mutableSetOf<() -> Unit>()

    val speechActive: Boolean get() = speechOwners.isNotEmpty()

    fun claimSpeech(owner: Any) {
        speechOwners += owner
        effectStoppers.toList().forEach { it() }
    }

    fun releaseSpeech(owner: Any) {
        speechOwners -= owner
    }

    fun registerEffects(stop: () -> Unit) {
        effectStoppers += stop
    }

    fun unregisterEffects(stop: () -> Unit) {
        effectStoppers -= stop
    }

    /** Test-only reset of process-wide state. */
    internal fun resetForTest() {
        speechOwners.clear()
        effectStoppers.clear()
    }
}
