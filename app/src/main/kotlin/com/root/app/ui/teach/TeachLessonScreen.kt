package com.root.app.ui.teach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.root.app.content.Activity
import com.root.app.content.Choice
import com.root.app.content.EvaluableTask
import com.root.app.content.Lesson
import com.root.app.learning.LessonRunState
import com.root.app.teach.LessonAudioSource
import com.root.app.ui.audio.VoiceRecordingControls
import com.root.app.ui.audio.rememberRootAudioSession
import com.root.app.ui.icon.RootIcons
import com.root.app.ui.theme.RootType

/**
 * Renders all three teaching formats (dialogue/explanation/audio/choice/tokens/
 * feedback/reflection) through one shared step-by-step runner UI, driven
 * directly from the shared [Lesson]/[Activity] content and [LessonRunState] —
 * there is deliberately no per-format screen and no parallel step/DTO type of
 * this module's own. A null [run] shows a loading state.
 */
@Composable
fun TeachLessonScreen(
    lesson: Lesson,
    run: LessonRunState?,
    error: String?,
    audioSource: (String?) -> LessonAudioSource,
    onChoice: (String, String) -> Unit,
    onTokens: (String, List<String>) -> Unit,
    onSelfAssessed: (String, Boolean) -> Unit,
    onReveal: (String) -> Unit,
    onAcknowledgeUnavailable: (String) -> Unit,
    onContinue: () -> Unit,
    onRestart: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(onClick = onBack) { Icon(RootIcons.Back, contentDescription = "Pause and go back") }
                    Text(formatLabel(lesson.format), style = MaterialTheme.typography.labelSmall)
                }
                if (run == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Opening lesson…", style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    error?.let {
                        Text(
                            it,
                            Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                                .semantics { liveRegion = LiveRegionMode.Assertive },
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    when {
                        // The pack backing this run was retired/uninstalled mid-run
                        // (LessonRunStatus.UNAVAILABLE) — distinct from a normal
                        // completion: never offer Resume/Restart for content that
                        // no longer exists on-device.
                        run.status == "UNAVAILABLE" -> LessonUnavailableContent(Modifier.weight(1f), onBack)
                        run.completed -> LessonCompleteContent(lesson, run, Modifier.weight(1f), onRestart, onBack)
                        else -> LessonStepContent(
                            lesson, run, Modifier.weight(1f), audioSource, onChoice, onTokens,
                            onSelfAssessed, onReveal, onAcknowledgeUnavailable, onContinue,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LessonStepContent(
    lesson: Lesson,
    run: LessonRunState,
    columnModifier: Modifier,
    audioSource: (String?) -> LessonAudioSource,
    onChoice: (String, String) -> Unit,
    onTokens: (String, List<String>) -> Unit,
    onSelfAssessed: (String, Boolean) -> Unit,
    onReveal: (String) -> Unit,
    onAcknowledgeUnavailable: (String) -> Unit,
    onContinue: () -> Unit,
) {
    val activity = lesson.activities.getOrNull(run.currentActivityIndex)
    Column(columnModifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Text(
            "Step ${run.currentActivityIndex + 1} of ${run.totalActivities}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        val respondedCorrect = run.currentActivityFeedback?.correct
        when (activity) {
            null -> Text("This lesson has no more steps.")
            is Activity.DialogueTurn -> DialogueTurnContent(activity)
            is Activity.PatternExplanation -> ExplanationContent(activity)
            is Activity.ChoiceActivity -> ChoiceTaskContent(activity.task, respondedCorrect) { onChoice(activity.id, it) }
            is Activity.OrderedTokenActivity -> OrderedTokenTaskContent(activity.task, respondedCorrect) { onTokens(activity.id, it) }
            is Activity.Listening -> ListeningContent(
                activity, run, audioSource(activity.audioAssetId), respondedCorrect,
                onReveal, onChoice, onTokens, onAcknowledgeUnavailable,
            )
            is Activity.Reflection -> ReflectionContent(activity)
            is Activity.SpeakingPrompt -> SpeakingPromptContent(activity) { onSelfAssessed(activity.id, it) }
        }
        Spacer(Modifier.height(24.dp))
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    val canContinue = activity?.let { canContinue(it, run) } ?: false
    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.End) {
        Button(onClick = onContinue, enabled = canContinue, shape = MaterialTheme.shapes.small) {
            Text("Continue")
        }
    }
}

private fun canContinue(activity: Activity, run: LessonRunState): Boolean = when (activity) {
    is Activity.ChoiceActivity, is Activity.OrderedTokenActivity, is Activity.Listening ->
        run.currentActivityFeedback != null
    // Dialogue/explanation/reflection/speaking-prompt steps can always attempt
    // to continue — Continue itself auto-submits the required acknowledgement
    // for those, and a required-but-unavailable listening audio activity is
    // instead permanently blocked above (never silently skipped/completed).
    else -> true
}

@Composable
private fun DialogueTurnContent(step: Activity.DialogueTurn) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(step.speaker, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(step.text, style = RootType.editorialTitle)
        step.translation?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ExplanationContent(step: Activity.PatternExplanation) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(step.explanation, style = MaterialTheme.typography.bodyLarge)
        step.examples.forEach { example ->
            Text(example, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun ReflectionContent(step: Activity.Reflection) {
    Text(step.prompt, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun SpeakingPromptContent(step: Activity.SpeakingPrompt, onSelfAssessed: (Boolean) -> Unit) {
    val session = rememberRootAudioSession(step.id)
    var practiced by rememberSaveable(step.id) { mutableStateOf<Boolean?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(step.prompt, style = MaterialTheme.typography.bodyLarge)
        Text(
            "Optional. Speaking is self-assessed only — Root never scores pronunciation or automatically rates recall from it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        VoiceRecordingControls(session)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { practiced = true; onSelfAssessed(true) },
                enabled = practiced != true, shape = MaterialTheme.shapes.small) { Text("I practiced this") }
            TextButton(onClick = { practiced = false; onSelfAssessed(false) },
                enabled = practiced == null) { Text("Skip") }
        }
    }
}

/**
 * Missing audio ([LessonAudioSource.Unavailable]) is shown as an explicit,
 * accessible label — never silently swapped for the transcript, and the
 * comprehension check is disabled entirely (the runner permanently rejects
 * any submission for this step while its audio is unavailable, so offering a
 * clickable answer would be dishonest) — but acknowledging the unavailable
 * state itself is accepted by the runner as EXPOSURE evidence, so [onAcknowledgeUnavailable]
 * is offered instead of leaving the step permanently blocked. Revealing the
 * transcript/translation commits [onReveal] (assistance) *before* the text is
 * shown — the reveal button only becomes the visible text after the run's
 * own assistance state updates, which happens because [onReveal] suspends
 * until the runner commits it.
 */
@Composable
private fun ListeningContent(
    step: Activity.Listening,
    run: LessonRunState,
    source: LessonAudioSource,
    respondedCorrect: Boolean?,
    onReveal: (String) -> Unit,
    onChoice: (String, String) -> Unit,
    onTokens: (String, List<String>) -> Unit,
    onAcknowledgeUnavailable: (String) -> Unit,
) {
    val session = rememberRootAudioSession()
    val assisted = run.assistanceUsedActivityIds.contains(step.id)
    val audioUnavailable = source is LessonAudioSource.Unavailable
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (source) {
            is LessonAudioSource.Unavailable -> Text(
                step.unavailableReason
                    ?: "Audio unavailable. This lesson needs a real speaker recording that hasn't been sourced/reviewed yet — " +
                        "it can't be marked complete without it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { contentDescription = "Required audio unavailable" },
            )
            is LessonAudioSource.Bundled -> OutlinedButton(
                onClick = { session.playReference(source.assetPath) },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) { Text(if (session.playing == "reference") "Stop" else "Play recording") }
            is LessonAudioSource.DownloadedFile -> OutlinedButton(
                onClick = { session.playReference(source.absolutePath) },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) { Text(if (session.playing == "reference") "Stop" else "Play recording") }
        }
        session.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (step.transcript != null || step.translation != null) {
            if (assisted) {
                step.transcript?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                step.translation?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("Transcript shown — this attempt is recorded as assisted.", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                TextButton(onClick = { onReveal(step.id) }) { Text("Show transcript (marks this attempt assisted)") }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (audioUnavailable) {
            if (run.currentActivityFeedback == null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "You can still continue: acknowledging the missing recording is recorded honestly as " +
                            "exposure only, never as a passed comprehension check.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { onAcknowledgeUnavailable(step.id) },
                        shape = MaterialTheme.shapes.small,
                    ) { Text("I understand — no recording available yet") }
                }
            } else {
                Text(
                    "Acknowledged. This step can't be marked as comprehension-checked without a real recording.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            when (val comprehension = step.comprehension) {
                is EvaluableTask.Choice -> ChoiceTaskContent(comprehension.task, respondedCorrect) { onChoice(step.id, it) }
                is EvaluableTask.OrderedTokens -> OrderedTokenTaskContent(comprehension.task, respondedCorrect) { onTokens(step.id, it) }
            }
        }
    }
}

@Composable
private fun ChoiceTaskContent(task: com.root.app.content.ChoiceTask, respondedCorrect: Boolean?, onChoice: (String) -> Unit) {
    var selected by rememberSaveable(task.id) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(task.prompt, style = MaterialTheme.typography.bodyLarge)
        task.choices.forEach { choice ->
            ChoiceRow(choice, selected == choice.id, enabled = respondedCorrect == null) {
                selected = choice.id
                onChoice(choice.id)
            }
        }
        respondedCorrect?.let { correct -> FeedbackBanner(correct, if (correct) task.feedbackCorrect else task.feedbackIncorrect) }
    }
}

@Composable
private fun ChoiceRow(choice: Choice, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().semantics { this.selected = selected; role = Role.RadioButton },
        shape = MaterialTheme.shapes.small,
    ) { Text((if (selected) "• " else "") + choice.text) }
}

@Composable
private fun OrderedTokenTaskContent(task: com.root.app.content.OrderedTokenTask, respondedCorrect: Boolean?, onTokens: (List<String>) -> Unit) {
    var order by rememberSaveable(task.id) { mutableStateOf(emptyList<String>()) }
    val byId = remember(task.tokens) { task.tokens.associateBy { it.occurrenceId } }
    val locked = respondedCorrect != null
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(task.prompt, style = MaterialTheme.typography.bodyLarge)
        Text("Built: " + order.mapNotNull { byId[it]?.text }.joinToString(" ").ifBlank { "(tap words below in order)" },
            style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            task.tokens.filterNot { it.occurrenceId in order }.forEach { token ->
                // Tap-to-add tokens (non-drag), so ordering is accessible without a
                // drag gesture.
                TextButton(onClick = { order = order + token.occurrenceId }, enabled = !locked) { Text(token.text) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { order = emptyList() }, enabled = order.isNotEmpty() && !locked) { Text("Clear") }
            Button(
                onClick = { onTokens(order) },
                enabled = order.size == task.tokens.size && !locked,
                shape = MaterialTheme.shapes.small,
            ) { Text("Check") }
        }
        respondedCorrect?.let { correct ->
            FeedbackBanner(correct, if (correct) task.feedbackCorrect else task.feedbackIncorrect)
            if (!correct) TextButton(onClick = { order = emptyList() }) { Text("Try again") }
        }
    }
}

@Composable
private fun FeedbackBanner(correct: Boolean, text: String?) {
    Text(
        (if (correct) "Correct. " else "Not quite. ") + text.orEmpty(),
        style = MaterialTheme.typography.bodyMedium,
        color = if (correct) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun LessonUnavailableContent(columnModifier: Modifier, onBack: () -> Unit) {
    Column(columnModifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("This content is no longer available.", style = RootType.editorialTitle)
        Text(
            "The unit backing this lesson was removed or retired. Your earlier progress is preserved but this run can't continue.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onBack, shape = MaterialTheme.shapes.small) { Text("Back") }
    }
}

@Composable
private fun LessonCompleteContent(
    lesson: Lesson,
    run: LessonRunState,
    columnModifier: Modifier,
    onRestart: () -> Unit,
    onBack: () -> Unit,
) {
    Column(columnModifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Lesson practiced.", style = RootType.editorialTitle)
        Text(
            "You completed ${run.requiredCompletedCount} of ${run.requiredTotalCount} required step(s) in \"${lesson.title}\". " +
                "This reflects what was practiced, not a fluency or mastery score.",
            style = MaterialTheme.typography.bodyMedium,
        )
        val assistedCount = run.assistanceUsedActivityIds.size
        if (assistedCount > 0) {
            Text("$assistedCount step(s) used shown transcript/hint support.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onRestart, shape = MaterialTheme.shapes.small) { Text("Restart (new attempt)") }
            Button(onClick = onBack, shape = MaterialTheme.shapes.small) { Text("Done") }
        }
    }
}
