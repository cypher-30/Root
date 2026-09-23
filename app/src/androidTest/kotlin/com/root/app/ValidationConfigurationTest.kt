package com.root.app

import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ValidationConfigurationTest {
    @Test fun isolatedPackageIsKeylessAndContainsDevelopmentFixtures() {
        assumeTrue(BuildConfig.BUILD_TYPE == "validation")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.root.app.validation", context.packageName)
        assertEquals("com.root.app.validation.test", InstrumentationRegistry.getInstrumentation().context.packageName)
        assertTrue(BuildConfig.DEBUG)
        assertEquals("", BuildConfig.REVENUECAT_API_KEY)
        assertEquals("", BuildConfig.CONTENT_CATALOG_URL)
        assertFalse(com.root.app.billing.BillingConfiguration.isReady)
        assertEquals("Root Validation", context.getString(R.string.app_name))
        context.assets.open("content/shona-pilot.json").use { assertTrue(it.read() != -1) }
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS)
        val provider = info.providers?.singleOrNull { it.name == "androidx.core.content.FileProvider" }
        assertNotNull(provider)
        assertEquals("com.root.app.validation.sharedfiles", provider!!.authority)
        assertFalse(provider.exported)
    }
}
