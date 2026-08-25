package com.zyna.app.ui.chat.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatrixCallIntentPresentationPolicyTest {
    @Test
    fun nullAndAudioIntents_areAudioCompatible() {
        assertTrue(null.isAudioCompatibleCallIntent())
        assertTrue("audio".isAudioCompatibleCallIntent())
        assertTrue(" M.AUDIO ".isAudioCompatibleCallIntent())
    }

    @Test
    fun videoAndUnsupportedIntents_areNotAudioCompatible() {
        assertFalse("video".isAudioCompatibleCallIntent())
        assertFalse("m.video".isAudioCompatibleCallIntent())
        assertFalse("m.unsupported".isAudioCompatibleCallIntent())
    }
}
