package com.lastwave.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
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
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.lastwave.app.R
import com.lastwave.app.data.repository.ThemeRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File

data class NowPlayingWidgetSnapshot(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val sourceApp: String = "",
    val sourcePackage: String = "",
    val artPath: String? = null,
    val isPlaying: Boolean = false,
    val hasSession: Boolean = false,
) {
    companion object {
        private const val STORE = "lastwave_widget_now_playing"

        fun read(context: Context): NowPlayingWidgetSnapshot {
            val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            return NowPlayingWidgetSnapshot(
                title = prefs.getString("title", "").orEmpty(),
                artist = prefs.getString("artist", "").orEmpty(),
                album = prefs.getString("album", "").orEmpty(),
                sourceApp = prefs.getString("source_app", "").orEmpty(),
                sourcePackage = prefs.getString("source_package", "").orEmpty(),
                artPath = prefs.getString("art_path", null),
                isPlaying = prefs.getBoolean("is_playing", false),
                hasSession = prefs.getBoolean("has_session", false),
            )
        }

        fun write(context: Context, value: NowPlayingWidgetSnapshot) {
            context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
                .putString("title", value.title)
                .putString("artist", value.artist)
                .putString("album", value.album)
                .putString("source_app", value.sourceApp)
                .putString("source_package", value.sourcePackage)
                .putString("art_path", value.artPath)
                .putBoolean("is_playing", value.isPlaying)
                .putBoolean("has_session", value.hasSession)
                .apply()
        }
    }
}

/** The single fixed 4 x 1 artwork-and-controls widget exposed in the widget picker. */
class NowPlayingWidget : GlanceAppWidget() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun themeRepository(): ThemeRepository
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = runCatching {
            EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
        }.getOrNull()
        val scheme = entryPoint?.themeRepository()?.uiState?.value?.colorScheme
            ?: com.lastwave.app.ui.theme.Md3SchemeBuilder.buildScheme("#E03030", false)

        val darkThemeSurface = blendColor(scheme.surfaceContainerHigh, scheme.primary, 0.22f)
        val darkThemeRaised = blendColor(scheme.surfaceContainerHighest, scheme.primary, 0.34f)

        val widgetScheme = scheme.copy(
            surface = darkThemeSurface,
            onSurface = scheme.onSurface,
            surfaceVariant = darkThemeRaised,
            onSurfaceVariant = scheme.onSurfaceVariant,
            primary = scheme.primary,
            onPrimary = scheme.onPrimary,
            primaryContainer = scheme.primary,
            onPrimaryContainer = scheme.onPrimary,
        )
        val colors = ColorProviders(light = widgetScheme, dark = widgetScheme)
        val hasNotificationAccess = NotificationManagerCompat
            .getEnabledListenerPackages(context)
            .contains(context.packageName)
        val snapshot = NowPlayingWidgetSnapshot.read(context)
        val animationFrame = WidgetUpdater.animationFrame

        provideContent {
            GlanceTheme(colors = colors) {
                NowPlayingWidgetContent(context.packageName, hasNotificationAccess, snapshot, animationFrame)
            }
        }
    }
}

private fun blendColor(base: Color, tint: Color, fraction: Float): Color = Color(
    red = base.red + (tint.red - base.red) * fraction,
    green = base.green + (tint.green - base.green) * fraction,
    blue = base.blue + (tint.blue - base.blue) * fraction,
    alpha = 1f,
)

private data class WidgetUiState(
    val title: String,
    val artist: String,
    val isPlaying: Boolean,
    val art: Bitmap?,
    val animationFrame: Int,
)

@Composable
private fun NowPlayingWidgetContent(
    ownPackage: String,
    hasNotificationAccess: Boolean,
    snapshot: NowPlayingWidgetSnapshot,
    animationFrame: Int,
) {
    val hasUsableSession = snapshot.hasSession &&
        (hasNotificationAccess || snapshot.sourcePackage == ownPackage)
    val artPath = snapshot.artPath
    val art = remember(artPath) {
        artPath?.let(::File)?.takeIf(File::exists)?.let { BitmapFactory.decodeFile(it.path) }
    }
    val state = WidgetUiState(
        title = snapshot.title,
        artist = snapshot.artist,
        isPlaying = snapshot.isPlaying,
        art = art,
        animationFrame = animationFrame,
    )

    if (!hasUsableSession) {
        EmptyWidget(hasNotificationAccess)
        return
    }

    PlayerWidget(state)
}

