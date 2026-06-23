package com.zyna.app.data.calls.matrixrtc

import org.json.JSONObject

data class MatrixRtcCallEncryptionKeysContent(
    val keys: Keys,
    val member: Member,
    val roomId: String,
    val session: Session = Session.MATRIX_CALL_ROOM,
    val sentTimestamp: Long?
) {
    data class Keys(
        val index: Int,
        val key: String
    )

    data class Member(
        val id: String?,
        val claimedDeviceId: String
    )

    data class Session(
        val application: String,
        val callId: String,
        val scope: String
    ) {
        companion object {
            val MATRIX_CALL_ROOM = Session(
                application = MatrixRtcSlotDescription.MATRIX_CALL_APPLICATION,
                callId = "",
                scope = "m.room"
            )
        }
    }

    fun jsonString(): String {
        val memberJson = JSONObject()
            .put("claimed_device_id", member.claimedDeviceId)
        memberJson.putIfNotNull("id", member.id)

        val json = JSONObject()
            .put(
                "keys",
                JSONObject()
                    .put("index", keys.index)
                    .put("key", keys.key)
            )
            .put("member", memberJson)
            .put("room_id", roomId)
            .put(
                "session",
                JSONObject()
                    .put("application", session.application)
                    .put("call_id", session.callId)
                    .put("scope", session.scope)
            )
        json.putIfNotNull("sent_ts", sentTimestamp)
        return json.toString()
    }

    companion object {
        const val EVENT_TYPE = "io.element.call.encryption_keys"

        fun fromJson(contentJson: String): MatrixRtcCallEncryptionKeysContent {
            val json = JSONObject(contentJson)
            val keys = json.getJSONObject("keys")
            val member = json.getJSONObject("member")
            val session = json.getJSONObject("session")
            return MatrixRtcCallEncryptionKeysContent(
                keys = Keys(
                    index = keys.getInt("index"),
                    key = keys.getString("key")
                ),
                member = Member(
                    id = member.stringOrNull("id"),
                    claimedDeviceId = member.getString("claimed_device_id")
                ),
                roomId = json.getString("room_id"),
                session = Session(
                    application = session.getString("application"),
                    callId = session.getString("call_id"),
                    scope = session.getString("scope")
                ),
                sentTimestamp = json.longOrNull("sent_ts")
            )
        }
    }
}

enum class MatrixRtcCallNotificationType(val wireValue: String) {
    RING("ring"),
    NOTIFICATION("notification");

    companion object {
        fun fromWireValue(value: String): MatrixRtcCallNotificationType {
            return entries.first { it.wireValue == value }
        }
    }
}

data class MatrixRtcCallNotificationContent(
    val mentions: Mentions?,
    val notificationType: MatrixRtcCallNotificationType,
    val relation: Relation,
    val senderTimestamp: Long,
    val lifetime: Long,
    val callIntent: String?
) {
    data class Mentions(
        val userIds: List<String>,
        val room: Boolean
    ) {
        fun toJsonObject(): JSONObject {
            return JSONObject()
                .put("user_ids", org.json.JSONArray().also { array ->
                    userIds.forEach { array.put(it) }
                })
                .put("room", room)
        }

        companion object {
            val ROOM_WIDE = Mentions(userIds = emptyList(), room = true)

            fun fromJsonObject(json: JSONObject): Mentions {
                val userIds = json.optJSONArray("user_ids")
                return Mentions(
                    userIds = if (userIds == null) {
                        emptyList()
                    } else {
                        (0 until userIds.length()).map { index -> userIds.getString(index) }
                    },
                    room = json.getBoolean("room")
                )
            }
        }
    }

    data class Relation(
        val eventId: String,
        val relType: String
    ) {
        fun toJsonObject(): JSONObject {
            return JSONObject()
                .put("event_id", eventId)
                .put("rel_type", relType)
        }

        companion object {
            fun reference(eventId: String): Relation {
                return Relation(eventId = eventId, relType = "m.reference")
            }

            fun fromJsonObject(json: JSONObject): Relation {
                return Relation(
                    eventId = json.getString("event_id"),
                    relType = json.getString("rel_type")
                )
            }
        }
    }

    fun jsonString(): String {
        val json = JSONObject()
            .put("notification_type", notificationType.wireValue)
            .put("m.relates_to", relation.toJsonObject())
            .put("sender_ts", senderTimestamp)
            .put("lifetime", lifetime)
        mentions?.let { json.put("m.mentions", it.toJsonObject()) }
        json.putIfNotNull("m.call.intent", callIntent)
        return json.toString()
    }

    companion object {
        const val EVENT_TYPE = "org.matrix.msc4075.rtc.notification"

        fun create(
            parentEventId: String,
            notificationType: MatrixRtcCallNotificationType,
            senderTimestamp: Long,
            lifetime: Long = 30_000,
            callIntent: String?,
            mentions: Mentions? = Mentions.ROOM_WIDE
        ): MatrixRtcCallNotificationContent {
            return MatrixRtcCallNotificationContent(
                mentions = mentions,
                notificationType = notificationType,
                relation = Relation.reference(parentEventId),
                senderTimestamp = senderTimestamp,
                lifetime = lifetime,
                callIntent = callIntent
            )
        }

        fun fromJson(contentJson: String): MatrixRtcCallNotificationContent {
            val json = JSONObject(contentJson)
            return MatrixRtcCallNotificationContent(
                mentions = json.optJSONObject("m.mentions")?.let(Mentions::fromJsonObject),
                notificationType = MatrixRtcCallNotificationType.fromWireValue(
                    json.getString("notification_type")
                ),
                relation = Relation.fromJsonObject(json.getJSONObject("m.relates_to")),
                senderTimestamp = json.getLong("sender_ts"),
                lifetime = json.getLong("lifetime"),
                callIntent = json.stringOrNull("m.call.intent")
            )
        }
    }
}

data class MatrixRtcLegacyCallNotifyContent(
    val application: String,
    val mentions: MatrixRtcCallNotificationContent.Mentions,
    val notifyType: String,
    val callId: String
) {
    fun jsonString(): String {
        return JSONObject()
            .put("application", application)
            .put("m.mentions", mentions.toJsonObject())
            .put("notify_type", notifyType)
            .put("call_id", callId)
            .toString()
    }

    companion object {
        const val EVENT_TYPE = "org.matrix.msc4075.call.notify"

        fun create(
            slot: MatrixRtcSlotDescription = MatrixRtcSlotDescription.MATRIX_CALL_ROOM,
            notificationType: MatrixRtcCallNotificationType,
            mentions: MatrixRtcCallNotificationContent.Mentions =
                MatrixRtcCallNotificationContent.Mentions.ROOM_WIDE
        ): MatrixRtcLegacyCallNotifyContent {
            return MatrixRtcLegacyCallNotifyContent(
                application = slot.application,
                mentions = mentions,
                notifyType = if (notificationType == MatrixRtcCallNotificationType.NOTIFICATION) {
                    "notify"
                } else {
                    notificationType.wireValue
                },
                callId = slot.id
            )
        }

        fun fromJson(contentJson: String): MatrixRtcLegacyCallNotifyContent {
            val json = JSONObject(contentJson)
            return MatrixRtcLegacyCallNotifyContent(
                application = json.getString("application"),
                mentions = MatrixRtcCallNotificationContent.Mentions.fromJsonObject(
                    json.getJSONObject("m.mentions")
                ),
                notifyType = json.getString("notify_type"),
                callId = json.getString("call_id")
            )
        }
    }
}

