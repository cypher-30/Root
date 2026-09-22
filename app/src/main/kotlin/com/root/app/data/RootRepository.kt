package com.root.app.data

import android.content.Context
import androidx.room.withTransaction
import com.root.app.billing.EntitlementStore
import com.root.app.practice.PracticeRateResult
import com.root.app.practice.PracticeRepository
import com.root.app.practice.PracticeSessionState
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Single point of access to Room, [RootPreferences], and [EntitlementStore]. UI code
 * and [com.root.app.RootViewModel] go through this class rather than touching the
 * database or preferences directly, so access rules ([ContentAccess]) and locked-pack
 * text are enforced in one place ([phrases], [phrase], [practice]).
 */
class RootRepository(context: Context) {
    private val context = context.applicationContext
    val db: AppDatabase = AppDatabase.get(this.context)
    private val preferences = RootPreferences(this.context)
    private val access = EntitlementStore(this.context)

    /** Durable practice-run backend — see [PracticeRepository]. Lazily created since
     *  it only needs [db] plus fresh entitlement reads, both already available here. */
    val practice: PracticeRepository by lazy {
        PracticeRepository(
            db = db,
            premium = { access.isPremium() },
            rewardUnlocked = { ReferralPrefs.hasUnlockedReward(this.context) },
        )
    }

    suspend fun beginOrResumePractice(languageId: String, packId: String? = null): PracticeSessionState? =
        practice.beginOrResume(languageId, packId)

    suspend fun continuePractice(sessionId: String): PracticeSessionState? =
        practice.continueSession(sessionId)

    suspend fun ratePractice(sessionId: String, entryId: String, outcome: ConfidenceLevel): PracticeRateResult =
        practice.rate(sessionId, entryId, outcome)

    suspend fun stopPractice(sessionId: String, reason: PracticeEndReason): Boolean =
        practice.stop(sessionId, reason)

    suspend fun revalidatePractice(sessionId: String): PracticeSessionState? =
        practice.revalidateCurrent(sessionId)

    suspend fun practiceState(sessionId: String): PracticeSessionState? =
        practice.state(sessionId)

    suspend fun nextDuePhrase(languageId: String, packId: String? = null): PhraseEntity? =
        practice.nextDuePhrase(languageId, packId)

    suspend fun initialize() {
        com.root.app.content.ContentBuildPolicy.apply(db)
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
        if (!ManagedContentAccess.isEligible(db.contentDao().getManagedPhrase(id))) return null
        val pack = requireNotNull(db.packDao().getById(phrase.packId)) { "Phrase pack is missing." }
        return phrase.takeIf { canAccess(pack, access.isPremium()) }
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

    private suspend fun unlockedPackIds(languageId: String, premium: Boolean): List<String> =
        ContentAccess.unlockedPackIds(db, languageId, premium, ReferralPrefs.hasUnlockedReward(context))

    private suspend fun canAccess(pack: PackEntity, premium: Boolean): Boolean {
        val language = requireNotNull(db.languageDao().getById(pack.languageId)) { "Pack language is missing." }
        return db.contentDao().getInstalledPack(pack.id)?.status != InstalledPackStatus.RETIRED &&
            ContentAccess.canAccess(language, pack, premium, ReferralPrefs.hasUnlockedReward(context))
    }
}
