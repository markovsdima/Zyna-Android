package com.zyna.app.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.Window
import androidx.recyclerview.widget.RecyclerView
import com.zyna.app.BuildConfig
import java.util.Locale
import kotlin.math.max

/**
 * Debug-only, low-noise profiler for chat scroll sessions.
 *
 * Hot paths only update primitive counters. Log lines and runtime/heap sampling happen once per
 * second, while detailed Window FrameMetrics are collected off the main thread.
 */
object ChatScrollPerfProbe {
    /** Enable only for an intentional local profiling run. */
    internal const val ENABLED = false

    private val isEnabled: Boolean
        get() = BuildConfig.DEBUG && ENABLED

    private class PhaseStats {
        var calls = 0L
        var totalNanos = 0L
        var maxNanos = 0L

        fun record(durationNanos: Long) {
            calls += 1
            totalNanos += durationNanos
            maxNanos = max(maxNanos, durationNanos)
        }

        fun reset() {
            calls = 0L
            totalNanos = 0L
            maxNanos = 0L
        }

        fun summary(name: String): String {
            val average = if (calls == 0L) 0.0 else totalNanos / calls / NANOS_PER_MS
            return "$name=$calls/${average.twoDecimals()}/${(maxNanos / NANOS_PER_MS).twoDecimals()}"
        }
    }

    private data class FrameMetricsSnapshot(
        val count: Long,
        val totalNanos: Long,
        val maxTotalNanos: Long,
        val layoutMaxNanos: Long,
        val drawMaxNanos: Long,
        val syncMaxNanos: Long,
        val commandMaxNanos: Long,
        val gpuMaxNanos: Long,
        val overBudget: Long,
        val overDoubleBudget: Long,
        val overFortyMs: Long,
        val listenerDrops: Long
    )

    private const val TAG = "ZynaChatScrollPerf"
    private const val WINDOW_NANOS = 1_000_000_000L
    private const val TAIL_AFTER_IDLE_NANOS = 1_400_000_000L
    private const val STALL_LOG_THRESHOLD_NANOS = 40_000_000L
    private const val MIN_DYNAMIC_FRAME_BUDGET_NANOS = 6_000_000L
    private const val NANOS_PER_MS = 1_000_000.0
    private const val BYTES_PER_MIB = 1024.0 * 1024.0

    private var activeRecyclerView: RecyclerView? = null
    private var activeWindow: Window? = null
    private var choreographer: Choreographer? = null
    private var frameMetricsThread: HandlerThread? = null
    private var frameMetricsHandler: Handler? = null

    @Volatile
    private var recording = false

    @Volatile
    private var frameBudgetNanos = 16_666_667L

    private var scrollState = RecyclerView.SCROLL_STATE_IDLE
    private var activeUntilNanos = 0L
    private var frameCallbackPosted = false
    private var previousFrameTimeNanos = 0L
    private var windowStartNanos = 0L
    private var choreographerFrames = 0L
    private var choreographerGapTotalNanos = 0L
    private var choreographerGapMaxNanos = 0L
    private var estimatedMissedVsyncs = 0L
    private var gapOverDoubleBudget = 0L
    private var gapOverFortyMs = 0L

    private val dateOverlayStats = PhaseStats()
    private val glassScrollStats = PhaseStats()
    private val mediaPrefetchStats = PhaseStats()
    private val chatRenderStats = PhaseStats()
    private val adapterBindStats = PhaseStats()

    private var gcCount = -1L
    private var blockingGcCount = -1L
    private var gcTimeMillis = -1L
    private var allocatedBytes = -1L

    private val metricsLock = Any()
    private var metricsCount = 0L
    private var metricsTotalNanos = 0L
    private var metricsMaxTotalNanos = 0L
    private var metricsLayoutMaxNanos = 0L
    private var metricsDrawMaxNanos = 0L
    private var metricsSyncMaxNanos = 0L
    private var metricsCommandMaxNanos = 0L
    private var metricsGpuMaxNanos = 0L
    private var metricsOverBudget = 0L
    private var metricsOverDoubleBudget = 0L
    private var metricsOverFortyMs = 0L
    private var metricsListenerDrops = 0L
    private var lastMetricsIntendedVsyncNanos = Long.MIN_VALUE

