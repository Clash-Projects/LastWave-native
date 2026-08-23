package com.lastwave.app.data.network

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

@Singleton
class LastFmRateGuard @Inject constructor() {

    private val lock = Any()
    private var cooldownUntilElapsed: Long = 0L

    val cooldownRemainingMs: Long
        get() = synchronized(lock) {
            (cooldownUntilElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        }

    fun onRequestLimited() {
        synchronized(lock) {
            cooldownUntilElapsed = SystemClock.elapsedRealtime() + 2_000L
        }
    }

    fun onRequestSucceeded() {
        synchronized(lock) {
            cooldownUntilElapsed = 0L
        }
    }

    fun paceOutBlocking() {
        // Non-blocking: allow OkHttp worker threads to proceed immediately
    }

    fun awaitClearanceBlocking(maxWaitMs: Long): Boolean = true

    suspend fun suspendAwaitClearance(maxWaitMs: Long): Boolean {
        val remaining = cooldownRemainingMs
        if (remaining > 0) {
            delay(minOf(remaining, maxWaitMs))
        }
        return true
    }
}

