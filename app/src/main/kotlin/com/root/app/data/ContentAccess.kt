package com.root.app.data

/** Personal content stays free even when its language also has paid curated packs. */
object ContentAccess {
    fun userPackId(languageId: String): String = "pack-user-$languageId"

    fun canAccess(
        language: LanguageEntity,
        pack: PackEntity,
        premium: Boolean,
        rewardUnlocked: Boolean,
    ): Boolean =
        pack.languageId == language.id &&
            (pack.id == userPackId(language.id) ||
                premium ||
                (!language.isPremium &&
                    (pack.isFree || (rewardUnlocked && pack.id == ReferralPrefs.REWARD_PACK_ID))))
}
