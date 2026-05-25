package com.multipathvpn

import android.content.Context
import android.os.Looper
import java.io.File

/**
 * Catches all unhandled exceptions and writes them to a file.
 * On next app launch, [getSavedCrash] returns the crash info.
 */
object CrashReporter {

    private const val FILE_NAME = "crash_log.txt"

    fun install(context: Context) {
        val filesDir = context.filesDir
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val msg = buildString {
                    appendLine("=== CRASH ===")
                    appendLine("Thread: ${thread.name}")
                    appendLine("Time: ${System.currentTimeMillis()}")
                    appendLine("Exception: ${throwable::class.simpleName}: ${throwable.message}")
                    for ((i, el) in throwable.stackTrace.withIndex()) {
                        if (i > 30) {
                            appendLine("  ... (${throwable.stackTrace.size - 30} more)")
                            break
                        }
                        appendLine("  at ${el.className}.${el.methodName}(${el.fileName}:${el.lineNumber})")
                    }
                    throwable.cause?.let { cause ->
                        appendLine("Caused by: ${cause::class.simpleName}: ${cause.message}")
                        for (el in cause.stackTrace.take(15)) {
                            appendLine("  at ${el.className}.${el.methodName}(${el.fileName}:${el.lineNumber})")
                        }
                    }
                }
                File(filesDir, FILE_NAME).writeText(msg)
            } catch (_: Exception) {}
            handler?.uncaughtException(thread, throwable)
        }
    }

    fun getSavedCrash(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return try {
            file.readText()
        } catch (_: Exception) { null }
    }

    fun clearCrash(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }
}
