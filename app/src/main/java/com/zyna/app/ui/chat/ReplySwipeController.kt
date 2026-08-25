package com.zyna.app.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.zyna.app.ui.chat.render.MessageCellView
import com.zyna.app.ui.chat.render.MessageReplyPreview
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Owns the single active swipe-to-reply interaction for a chat RecyclerView. */
internal class ReplySwipeController(
    private val recyclerView: RecyclerView,
    private val indicatorView: ReplySwipeIndicatorView,
    private val canStart: () -> Boolean,
    private val indicatorTop: () -> Float,
    private val indicatorBottom: () -> Float,
    private val onInteractionActiveChanged: (Boolean) -> Unit,
    private val onReply: (MessageReplyPreview) -> Unit
) : RecyclerView.OnItemTouchListener, RecyclerView.OnChildAttachStateChangeListener {
    private val density = recyclerView.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(recyclerView.context).scaledTouchSlop.toFloat()
    private val maxTranslation = MAX_TRANSLATION_DP * density
    private val triggerTranslation = TRIGGER_TRANSLATION_DP * density
    private val triggerVelocity = TRIGGER_VELOCITY_DP_PER_SECOND * density
    private val hitVerticalPadding = HIT_VERTICAL_PADDING_DP * density
    private val hiddenTrailingOverflow = INDICATOR_HIDDEN_OVERFLOW_DP * density
    private val visibleTrailingInset = INDICATOR_VISIBLE_INSET_DP * density
    private val returnInterpolator = DampedSpringInterpolator(dampingRatio = 0.82f)
    private val hideInterpolator = PathInterpolator(0f, 0f, 0.2f, 1f)

    private var candidateCell: MessageCellView? = null
    private var candidateTarget: MessageReplyPreview? = null
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var isTracking = false
    private var isPrimed = false
    private var velocityTracker: VelocityTracker? = null
    private var lastTrackedEventTime = Long.MIN_VALUE
    private var returningCell: MessageCellView? = null

    val isActive: Boolean
        get() = isTracking

    init {
        recyclerView.addOnItemTouchListener(this)
        recyclerView.addOnChildAttachStateChangeListener(this)
    }

    override fun onInterceptTouchEvent(recyclerView: RecyclerView, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginCandidate(event)
            MotionEvent.ACTION_MOVE -> {
                trackVelocity(event)
                val cell = candidateCell ?: return false
                val pointerIndex = event.findPointerIndex(pointerId)
                if (pointerIndex == -1) {
                    cancel(immediate = false)
                    return false
                }
                if (!isCurrentTarget(cell)) {
                    cancel(immediate = true)
                    return false
                }
                val deltaX = event.getX(pointerIndex) - downX
                val deltaY = event.getY(pointerIndex) - downY
                if (!isTracking) {
                    if (
                        !canStart() ||
                        recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE ||
                        ReplySwipePolicy.shouldRejectAsVertical(
                            deltaX = deltaX,
                            deltaY = deltaY,
                            touchSlop = touchSlop,
                            horizontalBias = HORIZONTAL_BIAS
                        )
                    ) {
                        cancelCandidate()
                        return false
                    }
                    if (
                        !ReplySwipePolicy.shouldStart(
                            deltaX = deltaX,
                            deltaY = deltaY,
                            touchSlop = touchSlop,
                            horizontalBias = HORIZONTAL_BIAS
                        )
                    ) {
                        return false
                    }
                    beginTracking()
                }
                updateTracking(cell, deltaX)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> cancel(immediate = false)
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> cancelCandidate()
        }
        return isTracking
    }

    override fun onTouchEvent(recyclerView: RecyclerView, event: MotionEvent) {
        trackVelocity(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val cell = candidateCell ?: return
                val pointerIndex = event.findPointerIndex(pointerId)
                if (pointerIndex == -1 || !isCurrentTarget(cell)) {
                    cancel(immediate = true)
                    return
                }
                updateTracking(cell, event.getX(pointerIndex) - downX)
            }
            MotionEvent.ACTION_UP -> finish(cancelled = false)
            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP -> finish(cancelled = true)
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (disallowIntercept && !isTracking) {
            cancelCandidate()
        }
    }

    override fun onChildViewAttachedToWindow(view: View) = Unit

    override fun onChildViewDetachedFromWindow(view: View) {
        if (view === candidateCell) {
            cancel(immediate = true)
        }
        if (view === returningCell) {
            view.animate().setUpdateListener(null)
            view.animate().cancel()
            view.translationX = 0f
            recyclerView.postInvalidateOnAnimation()
            returningCell = null
        }
    }

    fun cancel(immediate: Boolean) {
        if (candidateCell == null && !isTracking) {
            if (immediate) {
                resetReturningCell()
                hideIndicator(immediate = true)
            }
            return
        }
        finish(cancelled = true, immediate = immediate)
    }

    private fun beginCandidate(event: MotionEvent) {
        cancel(immediate = true)
        resetReturningCell()
        if (!canStart() || recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE) return

        val child = recyclerView.findChildViewUnder(event.x, event.y) as? MessageCellView ?: return
        val localY = event.y - child.y
        val target = child.replySwipeTargetAt(localY, hitVerticalPadding) ?: return

        candidateCell = child
        candidateTarget = target
        pointerId = event.getPointerId(event.actionIndex)
        downX = event.x
        downY = event.y
        velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
        lastTrackedEventTime = event.eventTime
    }

    private fun beginTracking() {
        isTracking = true
        isPrimed = false
        recyclerView.stopScroll()
        recyclerView.parent?.requestDisallowInterceptTouchEvent(true)
        candidateCell?.let(::cancelCellAnimation)
        indicatorView.animate().cancel()
        onInteractionActiveChanged(true)
    }

    private fun updateTracking(cell: MessageCellView, deltaX: Float) {
        val offset = ReplySwipePolicy.offset(deltaX, maxTranslation)
        if (cell.translationX != offset) {
            cell.translationX = offset
            recyclerView.postInvalidateOnAnimation()
        }
        val progress = ReplySwipePolicy.progress(offset, triggerTranslation)
        val primed = offset <= -triggerTranslation
        if (primed && !isPrimed) {
            performThresholdHaptic(cell)
        }
        isPrimed = primed
        updateIndicator(cell, progress)
    }

    private fun updateIndicator(cell: MessageCellView, progress: Float) {
        val easedProgress = progress * (2f - progress)
        val recyclerRight = recyclerView.x + recyclerView.width
        val hiddenCenterX = recyclerRight + hiddenTrailingOverflow
        val visibleCenterX = recyclerRight - visibleTrailingInset
        val centerX = hiddenCenterX + (visibleCenterX - hiddenCenterX) * easedProgress
        val rawCenterY = recyclerView.y + cell.y + cell.height / 2f
        val top = indicatorTop()
        val bottom = max(top, indicatorBottom())
        val centerY = min(max(rawCenterY, top), bottom)
        val scale = INDICATOR_MIN_SCALE +
            (INDICATOR_MAX_SCALE - INDICATOR_MIN_SCALE) * easedProgress

        indicatorView.alpha = progress
        indicatorView.scaleX = scale
        indicatorView.scaleY = scale
        indicatorView.translationX = centerX - indicatorView.measuredWidth / 2f
        indicatorView.translationY = centerY - indicatorView.measuredHeight / 2f
    }

    private fun finish(cancelled: Boolean, immediate: Boolean = false) {
        val cell = candidateCell
        val originalTarget = candidateTarget
        val shouldReply = if (!cancelled && cell != null && originalTarget != null) {
            velocityTracker?.computeCurrentVelocity(1_000)
            val velocityX = velocityTracker?.getXVelocity(pointerId) ?: 0f
            isCurrentTarget(cell) && ReplySwipePolicy.shouldActivate(
                offsetX = cell.translationX,
                velocityX = velocityX,
                triggerTranslation = triggerTranslation,
                triggerVelocity = triggerVelocity
            )
        } else {
            false
        }

        if (isTracking) {
            isTracking = false
            onInteractionActiveChanged(false)
            recyclerView.parent?.requestDisallowInterceptTouchEvent(false)
        }
        isPrimed = false
        resetCell(cell, immediate)
        hideIndicator(immediate)
        clearCandidate()

        if (shouldReply && originalTarget != null) {
            onReply(originalTarget)
        }
    }

    private fun resetCell(cell: MessageCellView?, immediate: Boolean) {
        cell ?: return
        cancelCellAnimation(cell)
        if (immediate || !cell.isAttachedToWindow || cell.translationX == 0f) {
            cell.translationX = 0f
            recyclerView.postInvalidateOnAnimation()
            return
        }
        returningCell = cell
        cell.animate()
            .translationX(0f)
            .setDuration(RETURN_DURATION_MS)
            .setInterpolator(returnInterpolator)
            .setUpdateListener {
                recyclerView.postInvalidateOnAnimation()
            }
            .withEndAction {
                cell.animate().setUpdateListener(null)
                recyclerView.postInvalidateOnAnimation()
                if (returningCell === cell) {
                    returningCell = null
                }
            }
            .start()
    }

    private fun hideIndicator(immediate: Boolean) {
        indicatorView.animate().cancel()
        val hiddenCenterX = recyclerView.x + recyclerView.width + hiddenTrailingOverflow
        val hiddenTranslationX = hiddenCenterX - indicatorView.measuredWidth / 2f
        if (immediate) {
            indicatorView.alpha = 0f
            indicatorView.scaleX = INDICATOR_MIN_SCALE
            indicatorView.scaleY = INDICATOR_MIN_SCALE
            indicatorView.translationX = hiddenTranslationX
            return
        }
        indicatorView.animate()
            .alpha(0f)
            .scaleX(INDICATOR_MIN_SCALE)
            .scaleY(INDICATOR_MIN_SCALE)
            .translationX(hiddenTranslationX)
            .setDuration(INDICATOR_HIDE_DURATION_MS)
            .setInterpolator(hideInterpolator)
            .start()
    }

    private fun isCurrentTarget(cell: MessageCellView): Boolean {
        val expected = candidateTarget ?: return false
        return cell.isBoundToReplySwipeEvent(expected.eventId)
    }

    private fun trackVelocity(event: MotionEvent) {
        if (event.eventTime == lastTrackedEventTime) return
        velocityTracker?.addMovement(event)
        lastTrackedEventTime = event.eventTime
    }

    private fun cancelCandidate() {
        if (isTracking) {
            finish(cancelled = true)
        } else {
            clearCandidate()
        }
    }

    private fun clearCandidate() {
        candidateCell = null
        candidateTarget = null
        pointerId = MotionEvent.INVALID_POINTER_ID
        velocityTracker?.recycle()
        velocityTracker = null
        lastTrackedEventTime = Long.MIN_VALUE
    }

    private fun resetReturningCell() {
        returningCell?.let { cell ->
            cancelCellAnimation(cell)
            cell.translationX = 0f
            recyclerView.postInvalidateOnAnimation()
        }
        returningCell = null
    }

    private fun cancelCellAnimation(cell: MessageCellView) {
        cell.animate().setUpdateListener(null)
        cell.animate().cancel()
    }

    private fun performThresholdHaptic(view: View) {
        val feedback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        }
        view.performHapticFeedback(feedback)
    }

    private companion object {
        const val MAX_TRANSLATION_DP = 64f
        const val TRIGGER_TRANSLATION_DP = 42f
        const val TRIGGER_VELOCITY_DP_PER_SECOND = 650f
        const val HORIZONTAL_BIAS = 1.2f
        const val HIT_VERTICAL_PADDING_DP = 6f
        const val RETURN_DURATION_MS = 280L
        const val INDICATOR_HIDE_DURATION_MS = 160L
        const val INDICATOR_HIDDEN_OVERFLOW_DP = 16f
        const val INDICATOR_VISIBLE_INSET_DP = 30f
        const val INDICATOR_MIN_SCALE = 0.62f
        const val INDICATOR_MAX_SCALE = 1f
    }
}

