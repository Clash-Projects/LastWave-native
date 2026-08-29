package com.lastwave.app.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.lastwave.app.ui.common.PredictiveBackScreen
import com.lastwave.app.ui.common.ExpressiveMotion
import com.lastwave.app.ui.generate.GenerateScreen
import com.lastwave.app.ui.generate.MixLauncher
import com.lastwave.app.ui.home.HomeScreen
import com.lastwave.app.ui.playlist.PlaylistScreen
import com.lastwave.app.ui.player.LocalMiniPlayerScrollClearance
import com.lastwave.app.ui.theme.LocalLiquidGlass
import com.lastwave.app.ui.theme.liquidGlassChrome
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

/** Thin bridge exposing MixLauncher's requests to MainShell — MainShell
 *  itself isn't a screen with its own ViewModel, so this is the smallest
 *  way to reach the same singleton GenerateViewModel already listens to
 *  (see MixLauncher's doc comment for the full "Start Mix" flow). */
@HiltViewModel
class MainShellViewModel @Inject constructor(mixLauncher: MixLauncher) : ViewModel() {
    val mixRequests = mixLauncher.requests
}

private enum class MainTab(val label: String) { HOME("Home"), GENERATE("Generate"), PLAYLISTS("Playlists") }

/** Shared with any screen hosted inside [MainShell] so their scrolling
 *  lists know how much bottom content padding to reserve — the nav
 *  overlays content (it's not a Scaffold bottomBar reserving space), so
 *  each screen leaves this much room for its last item to clear it. */
object FloatingNavDefaults {
    val ContentBottomPadding = 112.dp

    /**
     * Full bottom clearance for edge-to-edge scrolling content: the floating
     * dock's visual height + margins ([ContentBottomPadding]) PLUS the live
     * navigation-bar (gesture area) inset. Screens that let their list draw
     * beneath the transparent gesture area must use this instead of the raw
     * constant, otherwise the last row hides behind the dock/gesture bar.
     */
    @Composable
    fun contentBottomPadding(): Dp =
        ContentBottomPadding +
            LocalMiniPlayerScrollClearance.current +
            WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
}

// Hoisted to plain top-level vals instead of being constructed inside a
// @Composable body: RoundedCornerShape is immutable and never changes here,
// so there's no reason to let it be reconstructed on every recomposition.
private val DockShape: Shape = RoundedCornerShape(32.dp)
private val PillShape: Shape = CircleShape

// One shared spring keeps tab selection, label expansion, and pager controls
// visually coherent while preserving each call site's inferred value type.
private fun <T> navSpring() = ExpressiveMotion.spatialSpring<T>()

@Composable
fun MainShell(
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenDiscover: () -> Unit,
    onOpenGenres: () -> Unit,
    onOpenFriends: () -> Unit,
    onOpenPlaylist: (Long) -> Unit = {},
    mainShellViewModel: MainShellViewModel = hiltViewModel(),
) {
    val tabs = MainTab.entries
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()

    // "Start Mix with this Song" (§6) can be tapped from any screen's track
    // menu, including ones pushed on top of MainShell (Discover/Search/
    // Genres) — this keeps running even while MainShell isn't the visible
    // screen, since its composition isn't disposed just because another
    // route is on top of it in the back stack.
    LaunchedEffect(Unit) {
        mainShellViewModel.mixRequests.collect {
            scope.launch { pagerState.animateScrollToPage(tabs.indexOf(MainTab.GENERATE)) }
        }
    }

    // A plain Box, not Scaffold(bottomBar = ...): the nav floats ON TOP of
    // content via Box alignment, never reserving/subtracting its own
    // height from the content area.
    Box(Modifier.fillMaxSize()) {
        val homeIndex = tabs.indexOf(MainTab.HOME)
        HorizontalPager(
            state = pagerState,
            // Pager prefetches the gesture destination itself. Keeping all
            // three tabs composed made off-screen lists, image loaders and
            // infinite animations compete with the visible page for frames.
            beyondViewportPageCount = 0,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            // Predictive back on a non-Home tab returns to Home (with the
            // same swipe-to-pop gesture as other screens), but once on Home
            // stays enabled = false so back on Home falls through to the
            // system default (exit/minimize), same as before.
            val isCurrent = page == pagerState.currentPage
            PredictiveBackScreen(
                enabled = isCurrent && tabs[page] != MainTab.HOME,
                onBack = { scope.launch { pagerState.animateScrollToPage(homeIndex) } },
            ) {
                when (tabs[page]) {
                    MainTab.HOME -> HomeScreen(onOpenSettings = onOpenSettings, onOpenSearch = onOpenSearch, onOpenDiscover = onOpenDiscover, onOpenGenres = onOpenGenres, onOpenFriends = onOpenFriends)
                    MainTab.GENERATE -> GenerateScreen(
                        onNavigateToPlaylist = {
                            scope.launch { pagerState.animateScrollToPage(tabs.indexOf(MainTab.PLAYLISTS)) }
                        },
                    )
                    MainTab.PLAYLISTS -> PlaylistScreen(onOpenPlaylist = onOpenPlaylist)
                }
            }
        }

        FloatingNavBar(
            tabs = tabs,
            selectedIndex = pagerState.currentPage,
            onSelect = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * ONE floating dock (a single Surface with generous rounded corners,
 * elevation, and margin) containing the three tabs with real breathing
 * space between them. Unselected tabs render as plain borderless icon
 * buttons (ripple only) so the dock's own surface shows through as their
 * "background"; the selected tab morphs its own shape into an accent pill
 * with icon + label.
 */
@Composable
private fun FloatingNavBar(
    tabs: List<MainTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val liquidGlass = LocalLiquidGlass.current
    Box(
        modifier = modifier
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = DockShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlassChrome(DockShape, liquidGlass),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { index, tab ->
                    val onClick = remember(index) { { onSelect(index) } }
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        FloatingNavItem(
                            label = tab.label,
                            icon = tab.icon(),
                            selected = selectedIndex == index,
                            onClick = onClick,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingNavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        animationSpec = navSpring(),
        label = "navItemBackground",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = navSpring(),
        label = "navItemContent",
    )

    Surface(
        onClick = onClick,
        shape = PillShape,
        color = backgroundColor,
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .animateContentSize(animationSpec = navSpring()),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize(),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(22.dp),
            )
            AnimatedVisibility(
                visible = selected,
                enter = fadeIn(animationSpec = navSpring()) + expandHorizontally(
                    animationSpec = navSpring(),
                    expandFrom = Alignment.Start,
                ),
                exit = fadeOut(animationSpec = navSpring()) + shrinkHorizontally(
                    animationSpec = navSpring(),
                    shrinkTowards = Alignment.Start,
                ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = contentColor,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun MainTab.icon(): ImageVector = when (this) {
    MainTab.HOME -> Icons.Filled.Home
    MainTab.GENERATE -> Icons.Filled.Add
    MainTab.PLAYLISTS -> Icons.AutoMirrored.Filled.QueueMusic
}
