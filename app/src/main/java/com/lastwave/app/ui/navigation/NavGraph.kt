package com.lastwave.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.model.AuthState
import com.lastwave.app.ui.auth.AuthViewModel
import com.lastwave.app.ui.auth.LoginScreen
import com.lastwave.app.ui.settings.SettingsScreen
import com.lastwave.app.ui.search.SearchScreen
import com.lastwave.app.ui.discover.DiscoverScreen
import com.lastwave.app.ui.genres.GenresScreen
import com.lastwave.app.ui.shell.MainShell
import com.lastwave.app.ui.common.PredictiveBackScreen
import com.lastwave.app.ui.common.ExpressiveLoadingIndicator
import com.lastwave.app.ui.common.ExpressiveMotion
import com.lastwave.app.ui.genres.GenreExplorer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Thin bridge so LastWaveNavHost (a plain composable, no direct Hilt
 *  singleton access) can observe GenreExplorer's pending genre and
 *  navigate — same reasoning as MainShellViewModel's bridge to
 *  MixLauncher. */
@HiltViewModel
class GenreExplorerNavBridge @Inject constructor(genreExplorer: GenreExplorer) : androidx.lifecycle.ViewModel() {
    val pendingGenre = genreExplorer.pendingGenre
}

@HiltViewModel
class ArtistAlbumNavBridge @Inject constructor(val navigator: ArtistAlbumNavigator) : androidx.lifecycle.ViewModel()

@HiltViewModel
class AppRouteNavBridge @Inject constructor(val routeNavigator: AppRouteNavigator) : androidx.lifecycle.ViewModel()