/** UIKit-like underdamped response normalized to finish exactly at 1. */
private class DampedSpringInterpolator(
    dampingRatio: Float,
    angularFrequency: Float = 12f
) : Interpolator {
    private val damping = dampingRatio.coerceIn(0.01f, 0.99f).toDouble()
    private val frequency = angularFrequency.coerceAtLeast(1f).toDouble()
    private val dampedFrequency = frequency * sqrt(1.0 - damping * damping)
    private val sineCoefficient = damping / sqrt(1.0 - damping * damping)
    private val endValue = response(1.0)

    override fun getInterpolation(input: Float): Float {
        return (response(input.coerceIn(0f, 1f).toDouble()) / endValue).toFloat()
    }

    private fun response(time: Double): Double {
        val envelope = exp(-damping * frequency * time)
        val oscillation = cos(dampedFrequency * time) +
            sineCoefficient * sin(dampedFrequency * time)
        return 1.0 - envelope * oscillation
    }
}

/** One allocation for the whole chat; all interaction updates are primitive view properties. */
internal class ReplySwipeIndicatorView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    init {
        alpha = 0f
        scaleX = INDICATOR_INITIAL_SCALE
        scaleY = INDICATOR_INITIAL_SCALE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    fun setColor(color: Int) {
        if (paint.color == color) return
        paint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height / 2f
        val unit = density
        path.rewind()
        path.moveTo(centerX - 9f * unit, centerY)
        path.lineTo(centerX - 1f * unit, centerY - 8f * unit)
        path.lineTo(centerX - 1f * unit, centerY - 3.8f * unit)
        path.cubicTo(
            centerX + 6.5f * unit,
            centerY - 3.4f * unit,
            centerX + 9f * unit,
            centerY + 1.5f * unit,
            centerX + 9f * unit,
            centerY + 8f * unit
        )
        path.cubicTo(
            centerX + 6f * unit,
            centerY + 3f * unit,
            centerX + 3f * unit,
            centerY + 1.2f * unit,
            centerX - 1f * unit,
            centerY + 1.2f * unit
        )
        path.lineTo(centerX - 1f * unit, centerY + 8f * unit)
        path.close()
        canvas.drawPath(path, paint)
    }

    private companion object {
        const val INDICATOR_INITIAL_SCALE = 0.62f
    }
}
