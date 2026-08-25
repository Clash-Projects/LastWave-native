package com.lastwave.app.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lastwave.app.data.artwork.ArtworkNormalizer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Faithful port of home.js's art-loading behavior for a single row:
 *  - If the track already carries a real (non-placeholder) Last.fm image,
 *    show it directly — zero network calls, matching `!t.image` gating in
 *    enrichTracksWithArt()/​_enrichHomeArt().
 *  - Otherwise, request resolution (Last.fm track.getInfo -> iTunes) the
 *    moment this row enters composition, and recompose automatically the
 *    instant the shared resolved-map StateFlow reports a result — whether
 *    that's this frame or several seconds later.
 *  - Only shows the fallback icon once every provider has genuinely
 *    reported no art (resolved value == ""); shows nothing but the
 *    caller's own background while still resolving.
 */
@Composable
fun ArtworkImage(
    name: String,
    artist: String,
    embeddedUrl: String?,
    fallbackIcon: ImageVector,
    modifier: Modifier = Modifier,
    decodeSizePx: Int? = null,
    artworkViewModel: ArtworkViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    if (!embeddedUrl.isNullOrBlank()) {
        val model = remember(embeddedUrl, decodeSizePx, context) {
            if (decodeSizePx == null) embeddedUrl
            else ImageRequest.Builder(context).data(embeddedUrl).size(decodeSizePx).build()
        }
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }

    val key = remember(name, artist) { ArtworkNormalizer.cacheKey(name, artist) }
    // Collect ONLY this row's slot of the shared resolved-map. Collecting the
    // whole map meant every resolution anywhere recomposed every visible
    // artwork row; with distinctUntilChanged each row recomposes exactly once —
    // when its own URL resolves.
    val resolvedUrl by remember(key) {
        artworkViewModel.resolved.map { it[key] }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)

    LaunchedEffect(key, resolvedUrl) {
        if (resolvedUrl == null) {
            artworkViewModel.resolve(name, artist)
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when {
            !resolvedUrl.isNullOrBlank() -> {
                val model = remember(resolvedUrl, decodeSizePx, context) {
                    if (decodeSizePx == null) resolvedUrl
                    else ImageRequest.Builder(context).data(resolvedUrl).size(decodeSizePx).build()
                }
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            else -> Icon(
                fallbackIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (resolvedUrl == null) 0.35f else 0.6f),
                modifier = Modifier.fillMaxSize(0.42f),
            )
        }
    }
}
