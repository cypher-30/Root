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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.root.app.data.ContentAccess
import com.root.app.overview.OverviewRecommendations
import com.root.app.archive.ArchiveScreen
import com.root.app.audio.WaveformCache
import com.root.app.content.PackManifest
import com.root.app.data.ContributionDraftEntity
import com.root.app.reels.AudioSessionReelPort
import com.root.app.reels.ReelAudioSource
import com.root.app.reels.ReelClip
import com.root.app.reels.ReelsPlayer
import com.root.app.teach.ContentViewModel
import com.root.app.teach.LessonAudioSource
import com.root.app.teach.LessonViewModel
import com.root.app.teach.UnitDetailState
import com.root.app.ui.*
import com.root.app.ui.audio.rememberRootAudioSession
import com.root.app.ui.icon.RootIcons
import com.root.app.ui.launch.LaunchScreen
import com.root.app.ui.teach.TeachCatalogScreen
import com.root.app.ui.teach.TeachLessonScreen
import com.root.app.ui.teach.TeachUnitDetailScreen
import com.root.app.ui.theme.RootTheme
import com.root.app.ui.theme.RootType
import kotlinx.coroutines.CancellationException
import java.io.File

/** Result of reading an installed unit's manifest for a navigation-owned screen. */
private sealed interface ManifestLoad {
    data object Loading : ManifestLoad
    data object Missing : ManifestLoad
    data object Failed : ManifestLoad
    data class Ready(val manifest: PackManifest) : ManifestLoad
}

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
                    else {
                        Box(Modifier.fillMaxSize()) {
                            // While onboarding covers the app, screen readers must not reach the screen beneath it.
                            Box(if (vm.showOnboarding) Modifier.fillMaxSize().clearAndSetSemantics {} else Modifier.fillMaxSize()) {
                                RootNavigation(vm, widgetRequest, onClose = { finish() })
                            }
                            if (vm.showOnboarding) {
                                OnboardingScreen(onRespond = vm::respondToOnboarding)
                            }
                        }
                    }
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
    var selectedDraftId by rememberSaveable { mutableStateOf<String?>(null) }
    // Selected teaching unit/lesson for the "learn" routes below. Kept as simple
    // saveable state (matching the "invite"/"contribute" routes' pattern) rather
    // than NavHost path arguments, since this integration deliberately stays
    // minimal and coexists with the existing home/packs navigation untouched.
    var selectedUnitId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedLessonId by rememberSaveable { mutableStateOf<String?>(null) }
    val contentVm: ContentViewModel = viewModel(factory = ContentViewModel.factory())
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbar = remember { SnackbarHostState() }
    var resumeCount by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.refreshAccess()
                resumeCount++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var recommendations by remember { mutableStateOf<List<OverviewRecommendations.Recommendation>>(emptyList()) }
    var latestDraft by remember { mutableStateOf<ContributionDraftEntity?>(null) }
    LaunchedEffect(
        vm.loading, vm.activeLanguage?.id, vm.current?.id, vm.canPracticeMore,
        vm.challenge?.id, vm.challenge?.completed, vm.rows.size, vm.contributionDraftVersion, resumeCount,
    ) {
        if (!vm.loading) {
            try {
                recommendations = OverviewRecommendations.recommend(vm.overviewSnapshot())
                latestDraft = vm.latestOpenContributionDraft()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                android.util.Log.w("Root", "Recommendations could not refresh", e)
            }
        }
    }
    LaunchedEffect(widgetRequest) {
        if (widgetRequest > 0) {
            vm.startSession()
            nav.navigate("home") { popUpTo("home") { inclusive = true }; launchSingleTop = true }
        }
    }
    LaunchedEffect(vm.error) { vm.error?.let { snackbar.showSnackbar(it); vm.clearError() } }
    LaunchedEffect(contentVm.error) { contentVm.error?.let { snackbar.showSnackbar(it); contentVm.clearError() } }
    fun open(route: String) { more = false; nav.navigate(route) { launchSingleTop = true } }
    fun openFreshContribute() { selectedDraftId = null; open("contribute") }
    fun openRecommendations() { open("recommendations") }
    fun goHome() { more = false; nav.popBackStack("home", false) }
    fun handleRecommendation(kind: OverviewRecommendations.Kind) {
        when (kind) {
            OverviewRecommendations.Kind.PRACTICE_DUE -> {
                goHome()
                if (vm.current == null) vm.startSession()
            }
            OverviewRecommendations.Kind.CONTINUE_PRACTICING -> {
                goHome()
                vm.continuePractice()
            }
            OverviewRecommendations.Kind.WEEKLY_CHALLENGE -> goHome()
            OverviewRecommendations.Kind.RESUME_DRAFT -> {
                latestDraft?.id?.let { id ->
                    selectedDraftId = id
                    open("contribute")
                }
            }
            OverviewRecommendations.Kind.ADD_A_WORD -> openFreshContribute()
            OverviewRecommendations.Kind.EXPLORE_PACKS -> open("packs")
        }
    }
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
                        if (vm.loadFailed) {
                            OutlinedButton(onClick = { vm.load() }, shape = MaterialTheme.shapes.small) { Text("Try again") }
                        } else {
                            Text("Add a phrase you know to start practicing.", style = MaterialTheme.typography.bodyLarge)
                        }
                        TextButton(onClick = { openFreshContribute() }) { Text("Add your first word") }
                    }
                    vm.current != null -> {
                        val phrase = vm.current!!
                        val lastPracticedAt by produceState<Long?>(
                            initialValue = null,
                            key1 = phrase.id,
                            key2 = vm.practiceMarkVersion,
                        ) {
                            value = vm.lastPracticedMarkAt(phrase.id)
                        }
                        PracticeScreen(phrase, vm.activeLanguage!!.name, vm.correct, vm.turn,
                            onRate = { vm.rate(phrase.id, it) }, onMore = { more = true },
                            // "Stop for now" durably ends the run in Room without
                            // navigating away — the next composition falls through
                            // to the completed branch below, same as running out
                            // of due phrases.
                            onTeach = { open("invite") }, onStop = { vm.stopSession() },
                            onMarkPracticed = { vm.markPracticed(it) },
                            lastPracticedAt = lastPracticedAt) {
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
                        onContinuePracticing = { vm.continuePractice() },
                        recommendations = recommendations,
                        onRecommendationClick = ::handleRecommendation)
                }
            }
            composable("recommendations") {
                RecommendationsScreen(
                    recommendations = recommendations,
                    onAction = { kind ->
                        nav.popBackStack()
                        handleRecommendation(kind)
                    },
                    onBack = { nav.popBackStack() },
                )
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
                    onContribute = { openFreshContribute() },
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
                val contributeVm: ContributeViewModel = viewModel()
                ContributeScreen(
                    vm = contributeVm,
                    requestedDraftId = selectedDraftId,
                    defaultLanguageName = vm.activeLanguage?.name ?: "",
                    activeLanguageId = vm.activeLanguage?.id,
                    onDraftsChanged = vm::contributionDraftsChanged,
                    onCommit = { language, prompt, answer, audio, speakerLabel, consentConfirmed, contributionDraftId ->
                        vm.contribute(language, prompt, answer, audio, speakerLabel, consentConfirmed, contributionDraftId)
                        selectedDraftId = null
                        nav.popBackStack("home", false)
                    },
                    onBack = {
                        selectedDraftId = null
                        nav.popBackStack()
                    },
                )
            }
            composable("archive") {
                val language = vm.activeLanguage
                if (language != null) {
                    ArchiveScreen(
                        languageId = language.id,
                        languageName = language.name,
                        onBack = { nav.popBackStack() },
                    )
                } else if (vm.loading) {
                    RouteLoading("Gathering your words…")
                } else {
                    RouteMessage(
                        title = "No language chosen yet.",
                        body = "Pick or add a language first, then its words will appear here.",
                        onBack = { nav.popBackStack() },
                    )
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
                if (unitId != null && row == null && contentVm.loading) {
                    RouteLoading("Loading unit…")
                } else if (unitId == null || row == null) {
                    RouteMessage(
                        title = "This unit isn't available.",
                        body = "It may have been removed from the catalog. Choose another unit.",
                        onBack = { nav.popBackStack() },
                    )
                } else {
                    // Lesson bodies are loaded lazily (a manifest read) the first
                    // time this unit's detail screen opens, and again whenever a
                    // download/update changes the installed revision.
                    LaunchedEffect(unitId, row.pack.installedVersion) { contentVm.loadDetail(unitId) }
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
                        onPlayReels = if (row.pack.phraseCount > 0 && row.pack.installedVersion != null) {
                            { nav.navigate("reels") }
                        } else null,
                    )
                }
            }
            composable("reels") {
                val unitId = selectedUnitId
                val row = unitId?.let { id -> contentVm.row(id) }
                val installedVersion = row?.pack?.installedVersion
                if (unitId != null && row == null && contentVm.loading) {
                    RouteLoading("Loading reel…")
                } else if (unitId == null || row == null || installedVersion == null) {
                    RouteMessage(
                        title = "This reel isn't available.",
                        body = "Download this unit to listen to its recordings.",
                        onBack = { nav.popBackStack() },
                    )
                } else {
                    val context = LocalContext.current.applicationContext
                    // Lifecycle-owned: backgrounding interrupts playback, which the
                    // player treats as a stop; nothing resumes on its own.
                    val reelSession = rememberRootAudioSession()
                    var manifestAttempt by remember(unitId, installedVersion) { mutableIntStateOf(0) }
                    var manifestState by remember(unitId, installedVersion) { mutableStateOf<ManifestLoad>(ManifestLoad.Loading) }
                    LaunchedEffect(unitId, installedVersion, manifestAttempt) {
                        manifestState = ManifestLoad.Loading
                        manifestState = try {
                            contentVm.manifest(unitId)?.let { ManifestLoad.Ready(it) } ?: ManifestLoad.Missing
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            ManifestLoad.Failed
                        }
                    }
                    when (val load = manifestState) {
                        ManifestLoad.Loading -> RouteLoading("Loading reel…")
                        ManifestLoad.Missing -> RouteMessage(
                            title = "This reel isn't ready yet.",
                            body = "This unit's content isn't installed on this device.",
                            onBack = { nav.popBackStack() },
                        )
                        ManifestLoad.Failed -> RouteMessage(
                            title = "This reel couldn't be opened.",
                            body = "The unit's files couldn't be read. Try again, or remove and download the unit again.",
                            onBack = { nav.popBackStack() },
                            onRetry = { manifestAttempt++ },
                        )
                        is ManifestLoad.Ready -> {
                            val manifest = load.manifest
                            val clips = remember(manifest, unitId, installedVersion) {
                                val resolver = contentVm.audioResolver(unitId)
                                manifest.phrases.map { phrase ->
                                    val source = phrase.audioAssetId
                                        ?.takeIf { assetId -> contentVm.assetAvailable(unitId, installedVersion, assetId) }
                                        ?.let { assetId ->
                                            when (val path = resolver.resolve(assetId)) {
                                                is LessonAudioSource.Bundled -> ReelAudioSource.Bundled(path.assetPath)
                                                is LessonAudioSource.DownloadedFile -> ReelAudioSource.DownloadedFile(path.absolutePath)
                                                is LessonAudioSource.Unavailable -> null
                                            }
                                        }
                                    ReelClip(
                                        id = phrase.id,
                                        label = phrase.prompt + if (phrase.meaning.isNotBlank()) " (${phrase.meaning})" else "",
                                        credits = phrase.credits,
                                        source = source,
                                    )
                                }
                            }
                            val player = remember(clips, reelSession) { ReelsPlayer(clips, AudioSessionReelPort(reelSession)) }
                            val waveforms = remember(context) { WaveformCache(context) }
                            key(manifest.id, manifest.version) {
                                ReelsScreen(
                                    title = row.pack.title,
                                    player = player,
                                    onBack = { nav.popBackStack() },
                                    loadWaveform = { clip ->
                                        val cacheKey = "$unitId:$installedVersion:${clip.id}"
                                        when (val source = clip.source) {
                                            is ReelAudioSource.DownloadedFile -> waveforms.getOrDecode(cacheKey, File(source.absolutePath))
                                            is ReelAudioSource.Bundled -> waveforms.getOrDecodeAsset(cacheKey, source.assetPath)
                                            null -> null
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
            composable("learnLesson") {
                val lessonId = selectedLessonId
                val unitId = selectedUnitId
                val row = unitId?.let { contentVm.row(it) }
                // Defensive reload: a cold process resume can land directly on
                // this route (SavedStateHandle-restored selection) before the
                // catalog rows hydrate, so reload once the row exists.
                LaunchedEffect(unitId, row != null) { if (unitId != null && row != null) contentVm.loadDetail(unitId) }
                val lesson = row?.lessons?.firstOrNull { it.id == lessonId }
                val packVersion = row?.pack?.installedVersion
                val detail = unitId?.let { contentVm.detailState(it) }
                if (lessonId == null || unitId == null || lesson == null || packVersion == null) {
                    val stillLoading = unitId != null && lessonId != null &&
                        (row == null && contentVm.loading || row != null && detail == UnitDetailState.LOADING)
                    if (stillLoading) {
                        RouteLoading("Opening lesson…")
                    } else if (detail == UnitDetailState.FAILED && unitId != null) {
                        RouteMessage(
                            title = "This lesson couldn't be opened.",
                            body = "The unit's files couldn't be read. Try again, or remove and download the unit again.",
                            onBack = { nav.popBackStack() },
                            onRetry = { contentVm.loadDetail(unitId) },
                        )
                    } else {
                        RouteMessage(
                            title = "This lesson isn't available.",
                            body = "It isn't part of the unit installed on this device. Your earlier progress is kept.",
                            onBack = { nav.popBackStack() },
                        )
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
                OverviewRecommendationList(
                    recommendations = recommendations,
                    onRecommendationClick = ::handleRecommendation,
                    modifier = Modifier.padding(top = 20.dp, bottom = 16.dp),
                )
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                MenuEntry("What to do next") { openRecommendations() }
                MenuEntry("Browse phrase packs") { open("packs") }
                MenuEntry("Learn a teaching unit") { open("learn") }
                MenuEntry("Unlock more words") { open("paywall") }
                MenuEntry("Teach someone one word") { open("invite") }
                MenuEntry("Add a word of your own") { openFreshContribute() }
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
                MenuEntry("How Root works") { more = false; vm.showOnboardingWalkthrough() }
                Text("No account. Your practice stays on this device.", style = RootType.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (BuildConfig.SHIP_SAMPLE_CONTENT) {
                    Text("Dholuo phrases are development samples. Shona and Swahili Greetings are source-checked against Omniglot; Amharic Greetings/Directions against the 1964 FSI course. Native-speaker review and reference audio are still pending for all four.",
                        Modifier.padding(top = 8.dp), style = RootType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
                MenuEntry("Add a language and its first word") { languagePicker = false; openFreshContribute() }
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
