package com.root.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.root.app.data.AppDatabase
import com.root.app.data.Scheduler
import com.root.app.data.SeedData
import com.root.app.data.WeeklyChallengeEntity
import com.root.app.ui.PackRow
import com.root.app.ui.PacksScreen
import com.root.app.ui.PaywallScreen
import com.root.app.ui.PracticeScreen
import com.root.app.ui.theme.RootTheme
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.get(applicationContext)
        lifecycleScope.launch { SeedData.seedIfEmpty(db) }

        setContent {
            RootTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {
                        var challenge by remember { mutableStateOf<WeeklyChallengeEntity?>(null) }
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            challenge = currentOrNewWeeklyChallenge(db)
                        }
                        HomeScreen(
                            challenge = challenge,
                            onPractice = { navController.navigate("practice") },
                            onBrowsePacks = { navController.navigate("packs") },
                            onUnlock = { navController.navigate("paywall") },
                            onMarkChallengeDone = {
                                challenge?.let { current ->
                                    lifecycleScope.launch {
                                        db.challengeDao().markCompleted(current.id, System.currentTimeMillis())
                                        challenge = current.copy(completed = true)
                                    }
                                }
                            },
                        )
                    }
                    composable("packs") {
                        var rows by remember { mutableStateOf(emptyList<PackRow>()) }
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            val packs = db.packDao().observeForLanguage("lang-dholuo").first()
                            rows = packs.map { PackRow(it, db.phraseDao().countForPack(it.id)) }
                        }
                        PacksScreen(
                            languageName = "Dholuo",
                            rows = rows,
                            onPackClick = { pack ->
                                if (pack.isFree) navController.navigate("practice") else navController.navigate("paywall")
                            },
                        )
                    }
                    composable("practice") {
                        var phrases by remember { mutableStateOf(emptyList<com.root.app.data.PhraseEntity>()) }
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            phrases = db.attemptDao().observeDueToday(System.currentTimeMillis()).first()
                        }
                        PracticeScreen(
                            phrases = phrases,
                            onRate = { phrase, confidence ->
                                lifecycleScope.launch {
                                    db.attemptDao().insert(
                                        com.root.app.data.AttemptEntity(
                                            phraseId = phrase.id,
                                            confidence = confidence,
                                            nextDueAt = Scheduler.nextDueAt(confidence),
                                        )
                                    )
                                }
                            },
                            onSessionComplete = { navController.popBackStack() },
                        )
                    }
                    composable("paywall") {
                        PaywallScreen(onUnlocked = { navController.popBackStack() })
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun HomeScreen(
    challenge: WeeklyChallengeEntity?,
    onPractice: () -> Unit,
    onBrowsePacks: () -> Unit,
    onUnlock: () -> Unit,
    onMarkChallengeDone: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Root")
            Text("Practice today's due phrases, or unlock every pack.")
            Button(onClick = onPractice) { Text("Practice") }
            Button(onClick = onBrowsePacks) { Text("Browse packs") }
            Button(onClick = onUnlock) { Text("Unlock all packs") }

            if (challenge != null) {
                Text("This week: try using a ${challenge.theme} phrase in a real conversation")
                if (challenge.completed) {
                    Text("Done — nice.")
                } else {
                    Button(onClick = onMarkChallengeDone) { Text("I did this") }
                }
            }
        }
    }
}

/** Loads this week's conversation challenge, or creates one if the week's rolled over.
 *  Not calendar-aligned (doesn't start on Monday) — just a fixed 7-day bucket since the
 *  Unix epoch, which is all "rotates weekly" actually needs and avoids pulling in
 *  java.time for a hackathon-scope feature. */
private suspend fun currentOrNewWeeklyChallenge(db: AppDatabase): WeeklyChallengeEntity {
    val weekMillis = TimeUnit.DAYS.toMillis(7)
    val weekStart = (System.currentTimeMillis() / weekMillis) * weekMillis
    db.challengeDao().getForWeek(weekStart)?.let { return it }

    val theme = db.attemptDao().mostRecentlyPracticedTheme()
        ?: db.packDao().observeForLanguage("lang-dholuo").first().firstOrNull { it.isFree }?.theme
        ?: "Greetings"
    val fresh = WeeklyChallengeEntity(weekStart = weekStart, theme = theme)
    db.challengeDao().upsert(fresh)
    return fresh
}
