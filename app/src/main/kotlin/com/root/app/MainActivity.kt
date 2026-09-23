package com.root.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.root.app.data.ReferralPrefs
import com.root.app.data.ContentAccess
import com.root.app.archive.ArchiveScreen
import com.root.app.teach.ContentViewModel
import com.root.app.teach.LessonViewModel
import com.root.app.ui.*
import com.root.app.ui.icon.RootIcons
import com.root.app.ui.launch.LaunchScreen
import com.root.app.ui.teach.TeachCatalogScreen
import com.root.app.ui.teach.TeachLessonScreen
import com.root.app.ui.teach.TeachUnitDetailScreen
import com.root.app.ui.theme.RootTheme
import com.root.app.ui.theme.RootType

/**
 * Single-activity host. Owns the Compose content root, dark/light resolution (a
 * manual preference overrides the system setting), status/navigation bar icon
 * contrast, and the widget's "open practice" deep-link signal, handled on both a
 * cold start (the initial [onCreate] intent) and a warm relaunch ([onNewIntent]).
 * All navigation and screen composition happens in [RootNavigation] below.
 */
class MainActivity : ComponentActivity() {
    // Bumped by onNewIntent when the home-screen widget requests practice; observed
    // by a LaunchedEffect in RootNavigation to start a session and jump to "home".
    private var widgetRequest by mutableIntStateOf(0)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("root.openPractice", false)) widgetRequest++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A cold start from the widget's tap delivers the "open practice" extra as
        // this Activity's *initial* intent - onNewIntent is only called for a warm
        // relaunch of an already-running task, so it alone silently drops a cold entry.
        if (intent.getBooleanExtra("root.openPractice", false)) widgetRequest++
        setContent {
            val vm: RootViewModel = viewModel()
            val dark = when (vm.theme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            RootTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize()) {
                    if (!vm.launched) LaunchScreen(vm::finishLaunch)
                    else RootNavigation(vm, widgetRequest, onClose = { finish() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
/**
 * The app's single NavHost plus its two modal bottom sheets (overflow menu and
 * language picker). Screens are simple stateless composables; all session, access,
 * and persistence state lives in [vm] and flows down as parameters/callbacks.
 */
@Composable
private fun RootNavigation(vm: RootViewModel, widgetRequest: Int, onClose: () -> Unit) {
    val nav = rememberNavController()
    var more by rememberSaveable { mutableStateOf(false) }
    var languagePicker by rememberSaveable { mutableStateOf(false) }
    // Selected teaching unit/lesson for the "learn" routes below. Kept as simple
    // saveable state (matching the "invite"/"contribute" routes' pattern) rather
    // than NavHost path arguments, since this integration deliberately stays
    // minimal and coexists with the existing home/packs navigation untouched.
    var selectedUnitId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedLessonId by rememberSaveable { mutableStateOf<String?>(null) }
    val contentVm: ContentViewModel = viewModel(factory = ContentViewModel.factory())
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbar = remember { SnackbarHostState() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.refreshAccess() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(widgetRequest) {
        if (widgetRequest > 0) {
            vm.startSession()
            nav.navigate("home") { popUpTo("home") { inclusive = true }; launchSingleTop = true }
        }
    }
    LaunchedEffect(vm.error) { vm.error?.let { snackbar.showSnackbar(it); vm.clearError() } }
    fun open(route: String) { more = false; nav.navigate(route) { launchSingleTop = true } }
    Box(Modifier.fillMaxSize()) {
        NavHost(navController = nav, startDestination = "home") {
            composable("home") {
                when {
                    vm.loading -> Column(Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
                        verticalArrangement = Arrangement.Center) {
                        Text("Gathering your words.", style = RootType.editorialTitle)
                    }
                    vm.loadFailed || vm.activeLanguage == null -> Column(Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
                        verticalArrangement = Arrangement.Center) {
                        Text("Your words belong here.", style = RootType.editorialTitle)
                        OutlinedButton(onClick = { vm.load() }, shape = MaterialTheme.shapes.small) { Text("Try again") }
                        TextButton(onClick = { open("contribute") }) { Text("Add your first word") }
                    }
                    vm.current != null -> {
                        val phrase = vm.current!!
                        PracticeScreen(phrase, vm.activeLanguage!!.name, vm.correct, vm.turn,
                            onRate = { vm.rate(phrase.id, it) }, onMore = { more = true },
                            // "Stop for now" durably ends the run in Room without
                            // navigating away — the next composition falls through
                            // to the completed branch below, same as running out
                            // of due phrases.
                            onTeach = { open("invite") }, onStop = { vm.stopSession() },
                            onMarkPracticed = { vm.markPracticed(it) }) {
                            WeeklyChallengeCard(vm.challenge) { vm.completeChallenge() }
                        }
                    }
                    else -> SessionCompleteScreen(vm.correct, vm.capability, vm.completed,
                        vm.activeLanguage!!.name, vm.challenge, { vm.completeChallenge() }, { more = true },
                        // Durably end the run before finishing the Activity (onClose),
                        // so a session can never look finished on screen while a run
                        // is still open in Room and silently resumable later.
                        { vm.closeSession(onClose) },
                        onRefresh = { vm.startSession() },
                        canPracticeMore = vm.canPracticeMore,
                        onContinuePracticing = { vm.continuePractice() })
                }
            }
            composable("packs") {
                PacksScreen(
                    languageName = vm.activeLanguage?.name ?: "Your language",
                    rows = vm.rows,
                    onPackClick = { pack ->
                        val hasContent = vm.rows.firstOrNull { it.pack.id == pack.id }?.phraseCount?.let { it > 0 } == true
                        if (hasContent) {
                            if (vm.activeLanguage?.let { ContentAccess.canAccess(it, pack, vm.premium, vm.reward) } == true) {
                                vm.startSession(pack.id)
                                nav.popBackStack("home", false)
                            } else open("paywall")
                        }
                    },
                    onBack = { nav.popBackStack() },
                    premium = vm.premium,
                    rewardUnlocked = vm.reward,
                    onLanguageClick = { languagePicker = true },
                    languagePremium = vm.activeLanguage?.isPremium == true,
                    onContribute = { open("contribute") },
                )
            }
            composable("paywall") {
                PaywallScreen(onUnlocked = { vm.refreshAccess(); nav.popBackStack() }, onBack = { nav.popBackStack() })
            }
            composable("invite") {
                InviteScreen(phrase = vm.sharePhrase, languageName = vm.activeLanguage?.name ?: "Your language",
                    onBack = { vm.refreshAccess(); nav.popBackStack() })
            }
            composable("contribute") {
                ContributeScreen(initialLanguageName = vm.activeLanguage?.name ?: "",
                    onSave = { language, prompt, answer, audio, speakerLabel, consentConfirmed ->
                        vm.contribute(language, prompt, answer, audio, speakerLabel, consentConfirmed)
                        nav.popBackStack("home", false)
                    },
                    onBack = { nav.popBackStack() })
            }
            composable("archive") {
                val language = vm.activeLanguage
                if (language != null) {
                    ArchiveScreen(
                        languageId = language.id,
                        languageName = language.name,
                        onBack = { nav.popBackStack() },
                    )
                } else {
                    nav.popBackStack()
                }
            }
            composable("study") {
                DesignStudyScreen({ nav.popBackStack() }, { nav.navigate("launch-study") })
            }
            composable("launch-study") {
                LaunchScreen { nav.popBackStack("study", false) }
            }
            composable("learn") {
                TeachCatalogScreen(
                    rows = contentVm.rows,
                    onOpenUnit = { unitId -> selectedUnitId = unitId; nav.navigate("learnUnit") },
                    onDownload = contentVm::download,
                    onRetry = contentVm::retry,
                    onUpdate = contentVm::update,
                    onBack = { nav.popBackStack() },
                )
            }
            composable("learnUnit") {
                val unitId = selectedUnitId
                val row = unitId?.let { id -> contentVm.row(id) }
                if (unitId == null || row == null) {
                    LaunchedEffect(Unit) { nav.popBackStack() }
                } else {
                    // Lesson bodies are loaded lazily (a manifest read) the first
                    // time this unit's detail screen opens; safe to call every
                    // time since it's a no-op once already merged into rows.
                    LaunchedEffect(unitId) { contentVm.loadDetail(unitId) }
                    TeachUnitDetailScreen(
                        pack = row.pack,
                        lessons = row.lessons,
                        onOpenLesson = { lesson -> selectedLessonId = lesson.id; nav.navigate("learnLesson") },
                        onBack = { nav.popBackStack() },
                        onDownload = { contentVm.download(unitId) },
                        onCancel = { contentVm.cancel(unitId) },
                        onUninstall = { contentVm.uninstall(unitId) },
                        onRetry = { contentVm.retry(unitId) },
                        onUpdate = { contentVm.update(unitId) },
                        isResumable = { lessonId -> lessonId in contentVm.resumableLessonIds },
                        // The linked recall phrases live in the pack sharing this
                        // unit's own id (PackEntity.id == PackManifest.id).
                        // startPackPractice durably switches the active
                        // language/pack itself — this must never be
                        // `vm.startSession(unitId)` while the active language
                        // stays on whatever was last practiced, since the unit's
                        // language and the current recall language can
                        // genuinely differ.
                        onReviewPhrases = if (row.pack.phraseCount > 0 && row.pack.installedVersion != null) {
                            {
                                vm.startPackPractice(unitId)
                                nav.navigate("home") { popUpTo("home") { inclusive = true }; launchSingleTop = true }
                            }
                        } else null,
                    )
                }
            }
            composable("learnLesson") {
                val lessonId = selectedLessonId
                val unitId = selectedUnitId
                val row = unitId?.let { contentVm.row(it) }
                // Defensive reload: a cold process resume can land directly on
                // this route (SavedStateHandle-restored selection) before the
                // unit-detail screen has ever fetched its manifest.
                LaunchedEffect(unitId) { if (unitId != null) contentVm.loadDetail(unitId) }
                val lesson = row?.lessons?.firstOrNull { it.id == lessonId }
                val packVersion = row?.pack?.installedVersion
                if (lessonId == null || unitId == null || lesson == null || packVersion == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Opening lesson…", style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    // Keyed per lesson so switching lessons never reuses another
                    // lesson's SavedStateHandle-held run ID.
                    val lessonVm: LessonViewModel = viewModel(
                        key = "lesson-$lessonId",
                        factory = LessonViewModel.factory(
                            audioResolver = contentVm.audioResolver(unitId),
                            assetAvailable = { packId, version, assetId -> contentVm.assetAvailable(packId, version, assetId) },
                        ),
                    )
                    LaunchedEffect(lessonId) { lessonVm.start(unitId, packVersion, lessonId, lesson) }
                    // Leaving this destination (back press, or navigating away)
                    // pauses the run — it is never silently reset or force-completed.
                    DisposableEffect(lessonId) { onDispose { lessonVm.pause() } }
                    TeachLessonScreen(
                        lesson = lesson,
                        run = lessonVm.run,
                        error = lessonVm.error,
                        audioSource = lessonVm::audioSource,
                        onChoice = lessonVm::submitChoice,
                        onTokens = lessonVm::submitTokens,
                        onSelfAssessed = lessonVm::submitSelfAssessment,
                        onReveal = lessonVm::revealSupport,
                        onAcknowledgeUnavailable = lessonVm::acknowledgeUnavailableAudio,
                        onContinue = lessonVm::continueStep,
                        onRestart = lessonVm::restart,
                        onBack = { nav.popBackStack() },
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding())
    }
    if (more) {
        ModalBottomSheet(onDismissRequest = { more = false }, shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surface, dragHandle = null) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("A little more Root.", style = RootType.editorialTitle, modifier = Modifier.weight(1f))
                    IconButton(onClick = { more = false }) { Icon(RootIcons.Close, "Close options") }
                }
                MenuEntry("Browse phrase packs") { open("packs") }
                MenuEntry("Learn a teaching unit") { open("learn") }
                MenuEntry("Unlock more words") { open("paywall") }
                MenuEntry("Teach someone one word") { open("invite") }
                MenuEntry("Add a word of your own") { open("contribute") }
                MenuEntry("Your words") { open("archive") }
                MenuEntry("Language · ${vm.activeLanguage?.name ?: "Choose"}") { more = false; languagePicker = true }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("PAPER & INK", style = RootType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("system", "light", "dark").forEach { value ->
                        OutlinedButton(onClick = { vm.changeTheme(value) }, modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.small, contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Text((if (value == vm.theme) "• " else "") + value.replaceFirstChar { it.uppercase() })
                        }
                    }
                }
                MenuEntry("The design study") { open("study") }
                Text("No account. Your practice stays on this device.", style = RootType.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Dholuo phrases are development samples. Shona Greetings is source-checked against Omniglot. Native-speaker review and reference audio are still pending for both.",
                    Modifier.padding(top = 8.dp), style = RootType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (languagePicker) {
        ModalBottomSheet(onDismissRequest = { languagePicker = false }, shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surface, dragHandle = null) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
                Text("Your language, your words.", style = RootType.editorialTitle)
                Text("Curated languages can be unlocked. Adding your own words is always free.",
                    Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
                vm.languages.forEach { language ->
                    MenuEntry(language.name + if (language.isPremium && !vm.premium) " · Locked" else "") {
                        languagePicker = false
                        if (language.isPremium && !vm.premium && language.id !in vm.personalLanguageIds) open("paywall")
                        else { vm.selectLanguage(language); nav.popBackStack("home", false) }
                    }
                }
                MenuEntry("Add a language and its first word") { languagePicker = false; open("contribute") }
                TextButton(onClick = { languagePicker = false }) { Text("Back to practice") }
            }
        }
    }
}

@Composable
private fun MenuEntry(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.small, contentPadding = PaddingValues(vertical = 10.dp)) {
        Text(label, modifier = Modifier.fillMaxWidth())
    }
}
