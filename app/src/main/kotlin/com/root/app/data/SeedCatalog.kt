package com.root.app.data

/**
 * The bundled starter phrase catalog, as plain data so it can be checked in a
 * JVM test (counts, unique IDs, sources) without Room. [SeedData] installs it.
 *
 * Every phrase is source-checked against a public reference listed in
 * [SeedSource] and `assets/content_sources.txt`. None has had native-speaker
 * review yet. A few phrases have real Lingua Libre recordings (see [SeedAudio]);
 * the rest have no reference audio rather than a synthetic or unrelated one.
 * Phrase and pack IDs are stable forever — learner history keys on them.
 * Every starter set is free. Premium is reserved for native-reviewed,
 * recorded content (see docs/PREMIUM.md), which these samples are not.
 */
object SeedCatalog {
    data class Language(val key: String, val defaultId: String, val name: String)
    data class Phrase(val id: String, val prompt: String, val answer: String)
    data class Pack(
        val id: String,
        val languageKey: String,
        val theme: String,
        val sortOrder: Int,
        val isFree: Boolean,
        val source: SeedSource,
        val phrases: List<Phrase>,
    )

    val dholuo = Language("dholuo", "lang-dholuo", "Dholuo")
    val shona = Language("shona", "lang-shona", "Shona")
    val swahili = Language("swahili", "lang-swahili", "Swahili")
    val amharic = Language("amharic", "lang-amharic", "Amharic")
    val languages = listOf(dholuo, shona, swahili, amharic)

    /** The original three Dholuo phrases. Older installs stored them under
     *  random IDs, so they are only inserted into an empty Greetings pack. */
    val legacyDholuoGreetings = listOf(
        Phrase("phrase-dholuo-hello", "Hello", "Amosi"),
        Phrase("phrase-dholuo-how-are-you", "How are you?", "Idhi nade?"),
        Phrase("phrase-dholuo-thank-you", "Thank you", "Erokamano"),
    )

    /** Former theme names that [SeedData] renames in place (same pack ID). */
    val renamedThemes = mapOf("pack-dholuo-directions" to ("Directions" to "Meeting people"))

    private fun p(id: String, prompt: String, answer: String) = Phrase(id, prompt, answer)

