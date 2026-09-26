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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.root.app.audio.RootAudioSession
import com.root.app.ui.audio.VoiceRecordingControls
import com.root.app.ui.audio.rememberRootAudioSession
import com.root.app.ui.theme.RootType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Saves a finished phrase. Arguments: language, meaning, phrase, recording
 *  path, speaker label, consent confirmed, and the draft being committed. */
typealias CommitContribution = suspend (String, String, String, String?, String?, Boolean, String?) -> Unit

/**
 * A learner-authored phrase form backed by a durable draft ([ContributeViewModel]).
 * Typed fields, a new language's name, and a completed recording survive leaving
 * the screen and process death. The recorder's file is only moved to the saved
 * phrase inside [RootAudioSession.beginContributionSave]/`endContributionSave`,
 * all under [NonCancellable], so a failed or interrupted save keeps the draft.
 */
@Composable
internal fun ContributeScreen(
    vm: ContributeViewModel,
    requestedDraftId: String?,
    defaultLanguageName: String,
    activeLanguageId: String?,
    onDraftsChanged: () -> Unit,
    onCommit: CommitContribution,
    onBack: () -> Unit,
) {
    val latestDraftsChanged by rememberUpdatedState(onDraftsChanged)
    LaunchedEffect(Unit) { vm.open(requestedDraftId, defaultLanguageName, activeLanguageId) }
    LaunchedEffect(vm.draftsVersion) { if (vm.draftsVersion > 0) latestDraftsChanged() }

    when (val state = vm.openState) {
        DraftOpenState.Loading -> {
            BackHandler(onBack = onBack)
            RouteLoading("Opening your draft…")
        }
        DraftOpenState.Missing -> {
            BackHandler(onBack = onBack)
            RouteMessage(
                title = "This draft is no longer here.",
                body = "It was already saved as a phrase or discarded.",
                onBack = onBack,
            )
        }
        is DraftOpenState.Failed -> {
            BackHandler(onBack = onBack)
            RouteMessage(
                title = "Your draft didn't open.",
                body = state.message,
                onBack = onBack,
                onRetry = { vm.retryOpen(defaultLanguageName) },
            )
        }
        DraftOpenState.Ready -> ContributeEditor(vm, onCommit, onBack)
    }
}

