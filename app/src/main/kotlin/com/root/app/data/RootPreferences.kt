package com.root.app.data

import android.content.Context

/**
 * Small device-local settings that are not learner content: which language is
 * currently active and which appearance ("system"/"light"/"dark") is selected.
 * Backed by SharedPreferences rather than Room because these are single scalar
 * values with no history and no need for query/observe support.
 */
class RootPreferences(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences("root_preferences", Context.MODE_PRIVATE)

    var activeLanguageId: String?
        get() = preferences.getString("active_language_id", null)
        set(value) {
            require(value == null || value.isNotBlank()) { "Language ID cannot be blank." }
            preferences.edit().putString("active_language_id", value).apply()
        }

    var theme: String
        get() = preferences.getString("theme", "system") ?: "system"
        set(value) {
            require(value in setOf("system", "light", "dark")) { "Unknown theme: $value" }
            preferences.edit().putString("theme", value).apply()
        }

    /** Version of onboarding/overview content the learner has last completed or
     *  explicitly skipped, or 0 if they have never seen it. Onboarding is always
     *  optional and skippable (see docs/TEACHING_CONTRACTS.md); this value only
     *  gates whether it's offered again on next launch, never [activeLanguageId]
     *  or [theme] — writing it must never touch those two keys. Bumping
     *  [ONBOARDING_CURRENT_VERSION] re-offers onboarding once to existing
     *  learners without re-forcing every subsequent launch. */
    var onboardingCompletedVersion: Int
        get() = preferences.getInt("onboarding_completed_version", 0)
        set(value) {
            require(value >= 0) { "onboarding_completed_version cannot be negative." }
            preferences.edit().putInt("onboarding_completed_version", value).apply()
        }

    /** The languages the learner chose (onboarding, Profile → Add a language), or
     *  null before their first choice. The active language always counts as theirs. */
    var myLanguageIds: Set<String>?
        get() = preferences.getStringSet("my_language_ids", null)?.toSet()
        set(value) { preferences.edit().putStringSet("my_language_ids", value?.toSet()).apply() }

    /** Interaction sounds are on by default; the learner can turn them off in Profile → Sound. */
    var soundEffectsEnabled: Boolean
        get() = preferences.getBoolean("sound_effects_enabled", com.root.app.audio.SoundSettings.DEFAULT_EFFECTS_ENABLED)
        set(value) { preferences.edit().putBoolean("sound_effects_enabled", value).apply() }

    /** The launch motif has its own switch (on by default), and also needs [soundEffectsEnabled]. */
    var startupSoundEnabled: Boolean
        get() = preferences.getBoolean("startup_sound_enabled", com.root.app.audio.SoundSettings.DEFAULT_STARTUP_ENABLED)
        set(value) { preferences.edit().putBoolean("startup_sound_enabled", value).apply() }

    /** Interaction-sound level relative to the device's system sound volume,
     *  0..1. Never changes Android's own stream volumes. */
    var soundVolume: Float
        get() = preferences.getFloat("sound_volume", DEFAULT_SOUND_VOLUME).coerceIn(0f, 1f)
        set(value) {
            require(value in 0f..1f) { "Sound volume must be between 0 and 1." }
            preferences.edit().putFloat("sound_volume", value).apply()
        }

    companion object {
        /** Bump when onboarding content changes meaningfully enough to re-offer it.
         *  2: onboarding ends by choosing your language. */
        const val ONBOARDING_CURRENT_VERSION = 2
        const val DEFAULT_SOUND_VOLUME = com.root.app.audio.SoundSettings.DEFAULT_VOLUME
    }
}
