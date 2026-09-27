package com.root.app.data

/** Personal content stays free even when its language also has paid curated packs.
 *  Premium is per language (or all languages); see [PremiumAccess]. */
object ContentAccess {
    fun userPackId(languageId: String): String = "pack-user-$languageId"

    /** [premium] is true when the learner owns premium for this pack's language. */
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

    fun canAccess(language: LanguageEntity, pack: PackEntity, premium: PremiumAccess, rewardUnlocked: Boolean): Boolean =
        canAccess(language, pack, premium.covers(language), rewardUnlocked)

    /** True only when some premium plan would open at least one real,
     *  still-eligible phrase. Purchases must never sell empty placeholders or
     *  the unreviewed starter samples in [SeedCatalog]. */
    suspend fun hasPremiumContent(db: AppDatabase): Boolean = premiumContentLanguageKeys(db).isNotEmpty()

    /** [PremiumAccess.languageKey]s of the languages with sellable premium content.
     *  A single-language plan is only offered for these. */
    suspend fun premiumContentLanguageKeys(db: AppDatabase): Set<String> =
        db.languageDao().getAll().filter { language ->
            db.packDao().getForLanguage(language.id).any { pack ->
                pack.id != userPackId(language.id) &&
                    pack.id !in SeedCatalog.packIds &&
                    (language.isPremium || (!pack.isFree && pack.id != ReferralPrefs.REWARD_PACK_ID)) &&
                    db.contentDao().getInstalledPack(pack.id)?.status != InstalledPackStatus.RETIRED &&
                    db.phraseDao().countForPack(pack.id) > 0
            }
        }.map { PremiumAccess.languageKey(it.name) }.toSet()

    suspend fun unlockedPackIds(
        db: AppDatabase,
        languageId: String,
        premium: PremiumAccess,
        rewardUnlocked: Boolean,
    ): List<String> {
        val language = db.languageDao().getById(languageId) ?: return emptyList()
        return unlockedPackIds(db, languageId, premium.covers(language), rewardUnlocked)
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
