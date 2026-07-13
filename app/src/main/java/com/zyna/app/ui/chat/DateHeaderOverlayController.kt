package com.zyna.app.ui.chat

import android.graphics.Color
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.zyna.app.ui.chat.render.SystemEventCellView
import com.zyna.app.ui.chat.render.SystemEventRenderKind
import com.zyna.app.ui.chat.render.SystemEventRenderModel
import com.zyna.app.ui.chat.render.SystemEventRenderTheme
import kotlin.math.abs
import kotlin.math.max

internal interface DateHeaderTimelineLookup {
    fun timelineItemAt(position: Int): ChatTimelineItem?

    fun dateDividerForPosition(position: Int): TimelineDateDividerModel?
}

/** Coordinates the transient floating copy of real date-divider adapter rows. */
internal class DateHeaderOverlayController(
    private val recyclerView: RecyclerView,
    private val overlayView: SystemEventCellView,
    initialTheme: SystemEventRenderTheme,
    private val canReveal: () -> Boolean = { true }
) : RecyclerView.OnScrollListener() {
    private enum class OverlayState {
        HIDDEN,
        REVEALING,
        VISIBLE,
        HIDING
    }

    private val density = recyclerView.resources.displayMetrics.density
    private val stickyTop = STICKY_TOP_DP * density
    private val pushSpacing = PUSH_SPACING_DP * density
    private val easeOut = DecelerateInterpolator()
    private val easeInOut = AccelerateDecelerateInterpolator()
    private var theme = initialTheme
    private var highlightedBackground = initialTheme.highlightedDateBackground()
    private var currentModel: TimelineDateDividerModel? = null
    private var overlayState = OverlayState.HIDDEN
    private var appliedTranslationY = Float.NaN
    private var alphaAnimationGeneration = 0
    private var settleHideScheduled = false
    private var settleHideEpochDay = NO_EPOCH_DAY
    private var revealBackgroundSettleScheduled = false

    private val revealBackgroundSettle = Runnable {
        revealBackgroundSettleScheduled = false
        if (overlayState != OverlayState.HIDDEN) {
            overlayView.animatePillBackgroundColor(
                color = theme.backgroundColor,
                durationMillis = BACKGROUND_PULSE_DOWN_DURATION_MS,
                interpolator = easeInOut
            )
        }
    }
    private val preHidePulse = Runnable {
        if (!isSettleHideTargetCurrent()) return@Runnable
        overlayView.animatePillBackgroundColor(
            color = highlightedBackground,
            durationMillis = BACKGROUND_PULSE_UP_DURATION_MS,
            interpolator = easeOut
        )
        overlayView.postDelayed(preHidePulseDown, BACKGROUND_PULSE_UP_DURATION_MS)
        overlayView.postDelayed(settleFadeOut, PRE_HIDE_PULSE_LEAD_MS)
    }
    private val preHidePulseDown = Runnable {
        if (!isSettleHideTargetCurrent()) return@Runnable
        overlayView.animatePillBackgroundColor(
            color = theme.backgroundColor,
            durationMillis = BACKGROUND_PULSE_DOWN_DURATION_MS,
            interpolator = easeInOut
        )
    }
    private val settleFadeOut = Runnable {
        if (!isSettleHideTargetCurrent()) return@Runnable
        settleHideScheduled = false
        settleHideEpochDay = NO_EPOCH_DAY
        startFadeOut(animated = true)
    }

    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        val hasPositionChange = dx != 0 || dy != 0
        val isActivelyMoving =
            recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE || hasPositionChange
        if (isActivelyMoving) {
            cancelSettleHide(settleBackground = true)
        }
        update(allowReveal = isActivelyMoving || overlayState != OverlayState.HIDDEN)
        if (recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE) {
            scheduleSettleHide()
        }
    }

    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
        if (newState == RecyclerView.SCROLL_STATE_IDLE) {
            update(allowReveal = overlayState != OverlayState.HIDDEN)
            scheduleSettleHide()
        } else {
            cancelSettleHide(settleBackground = true)
            update(allowReveal = true)
        }
    }

    fun onTimelineChanged() {
        recyclerView.post {
            val isScrolling = recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE
            update(allowReveal = isScrolling || overlayState != OverlayState.HIDDEN)
            if (!isScrolling) {
                scheduleSettleHide()
            }
        }
    }

    fun setTheme(nextTheme: SystemEventRenderTheme) {
        if (theme == nextTheme) return
        theme = nextTheme
        highlightedBackground = nextTheme.highlightedDateBackground()
        currentModel?.let(::bind)
        if (overlayState != OverlayState.HIDDEN) {
            overlayView.setPillBackgroundColor(theme.backgroundColor)
        }
    }

    fun hideImmediately() {
        cancelSettleHide(settleBackground = false)
        cancelRevealBackgroundSettle()
        startFadeOut(animated = false)
    }

    fun dispose() {
        cancelSettleHide(settleBackground = false)
        cancelRevealBackgroundSettle()
        overlayView.animate().cancel()
        overlayView.setPillBackgroundColor(theme.backgroundColor)
    }

    /** Two small child passes, primitive state only: this method allocates nothing per frame. */
    private fun update(allowReveal: Boolean) {
        updateMeasured(allowReveal)
    }

    private fun updateMeasured(allowReveal: Boolean) {
        if (!canReveal()) {
            hideImmediately()
            return
        }
        val lookup = recyclerView.adapter as? DateHeaderTimelineLookup
        val childCount = recyclerView.childCount
        if (lookup == null || childCount == 0 || recyclerView.width <= 0) {
            hideImmediately()
            return
        }
        val viewportHeight = recyclerView.height

        var activePosition = RecyclerView.NO_POSITION
        var activeVisibleTop = Int.MAX_VALUE
        var activeRawTop = Int.MAX_VALUE
        for (index in 0 until childCount) {
            val child = recyclerView.getChildAt(index) ?: continue
            if (child.bottom <= 0 || child.top >= viewportHeight) continue
            val position = recyclerView.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            val item = lookup.timelineItemAt(position)
            if (item == null || item is ChatTimelineItem.DateDivider) continue

            val visibleTop = max(child.top, 0)
            if (
                visibleTop < activeVisibleTop ||
                (visibleTop == activeVisibleTop && child.top < activeRawTop)
            ) {
                activeVisibleTop = visibleTop
                activeRawTop = child.top
                activePosition = position
            }
        }

        val activeModel = if (activePosition == RecyclerView.NO_POSITION) {
            null
        } else {
            lookup.dateDividerForPosition(activePosition)
        }
        if (activeModel == null || !allowReveal) {
            startFadeOut(animated = overlayState != OverlayState.HIDDEN)
            return
        }

        var ownDividerStillVisible = false
        var approachingDividerTop = Float.POSITIVE_INFINITY
        for (index in 0 until childCount) {
            val child = recyclerView.getChildAt(index) ?: continue
            if (child.bottom <= 0 || child.top >= viewportHeight) continue
            val position = recyclerView.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            val divider = (lookup.timelineItemAt(position) as? ChatTimelineItem.DateDivider)
                ?.model ?: continue
            if (divider.epochDay == activeModel.epochDay) {
                if (child.bottom > stickyTop) {
                    ownDividerStillVisible = true
                }
            } else if (child.top > stickyTop && child.top < approachingDividerTop) {
                approachingDividerTop = child.top.toFloat()
            }
        }

        val translationY = resolveDateHeaderTranslationY(
            ownDividerStillVisible = ownDividerStillVisible,
            approachingDividerTop = approachingDividerTop,
            stickyTop = stickyTop,
            overlayHeight = overlayView.height.toFloat(),
            pushSpacing = pushSpacing
        )
        if (translationY.isNaN()) {
            startFadeOut(animated = overlayState != OverlayState.HIDDEN)
            return
        }

        val modelChanged = currentModel != activeModel
        if (modelChanged) {
            cancelSettleHide(settleBackground = false)
            currentModel = activeModel
            bind(activeModel)
        }
        applyTranslationY(translationY)
        show(modelChanged = modelChanged)
    }

    private fun bind(model: TimelineDateDividerModel) {
        overlayView.bindKeepingMeasuredSize(
            model = SystemEventRenderModel(
                text = model.title,
                accessibilityText = model.title,
                kind = SystemEventRenderKind.SYSTEM_EVENT
            ),
            theme = theme
        )
    }

    private fun applyTranslationY(translationY: Float) {
        if (!appliedTranslationY.isNaN() && abs(appliedTranslationY - translationY) < 0.25f) {
            return
        }
        appliedTranslationY = translationY
        overlayView.translationY = translationY
    }

    private fun show(modelChanged: Boolean) {
        when (overlayState) {
            OverlayState.VISIBLE,
            OverlayState.REVEALING -> {
                if (modelChanged) {
                    prepareHighlightedReveal()
                }
                return
            }

            OverlayState.HIDDEN -> {
                overlayView.animate().cancel()
                overlayView.alpha = 0f
                overlayView.visibility = View.VISIBLE
            }

            OverlayState.HIDING -> overlayView.animate().cancel()
        }

        overlayState = OverlayState.REVEALING
        alphaAnimationGeneration += 1
        val generation = alphaAnimationGeneration
        prepareHighlightedReveal()
        overlayView.animate()
            .alpha(1f)
            .setDuration(REVEAL_DURATION_MS)
            .setInterpolator(easeOut)
            .withEndAction {
                if (generation == alphaAnimationGeneration && overlayState == OverlayState.REVEALING) {
                    overlayState = OverlayState.VISIBLE
                }
            }
            .start()
    }

    private fun prepareHighlightedReveal() {
        cancelRevealBackgroundSettle()
        overlayView.setPillBackgroundColor(highlightedBackground)
        revealBackgroundSettleScheduled = true
        overlayView.postDelayed(revealBackgroundSettle, REVEAL_DURATION_MS)
    }

    private fun startFadeOut(animated: Boolean) {
        if (overlayState == OverlayState.HIDDEN) return
        if (animated && overlayState == OverlayState.HIDING) return

        cancelSettleHide(settleBackground = false)
        cancelRevealBackgroundSettle()
        if (!animated) {
            alphaAnimationGeneration += 1
            overlayView.animate().cancel()
            overlayView.alpha = 0f
            overlayView.visibility = View.INVISIBLE
            overlayView.translationY = 0f
            appliedTranslationY = Float.NaN
            currentModel = null
            overlayState = OverlayState.HIDDEN
            overlayView.setPillBackgroundColor(theme.backgroundColor)
            return
        }

        overlayState = OverlayState.HIDING
        alphaAnimationGeneration += 1
        val generation = alphaAnimationGeneration
        overlayView.animate().cancel()
        overlayView.animate()
            .alpha(0f)
            .setDuration(SETTLE_HIDE_DURATION_MS)
            .setInterpolator(easeInOut)
            .withEndAction {
                if (generation == alphaAnimationGeneration && overlayState == OverlayState.HIDING) {
                    overlayView.visibility = View.INVISIBLE
                    currentModel = null
                    overlayState = OverlayState.HIDDEN
                    appliedTranslationY = Float.NaN
                    overlayView.setPillBackgroundColor(theme.backgroundColor)
                }
            }
            .start()
    }

    private fun scheduleSettleHide() {
        val model = currentModel ?: return
        if (
            settleHideScheduled ||
            overlayState == OverlayState.HIDDEN ||
            overlayState == OverlayState.HIDING
        ) {
            return
        }
        settleHideScheduled = true
        settleHideEpochDay = model.epochDay
        overlayView.postDelayed(preHidePulse, SETTLE_HIDE_DELAY_MS)
    }

    private fun cancelSettleHide(settleBackground: Boolean) {
        if (!settleHideScheduled) return
        settleHideScheduled = false
        settleHideEpochDay = NO_EPOCH_DAY
        overlayView.removeCallbacks(preHidePulse)
        overlayView.removeCallbacks(preHidePulseDown)
        overlayView.removeCallbacks(settleFadeOut)
        if (settleBackground && overlayState != OverlayState.HIDDEN) {
            overlayView.animatePillBackgroundColor(
                color = theme.backgroundColor,
                durationMillis = BACKGROUND_PULSE_DOWN_DURATION_MS,
                interpolator = easeInOut
            )
        }
    }

    private fun isSettleHideTargetCurrent(): Boolean {
        return settleHideScheduled &&
            overlayState != OverlayState.HIDDEN &&
            settleHideEpochDay == currentModel?.epochDay
    }

    private fun cancelRevealBackgroundSettle() {
        if (!revealBackgroundSettleScheduled) return
        revealBackgroundSettleScheduled = false
        overlayView.removeCallbacks(revealBackgroundSettle)
    }

    private fun SystemEventRenderTheme.highlightedDateBackground(): Int {
        val brightness = Color.red(backgroundColor) +
            Color.green(backgroundColor) +
            Color.blue(backgroundColor)
        return if (brightness < DARK_BACKGROUND_BRIGHTNESS_THRESHOLD) {
            Color.rgb(36, 36, 36)
        } else {
            Color.WHITE
        }
    }

    private companion object {
        const val STICKY_TOP_DP = 4f
        const val PUSH_SPACING_DP = 4f
        const val REVEAL_DURATION_MS = 160L
        const val SETTLE_HIDE_DELAY_MS = 240L
        const val SETTLE_HIDE_DURATION_MS = 320L
        const val PRE_HIDE_PULSE_LEAD_MS = 140L
        const val BACKGROUND_PULSE_UP_DURATION_MS = 120L
        const val BACKGROUND_PULSE_DOWN_DURATION_MS = 280L
        const val DARK_BACKGROUND_BRIGHTNESS_THRESHOLD = 384
        const val NO_EPOCH_DAY = Long.MIN_VALUE
    }
}
