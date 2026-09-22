package com.root.app.archive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.root.app.data.PhraseEntity
import com.root.app.ui.theme.RootType

/**
 * The learner's "Your words" archive: search, inline edit, and permanent
 * delete for personally-contributed phrases only (see [ArchiveViewModel]).
 * Never lists a catalog/pack-managed phrase — those are retired through the
 * content pipeline's own lifecycle, not from here.
 */
@Composable
fun ArchiveScreen(
    languageId: String,
    languageName: String,
    onBack: () -> Unit,
    vm: ArchiveViewModel = viewModel(),
) {
    val phrases by vm.phrases.collectAsStateWithLifecycle()
    val query by vm.searchQuery.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<PhraseEntity?>(null) }
    var confirmingDeleteOf by remember { mutableStateOf<PhraseEntity?>(null) }

    androidx.compose.runtime.LaunchedEffect(languageId) { vm.setLanguage(languageId) }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Your words", style = RootType.editorialTitle)
        Text("Phrases you've added for $languageName. Editing changes the text only; deleting is permanent.")
        OutlinedTextField(
            value = query,
            onValueChange = vm::setQuery,
            label = { Text("Search your words") },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        )
        vm.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        if (phrases.isEmpty()) {
            Text(
                if (query.isBlank()) "You haven't added any words yet." else "No words match \"$query\".",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(phrases, key = { it.id }) { phrase ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(phrase.answer, style = RootType.heroAnswer)
                        Text(phrase.prompt, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        androidx.compose.foundation.layout.Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            OutlinedButton(onClick = { editing = phrase }) { Text("Edit") }
                            OutlinedButton(onClick = { confirmingDeleteOf = phrase }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }

    editing?.let { phrase ->
        EditPhraseDialog(
            phrase = phrase,
            onDismiss = { editing = null },
            onSave = { prompt, answer ->
                vm.update(phrase.id, prompt, answer) { error -> if (error == null) editing = null }
            },
        )
    }

    confirmingDeleteOf?.let { phrase ->
        AlertDialog(
            onDismissRequest = { confirmingDeleteOf = null },
            title = { Text("Delete this word?") },
            text = {
                Text(
                    "This permanently deletes \"${phrase.answer}\", its recording (if any), and its " +
                        "practice history from this device. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(phrase.id) { confirmingDeleteOf = null }
                }) { Text("Delete permanently") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDeleteOf = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EditPhraseDialog(
    phrase: PhraseEntity,
    onDismiss: () -> Unit,
    onSave: (prompt: String, answer: String) -> Unit,
) {
    var prompt by rememberSaveable(phrase.id) { mutableStateOf(phrase.prompt) }
    var answer by rememberSaveable(phrase.id) { mutableStateOf(phrase.answer) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit word") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Meaning") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    label = { Text("Phrase") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = prompt.isNotBlank() && answer.isNotBlank(),
                onClick = { onSave(prompt, answer) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
