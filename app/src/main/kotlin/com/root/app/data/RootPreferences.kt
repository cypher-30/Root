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
}
