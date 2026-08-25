package com.zyna.app.ui.chat

import kotlin.math.abs

internal object ReplySwipePolicy {
    fun shouldStart(
        deltaX: Float,
        deltaY: Float,
        touchSlop: Float,
        horizontalBias: Float
    ): Boolean {
        return deltaX <= -touchSlop && abs(deltaX) > abs(deltaY) * horizontalBias
    }

    fun shouldRejectAsVertical(
        deltaX: Float,
        deltaY: Float,
        touchSlop: Float,
        horizontalBias: Float
    ): Boolean {
        return abs(deltaY) > touchSlop && abs(deltaY) * horizontalBias >= abs(deltaX)
    }

    fun offset(deltaX: Float, maxTranslation: Float): Float {
        return deltaX.coerceIn(-maxTranslation, 0f)
    }

    fun progress(offsetX: Float, triggerTranslation: Float): Float {
        if (triggerTranslation <= 0f) return 0f
        return (abs(offsetX) / triggerTranslation).coerceIn(0f, 1f)
    }

    fun shouldActivate(
        offsetX: Float,
        velocityX: Float,
        triggerTranslation: Float,
        triggerVelocity: Float
    ): Boolean {
        return offsetX <= -triggerTranslation || velocityX <= -triggerVelocity
    }
}
