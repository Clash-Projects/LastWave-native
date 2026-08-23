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

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private class NetworkResilienceInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            var attempt = 0
            var lastException: IOException? = null
            val isIdempotent = request.method.equals("GET", ignoreCase = true) || request.method.equals("HEAD", ignoreCase = true)

            while (attempt < (if (isIdempotent) 3 else 1)) {
                attempt++
                try {
                    val response = chain.proceed(request)
                    if (isIdempotent && (response.code == 502 || response.code == 503 || response.code == 504) && attempt < 3) {
                        response.close()
                        val jitter = (Math.random() * 100).toLong()
                        try { Thread.sleep(200L * attempt + jitter) } catch (_: InterruptedException) {}
                        continue
                    }
                    return response
                } catch (e: IOException) {
                    lastException = e
                    if (!isIdempotent || attempt >= 3) throw e
                    val jitter = (Math.random() * 100).toLong()
                    try { Thread.sleep(200L * attempt + jitter) } catch (_: InterruptedException) {}
                }
            }
            throw lastException ?: IOException("Request failed after $attempt attempts")
        }
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 24
        }
        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(okhttp3.ConnectionPool(24, 5, TimeUnit.MINUTES))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(NetworkResilienceInterceptor())
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
