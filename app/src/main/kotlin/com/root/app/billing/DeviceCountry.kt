package com.root.app.billing

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * The country used to pick planned prices: the SIM's country, then the mobile
 * network's, then the device locale. None of these needs a permission or location.
 * Real charges always come from the store account's country, not from this.
 */
object DeviceCountry {
    fun of(context: Context): String? {
        val telephony = runCatching { context.getSystemService(TelephonyManager::class.java) }.getOrNull()
        return listOfNotNull(
            runCatching { telephony?.simCountryIso }.getOrNull(),
            runCatching { telephony?.networkCountryIso }.getOrNull(),
            Locale.getDefault().country,
        ).firstOrNull { it.isNotBlank() }?.uppercase()
    }
}
