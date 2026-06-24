package com.zyna.app.util

import android.os.SystemClock
import android.util.Log
import com.zyna.app.BuildConfig
import java.util.Locale

object ZynaPerfLog {
    private const val TAG = "ZynaPerf"
    private const val NANOS_PER_MS = 1_000_000.0

    fun start(): Long {
        if (!BuildConfig.DEBUG) return 0L
        return SystemClock.elapsedRealtimeNanos()
    }

    inline fun mark(message: () -> String) {
        if (!BuildConfig.DEBUG) return
        logMessage(message())
    }

    @Deprecated(
        message = "Use the lazy overload to avoid building log strings in release builds.",
        replaceWith = ReplaceWith("mark { message }"),
        level = DeprecationLevel.ERROR
    )
    fun mark(message: String) {
        if (!BuildConfig.DEBUG) return
        logMessage(message)
    }

    inline fun end(startNanos: Long, label: String, details: () -> String) {
        if (!BuildConfig.DEBUG) return
        logTimed(label, elapsedMsSince(startNanos), details())
    }

    fun end(startNanos: Long, label: String) {
        if (!BuildConfig.DEBUG) return
        logTimed(label, elapsedMsSince(startNanos), details = "")
    }

    @Deprecated(
        message = "Use the lazy details overload to avoid building log strings in release builds.",
        replaceWith = ReplaceWith("end(startNanos, label) { details }"),
        level = DeprecationLevel.ERROR
    )
    fun end(startNanos: Long, label: String, details: String) {
        if (!BuildConfig.DEBUG) return
        logTimed(label, elapsedMsSince(startNanos), details)
    }

    inline fun endIfSlow(
        startNanos: Long,
        label: String,
        thresholdMs: Double,
        details: () -> String
    ) {
        if (!BuildConfig.DEBUG) return
        val elapsedMs = elapsedMsSince(startNanos)
        if (elapsedMs < thresholdMs) return
        logTimed(label, elapsedMs, details())
    }

    fun endIfSlow(
        startNanos: Long,
        label: String,
        thresholdMs: Double
    ) {
        if (!BuildConfig.DEBUG) return
        val elapsedMs = elapsedMsSince(startNanos)
        if (elapsedMs < thresholdMs) return
        logTimed(label, elapsedMs, details = "")
    }

    @Deprecated(
        message = "Use the lazy details overload to avoid building log strings before the slow threshold is known.",
        replaceWith = ReplaceWith("endIfSlow(startNanos, label, thresholdMs) { details }"),
        level = DeprecationLevel.ERROR
    )
    fun endIfSlow(
        startNanos: Long,
        label: String,
        thresholdMs: Double,
        details: String
    ) {
        if (!BuildConfig.DEBUG) return
        val elapsedMs = elapsedMsSince(startNanos)
        if (elapsedMs < thresholdMs) return
        logTimed(label, elapsedMs, details)
    }

    @PublishedApi
    internal fun elapsedMsSince(startNanos: Long): Double {
        return (SystemClock.elapsedRealtimeNanos() - startNanos) / NANOS_PER_MS
    }

    @PublishedApi
    internal fun logMessage(message: String) {
        Log.d(TAG, "${threadPrefix()} $message")
    }

    @PublishedApi
    internal fun logTimed(label: String, elapsedMs: Double, details: String) {
        val suffix = details.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        Log.d(TAG, "${threadPrefix()} $label ${String.format(Locale.US, "%.2f", elapsedMs)}ms$suffix")
    }

    private fun threadPrefix(): String {
        return "[${Thread.currentThread().name}]"
    }
}
