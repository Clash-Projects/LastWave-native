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

/** The single fixed 3 x 1 artwork-and-controls widget exposed in the widget picker. */
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

        val darkThemeSurface = Color(0xFF0F1017)
        val darkThemeRaised = Color(0xFF1E1F2B)

        val widgetScheme = scheme.copy(
            surface = darkThemeSurface,
            onSurface = Color(0xFFEEEEF5),
            surfaceVariant = darkThemeRaised,
            onSurfaceVariant = Color(0xFFA0A1B2),
            primary = scheme.primary,
            onPrimary = scheme.onPrimary,
            primaryContainer = Color(0xFF2C2738),
            onPrimaryContainer = Color(0xFFEADBFA),
        )
        val colors = ColorProviders(light = widgetScheme, dark = widgetScheme)
        val hasNotificationAccess = NotificationManagerCompat
            .getEnabledListenerPackages(context)
            .contains(context.packageName)
        val snapshot = NowPlayingWidgetSnapshot.read(context)
        val animationFrame = WidgetUpdater.animationFrame
        // Keep the artwork's equalizer waves ticking after process death or
        // a freshly placed widget that restored a playing snapshot.
        if (snapshot.hasSession && snapshot.isPlaying &&
            (hasNotificationAccess || snapshot.sourcePackage == context.packageName)
        ) {
            WidgetUpdater.startWaveAnimation(context.applicationContext)
        } else {
            WidgetUpdater.stopWaveAnimation()
        }

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

/**
 * Glance re-runs this composable on every widget update; decoding the artwork
 * PNG each time was constant allocation churn. A single-slot cache keyed by
 * path + last-modified makes repeated updates pure memory hits while still
 * picking up new art the moment the file changes.
 */
private val widgetArtCache = object : java.util.LinkedHashMap<String, Bitmap>(2) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean = size > 1
}

private fun decodeWidgetArt(path: String?): Bitmap? {
    val file = path?.let(::File)?.takeIf(File::exists) ?: return null
    val key = "${file.absolutePath}|${file.lastModified()}"
    synchronized(widgetArtCache) {
        widgetArtCache[key]?.let { return it }
        val decoded = runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull() ?: return null
        widgetArtCache.clear()
        widgetArtCache[key] = decoded
        return decoded
    }
}

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
    val art = remember(artPath) { decodeWidgetArt(artPath) }
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiniArtwork(state.art, 54, state.isPlaying, state.animationFrame)
        Spacer(GlanceModifier.width(10.dp))
        Column(
            modifier = GlanceModifier.defaultWeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackTitle(state.title, size = 15)
            Spacer(GlanceModifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TrackArtist(state.artist, size = 12)
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    text = if (state.isPlaying) "• Playing" else "• Paused",
                    style = TextStyle(
                        color = if (state.isPlaying) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
            Spacer(GlanceModifier.height(6.dp))
            PlaybackControls(state.isPlaying)
        }
    }
}

@Composable
private fun playerSurface(modifier: GlanceModifier): GlanceModifier = modifier
    .fillMaxSize()
    .background(GlanceTheme.colors.surface)
    .cornerRadius(24.dp)
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
                modifier = GlanceModifier.fillMaxSize().cornerRadius(14.dp),
                contentScale = ContentScale.Crop,
            )
        } else {
            Image(
                provider = ImageProvider(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = GlanceModifier.size((size * 0.6f).dp),
            )
        }

        // Live Equalizer Waves Overlay on Artwork
        if (isPlaying) {
            val frame = animationFrame % 3
            val bar1Height = if (frame == 0) 10.dp else if (frame == 1) 5.dp else 12.dp
            val bar2Height = if (frame == 0) 5.dp else if (frame == 1) 12.dp else 7.dp
            val bar3Height = if (frame == 0) 12.dp else if (frame == 1) 8.dp else 11.dp

            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .padding(4.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Row(
                    modifier = GlanceModifier
                        .background(GlanceTheme.colors.surface)
                        .cornerRadius(6.dp)
                        .padding(horizontal = 4.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Box(
                        modifier = GlanceModifier
                            .width(2.5.dp)
                            .height(bar1Height)
                            .cornerRadius(1.dp)
                            .background(GlanceTheme.colors.primary),
                    ) {}
                    Spacer(GlanceModifier.width(2.dp))
                    Box(
                        modifier = GlanceModifier
                            .width(2.5.dp)
                            .height(bar2Height)
                            .cornerRadius(1.dp)
                            .background(GlanceTheme.colors.primary),
                    ) {}
                    Spacer(GlanceModifier.width(2.dp))
                    Box(
                        modifier = GlanceModifier
                            .width(2.5.dp)
                            .height(bar3Height)
                            .cornerRadius(1.dp)
                            .background(GlanceTheme.colors.primary),
                    ) {}
                }
            }
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
                .height(30.dp)
                .background(GlanceTheme.colors.primaryContainer)
                .cornerRadius(15.dp)
                .padding(horizontal = 12.dp)
                .clickable(actionRunCallback<TogglePlayPauseAction>()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                provider = ImageProvider(icon),
                contentDescription = label,
                modifier = GlanceModifier.size(14.dp),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer),
            )
            Spacer(GlanceModifier.width(5.dp))
            Text(
                text = label,
                style = TextStyle(
                    color = GlanceTheme.colors.onPrimaryContainer,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
        Spacer(GlanceModifier.width(8.dp))
        Box(
            modifier = GlanceModifier
                .size(30.dp)
                .background(GlanceTheme.colors.primaryContainer)
                .cornerRadius(15.dp)
                .clickable(actionRunCallback<SkipNextAction>()),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_skip_next),
                contentDescription = "Next",
                modifier = GlanceModifier.size(14.dp),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer),
            )
        }
    }
}

