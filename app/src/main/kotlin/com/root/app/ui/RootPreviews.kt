package com.root.app.ui

// Compose @Preview functions kept separate from their screens to avoid pulling
// preview-only imports/parameters into the production composables above.
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.root.app.data.WeeklyChallengeEntity
import com.root.app.ui.audio.rememberRootAudioSession
import com.root.app.ui.theme.RootTheme

@Preview(name = "Contribution / paper", widthDp = 390, heightDp = 844)
@Preview(name = "Contribution / charcoal", widthDp = 390, heightDp = 844, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ContributionPreview() {
    RootTheme {
        Surface {
            ContributeForm(
                fields = DraftFields(languageName = "Your language", prompt = "How are you?"),
                onFieldsChange = {},
                status = DraftSaveStatus.Saved,
                session = rememberRootAudioSession(),
                audioNotice = null,
                consentConfirmed = false,
                onConsentChange = {},
                canDiscard = true,
                saving = false,
                discarding = false,
                busy = false,
                error = null,
                onBack = {},
                onDiscard = {},
                onSave = {},
            )
        }
    }
}

@Preview(name = "Complete / paper", widthDp = 390, heightDp = 844)
@Preview(name = "Complete / charcoal", widthDp = 390, heightDp = 844, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CompletionPreview() {
    RootTheme {
        Surface {
            SessionCompleteScreen(3, 3, true, "Your language",
                WeeklyChallengeEntity(weekStart = 0, theme = "Greetings", completed = true), {}, {}, {})
        }
    }
}
