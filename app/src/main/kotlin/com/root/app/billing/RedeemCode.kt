package com.root.app.billing

/**
 * A showcase code for judges and demos. Redeeming it grants the same access as
 * the all-languages purchase, on this device only, without a store account or a
 * RevenueCat key. It never creates a RevenueCat transaction; real purchases
 * still go through [PaywallViewModel] and the store.
 */
object RedeemCode {
    const val SHOWCASE = "SHIPATON2026"

    /** Case, spaces and dashes are ignored, so "shipaton-2026" also works. */
    fun normalize(input: String): String = input.uppercase().filter { it.isLetterOrDigit() }

    fun isValid(input: String): Boolean = normalize(input) == SHOWCASE
}