@Composable
private fun ContributeEditor(
    vm: ContributeViewModel,
    onCommit: CommitContribution,
    onBack: () -> Unit,
) {
    val session = rememberRootAudioSession()
    val scope = rememberCoroutineScope()
    var consentConfirmed by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var discarding by remember { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var confirmLeaveUnsaved by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var audioNotice by remember { mutableStateOf<String?>(null) }
    // The recorder must adopt the draft's saved take before its clip is
    // observed, or an empty new session would erase the saved reference.
    var audioReady by remember(session) { mutableStateOf(false) }
    val busy = saving || leaving || discarding

    LaunchedEffect(session) {
        vm.persistedAudioPath?.let { path ->
            if (!File(path).isFile || !session.adoptDraftRecording(path)) {
                audioNotice = "The recording in this draft is no longer on this device. Record it again if you want one."
            }
        }
        audioReady = true
    }
    val clipPath = session.clipPath
    LaunchedEffect(audioReady, clipPath, session.isRecording) {
        if (audioReady && !session.isRecording && !saving) {
            vm.updateAudio(clipPath) { persisted -> session.retainClipForDraft(persisted) }
        }
    }

    fun leave() {
        if (busy) return
        leaving = true
        scope.launch {
            val saved = try { vm.flush() } finally { leaving = false }
            if (saved) onBack() else confirmLeaveUnsaved = true
        }
    }
    BackHandler(enabled = true) { leave() }

    ContributeForm(
        fields = vm.fields,
        onFieldsChange = { next ->
            error = null
            vm.update(next)
        },
        status = vm.status,
        session = session,
        audioNotice = audioNotice,
        consentConfirmed = consentConfirmed,
        onConsentChange = { consentConfirmed = it },
        canDiscard = vm.draftId != null,
        saving = saving,
        discarding = discarding,
        busy = busy,
        error = error,
        onBack = ::leave,
        onDiscard = { confirmDiscard = true },
        onSave = {
            if (!busy) {
                saving = true
                error = null
                val fields = vm.fields
                val speaker = fields.speakerLabel.trim().takeIf { it.isNotEmpty() }
                scope.launch {
                    // Do not delete audio between a committed database save and ownership transfer.
                    withContext(NonCancellable) {
                        var began = false
                        try {
                            val draftId = try { vm.prepareCommit() } catch (_: Exception) { null }
                            val path = session.beginContributionSave()
                            began = true
                            onCommit(
                                fields.languageName.trim(),
                                fields.prompt.trim(),
                                fields.answer.trim(),
                                path,
                                speaker,
                                consentConfirmed,
                                draftId,
                            )
                            session.endContributionSave(success = true)
                            vm.committed()
                        } catch (failure: Exception) {
                            if (began) session.endContributionSave(success = false)
                            vm.commitFailed()
                            error = failure.localizedMessage?.takeIf { it.isNotBlank() && failure !is CancellationException }
                                ?: "The phrase could not be saved. Your words are still here; please try again."
                        } finally {
                            saving = false
                        }
                    }
                }
            }
        },
    )

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this draft?") },
            text = { Text("Its words and any recording will be deleted from this device. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    discarding = true
                    scope.launch {
                        try {
                            vm.discard()
                            onBack()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            error = "That draft couldn't be discarded just yet. Please try again."
                        } finally {
                            discarding = false
                        }
                    }
                }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
    if (confirmLeaveUnsaved) {
        AlertDialog(
            onDismissRequest = { confirmLeaveUnsaved = false },
            title = { Text("Your latest changes aren't saved.") },
            text = { Text("Root couldn't save this draft. If you leave now, your most recent edits will be lost.") },
            confirmButton = { TextButton(onClick = { confirmLeaveUnsaved = false }) { Text("Stay") } },
            dismissButton = {
                TextButton(onClick = {
                    confirmLeaveUnsaved = false
                    onBack()
                }) { Text("Leave anyway") }
            },
        )
    }
}

@Composable
internal fun ContributeForm(
    fields: DraftFields,
    onFieldsChange: (DraftFields) -> Unit,
    status: DraftSaveStatus,
    session: RootAudioSession,
    audioNotice: String?,
    consentConfirmed: Boolean,
    onConsentChange: (Boolean) -> Unit,
    canDiscard: Boolean,
    saving: Boolean,
    discarding: Boolean,
    busy: Boolean,
    error: String?,
    onBack: () -> Unit,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TextButton(onClick = onBack, enabled = !busy) { Text("Back") }
            Text("Keep a word alive.", style = RootType.editorialTitle)
            Text("Add a phrase you know. It stays in Your words on this device.", style = MaterialTheme.typography.bodyLarge)
            val statusText = when (status) {
                DraftSaveStatus.Idle -> null
                DraftSaveStatus.Pending -> "Saving draft…"
                DraftSaveStatus.Saved -> "Draft saved on this device."
                is DraftSaveStatus.Failed -> status.message
            }
            statusText?.let {
                Text(
                    it,
                    style = RootType.meta,
                    color = if (status is DraftSaveStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            OutlinedTextField(
                value = fields.languageName,
                onValueChange = { onFieldsChange(fields.copy(languageName = it)) },
                label = { Text("Language") },
                supportingText = { Text("Type a new name to start a new language.") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            )
            OutlinedTextField(
                value = fields.prompt,
                onValueChange = { onFieldsChange(fields.copy(prompt = it)) },
                label = { Text("Meaning in a language you know") },
                supportingText = { Text("For example: How are you?") },
                minLines = 2,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            )
            OutlinedTextField(
                value = fields.answer,
                onValueChange = { onFieldsChange(fields.copy(answer = it)) },
                label = { Text("Phrase in the target language") },
                textStyle = RootType.heroAnswer,
                minLines = 2,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            )
            Text("A family voice (optional)", style = MaterialTheme.typography.titleLarge)
            Text(
                "Ask before recording someone",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text("With their permission, record a speaker saying this phrase. The recording stays on this device, is kept with this draft, and becomes the reference only when you save.")
            audioNotice?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            VoiceRecordingControls(session, enabled = !busy)
            if (session.hasRecording) {
                OutlinedTextField(
                    value = fields.speakerLabel,
                    onValueChange = { onFieldsChange(fields.copy(speakerLabel = it)) },
                    label = { Text("Who's speaking? (optional)") },
                    supportingText = { Text("For example: Grandma. Never shared, just a note for you.") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = consentConfirmed,
                            enabled = !busy,
                            role = Role.Checkbox,
                            onValueChange = onConsentChange,
                        )
                        .padding(vertical = 4.dp),
                ) {
                    Checkbox(checked = consentConfirmed, onCheckedChange = null, enabled = !busy)
                    Text(
                        "I have this person's permission to record them and keep this recording on my device.",
                        modifier = Modifier.padding(start = 12.dp),
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
            if (canDiscard) {
                TextButton(enabled = !busy, onClick = onDiscard) {
                    Text(if (discarding) "Discarding…" else "Discard draft")
                }
            }
            val missing = buildList {
                if (fields.languageName.isBlank()) add("a language")
                if (fields.prompt.isBlank()) add("a meaning")
                if (fields.answer.isBlank()) add("the phrase")
            }
            val needsConsent = session.hasRecording && !consentConfirmed
            OutlinedButton(
                enabled = !busy && !session.isRecording && missing.isEmpty() && !needsConsent,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                onClick = onSave,
            ) {
                Text(if (saving) "Saving…" else "Save phrase")
            }
            val hint = when {
                missing.isNotEmpty() -> "To save, add ${missing.joinToString(", ")}."
                session.isRecording -> "Stop recording to save."
                needsConsent -> "Confirm the speaker's permission to save with this recording, or delete it."
                else -> null
            }
            hint?.let { Text(it, style = RootType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
