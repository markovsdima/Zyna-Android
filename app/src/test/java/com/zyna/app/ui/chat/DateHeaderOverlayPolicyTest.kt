package com.zyna.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DateHeaderOverlayPolicyTest {
    @Test
    fun inlineDividerStillVisible_doesNotCreateDuplicateFloatingHeader() {
        val translationY = resolveDateHeaderTranslationY(
            ownDividerStillVisible = true,
            approachingDividerTop = Float.POSITIVE_INFINITY,
            stickyTop = 4f,
            overlayHeight = 34f,
            pushSpacing = 4f
        )

        assertTrue(translationY.isNaN())
    }

    @Test
    fun activeDayWithoutInlineDivider_sticksAtTop() {
        val translationY = resolveDateHeaderTranslationY(
            ownDividerStillVisible = false,
            approachingDividerTop = Float.POSITIVE_INFINITY,
            stickyTop = 4f,
            overlayHeight = 34f,
            pushSpacing = 4f
        )

        assertEquals(0f, translationY)
    }

    @Test
    fun approachingDifferentDivider_pushesFloatingHeaderUp() {
        val translationY = resolveDateHeaderTranslationY(
            ownDividerStillVisible = false,
            approachingDividerTop = 30f,
            stickyTop = 4f,
            overlayHeight = 34f,
            pushSpacing = 4f
        )

        assertEquals(-12f, translationY)
    }
}
