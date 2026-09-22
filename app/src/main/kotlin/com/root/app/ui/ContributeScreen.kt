package com.root.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.root.app.ui.theme.RootTheme
import androidx.compose.material3.Surface
import com.root.app.ui.audio.VoiceRecordingControls
import com.root.app.ui.audio.rememberRootAudioSession
import com.root.app.ui.theme.RootType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A learner-authored phrase form: language, prompt, answer, and an optional
 * reference recording via [VoiceRecordingControls]. The recorder's draft file is
 * only "claimed" by the save transaction inside
 * [com.root.app.audio.RootAudioSession.beginContributionSave] /
 * `endContributionSave`, so a save failure leaves the draft recoverable and a
 * cancelled coroutine (e.g. navigating away mid-save) does not orphan the file —
 * the whole save runs under [NonCancellable].
 */
@Composable
fun ContributeScreen(
    initialLanguageName: String,
    onSave: suspend (languageName: String, prompt: String, answer: String, audioPath: String?, speakerLabel: String?, consentConfirmed: Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var languageName by rememberSaveable { mutableStateOf(initialLanguageName) }
    var prompt by rememberSaveable { mutableStateOf("") }
    var answer by rememberSaveable { mutableStateOf("") }
    var speakerLabel by rememberSaveable { mutableStateOf("") }
    var consentConfirmed by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val session = rememberRootAudioSession()
    val scope = rememberCoroutineScope()
    BackHandler { if (!saving) onBack() }
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack, enabled = !saving) { Text("Back") }
        Text("Keep a word alive.", style = RootType.editorialTitle)
        Text("Add a phrase you know. It stays in Your words on this device.", style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = languageName,
            onValueChange = { languageName = it },
            label = { Text("Language") },
            enabled = !saving && !saved,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        )
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Meaning in a language you know") },
            supportingText = { Text("For example: How are you?") },
            minLines = 2,
            enabled = !saving && !saved,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        )
        OutlinedTextField(
            value = answer,
            onValueChange = { answer = it },
            label = { Text("Phrase in the target language") },
            textStyle = RootType.heroAnswer,
            minLines = 2,
            enabled = !saving && !saved,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        )
        Text("A family voice (optional)", style = MaterialTheme.typography.titleLarge)
        Text(
            "Ask before recording someone",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Text("With their permission, record a speaker saying this phrase. The recording stays on this device and becomes the reference only when you save.")
        VoiceRecordingControls(session, enabled = !saving && !saved)
        if (session.hasRecording) {
            OutlinedTextField(
                value = speakerLabel,
                onValueChange = { speakerLabel = it },
                label = { Text("Who's speaking? (optional)") },
                supportingText = { Text("For example: Grandma. Never shared, just a note for you.") },
                enabled = !saving && !saved,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            )
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(
                    checked = consentConfirmed,
                    onCheckedChange = { consentConfirmed = it },
                    enabled = !saving && !saved,
                )
                Text(
                    "I have this person's permission to record them and keep this recording on my device.",
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        if (saved) {
            Text("Phrase saved.", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        OutlinedButton(
            enabled = !saving && !saved && !session.isRecording &&
                languageName.isNotBlank() && prompt.isNotBlank() && answer.isNotBlank() &&
                (!session.hasRecording || consentConfirmed),
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            onClick = {
                if (!saving && !saved) {
                    saving = true
                    error = null
                    val language = languageName.trim()
                    val meaning = prompt.trim()
                    val phrase = answer.trim()
                    val speaker = speakerLabel.trim()
                    scope.launch {
                        // Do not delete audio between a committed database save and ownership transfer.
                        withContext(NonCancellable) {
                            try {
                                val path = session.beginContributionSave()
                                onSave(language, meaning, phrase, path, speaker.takeIf { it.isNotEmpty() }, consentConfirmed)
                                session.endContributionSave(success = true)
                                saved = true
                            } catch (cancelled: CancellationException) {
                                session.endContributionSave(success = false)
                                throw cancelled
                            } catch (failure: Exception) {
                                session.endContributionSave(success = false)
                                error = failure.localizedMessage?.takeIf { it.isNotBlank() }
                                    ?: "The phrase could not be saved. Your words are still here; please try again."
                            } finally {
                                saving = false
                            }
                        }
                    }
                }
            },
        ) {
            Text(if (saving) "Saving..." else if (saved) "Saved" else "Save phrase")
        }
    }
}
