package com.lastwave.app.data.network

import java.security.MessageDigest

/**
 * Port of app.js's _normaliseKey() / _lfmSig(). Last.fm's signing spec
 * (last.fm/api/authspec):
 *  1. Take all params except "format", "callback", "api_sig"
 *  2. Sort keys alphabetically
 *  3. Concatenate key+value pairs with no separator
 *  4. Append the API secret
 *  5. MD5-hash the UTF-8 bytes -> lowercase hex
 */
object LastFmSigner {

    private val NON_HEX = Regex("[^a-fA-F0-9]")
    private val WHITESPACE = Regex("[\\s\\u00A0\\u200B\\u200C\\u200D\\uFEFF]")

    /** Strips everything but hex characters and lowercases — API keys/secrets
     *  are always 32-char lowercase hex, so any stray whitespace or invisible
     *  unicode pasted in from a browser is silently cleaned up. */
    fun normalizeKey(raw: String): String =
        raw.replace(WHITESPACE, "").replace(NON_HEX, "").lowercase()

    private val SKIP = setOf("format", "callback", "api_sig")

    fun sign(params: Map<String, String>, secret: String): String {
        val base = params
            .filterKeys { it !in SKIP }
            .toSortedMap()
            .entries
            .joinToString(separator = "") { (k, v) -> k + v } + secret
        return md5(base)
    }

    fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
