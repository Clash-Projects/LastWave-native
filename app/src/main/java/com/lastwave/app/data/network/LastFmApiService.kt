package com.lastwave.app.data.network

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.QueryMap

/**
 * Last.fm exposes every method (auth, user, track, artist, chart, tag...)
 * through the same endpoint with a `method` query/form param, rather than
 * one REST path per resource — so a single generic GET/POST pair mirrors
 * lfmCall()/lfmCallSigned() from app.js. Feature modules add typed request
 * builders + response DTOs on top of this as they're migrated (Home, Discover,
 * etc.) rather than each needing a new Retrofit method here.
 */
interface LastFmApiService {

    /** Unsigned GET — used for reads that don't require a session (search,
     *  chart, artist/track info, auth.getToken). */
    @GET("2.0/")
    suspend fun get(@QueryMap params: Map<String, String>): Response<ResponseBody>

    /** Signed POST — used for auth.getSession and any authenticated write
     *  (scrobble and love/unlove). Params must already include
     *  api_sig and format, added by the caller after signing. */
    @FormUrlEncoded
    @POST("2.0/")
    suspend fun post(@FieldMap params: Map<String, String>): Response<ResponseBody>

    companion object {
        const val BASE_URL = "https://ws.audioscrobbler.com/"
    }
}
