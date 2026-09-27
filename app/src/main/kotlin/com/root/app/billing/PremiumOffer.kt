package com.root.app.billing

/**
 * What Root Premium is, as plain data shared by the paywall and its tests.
 * The definition and pricing rationale live in docs/PREMIUM.md. The price you
 * are charged always comes from the store (RevenueCat/Play); the planned prices
 * are only shown, labelled as planned, while the store has no plan to show.
 *
 * Premium pays for what costs real money to make: native-speaker review and
 * recording. It never gates practice, frequency, or the starter sets.
 */
object PremiumOffer {
    /** Topic sets planned for every language, in rough priority order for heritage learners. */
    val plannedTopics = listOf(
        "Talking with elders", "Family and home", "Food and cooking",
        "Ceremonies", "Health", "Travel", "Work",
    )

    val included: List<Pair<String, String>> = listOf(
        "Native voices" to
            "Every premium phrase is reviewed and recorded by a native speaker, and credited by name.",
        "Deeper sets" to
            plannedTopics.mapIndexed { i, topic -> if (i == 0) topic else topic.lowercase() }
                .joinToString(", ").replaceLast(", ", ", and ") + ".",
        "Proverbs and longer stories" to
            "Sayings with what they mean and when to use them, and longer spoken scenes.",
        "Full Learn courses" to
            "Guided units that continue past the starter lessons.",
        "Pay once, for one language or all" to
            "$plannedLanguagePrice for the language you're learning, or $plannedAllLanguagesPrice for every language. " +
                "Not a subscription. Premium packs added later for what you own are included.",
    )

    /** Keep in step with the price table in docs/PREMIUM.md and the Play Console products. */
    const val plannedLanguagePrice = "US$4.99"
    const val plannedAllLanguagesPrice = "US$9.99"
    const val plannedPriceNote =
        "Planned prices, each a one-time unlock and not a subscription. In Kenya and Zimbabwe, and in Ethiopia if the store " +
            "sells there, about US$1.99 for one language or US$3.99 for all. Nothing is for sale until the first " +
            "premium pack is ready, and the store shows the exact amount in your currency before you pay."

    /** Title for a plan card. [languageName] is the language the paywall was opened for. */
    fun planTitle(plan: PremiumPlan, languageName: String?): String = when (plan) {
        PremiumPlan.AllLanguages -> "All languages"
        is PremiumPlan.Language -> "${languageName ?: plan.key.replaceFirstChar { it.uppercase() }} only"
    }

    fun planDetail(plan: PremiumPlan, languageName: String?): String = when (plan) {
        PremiumPlan.AllLanguages -> "Every premium pack, in every language, including ones added later."
        is PremiumPlan.Language -> "Every premium pack for ${languageName ?: "this language"}, including ones added later."
    }

    const val alwaysFree =
        "Every starter set, daily practice, the starter Learn units, Explore, your notebook, " +
            "and your own words. Offline, with no account."

    private fun String.replaceLast(old: String, new: String): String {
        val index = lastIndexOf(old)
        return if (index < 0) this else substring(0, index) + new + substring(index + old.length)
    }
}
