package com.root.app.ui.launch

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.root.app.ui.motion.RootMotion
import com.root.app.ui.root.RootPath
import com.root.app.ui.theme.RootType
import kotlinx.coroutines.delay

/**
 * The 3.2 s cold-open sequence: seed hold -> ordered root growth -> completed-mark
 * hold (see [RootMotion] for the exact millisecond breakdown), then [onComplete].
 * Tapping anywhere skips straight to [onComplete]. When system animations are
 * disabled ([RootMotion.enabled] is false), the wait is skipped entirely by
 * snapping progress to 1 rather than fading — motion is reduced, not required.
 */
@Composable
fun LaunchScreen(onComplete: () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (RootMotion.enabled()) {
            delay(RootMotion.launchSeedMillis)
            progress.animateTo(1f, tween(RootMotion.launchMillis, easing = RootMotion.launchEase))
            delay(RootMotion.launchHoldMillis)
        } else progress.snapTo(1f)
        onComplete()
    }
    Surface {
        Box(Modifier.fillMaxSize().clickable(role = Role.Button, onClickLabel = "Skip introduction", onClick = onComplete)) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                RootPath(progress.value, Modifier.size(112.dp))
                Text("Root", Modifier.alpha(((progress.value - 0.5f) * 2).coerceIn(0f, 1f)),
                    style = RootType.heroAnswer.copy(letterSpacing = 1.sp))
            }
            Text("A LITTLE CLOSER TO YOUR WORDS", Modifier.align(Alignment.BottomCenter).padding(40.dp),
                style = RootType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