@Composable
private fun EmptyWidget(hasNotificationAccess: Boolean) {
    val needsAccess = !hasNotificationAccess
    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .cornerRadius(24.dp)
            .appWidgetBackground()
            .padding(12.dp)
            .then(
                if (needsAccess) GlanceModifier.clickable(actionRunCallback<OpenMusicAccessAction>())
                else GlanceModifier.clickable(actionRunCallback<OpenLastWaveAction>()),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = GlanceModifier.size(36.dp),
        )
        Spacer(GlanceModifier.width(10.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = if (needsAccess) "Allow music access" else "Nothing playing",
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                text = if (needsAccess) "Tap to detect every media app" else "Start a song in any media app",
                maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
            )
        }
    }
}

@Composable
private fun PlayerWidget(state: WidgetUiState) {
    Row(
        modifier = playerSurface(GlanceModifier)
            .clickable(actionRunCallback<OpenLastWaveAction>())
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiniArtwork(state.art, 76, state.isPlaying, state.animationFrame)
        Spacer(GlanceModifier.width(12.dp))
        Column(
            modifier = GlanceModifier.defaultWeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackTitle(state.title, size = 15)
            Spacer(GlanceModifier.height(2.dp))
            TrackArtist(state.artist, size = 12)
            Spacer(GlanceModifier.height(6.dp))
            PlaybackControls(state.isPlaying)
        }
    }
}

@Composable
private fun playerSurface(modifier: GlanceModifier): GlanceModifier = modifier
    .fillMaxSize()
    .background(GlanceTheme.colors.surface)
    .cornerRadius(28.dp)
    .appWidgetBackground()

@Composable
private fun MiniArtwork(art: Bitmap?, size: Int, isPlaying: Boolean, animationFrame: Int) {
    Box(
        modifier = GlanceModifier.size(size.dp).background(GlanceTheme.colors.surfaceVariant).cornerRadius(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(
                provider = ImageProvider(art),
                contentDescription = "Album artwork",
                modifier = GlanceModifier.fillMaxSize().cornerRadius(16.dp),
                contentScale = ContentScale.Crop,
            )
        } else {
            Image(
                provider = ImageProvider(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = GlanceModifier.size((size * 0.6f).dp),
            )
        }
        if (isPlaying) {
            Box(
                modifier = GlanceModifier.fillMaxSize().padding(5.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                PlayingWaves(animationFrame)
            }
        }
    }
}

@Composable
private fun PlayingWaves(frame: Int) {
    val heights = when (frame % 4) {
        0 -> intArrayOf(6, 15, 9)
        1 -> intArrayOf(11, 7, 15)
        2 -> intArrayOf(15, 10, 6)
        else -> intArrayOf(8, 15, 12)
    }
    Row(
        modifier = GlanceModifier
            .width(28.dp)
            .height(22.dp)
            .background(GlanceTheme.colors.surface)
            .cornerRadius(8.dp)
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        heights.forEachIndexed { index, height ->
            Spacer(
                GlanceModifier
                    .width(3.dp)
                    .height(height.dp)
                    .background(GlanceTheme.colors.primary)
                    .cornerRadius(2.dp),
            )
            if (index < heights.lastIndex) Spacer(GlanceModifier.width(2.dp))
        }
    }
}

@Composable
private fun TrackTitle(text: String, size: Int) {
    Text(
        text = text.ifBlank { "Unknown track" },
        maxLines = 1,
        style = TextStyle(
            color = GlanceTheme.colors.onSurface,
            fontSize = size.sp,
            fontWeight = FontWeight.Bold,
        ),
    )
}

@Composable
private fun TrackArtist(text: String, size: Int) {
    Text(
        text = text.ifBlank { "Unknown artist" },
        maxLines = 1,
        style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = size.sp,
            fontWeight = FontWeight.Medium,
        ),
    )
}

@Composable
private fun PlaybackControls(isPlaying: Boolean) {
    val label = if (isPlaying) "Pause" else "Play"
    val icon = if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = GlanceModifier
                .width(88.dp)
                .height(36.dp)
                .background(GlanceTheme.colors.primary)
                .cornerRadius(18.dp)
                .clickable(actionRunCallback<TogglePlayPauseAction>()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                provider = ImageProvider(icon),
                contentDescription = label,
                modifier = GlanceModifier.size(18.dp),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary),
            )
            Spacer(GlanceModifier.width(5.dp))
            Text(
                text = label,
                style = TextStyle(
                    color = GlanceTheme.colors.onPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
        Spacer(GlanceModifier.width(6.dp))
        Box(
            modifier = GlanceModifier
                .size(36.dp)
                .background(GlanceTheme.colors.primary)
                .cornerRadius(18.dp)
                .clickable(actionRunCallback<SkipNextAction>()),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_skip_next),
                contentDescription = "Next",
                modifier = GlanceModifier.size(18.dp),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary),
            )
        }
    }
}
