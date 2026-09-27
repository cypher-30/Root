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
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
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
import com.root.app.ui.audio.CollectSoundEvents
import com.root.app.ui.audio.LocalRootSounds
import com.root.app.ui.audio.ReserveSpeech
import com.root.app.ui.audio.SoundLabScreen
import com.root.app.ui.audio.SoundSettingsScreen
import com.root.app.ui.audio.rememberRootSoundPlayer
import com.root.app.audio.RootSoundCue
import com.root.app.audio.SoundRequest
import com.root.app.ui.icon.RootIcons
import com.root.app.ui.launch.LaunchScreen
import com.root.app.ui.navigation.RootBottomBar
import com.root.app.ui.navigation.RootDestination
import com.root.app.ui.teach.LearnPathScreen
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
        // The optional startup motif belongs to a fresh, normal launch only:
        // never after recreation/restoration or a widget shortcut into practice.
        val startupSoundEligible = savedInstanceState == null && !intent.getBooleanExtra("root.openPractice", false)
        setContent {
            val vm: RootViewModel = viewModel()
            val sounds = rememberRootSoundPlayer { vm.soundSettings }
            val dark = when (vm.theme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            CompositionLocalProvider(LocalRootSounds provides sounds) {
            RootTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize()) {
                    if (!vm.launched) {
                        // Skipping (or leaving) the introduction fades the motif out.
                        DisposableEffect(Unit) { onDispose { sounds.stopIf(RootSoundCue.STARTUP_MOTIF, fade = true) } }
                        LaunchScreen(vm::finishLaunch, onGrowthStart = {
                            if (startupSoundEligible && !vm.startupSoundHandled) {
                                vm.startupSoundHandled = true
                                sounds.play(RootSoundCue.STARTUP_MOTIF, SoundRequest.STARTUP)
                            }
                        })
                    }
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
    var languagePicker by rememberSaveable { mutableStateOf(false) }
    var selectedDraftId by rememberSaveable { mutableStateOf<String?>(null) }
    // The language whose single-language plan the paywall offers (defaults to the active one).
    var paywallLanguage by rememberSaveable { mutableStateOf<String?>(null) }
    // Selected teaching unit/lesson for the "learn" routes below. Kept as simple
    // saveable state (matching the "invite"/"contribute" routes' pattern) rather
    // than NavHost path arguments, since this integration deliberately stays
    // minimal and coexists with the existing home/packs navigation untouched.
    var selectedUnitId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedLessonId by rememberSaveable { mutableStateOf<String?>(null) }
    val contentVm: ContentViewModel = viewModel(factory = ContentViewModel.factory())
    // Teaching units are catalogued across every language at once (see
    // ContentViewModel), but Learn/Explore must only ever show the language
    // currently being practiced — otherwise switching to a one-pack language
    // still shows every other language's units, which reads as a bug, not a
    // library. Matched by language row id; units whose revision isn't installed
    // yet carry no id, so they fall back to the display name.
    val activeLanguageUnits = contentVm.rows.filter { row ->
        val active = vm.activeLanguage
        active != null && (row.pack.languageId?.let { it == active.id } ?: (row.pack.languageName == active.name))
    }
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
    var openDrafts by remember { mutableStateOf<List<ContributionDraftEntity>>(emptyList()) }
    LaunchedEffect(
        vm.loading, vm.activeLanguage?.id, vm.current?.id, vm.canPracticeMore,
        vm.challenge?.id, vm.challenge?.completed, vm.rows.size, vm.contributionDraftVersion, resumeCount,
    ) {
        if (!vm.loading) {
            try {
                recommendations = OverviewRecommendations.recommend(vm.overviewSnapshot())
                openDrafts = vm.openContributionDrafts()
                latestDraft = openDrafts.firstOrNull()
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
            nav.navigate(RootDestination.Practice.route) {
                popUpTo(RootDestination.Practice.route) { inclusive = true }
                launchSingleTop = true
            }
        }
    }
    LaunchedEffect(vm.error) { vm.error?.let { snackbar.showSnackbar(it); vm.clearError() } }
    LaunchedEffect(contentVm.error) { contentVm.error?.let { snackbar.showSnackbar(it); contentVm.clearError() } }
    fun open(route: String) { nav.navigate(route) { launchSingleTop = true } }
    fun openPaywall(languageName: String? = vm.activeLanguage?.name) { paywallLanguage = languageName; open("paywall") }
    fun openFreshContribute() { selectedDraftId = null; open("contribute") }
    fun goHome() { nav.popBackStack(RootDestination.Practice.route, false) }
    fun openProfile() { open(RootDestination.Profile.route) }
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
            OverviewRecommendations.Kind.EXPLORE_PACKS -> open(RootDestination.Explore.route)
        }
    }

    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // Used only to highlight which tab is selected — the bar itself is always
    // visible and always overrides whatever screen is currently open (see
    // selectTab below), so a learner can jump straight to any tab from
    // anywhere instead of needing to back out of a detail screen first.
    val currentTab = RootDestination.fromRoute(currentRoute)

    fun selectTab(destination: RootDestination) {
        languagePicker = false
        if (destination == RootDestination.Practice) {
            // Home is the start destination: pop to it without saving. Saving a
            // non-inclusive pop keys the popped stack to Practice itself, so
            // restoreState would push e.g. a header-opened Profile straight back.
            nav.popBackStack(RootDestination.Practice.route, inclusive = false, saveState = false)
            return
        }
        nav.navigate(destination.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        bottomBar = { RootBottomBar(current = currentTab, onSelect = ::selectTab) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { scaffoldPadding ->
    Box(Modifier.fillMaxSize().padding(bottom = scaffoldPadding.calculateBottomPadding())) {
        NavHost(navController = nav, startDestination = RootDestination.Practice.route) {
            composable(RootDestination.Practice.route) {
                CollectSoundEvents(vm.soundEvents)
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
                            onRate = { vm.rate(phrase.id, it) }, onMore = { openProfile() },
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
                        vm.activeLanguage!!.name, vm.challenge, { vm.completeChallenge() }, { openProfile() },
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
            composable(RootDestination.Explore.route) {
                val language = vm.activeLanguage
                val savedIds by vm.savedPhraseIds.collectAsState(initial = emptySet())
                val savedFlow = remember(language?.id, vm.premium, vm.reward) {
                    language?.let { vm.savedPhrases(it.id) } ?: kotlinx.coroutines.flow.flowOf(emptyList())
                }
                val savedPhrases by savedFlow.collectAsState(initial = emptyList())
                ExploreScreen(
                    language = language,
                    packRows = vm.rows,
                    premium = vm.premium,
                    rewardUnlocked = vm.reward,
                    loadPhrases = vm::browsePhrases,
                    savedIds = savedIds,
                    savedPhrases = savedPhrases,
                    onSetSaved = { phraseId, saved -> vm.setPhraseSaved(phraseId, saved) },
                    onPracticePack = { pack ->
                        if (language != null && ContentAccess.canAccess(language, pack, vm.premium, vm.reward)) {
                            vm.startSession(pack.id)
                            goHome()
                        } else openPaywall()
                    },
                    onUnlock = { openPaywall() },
                    onYourWords = { open("archive") },
                    onContribute = { openFreshContribute() },
                    openDrafts = openDrafts.filter { it.languageId == language?.id },
                    onOpenDraft = { draftId -> selectedDraftId = draftId; open("contribute") },
                    onProfileClick = { openProfile() },
                )
            }
            composable("paywall") {
                PaywallScreen(
                    languageName = paywallLanguage ?: vm.activeLanguage?.name,
                    onUnlocked = { vm.refreshAccess(); nav.popBackStack() },
                    onBack = { nav.popBackStack() },
                )
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
                        goHome()
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
            composable(RootDestination.Learn.route) {
                // Unlike the old catalog list, the Learn tab shows every
                // installed unit's lesson path inline, so their manifests must
                // be loaded (not just on-demand from a detail screen) — still
                // skipped once a unit's lessons are already known, and never
                // for units that are not yet installed.
                LaunchedEffect(activeLanguageUnits.map { it.pack.id to it.pack.installedVersion }) {
                    activeLanguageUnits.forEach { row ->
                        if (row.pack.installedVersion != null && row.lessons.isEmpty()) contentVm.loadDetail(row.pack.id)
                    }
                }
                LearnPathScreen(
                    languageName = vm.activeLanguage?.name ?: "your language",
                    rows = activeLanguageUnits,
                    progressFor = { unitId -> contentVm.progressFor(unitId) },
                    onOpenLesson = { unitId, lesson ->
                        selectedUnitId = unitId; selectedLessonId = lesson.id; open("learnLesson")
                    },
                    onRemoveUnit = { unitId -> contentVm.uninstall(unitId) },
                    onPlayReels = { unitId -> selectedUnitId = unitId; open("reels") },
                    onDownload = { unitId -> contentVm.download(unitId) },
                    onExplore = { open(RootDestination.Explore.route) },
                    onProfileClick = { openProfile() },
                )
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
                            val reelSnapshot by player.snapshot.collectAsState()
                            ReserveSpeech(reelSnapshot.state is ReelsPlayer.State.Playing)
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
                        key = "lesson-$unitId-$lessonId",
                        factory = LessonViewModel.factory(
                            audioResolver = contentVm.audioResolver(unitId),
                            assetAvailable = { packId, version, assetId -> contentVm.assetAvailable(packId, version, assetId) },
                        ),
                    )
                    LaunchedEffect(lessonId) { lessonVm.start(unitId, packVersion, lessonId, lesson) }
                    CollectSoundEvents(lessonVm.soundEvents)
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
            composable(RootDestination.Profile.route) {
                ProfileScreen(
                    languageName = vm.activeLanguage?.name ?: "Choose",
                    capability = vm.capability,
                    correctThisSession = vm.correct,
                    theme = vm.theme,
                    onThemeChange = { vm.changeTheme(it) },
                    onLanguageClick = { languagePicker = true },
                    onUnlockMoreWords = { openPaywall() },
                    onShareAWord = { open("invite") },
                    onHowRootWorks = { vm.showOnboardingWalkthrough() },
                    soundSettings = vm.soundSettings,
                    onOpenSound = { open("sound") },
                )
            }
            composable("sound") {
                SoundSettingsScreen(
                    settings = vm.soundSettings,
                    onSettingsChange = vm::changeSoundSettings,
                    onOpenSoundLab = { open("soundLab") },
                    onBack = { nav.popBackStack() },
                )
            }
            composable("soundLab") {
                SoundLabScreen(onBack = { nav.popBackStack() })
            }
        }
    }
    }
    if (languagePicker) {
        ModalBottomSheet(onDismissRequest = { languagePicker = false }, shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surface, dragHandle = null) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
                Text("Your language, your words.", style = RootType.editorialTitle)
                Text("Every language's starter sets are free, and so is adding your own words.",
                    Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
                vm.languages.forEach { language ->
                    val locked = language.isPremium && !vm.premium.covers(language)
                    MenuEntry(language.name + if (locked) " · Locked" else "") {
                        languagePicker = false
                        if (locked && language.id !in vm.personalLanguageIds) openPaywall(language.name)
                        else { vm.selectLanguage(language); goHome() }
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
