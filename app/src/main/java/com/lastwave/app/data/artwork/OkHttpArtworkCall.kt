package com.lastwave.app.data.artwork

import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

internal suspend fun Call.awaitSuccessfulBodyOrNull(): String? =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resume(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = try {
                    response.use { if (it.isSuccessful) it.body?.string() else null }
                } catch (_: Exception) {
                    null
                }
                if (continuation.isActive) continuation.resume(body)
            }
        })
    }
