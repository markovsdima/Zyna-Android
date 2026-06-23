package com.zyna.app.data.calls.matrixrtc

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

data class MatrixRtcSlotDescription(
    val application: String,
    val id: String
) {
    init {
        require(application.isNotEmpty()) { "MatrixRTC slot application is empty" }
        require(id.isNotEmpty()) { "MatrixRTC slot id is empty" }
    }

    val slotId: String
        get() = "$application#$id"

    val legacyCallId: String
        get() = if (application == MATRIX_CALL_APPLICATION && id == MATRIX_CALL_ROOM_ID) "" else id

    companion object {
        const val MATRIX_CALL_APPLICATION = "m.call"
        const val MATRIX_CALL_ROOM_ID = "ROOM"

        val MATRIX_CALL_ROOM = MatrixRtcSlotDescription(
            application = MATRIX_CALL_APPLICATION,
            id = MATRIX_CALL_ROOM_ID
        )

        fun fromSlotId(slotId: String): MatrixRtcSlotDescription {
            val separator = slotId.indexOf('#')
            require(separator > 0 && separator < slotId.lastIndex) {
                "Invalid MatrixRTC slot id: $slotId"
            }
            return MatrixRtcSlotDescription(
                application = slotId.substring(0, separator),
                id = slotId.substring(separator + 1)
            )
        }

        fun legacy(application: String, callId: String): MatrixRtcSlotDescription {
            return MatrixRtcSlotDescription(
                application = application,
                id = if (application == MATRIX_CALL_APPLICATION && callId.isEmpty()) {
                    MATRIX_CALL_ROOM_ID
                } else {
                    callId
                }
            )
        }
    }
}

data class MatrixRtcMembershipIdentity(
    val userId: String,
    val deviceId: String,
    val memberId: String
) {
    val rtcBackendIdentity: String
        get() = rtcBackendIdentity(userId, deviceId, memberId)

    val legacyRtcBackendIdentity: String
        get() = legacyRtcBackendIdentity(userId, deviceId)

    companion object {
        fun rtcBackendIdentity(userId: String, deviceId: String, memberId: String): String {
            val payload = JSONArray()
                .put(userId)
                .put(deviceId)
                .put(memberId)
                .toString()
                .toByteArray(StandardCharsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256").digest(payload)
            return Base64.getEncoder()
                .encodeToString(digest)
                .trimEnd('=')
        }

        fun legacyRtcBackendIdentity(userId: String, deviceId: String): String {
            return "$userId:$deviceId"
        }
    }
}

data class MatrixRtcTransport(
    val type: String,
    val raw: Map<String, MatrixRtcJsonValue> = mapOf(
        "type" to MatrixRtcJsonValue.StringValue(type)
    )
) {
    init {
        require(type.isNotEmpty()) { "MatrixRTC transport type is empty" }
    }

    val liveKitServiceUrl: String?
        get() = raw["livekit_service_url"]?.stringValue

    fun toJsonObject(): JSONObject {
        val rawWithType = LinkedHashMap(raw)
        rawWithType["type"] = MatrixRtcJsonValue.StringValue(type)
        return MatrixRtcJsonValue.ObjectValue(rawWithType).toJsonValue() as JSONObject
    }

    companion object {
        fun liveKit(serviceUrl: String): MatrixRtcTransport {
            return MatrixRtcTransport(
                type = "livekit",
                raw = linkedMapOf(
                    "type" to MatrixRtcJsonValue.StringValue("livekit"),
                    "livekit_service_url" to MatrixRtcJsonValue.StringValue(serviceUrl)
                )
            )
        }

        fun fromJsonObject(json: JSONObject): MatrixRtcTransport {
            val raw = (matrixRtcJsonValueFrom(json) as MatrixRtcJsonValue.ObjectValue).value
            val type = raw["type"]?.stringValue
            require(!type.isNullOrEmpty()) { "MatrixRTC transport is missing a string type" }
            return MatrixRtcTransport(type = type, raw = raw)
        }
    }
}

data class MatrixRtcRawMembershipEvent(
    val eventId: String,
    val eventType: String,
    val stateKey: String?,
    val sender: String,
    val originServerTimestamp: Long,
    val contentJson: String
) {
    companion object {
        const val LEGACY_CALL_MEMBER_EVENT_TYPE = "org.matrix.msc3401.call.member"
        const val RTC_MEMBER_EVENT_TYPE = "org.matrix.msc4143.rtc.member"
    }
}

data class MatrixRtcToDeviceTarget(
    val userId: String,
    val deviceId: String
)

data class MatrixRtcOwnDevice(
    val userId: String,
    val deviceId: String
) {
    val legacyMembershipIdentity: MatrixRtcMembershipIdentity
        get() = MatrixRtcMembershipIdentity(
            userId = userId,
            deviceId = deviceId,
            memberId = MatrixRtcMembershipIdentity.legacyRtcBackendIdentity(
                userId = userId,
                deviceId = deviceId
            )
        )
}

