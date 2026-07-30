package com.lastwave.app.data.network

/** Thrown for any Last.fm API-level error (HTTP-200-with-error-body,
 *  network failure, or malformed response). [message] is already the
 *  user-friendly string — show it directly. */
class LastFmException(message: String, val code: Int? = null) : Exception(message)

/** Port of _lfmFriendlyError() in app.js — see last.fm/api/errorcodes. */
object LastFmErrors {
    private val MESSAGES = mapOf(
        2 to "Invalid service — please contact support.",
        3 to "Invalid method — please update the app.",
        4 to "Authentication failed — your API key or secret may be wrong. Double-check them in Settings.",
        5 to "Invalid format specified.",
        6 to "Invalid parameters — one or more required fields are missing.",
        7 to "Invalid resource.",
        8 to "Operation failed — try again in a moment.",
        9 to "Invalid session key — please sign in again.",
        10 to "Invalid API key — paste your key from last.fm/api/accounts exactly.",
        11 to "Service temporarily offline — try again later.",
        13 to "Invalid API signature — make sure your API secret is pasted correctly with no extra spaces.",
        14 to "Unauthorized token — please authorize the app in the browser first.",
        15 to "This token has expired — please sign in again.",
        16 to "Service temporarily unavailable — try again in a few seconds.",
        26 to "Suspended API key — contact Last.fm support.",
        29 to "Rate limit exceeded — please wait a moment then try again.",
    )

    fun friendlyMessage(code: Int?, rawMessage: String?): String =
        code?.let { MESSAGES[it] } ?: rawMessage ?: "Auth error (code ${code ?: "unknown"})"
}
