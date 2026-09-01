package com.lastwave.app.data.music

import android.net.Uri
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.jvm.functions.Function1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import com.lastwave.app.data.ytmusic.YtMusicAuthManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small compatibility boundary for InnerTubeX. The published 0.4.x artifact
 * is built with a newer Kotlin metadata version than the app, so this adapter
 * calls its public API reflectively while keeping the rest of the player typed.
 */
@Singleton
class InnerTubeXStreamExtractor @Inject constructor(
    private val ytAuth: YtMusicAuthManager,
) {
    private val failedProfilesUntil = ConcurrentHashMap<String, Long>()
    private val runtime: RuntimeStack? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching { createRuntime() }
            .onFailure { Log.w(TAG, "InnerTubeX unavailable; legacy resolver remains fallback", it) }
            .getOrNull()
    }

    suspend fun resolve(
        videoId: String,
        visitorData: String?,
        authScope: String,
    ): YouTubeAudioStream? = withContext(Dispatchers.IO) {
        val stack = runtime ?: return@withContext null
        try {
            val connection = ytAuth.connection.value
            setProperty(stack.innerTube, "VisitorData", visitorData)
            setProperty(stack.innerTube, "Cookie", ytAuth.cookieHeaderValue())
            setProperty(stack.innerTube, "UseLoginForBrowse", connection.isConnected)

            val hints = newInstance("com.metrolist.innertubex.extraction.ContentHints")
            val withCapabilities = hints.javaClass.methods.firstOrNull {
                it.name == "withStreamCapabilities" && it.parameterCount == 3
            } ?: return@withContext null
            // The existing Media3 graph consumes complete HTTP/HLS URLs. Keep
            // SABR/segmented transports out of that graph; InnerTubeX will
            // continue through its direct/HLS client fallbacks instead.
            val constrainedHints = withCapabilities.invoke(hints, true, false, false)
            val audioQuality = enumValue("com.metrolist.innertubex.extraction.AudioQuality", "HIGH")
            val now = System.currentTimeMillis()
            failedProfilesUntil.entries.removeIf { it.value <= now }
            val excludedProfiles = failedProfilesUntil
                .filter { (key, expiry) -> key.startsWith("$videoId|$authScope|") && expiry > now }
                .mapTo(mutableSetOf()) { it.key.substringAfterLast('|') }
            val extractMethod = stack.extractor.javaClass.methods.firstOrNull {
                it.name == "extract" && it.parameterCount == 6
            } ?: return@withContext null
            val extracted = invokeSuspend(
                receiver = stack.extractor,
                method = extractMethod,
                arguments = arrayOf(
                    videoId,
                    constrainedHints,
                    excludedProfiles,
                    audioQuality,
                    UUID.randomUUID().toString().replace("-", ""),
                ),
            ) ?: return@withContext null
            extracted.toAudioStream(videoId, authScope)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: LinkageError) {
            logDiagnostics(videoId, error)
            Log.w(TAG, "InnerTubeX extraction unavailable for ${videoId.take(32)} (${error::class.simpleName})")
            null
        } catch (error: Exception) {
            logDiagnostics(videoId, error)
            Log.w(TAG, "InnerTubeX extraction failed for ${videoId.take(32)} (${error::class.simpleName})")
            null
        }
    }

    fun reportPlaybackFailure(videoId: String, authScope: String, clientProfile: String?) {
        val profile = clientProfile
            ?.removePrefix("INNERTUBEX:")
            ?.takeIf(String::isNotBlank)
            ?: return
        failedProfilesUntil["$videoId|$authScope|$profile"] =
            System.currentTimeMillis() + CLIENT_FAILURE_COOLDOWN_MS
    }

    private fun Any.toAudioStream(videoId: String, authScope: String): YouTubeAudioStream? {
        val url = readString(this, "getAudioUrl").orEmpty()
        if (url.isBlank() || url.startsWith("sabr:", ignoreCase = true)) return null
        val mime = readString(this, "getMimeType")?.substringBefore(';')?.trim().orEmpty()
        val isHls = mime.equals("application/x-mpegurl", true) || url.substringBefore('?').endsWith(".m3u8", true)
        if (!isHls && !mime.startsWith("audio/", true)) return null
        if (!isHls && mime.lowercase() !in setOf("audio/webm", "audio/mp4", "audio/m4a", "audio/ogg", "audio/mpeg")) return null
        if (!url.startsWith("https://", true)) return null

        val rawHeaders = (readValue(this, "getHeaders") as? Map<*, *>)
            ?.mapNotNull { (name, value) ->
                val key = name as? String
                val headerValue = value as? String
                if (key.isNullOrBlank() || headerValue.isNullOrBlank()) null else key to headerValue
            }
            ?.toMap()
            .orEmpty()
        val clientName = readString(this, "getClientName").orEmpty()
        val profileId = readString(this, "getProfileId").orEmpty()
        val headers = rawHeaders.toMutableMap().apply {
            if (keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                diagnosticUserAgent(readValue(this@toAudioStream, "getStreamDiagnostics"), clientName, profileId)
                    ?.let { put("User-Agent", it) }
            }
        }
        val expiresAt = extractExpiry(url) ?: instantToEpochMillis(readValue(this, "getExpiresAt"))
        val durationMs = readValue(this, "getMediaMetadata")
            ?.let { readLong(it, "getDurationSeconds") }
            ?.times(1_000L)
        val profile = profileId.ifBlank { clientName.ifBlank { "INNERTUBEX" } }
        return YouTubeAudioStream(
            videoId = videoId,
            url = url,
            itag = readInt(this, "getItag"),
            mimeType = mime.ifBlank { null },
            codec = if (isHls) "hls" else readString(this, "getCodecs"),
            bitrate = readInt(this, "getBitrate") ?: 0,
            sampleRateHz = readInt(this, "getSampleRate"),
            durationMs = durationMs,
            contentLength = readLong(this, "getContentLengthBytes"),
            isAdaptive = isHls,
            clientProfile = "INNERTUBEX:$profile",
            authScope = authScope,
            requestHeaders = headers,
            expiresAtEpochMs = expiresAt ?: (System.currentTimeMillis() + UNKNOWN_STREAM_EXPIRY_TTL_MS),
        )
    }

    private fun createRuntime(): RuntimeStack {
        val httpClient = createKtorHttpClient()
        val repository = companionCall(
            "com.metrolist.innertubex.cipher.PlayerConfigRepository",
            "disabled",
        )
        val configStore = constructWithDefaults(
            "com.metrolist.innertubex.cipher.RemotePlayerConfigStore",
            arrayOf(httpClient, repository),
            defaultMask = 4,
        )
        val innerTube = constructWithDefaults(
            "com.metrolist.innertubex.InnerTube",
            arrayOf(httpClient),
            defaultMask = 6,
        )
        val cipher = constructWithDefaults(
            "com.metrolist.innertubex.cipher.YouTubeCipherService",
            arrayOf(httpClient, configStore),
            defaultMask = 4,
        )
        val parser = constructWithDefaults(
            "com.metrolist.innertubex.extraction.YtConfigParserImpl",
            arrayOf(httpClient, innerTube, configStore),
            defaultMask = 8,
        )
        val extractor = constructWithDefaults(
            "com.metrolist.innertubex.extraction.InnerTubeExtractor",
            arrayOf(parser, cipher, innerTube),
            defaultMask = 120,
        )
        return RuntimeStack(innerTube, extractor)
    }

    private fun createKtorHttpClient(): Any {
        val candidates = listOf("io.ktor.client.HttpClientJvmKt", "io.ktor.client.HttpClientKt")
        for (className in candidates) {
            val method = runCatching {
                Class.forName(className).methods.firstOrNull {
                    it.name == "HttpClient" && Modifier.isStatic(it.modifiers) &&
                        (it.parameterCount == 0 ||
                            (it.parameterCount == 1 && Function1::class.java.isAssignableFrom(it.parameterTypes[0])))
                }
            }.getOrNull() ?: continue
            return if (method.parameterCount == 0) {
                method.invoke(null)
            } else {
                method.invoke(null, Function1<Any?, Any?> { Unit })
            }
        }
        error("Ktor JVM HttpClient factory was not found")
    }

    private suspend fun invokeSuspend(
        receiver: Any,
        method: Method,
        arguments: Array<Any?>,
    ): Any? = suspendCancellableCoroutine { continuation ->
        val completion = object : Continuation<Any?> {
            override val context: CoroutineContext = continuation.context

            override fun resumeWith(result: Result<Any?>) {
                continuation.resumeWith(result)
            }
        }
        try {
            val result = method.invoke(receiver, *(arguments + completion))
            if (result !== COROUTINE_SUSPENDED && continuation.isActive) continuation.resume(result)
        } catch (error: InvocationTargetException) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(error.targetException))
        } catch (error: Throwable) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(error))
        }
    }

    private fun companionCall(className: String, methodName: String): Any {
        val companion = Class.forName("$className\$Companion")
            .getField("INSTANCE")
            .get(null)
        return companion.javaClass.getMethod(methodName).invoke(companion)
    }

    private fun constructWithDefaults(
        className: String,
        required: Array<Any?>,
        defaultMask: Int,
    ): Any {
        val type = Class.forName(className)
        val constructor = type.declaredConstructors.firstOrNull { candidate ->
            candidate.parameterCount >= required.size + 2 &&
                candidate.parameterTypes[candidate.parameterCount - 2] == Int::class.javaPrimitiveType &&
                candidate.parameterTypes.last().name == "kotlin.jvm.internal.DefaultConstructorMarker" &&
                required.withIndex().all { (index, value) ->
                    value == null || candidate.parameterTypes[index].isAssignableFrom(value.javaClass)
                }
        } ?: error("No compatible constructor for $className")
        constructor.isAccessible = true
        val originalParameterCount = constructor.parameterCount - 2
        val arguments = arrayOfNulls<Any?>(constructor.parameterCount)
        required.copyInto(arguments)
        arguments[originalParameterCount] = defaultMask
        return constructor.newInstance(*arguments)
    }

    private fun setProperty(instance: Any, suffix: String, value: Any?) {
        instance.javaClass.methods.firstOrNull {
            it.name == "set$suffix" && it.parameterCount == 1
        }?.invoke(instance, value)
    }

    private fun newInstance(className: String): Any =
        Class.forName(className).getDeclaredConstructor().newInstance()

    private fun enumValue(className: String, name: String): Any =
        (Class.forName(className).enumConstants as Array<*>).first { (it as Enum<*>).name == name }!!

    private fun readValue(instance: Any, getter: String): Any? =
        instance.javaClass.methods.firstOrNull { it.name == getter && it.parameterCount == 0 }?.invoke(instance)

    private fun readString(instance: Any, getter: String): String? = readValue(instance, getter) as? String

    private fun readInt(instance: Any, getter: String): Int? = (readValue(instance, getter) as? Number)?.toInt()

    private fun readLong(instance: Any, getter: String): Long? = (readValue(instance, getter) as? Number)?.toLong()

    private fun diagnosticUserAgent(diagnostics: Any?, clientName: String, profileId: String): String? {
        val attempts = readValue(diagnostics ?: return null, "getAttempts") as? Iterable<*> ?: return null
        val match = attempts.firstOrNull { attempt ->
            attempt != null &&
                (readString(attempt, "getClientName") == clientName || clientName.isBlank()) &&
                (readString(attempt, "getProfileId") == profileId || profileId.isBlank())
        } ?: return null
        return readString(match, "getUserAgent")?.takeIf(String::isNotBlank)
    }

    private fun logDiagnostics(videoId: String, error: Throwable) {
        val diagnostics = readValue(error, "getDiagnostics") ?: return
        val attempts = readValue(diagnostics, "getAttempts") as? Iterable<*> ?: return
        attempts.forEach { attempt ->
            if (attempt == null) return@forEach
            Log.w(
                TAG,
                "stage=extract videoId=${videoId.take(32)} client=${readString(attempt, "getClientName").orEmpty()} " +
                    "profile=${readString(attempt, "getProfileId").orEmpty()} " +
                    "outcome=${readString(attempt, "getOutcome").orEmpty()}",
            )
        }
    }

    private fun extractExpiry(url: String): Long? =
        Uri.parse(url).getQueryParameter("expire")?.toLongOrNull()?.times(1_000L)

    private fun instantToEpochMillis(value: Any?): Long? {
        value ?: return null
        val method = value.javaClass.methods.firstOrNull {
            it.name == "toEpochMilliseconds" && it.parameterCount == 0
        } ?: return null
        return (runCatching { method.invoke(value) }.getOrNull() as? Number)?.toLong()
    }

    private data class RuntimeStack(
        val innerTube: Any,
        val extractor: Any,
    )

    private companion object {
        const val TAG = "InnerTubeXStreamExtractor"
        const val UNKNOWN_STREAM_EXPIRY_TTL_MS = 5 * 60 * 1_000L
        const val CLIENT_FAILURE_COOLDOWN_MS = 60_000L
    }
}
