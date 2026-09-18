package com.root.app.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll as updateGlanceWidgets
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.root.app.MainActivity
import com.root.app.R
import com.root.app.billing.EntitlementStore
import com.root.app.data.RootRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Home-screen widget (Jetpack Glance) that surfaces the learner's next due phrase without
 * opening the app. Reads through [RootRepository] directly rather than sharing a ViewModel
 * instance with [MainActivity], because widgets run in their own short-lived process/composition
 * and cannot hold a reference to an Activity-scoped ViewModel.
 *
 * Content follows the same [com.root.app.data.ContentAccess.canAccess] rule as the in-app due
 * queue: locked/premium phrases never surface here for non-premium users.
 */
class RootWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appContext = context.applicationContext
        val initial = loadPhrase(appContext)
        provideContent {
            val updates = remember { refreshes.map { loadPhrase(appContext) } }
            val phrase by updates.collectAsState(initial)
            DuePhraseContent(appContext, phrase)
        }
    }

    /**
     * Also refreshes a currently running Glance composition; the Glance extension alone
     * does not restart provideGlance during its active session.
     */
    suspend fun updateAll(context: Context) {
        refreshes.update { it + 1 }
        updateGlanceWidgets(context.applicationContext)
    }

    private suspend fun loadPhrase(context: Context): WidgetPhrase = withContext(Dispatchers.IO) {
        try {
            // A fresh RootRepository per refresh is intentional: the widget process is
            // short-lived and does not share the Activity's singleton repository/ViewModel.
            val repository = RootRepository(context)
            repository.initialize()
            val appearance = repository.getTheme()
            val languages = repository.languages()
            val language = languages.firstOrNull { it.id == repository.activeLanguageId() }
                ?: languages.firstOrNull { !it.isPremium }
            if (language == null) {
                WidgetPhrase(body = "Your words start here.", footer = "Open Root to add a phrase.", appearance = appearance)
            } else {
                val due = repository.duePhrases(
                    language.id,
                    premium = EntitlementStore(context).isPremium(),
                ).firstOrNull()
                if (due == null) {
                    WidgetPhrase(
                        language = language.name,
                        body = "Nothing due right now.",
                        footer = "Open Root to explore or add your own words.",
                        appearance = appearance,
                    )
                } else {
                    WidgetPhrase(
                        language = language.name,
                        body = due.answer,
                        footer = "Tap to practice",
                        hasDue = true,
                        appearance = appearance,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("RootWidget", "Unable to load the next due phrase", failure)
            WidgetPhrase(body = "Your words are unavailable.", footer = "Open Root to try again.")
        }
    }

    companion object {
        const val OPEN_PRACTICE_EXTRA = "root.openPractice"
        const val PRACTICE_ACTION = "com.root.app.action.PRACTICE"
        private val refreshes = MutableStateFlow(0L)
    }
}

class RootWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RootWidget()
}

private data class WidgetPhrase(
    val language: String? = null,
    val body: String,
    val footer: String,
    val hasDue: Boolean = false,
    val appearance: String = "system",
)

@Composable
private fun DuePhraseContent(context: Context, phrase: WidgetPhrase) {
    val openPractice = Intent(context, MainActivity::class.java)
        .setAction(RootWidget.PRACTICE_ACTION)
        .putExtra(RootWidget.OPEN_PRACTICE_EXTRA, true)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val ink = when (phrase.appearance) {
        "light" -> ColorProvider(Color(0xFF27251F))
        "dark" -> ColorProvider(Color(0xFFEAE0D0))
        else -> androidx.glance.color.ColorProvider(day = Color(0xFF27251F), night = Color(0xFFEAE0D0))
    }
    val muted = when (phrase.appearance) {
        "light" -> ColorProvider(Color(0xFF655E53))
        "dark" -> ColorProvider(Color(0xFFB9AE9C))
        else -> androidx.glance.color.ColorProvider(day = Color(0xFF655E53), night = Color(0xFFB9AE9C))
    }
    val paper = when (phrase.appearance) {
        "light" -> ColorProvider(Color(0xFFF4F0E8))
        "dark" -> ColorProvider(Color(0xFF201E1A))
        else -> androidx.glance.color.ColorProvider(day = Color(0xFFF4F0E8), night = Color(0xFF201E1A))
    }
    Column(
        GlanceModifier.fillMaxSize().appWidgetBackground()
            .background(paper)
            .clickable(actionStartActivity(openPractice))
            .padding(20.dp),
    ) {
        Text(
            if (phrase.hasDue) "ROOT / TODAY'S DUE PHRASE" else "ROOT / YOUR WORDS",
            style = TextStyle(color = muted, fontSize = 11.sp, fontWeight = FontWeight.Medium),
            maxLines = 2,
        )
        phrase.language?.let {
            Spacer(GlanceModifier.height(6.dp))
            Text(it, style = TextStyle(color = muted, fontSize = 13.sp), maxLines = 1)
        }
        Spacer(GlanceModifier.height(12.dp))
        Text(
            phrase.body,
            style = TextStyle(color = ink, fontSize = 26.sp, fontFamily = FontFamily.Serif),
            maxLines = 3,
        )
        Spacer(GlanceModifier.height(12.dp))
        Text(phrase.footer, style = TextStyle(color = muted, fontSize = 12.sp), maxLines = 2)
    }
}
