package com.lastwave.app

import android.content.Context
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last-resort process survival net for device/ROM defects.
 *
 * Custom ROMs and vendor builds routinely let stray runtime exceptions escape
 * threads this app does not own: binder pool threads, audio HAL callbacks,
 * codec workers, WebView glue. The platform default handler kills the whole
 * process for any of them even though the app itself was healthy. This guard
 * keeps the process alive when a non-main thread dies, while still delegating
 * main-thread failures to the system (a swallowed UI-thread crash leaves a
 * frozen, unresponsive app which is worse than a visible crash dialog).
 *
 * Swallowing is rate-limited: if background threads start failing in a tight
 * loop the process is handed back to the default handler instead of spinning
 * forever on battery.
 */
object CrashGuard {

    private const val LOG_FILE_NAME = "lastwave_crash_guard.log"
    private const val MAX_LOG_BYTES = 64L * 1024
    private const val STACK_FRAMES_LOGGED = 24
    private const val MAX_SWALLOWS_PER_WINDOW = 5
    private const val WINDOW_MS = 60_000L

    @Volatile private var logFile: File? = null

    @Volatile private var previousHandler: Thread.UncaughtExceptionHandler? = null

    /** Swallow timestamps inside the current rate-limit window. */
    private val swallowTimes = ArrayDeque<Long>()

    private val bridgeHandler = Thread.UncaughtExceptionHandler { thread, error ->
        appendLog(thread, error)
        val mustDelegate =
            thread === Looper.getMainLooper().thread ||
                !maySwallow()
        if (mustDelegate) {
            delegateToDefault(thread, error)
        }
        // Otherwise: swallow. The dead thread simply terminates; every other
        // thread, the player engine and all UI surfaces stay alive.
    }

    fun install(context: Context) {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current === bridgeHandler) return
        previousHandler = current
        logFile = runCatching { File(context.applicationInfo.dataDir, LOG_FILE_NAME) }.getOrNull()
        Thread.setDefaultUncaughtExceptionHandler(bridgeHandler)
    }

    private fun maySwallow(): Boolean = synchronized(swallowTimes) {
        val now = System.currentTimeMillis()
        while (swallowTimes.isNotEmpty() && now - swallowTimes.first() > WINDOW_MS) {
            swallowTimes.removeFirst()
        }
        if (swallowTimes.size >= MAX_SWALLOWS_PER_WINDOW) return false
        swallowTimes.addLast(now)
        true
    }

    private fun delegateToDefault(thread: Thread, error: Throwable) {
        val previous = previousHandler ?: return hardExit()
        runCatching { previous.uncaughtException(thread, error) }.onFailure { hardExit() }
    }

    private fun appendLog(thread: Thread, error: Throwable) {
        val file = logFile ?: return
        runCatching {
            synchronized(this@CrashGuard) {
                if (file.length() > MAX_LOG_BYTES) file.writeText("")
                val timestamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                file.appendText(
                    buildString {
                        append(timestamp).append(' ').append(thread.name).append('\n')
                        append(error.toString()).append('\n')
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
