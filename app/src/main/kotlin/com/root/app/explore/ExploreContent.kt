package com.root.app.explore

import com.root.app.data.SeedCatalog
import com.root.app.data.SeedSource

/**
 * Read-only Explore content: what each everyday situation is for, short
 * scenes told with the starter phrases, and culture notes.
 *
 * Scenes never invent target-language sentences: every spoken line points at a
 * source-checked starter phrase in [SeedCatalog] by ID, and English narration
 * frames it. Scenes only use phrases from free packs, so they never reveal the
 * text of a locked pack. Culture notes only restate what the cited public page
 * says (checked when written). None of this has had native-speaker review yet.
 */
object ExploreContent {
    /** A narration line when [phraseId] is null, otherwise a spoken starter phrase. */
    data class Line(val speaker: String?, val narration: String?, val phraseId: String?)
    data class Story(val id: String, val title: String, val setting: String, val lines: List<Line>, val source: SeedSource)
    data class Note(val id: String, val title: String, val body: String, val source: String, val url: String)
    data class Language(val situations: Map<String, String>, val stories: List<Story>, val notes: List<Note>)

    private fun narrate(text: String) = Line(null, text, null)
    private fun say(speaker: String, phraseId: String) = Line(speaker, null, phraseId)

    private val swahili = Language(
        situations = mapOf(
            "pack-swahili-greetings" to "Hello and thank you for one person or many, greetings for each time of day, please and sorry.",
            "pack-swahili-people" to "Swap names and home towns, and say when you don't understand.",
            "pack-swahili-market" to "Ask prices, order tea, ask for the bill, and turn things down politely.",
            "pack-swahili-directions" to "Left, right, straight on, and what to say when you're lost.",
            "pack-swahili-numbers" to "Count the basics, ask the time, and say see you tomorrow.",
        ),
        stories = listOf(
            Story(
                "sw-neighbours", "New neighbours", "An apartment block in Mombasa",
                listOf(
                    narrate("Juma knocks on the door of the family who just moved in. Three children answer, so he greets them all:"),
                    say("Juma", "phrase-swahili-greetings-02"),
                    narrate("Their mother comes to the door and invites him in:"),
                    say("Mother", "phrase-swahili-greetings-03"),
                    narrate("Juma has brought mandazi. The whole family thanks him:"),
                    say("Family", "phrase-swahili-greetings-08"),
                    narrate("As he leaves, he promises to come back:"),
                    say("Juma", "phrase-swahili-numbers-06"),
                ),
                SeedSource.OMNIGLOT_WIKIVOYAGE_SWAHILI,
            ),
            Story(
                "sw-lost", "Lost in the old town", "The narrow streets of Stone Town",
                listOf(
                    narrate("Amina has taken one turn too many. She stops a shopkeeper:"),
                    say("Amina", "phrase-swahili-greetings-12"),
                    say("Amina", "phrase-swahili-around-05"),
                    narrate("He points down the lane and gives directions:"),
                    say("Shopkeeper", "phrase-swahili-around-04"),
                    say("Shopkeeper", "phrase-swahili-around-02"),
                    narrate("Amina thanks him and heads off:"),
                    say("Amina", "phrase-swahili-greetings-07"),
                ),
                SeedSource.WIKIVOYAGE_SWAHILI,
            ),
        ),
        notes = listOf(
            Note(
                "sw-karibu", "Karibu, twice over",
                "Karibu welcomes someone who arrives, and it is also the usual reply when someone thanks you " +
                    "with asante: 'you're welcome'.",
                "Wikivoyage, Swahili phrasebook", "https://en.wikivoyage.org/wiki/Swahili_phrasebook",
            ),
            Note(
                "sw-time", "The clock starts at dawn",
                "Swahili time counts the hours from 6 in the morning, not from midnight. Saa mbili, literally " +
                    "'hour two', is 8 am, and saa moja asubuhi is 7 am. Add usiku for the night hours: saa mbili " +
                    "usiku is 8 pm. Saa ngapi? asks what time it is.",
                "Wikivoyage, Swahili phrasebook", "https://en.wikivoyage.org/wiki/Swahili_phrasebook",
            ),
        ),
    )

