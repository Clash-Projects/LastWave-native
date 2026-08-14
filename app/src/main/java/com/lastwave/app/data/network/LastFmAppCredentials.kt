package com.lastwave.app.data.network

/**
 * LastWave's own registered Last.fm API application, baked into the app
 * itself — per explicit request, so nobody signing in ever has to go find
 * or paste an API key/secret. This is the same pattern most published
 * Last.fm apps use: one app-level key identifies the APPLICATION to
 * Last.fm, not the individual person signing into it; each person's own
 * identity comes from the session key they get during their own sign-in
 * (see AuthRepository.beginWebAuth / completeWebAuth), not from this key.
 *
 * Registered at last.fm/api/account/create under the app name "LastWave".
 */
object LastFmAppCredentials {
    const val API_KEY = "e2c8e7a67eaeb0fe5a71ee539a34641a"
    const val API_SECRET = "94b5c6aa634e459defedbf8180625e8a"
}
