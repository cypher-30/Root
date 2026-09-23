package com.root.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.root.app.data.AppDatabase
import org.junit.rules.ExternalResource

/** Runs before the Activity rule; never resets a developer's normal installation. */
class ValidationStateRule : ExternalResource() {
    override fun before() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.root.app.validation") {
            "State-resetting navigation tests require -ProotTestBuildType=validation."
        }
        check(BuildConfig.REVENUECAT_API_KEY.isEmpty() && BuildConfig.CONTENT_CATALOG_URL.isEmpty()) {
            "Navigation fixtures require keyless, offline validation configuration."
        }
        WorkManager.getInstance(context).cancelAllWork().result.get()
        AppDatabase.get(context).clearAllTables()
        listOf("root_preferences", "root_referral_prefs", "root_content").forEach { name ->
            check(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()) {
                "Could not reset validation preferences."
            }
        }
    }
}
