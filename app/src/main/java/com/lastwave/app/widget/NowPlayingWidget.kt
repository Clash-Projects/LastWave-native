package com.lastwave.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.glance.layout.fillMaxWidth
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

enum class NowPlayingWidgetStyle { COMPACT, CLASSIC, ARTWORK, GLASS }

/**
 * Common Glance host. Each concrete subclass is registered separately so
 * Android exposes four distinct, pre-sized widgets in the widget picker.
 */
abstract class ThemedNowPlayingWidget(
    private val widgetStyle: NowPlayingWidgetStyle,
) : GlanceAppWidget() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun themeRepository(): ThemeRepository
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        // This is the exact live scheme used by the Compose app, including
        // manual, wallpaper-dynamic, monochrome, AMOLED, and now-playing modes.
        val scheme = entryPoint.themeRepository().uiState.value.colorScheme
        val colors = ColorProviders(light = scheme, dark = scheme)
        val hasNotificationAccess = NotificationManagerCompat
            .getEnabledListenerPackages(context)
            .contains(context.packageName)
        val snapshot = NowPlayingWidgetSnapshot.read(context)

        provideContent {
            GlanceTheme(colors = colors) {
                NowPlayingWidgetContent(widgetStyle, context.packageName, hasNotificationAccess, snapshot)
            }
        }
    }
}

/** 4 x 1 balanced player bar. Kept under the original class name for upgrades. */
class NowPlayingWidget : ThemedNowPlayingWidget(NowPlayingWidgetStyle.CLASSIC)

/** 3 x 1 text-first player. */
class CompactNowPlayingWidget : ThemedNowPlayingWidget(NowPlayingWidgetStyle.COMPACT)

/** 2 x 2 artwork-first player. */
class ArtworkNowPlayingWidget : ThemedNowPlayingWidget(NowPlayingWidgetStyle.ARTWORK)

/** 4 x 2 large glass-tonal player. */
class GlassNowPlayingWidget : ThemedNowPlayingWidget(NowPlayingWidgetStyle.GLASS)

private data class WidgetUiState(
    val title: String,
    val artist: String,
    val album: String,
    val sourceApp: String,
    val isPlaying: Boolean,
    val hasSession: Boolean,
    val art: Bitmap?,
)

@Composable
private fun NowPlayingWidgetContent(
    style: NowPlayingWidgetStyle,
    ownPackage: String,
    hasNotificationAccess: Boolean,
    snapshot: NowPlayingWidgetSnapshot,
) {
    // Third-party metadata must not remain visible after Notification Access
    // is revoked. LastWave's own session needs no special access.
    val hasUsableSession = snapshot.hasSession &&
        (hasNotificationAccess || snapshot.sourcePackage == ownPackage)
    val artPath = snapshot.artPath
    val art = remember(artPath) {
        artPath?.let(::File)?.takeIf(File::exists)?.let { BitmapFactory.decodeFile(it.path) }
    }
    val state = WidgetUiState(
        title = snapshot.title,
        artist = snapshot.artist,
        album = snapshot.album,
        sourceApp = snapshot.sourceApp,
        isPlaying = snapshot.isPlaying,
        hasSession = hasUsableSession,
        art = art,
    )

    if (!hasUsableSession) {
        EmptyWidget(hasNotificationAccess)
        return
    }

    when (style) {
        NowPlayingWidgetStyle.COMPACT -> CompactWidget(state)
        NowPlayingWidgetStyle.CLASSIC -> ClassicWidget(state)
        NowPlayingWidgetStyle.ARTWORK -> ArtworkWidget(state)
        NowPlayingWidgetStyle.GLASS -> GlassWidget(state)
    }
}

@Composable
private fun EmptyWidget(hasNotificationAccess: Boolean) {
    val needsAccess = !hasNotificationAccess
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .cornerRadius(28.dp)
            .appWidgetBackground()
            .padding(16.dp)
            .then(
                if (needsAccess) GlanceModifier.clickable(actionRunCallback<OpenMusicAccessAction>())
                else GlanceModifier,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = GlanceModifier.size(32.dp),
        )
        Spacer(GlanceModifier.height(5.dp))
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

@Composable
private fun CompactWidget(state: WidgetUiState) {
    Column(
        modifier = playerSurface(GlanceModifier, usePrimary = true).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                TrackTitle(state.title, primarySurface = true, size = 14)
                TrackArtist(state.artist, primarySurface = true, size = 11)
            }
            Spacer(GlanceModifier.width(6.dp))
            MiniArtwork(state.art, 42)
        }
        Spacer(GlanceModifier.height(6.dp))
        TransportControls(state.isPlaying, fillWidth = true)
    }
}

