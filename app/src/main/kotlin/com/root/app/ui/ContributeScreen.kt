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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.root.app.data.ContributionDraftEntity
import com.root.app.ui.audio.VoiceRecordingControls
import com.root.app.ui.audio.rememberRootAudioSession
import com.root.app.ui.theme.RootType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
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
    activeLanguageId: String?,
    initialDraft: ContributionDraftEntity? = null,
    onEnsureDraft: suspend (languageId: String) -> String,
    onAutosaveDraft: suspend (draftId: String, prompt: String, answer: String, speakerLabel: String?) -> Unit,
    onDiscardDraft: suspend (draftId: String) -> Unit,
    onSave: suspend (languageName: String, prompt: String, answer: String, audioPath: String?, speakerLabel: String?, consentConfirmed: Boolean, draftId: String?) -> Unit,
    onBack: () -> Unit,
) {
    data class DraftFields(val prompt: String, val answer: String, val speakerLabel: String) {
        fun isBlank(): Boolean = prompt.isBlank() && answer.isBlank() && speakerLabel.isBlank()
        fun speakerLabelOrNull(): String? = speakerLabel.trim().takeIf { it.isNotEmpty() }
    }

    val draftKey = initialDraft?.id ?: "new"
    val restoredFields = remember(initialDraft?.id) {
        DraftFields(
            prompt = initialDraft?.promptDraft.orEmpty(),
            answer = initialDraft?.answerDraft.orEmpty(),
            speakerLabel = initialDraft?.speakerLabelDraft.orEmpty(),
        )
    }
    var draftId by rememberSaveable(draftKey) { mutableStateOf(initialDraft?.id) }
    var languageName by rememberSaveable(draftKey) { mutableStateOf(initialLanguageName) }
    var prompt by rememberSaveable(draftKey) { mutableStateOf(restoredFields.prompt) }
    var answer by rememberSaveable(draftKey) { mutableStateOf(restoredFields.answer) }
    var speakerLabel by rememberSaveable(draftKey) { mutableStateOf(restoredFields.speakerLabel) }
    var consentConfirmed by rememberSaveable(draftKey) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var discarding by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var draftMessage by remember(initialDraft?.id) {
        mutableStateOf(if (initialDraft != null) "Draft restored." else null)
    }
    var lastAutosaved by remember(initialDraft?.id) { mutableStateOf(restoredFields) }
    val session = rememberRootAudioSession()
    val scope = rememberCoroutineScope()
    BackHandler { if (!saving && !discarding) onBack() }

    LaunchedEffect(prompt, answer, speakerLabel, draftId, activeLanguageId, saving, discarding, saved) {
        if (saving || discarding || saved) return@LaunchedEffect
        val fields = DraftFields(prompt = prompt, answer = answer, speakerLabel = speakerLabel)
        if (fields == lastAutosaved || fields.isBlank()) return@LaunchedEffect
        delay(600)
        val latest = DraftFields(prompt = prompt, answer = answer, speakerLabel = speakerLabel)
        if (latest == lastAutosaved || latest.isBlank()) return@LaunchedEffect
        try {
            val ensuredDraftId = draftId
                ?: if (activeLanguageId != null) onEnsureDraft(activeLanguageId) else return@LaunchedEffect
            draftId = ensuredDraftId
            onAutosaveDraft(ensuredDraftId, latest.prompt, latest.answer, latest.speakerLabelOrNull())
            lastAutosaved = latest
            draftMessage = "Draft saved."
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Drop autosave failure instead of blocking the user
        }
    }
    
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack, enabled = !saving && !discarding) { Text("Back") }
        Text("Keep a word alive.", style = RootType.editorialTitle)
        Text("Add a phrase you know. It stays in Your words on this device.", style = MaterialTheme.typography.bodyLarge)
        draftMessage?.let {
            Text(
                it,
                style = RootType.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        OutlinedTextField(
            value = languageName,
            onValueChange = { languageName = it },
            label = { Text("Language") },
            enabled = !saving && !discarding && !saved,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        )
        OutlinedTextField(
            value = prompt,
            onValueChange = {
                prompt = it
                draftMessage = null
            },
            label = { Text("Meaning in a language you know") },
            supportingText = { Text("For example: How are you?") },
            minLines = 2,
            enabled = !saving && !discarding && !saved,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        )
        OutlinedTextField(
            value = answer,
            onValueChange = {
                answer = it
                draftMessage = null
            },
            label = { Text("Phrase in the target language") },
            textStyle = RootType.heroAnswer,
            minLines = 2,
            enabled = !saving && !discarding && !saved,
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
        VoiceRecordingControls(session, enabled = !saving && !discarding && !saved)
        if (session.hasRecording) {
            OutlinedTextField(
                value = speakerLabel,
                onValueChange = {
                    speakerLabel = it
                    draftMessage = null
                },
                label = { Text("Who's speaking? (optional)") },
                supportingText = { Text("For example: Grandma. Never shared, just a note for you.") },
                enabled = !saving && !discarding && !saved,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = consentConfirmed,
                    onCheckedChange = { consentConfirmed = it },
                    enabled = !saving && !discarding && !saved,
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
        if (draftId != null && !saved) {
            TextButton(
                enabled = !saving && !discarding,
                onClick = {
                    val existingDraftId = draftId ?: return@TextButton
                    scope.launch {
                        discarding = true
                        error = null
                        try {
                            onDiscardDraft(existingDraftId)
                            onBack()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.localizedMessage?.takeIf { it.isNotBlank() }
                                ?: "That draft couldn't be discarded just yet."
                        } finally {
                            discarding = false
                        }
                    }
                },
            ) {
                Text(if (discarding) "Discarding..." else "Discard draft")
            }
        }
        OutlinedButton(
            enabled = !saving && !discarding && !saved && !session.isRecording &&
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
                                val ensuredDraftId = draftId ?: activeLanguageId?.let { languageId ->
                                    onEnsureDraft(languageId).also { createdId ->
                                        draftId = createdId
                                        onAutosaveDraft(createdId, prompt, answer, speaker.takeIf { it.isNotEmpty() })
                                        lastAutosaved = DraftFields(prompt = prompt, answer = speaker, speakerLabel = speakerLabel)
                                    }
                                }
                                onSave(language, meaning, phrase, path, speaker.takeIf { it.isNotEmpty() }, consentConfirmed, ensuredDraftId)
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
