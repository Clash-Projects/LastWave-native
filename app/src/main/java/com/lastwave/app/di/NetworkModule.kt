package com.lastwave.app.di

import android.content.Context
import com.lastwave.app.BuildConfig
import com.lastwave.app.data.network.LastFmApiService
import com.lastwave.app.data.network.LastFmRateGuard
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    // Metadata/JSON responses are small; a large HTTP cache only duplicates
    // data already held by Room and the dedicated artwork/media caches.
    private const val HTTP_CACHE_SIZE = 8L * 1024 * 1024
    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 LastWave/1.0"

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context,
        rateGuard: LastFmRateGuard,
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            // Release logging still formats every request and writes logcat
            // on the networking threads. Keep it for diagnostics only.
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        // Keep enough parallelism for Home's batched metadata calls without
        // allowing a slow network to retain dozens of response buffers and
        // coroutines on low-memory devices. Artwork uses its own dispatcher.
        val dispatcher = Dispatcher().apply {
            maxRequests = 24
            maxRequestsPerHost = 8
        }

        val cacheDir = File(context.cacheDir, "lfm_http_cache")
        val cache = Cache(cacheDir, HTTP_CACHE_SIZE)

        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .cache(cache)
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val original = chain.request()
                val isLastFm = original.url.host.endsWith("audioscrobbler.com", ignoreCase = true)
                val request = if (original.header("User-Agent") != null) {
                    original
                } else {
                    original.newBuilder()
                        .header("User-Agent", BROWSER_USER_AGENT)
                        .header("Accept", "application/json, text/plain, */*")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .build()
                }

                if (!isLastFm) {
                    chain.proceed(request)
                } else {
                    val response = chain.proceed(request)
                    if (response.code == 429 || response.code == 503) {
                        response.closeQuietly()
                        rateGuard.onRequestLimited()
                        runCatching { Thread.sleep(1500L) }
                        chain.proceed(request)
                    } else {
                        if (response.isSuccessful) rateGuard.onRequestSucceeded()
                        response
                    }
                }
            }
            .addInterceptor(logging)
            .build()
    }

    private fun Response.closeQuietly() {
        runCatching { close() }
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(LastFmApiService.BASE_URL)
            .client(client)
            .build()

    @Provides
    @Singleton
    fun provideLastFmApiService(retrofit: Retrofit): LastFmApiService =
        retrofit.create(LastFmApiService::class.java)
}
