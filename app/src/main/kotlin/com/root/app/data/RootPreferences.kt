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

    companion object {
        /** Bump when onboarding content changes meaningfully enough to re-offer it. */
        const val ONBOARDING_CURRENT_VERSION = 1
    }
}
