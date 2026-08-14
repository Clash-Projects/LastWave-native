package com.lastwave.app.di

import com.lastwave.app.data.network.LastFmApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            // Last.fm URLs never carry the session key or secret in the path/query
            // for GET reads, but signed POST bodies do — keep logging headers-only
            // in release builds; BuildConfig gating is left to the app's own logger.
            level = HttpLoggingInterceptor.Level.BASIC
        }
        // The real reason cover art (and honestly every other Last.fm call)
        // could feel slow: OkHttp's DEFAULT Dispatcher caps concurrent
        // requests at just 5 per host — and every single call this app
        // makes (artwork lookups, recent-tracks polling, search, friends,
        // scrobbling) shares the exact same host. A list of 20-30 tracks
        // needing artwork queues up into slow waves of 5 sequential
        // batches, competing with everything else hitting that host at the
        // same time, purely because of this client-side self-imposed
        // limit — Last.fm's servers handle far more concurrency than that
        // from a single app just fine. Matched the connection pool's max
        // idle connections to the same number so those extra concurrent
        // requests don't just churn through new TCP/TLS handshakes instead
        // of reusing warm connections.
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 24
        }
        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(okhttp3.ConnectionPool(24, 5, TimeUnit.MINUTES))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()
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
