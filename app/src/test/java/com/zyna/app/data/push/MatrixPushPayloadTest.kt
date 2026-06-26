package com.zyna.app.data.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MatrixPushPayloadTest {
    @Test
    fun parsesRequiredFieldsAndMetadata() {
        val payload = MatrixPushPayload.fromData(
            mapOf(
                "event_id" to "\$event",
                "room_id" to "!room:example.test",
                "unread" to "4",
                "cs" to "secret"
            )
        )

        assertEquals("\$event", payload?.eventId)
        assertEquals("!room:example.test", payload?.roomId)
        assertEquals(4, payload?.unreadCount)
        assertEquals("secret", payload?.clientSecret)
    }

    @Test
    fun ignoresInvalidUnreadCounts() {
        val payload = MatrixPushPayload.fromData(
            mapOf(
                "event_id" to "\$event",
                "room_id" to "!room:example.test",
                "unread" to "not-a-number"
            )
        )

        assertNull(payload?.unreadCount)
    }

    @Test
    fun rejectsPayloadsWithoutRequiredFields() {
        assertNull(MatrixPushPayload.fromData(mapOf("room_id" to "!room:example.test")))
        assertNull(MatrixPushPayload.fromData(mapOf("event_id" to "\$event")))
    }
}
