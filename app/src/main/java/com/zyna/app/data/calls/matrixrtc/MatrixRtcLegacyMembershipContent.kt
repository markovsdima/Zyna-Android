package com.zyna.app.data.calls.matrixrtc

import org.json.JSONArray
import org.json.JSONObject

enum class MatrixRtcLegacyCallMembershipFocusSelection(val wireValue: String) {
    OLDEST_MEMBERSHIP("oldest_membership"),
    MULTI_SFU("multi_sfu");

    companion object {
        fun fromWireValue(value: String): MatrixRtcLegacyCallMembershipFocusSelection {
            return entries.first { it.wireValue == value }
        }
    }
}

data class MatrixRtcLegacyCallMembershipContent(
    val application: String,
    val callId: String,
    val scope: String,
    val deviceId: String,
    val focusActive: FocusActive,
    val fociPreferred: List<MatrixRtcTransport>,
    val createdTimestamp: Long?,
    val expires: Long,
    val callIntent: String?,
    val membershipId: String
) {
    data class FocusActive(
        val type: String,
        val focusSelection: MatrixRtcLegacyCallMembershipFocusSelection
    )

    fun jsonString(): String {
        val json = JSONObject()
            .put("application", application)
            .put("call_id", callId)
            .put("scope", scope)
            .put("device_id", deviceId)
            .put(
                "focus_active",
                JSONObject()
                    .put("type", focusActive.type)
                    .put("focus_selection", focusActive.focusSelection.wireValue)
            )
            .put(
                "foci_preferred",
                JSONArray().also { array ->
                    fociPreferred.forEach { array.put(it.toJsonObject()) }
                }
            )
            .put("expires", expires)
            .put("membershipID", membershipId)
        json.putIfNotNull("created_ts", createdTimestamp)
        json.putIfNotNull("m.call.intent", callIntent)
        return json.toString()
    }

    companion object {
        fun create(
            slot: MatrixRtcSlotDescription = MatrixRtcSlotDescription.MATRIX_CALL_ROOM,
            deviceId: String,
            focusSelection: MatrixRtcLegacyCallMembershipFocusSelection,
            fociPreferred: List<MatrixRtcTransport>,
            createdTimestamp: Long?,
            expires: Long = MatrixRtcCallMembership.DEFAULT_EXPIRE_DURATION_MILLIS,
            callIntent: String?,
            membershipId: String
        ): MatrixRtcLegacyCallMembershipContent {
            return MatrixRtcLegacyCallMembershipContent(
                application = slot.application,
                callId = slot.legacyCallId,
                scope = "m.room",
                deviceId = deviceId,
                focusActive = FocusActive(type = "livekit", focusSelection = focusSelection),
                fociPreferred = fociPreferred,
                createdTimestamp = createdTimestamp,
                expires = expires,
                callIntent = callIntent,
                membershipId = membershipId
            )
        }

        fun fromJson(contentJson: String): MatrixRtcLegacyCallMembershipContent {
            val json = JSONObject(contentJson)
            val focusActive = json.getJSONObject("focus_active")
            val fociPreferred = json.optJSONArray("foci_preferred")
            return MatrixRtcLegacyCallMembershipContent(
                application = json.getString("application"),
                callId = json.getString("call_id"),
                scope = json.getString("scope"),
                deviceId = json.getString("device_id"),
                focusActive = FocusActive(
                    type = focusActive.getString("type"),
                    focusSelection = MatrixRtcLegacyCallMembershipFocusSelection.fromWireValue(
                        focusActive.getString("focus_selection")
                    )
                ),
                fociPreferred = if (fociPreferred == null) {
                    emptyList()
                } else {
                    (0 until fociPreferred.length()).map { index ->
                        MatrixRtcTransport.fromJsonObject(fociPreferred.getJSONObject(index))
                    }
                },
                createdTimestamp = json.longOrNull("created_ts"),
                expires = json.getLong("expires"),
                callIntent = json.stringOrNull("m.call.intent"),
                membershipId = json.getString("membershipID")
            )
        }
    }
}

data class MatrixRtcLegacyCallMembershipStateEvent(
    val identity: MatrixRtcMembershipIdentity,
    val stateKey: String,
    val content: MatrixRtcLegacyCallMembershipContent
) {
    fun contentJson(): String {
        return content.jsonString()
    }

    companion object {
        const val LEAVE_CONTENT_JSON = "{}"

        fun create(
            userId: String,
            deviceId: String,
            slot: MatrixRtcSlotDescription = MatrixRtcSlotDescription.MATRIX_CALL_ROOM,
            roomVersion: String? = null,
            focusSelection: MatrixRtcLegacyCallMembershipFocusSelection,
            fociPreferred: List<MatrixRtcTransport>,
            createdTimestamp: Long?,
            expires: Long = MatrixRtcCallMembership.DEFAULT_EXPIRE_DURATION_MILLIS,
            callIntent: String?
        ): MatrixRtcLegacyCallMembershipStateEvent {
            val membershipId = MatrixRtcMembershipIdentity.legacyRtcBackendIdentity(
                userId = userId,
                deviceId = deviceId
            )
            return MatrixRtcLegacyCallMembershipStateEvent(
                identity = MatrixRtcMembershipIdentity(
                    userId = userId,
                    deviceId = deviceId,
                    memberId = membershipId
                ),
                stateKey = stateKey(
                    userId = userId,
                    deviceId = deviceId,
                    slot = slot,
                    roomVersion = roomVersion
                ),
                content = MatrixRtcLegacyCallMembershipContent.create(
                    slot = slot,
                    deviceId = deviceId,
                    focusSelection = focusSelection,
                    fociPreferred = fociPreferred,
                    createdTimestamp = createdTimestamp,
                    expires = expires,
                    callIntent = callIntent,
                    membershipId = membershipId
                )
            )
        }

        fun stateKey(
            userId: String,
            deviceId: String,
            slot: MatrixRtcSlotDescription = MatrixRtcSlotDescription.MATRIX_CALL_ROOM,
            roomVersion: String? = null
        ): String {
            val base = "${userId}_${deviceId}_${slot.application}${slot.legacyCallId}"
            return if (shouldPrefixStateKey(roomVersion)) "_$base" else base
        }

        private fun shouldPrefixStateKey(roomVersion: String?): Boolean {
            return roomVersion == null ||
                !Regex("^org\\.matrix\\.msc(3757|3779)\\b").containsMatchIn(roomVersion)
        }
    }
}
