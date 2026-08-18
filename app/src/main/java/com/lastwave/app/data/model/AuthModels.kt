package com.lastwave.app.data.model

/** Screen-facing auth state. No browser/WebView step anywhere in this flow
 *  anymore — signing in is just API key + API secret + username, verified
 *  with one unsigned Last.fm read call (user.getInfo). */
sealed interface AuthState {
    /** Not yet resolved — DataStore hasn't emitted its first read. Distinct
     *  from [SignedOut] on purpose: without this, the app can't tell "we
     *  haven't checked session state yet" from "we checked, you're logged
     *  out", and ends up flashing the login form on every launch even when
     *  a valid session exists. */
    data object Unknown : AuthState
    data object SignedOut : AuthState
    /** Full account-free player/search mode. Last.fm-only profile screens
     *  simply have no remote profile data until an account is connected. */
    data object Guest : AuthState
    data object SigningIn : AuthState
    data class SignedIn(val username: String) : AuthState
    data class Error(val message: String) : AuthState
}
