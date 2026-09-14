package com.lugha.app.data

/**
 * A few hand-typed phrases so Block 1 (core loop) is demoable before Block 2 (real
 * Kencorpus-sourced content) lands. Delete this once real packs exist — it exists only
 * so "does the practice loop work" doesn't block on "is the content pipeline done."
 * Placeholder Dholuo greetings only — NOT reviewed by a native speaker yet; do not ship
 * these to the demo video without the Block 2 review step.
 */
object SeedData {
    suspend fun seedIfEmpty(db: AppDatabase) {
        val languages = db.languageDao()
        val packs = db.packDao()
        val phrases = db.phraseDao()

        val dholuo = LanguageEntity(id = "lang-dholuo", name = "Dholuo", isPremium = false)
        val greetings = PackEntity(
            id = "pack-dholuo-greetings",
            languageId = dholuo.id,
            theme = "Greetings",
            sortOrder = 0,
            isFree = true,
        )
        val seedPhrases = listOf(
            PhraseEntity(packId = greetings.id, prompt = "Hello", answer = "Amosi", audioAsset = null),
            PhraseEntity(packId = greetings.id, prompt = "How are you?", answer = "Idhi nade?", audioAsset = null),
            PhraseEntity(packId = greetings.id, prompt = "Thank you", answer = "Erokamano", audioAsset = null),
        )

        languages.upsertAll(listOf(dholuo))
        packs.upsertAll(listOf(greetings))
        phrases.upsertAll(seedPhrases)
    }
}