    val packs: List<Pack> = listOf(
        // ---- Dholuo ----
        Pack("pack-dholuo-greetings", "dholuo", "Greetings", 0, true, SeedSource.WIKIVOYAGE_LUO, listOf(
            p("phrase-dholuo-greetings-04", "I'm fine (reply to 'How are you?')", "Adhi maber"),
            p("phrase-dholuo-greetings-05", "Good morning", "Oyawore"),
            p("phrase-dholuo-greetings-06", "Good evening", "Oimore"),
            p("phrase-dholuo-greetings-07", "Goodbye", "Oriti"),
            p("phrase-dholuo-greetings-08", "Sleep well", "Nindi maber"),
            p("phrase-dholuo-greetings-09", "See you soon", "Wanenre machiegni"),
        )),
        Pack("pack-dholuo-family", "dholuo", "Family", 1, true, SeedSource.WIKIVOYAGE_LUO, listOf(
            p("phrase-dholuo-family-01", "Child", "Nyathi"),
            p("phrase-dholuo-family-02", "Grandfather", "Kwaro"),
            p("phrase-dholuo-family-03", "Grandmother", "Dayo"),
            p("phrase-dholuo-family-04", "Son (son of …)", "Wuod"),
            p("phrase-dholuo-family-05", "Daughter (daughter of …)", "Nyar"),
            p("phrase-dholuo-family-06", "Sister", "Nyamin"),
            p("phrase-dholuo-family-07", "Uncle", "Ner"),
        )),
        Pack("pack-dholuo-market", "dholuo", "Market", 2, true, SeedSource.WIKIVOYAGE_LUO, listOf(
            p("phrase-dholuo-market-01", "I want to eat", "Adwaro chiemo"),
            p("phrase-dholuo-market-02", "I have money", "An gi pesa"),
            p("phrase-dholuo-market-03", "Maize", "Bando"),
            p("phrase-dholuo-market-04", "Vegetables", "Alot"),
        )),
        Pack("pack-dholuo-numbers", "dholuo", "Numbers", 3, true, SeedSource.WIKIVOYAGE_LUO, listOf(
            p("phrase-dholuo-numbers-01", "One", "Achiel"),
            p("phrase-dholuo-numbers-02", "Two", "Ariyo"),
            p("phrase-dholuo-numbers-03", "Three", "Adek"),
            p("phrase-dholuo-numbers-04", "Four", "Ang'wen"),
            p("phrase-dholuo-numbers-05", "Five", "Abich"),
            p("phrase-dholuo-numbers-06", "Ten", "Apar"),
        )),
        Pack("pack-dholuo-food", "dholuo", "Food", 4, true, SeedSource.WIKIVOYAGE_LUO, listOf(
            p("phrase-dholuo-food-01", "Fish", "Rech"),
            p("phrase-dholuo-food-02", "Bread", "Makati"),
            p("phrase-dholuo-food-03", "Banana", "Rabolo"),
            p("phrase-dholuo-food-04", "Cassava", "Muogo"),
            p("phrase-dholuo-food-05", "Egg", "Tong'"),
            p("phrase-dholuo-food-06", "Potato", "Rabuon"),
        )),
        Pack("pack-dholuo-directions", "dholuo", "Meeting people", 5, true, SeedSource.WIKIVOYAGE_LUO, listOf(
            p("phrase-dholuo-people-01", "What's your name?", "Nyingi ng'a?"),
            p("phrase-dholuo-people-02", "My name is …", "Nyinga …"),
            p("phrase-dholuo-people-03", "Where are you from?", "To ia kanye?"),
            p("phrase-dholuo-people-04", "I'm from …", "Aa ki …"),
            p("phrase-dholuo-people-05", "Nice to meet you", "Amor kuom rado kodi"),
            p("phrase-dholuo-people-06", "I don't know", "Ok ang'eyo"),
            p("phrase-dholuo-people-07", "Help!", "Konya!"),
        )),

        // ---- Shona ----
        Pack("pack-shona-greetings", "shona", "Greetings", 0, true, SeedSource.OMNIGLOT_SHONA, listOf(
            p("phrase-shona-greetings-01", "Hello (one person)", "Mhoro"),
            p("phrase-shona-greetings-02", "Hello (more than one person)", "Mhoroi"),
            p("phrase-shona-greetings-03", "Welcome", "Mauya"),
            p("phrase-shona-greetings-04", "Good morning", "Mangwanani"),
            p("phrase-shona-greetings-05", "Good afternoon", "Masikati"),
            p("phrase-shona-greetings-06", "Good evening", "Manheru"),
            p("phrase-shona-greetings-07", "Thank you (one person)", "Waita zvako"),
            p("phrase-shona-greetings-08", "Thank you (more than one person)", "Maita zvenyu"),
            p("phrase-shona-greetings-09", "How are you? (one person)", "Wakadini zvako?"),
            p("phrase-shona-greetings-10", "I'm fine (reply to 'How are you?')", "Ndiripo"),
            p("phrase-shona-greetings-11", "Goodbye (one person staying)", "Sara zvakanaka"),
            p("phrase-shona-greetings-12", "Sorry", "Ndineurombo"),
        )),
        Pack("pack-shona-people", "shona", "Meeting people", 1, true, SeedSource.OMNIGLOT_SHONA, listOf(
            p("phrase-shona-people-01", "What's your name? (one person)", "Unonzani?"),
            p("phrase-shona-people-02", "My name is …", "Ndinonzi …"),
            p("phrase-shona-people-03", "Where are you from? (one person)", "Unobva kupi?"),
            p("phrase-shona-people-04", "I'm from …", "Ndinobva ku…"),
            p("phrase-shona-people-05", "Pleased to meet you (one person)", "Ndafara kukuziva"),
            p("phrase-shona-people-06", "Excuse me (one person)", "Pamusoro"),
            p("phrase-shona-people-07", "Congratulations!", "Makorokoto!"),
        )),
        Pack("pack-shona-market", "shona", "Food & market", 2, true, SeedSource.OMNIGLOT_WIKTIONARY_SHONA, listOf(
            p("phrase-shona-market-01", "How much is this?", "Chinoita marii?"),
            p("phrase-shona-market-02", "Enjoy your meal", "Mudye kunaka"),
            p("phrase-shona-market-03", "I want …", "Ndinoda …"),
            p("phrase-shona-market-04", "Stiff maize porridge (staple food)", "Sadza"),
            p("phrase-shona-market-05", "Meat", "Nyama"),
            p("phrase-shona-market-06", "Leafy vegetables / relish", "Muriwo"),
            p("phrase-shona-market-07", "Water", "Mvura"),
            p("phrase-shona-market-08", "Bread", "Chingwa"),
        )),
        Pack("pack-shona-directions", "shona", "Getting around", 3, true, SeedSource.OMNIGLOT_SHONA, listOf(
            p("phrase-shona-around-01", "Where's the toilet?", "Chimbuzi chiripi?"),
            p("phrase-shona-around-02", "Help!", "Ndibatsireiwo!"),
            p("phrase-shona-around-03", "Stop!", "Mira!"),
            p("phrase-shona-around-04", "Have a good journey (one person)", "Ufambe zvakanaka"),
            p("phrase-shona-around-05", "Please say that again", "Ndinokumbirawo kuti muzvitaure futi"),
            p("phrase-shona-around-06", "Please speak more slowly", "Ndinokumbirawo kuti musakurumidze kutaura"),
        )),
        Pack("pack-shona-numbers", "shona", "Numbers & time", 4, true, SeedSource.OMNIGLOT_SHONA_NUMBERS, listOf(
            p("phrase-shona-numbers-01", "One", "Motsi"),
            p("phrase-shona-numbers-02", "Two", "Piri"),
            p("phrase-shona-numbers-03", "Three", "Tatu"),
            p("phrase-shona-numbers-04", "Five", "Shanu"),
            p("phrase-shona-numbers-05", "Ten", "Gumi"),
            p("phrase-shona-numbers-06", "Good night (one person)", "Urare zvakanaka"),
            p("phrase-shona-numbers-07", "Have a nice day (one person)", "Uve nezuva rakanaka"),
        )),

        // ---- Swahili ----
        Pack("pack-swahili-greetings", "swahili", "Greetings", 0, true, SeedSource.OMNIGLOT_WIKIVOYAGE_SWAHILI, listOf(
            p("phrase-swahili-greetings-01", "Hello (one person)", "Hujambo"),
            p("phrase-swahili-greetings-02", "Hello (more than one person)", "Hamjambo"),
            p("phrase-swahili-greetings-03", "Welcome", "Karibu"),
            p("phrase-swahili-greetings-04", "Good morning", "Habari ya asubuhi"),
            p("phrase-swahili-greetings-05", "Good afternoon", "Habari ya mchana"),
            p("phrase-swahili-greetings-06", "Good evening", "Habari ya jioni"),
            p("phrase-swahili-greetings-07", "Thank you (one person)", "Asante"),
            p("phrase-swahili-greetings-08", "Thank you (more than one person)", "Asanteni"),
            p("phrase-swahili-greetings-09", "Goodbye", "Kwaheri"),
            p("phrase-swahili-greetings-10", "Fine, thank you", "Nzuri, asante"),
            p("phrase-swahili-greetings-11", "Please", "Tafadhali"),
            p("phrase-swahili-greetings-12", "Excuse me / Sorry", "Samahani"),
        )),
        Pack("pack-swahili-people", "swahili", "Meeting people", 1, true, SeedSource.OMNIGLOT_WIKIVOYAGE_SWAHILI, listOf(
            p("phrase-swahili-people-01", "What is your name?", "Jina lako ni nani?"),
            p("phrase-swahili-people-02", "My name is …", "Jina langu ni …"),
            p("phrase-swahili-people-03", "Where are you from?", "Unatoka wapi?"),
            p("phrase-swahili-people-04", "I'm from …", "Ninatoka …"),
            p("phrase-swahili-people-05", "Pleased to meet you", "Nimefurahi kukutana nawe"),
            p("phrase-swahili-people-06", "I'm learning Swahili", "Ninajifunza Kiswahili"),
            p("phrase-swahili-people-07", "I don't understand", "Sielewi"),
        )),
        Pack("pack-swahili-market", "swahili", "Food & market", 2, true, SeedSource.OMNIGLOT_WIKIVOYAGE_SWAHILI, listOf(
            p("phrase-swahili-market-01", "How much is this?", "Hii ni bei gani?"),
            p("phrase-swahili-market-02", "I want …", "Ninataka …"),
            p("phrase-swahili-market-03", "I would like … (polite request)", "Naomba …"),
            p("phrase-swahili-market-04", "Tea with milk", "Chai ya maziwa"),
            p("phrase-swahili-market-05", "Water", "Maji"),
            p("phrase-swahili-market-06", "The food is delicious", "Chakula ni kitamu"),
            p("phrase-swahili-market-07", "The bill, please", "Naomba bili, tafadhali"),
            p("phrase-swahili-market-08", "No, thank you", "Hapana, asante"),
        )),
        Pack("pack-swahili-directions", "swahili", "Getting around", 3, true, SeedSource.WIKIVOYAGE_SWAHILI, listOf(
            p("phrase-swahili-around-01", "Where is the toilet?", "Choo kiko wapi?"),
            p("phrase-swahili-around-02", "Turn left", "Pinda kushoto"),
            p("phrase-swahili-around-03", "Turn right", "Pinda kulia"),
            p("phrase-swahili-around-04", "Straight ahead", "Moja kwa moja"),
            p("phrase-swahili-around-05", "I'm lost", "Nimepotea"),
            p("phrase-swahili-around-06", "Help!", "Msaada!"),
            p("phrase-swahili-around-07", "Take me there, please", "Nipeleke huko, tafadhali"),
        )),
        Pack("pack-swahili-numbers", "swahili", "Numbers & time", 4, true, SeedSource.WIKIVOYAGE_SWAHILI, listOf(
            p("phrase-swahili-numbers-01", "One", "Moja"),
            p("phrase-swahili-numbers-02", "Two", "Mbili"),
            p("phrase-swahili-numbers-03", "Three", "Tatu"),
            p("phrase-swahili-numbers-04", "Ten", "Kumi"),
            p("phrase-swahili-numbers-05", "What time is it?", "Saa ngapi?"),
            p("phrase-swahili-numbers-06", "See you tomorrow", "Tutaonana kesho"),
        )),

        // ---- Amharic (FSI transliteration for the original sets; Ge'ez script added for new ones) ----
        Pack("pack-amharic-greetings", "amharic", "Greetings", 0, true, SeedSource.FSI_OMNIGLOT_AMHARIC, listOf(
            p("phrase-amharic-greetings-01", "Hello / Goodbye (general greeting)", "Tena yisTilliñ."),
            p("phrase-amharic-greetings-02", "Good morning, how are you?", "Tena yisTilliñ, indemin adderu."),
            p("phrase-amharic-greetings-03", "Very well, thank you", "Dehna, igziyabher yimmesgen."),
            p("phrase-amharic-greetings-04", "Do you know Amharic?", "Amariñña yawKallu?"),
            p("phrase-amharic-greetings-05", "Yes, I know", "Awo, awKallehu."),
            p("phrase-amharic-greetings-06", "No, I don't know", "Yellem, alawKim."),
            p("phrase-amharic-greetings-07", "I know a little", "Tinniš awKallehu."),
            p("phrase-amharic-greetings-08", "What did you say?", "Minalu?"),
            p("phrase-amharic-greetings-09", "Hello (informal)", "Selam (ሰላም)"),
            p("phrase-amharic-greetings-10", "Thank you", "Amesegnalehu (አመሰግናለሁ)"),
            p("phrase-amharic-greetings-11", "Bye (informal)", "Chaw (ቻው)"),
            p("phrase-amharic-greetings-12", "Excuse me / Sorry", "Yiqirta (ይቅርታ)"),
        )),
        Pack("pack-amharic-directions", "amharic", "Directions", 1, true, SeedSource.FSI_AMHARIC, listOf(
            p("phrase-amharic-directions-01", "Please / Excuse me", "Ibákkiwo."),
            p("phrase-amharic-directions-02", "Where?", "Yet?"),
            p("phrase-amharic-directions-03", "It's in front of you", "Fitlefit new."),
            p("phrase-amharic-directions-04", "It's far", "RuK new."),
            p("phrase-amharic-directions-05", "It's on your right", "BesteKeññiwo new."),
            p("phrase-amharic-directions-06", "It's on your left", "Bestegrawo new."),
            p("phrase-amharic-directions-07", "Go straight ahead and turn", "Wedefit yihidunná, wedegrá yizuru."),
            p("phrase-amharic-directions-08", "It's nearby", "Kirb new."),
        )),
        Pack("pack-amharic-people", "amharic", "Meeting people", 2, true, SeedSource.OMNIGLOT_WIKIVOYAGE_AMHARIC, listOf(
            p("phrase-amharic-people-01", "What is your name? (to a man)", "Simih man new? (ስምህ ማን ነው?)"),
            p("phrase-amharic-people-02", "What is your name? (to a woman)", "Simish man new? (ስምሽ ማን ነው?)"),
            p("phrase-amharic-people-03", "My name is …", "Sime … new (ስሜ … ነው)"),
            p("phrase-amharic-people-04", "Pleased to meet you", "Siletewawekin des bilognal (ስለተዋወቅን ደስ ብሎኛል)"),
            p("phrase-amharic-people-05", "I don't understand", "Algebagnim (አልገባኝም)"),
            p("phrase-amharic-people-06", "Yes", "Awo (አዎ)"),
        )),
        Pack("pack-amharic-market", "amharic", "Food & market", 3, true, SeedSource.OMNIGLOT_WIKIVOYAGE_AMHARIC, listOf(
            p("phrase-amharic-market-01", "How much is it?", "Sint new? (ስንት ነው?)"),
            p("phrase-amharic-market-02", "Coffee with milk", "Buna be wetet (ቡና በወተት)"),
            p("phrase-amharic-market-03", "Bread", "Dabo (ዳቦ)"),
            p("phrase-amharic-market-04", "Chicken", "Doro (ዶሮ)"),
            p("phrase-amharic-market-05", "Stew", "Wet (ወጥ)"),
            p("phrase-amharic-market-06", "I don't eat meat", "Siga albelam (ስጋ አልበላም)"),
            p("phrase-amharic-market-07", "Enjoy your meal", "Melkam migib (መልካም ምግብ)"),
        )),
        Pack("pack-amharic-numbers", "amharic", "Numbers & time", 4, true, SeedSource.OMNIGLOT_WIKIVOYAGE_AMHARIC, listOf(
            p("phrase-amharic-numbers-01", "One", "And (አንድ)"),
            p("phrase-amharic-numbers-02", "Two", "Hulet (ሁለት)"),
            p("phrase-amharic-numbers-03", "Three", "Sost (ሶስት)"),
            p("phrase-amharic-numbers-04", "Five", "Amist (አምስት)"),
            p("phrase-amharic-numbers-05", "Ten", "Asir (አስር)"),
            p("phrase-amharic-numbers-06", "Have a nice day", "Melkam qen (መልካም ቀን)"),
            p("phrase-amharic-numbers-07", "Good night (to a man)", "Dehna eder (ደህና እደር)"),
        )),
    )

