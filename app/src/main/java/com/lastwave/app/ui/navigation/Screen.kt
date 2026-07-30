package com.lastwave.app.ui.navigation

/**
 * Route constants for the whole app. Declaring the full set up front — even
 * though only [Login] has a screen behind it yet — means NavGraph.kt's
 * shape doesn't change on every future module; each module just adds its
 * composable() block under the route that's already named here.
 */
sealed class Screen(val route: String) {
    data object Splash : Screen("splash")
    data object Login : Screen("login")
    data object MainShell : Screen("main_shell")

    // Bottom-nav swipeable tabs (Home module wires these + the pager together)
    data object Home : Screen("home")
    data object Create : Screen("create")
    data object Playlist : Screen("playlist")

    // Pushed screens, slide-in from the right (matches PAGE_TRANSITIONS in nav.js)
    data object Discover : Screen("discover")
    data object Genres : Screen("genres")
    data object Search : Screen("search")
    data object Settings : Screen("settings")
}
