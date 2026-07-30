package com.lastwave.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class TokenResponse(
    val token: String? = null,
    val error: Int? = null,
    val message: String? = null,
)

@Serializable
data class SessionEnvelope(
    val session: SessionDto? = null,
    val error: Int? = null,
    val message: String? = null,
)

@Serializable
data class SessionDto(
    val name: String,
    val key: String,
    val subscriber: Int = 0,
)

/** Screen-facing auth state — mirrors state.authState in app.js ('idle' | 'pending' | 'authenticated'). */
sealed interface AuthState {
    /** Not yet resolved — DataStore hasn't emitted its first read. Distinct
     *  from [SignedOut] on purpose: without this, the app can't tell "we
     *  haven't checked session state yet" from "we checked, you're logged
     *  out", and ends up flashing the login form on every launch even when
     *  a valid session exists. */
    data object Unknown : AuthState
    data object SignedOut : AuthState
    data object RequestingToken : AuthState
    data object AwaitingAuthorization : AuthState
    data object ExchangingToken : AuthState
    data class SignedIn(val username: String) : AuthState
    data class Error(val message: String) : AuthState
}
