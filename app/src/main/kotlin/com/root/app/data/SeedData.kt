package com.root.app.data

import androidx.room.withTransaction

/**
 * Installs [SeedCatalog] — source-checked starter phrases for Dholuo, Shona,
 * Swahili, and Amharic. Runs on every start and only adds what is missing, so
 * learner edits, retirements, and history are never overwritten.
 * Only phrases listed in [SeedAudio] get a reference recording; every other phrase
 * keeps null audio on purpose rather than a manufactured voice.
 */
object SeedData {
    suspend fun seedIfEmpty(db: AppDatabase) = db.withTransaction {
        val languages = db.languageDao()
        val packs = db.packDao()
        val phrases = db.phraseDao()

        for (language in SeedCatalog.languages) {
            // Reuse a learner-created language of the same name rather than listing it twice.
            val entity = languages.getById(language.defaultId)
                ?: languages.getAll().firstOrNull { it.name.equals(language.name, ignoreCase = true) }
                ?: LanguageEntity(id = language.defaultId, name = language.name, isPremium = false)
            languages.insertMissing(listOf(entity))

            val languagePacks = SeedCatalog.packsFor(language.key)
            packs.insertMissing(languagePacks.map {
                PackEntity(id = it.id, languageId = entity.id, theme = it.theme, sortOrder = it.sortOrder, isFree = it.isFree)
            })
            SeedCatalog.renamedThemes.forEach { (packId, names) ->
                if (languagePacks.any { it.id == packId }) packs.renameTheme(packId, names.first, names.second)
            }
            // Starter sets used to be partly locked; they are all free now, including on older installs.
            packs.markFree(languagePacks.filter { it.isFree }.map { it.id })

            if (language == SeedCatalog.dholuo && phrases.countForPack("pack-dholuo-greetings") == 0) {
                // Earlier installs used random phrase IDs. Leave populated packs (and their
                // edits/history) intact instead of duplicating or replacing those phrases.
                phrases.insertMissing(SeedCatalog.legacyDholuoGreetings.map { it.toEntity("pack-dholuo-greetings") })
            }
            for (pack in languagePacks) {
                phrases.insertMissing(pack.phrases.map { it.toEntity(pack.id) })
            }
        }
        SeedAudio.clips.forEach { phrases.attachMissingAudio(it.phraseId, it.answer, it.assetPath) }
    }

    private fun SeedCatalog.Phrase.toEntity(packId: String) = PhraseEntity(
        id = id,
        packId = packId,
        prompt = prompt,
        answer = answer,
        audioAsset = SeedAudio.forPhrase(id)?.takeIf { it.answer == answer }?.assetPath,
    )
}
