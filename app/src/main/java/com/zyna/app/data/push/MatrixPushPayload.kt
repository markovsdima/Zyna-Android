package com.zyna.app.data.push

data class MatrixPushPayload(
    val eventId: String,
    val roomId: String,
    val unreadCount: Int?,
    val clientSecret: String?
) {
    companion object {
        private const val KEY_EVENT_ID = "event_id"
        private const val KEY_ROOM_ID = "room_id"
        private const val KEY_UNREAD = "unread"
        private const val KEY_CLIENT_SECRET = "cs"

        fun fromData(data: Map<String, String>): MatrixPushPayload? {
            val eventId = data[KEY_EVENT_ID]?.takeIf { it.isNotBlank() } ?: return null
            val roomId = data[KEY_ROOM_ID]?.takeIf { it.isNotBlank() } ?: return null
            return MatrixPushPayload(
                eventId = eventId,
                roomId = roomId,
                unreadCount = data[KEY_UNREAD]?.toIntOrNull()?.takeIf { it > 0 },
                clientSecret = data[KEY_CLIENT_SECRET]?.takeIf { it.isNotBlank() }
            )
        }
    }
}
