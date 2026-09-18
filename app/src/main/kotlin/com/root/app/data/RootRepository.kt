package com.root.app.data

import android.content.Context
import androidx.room.withTransaction
import com.root.app.billing.EntitlementStore
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Single point of access to Room, [RootPreferences], and [EntitlementStore]. UI code
 * and [com.root.app.RootViewModel] go through this class rather than touching the
 * database or preferences directly, so access rules ([ContentAccess]) and locked-pack
 * text are enforced in one place ([phrases], [phrase], [duePhrases], [recordAttempt]).
 */
class RootRepository(context: Context) {
    private val context = context.applicationContext
    val db: AppDatabase = AppDatabase.get(this.context)
    private val preferences = RootPreferences(this.context)
    private val access = EntitlementStore(this.context)

    suspend fun initialize() {
        SeedData.seedIfEmpty(db)
        val languages = languages()
        if (languages.none { it.id == activeLanguageId() }) {
            preferences.activeLanguageId = languages.firstOrNull { !it.isPremium }?.id
                ?: languages.firstOrNull()?.id
        }
    }

    suspend fun languages(): List<LanguageEntity> = db.languageDao().getAll()

    fun activeLanguageId(): String? = preferences.activeLanguageId

    fun setActiveLanguage(id: String) {
        preferences.activeLanguageId = id
    }

    fun getTheme(): String = preferences.theme

    fun setTheme(theme: String) {
        preferences.theme = theme
    }

    suspend fun packs(languageId: String): List<PackEntity> =
        db.packDao().getForLanguage(languageId)

    /** Locked pack metadata may be listed; its phrase text is never returned. */
    suspend fun phrases(packId: String): List<PhraseEntity> {
        val pack = requireNotNull(db.packDao().getById(packId)) { "Unknown pack: $packId" }
        return if (canAccess(pack, access.isPremium())) {
            db.phraseDao().getForPack(packId)
        } else {
            emptyList()
        }
    }

    /** A removed or currently locked phrase is unavailable for restoration/widget use. */
    suspend fun phrase(id: String): PhraseEntity? {
        require(id.isNotBlank()) { "Phrase ID cannot be blank." }
        val phrase = db.phraseDao().getById(id) ?: return null
        val pack = requireNotNull(db.packDao().getById(phrase.packId)) { "Phrase pack is missing." }
        return phrase.takeIf { canAccess(pack, access.isPremium()) }
    }

    suspend fun duePhrases(
        languageId: String,
        packId: String? = null,
        premium: Boolean = false,
    ): List<PhraseEntity> {
        val unlockedIds = unlockedPackIds(languageId, premium)
        if (unlockedIds.isEmpty() || (packId != null && packId !in unlockedIds)) return emptyList()
        return db.attemptDao().dueForLanguage(
            languageId = languageId,
            nowMillis = System.currentTimeMillis(),
            unlockedPackIds = unlockedIds,
            packId = packId,
        )
    }

    suspend fun recordAttempt(phraseId: String, confidence: ConfidenceLevel) {
        db.withTransaction {
            val phrase = requireNotNull(db.phraseDao().getById(phraseId)) { "Unknown phrase: $phraseId" }
            val pack = requireNotNull(db.packDao().getById(phrase.packId)) { "Phrase pack is missing." }
            check(canAccess(pack, access.isPremium())) { "This pack is locked." }
            val now = System.currentTimeMillis()
            db.attemptDao().insert(
                AttemptEntity(
                    phraseId = phraseId,
                    confidence = confidence,
                    reviewedAt = now,
                    nextDueAt = Scheduler.nextDueAt(confidence, now),
                )
            )
        }
    }

    suspend fun capabilityCount(languageId: String): Int =
        db.attemptDao().capabilityCount(languageId)

    suspend fun weeklyChallenge(languageId: String): WeeklyChallengeEntity = db.withTransaction {
        requireNotNull(db.languageDao().getById(languageId)) { "Unknown language: $languageId" }
        val weekLength = TimeUnit.DAYS.toMillis(7)
        val now = System.currentTimeMillis()
        val weekStart = Math.floorDiv(now, weekLength) * weekLength
        // Language-scoped stable IDs avoid a schema change and preserve old challenges.
        val id = "challenge-$languageId-$weekStart"
        db.challengeDao().getById(id)?.let { return@withTransaction it }
        // The old app only had Dholuo and used unscoped random challenge IDs.
        val legacy = if (languageId == "lang-dholuo") db.challengeDao().getLegacyForWeek(weekStart) else null
        val unlockedIds = unlockedPackIds(languageId, access.isPremium())
        val theme = db.attemptDao().mostRecentlyPracticedTheme(languageId, unlockedIds)
            ?: packs(languageId).firstOrNull { it.id in unlockedIds }?.theme
            ?: "Your words"
        val challenge = legacy?.copy(id = id)
            ?: WeeklyChallengeEntity(id = id, weekStart = weekStart, theme = theme)
        db.challengeDao().insertMissing(challenge)
        checkNotNull(db.challengeDao().getById(id))
    }

    suspend fun completeChallenge(id: String) {
        require(db.challengeDao().markCompleted(id, System.currentTimeMillis()) == 1) {
            "Unknown challenge: $id"
        }
    }

    suspend fun contribute(
        languageName: String,
        prompt: String,
        answer: String,
        audioPath: String?,
    ): PhraseEntity {
        val name = languageName.trim()
        val cleanPrompt = prompt.trim()
        val cleanAnswer = answer.trim()
        require(name.isNotEmpty()) { "Language name is required." }
        require(cleanPrompt.isNotEmpty()) { "Prompt is required." }
        require(cleanAnswer.isNotEmpty()) { "Answer is required." }
        val audio = audioPath?.let {
            require(it.isNotBlank()) { "Recording path cannot be blank." }
            val file = File(it).canonicalFile
            require(file.toPath().startsWith(context.filesDir.canonicalFile.toPath()) && file.isFile) {
                "Reference recording must be an existing app-local file."
            }
            file.path
        }
        return db.withTransaction {
            val language = languages().firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: LanguageEntity(name = name, isPremium = false).also {
                    db.languageDao().insertMissing(listOf(it))
                }
            val pack = PackEntity(
                id = ContentAccess.userPackId(language.id),
                languageId = language.id,
                theme = "Your words",
                sortOrder = Int.MAX_VALUE,
                isFree = true,
            )
            db.packDao().insertMissing(listOf(pack))
            val phrase = PhraseEntity(
                packId = pack.id,
                prompt = cleanPrompt,
                answer = cleanAnswer,
                audioAsset = audio,
            )
            db.phraseDao().upsertAll(listOf(phrase))
            phrase
        }
    }

    private suspend fun unlockedPackIds(languageId: String, premium: Boolean): List<String> {
        val language = requireNotNull(db.languageDao().getById(languageId)) { "Unknown language: $languageId" }
        val reward = ReferralPrefs.hasUnlockedReward(context)
        return packs(languageId).filter { ContentAccess.canAccess(language, it, premium, reward) }.map { it.id }
    }

    private suspend fun canAccess(pack: PackEntity, premium: Boolean): Boolean {
        val language = requireNotNull(db.languageDao().getById(pack.languageId)) { "Pack language is missing." }
        return ContentAccess.canAccess(language, pack, premium, ReferralPrefs.hasUnlockedReward(context))
    }
}
