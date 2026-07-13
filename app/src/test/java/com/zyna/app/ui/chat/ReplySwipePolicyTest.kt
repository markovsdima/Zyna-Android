package com.zyna.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplySwipePolicyTest {
    @Test
    fun leftBiasedMotion_startsSwipe() {
        assertTrue(
            ReplySwipePolicy.shouldStart(
                deltaX = -18f,
                deltaY = 8f,
                touchSlop = 10f,
                horizontalBias = 1.2f
            )
        )
    }

    @Test
    fun rightOrVerticalMotion_doesNotStartSwipe() {
        assertFalse(ReplySwipePolicy.shouldStart(18f, 2f, 10f, 1.2f))
        assertFalse(ReplySwipePolicy.shouldStart(-18f, 16f, 10f, 1.2f))
        assertTrue(ReplySwipePolicy.shouldRejectAsVertical(-12f, 18f, 10f, 1.2f))
    }

    @Test
    fun offset_isLeftOnlyAndClamped() {
        assertEquals(0f, ReplySwipePolicy.offset(12f, 64f))
        assertEquals(-24f, ReplySwipePolicy.offset(-24f, 64f))
        assertEquals(-64f, ReplySwipePolicy.offset(-100f, 64f))
    }

    @Test
    fun progress_isClampedAtTrigger() {
        assertEquals(0.5f, ReplySwipePolicy.progress(-21f, 42f))
        assertEquals(1f, ReplySwipePolicy.progress(-64f, 42f))
    }

    @Test
    fun distanceOrFastLeftFlick_activatesReply() {
        assertTrue(ReplySwipePolicy.shouldActivate(-42f, 0f, 42f, 650f))
        assertTrue(ReplySwipePolicy.shouldActivate(-12f, -700f, 42f, 650f))
        assertFalse(ReplySwipePolicy.shouldActivate(-20f, -400f, 42f, 650f))
    }
}
