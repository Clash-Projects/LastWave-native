package com.lastwave.app.widget

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.lastwave.app.R
import com.lastwave.app.data.local.ThemePreferences
import com.lastwave.app.ui.theme.Md3SchemeBuilder
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Home-screen "Now Playing" widget — visually modeled on the platform
 * lock-screen media card (art + title/artist + a Pause pill + a round
 * skip button), rebuilt in LastWave's own Material 3 Expressive language:
 * a single elevated rounded surface, tonal (not literal-black) card
 * color, pill-shaped primary action, matching the app's accent.
 *
 * One real, honest limitation: RemoteViews (what Glance widgets compile
 * down to) has no supported way to embed an arbitrary bundled variable
 * font the way in-app Compose text can — there's no Typeface API exposed
 * on TextView through RemoteViews. So this can't reuse the app's actual
 * Google Sans Flex file; it leans on FontWeight.Bold on the system
 * default sans instead, which gets close to the same "confident, chunky"
 * feel without literally being the same typeface. Flagging this rather
 * than quietly shipping something that only looks right by accident.
 */
class NowPlayingWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    object Keys {
        val title = stringPreferencesKey("np_title")
        val artist = stringPreferencesKey("np_artist")
        val artPath = stringPreferencesKey("np_art_path")
        val isPlaying = booleanPreferencesKey("np_is_playing")
        val hasSession = booleanPreferencesKey("np_has_session")
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun themePreferences(): ThemePreferences
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        val accentHex = runCatching { entryPoint.themePreferences().prefs.first().accentColor }
            .getOrDefault("#E03030")
        // Always the dark build of the scheme — the app itself is dark-only
        // (AMOLED is an option WITHIN dark, there's no light theme to match
        // here), and it's also what keeps this readable as an on-screen
        // widget, the way the reference lock-screen card is.
        val scheme = Md3SchemeBuilder.buildScheme(accentHex, amoled = false)
        val colors = ColorProviders(light = scheme, dark = scheme)

        provideContent {
            GlanceTheme(colors = colors) {
                NowPlayingWidgetContent()
            }
        }
    }
}

@Composable
private fun NowPlayingWidgetContent() {
    val prefs = currentState<Preferences>()
    val hasSession = prefs[NowPlayingWidget.Keys.hasSession] ?: false
    val title = prefs[NowPlayingWidget.Keys.title]?.takeIf { hasSession } ?: "Not playing"
    val artist = prefs[NowPlayingWidget.Keys.artist]?.takeIf { hasSession } ?: "Open a tracked music app"
    val isPlaying = prefs[NowPlayingWidget.Keys.isPlaying] ?: false
    val artPath = prefs[NowPlayingWidget.Keys.artPath]

    val artBitmap = remember(artPath) {
        artPath?.let { path -> File(path).takeIf { it.exists() } }?.let { BitmapFactory.decodeFile(it.path) }
    }

    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(GlanceTheme.colors.primaryContainer)
            .cornerRadius(28.dp)
            .appWidgetBackground()
            .padding(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = GlanceModifier.fillMaxWidth(),
        ) {
            Box(
                modifier = GlanceModifier
                    .size(64.dp)
                    .cornerRadius(18.dp)
                    .background(GlanceTheme.colors.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (artBitmap != null) {
                    Image(
                        provider = ImageProvider(artBitmap),
                        contentDescription = null,
                        modifier = GlanceModifier.fillMaxSize().cornerRadius(18.dp),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Image(
                        provider = ImageProvider(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = GlanceModifier.size(38.dp),
                    )
                }
            }

            Spacer(modifier = GlanceModifier.width(12.dp))

            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = title,
                    maxLines = 1,
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = GlanceTheme.colors.onPrimaryContainer,
                    ),
                )
                Spacer(modifier = GlanceModifier.height(2.dp))
                Text(
                    text = artist,
                    maxLines = 1,
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = GlanceTheme.colors.onPrimaryContainer,
                    ),
                )
                Spacer(modifier = GlanceModifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlayPausePill(isPlaying = isPlaying, enabled = hasSession)
                    Spacer(modifier = GlanceModifier.width(10.dp))
                    SkipButton(enabled = hasSession)
                }
            }
        }
    }
}

@Composable
private fun PlayPausePill(isPlaying: Boolean, enabled: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = GlanceModifier
            .background(GlanceTheme.colors.primary)
            .cornerRadius(20.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .then(
                if (enabled) GlanceModifier.clickable(actionRunCallback<TogglePlayPauseAction>())
                else GlanceModifier,
            ),
    ) {
        Image(
            provider = ImageProvider(
                if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            ),
            contentDescription = if (isPlaying) "Pause" else "Play",
            modifier = GlanceModifier.size(18.dp),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary),
        )
        Spacer(modifier = GlanceModifier.width(6.dp))
        Text(
            text = if (isPlaying) "Pause" else "Play",
            style = TextStyle(
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = GlanceTheme.colors.onPrimary,
            ),
        )
    }
}

@Composable
private fun SkipButton(enabled: Boolean) {
    Box(
        modifier = GlanceModifier
            .size(36.dp)
            .background(GlanceTheme.colors.primary)
            .cornerRadius(18.dp)
            .then(
                if (enabled) GlanceModifier.clickable(actionRunCallback<SkipNextAction>())
                else GlanceModifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_widget_skip_next),
            contentDescription = "Skip",
            modifier = GlanceModifier.size(16.dp),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary),
        )
    }
}