    private val shona = Language(
        situations = mapOf(
            "pack-shona-greetings" to "Greetings for each time of day, thank you for one person or many, and sorry.",
            "pack-shona-people" to "Ask a name and where someone is from, and congratulate them.",
            "pack-shona-market" to "Prices, everyday food words, and wishing someone a good meal.",
            "pack-shona-directions" to "Find the toilet, call for help, and ask someone to slow down.",
            "pack-shona-numbers" to "Count to ten and wish someone a good day or a good night.",
        ),
        stories = listOf(
            Story(
                "sn-bus", "The bus to Bulawayo", "A bus station in Harare",
                listOf(
                    narrate("Rudo arrives early and greets the conductor:"),
                    say("Rudo", "phrase-shona-greetings-04"),
                    narrate("He answers too fast over the noise of the engines, so she asks:"),
                    say("Rudo", "phrase-shona-around-05"),
                    narrate("This time she catches the bay number, and thanks him:"),
                    say("Rudo", "phrase-shona-greetings-07"),
                    narrate("Her aunt, seeing her off, calls after her:"),
                    say("Aunt", "phrase-shona-around-04"),
                ),
                SeedSource.OMNIGLOT_SHONA,
            ),
            Story(
                "sn-news", "Good news", "A family gathering",
                listOf(
                    narrate("Tendai walks into a room full of relatives and greets everyone at once:"),
                    say("Tendai", "phrase-shona-greetings-02"),
                    narrate("His cousin has just finished school. Tendai tells him:"),
                    say("Tendai", "phrase-shona-people-07"),
                    narrate("He meets his cousin's friend for the first time:"),
                    say("Tendai", "phrase-shona-people-05"),
                    narrate("Late at night, he wishes his little sister good night:"),
                    say("Tendai", "phrase-shona-numbers-06"),
                ),
                SeedSource.OMNIGLOT_SHONA,
            ),
        ),
        notes = listOf(
            Note(
                "sn-plural", "One person or many",
                "Many Shona greetings and thanks change depending on whether you speak to one person or to " +
                    "more than one: Mhoro and Mhoroi, Waita zvako and Maita zvenyu. Phrase lists mark these " +
                    "as singular and plural.",
                "Omniglot, Useful Shona phrases", "https://www.omniglot.com/language/phrases/shona.php",
            ),
            Note(
                "sn-time", "Greetings follow the clock",
                "Shona has a different greeting for each part of the day: mangwanani in the morning, masikati " +
                    "in the afternoon, and manheru in the evening.",
                "Omniglot, Useful Shona phrases", "https://www.omniglot.com/language/phrases/shona.php",
            ),
        ),
    )

    private val dholuo = Language(
        situations = mapOf(
            "pack-dholuo-greetings" to "Hello, how are you, and good wishes for the morning, evening, and night.",
            "pack-dholuo-family" to "Grandparents, children, sons, daughters, and more of the family.",
            "pack-dholuo-market" to "Say you're hungry and that you can pay, and name what's for sale.",
            "pack-dholuo-numbers" to "Count from one to five, and ten.",
            "pack-dholuo-food" to "Fish, bananas, cassava, and more everyday food.",
            "pack-dholuo-directions" to "Swap names and home towns, and call for help.",
        ),
        stories = listOf(
            Story(
                "luo-visit", "A morning visit", "A neighbour's gate near Kisumu",
                listOf(
                    narrate("Otieno stops at his neighbour's gate on his way to the lake:"),
                    say("Otieno", "phrase-dholuo-greetings-05"),
                    say("Otieno", "phrase-dholuo-how-are-you"),
                    say("Neighbour", "phrase-dholuo-greetings-04"),
                    narrate("She hands him a bag of oranges from her tree. He thanks her:"),
                    say("Otieno", "phrase-dholuo-thank-you"),
                    narrate("He promises to come back soon:"),
                    say("Otieno", "phrase-dholuo-greetings-09"),
                ),
                SeedSource.WIKIVOYAGE_LUO,
            ),
            Story(
                "luo-evening", "Evening with the grandparents", "A home near Kisumu",
                listOf(
                    narrate("Akinyi arrives as the sun goes down and greets her grandfather, her kwaro:"),
                    say("Akinyi", "phrase-dholuo-greetings-06"),
                    narrate("Her grandmother, her dayo, asks how she is:"),
                    say("Dayo", "phrase-dholuo-how-are-you"),
                    say("Akinyi", "phrase-dholuo-greetings-04"),
                    narrate("After supper, everyone says good night:"),
                    say("Dayo", "phrase-dholuo-greetings-08"),
                ),
                SeedSource.WIKIVOYAGE_LUO,
            ),
        ),
        notes = listOf(
            Note(
                "luo-colours", "Colours that start with Ra-",
                "Every colour word in the phrasebook begins with ra-: rakwar is red, rateng' black, rachar white, ralum " +
                    "green, and rabuor brown. You'll hear the same start in food words like rabolo, banana, " +
                    "and rabuon, potato.",
                "Wikivoyage, Luo phrasebook", "https://en.wikivoyage.org/wiki/Luo_phrasebook",
            ),
            Note(
                "luo-family", "The family, joot",
                "Wuon is father and min is mother. Kwaro and dayo are grandfather and grandmother, wuod and nyar " +
                    "are son and daughter, and nyathi is a child.",
                "Wikivoyage, Luo phrasebook", "https://en.wikivoyage.org/wiki/Luo_phrasebook",
            ),
        ),
    )

