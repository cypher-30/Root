package com.root.app.billing

import com.root.app.data.PremiumAccess
import com.root.app.data.SeedCatalog

/**
 * What Root Premium is, as plain data shared by the paywall and its tests.
 * The definition and pricing rationale live in docs/PREMIUM.md. The price you
 * are charged always comes from the store (RevenueCat/Play); the planned prices
 * are only shown, labelled as planned, while the store has no plan to show.
 *
 * Premium opens each language's premium phrase sets ([SeedCatalog.Pack.isFree]
 * is false). It never gates practice, how often you practise, or the starter sets.
 */
object PremiumOffer {
    data class PremiumSet(val languageName: String, val theme: String, val phraseCount: Int)

    /** The premium sets in the app today, for one language or (with null) every language. */
    fun premiumSets(languageName: String? = null): List<PremiumSet> {
        val key = languageName?.let(PremiumAccess::languageKey)
        return SeedCatalog.languages
            .filter { key == null || PremiumAccess.languageKey(it.name) == key }
            .flatMap { language ->
                SeedCatalog.packsFor(language.key).filterNot { it.isFree }
                    .map { PremiumSet(language.name, it.theme, it.phrases.size) }
            }
    }

    /** The paywall's "What Premium includes" rows, naming the real sets for [languageName]. */
    fun included(languageName: String?): List<Pair<String, String>> {
        val sets = premiumSets(languageName)
        val setsRow = when {
            sets.isEmpty() ->
                "Premium sets" to "${languageName ?: "This language"} has no premium sets yet."
            languageName != null && sets.all { it.languageName.equals(languageName, ignoreCase = true) } ->
                "${sets.first().languageName} premium sets" to
                    sets.joinToString(", ") { "${it.theme} (${it.phraseCount} phrases)" }.replaceLast(", ", " and ") + "."
            else ->
                "Premium sets in every language" to
                    sets.groupBy { it.languageName }.entries.joinToString(" ") { (name, own) ->
                        "$name: " + own.joinToString(", ") { it.theme }.replaceLast(", ", " and ") + "."
                    }
        }
        return listOf(
            setsRow,
            "Practise them like any set" to
                "They join your daily practice and Explore, you can save them to your notebook, and they work offline.",
            "New sets included" to
                "Premium sets added later for a language you own are yours at no extra cost.",
            "Pay once, for one language or all" to
                "One payment for the language you're learning, or one for every language. Not a subscription.",
        )
    }

    /** Keep in step with the price table in docs/PREMIUM.md and the store products. */
    const val plannedLanguagePrice = "US$4.99"
    const val plannedAllLanguagesPrice = "US$9.99"

    const val plannedPriceNote =
        "Planned prices, each a one-time unlock and not a subscription. The store shows the exact " +
            "amount in your currency before you pay."

    /** Title for a plan card. [languageName] is the language the paywall was opened for. */
    fun planTitle(plan: PremiumPlan, languageName: String?): String = when (plan) {
        PremiumPlan.AllLanguages -> "All languages"
        is PremiumPlan.Language -> "${languageName ?: plan.key.replaceFirstChar { it.uppercase() }} only"
    }

    fun planDetail(plan: PremiumPlan, languageName: String?): String = when (plan) {
        PremiumPlan.AllLanguages -> "Every premium set, in every language, including ones added later."
        is PremiumPlan.Language -> "Every premium set for ${languageName ?: "this language"}, including ones added later."
    }

    const val alwaysFree =
        "Every starter set in every language, daily practice, Learn, Explore's stories and culture notes, " +
            "your notebook, and your own words. Offline, with no account."

    private fun String.replaceLast(old: String, new: String): String {
        val index = lastIndexOf(old)
        return if (index < 0) this else substring(0, index) + new + substring(index + old.length)
    }
}
