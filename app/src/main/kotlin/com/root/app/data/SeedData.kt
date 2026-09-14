package com.root.app.data

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

        // Greetings is the only pack with real content — everything else is a shell
        // (theme + lock state, zero phrases) until Block 2's Kencorpus curation lands.
        // Deliberately not filled with more hand-typed phrases: SeedData's Greetings
        // set is already flagged as unreviewed by a native speaker, and duplicating
        // that risk across more packs just to make this screen look fuller isn't worth
        // it — an honestly-empty "Coming soon" pack beats more unverified content.
        val greetings = PackEntity(id = "pack-dholuo-greetings", languageId = dholuo.id, theme = "Greetings", sortOrder = 0, isFree = true)
        val family = PackEntity(id = "pack-dholuo-family", languageId = dholuo.id, theme = "Family", sortOrder = 1, isFree = true)
        val market = PackEntity(id = "pack-dholuo-market", languageId = dholuo.id, theme = "Market", sortOrder = 2, isFree = false)
        val numbers = PackEntity(id = "pack-dholuo-numbers", languageId = dholuo.id, theme = "Numbers", sortOrder = 3, isFree = false)
        val food = PackEntity(id = "pack-dholuo-food", languageId = dholuo.id, theme = "Food", sortOrder = 4, isFree = false)
        val directions = PackEntity(id = "pack-dholuo-directions", languageId = dholuo.id, theme = "Directions", sortOrder = 5, isFree = false)

        val seedPhrases = listOf(
            PhraseEntity(packId = greetings.id, prompt = "Hello", answer = "Amosi", audioAsset = null),
            PhraseEntity(packId = greetings.id, prompt = "How are you?", answer = "Idhi nade?", audioAsset = null),
            PhraseEntity(packId = greetings.id, prompt = "Thank you", answer = "Erokamano", audioAsset = null),
        )

        languages.upsertAll(listOf(dholuo))
        packs.upsertAll(listOf(greetings, family, market, numbers, food, directions))
        phrases.upsertAll(seedPhrases)
    }
}
