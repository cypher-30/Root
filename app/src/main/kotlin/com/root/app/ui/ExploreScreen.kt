package com.root.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.root.app.audio.RootAudioSession
import com.root.app.data.ContentAccess
import com.root.app.data.ContributionDraftEntity
import com.root.app.data.LanguageEntity
import com.root.app.data.PackEntity
import com.root.app.data.PhraseEntity
import com.root.app.data.PremiumAccess
import com.root.app.data.SeedAudio
import com.root.app.explore.ExploreContent
import com.root.app.ui.audio.rememberRootAudioSession
import com.root.app.ui.icon.RootIcons
import com.root.app.ui.navigation.RootTabHeader
import com.root.app.ui.theme.RootType

/** The three ways into Explore. Learn is the lesson path; Explore is for browsing. */
enum class ExploreSection(val label: String) { SITUATIONS("Situations"), STORIES("Stories & culture"), NOTEBOOK("My notebook") }

/**
 * The Explore tab: browse the active language instead of following a path.
 * Situations lists every phrase set with its phrases (save any to the
 * notebook, or practice the set); Stories & culture tells short scenes with
 * starter phrases plus culture notes; My notebook gathers saved phrases, your
 * own words, and drafts. Search is on-device and scoped to the open section.
 * Phrases with a real recording (see [SeedAudio]) get a Listen button and a credit.
 * The language is chosen only in Profile. Locked phrase text is never shown.
 */
