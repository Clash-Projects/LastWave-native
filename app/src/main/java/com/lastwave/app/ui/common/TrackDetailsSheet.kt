package com.lastwave.app.ui.common

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.download.TrackDownloadManager
import com.lastwave.app.data.local.db.DownloadedTrackDao
import com.lastwave.app.data.local.db.DownloadedTrackEntity
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.qobuz.QobuzMusicApi
import com.lastwave.app.ui.theme.ArtworkShape
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class TrackSpecs(
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val qualityBadge: String = "Resolving...",
    val audioCodec: String = "Detecting...",
    val bitDepthSampleRate: String = "Analyzing...",
    val provider: String = "Qobuz / YouTube",
    val isQobuz: Boolean = false,
    val durationText: String = "--:--",
    val downloadedEntity: DownloadedTrackEntity? = null,
    val isDownloading: Boolean = false,
)

@HiltViewModel
class TrackDetailsViewModel @Inject constructor(
    private val qobuzMusicApi: QobuzMusicApi,
    private val innerTube: InnerTubeMusicApi,
    private val downloadedTrackDao: DownloadedTrackDao,
    private val downloadManager: TrackDownloadManager,
) : ViewModel() {

    private val _specs = MutableStateFlow<TrackSpecs?>(null)
    val specs: StateFlow<TrackSpecs?> = _specs.asStateFlow()

    fun load(title: String, artist: String, album: String? = null, artworkUrl: String? = null) {
        viewModelScope.launch {
            val downloaded = downloadedTrackDao.findByTitleAndArtist(title, artist)
            val isDownloading = downloadManager.isDownloading(title, artist)

            _specs.value = TrackSpecs(
                title = title,
                artist = artist,
                album = album,
                artworkUrl = artworkUrl,
                downloadedEntity = downloaded,
                isDownloading = isDownloading,
            )

            // Resolve real audio resolution specs in background
            withContext(Dispatchers.IO) {
                val qobuzStream = runCatching {
                    qobuzMusicApi.resolveStream(title, artist, preferredQuality = QobuzMusicApi.QUALITY_MAX_HI_RES)
                }.getOrNull()

                if (qobuzStream != null) {
                    val badge = when {
                        qobuzStream.bitDepth > 16 || qobuzStream.samplingRate > 48.0 -> "24-BIT HI-RES"
                        qobuzStream.formatId == QobuzMusicApi.QUALITY_CD_LOSSLESS -> "CD LOSSLESS"
                        qobuzStream.formatId == QobuzMusicApi.QUALITY_MP3_320 -> "320k MP3"
                        else -> "FLAC"
                    }
                    val codec = if (qobuzStream.formatId == QobuzMusicApi.QUALITY_MP3_320) "MPEG Layer 3 (MP3)" else "Free Lossless Audio Codec (FLAC)"
                    val depthRate = "${qobuzStream.bitDepth}-bit / ${qobuzStream.samplingRate} kHz (${qobuzStream.bitrateKbps ?: 0} kbps)"
                    val durText = downloaded?.durationMs?.takeIf { it > 0L }?.let { ms ->
                        val dur = (ms / 1000).toInt()
                        "%d:%02d".format(dur / 60, dur % 60)
                    } ?: "\u2014"

                    _specs.value = _specs.value?.copy(
                        qualityBadge = badge,
                        audioCodec = codec,
                        bitDepthSampleRate = depthRate,
                        provider = "Qobuz Lossless Master CDN",
                        isQobuz = true,
                        durationText = durText,
                    )
                } else {
                    // Fallback YouTube stream specs
                    val bestMatch = runCatching { innerTube.findBestMatch(title, artist) }.getOrNull()
                    val videoId = bestMatch?.videoId
                    val ytStream = videoId?.let { runCatching { innerTube.resolveAudioStream(it) }.getOrNull() }

                    val rawMime = ytStream?.mimeType.orEmpty()
                    val codec = if (rawMime.contains("mp4") || rawMime.contains("m4a")) "Advanced Audio Coding (AAC)" else "Opus Interactive Audio"
                    val badge = if (rawMime.contains("mp4") || rawMime.contains("m4a")) "M4A 256k" else "OPUS 160k"
                    val bitrate = ytStream?.bitrate?.takeIf { it > 0 }?.let { "${(it + 500) / 1000} kbps" } ?: "160 kbps"

                    _specs.value = _specs.value?.copy(
                        qualityBadge = badge,
                        audioCodec = codec,
                        bitDepthSampleRate = "16-bit / 48.0 kHz ($bitrate)",
                        provider = "YouTube Music CDN",
                        isQobuz = false,
                    )
                }
            }
        }
    }

    fun downloadNow(title: String, artist: String, album: String?, artworkUrl: String?) {
        downloadManager.downloadTrack(title, artist, album, artworkUrl)
        _specs.value = _specs.value?.copy(isDownloading = true)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackDetailsSheet(
    title: String,
    artist: String,
    album: String? = null,
    artworkUrl: String? = null,
    onDismiss: () -> Unit,
    onPlayTrack: (() -> Unit)? = null,
    viewModel: TrackDetailsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val specs by viewModel.specs.collectAsState()

    LaunchedEffect(title, artist) {
        viewModel.load(title, artist, album, artworkUrl)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .size(width = 36.dp, height = 4.dp),
            ) {}
        },
    ) {
        val currentSpecs = specs ?: TrackSpecs(title = title, artist = artist, album = album, artworkUrl = artworkUrl)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp + safeDrawingBottomPadding())
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header: Cover Art + Badge
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .shadow(12.dp, RoundedCornerShape(20.dp), spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
            ) {
                ArtworkImage(
                    name = title,
                    artist = artist,
                    embeddedUrl = artworkUrl,
                    fallbackIcon = Icons.Filled.MusicNote,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)),
                )
                Surface(
                    shape = RoundedCornerShape(bottomStart = 8.dp, topEnd = 20.dp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Text(
                        text = currentSpecs.qualityBadge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Title & Artist
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = artist,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            if (!album.isNullOrBlank()) {
                Text(
                    text = album,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(18.dp))

            // Quick Actions: Play & Download
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (onPlayTrack != null) {
                    Button(
                        onClick = {
                            onPlayTrack()
                            onDismiss()
                        },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        modifier = Modifier.weight(1f).height(46.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Play", fontWeight = FontWeight.Bold)
                    }
                }

                FilledTonalButton(
                    onClick = {
                        viewModel.downloadNow(title, artist, album, artworkUrl)
                    },
                    enabled = currentSpecs.downloadedEntity == null && !currentSpecs.isDownloading,
                    shape = CircleShape,
                    modifier = Modifier.weight(1f).height(46.dp),
                ) {
                    Icon(
                        if (currentSpecs.downloadedEntity != null) Icons.Filled.CheckCircle else Icons.Filled.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            currentSpecs.downloadedEntity != null -> "Downloaded"
                            currentSpecs.isDownloading -> "Downloading..."
                            else -> "Download"
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Technical Audio Specs Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.HighQuality, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Audio & Stream Specifications", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    DetailRow(label = "Format Tier", value = currentSpecs.qualityBadge)
                    DetailRow(label = "Audio Codec", value = currentSpecs.audioCodec)
                    DetailRow(label = "Resolution", value = currentSpecs.bitDepthSampleRate)
                    DetailRow(label = "Stream Source", value = currentSpecs.provider)
                    if (currentSpecs.durationText != "--:--") {
                        DetailRow(label = "Duration", value = currentSpecs.durationText)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Offline & File Storage Info Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Storage & Scrobbler", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    DetailRow(
                        label = "Offline File",
                        value = if (currentSpecs.downloadedEntity != null) "Saved in Music/LastWave/" else "Not downloaded yet",
                    )
                    if (currentSpecs.downloadedEntity != null) {
                        DetailRow(
                            label = "File Size",
                            value = "${currentSpecs.downloadedEntity.fileSizeBytes / (1024 * 1024)} MB",
                        )
                        DetailRow(
                            label = "Location",
                            value = currentSpecs.downloadedEntity.filePath,
                        )
                    }
                    DetailRow(label = "Scrobble Target", value = "Last.fm profile")
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
