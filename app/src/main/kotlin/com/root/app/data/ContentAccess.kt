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

    /** True only when the one-time unlock would open at least one real,
     *  still-eligible phrase. Purchases must never sell empty placeholders. */
    suspend fun hasPremiumContent(db: AppDatabase): Boolean =
        db.languageDao().getAll().any { language ->
            db.packDao().getForLanguage(language.id).any { pack ->
                pack.id != userPackId(language.id) &&
                    (language.isPremium || (!pack.isFree && pack.id != ReferralPrefs.REWARD_PACK_ID)) &&
                    db.contentDao().getInstalledPack(pack.id)?.status != InstalledPackStatus.RETIRED &&
                    db.phraseDao().countForPack(pack.id) > 0
            }
        }

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
