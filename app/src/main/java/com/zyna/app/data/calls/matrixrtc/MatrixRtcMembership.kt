package com.zyna.app.data.calls.matrixrtc

import org.json.JSONArray
import org.json.JSONObject

data class MatrixRtcCallMembership(
    val kind: Kind,
    val eventId: String,
    val eventType: String,
    val stateKey: String?,
    val sender: String,
    val identity: MatrixRtcMembershipIdentity,
    val slot: MatrixRtcSlotDescription,
    val createdTimestamp: Long,
    val absoluteExpiryTimestamp: Long?,
    val rtcBackendIdentity: String,
    val transports: List<MatrixRtcTransport>,
    val focusSelection: String?,
    val callIntent: String?
) {
    enum class Kind {
        LEGACY_STATE,
        RTC
    }

    val userId: String
        get() = identity.userId

    val deviceId: String
        get() = identity.deviceId

    val memberId: String
        get() = identity.memberId

    val toDeviceTarget: MatrixRtcToDeviceTarget
        get() = MatrixRtcToDeviceTarget(userId = userId, deviceId = deviceId)

    fun isExpired(atTimestamp: Long): Boolean {
        return absoluteExpiryTimestamp?.let { it <= atTimestamp } ?: false
    }

    companion object {
        const val DEFAULT_EXPIRE_DURATION_MILLIS = 4L * 60L * 60L * 1000L
    }
}

object MatrixRtcCallMembershipParser {
    fun parse(event: MatrixRtcRawMembershipEvent): MatrixRtcCallMembership {
        return when (event.eventType) {
            MatrixRtcRawMembershipEvent.LEGACY_CALL_MEMBER_EVENT_TYPE -> parseLegacy(event)
            MatrixRtcRawMembershipEvent.RTC_MEMBER_EVENT_TYPE -> parseRtc(event)
            else -> throw IllegalArgumentException("Unsupported MatrixRTC membership event type: ${event.eventType}")
        }
    }

    fun activeMemberships(
        events: List<MatrixRtcRawMembershipEvent>,
        slot: MatrixRtcSlotDescription = MatrixRtcSlotDescription.MATRIX_CALL_ROOM,
        joinedUserIds: Set<String>? = null,
        now: Long = System.currentTimeMillis()
    ): List<MatrixRtcCallMembership> {
        return events.mapNotNull { event ->
            runCatching { parse(event) }.getOrNull()
        }.filter { membership ->
            membership.slot == slot &&
                !membership.isExpired(now) &&
                (joinedUserIds?.contains(membership.userId) ?: true)
        }.sortedWith(
            compareBy<MatrixRtcCallMembership> { it.createdTimestamp }
                .thenBy { it.eventId }
        )
    }

    fun toDeviceTargets(
        memberships: List<MatrixRtcCallMembership>,
        excluding: MatrixRtcMembershipIdentity
    ): List<MatrixRtcToDeviceTarget> {
        val seen = LinkedHashSet<MatrixRtcToDeviceTarget>()
        memberships.forEach { membership ->
            if (
                membership.userId == excluding.userId &&
                membership.deviceId == excluding.deviceId
            ) {
                return@forEach
            }
            seen += membership.toDeviceTarget
        }
        return seen.toList()
    }

    private fun parseLegacy(event: MatrixRtcRawMembershipEvent): MatrixRtcCallMembership {
        val content = JSONObject(event.contentJson)
        val application = content.getString("application")
        val callId = content.getString("call_id")
        val deviceId = content.getString("device_id")
        val createdTimestamp = content.longOrNull("created_ts") ?: event.originServerTimestamp
        val expires = content.longOrNull("expires")
            ?: MatrixRtcCallMembership.DEFAULT_EXPIRE_DURATION_MILLIS
        val memberId = content.stringOrNull("membershipID")
            ?: MatrixRtcMembershipIdentity.legacyRtcBackendIdentity(event.sender, deviceId)
        val identity = MatrixRtcMembershipIdentity(
            userId = event.sender,
            deviceId = deviceId,
            memberId = memberId
        )

        return MatrixRtcCallMembership(
            kind = MatrixRtcCallMembership.Kind.LEGACY_STATE,
            eventId = event.eventId,
            eventType = event.eventType,
            stateKey = event.stateKey,
            sender = event.sender,
            identity = identity,
            slot = MatrixRtcSlotDescription.legacy(application = application, callId = callId),
            createdTimestamp = createdTimestamp,
            absoluteExpiryTimestamp = createdTimestamp + expires,
            rtcBackendIdentity = MatrixRtcMembershipIdentity.legacyRtcBackendIdentity(
                userId = event.sender,
                deviceId = deviceId
            ),
            transports = content.optJSONArray("foci_preferred").toTransportList(),
            focusSelection = content.optJSONObject("focus_active")?.stringOrNull("focus_selection"),
            callIntent = content.stringOrNull("m.call.intent")
        )
    }

    private fun parseRtc(event: MatrixRtcRawMembershipEvent): MatrixRtcCallMembership {
        val content = JSONObject(event.contentJson)
        val application = content.getJSONObject("application")
        val applicationType = application.getString("type")
        val slotId = content.getString("slot_id")
        require(slotId.startsWith("$applicationType#")) {
            "Invalid MatrixRTC slot '$slotId' for application '$applicationType'"
        }

        val member = content.getJSONObject("member")
        val memberUserId = member.getString("user_id")
        require(event.sender == memberUserId) {
            "MatrixRTC sender '${event.sender}' does not match member user '$memberUserId'"
        }
        require(content.hasValidStickyKey()) {
            "MatrixRTC membership is missing a valid sticky key"
        }

        val identity = MatrixRtcMembershipIdentity(
            userId = memberUserId,
            deviceId = member.getString("device_id"),
            memberId = member.getString("id")
        )

        return MatrixRtcCallMembership(
            kind = MatrixRtcCallMembership.Kind.RTC,
            eventId = event.eventId,
            eventType = event.eventType,
            stateKey = event.stateKey,
            sender = event.sender,
            identity = identity,
            slot = MatrixRtcSlotDescription.fromSlotId(slotId),
            createdTimestamp = event.originServerTimestamp,
            absoluteExpiryTimestamp = null,
            rtcBackendIdentity = identity.rtcBackendIdentity,
            transports = content.optJSONArray("rtc_transports").toTransportList(),
            focusSelection = null,
            callIntent = application.stringOrNull("m.call.intent")
        )
    }
}

private fun JSONObject.hasValidStickyKey(): Boolean {
    val stickyKey = stringOrNull("sticky_key")
    val msc4354StickyKey = stringOrNull("msc4354_sticky_key")
    return when {
        stickyKey == null && msc4354StickyKey == null -> false
        stickyKey != null && msc4354StickyKey != null -> stickyKey == msc4354StickyKey
        else -> true
    }
}

private fun JSONArray?.toTransportList(): List<MatrixRtcTransport> {
    if (this == null) {
        return emptyList()
    }
    return (0 until length()).map { index ->
        MatrixRtcTransport.fromJsonObject(getJSONObject(index))
    }
}