@Composable
fun LastWaveNavHost(
    navController: NavHostController = rememberNavController(),
) {
    val appRouteBridge: AppRouteNavBridge = hiltViewModel()
    val pendingAppRoute by appRouteBridge.routeNavigator.pendingRoute.collectAsStateWithLifecycle()
    LaunchedEffect(pendingAppRoute) {
        val route = pendingAppRoute
        if (route != null) {
            appRouteBridge.routeNavigator.consumeRoute()
            navController.navigate(route) {
                launchSingleTop = true
            }
        }
    }

    // "Explore this genre" from any track's context menu, anywhere in the
    // app — Home, Discover, Playlist, Search — routes here since Genres
    // is a pushed destination on THIS nav controller and none of those
    // screens hold a reference to it. See GenreExplorer's doc comment.
    val genreExplorerBridge: GenreExplorerNavBridge = hiltViewModel()
    val pendingGenre by genreExplorerBridge.pendingGenre.collectAsStateWithLifecycle()
    LaunchedEffect(pendingGenre) {
        if (pendingGenre != null) {
            navController.navigate(Screen.Genres.route) {
                launchSingleTop = true
            }
        }
    }

    val navBridge: ArtistAlbumNavBridge = hiltViewModel()
    LaunchedEffect(Unit) {
        navBridge.navigator.events.collect { target ->
            when (target) {
                is ArtistAlbumNavTarget.Artist -> {
                    navController.navigate(Screen.ArtistDetail.createRoute(target.name, target.browseId)) {
                        launchSingleTop = true
                    }
                }
                is ArtistAlbumNavTarget.Album -> {
                    navController.navigate(Screen.AlbumDetail.createRoute(target.title, target.artist, target.browseId)) {
                        launchSingleTop = true
                    }
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Screen.Splash.route,
        enterTransition = { ExpressiveMotion.forwardEnter() },
        exitTransition = { ExpressiveMotion.forwardExit() },
        popEnterTransition = { ExpressiveMotion.backEnter() },
        popExitTransition = { ExpressiveMotion.backExit() },
    ) {

        // Resolves the persisted session BEFORE showing any interactive UI.
        // This is what makes login persistent: without this gate, the app
        // used to start directly on the Login route and only redirect away
        // reactively once DataStore's first read arrived — meaning every
        // cold start visibly showed the login form for a moment, and felt
        // like being asked to log in again even though it wasn't. Now we
        // wait for AuthState to resolve to something other than Unknown
        // before deciding where to go, so a valid session skips Login
        // completely and a real logged-out state is the only way to see it.
        composable(Screen.Splash.route) {
            val authViewModel: AuthViewModel = hiltViewModel()
            val authState by authViewModel.authState.collectAsStateWithLifecycle()

            LaunchedEffect(authState) {
                when (authState) {
                    is AuthState.SignedIn -> navController.navigate(Screen.MainShell.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                    AuthState.SignedOut, is AuthState.Error -> navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                    else -> Unit // still Unknown — keep waiting
                }
            }

            LaunchGate()
        }

        composable(Screen.Login.route) {
            val authViewModel: AuthViewModel = hiltViewModel()
            val authState by authViewModel.authState.collectAsStateWithLifecycle()
            val webAuthState by authViewModel.webAuthState.collectAsStateWithLifecycle()

            // Real one-tap sign-in now: tap Connect, approve in an
            // embedded WebView, done — see AuthViewModel/LoginScreen for
            // the full flow. No credentials form to fill in here anymore.
            LaunchedEffect(authState, webAuthState) {
                if (authState is AuthState.SignedIn && webAuthState == com.lastwave.app.ui.auth.WebAuthState.Idle) {
                    navController.navigate(Screen.MainShell.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            }

            LoginScreen(
                authState = authState,
                webAuthState = webAuthState,
                onBeginSignIn = authViewModel::beginSignIn,
                onReturnedFromBrowser = authViewModel::onReturnedFromBrowser,
                onCancelWebAuth = authViewModel::cancelSignIn,
                onSignOut = authViewModel::signOut,
                onRestoreBackupAndSignIn = authViewModel::beginRestoreAndSignIn,
                onDismissError = authViewModel::dismissError,
            )
        }

        // Post-login container: Material3 bottom nav with Home/Generate/
        // Playlists as swipeable tabs. Settings, Search, and Discover are
        // NOT tabs — they're pushed screens reached from Home's top app bar
        // (profile icon / search icon / discover icon), on this same root
        // nav controller, so they can pop back cleanly and (on log out /
        // clear session) return all the way to Login.
        composable(Screen.MainShell.route) {
            MainShell(
                onOpenSettings = {
                    navController.navigate(Screen.Settings.route) { launchSingleTop = true }
                },
                onOpenSearch = { navController.navigate(Screen.Search.route) },
                onOpenDiscover = { navController.navigate(Screen.Discover.route) },
                onOpenGenres = { navController.navigate(Screen.Genres.route) },
                onOpenFriends = { navController.navigate(Screen.Friends.route) },
                onOpenPlaylist = { playlistId ->
                    navController.navigate(Screen.PlaylistDetail.createRoute(playlistId))
                },
            )
        }

        // Friends is a real pushed screen (not a Dialog/ModalBottomSheet) —
        // both of those were tried first and both had the same underlying
        // problem: getting a Compose overlay to genuinely claim the full
        // real window (not just "as tall as its own content measured
        // itself to be", which is all either API reliably guarantees
        // without extra low-level workarounds) turned out to be fragile
        // enough, even after direct fixes, that it kept regressing. A
        // normal NavHost destination gets full-screen sizing for free —
        // every other pushed screen here (Settings, Search, Discover,
        // Genres, ScrobblerApps) already renders correctly edge-to-edge —
        // so Friends now works exactly like those instead of being a
        // special case. It shares Home's own HomeViewModel (scoped to
        // MainShell's back stack entry) rather than getting a fresh one,
        // since the friends list / switch-profile state already lives
        // there and switching profile needs to affect the Home tab
        // underneath once you pop back.
        composable(Screen.Friends.route) { backStackEntry ->
            val parentEntry = remember(backStackEntry) {
                navController.getBackStackEntry(Screen.MainShell.route)
            }
            val homeViewModel: com.lastwave.app.ui.home.HomeViewModel = hiltViewModel(parentEntry)
            PredictiveBackScreen(onBack = { navController.popBackStack() }) {
                com.lastwave.app.ui.home.FriendsScreen(
                    viewModel = homeViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
        }

        // Every pushed-on-top destination below is wrapped in
        // PredictiveBackScreen so Android's predictive back gesture (drag
        // in from the edge) scales/rounds/dims the screen in real time
        // instead of using a canned pop transition — see its doc comment
        // for exactly what it does and does not animate. The screen's own
        // onBack (its toolbar back button) still pops directly; the wrap
        // only adds the gesture-driven path, it doesn't replace the
        // button's.
        composable(Screen.Genres.route) {
            PredictiveBackScreen(onBack = { navController.popBackStack() }) {
                GenresScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateToPlaylist = {
                        navController.popBackStack()
                        // Playlist tab already re-reads on resume (PlaylistViewModel's
                        // LifecycleResumeEffect), so popping back to MainShell is
                        // sufficient — no separate "switch tab" signal is needed here
                        // since MainShell always shows Playlists as one of its tabs
                        // and the user lands back wherever they left MainShell.
                    },
                )
            }
        }

        composable(Screen.Settings.route) {
            PredictiveBackScreen(onBack = { navController.popBackStack() }) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onLoggedOut = {
                        navController.navigate(Screen.Login.route) {
                            popUpTo(Screen.MainShell.route) { inclusive = true }
                        }
                    },
                    onOpenChooseApps = { navController.navigate(Screen.ScrobblerApps.route) },
                    onOpenDownloads = { navController.navigate(Screen.Downloads.route) },
                    onOpenExcludedSongs = { navController.navigate(Screen.ExcludedSongs.route) },
                    onOpenYouTubeImport = { navController.navigate(Screen.YouTubeImport.route) },
                    onOpenYouTubeLogin = { navController.navigate(Screen.YouTubeLogin.route) },
                )
            }
        }

        composable(Screen.YouTubeImport.route) {
            PredictiveBackScreen(onBack = { navController.popBackStack() }) {
                com.lastwave.app.ui.settings.YouTubePlaylistImportScreen(
                    onBack = { navController.popBackStack() },
                    onImportSuccess = {
                        navController.popBackStack()
                    },
                )
            }
        }

        composable(Screen.YouTubeLogin.route) {
            PredictiveBackScreen(onBack = { navController.popBackStack() }) {
                com.lastwave.app.ui.settings.YouTubeLoginScreen(
                    onBack = { navController.popBackStack() },
                    onConnected = { navController.popBackStack() },
                )
            }
        }

        composable(Screen.Downloads.route) {
            PredictiveBackScreen(onBack = { navController.popBackStack() }) {
                com.lastwave.app.ui.settings.DownloadsScreen(onBack = { navController.popBackStack() })
            }
        }

        composable(Screen.ExcludedSongs.route) {
            PredictiveBackScreen(onBack = { navController.popBackStack() }) {
                com.lastwave.app.ui.settings.ExcludedSongsScreen(onBack = { navController.popBackStack() })
            }
        }

        composable(Screen.ScrobblerApps.route) {
            PredictiveBackScreen(onBack = { navController.popBackStack() }) {
                com.lastwave.app.ui.settings.ScrobblerAppsScreen(onBack = { navController.popBackStack() })
            }
        }

        composable(Screen.Search.route) {
            PredictiveBackScreen(onBack = { navController.popBackStack() }) {
                SearchScreen(
                    onBack = { navController.popBackStack() },
                    onOpenArtist = { name, browseId ->
                        navController.navigate(Screen.ArtistDetail.createRoute(name, browseId))
                    },
                    onOpenAlbum = { title, artist, browseId ->
                        navController.navigate(Screen.AlbumDetail.createRoute(title, artist, browseId))
                    },
                )
            }
        }

        composable(Screen.Discover.route) {
            PredictiveBackScreen(onBack = { navController.popBackStack() }) {
                DiscoverScreen(onBack = { navController.popBackStack() })
            }
        }

        composable(
            route = Screen.PlaylistDetail.route,
            arguments = listOf(
                androidx.navigation.navArgument("playlistId") {
                    type = androidx.navigation.NavType.LongType
                },
            ),
        ) { backStackEntry ->
            val playlistId = backStackEntry.arguments?.getLong("playlistId") ?: return@composable
            PredictiveBackScreen(onBack = { navController.popBackStack() }) {
                com.lastwave.app.ui.playlist.PlaylistDetailScreen(
                    playlistId = playlistId,
                    onBack = { navController.popBackStack() },
                    onOpenPlaylist = { newId ->
                        navController.navigate(Screen.PlaylistDetail.createRoute(newId))
                    },
                )
            }
        }

        composable(
            route = Screen.ArtistDetail.route,
            arguments = listOf(
                androidx.navigation.navArgument("artistName") { type = androidx.navigation.NavType.StringType },
                androidx.navigation.navArgument("browseId") {
                    type = androidx.navigation.NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { backStackEntry ->
            val artistName = backStackEntry.arguments?.getString("artistName").orEmpty()
            val browseId = backStackEntry.arguments?.getString("browseId")?.takeIf(String::isNotBlank)
            PredictiveBackScreen(onBack = { navController.popBackStack() }) {
                com.lastwave.app.ui.artist.ArtistDetailScreen(
                    artistName = artistName,
                    browseId = browseId,
                    onBack = { navController.popBackStack() },
                    onOpenAlbum = { title, artist, id ->
                        navController.navigate(Screen.AlbumDetail.createRoute(title, artist, id))
                    },
                    onOpenArtist = { name, id ->
                        navController.navigate(Screen.ArtistDetail.createRoute(name, id))
                    },
                )
            }
        }

        composable(
            route = Screen.AlbumDetail.route,
            arguments = listOf(
                androidx.navigation.navArgument("albumTitle") { type = androidx.navigation.NavType.StringType },
                androidx.navigation.navArgument("artistName") {
                    type = androidx.navigation.NavType.StringType
                    defaultValue = ""
                },
                androidx.navigation.navArgument("browseId") {
                    type = androidx.navigation.NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { backStackEntry ->
            val albumTitle = backStackEntry.arguments?.getString("albumTitle").orEmpty()
            val artistName = backStackEntry.arguments?.getString("artistName").orEmpty()
            val browseId = backStackEntry.arguments?.getString("browseId")?.takeIf(String::isNotBlank)
            PredictiveBackScreen(onBack = { navController.popBackStack() }) {
                com.lastwave.app.ui.album.AlbumDetailScreen(
                    albumTitle = albumTitle,
                    artistName = artistName,
                    browseId = browseId,
                    onBack = { navController.popBackStack() },
                    onOpenArtist = { name, id ->
                        navController.navigate(Screen.ArtistDetail.createRoute(name, id))
                    },
                    onOpenAlbum = { title, artist, id ->
                        navController.navigate(Screen.AlbumDetail.createRoute(title, artist, id))
                    },
                )
            }
        }
    }
}

@Composable
private fun LaunchGate() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 6.dp,
                shadowElevation = 10.dp,
                modifier = Modifier.size(88.dp),
            ) {
                androidx.compose.foundation.Image(
                    painter = painterResource(com.lastwave.app.R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.height(18.dp))
            Text("LastWave", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(20.dp))
            ExpressiveLoadingIndicator(message = "Preparing your music")
        }
    }
}
