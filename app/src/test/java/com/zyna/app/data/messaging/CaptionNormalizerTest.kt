package com.zyna.app.data.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptionNormalizerTest {
    @Test
    fun normalizedMessageCaption_dropsZeroWidthOnlyCaption() {
        assertNull("\u200B".normalizedMessageCaption())
    }

    @Test
    fun normalizedMessageCaption_trimsInvisibleEdges() {
        assertEquals("caption", "\u200B caption \u200B".normalizedMessageCaption())
    }

    @Test
    fun normalizedMessageCaption_keepsVisibleEmojiSequence() {
        val familyEmoji = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66"
        assertEquals(familyEmoji, familyEmoji.normalizedMessageCaption())
    }
}