    private val amharic = Language(
        situations = mapOf(
            "pack-amharic-greetings" to "Formal and everyday greetings, thanks, and asking whether someone speaks Amharic.",
            "pack-amharic-directions" to "Where is it? Near, far, left, right, and straight ahead.",
            "pack-amharic-people" to "Ask a name the right way for a man or a woman, and say you don't understand.",
            "pack-amharic-market" to "Coffee, bread, stew, prices, and saying you don't eat meat.",
            "pack-amharic-numbers" to "Count the basics and wish someone a good day or night.",
        ),
        stories = listOf(
            Story(
                "am-way", "Finding the way", "A street corner in Addis Ababa",
                listOf(
                    narrate("Hanna is looking for the post office. She stops a passer-by:"),
                    say("Hanna", "phrase-amharic-directions-01"),
                    narrate("She names the post office and asks:"),
                    say("Hanna", "phrase-amharic-directions-02"),
                    narrate("He smiles and points:"),
                    say("Passer-by", "phrase-amharic-directions-08"),
                    say("Passer-by", "phrase-amharic-directions-05"),
                    narrate("Hanna thanks him:"),
                    say("Hanna", "phrase-amharic-greetings-10"),
                ),
                SeedSource.FSI_OMNIGLOT_AMHARIC,
            ),
            Story(
                "am-class", "A new classmate", "The first day of an evening class",
                listOf(
                    narrate("Dawit sits down next to someone he hasn't met and greets her:"),
                    say("Dawit", "phrase-amharic-greetings-09"),
                    narrate("He asks her name, using the form for a woman:"),
                    say("Dawit", "phrase-amharic-people-02"),
                    narrate("She tells him her name is Meron. He answers:"),
                    say("Dawit", "phrase-amharic-people-04"),
                    narrate("After class, they say goodbye:"),
                    say("Meron", "phrase-amharic-greetings-11"),
                ),
                SeedSource.OMNIGLOT_WIKIVOYAGE_AMHARIC,
            ),
        ),
        notes = listOf(
            Note(
                "am-time", "Ethiopian time",
                "In Ethiopia the two 12-hour cycles are offset by six hours: they begin at dawn and dusk, not " +
                    "at midnight and noon. Dawn, 6 am, is twelve o'clock on the Ethiopian clock, and many " +
                    "long-distance buses leave then. Check which clock someone means when you arrange a time.",
                "Wikivoyage, Ethiopia", "https://en.wikivoyage.org/wiki/Ethiopia",
            ),
            Note(
                "am-buna", "The coffee ceremony",
                "In a coffee ceremony the beans are roasted, finely ground, and brewed in a jebena, a clay pot " +
                    "with a round base, a neck, and a spout. The same grounds are brewed three times, giving " +
                    "three rounds of coffee.",
                "Wikipedia, Coffee ceremony of Ethiopia and Eritrea",
                "https://en.wikipedia.org/wiki/Coffee_ceremony_of_Ethiopia_and_Eritrea",
            ),
        ),
    )

    private val byLanguageKey = mapOf(
        SeedCatalog.swahili.key to swahili,
        SeedCatalog.shona.key to shona,
        SeedCatalog.dholuo.key to dholuo,
        SeedCatalog.amharic.key to amharic,
    )

    /** Content for a language by its display name (Swahili, Shona, …). */
    fun forLanguage(name: String?): Language? = name?.trim()?.lowercase()?.let { byLanguageKey[it] }

    val byKey: Map<String, Language> get() = byLanguageKey

    private val phrasesById: Map<String, SeedCatalog.Phrase> by lazy {
        (SeedCatalog.legacyDholuoGreetings + SeedCatalog.packs.flatMap { it.phrases }).associateBy { it.id }
    }

    /** The starter phrase a scene line speaks, or null for narration. */
    fun phrase(line: Line): SeedCatalog.Phrase? = line.phraseId?.let { phrasesById[it] }
}