@Composable
fun ExploreScreen(
    language: LanguageEntity?,
    packRows: List<PackRow>,
    premium: PremiumAccess,
    rewardUnlocked: Boolean,
    loadPhrases: suspend (String) -> List<PhraseEntity>,
    savedIds: Set<String>,
    savedPhrases: List<PhraseEntity>,
    onSetSaved: (phraseId: String, saved: Boolean) -> Unit,
    onPracticePack: (PackEntity) -> Unit,
    onUnlock: () -> Unit,
    onYourWords: () -> Unit,
    onContribute: () -> Unit,
    openDrafts: List<ContributionDraftEntity>,
    onOpenDraft: (String) -> Unit,
    onProfileClick: () -> Unit,
) {
    val languageName = language?.name ?: "your language"
    val audioSession = rememberRootAudioSession()
    val audio = remember(audioSession) { ExploreAudio(audioSession) }
    var section by rememberSaveable { mutableStateOf(ExploreSection.SITUATIONS) }
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val q = query.trim()
    val content = ExploreContent.forLanguage(language?.name)

    val sets = remember(packRows, language?.id) {
        packRows
            .filter { it.phraseCount > 0 && language != null && it.pack.id != ContentAccess.userPackId(language.id) }
            .sortedBy { it.pack.sortOrder }
    }
    fun isOpen(pack: PackEntity) = language != null && ContentAccess.canAccess(language, pack, premium, rewardUnlocked)
    val phrasesByPack by produceState(emptyMap<String, List<PhraseEntity>>(), sets, premium, rewardUnlocked) {
        value = sets.filter { isOpen(it.pack) }.associate { it.pack.id to loadPhrases(it.pack.id) }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            RootTabHeader(label = "EXPLORE", onProfileClick = onProfileClick, modifier = Modifier.widthIn(max = 760.dp).padding(horizontal = 24.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            CompositionLocalProvider(LocalExploreAudio provides audio) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 760.dp).fillMaxSize().testTag("explore-list"),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            ) {
                item {
                    Text("$languageName,\nday to day.", style = RootType.editorialTitle)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Browse at your own pace: phrases for everyday situations, short scenes, and culture notes. " +
                            "Save what you like to your notebook. Your lessons are in Learn.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(20.dp))
                    SectionTabs(section) { section = it; expanded = null }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search ${section.label.lowercase()}") },
                        leadingIcon = { Icon(RootIcons.Search, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                when (section) {
                    ExploreSection.SITUATIONS -> situations(
                        sets, q, content, phrasesByPack, ::isOpen, expanded, { expanded = if (expanded == it) null else it },
                        savedIds, onSetSaved, onPracticePack, onUnlock,
                    )
                    ExploreSection.STORIES -> stories(content, q, languageName)
                    ExploreSection.NOTEBOOK -> notebook(
                        languageName, q, savedPhrases, onSetSaved, onYourWords, onContribute, openDrafts, onOpenDraft,
                        onBrowse = { section = ExploreSection.SITUATIONS },
                    )
                }
                item { Spacer(Modifier.height(28.dp)) }
            }
            }
        }
    }
}

/** One player for the whole tab, so only one phrase recording plays at a time. */
private class ExploreAudio(private val session: RootAudioSession) {
    var playingId by mutableStateOf<String?>(null)
        private set

    fun toggle(phrase: PhraseEntity) {
        val source = phrase.audioAsset ?: return
        if (playingId == phrase.id) {
            session.stopPlayback()
            playingId = null
            return
        }
        playingId = phrase.id
        session.playClip(source, 1f) { if (playingId == phrase.id) playingId = null }
    }
}

private val LocalExploreAudio = staticCompositionLocalOf<ExploreAudio?> { null }

@Composable
private fun SectionTabs(selected: ExploreSection, onSelect: (ExploreSection) -> Unit) {
    Column {
        // Equal-width tabs so all three stay on screen on narrow phones and large font scales.
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ExploreSection.entries.forEach { entry ->
                val isSelected = entry == selected
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(entry) })
                        .padding(top = 10.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        entry.label.uppercase(),
                        style = RootType.label,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider(
                        Modifier.fillMaxWidth(),
                        thickness = 2.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surface,
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun matches(q: String, vararg fields: String?) = q.isEmpty() || fields.any { it?.contains(q, ignoreCase = true) == true }

private fun LazyListScope.emptyLine(text: String) = item {
    Text(text, Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun LazyListScope.situations(
    sets: List<PackRow>,
    q: String,
    content: ExploreContent.Language?,
    phrasesByPack: Map<String, List<PhraseEntity>>,
    isOpen: (PackEntity) -> Boolean,
    expanded: String?,
    onToggleExpanded: (String) -> Unit,
    savedIds: Set<String>,
    onSetSaved: (String, Boolean) -> Unit,
    onPracticePack: (PackEntity) -> Unit,
    onUnlock: () -> Unit,
) {
    val visible = sets.mapNotNull { row ->
        val blurb = content?.situations?.get(row.pack.id)
        val phrases = phrasesByPack[row.pack.id].orEmpty()
        val hits = phrases.filter { matches(q, it.prompt, it.answer) }
        when {
            q.isEmpty() || matches(q, row.pack.theme, blurb) -> Triple(row, blurb, phrases)
            hits.isNotEmpty() -> Triple(row, blurb, hits)
            else -> null
        }
    }
    if (visible.isEmpty()) {
        emptyLine(if (q.isEmpty()) "There are no phrase sets for this language yet." else "Nothing matches \"$q\".")
        return
    }
    items(visible, key = { "set-" + it.first.pack.id }) { (row, blurb, phrases) ->
        val open = isOpen(row.pack)
        SituationCard(
            row = row,
            blurb = blurb,
            open = open,
            expanded = expanded == row.pack.id || q.isNotEmpty(),
            phrases = phrases,
            savedIds = savedIds,
            onToggle = { onToggleExpanded(row.pack.id) },
            onSetSaved = onSetSaved,
            onPractice = { onPracticePack(row.pack) },
            onUnlock = onUnlock,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun SituationCard(
    row: PackRow,
    blurb: String?,
    open: Boolean,
    expanded: Boolean,
    phrases: List<PhraseEntity>,
    savedIds: Set<String>,
    onToggle: () -> Unit,
    onSetSaved: (String, Boolean) -> Unit,
    onPractice: () -> Unit,
    onUnlock: () -> Unit,
) {
    val count = "${row.phraseCount} ${if (row.phraseCount == 1) "phrase" else "phrases"}"
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Hide phrases" else "Show phrases", onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.pack.theme, style = RootType.editorialTitle)
                blurb?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text(
                    if (open) count else "$count · Premium",
                    style = RootType.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (open) RootIcons.ChevronRight else RootIcons.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            if (!open) {
                Text(
                    "Part of this language's Premium: reviewed and recorded by native speakers.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onUnlock) { Text("About Premium") }
            } else {
                phrases.forEach { phrase ->
                    PhraseLine(phrase, saved = phrase.id in savedIds, onSetSaved = { onSetSaved(phrase.id, it) })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onPractice, shape = MaterialTheme.shapes.small) { Text("Practice this set") }
            }
        }
    }
}

@Composable
private fun PhraseLine(phrase: PhraseEntity, saved: Boolean, onSetSaved: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = saved, role = Role.Checkbox, onValueChange = onSetSaved)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(phrase.answer, style = MaterialTheme.typography.titleMedium)
            Text(phrase.prompt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SeedAudio.creditFor(phrase.audioAsset)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val audio = LocalExploreAudio.current
        if (audio != null && !phrase.audioAsset.isNullOrBlank()) {
            val playing = audio.playingId == phrase.id
            TextButton(onClick = { audio.toggle(phrase) }) {
                Text(if (playing) "Stop" else "Listen", style = RootType.meta)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(
                if (saved) RootIcons.Check else RootIcons.Plus,
                contentDescription = null,
                tint = if (saved) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                if (saved) "Saved" else "Save",
                style = RootType.meta,
                color = if (saved) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun LazyListScope.stories(content: ExploreContent.Language?, q: String, languageName: String) {
    if (content == null) {
        emptyLine("There are no stories or culture notes for $languageName yet.")
        return
    }
    val stories = content.stories.filter { story ->
        matches(q, story.title, story.setting) || story.lines.any { line ->
            matches(q, line.narration) || ExploreContent.phrase(line)?.let { matches(q, it.answer, it.prompt) } == true
        }
    }
    val notes = content.notes.filter { matches(q, it.title, it.body) }
    if (stories.isEmpty() && notes.isEmpty()) {
        emptyLine("Nothing matches \"$q\".")
        return
    }
    item {
        Text(
            "Scenes use only the starter phrases, which are checked against public phrasebooks. " +
                "Nothing here has been reviewed by native speakers yet.",
            Modifier.padding(vertical = 12.dp),
            style = RootType.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (stories.isNotEmpty()) {
        item { SectionLabel("STORIES") }
        items(stories, key = { "story-" + it.id }) { story ->
            StoryCard(story)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
    if (notes.isNotEmpty()) {
        item { Spacer(Modifier.height(12.dp)); SectionLabel("CULTURE NOTES") }
        items(notes, key = { "note-" + it.id }) { note ->
            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(note.title, style = RootType.editorialTitle)
                Text(note.body, style = MaterialTheme.typography.bodyLarge)
                Text("Source: ${note.source}", style = RootType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, Modifier.padding(top = 8.dp, bottom = 4.dp), style = RootType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StoryCard(story: ExploreContent.Story) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(story.title, style = RootType.editorialTitle)
            Text(story.setting, style = RootType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        story.lines.forEach { line ->
            val phrase = ExploreContent.phrase(line)
            if (phrase == null) {
                Text(line.narration.orEmpty(), style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
            } else {
                Column(Modifier.padding(start = 16.dp)) {
                    line.speaker?.let {
                        Text(it.uppercase(), style = RootType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(phrase.answer, style = MaterialTheme.typography.titleMedium)
                    Text(phrase.prompt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text("Phrases: ${story.source.credit}", style = RootType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun LazyListScope.notebook(
    languageName: String,
    q: String,
    savedPhrases: List<PhraseEntity>,
    onSetSaved: (String, Boolean) -> Unit,
    onYourWords: () -> Unit,
    onContribute: () -> Unit,
    openDrafts: List<ContributionDraftEntity>,
    onOpenDraft: (String) -> Unit,
    onBrowse: () -> Unit,
) {
    item {
        Spacer(Modifier.height(8.dp))
        SectionLabel("SAVED PHRASES")
    }
    val saved = savedPhrases.filter { matches(q, it.prompt, it.answer) }
    if (savedPhrases.isEmpty()) {
        item {
            Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Nothing saved yet. Tap Save next to any phrase in Situations to keep it here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onBrowse) { Text("Browse situations") }
            }
        }
    } else if (saved.isEmpty()) {
        emptyLine("No saved phrases match \"$q\".")
    } else {
        items(saved, key = { "saved-" + it.id }) { phrase ->
            PhraseLine(phrase, saved = true, onSetSaved = { onSetSaved(phrase.id, it) })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
    item {
        Spacer(Modifier.height(16.dp))
        SectionLabel("YOUR OWN WORDS")
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        NotebookEntry("Your words in $languageName", onYourWords)
        NotebookEntry("Add a word of your own", onContribute)
    }
    val drafts = openDrafts.filter { matches(q, it.answerDraft, it.promptDraft) }
    if (drafts.isNotEmpty()) {
        item { Spacer(Modifier.height(12.dp)); SectionLabel("OPEN DRAFTS") }
        items(drafts, key = { "draft-" + it.id }) { draft ->
            NotebookEntry(draft.answerDraft.ifBlank { "Untitled draft" }) { onOpenDraft(draft.id) }
        }
    }
}

@Composable
private fun NotebookEntry(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Icon(RootIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}
