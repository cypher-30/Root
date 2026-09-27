package com.root.app.data

import java.text.Normalizer

/**
 * What a learner has bought. Premium is sold per language (one entitlement per
 * language, `premium_<key>`), or as one bundle that covers every language
 * (the original `premium` entitlement, so earlier bundle purchases keep working).
 * Everything else in [ContentAccess] is unchanged: this only answers "is premium
 * content in this language open?".
 */
data class PremiumAccess(
    val allLanguages: Boolean = false,
    /** Keys from [languageKey], e.g. "dholuo". */
    val languageKeys: Set<String> = emptySet(),
) {
    fun covers(language: LanguageEntity): Boolean = covers(language.name)

    fun covers(languageName: String?): Boolean =
        allLanguages || (languageName != null && languageKey(languageName) in languageKeys)

    val ownsAnything: Boolean get() = allLanguages || languageKeys.isNotEmpty()

    companion object {
        val NONE = PremiumAccess()
        val ALL = PremiumAccess(allLanguages = true)

        const val ALL_LANGUAGES_ENTITLEMENT = "premium"
        private const val LANGUAGE_ENTITLEMENT_PREFIX = "premium_"

        /** Keyed by name, not row ID: a learner-created "Shona" and the seeded
         *  Shona share one row, and downloaded packs may use their own IDs. */
        fun languageKey(languageName: String): String =
            Normalizer.normalize(languageName.trim().lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')

        fun entitlementFor(languageName: String): String = LANGUAGE_ENTITLEMENT_PREFIX + languageKey(languageName)

        fun fromEntitlements(active: Collection<String>): PremiumAccess = PremiumAccess(
            allLanguages = ALL_LANGUAGES_ENTITLEMENT in active,
            languageKeys = active.filter(::isRootEntitlement).filter { it != ALL_LANGUAGES_ENTITLEMENT }
                .map { it.removePrefix(LANGUAGE_ENTITLEMENT_PREFIX) }
                .toSet(),
        )

        fun isRootEntitlement(id: String): Boolean = id == ALL_LANGUAGES_ENTITLEMENT ||
            (id.startsWith(LANGUAGE_ENTITLEMENT_PREFIX) && id.length > LANGUAGE_ENTITLEMENT_PREFIX.length)
    }
}
