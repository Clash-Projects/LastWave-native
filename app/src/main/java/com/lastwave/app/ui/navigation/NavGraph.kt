package com.lastwave.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lastwave.app.data.model.AuthState
import com.lastwave.app.ui.auth.AuthViewModel
import com.lastwave.app.ui.auth.LoginScreen
import com.lastwave.app.ui.auth.openAuthorizeUrl
import com.lastwave.app.ui.settings.SettingsScreen
import com.lastwave.app.ui.search.SearchScreen
import com.lastwave.app.ui.discover.DiscoverScreen
import com.lastwave.app.ui.genres.GenresScreen
import com.lastwave.app.ui.shell.MainShell

@Composable
fun LastWaveNavHost(
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = Screen.Splash.route) {

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
            val authState by authViewModel.authState.collectAsState()

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

            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        composable(Screen.Login.route) {
            val authViewModel: AuthViewModel = hiltViewModel()
            val authState by authViewModel.authState.collectAsState()
            val authorizeUrl by authViewModel.authorizeUrl.collectAsState()
            val credentials by authViewModel.credentials.collectAsState()
            val context = LocalContext.current

            // Fires exactly once per new URL — matches startLastFmAuth()'s
            // single Platform.openAuthBrowser(authUrl) call in app.js. This
            // only ever runs from an explicit "Connect with Last.fm" tap
            // (see AuthViewModel.startAuth), never automatically on launch —
            // browser auth is a first-login-only step once a session persists.
            LaunchedEffect(authorizeUrl) {
                authorizeUrl?.let { openAuthorizeUrl(context, it) }
            }

            // Handles the case where auth succeeds while already sitting on
            // Login (e.g. finishing the OAuth flow) — moves to MainShell and
            // pops Login off the back stack.
            LaunchedEffect(authState) {
                if (authState is AuthState.SignedIn) {
                    navController.navigate(Screen.MainShell.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            }

            LoginScreen(
                authState = authState,
                initialApiKey = credentials.first,
                initialApiSecret = credentials.second,
                onSaveCredentials = authViewModel::saveCredentials,
                onStartAuth = authViewModel::startAuth,
                onContinueAfterAuth = authViewModel::continueAfterAuth,
                onSignOut = authViewModel::signOut,
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
                onOpenSettings = { navController.navigate(Screen.Settings.route) },
                onOpenSearch = { navController.navigate(Screen.Search.route) },
                onOpenDiscover = { navController.navigate(Screen.Discover.route) },
                onOpenGenres = { navController.navigate(Screen.Genres.route) },
            )
        }

        composable(Screen.Genres.route) {
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

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onLoggedOut = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.MainShell.route) { inclusive = true }
                    }
                },
            )
        }

        composable(Screen.Search.route) {
            SearchScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.Discover.route) {
            DiscoverScreen(onBack = { navController.popBackStack() })
        }
    }
}
