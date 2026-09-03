package com.lastwave.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Universal process survival net and crash interceptor.
 *
 * Catches and recovers from unhandled exceptions on both the main UI thread
 * and all background/worker threads (ExoPlayer, OkHttp, Coroutines, Binder,
 * Audio HAL, Coil, RenderThread, OEM system service callbacks).
 *
 * Keeps the application, audio engine, and UI alive across fragmented device
 * manufacturers and custom ROMs without breaking any functionality.
 */
object CrashGuard {

    private const val TAG = "CrashGuard"
    private const val LOG_FILE_NAME = "lastwave_crash_guard.log"
    private const val MAX_LOG_BYTES = 128L * 1024
    private const val STACK_FRAMES_LOGGED = 32

    @Volatile private var logFile: File? = null
    @Volatile private var installed = false

    private val bridgeHandler = Thread.UncaughtExceptionHandler { thread, error ->
        appendLog(thread, error)
        Log.e(TAG, "Uncaught exception on thread ${thread.name}: ${error.javaClass.name} - ${error.message}", error)

        // Fatal VM errors cannot be recovered safely
        if (error is OutOfMemoryError || error is VirtualMachineError) {
            hardExit()
            return@UncaughtExceptionHandler
        }

        // If the main thread's looper dies, restart and keep the looper running
        if (thread === Looper.getMainLooper().thread) {
            enterMainLooperLoop()
        }
        // Background threads: simply swallow so the process and audio playback stay alive.
    }

    fun install(context: Context) {
        if (installed) return
        installed = true

        logFile = runCatching { File(context.applicationInfo.dataDir, LOG_FILE_NAME) }.getOrNull()

        // 1. Intercept all background thread exceptions
        Thread.setDefaultUncaughtExceptionHandler(bridgeHandler)

        // 2. Intercept Main Looper exceptions (NeverCrash loop pattern)
        Handler(Looper.getMainLooper()).post {
            enterMainLooperLoop()
        }
    }

    private fun enterMainLooperLoop() {
        while (true) {
            try {
                Looper.loop()
            } catch (t: Throwable) {
                if (t is OutOfMemoryError || t is VirtualMachineError) {
                    hardExit()
                    break
                }
                appendLog(Looper.getMainLooper().thread, t)
                Log.e(TAG, "Main looper caught and suppressed exception: ${t.javaClass.name} - ${t.message}", t)
            }
        }
    }

    private fun appendLog(thread: Thread, error: Throwable) {
        val file = logFile ?: return
        runCatching {
            synchronized(this@CrashGuard) {
                if (file.length() > MAX_LOG_BYTES) file.writeText("")
                val timestamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                file.appendText(
                    buildString {
                        append(timestamp).append(" [").append(thread.name).append("] ")
                        append(error.javaClass.name).append(": ").append(error.message).append('\n')
                        error.stackTrace.take(STACK_FRAMES_LOGGED)
                            .forEach { append("  at ").append(it).append('\n') }
                        append('\n')
                    },
                )
            }
        }
    }

    private fun hardExit() {
        android.os.Process.killProcess(android.os.Process.myPid())
        Runtime.getRuntime().exit(10)
    }
}
