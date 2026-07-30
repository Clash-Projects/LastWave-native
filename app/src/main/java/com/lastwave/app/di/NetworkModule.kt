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
        return OkHttpClient.Builder()
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
