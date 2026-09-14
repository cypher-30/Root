package com.lugha.app

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
import com.lugha.app.data.AppDatabase
import com.lugha.app.data.Scheduler
import com.lugha.app.data.SeedData
import com.lugha.app.ui.PaywallScreen
import com.lugha.app.ui.PracticeScreen
import com.lugha.app.ui.theme.LughaTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.get(applicationContext)
        lifecycleScope.launch { SeedData.seedIfEmpty(db) }

        setContent {
            LughaTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            onPractice = { navController.navigate("practice") },
                            onUnlock = { navController.navigate("paywall") },
                        )
                    }
                    composable("practice") {
                        var phrases by remember { mutableStateOf(emptyList<com.lugha.app.data.PhraseEntity>()) }
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            phrases = db.attemptDao().observeDueToday(System.currentTimeMillis()).first()
                        }
                        PracticeScreen(
                            phrases = phrases,
                            onRate = { phrase, confidence ->
                                lifecycleScope.launch {
                                    db.attemptDao().insert(
                                        com.lugha.app.data.AttemptEntity(
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
private fun HomeScreen(onPractice: () -> Unit, onUnlock: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Lugha")
            Text("Practice today's due phrases, or unlock every pack.")
            Button(onClick = onPractice) { Text("Practice") }
            Button(onClick = onUnlock) { Text("Unlock all packs") }
        }
    }
}
