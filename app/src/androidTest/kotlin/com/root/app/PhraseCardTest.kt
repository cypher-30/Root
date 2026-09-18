package com.root.app

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.root.app.data.PhraseEntity
import com.root.app.data.ReferralPrefs
import com.root.app.sharing.PhraseCardPalette
import com.root.app.sharing.PhraseCardRenderer
import com.root.app.sharing.PhraseCardSharing
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Confirms the generated share PNG is provider-readable and diacritic-safe, and
 *  that merely preparing a card (as opposed to opening the share sheet) never
 *  grants the sharing reward — see [PhraseCardSharing.openShareSheet]. */
class PhraseCardTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val palette = PhraseCardPalette(0xfff4f0e8.toInt(), 0xff27251f.toInt(), 0xff655e53.toInt(), 0xff995137.toInt(), 0xffd6cfc2.toInt())

    @Test fun imageIsReadableThroughProviderAndPreparingDoesNotGrantReward() = runBlocking {
        val before = ReferralPrefs.hasUnlockedReward(context)
        val phrase = PhraseEntity(packId = "image-test", prompt = "A place to return to", answer = "Home", audioAsset = null)
        val card = PhraseCardSharing.prepare(context, phrase, "English · test card", palette)
        assertEquals("content", card.uri.scheme)
        assertEquals("${context.packageName}.sharedfiles", card.uri.authority)
        context.contentResolver.openInputStream(card.uri).use { stream ->
            val bitmap = BitmapFactory.decodeStream(stream)
            assertNotNull(bitmap)
            assertEquals(1080, bitmap.width)
            assertTrue(bitmap.height >= 1350)
            bitmap.recycle()
        }
        assertEquals(before, ReferralPrefs.hasUnlockedReward(context))
        card.preview.recycle()
    }

    @Test fun longDiacriticTextWrapsIntoTallerImage() {
        val phrase = PhraseEntity(packId = "image-test",
            prompt = "A long phrase keeps all of its words.", answer = "Ā ē ī ō ū ñ é à ".repeat(45), audioAsset = null)
        val image = PhraseCardRenderer.render(context, phrase, "Typography specimen", palette)
        assertTrue(image.height > 1350)
        assertTrue(image.height <= 8192)
        image.recycle()
    }
}