@Composable
private fun ClassicWidget(state: WidgetUiState) {
    Row(
        modifier = playerSurface(GlanceModifier, usePrimary = true).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiniArtwork(state.art, 64)
        Spacer(GlanceModifier.width(12.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            TrackTitle(state.title, primarySurface = true, size = 16)
            Spacer(GlanceModifier.height(2.dp))
            TrackArtist(state.artist, primarySurface = true, size = 13)
            Spacer(GlanceModifier.height(8.dp))
            TransportControls(state.isPlaying)
        }
    }
}

@Composable
private fun ArtworkWidget(state: WidgetUiState) {
    Column(
        modifier = playerSurface(GlanceModifier, usePrimary = false).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = GlanceModifier.fillMaxWidth().height(82.dp)
                .background(GlanceTheme.colors.surfaceVariant).cornerRadius(22.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (state.art != null) {
                Image(
                    provider = ImageProvider(state.art),
                    contentDescription = "Album artwork",
                    modifier = GlanceModifier.fillMaxSize().cornerRadius(22.dp),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Image(ImageProvider(R.drawable.ic_launcher_foreground), null, GlanceModifier.size(44.dp))
            }
        }
        Spacer(GlanceModifier.height(8.dp))
        TrackTitle(state.title, primarySurface = false, size = 14)
        TrackArtist(state.artist, primarySurface = false, size = 11)
        Spacer(GlanceModifier.height(7.dp))
        TransportControls(state.isPlaying, fillWidth = true)
    }
}

@Composable
private fun GlassWidget(state: WidgetUiState) {
    Box(
        modifier = playerSurface(GlanceModifier, usePrimary = false).padding(8.dp),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxSize()
                .background(GlanceTheme.colors.surfaceVariant)
                .cornerRadius(24.dp)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiniArtwork(state.art, 94)
            Spacer(GlanceModifier.width(14.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                if (state.sourceApp.isNotBlank()) {
                    Text(
                        text = "NOW PLAYING  •  ${state.sourceApp}",
                        maxLines = 1,
                        style = TextStyle(
                            color = GlanceTheme.colors.primary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                    Spacer(GlanceModifier.height(5.dp))
                }
                TrackTitle(state.title, primarySurface = false, size = 19)
                Spacer(GlanceModifier.height(3.dp))
                TrackArtist(state.artist, primarySurface = false, size = 13)
                if (state.album.isNotBlank()) {
                    Text(
                        text = state.album,
                        maxLines = 1,
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                    )
                }
                Spacer(GlanceModifier.height(12.dp))
                TransportControls(state.isPlaying)
            }
        }
    }
}

@Composable
private fun playerSurface(modifier: GlanceModifier, usePrimary: Boolean): GlanceModifier = modifier
    .fillMaxSize()
    .background(if (usePrimary) GlanceTheme.colors.primaryContainer else GlanceTheme.colors.surface)
    .cornerRadius(28.dp)
    .appWidgetBackground()

@Composable
private fun MiniArtwork(art: Bitmap?, size: Int) {
    Box(
        modifier = GlanceModifier.size(size.dp).background(GlanceTheme.colors.surfaceVariant).cornerRadius((size / 4).dp),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(
                provider = ImageProvider(art),
                contentDescription = "Album artwork",
                modifier = GlanceModifier.fillMaxSize().cornerRadius((size / 4).dp),
                contentScale = ContentScale.Crop,
            )
        } else {
            Image(
                provider = ImageProvider(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = GlanceModifier.size((size * 0.6f).dp),
            )
        }
    }
}

@Composable
private fun TrackTitle(text: String, primarySurface: Boolean, size: Int) {
    Text(
        text = text.ifBlank { "Unknown track" },
        maxLines = 1,
        style = TextStyle(
            color = if (primarySurface) GlanceTheme.colors.onPrimaryContainer else GlanceTheme.colors.onSurface,
            fontSize = size.sp,
            fontWeight = FontWeight.Bold,
        ),
    )
}

@Composable
private fun TrackArtist(text: String, primarySurface: Boolean, size: Int) {
    Text(
        text = text.ifBlank { "Unknown artist" },
        maxLines = 1,
        style = TextStyle(
            color = if (primarySurface) GlanceTheme.colors.onPrimaryContainer else GlanceTheme.colors.onSurfaceVariant,
            fontSize = size.sp,
            fontWeight = FontWeight.Medium,
        ),
    )
}

@Composable
private fun TransportControls(isPlaying: Boolean, fillWidth: Boolean = false) {
    Row(
        modifier = if (fillWidth) GlanceModifier.fillMaxWidth() else GlanceModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = if (fillWidth) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        TransportButton(
            icon = R.drawable.ic_widget_skip_previous,
            description = "Previous",
            action = ControlAction.PREVIOUS,
            prominent = false,
        )
        Spacer(GlanceModifier.width(8.dp))
        TransportButton(
            icon = if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            description = if (isPlaying) "Pause" else "Play",
            action = ControlAction.TOGGLE,
            prominent = true,
        )
        Spacer(GlanceModifier.width(8.dp))
        TransportButton(
            icon = R.drawable.ic_widget_skip_next,
            description = "Next",
            action = ControlAction.NEXT,
            prominent = false,
        )
    }
}

private enum class ControlAction { PREVIOUS, TOGGLE, NEXT }

@Composable
private fun TransportButton(
    icon: Int,
    description: String,
    action: ControlAction,
    prominent: Boolean,
) {
    Box(
        modifier = GlanceModifier
            .size(if (prominent) 38.dp else 32.dp)
            .background(if (prominent) GlanceTheme.colors.primary else GlanceTheme.colors.secondaryContainer)
            .cornerRadius(if (prominent) 19.dp else 16.dp)
            .clickable(
                when (action) {
                    ControlAction.PREVIOUS -> actionRunCallback<SkipPreviousAction>()
                    ControlAction.TOGGLE -> actionRunCallback<TogglePlayPauseAction>()
                    ControlAction.NEXT -> actionRunCallback<SkipNextAction>()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = description,
            modifier = GlanceModifier.size(if (prominent) 18.dp else 16.dp),
            colorFilter = ColorFilter.tint(
                if (prominent) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onSecondaryContainer,
            ),
        )
    }
}
