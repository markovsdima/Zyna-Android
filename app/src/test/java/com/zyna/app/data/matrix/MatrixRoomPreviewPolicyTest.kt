package com.zyna.app.data.matrix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MatrixRoomPreviewPolicyTest {
    @Test
    fun nonPreviewableTimelineEvent_doesNotExposeActivityTimestamp() {
        val preview = matrixRoomPreviewForTimelineEvent(
            body = null,
            senderName = "Alice",
            timestampMillis = 2_000L,
            localOwnMessageStatus = MatrixLastOwnMessageStatus.READ,
            needsReadReceiptSummary = true
        )

        assertNull(preview.body)
        assertNull(preview.senderName)
        assertNull(preview.timestampMillis)
        assertNull(preview.localOwnMessageStatus)
        assertEquals(false, preview.needsReadReceiptSummary)
    }

    @Test
    fun previewableTimelineEvent_keepsItsFieldsAsOneSnapshot() {
        val preview = matrixRoomPreviewForTimelineEvent(
            body = "Hello",
            senderName = "Alice",
            timestampMillis = 2_000L,
            localOwnMessageStatus = MatrixLastOwnMessageStatus.SENT,
            needsReadReceiptSummary = true
        )

        assertEquals("Hello", preview.body)
        assertEquals("Alice", preview.senderName)
        assertEquals(2_000L, preview.timestampMillis)
        assertEquals(MatrixLastOwnMessageStatus.SENT, preview.localOwnMessageStatus)
        assertEquals(true, preview.needsReadReceiptSummary)
    }

    @Test
    fun remoteInvite_intentionallyKeepsInviteTimestampWithoutMessagePreview() {
        val preview = matrixRoomPreviewForInvite(timestampMillis = 3_000L)

        assertNull(preview.body)
        assertNull(preview.senderName)
        assertEquals(3_000L, preview.timestampMillis)
        assertNull(preview.localOwnMessageStatus)
    }
}
