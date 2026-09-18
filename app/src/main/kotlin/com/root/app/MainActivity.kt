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
import com.root.app.ui.*
import com.root.app.ui.icon.RootIcons
import com.root.app.ui.launch.LaunchScreen
import com.root.app.ui.theme.RootTheme
import com.root.app.ui.theme.RootType

/**
 * Single-activity host. Owns the Compose content root, dark/light resolution (a
 * manual preference overrides the system setting), status/navigation bar icon
 * contrast, and the widget's "open practice" deep-link signal via [onNewIntent].
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
                            onTeach = { open("invite") }) {
                            WeeklyChallengeCard(vm.challenge) { vm.completeChallenge() }
                        }
                    }
                    else -> SessionCompleteScreen(vm.correct, vm.capability, vm.completed,
                        vm.activeLanguage!!.name, vm.challenge, { vm.completeChallenge() }, { more = true }, onClose,
                        onRefresh = { vm.startSession() })
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
                    onSave = { language, prompt, answer, audio ->
                        vm.contribute(language, prompt, answer, audio)
                        nav.popBackStack("home", false)
                    },
                    onBack = { nav.popBackStack() })
            }
            composable("study") {
                DesignStudyScreen({ nav.popBackStack() }, { nav.navigate("launch-study") })
            }
            composable("launch-study") {
                LaunchScreen { nav.popBackStack("study", false) }
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
                MenuEntry("Unlock more words") { open("paywall") }
                MenuEntry("Teach someone one word") { open("invite") }
                MenuEntry("Add a word of your own") { open("contribute") }
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
