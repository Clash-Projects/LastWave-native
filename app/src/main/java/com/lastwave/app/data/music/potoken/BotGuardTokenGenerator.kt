package com.lastwave.app.data.music.potoken

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class PoTokenResult(
    val playerToken: String,
    val sessionToken: String,
)

/**
 * Headless BotGuard engine that mints valid YouTube Proof-of-Origin (`po_token`)
 * values by running Google's JS challenge in a lightweight Android WebView.
 */
object BotGuardTokenGenerator {
    private const val TAG = "BotGuardTokenGen"
    private const val CREATE_URL = "https://www.youtube.com/api/jnn/v1/Create"
    private const val GENERATE_IT_URL = "https://www.youtube.com/api/jnn/v1/GenerateIT"
    private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
    private const val JS_BRIDGE = "BotGuardBridge"

    private const val COLD_START_TIMEOUT_MS = 1_500L
    private const val WARM_TIMEOUT_MS = 1_000L

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val mutex = Mutex()
    private var appContext: Context? = null
    private var engine: BotGuardEngine? = null
    private var engineSessionId: String? = null
    private var cachedSessionToken: String? = null
    private var engineReady = false
    private var permanentlyBroken = false

    private val playerTokenCache = LruCache<String, String>(200)

    @MainThread
    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        Log.d(TAG, "BotGuard Token Generator initialized")
    }

    suspend fun preWarm(sessionId: String = "lastwave_session") {
        val ctx = appContext ?: return
        if (permanentlyBroken || sessionId.isBlank()) return
        runCatching {
            withTimeoutOrNull(COLD_START_TIMEOUT_MS) {
                ensureEngineReady(ctx, sessionId)
            }
        }
    }

    suspend fun mintToken(
        videoId: String,
        sessionId: String = "lastwave_session",
    ): PoTokenResult? {
        val ctx = appContext ?: return null
        if (permanentlyBroken) return null

        mutex.withLock {
            if (isEngineReadyForSession(sessionId)) {
                val cachedPlayer = playerTokenCache.get(videoId)
                val sessionToken = cachedSessionToken
                if (cachedPlayer != null && sessionToken != null) {
                    return PoTokenResult(playerToken = cachedPlayer, sessionToken = sessionToken)
                }
            }
        }

        val timeout = if (!isEngineReadyForSession(sessionId)) COLD_START_TIMEOUT_MS else WARM_TIMEOUT_MS

        return try {
            withTimeoutOrNull(timeout) {
                val result = mintTokenInternal(ctx, videoId, sessionId, forceNewEngine = false)
                mutex.withLock {
                    playerTokenCache.put(videoId, result.playerToken)
                }
                result
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Log.w(TAG, "PO Token minting failed for $videoId: ${e.message}")
            null
        }
    }

    private suspend fun ensureEngineReady(ctx: Context, sessionId: String) {
        getOrCreateEngine(ctx, sessionId, forceNewEngine = false)
    }

    private suspend fun mintTokenInternal(
        ctx: Context,
        videoId: String,
        sessionId: String,
        forceNewEngine: Boolean,
    ): PoTokenResult {
        val (eng, sessionTok, wasNew) = getOrCreateEngine(ctx, sessionId, forceNewEngine)
        val playerTok = try {
            eng.mint(videoId)
        } catch (e: Throwable) {
            if (wasNew) throw e
            Log.w(TAG, "Mint failed on existing engine; retrying with fresh engine")
            return mintTokenInternal(ctx, videoId, sessionId, forceNewEngine = true)
        }
        return PoTokenResult(playerToken = playerTok, sessionToken = sessionTok)
    }

    private suspend fun getOrCreateEngine(
        ctx: Context,
        sessionId: String,
        forceNewEngine: Boolean,
    ): Triple<BotGuardEngine, String, Boolean> = mutex.withLock {
        val needsNew = forceNewEngine || !isEngineReadyForSession(sessionId)
        if (needsNew) {
            withContext(Dispatchers.Main) {
                engine?.close()
            }
            engine = null
            engineSessionId = null
            cachedSessionToken = null
            engineReady = false
            playerTokenCache.evictAll()

            val newEngine = BotGuardEngine.create(ctx)
            val newSessionToken = try {
                newEngine.mint(sessionId)
            } catch (error: Throwable) {
                withContext(Dispatchers.Main) {
                    newEngine.close()
                }
                throw error
            }

            engine = newEngine
            engineSessionId = sessionId
            cachedSessionToken = newSessionToken
            engineReady = true
        }

        Triple(requireNotNull(engine), requireNotNull(cachedSessionToken), needsNew)
    }

    private fun isEngineReadyForSession(sessionId: String): Boolean =
        engineReady && engineSessionId == sessionId && cachedSessionToken != null && engine?.isExpired == false

    private class BotGuardEngine private constructor(
        private val webView: WebView,
        private val readyDeferred: CompletableDeferred<BotGuardEngine>,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val closed = AtomicBoolean(false)
        private val pendingMints = ConcurrentHashMap<String, CompletableDeferred<String>>()
        private var expiresAtMs: Long = System.currentTimeMillis() + 50 * 60 * 1000L

        val isExpired: Boolean get() = System.currentTimeMillis() > expiresAtMs

        fun startBootstrap() {
            scope.launch {
                val html = withContext(Dispatchers.IO) {
                    webView.context.assets.open("po_token.html").bufferedReader().use { it.readText() }
                }
                val patched = html.replaceFirst("</script>", "\n$JS_BRIDGE.onPageLoaded()</script>")
                webView.loadDataWithBaseURL("https://www.youtube.com", patched, "text/html", "utf-8", null)
            }
        }

        @JavascriptInterface
        fun onPageLoaded() {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val requestBody = "[ \"$REQUEST_KEY\" ]".toRequestBody("application/json".toMediaType())
                    val request = Request.Builder().url(CREATE_URL).post(requestBody).build()
                    val response = httpClient.newCall(request).execute().use { it.body?.string().orEmpty() }
                    val challengeJson = parseCreateChallenge(response)

                    withContext(Dispatchers.Main) {
                        webView.evaluateJavascript(
                            """
                            try {
                                var data = $challengeJson;
                                runBotGuard(data).then(function(r) {
                                    window.webPoSignalOutput = r.webPoSignalOutput;
                                    $JS_BRIDGE.onBotGuardReady(r.botguardResponse);
                                }, function(e) {
                                    $JS_BRIDGE.onFatalError(e + "\n" + e.stack);
                                });
                            } catch(e) { $JS_BRIDGE.onFatalError(e + "\n" + e.stack); }
                            """.trimIndent(),
                            null,
                        )
                    }
                }.onFailure { error ->
                    Log.e(TAG, "Create challenge request failed", error)
                    readyDeferred.completeExceptionally(error)
                }
            }
        }

        @JavascriptInterface
        fun onBotGuardReady(botguardResponse: String) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val requestBody = "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]".toRequestBody("application/json".toMediaType())
                    val request = Request.Builder().url(GENERATE_IT_URL).post(requestBody).build()
                    val response = httpClient.newCall(request).execute().use { it.body?.string().orEmpty() }
                    val (tokenU8, lifetimeSec) = parseIntegrityToken(response)
                    expiresAtMs = System.currentTimeMillis() + (lifetimeSec - 300L) * 1000L

                    withContext(Dispatchers.Main) {
                        webView.evaluateJavascript(
                            """
                            try {
                                createPoTokenMinter(window.webPoSignalOutput, $tokenU8).then(function() {
                                    $JS_BRIDGE.onMinterReady();
                                }).catch(function(e) {
                                    $JS_BRIDGE.onFatalError(e + "\n" + (e.stack || ''));
                                });
                            } catch(e) { $JS_BRIDGE.onFatalError(e + "\n" + e.stack); }
                            """.trimIndent(),
                            null,
                        )
                    }
                }.onFailure { error ->
                    Log.e(TAG, "GenerateIT request failed", error)
                    readyDeferred.completeExceptionally(error)
                }
            }
        }

        @JavascriptInterface
        fun onMinterReady() {
            readyDeferred.complete(this@BotGuardEngine)
        }

        @JavascriptInterface
        fun onFatalError(error: String) {
            Log.e(TAG, "BotGuard JS fatal error: $error")
            readyDeferred.completeExceptionally(IllegalStateException("BotGuard JS error: $error"))
        }

        suspend fun mint(identifier: String): String = withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<String>()
            pendingMints[identifier] = deferred
            val u8Arg = stringToJsUint8Array(identifier)

            webView.evaluateJavascript(
                """
                try {
                    obtainPoToken($u8Arg).then(function(u8) {
                        $JS_BRIDGE.onMintOk("$identifier", Array.from(u8).join(","));
                    }).catch(function(e) {
                        $JS_BRIDGE.onMintErr("$identifier", e + "\n" + (e.stack || ''));
                    });
                } catch(e) { $JS_BRIDGE.onMintErr("$identifier", e + "\n" + e.stack); }
                """.trimIndent(),
                null,
            )
            deferred.await()
        }

        @JavascriptInterface
        fun onMintOk(identifier: String, csvBytes: String) {
            val base64 = commaSeparatedBytesToBase64(csvBytes)
            pendingMints.remove(identifier)?.complete(base64)
        }

        @JavascriptInterface
        fun onMintErr(identifier: String, error: String) {
            Log.e(TAG, "Mint error for $identifier: $error")
            pendingMints.remove(identifier)?.completeExceptionally(IllegalStateException("Mint error: $error"))
        }

        fun close() {
            if (closed.compareAndSet(false, true)) {
                webView.stopLoading()
                webView.removeJavascriptInterface(JS_BRIDGE)
                webView.destroy()
            }
        }

        companion object {
            @SuppressLint("SetJavaScriptEnabled")
            suspend fun create(context: Context): BotGuardEngine = withContext(Dispatchers.Main) {
                val readyDeferred = CompletableDeferred<BotGuardEngine>()
                val webView = WebView(context.applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                }
                val engine = BotGuardEngine(webView, readyDeferred)
                webView.addJavascriptInterface(engine, JS_BRIDGE)
                engine.startBootstrap()
                readyDeferred.await()
            }
        }
    }
}
