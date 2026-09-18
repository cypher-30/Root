package com.root.app.data

import androidx.room.withTransaction

/**
 * Dholuo development samples and a source-checked Shona starter pack.
 * Neither pack has completed native-speaker review. Shona spelling and usage
 * provenance is bundled in assets/content_sources.txt.
 * Authentic native-speaker reference audio is unavailable; null audio is intentional,
 * not a placeholder to replace with a manufactured voice.
 */
object SeedData {
    suspend fun seedIfEmpty(db: AppDatabase) = db.withTransaction {
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
            PhraseEntity(id = "phrase-dholuo-hello", packId = greetings.id, prompt = "Hello", answer = "Amosi", audioAsset = null),
            PhraseEntity(id = "phrase-dholuo-how-are-you", packId = greetings.id, prompt = "How are you?", answer = "Idhi nade?", audioAsset = null),
            PhraseEntity(id = "phrase-dholuo-thank-you", packId = greetings.id, prompt = "Thank you", answer = "Erokamano", audioAsset = null),
        )

        languages.insertMissing(listOf(dholuo))
        packs.insertMissing(listOf(greetings, family, market, numbers, food, directions))
        // Earlier installs used random phrase IDs. Leave populated packs (and their
        // edits/history) intact instead of duplicating or replacing those phrases.
        if (phrases.countForPack(greetings.id) == 0) {
            phrases.insertMissing(seedPhrases)
        }

        // Reuse a learner-created Shona language rather than listing it twice.
        val shona = languages.getAll().firstOrNull { it.name.equals("Shona", ignoreCase = true) }
            ?: LanguageEntity(id = "lang-shona", name = "Shona", isPremium = false)
        val shonaGreetings = PackEntity(
            id = "pack-shona-greetings",
            languageId = shona.id,
            theme = "Greetings",
            sortOrder = 0,
            isFree = true,
        )
        languages.insertMissing(listOf(shona))
        packs.insertMissing(listOf(shonaGreetings))
        phrases.insertMissing(listOf(
            PhraseEntity(id = "phrase-shona-greetings-01", packId = shonaGreetings.id,
                prompt = "Hello (one person)", answer = "Mhoro", audioAsset = null),
            PhraseEntity(id = "phrase-shona-greetings-02", packId = shonaGreetings.id,
                prompt = "Hello (more than one person)", answer = "Mhoroi", audioAsset = null),
            PhraseEntity(id = "phrase-shona-greetings-03", packId = shonaGreetings.id,
                prompt = "Welcome", answer = "Mauya", audioAsset = null),
            PhraseEntity(id = "phrase-shona-greetings-04", packId = shonaGreetings.id,
                prompt = "Good morning", answer = "Mangwanani", audioAsset = null),
            PhraseEntity(id = "phrase-shona-greetings-05", packId = shonaGreetings.id,
                prompt = "Good afternoon", answer = "Masikati", audioAsset = null),
            PhraseEntity(id = "phrase-shona-greetings-06", packId = shonaGreetings.id,
                prompt = "Good evening", answer = "Manheru", audioAsset = null),
            PhraseEntity(id = "phrase-shona-greetings-07", packId = shonaGreetings.id,
                prompt = "Thank you (one person)", answer = "Waita zvako", audioAsset = null),
            PhraseEntity(id = "phrase-shona-greetings-08", packId = shonaGreetings.id,
                prompt = "Thank you (more than one person)", answer = "Maita zvenyu", audioAsset = null),
        ))
    }
}
