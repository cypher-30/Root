package com.root.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.root.app.data.LanguageEntity
import com.root.app.data.MyLanguages
import com.root.app.ui.icon.RootIcons
import com.root.app.ui.theme.RootType

/**
 * Profile → Language. The language being practised is the one highlighted card;
 * the learner's other languages sit beneath it as quieter cards they can switch
 * to or remove, and everything else waits under Add a language.
 */
@Composable
fun LanguageSheetContent(
    mine: List<LanguageEntity>,
    others: List<LanguageEntity>,
    activeId: String?,
    isLocked: (LanguageEntity) -> Boolean,
    onChoose: (LanguageEntity) -> Unit,
    onRemove: (LanguageEntity) -> Unit,
    onAddOwn: () -> Unit,
    onClose: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Your languages", style = RootType.editorialTitle)
        Text(
            "Tap one to switch. Practice, Learn and Explore all follow the language you're practising.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val ordered = mine.sortedByDescending { it.id == activeId }
        ordered.forEach { language ->
            val active = language.id == activeId
            LanguageCard(
                language = language,
                selected = active,
                onClick = { onChoose(language) },
                caption = if (active) "PRACTISING NOW" else "Tap to switch",
                trailing = {
                    if (active) {
                        Icon(RootIcons.Check, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.tertiary)
                    } else {
                        TextButton(onClick = { onRemove(language) }) {
                            Text("Remove", modifier = Modifier.semantics { contentDescription = "Remove ${language.name}" })
                        }
                    }
                },
            )
        }

        Spacer(Modifier.height(12.dp))
        Text("ADD A LANGUAGE", style = RootType.label, color = MaterialTheme.colorScheme.tertiary)
        others.forEach { language ->
            val locked = isLocked(language)
            LanguageCard(
                language = language,
                selected = false,
                onClick = { onChoose(language) },
                caption = MyLanguages.about(language.name) + if (locked) " Part of Root Premium." else "",
                trailing = {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            if (locked) RootIcons.Lock else RootIcons.Plus,
                            contentDescription = if (locked) "Locked" else "Add",
                            modifier = Modifier.padding(8.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        }
        TextButton(onClick = onAddOwn, modifier = Modifier.fillMaxWidth()) {
            Icon(RootIcons.Plus, null, Modifier.size(18.dp))
            Text("Add a language of your own, and its first word", Modifier.padding(start = 8.dp).weight(1f))
        }
        Text(
            "Removing a language only takes it off this list. Its words and practice stay on your phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onClose) { Text("Back to practice") }
    }
}

@Composable
private fun LanguageCard(
    language: LanguageEntity,
    selected: Boolean,
    onClick: () -> Unit,
    caption: String,
    trailing: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.Button, onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(language.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    caption,
                    style = if (selected) RootType.label else MaterialTheme.typography.bodySmall,
                    color = if (selected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            trailing()
        }
    }
}
