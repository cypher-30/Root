package com.root.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.root.app.data.ConfidenceLevel
import com.root.app.data.PhraseEntity

/**
 * The core loop: prompt -> reveal -> report the outcome. Missed/Close/Got it, not a
 * felt-confidence scale — the learner reports what actually happened, which is a far
 * better-calibrated signal than a subjective "how well do I know this" judgment made
 * right after seeing the answer (see Entities.kt's ConfidenceLevel doc comment).
 */
private fun ConfidenceLevel.displayLabel(): String = when (this) {
    ConfidenceLevel.MISSED -> "Missed it"
    ConfidenceLevel.CLOSE -> "Close"
    ConfidenceLevel.GOT_IT -> "Got it"
}
@Composable
fun PracticeScreen(
    phrases: List<PhraseEntity>,
    onRate: (PhraseEntity, ConfidenceLevel) -> Unit,
    onSessionComplete: () -> Unit,
) {
    var index by remember { mutableIntStateOf(0) }
    var revealed by remember { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (phrases.isEmpty()) {
                Text("Nothing due right now — come back later.")
                return@Column
            }
            if (index >= phrases.size) {
                Text("Session complete.")
                Button(onClick = onSessionComplete) { Text("Done") }
                return@Column
            }

            val current = phrases[index]
            Text(current.prompt)

            if (revealed) {
                Text(current.answer)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ConfidenceLevel.entries.forEach { level ->
                        Button(onClick = {
                            onRate(current, level)
                            revealed = false
                            index += 1
                        }) {
                            Text(level.displayLabel())
                        }
                    }
                }
            } else {
                Button(onClick = { revealed = true }) { Text("Reveal") }
            }
        }
    }
}
