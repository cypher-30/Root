package com.root.app.billing

import com.revenuecat.purchases.Package
import com.root.app.data.PremiumAccess

/**
 * The two ways to buy Premium, both one-time purchases. Store products follow a
 * naming convention so the app knows what each one opens without trusting the
 * store's display text:
 * - `root_premium_all` grants the `premium` entitlement (every language).
 * - `root_premium_<language key>` (e.g. `root_premium_dholuo`) grants `premium_<key>`.
 * Any other product in the offering is never shown.
 */
sealed interface PremiumPlan {
    data object AllLanguages : PremiumPlan
    data class Language(val key: String) : PremiumPlan

    companion object {
        const val ALL_LANGUAGES_PRODUCT = "root_premium_all"
        private const val LANGUAGE_PRODUCT_PREFIX = "root_premium_"

        fun productIdFor(languageName: String): String = LANGUAGE_PRODUCT_PREFIX + PremiumAccess.languageKey(languageName)

        /** Plans are recognised by store product ID; a Play subscription ID's ":base-plan" suffix is ignored. */
        fun of(productId: String): PremiumPlan? {
            val id = productId.substringBefore(':')
            return when {
                id == ALL_LANGUAGES_PRODUCT -> AllLanguages
                id.startsWith(LANGUAGE_PRODUCT_PREFIX) && id.length > LANGUAGE_PRODUCT_PREFIX.length ->
                    Language(id.removePrefix(LANGUAGE_PRODUCT_PREFIX))
                else -> null
            }
        }

        fun of(pkg: Package): PremiumPlan? = of(pkg.product.id)
    }
}
