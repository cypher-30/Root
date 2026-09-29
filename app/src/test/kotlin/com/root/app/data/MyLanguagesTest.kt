package com.root.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MyLanguagesTest {
    private val dholuo = LanguageEntity("dholuo", "Dholuo", false)
    private val shona = LanguageEntity("shona", "Shona", false)
    private val swahili = LanguageEntity("swahili", "Swahili", false)
    private val all = listOf(dholuo, shona, swahili)

    @Test fun beforeAnyChoiceOnlyTheActiveLanguageIsYours() {
        val split = MyLanguages.split(all, chosen = null, activeId = "dholuo")
        assertEquals(listOf(dholuo), split.mine)
        assertEquals(listOf(shona, swahili), split.others)
    }

    @Test fun chosenLanguagesAndTheActiveOneAreYoursTheRestCanBeAdded() {
        val split = MyLanguages.split(all, chosen = setOf("swahili"), activeId = "shona")
        assertEquals(listOf(shona, swahili), split.mine)
        assertEquals(listOf(dholuo), split.others)
    }

    @Test fun everyBundledLanguageSaysWhereItIsSpoken() {
        listOf("Dholuo" to "Kenya", "Shona" to "Zimbabwe", "Swahili" to "East Africa", "Amharic" to "Ethiopia")
            .forEach { (name, place) -> assertTrue(name, place in MyLanguages.about(name)) }
        assertTrue("your own words" in MyLanguages.about("Kikuyu"))
    }
}