    private val frameCallback = Choreographer.FrameCallback(::onFrame)
    private val frameMetricsListener = Window.OnFrameMetricsAvailableListener {
            _, frameMetrics, dropCountSinceLastInvocation ->
        onFrameMetrics(frameMetrics, dropCountSinceLastInvocation)
    }

    fun attach(recyclerView: RecyclerView) {
        if (!isEnabled || activeRecyclerView === recyclerView) return
        detach(activeRecyclerView)
        activeRecyclerView = recyclerView
        choreographer = Choreographer.getInstance()
        val refreshRate = recyclerView.display?.refreshRate?.takeIf { it >= 30f } ?: 60f
        frameBudgetNanos = (1_000_000_000.0 / refreshRate).toLong()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val window = recyclerView.context.findActivity()?.window
            if (window != null) {
                val thread = HandlerThread("ZynaChatFrameMetrics").apply { start() }
                val handler = Handler(thread.looper)
                activeWindow = window
                frameMetricsThread = thread
                frameMetricsHandler = handler
                window.addOnFrameMetricsAvailableListener(frameMetricsListener, handler)
            }
        }
        Log.d(TAG, "attached refreshHz=${refreshRate.oneDecimal()} budgetMs=${frameBudgetNanos.ms()}")
    }

    fun detach(recyclerView: RecyclerView?) {
        if (!isEnabled || recyclerView == null || activeRecyclerView !== recyclerView) return
        if (recording) {
            emitWindow(reason = "detach", nowNanos = SystemClock.elapsedRealtimeNanos())
        }
        recording = false
        frameCallbackPosted = false
        choreographer?.removeFrameCallback(frameCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            activeWindow?.removeOnFrameMetricsAvailableListener(frameMetricsListener)
        }
        frameMetricsThread?.quitSafely()
        activeRecyclerView = null
        activeWindow = null
        choreographer = null
        frameMetricsThread = null
        frameMetricsHandler = null
        Log.d(TAG, "detached")
    }

    fun noteScroll(recyclerView: RecyclerView, dx: Int, dy: Int) {
        if (!isEnabled || activeRecyclerView !== recyclerView || (dx == 0 && dy == 0)) return
        scrollState = recyclerView.scrollState
        ensureRecording()
        if (scrollState == RecyclerView.SCROLL_STATE_IDLE) {
            activeUntilNanos = SystemClock.elapsedRealtimeNanos() + TAIL_AFTER_IDLE_NANOS
        }
    }

    fun noteScrollState(recyclerView: RecyclerView, newState: Int) {
        if (!isEnabled || activeRecyclerView !== recyclerView) return
        scrollState = newState
        ensureRecording()
        activeUntilNanos = if (newState == RecyclerView.SCROLL_STATE_IDLE) {
            SystemClock.elapsedRealtimeNanos() + TAIL_AFTER_IDLE_NANOS
        } else {
            Long.MAX_VALUE
        }
    }

    fun isRecording(recyclerView: RecyclerView): Boolean {
        return isEnabled && recording && activeRecyclerView === recyclerView
    }

    fun beginSection(recyclerView: RecyclerView): Long {
        if (!isEnabled || activeRecyclerView !== recyclerView) return 0L
        if (!recording && recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
            scrollState = recyclerView.scrollState
            ensureRecording()
        }
        return if (recording) SystemClock.elapsedRealtimeNanos() else 0L
    }

    fun beginActiveSection(): Long {
        return if (isEnabled && recording) SystemClock.elapsedRealtimeNanos() else 0L
    }

    fun recordDateOverlay(startNanos: Long) = record(startNanos, dateOverlayStats)

    fun recordGlassScroll(startNanos: Long) = record(startNanos, glassScrollStats)

    fun recordMediaPrefetch(startNanos: Long) = record(startNanos, mediaPrefetchStats)

    fun recordChatRender(startNanos: Long) = record(startNanos, chatRenderStats)

    fun recordAdapterBind(startNanos: Long) = record(startNanos, adapterBindStats)

    private fun record(startNanos: Long, stats: PhaseStats) {
        if (startNanos == 0L || !recording) return
        stats.record(SystemClock.elapsedRealtimeNanos() - startNanos)
    }

    private fun ensureRecording() {
        if (recording || activeRecyclerView == null) return
        recording = true
        val now = SystemClock.elapsedRealtimeNanos()
        activeUntilNanos = if (scrollState == RecyclerView.SCROLL_STATE_IDLE) {
            now + TAIL_AFTER_IDLE_NANOS
        } else {
            Long.MAX_VALUE
        }
        resetWindow(now)
        Log.d(TAG, "session start state=$scrollState")
        postFrameCallback()
    }

    private fun postFrameCallback() {
        if (frameCallbackPosted || !recording) return
        frameCallbackPosted = true
        choreographer?.postFrameCallback(frameCallback)
    }

    private fun onFrame(frameTimeNanos: Long) {
        frameCallbackPosted = false
        if (!recording) return
        val previous = previousFrameTimeNanos
        if (previous > 0L) {
            val gap = (frameTimeNanos - previous).coerceAtLeast(0L)
            if (
                gap in MIN_DYNAMIC_FRAME_BUDGET_NANOS until frameBudgetNanos &&
                gap * 4 <= frameBudgetNanos * 3
            ) {
                frameBudgetNanos = gap
            }
            choreographerFrames += 1
            choreographerGapTotalNanos += gap
            choreographerGapMaxNanos = max(choreographerGapMaxNanos, gap)
            val vsyncs = ((gap + frameBudgetNanos / 2) / frameBudgetNanos).coerceAtLeast(1L)
            estimatedMissedVsyncs += (vsyncs - 1L).coerceAtLeast(0L)
            if (gap > frameBudgetNanos * 2) gapOverDoubleBudget += 1
            if (gap >= STALL_LOG_THRESHOLD_NANOS) gapOverFortyMs += 1
        }
        previousFrameTimeNanos = frameTimeNanos

        val now = SystemClock.elapsedRealtimeNanos()
        if (now - windowStartNanos >= WINDOW_NANOS) {
            emitWindow(reason = "tick", nowNanos = now)
            resetWindow(now)
        }
        if (scrollState == RecyclerView.SCROLL_STATE_IDLE && now >= activeUntilNanos) {
            if (now - windowStartNanos >= 100_000_000L) {
                emitWindow(reason = "tail", nowNanos = now)
            }
            recording = false
            Log.d(TAG, "session end")
            return
        }
        postFrameCallback()
    }

    private fun onFrameMetrics(frameMetrics: FrameMetrics, dropCount: Int) {
        if (!recording) return
        val total = frameMetrics.metric(FrameMetrics.TOTAL_DURATION)
        if (total <= 0L) return
        val layout = frameMetrics.metric(FrameMetrics.LAYOUT_MEASURE_DURATION)
        val draw = frameMetrics.metric(FrameMetrics.DRAW_DURATION)
        val sync = frameMetrics.metric(FrameMetrics.SYNC_DURATION)
        val command = frameMetrics.metric(FrameMetrics.COMMAND_ISSUE_DURATION)
        val gpu = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            frameMetrics.metric(FrameMetrics.GPU_DURATION)
        } else {
            0L
        }
        val intendedVsync = frameMetrics.metric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)

        synchronized(metricsLock) {
            if (intendedVsync > 0L && intendedVsync == lastMetricsIntendedVsyncNanos) {
                return
            }
            lastMetricsIntendedVsyncNanos = intendedVsync
            metricsCount += 1
            metricsTotalNanos += total
            metricsMaxTotalNanos = max(metricsMaxTotalNanos, total)
            metricsLayoutMaxNanos = max(metricsLayoutMaxNanos, layout)
            metricsDrawMaxNanos = max(metricsDrawMaxNanos, draw)
            metricsSyncMaxNanos = max(metricsSyncMaxNanos, sync)
            metricsCommandMaxNanos = max(metricsCommandMaxNanos, command)
            metricsGpuMaxNanos = max(metricsGpuMaxNanos, gpu)
            if (total > frameBudgetNanos) metricsOverBudget += 1
            if (total > frameBudgetNanos * 2) metricsOverDoubleBudget += 1
            if (total >= STALL_LOG_THRESHOLD_NANOS) metricsOverFortyMs += 1
            metricsListenerDrops += dropCount.toLong()
        }

        if (total >= STALL_LOG_THRESHOLD_NANOS) {
            val input = frameMetrics.metric(FrameMetrics.INPUT_HANDLING_DURATION)
            val animation = frameMetrics.metric(FrameMetrics.ANIMATION_DURATION)
            val unknown = frameMetrics.metric(FrameMetrics.UNKNOWN_DELAY_DURATION)
            Log.w(
                TAG,
                "STALL totalMs=${total.ms()} inputMs=${input.ms()} animMs=${animation.ms()} " +
                    "layoutMs=${layout.ms()} drawMs=${draw.ms()} syncMs=${sync.ms()} " +
                    "commandMs=${command.ms()} gpuMs=${gpu.ms()} unknownMs=${unknown.ms()} " +
                    "listenerDrops=$dropCount"
            )
        }
    }

    private fun emitWindow(reason: String, nowNanos: Long) {
        val elapsed = (nowNanos - windowStartNanos).coerceAtLeast(1L)
        val frameSnapshot = takeFrameMetricsSnapshot()
        val choreographerAverage = if (choreographerFrames == 0L) {
            0.0
        } else {
            choreographerGapTotalNanos / choreographerFrames / NANOS_PER_MS
        }
        val frameMetricsAverage = if (frameSnapshot.count == 0L) {
            0.0
        } else {
            frameSnapshot.totalNanos / frameSnapshot.count / NANOS_PER_MS
        }

        val nextGcCount = runtimeStat("art.gc.gc-count")
        val nextBlockingGcCount = runtimeStat("art.gc.blocking-gc-count")
        val nextGcTimeMillis = runtimeStat("art.gc.gc-time")
        val nextAllocatedBytes = runtimeStat("art.gc.bytes-allocated")
        val gcDelta = delta(nextGcCount, gcCount)
        val blockingGcDelta = delta(nextBlockingGcCount, blockingGcCount)
        val gcTimeDelta = delta(nextGcTimeMillis, gcTimeMillis)
        val allocatedDelta = delta(nextAllocatedBytes, allocatedBytes)
        gcCount = nextGcCount
        blockingGcCount = nextBlockingGcCount
        gcTimeMillis = nextGcTimeMillis
        allocatedBytes = nextAllocatedBytes
        val heapBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        Log.d(
            TAG,
            "window reason=$reason elapsedMs=${elapsed.ms()} state=$scrollState " +
                "budgetMs=${frameBudgetNanos.ms()} " +
                "choreo=$choreographerFrames/${choreographerAverage.twoDecimals()}/" +
                "${(choreographerGapMaxNanos / NANOS_PER_MS).twoDecimals()} " +
                "missed=$estimatedMissedVsyncs gap2x=$gapOverDoubleBudget gap40=$gapOverFortyMs " +
                "fm=${frameSnapshot.count}/${frameMetricsAverage.twoDecimals()}/" +
                "${(frameSnapshot.maxTotalNanos / NANOS_PER_MS).twoDecimals()} " +
                "fmOver=${frameSnapshot.overBudget}/${frameSnapshot.overDoubleBudget}/" +
                "${frameSnapshot.overFortyMs} " +
                "phaseMaxMs=${(frameSnapshot.layoutMaxNanos / NANOS_PER_MS).twoDecimals()}/" +
                "${(frameSnapshot.drawMaxNanos / NANOS_PER_MS).twoDecimals()}/" +
                "${(frameSnapshot.syncMaxNanos / NANOS_PER_MS).twoDecimals()}/" +
                "${(frameSnapshot.commandMaxNanos / NANOS_PER_MS).twoDecimals()}/" +
                "${(frameSnapshot.gpuMaxNanos / NANOS_PER_MS).twoDecimals()} " +
                "listenerDrops=${frameSnapshot.listenerDrops} " +
                "sections[${dateOverlayStats.summary("date")}," +
                "${glassScrollStats.summary("glass")}," +
                "${mediaPrefetchStats.summary("media")}," +
                "${chatRenderStats.summary("render")}," +
                "${adapterBindStats.summary("bind")}] " +
                "gc=$gcDelta blockingGc=$blockingGcDelta gcTimeMs=$gcTimeDelta " +
                "allocMiB=${(allocatedDelta / BYTES_PER_MIB).twoDecimals()} " +
                "heapMiB=${(heapBytes / BYTES_PER_MIB).twoDecimals()}"
        )
    }

    private fun resetWindow(nowNanos: Long) {
        windowStartNanos = nowNanos
        previousFrameTimeNanos = 0L
        choreographerFrames = 0L
        choreographerGapTotalNanos = 0L
        choreographerGapMaxNanos = 0L
        estimatedMissedVsyncs = 0L
        gapOverDoubleBudget = 0L
        gapOverFortyMs = 0L
        dateOverlayStats.reset()
        glassScrollStats.reset()
        mediaPrefetchStats.reset()
        chatRenderStats.reset()
        adapterBindStats.reset()
        synchronized(metricsLock) {
            resetFrameMetricsLocked()
        }
        gcCount = runtimeStat("art.gc.gc-count")
        blockingGcCount = runtimeStat("art.gc.blocking-gc-count")
        gcTimeMillis = runtimeStat("art.gc.gc-time")
        allocatedBytes = runtimeStat("art.gc.bytes-allocated")
    }

    private fun takeFrameMetricsSnapshot(): FrameMetricsSnapshot {
        return synchronized(metricsLock) {
            FrameMetricsSnapshot(
                count = metricsCount,
                totalNanos = metricsTotalNanos,
                maxTotalNanos = metricsMaxTotalNanos,
                layoutMaxNanos = metricsLayoutMaxNanos,
                drawMaxNanos = metricsDrawMaxNanos,
                syncMaxNanos = metricsSyncMaxNanos,
                commandMaxNanos = metricsCommandMaxNanos,
                gpuMaxNanos = metricsGpuMaxNanos,
                overBudget = metricsOverBudget,
                overDoubleBudget = metricsOverDoubleBudget,
                overFortyMs = metricsOverFortyMs,
                listenerDrops = metricsListenerDrops
            )
        }
    }

    private fun resetFrameMetricsLocked() {
        metricsCount = 0L
        metricsTotalNanos = 0L
        metricsMaxTotalNanos = 0L
        metricsLayoutMaxNanos = 0L
        metricsDrawMaxNanos = 0L
        metricsSyncMaxNanos = 0L
        metricsCommandMaxNanos = 0L
        metricsGpuMaxNanos = 0L
        metricsOverBudget = 0L
        metricsOverDoubleBudget = 0L
        metricsOverFortyMs = 0L
        metricsListenerDrops = 0L
    }

    private fun FrameMetrics.metric(metric: Int): Long {
        return getMetric(metric).coerceAtLeast(0L)
    }

    private fun runtimeStat(name: String): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Debug.getRuntimeStat(name)?.toLongOrNull() ?: -1L
        } else {
            -1L
        }
    }

    private fun delta(current: Long, previous: Long): Long {
        return if (current >= 0L && previous >= 0L && current >= previous) current - previous else -1L
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            val base = current.baseContext
            if (base === current) break
            current = base
        }
        return current as? Activity
    }

    private fun Long.ms(): String = (this / NANOS_PER_MS).twoDecimals()

    private fun Double.twoDecimals(): String = String.format(Locale.US, "%.2f", this)

    private fun Float.oneDecimal(): String = String.format(Locale.US, "%.1f", this)
}