    fun packsFor(languageKey: String): List<Pack> = packs.filter { it.languageKey == languageKey }

    /** Starter packs are source-checked but not native-reviewed, so they are
     *  never counted as something the premium unlock can be sold for. */
    val packIds: Set<String> = packs.mapTo(mutableSetOf()) { it.id }

    /** All phrases a fresh install shows for [languageKey], legacy Dholuo included. */
    fun phrasesFor(languageKey: String): List<Phrase> =
        (if (languageKey == dholuo.key) legacyDholuoGreetings else emptyList()) +
            packsFor(languageKey).flatMap { it.phrases }
}

/** Public references each starter pack was checked against. Source-checked
 *  is not native-speaker approval; see assets/content_sources.txt. */
enum class SeedSource(val credit: String, val url: String, val license: String) {
    WIKIVOYAGE_LUO(
        "Wikivoyage contributors, \"Luo phrasebook\" (Dholuo). Spelling normalized to the dh spelling used elsewhere in Root.",
        "https://en.wikivoyage.org/wiki/Luo_phrasebook", "CC BY-SA 4.0",
    ),
    OMNIGLOT_SHONA(
        "Emma Thembani and Ernest Mdende via Omniglot, \"Useful Shona phrases\".",
        "https://www.omniglot.com/language/phrases/shona.php", "Checked for accuracy; not license-cleared",
    ),
    OMNIGLOT_WIKTIONARY_SHONA(
        "Omniglot, \"Useful Shona phrases\", with single food words checked against Wiktionary.",
        "https://www.omniglot.com/language/phrases/shona.php", "Checked for accuracy; Wiktionary CC BY-SA 4.0",
    ),
    OMNIGLOT_SHONA_NUMBERS(
        "Omniglot, \"Numbers in Shona\" and \"Useful Shona phrases\".",
        "https://www.omniglot.com/language/numbers/shona.htm", "Checked for accuracy; not license-cleared",
    ),
    OMNIGLOT_WIKIVOYAGE_SWAHILI(
        "Omniglot, \"Useful Swahili phrases\", and Wikivoyage contributors, \"Swahili phrasebook\".",
        "https://en.wikivoyage.org/wiki/Swahili_phrasebook", "Wikivoyage CC BY-SA 4.0",
    ),
    WIKIVOYAGE_SWAHILI(
        "Wikivoyage contributors, \"Swahili phrasebook\".",
        "https://en.wikivoyage.org/wiki/Swahili_phrasebook", "CC BY-SA 4.0",
    ),
    FSI_AMHARIC(
        "FSI Amharic Basic Course (Foreign Service Institute, 1964), a U.S. government work.",
        "https://www.fsi-language-courses.org/Content.php", "Public domain (17 U.S.C. §105)",
    ),
    FSI_OMNIGLOT_AMHARIC(
        "FSI Amharic Basic Course (public domain), with informal greetings checked against Omniglot and Wikivoyage.",
        "https://www.omniglot.com/language/phrases/amharic.php", "Public domain / Wikivoyage CC BY-SA 4.0",
    ),
    OMNIGLOT_WIKIVOYAGE_AMHARIC(
        "Omniglot, \"Useful Amharic phrases\", and Wikivoyage contributors, \"Amharic phrasebook\".",
        "https://en.wikivoyage.org/wiki/Amharic_phrasebook", "Wikivoyage CC BY-SA 4.0",
    ),
}
