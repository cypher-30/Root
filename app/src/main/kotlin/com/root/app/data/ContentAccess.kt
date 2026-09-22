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

    /** Shared by [RootRepository] and [com.root.app.practice.PracticeRepository] so
     *  both agree on exactly which packs a learner can currently see. */
    suspend fun unlockedPackIds(
        db: AppDatabase,
        languageId: String,
        premium: Boolean,
        rewardUnlocked: Boolean,
    ): List<String> {
        val language = db.languageDao().getById(languageId) ?: return emptyList()
        return db.packDao().getForLanguage(languageId)
            .filter {
                canAccess(language, it, premium, rewardUnlocked) &&
                    db.contentDao().getInstalledPack(it.id)?.status != InstalledPackStatus.RETIRED
            }
            .map { it.id }
    }
}
