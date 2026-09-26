package com.root.app.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.platform.app.InstrumentationRegistry
import com.root.app.data.ContributionAudioState
import com.root.app.data.RootRepository
import com.root.app.ui.theme.RootTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The contribution form persists typed fields to a durable draft, reports the
 *  save honestly, and only discards after an explicit confirmation. */
class ContributeDraftUiTest {
    @get:Rule val compose = createComposeRule()
    private val repository = RootRepository(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test fun typingSavesADraftAndDiscardNeedsConfirmation() {
        var vm: ContributeViewModel? = null
        var backCount = 0
        compose.setContent {
            RootTheme {
                Surface {
                    val model: ContributeViewModel = viewModel()
                    vm = model
                    ContributeScreen(
                        vm = model,
                        requestedDraftId = null,
                        defaultLanguageName = "Draft UI Test Language",
                        activeLanguageId = null,
                        onDraftsChanged = {},
                        onCommit = { _, _, _, _, _, _, _ -> },
                        onBack = { backCount++ },
                    )
                }
            }
        }

        compose.onNode(hasSetTextAction() and hasText("Meaning in a language you know")).performTextInput("Good morning")
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Draft saved on this device.")).fetchSemanticsNodes().isNotEmpty()
        }
        val draftId = requireNotNull(vm?.draftId)
        val saved = requireNotNull(runBlocking { repository.draft(draftId) })
        assertEquals("Good morning", saved.promptDraft)
        assertEquals("Draft UI Test Language", saved.languageNameDraft)

        compose.onNodeWithText("Discard draft").performScrollTo().performClick()
        compose.onNodeWithText("Keep editing").performClick()
        assertTrue(runBlocking { repository.draft(draftId) }!!.audioState != ContributionAudioState.DISCARDED)

        compose.onNodeWithText("Discard draft").performScrollTo().performClick()
        compose.onNodeWithText("Discard").performClick()
        compose.waitUntil(5_000) { backCount == 1 }
        assertEquals(ContributionAudioState.DISCARDED, runBlocking { repository.draft(draftId) }!!.audioState)
    }
}
