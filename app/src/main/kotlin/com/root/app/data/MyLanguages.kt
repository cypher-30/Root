package com.root.app.data

/**
 * "Your languages", the way language apps usually keep them: the languages you
 * chose are listed in Profile, and the rest wait under Add a language.
 */
object MyLanguages {
    data class Split(val mine: List<LanguageEntity>, val others: List<LanguageEntity>)

    /** [chosen] is null before the learner's first choice; the active language always counts as theirs. */
    fun split(all: List<LanguageEntity>, chosen: Set<String>?, activeId: String?): Split {
        val ids = (chosen ?: emptySet()) + setOfNotNull(activeId)
        val (mine, others) = all.partition { it.id in ids }
        return Split(mine, others)
    }

    /** Where each bundled language is spoken, shown when choosing one. */
    fun about(name: String): String = when (name.trim().lowercase()) {
        "dholuo" -> "The language of the Luo people around Lake Victoria, in Kenya and Tanzania."
        "shona" -> "Zimbabwe's most widely spoken language, also spoken in Mozambique."
        "swahili" -> "Spoken across East Africa, from Kenya and Tanzania to Uganda and the Congo."
        "amharic" -> "Ethiopia's working language, written in the Ge'ez script."
        else -> "A language you added, with your own words."
    }
}
