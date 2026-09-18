package com.root.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** A local thank-you for opening Android's share sheet, never a verified referral. */
object ReferralPrefs {
    private const val PREFS_NAME = "root_referral_prefs"
    private const val KEY_SHARE_OPENED = "share_sheet_opened"
    private const val LEGACY_SENT_COUNT = "invites_sent_count"

    const val REWARD_PACK_ID = "pack-dholuo-market"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun recordShareSheetOpened(context: Context) {
        prefs(context).edit().putBoolean(KEY_SHARE_OPENED, true).apply()
    }

    fun hasUnlockedReward(context: Context): Boolean = isUnlocked(prefs(context))

    fun observeReward(context: Context): Flow<Boolean> = callbackFlow {
        val preferences = prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(isUnlocked(preferences))
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(isUnlocked(preferences))
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    private fun isUnlocked(preferences: SharedPreferences): Boolean =
        preferences.getBoolean(KEY_SHARE_OPENED, false) || preferences.getInt(LEGACY_SENT_COUNT, 0) > 0
}
