package com.zyna.app.data.local

import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallHistoryOutcome
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallNotificationType
import com.zyna.app.data.matrix.MatrixMembershipEventChange
import com.zyna.app.data.matrix.MatrixPinnedEventsChange
import com.zyna.app.data.matrix.MatrixRoomStateChange
import com.zyna.app.data.matrix.MatrixRtcCallEventDetails
import com.zyna.app.data.matrix.MatrixSystemEventDetails
import org.json.JSONArray
import org.json.JSONObject

/** Versioned, locale-independent serialization for semantic timeline rows. */
internal object MatrixTimelineDetailsCodec {
    private const val VERSION = 1

    fun encodeSystemEvent(details: MatrixSystemEventDetails): String {
        return when (details) {
            is MatrixSystemEventDetails.Membership -> JSONObject()
                .putVersionAndKind(KIND_MEMBERSHIP)
                .put(KEY_USER_ID, details.userId)
                .putOptional(KEY_USER_DISPLAY_NAME, details.userDisplayName)
                .put(KEY_CHANGE, details.change.name)
                .putOptional(KEY_REASON, details.reason)

            is MatrixSystemEventDetails.ProfileChange -> JSONObject()
                .putVersionAndKind(KIND_PROFILE_CHANGE)
                .putOptional(KEY_DISPLAY_NAME, details.displayName)
                .putOptional(KEY_PREVIOUS_DISPLAY_NAME, details.previousDisplayName)

            is MatrixSystemEventDetails.RoomState -> JSONObject()
                .putVersionAndKind(KIND_ROOM_STATE)
                .put(KEY_STATE_KEY, details.stateKey)
                .put(KEY_CHANGE, encodeRoomStateChange(details.change))
        }.toString()
    }

    fun decodeSystemEvent(json: String?): MatrixSystemEventDetails? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(json).requireCurrentVersion()
            when (root.getString(KEY_KIND)) {
                KIND_MEMBERSHIP -> MatrixSystemEventDetails.Membership(
                    userId = root.getString(KEY_USER_ID),
                    userDisplayName = root.optionalString(KEY_USER_DISPLAY_NAME),
                    change = enumValueOf<MatrixMembershipEventChange>(root.getString(KEY_CHANGE)),
                    reason = root.optionalString(KEY_REASON)
                )

                KIND_PROFILE_CHANGE -> MatrixSystemEventDetails.ProfileChange(
                    displayName = root.optionalString(KEY_DISPLAY_NAME),
                    previousDisplayName = root.optionalString(KEY_PREVIOUS_DISPLAY_NAME)
                )

                KIND_ROOM_STATE -> MatrixSystemEventDetails.RoomState(
                    stateKey = root.getString(KEY_STATE_KEY),
                    change = decodeRoomStateChange(root.getJSONObject(KEY_CHANGE))
                )

                else -> error("Unsupported system timeline details kind")
            }
        }.getOrNull()
    }

    fun encodeMatrixRtcCall(details: MatrixRtcCallEventDetails): String {
        return JSONObject()
            .putVersionAndKind(KIND_MATRIX_RTC_CALL)
            .putOptional(KEY_PARENT_EVENT_ID, details.parentEventId)
            .putOptional(KEY_CALL_INTENT, details.callIntent)
            .put(KEY_NOTIFICATION_TYPE, details.notificationType.name)
            .putOptional(KEY_EXPIRES_AT_MILLIS, details.expiresAtMillis)
            .put(
                KEY_DECLINED_BY,
                JSONArray().also { array ->
                    details.declinedBy.normalizedUserIds().forEach(array::put)
                }
            )
            .putOptional(KEY_OUTCOME, details.outcome?.name)
            .toString()
    }

    fun decodeMatrixRtcCall(json: String?): MatrixRtcCallEventDetails? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(json).requireCurrentVersion()
            check(root.getString(KEY_KIND) == KIND_MATRIX_RTC_CALL)
            MatrixRtcCallEventDetails(
                parentEventId = root.optionalString(KEY_PARENT_EVENT_ID),
                callIntent = root.optionalString(KEY_CALL_INTENT),
                notificationType = enumValueOf<MatrixRtcCallNotificationType>(
                    root.getString(KEY_NOTIFICATION_TYPE)
                ),
                expiresAtMillis = root.optionalLong(KEY_EXPIRES_AT_MILLIS),
                declinedBy = root.optJSONArray(KEY_DECLINED_BY)
                    ?.toStringList()
                    .orEmpty()
                    .normalizedUserIds(),
                outcome = root.optionalString(KEY_OUTCOME)
                    ?.let { enumValueOf<MatrixRtcCallHistoryOutcome>(it) }
            )
        }.getOrNull()
    }

    private fun encodeRoomStateChange(change: MatrixRoomStateChange): JSONObject {
        return when (change) {
            is MatrixRoomStateChange.Avatar -> JSONObject()
                .put(KEY_KIND, ROOM_CHANGE_AVATAR)
                .putOptional(KEY_URL, change.url)

            is MatrixRoomStateChange.Created -> JSONObject()
                .put(KEY_KIND, ROOM_CHANGE_CREATED)
                .put(KEY_FEDERATE, change.federate)

            MatrixRoomStateChange.EncryptionEnabled -> JSONObject()
                .put(KEY_KIND, ROOM_CHANGE_ENCRYPTION_ENABLED)

            is MatrixRoomStateChange.Name -> JSONObject()
                .put(KEY_KIND, ROOM_CHANGE_NAME)
                .putOptional(KEY_NAME, change.name)

            is MatrixRoomStateChange.PinnedEvents -> JSONObject()
                .put(KEY_KIND, ROOM_CHANGE_PINNED_EVENTS)
                .put(KEY_CHANGE, change.change.name)

            is MatrixRoomStateChange.ThirdPartyInvite -> JSONObject()
                .put(KEY_KIND, ROOM_CHANGE_THIRD_PARTY_INVITE)
                .put(KEY_DISPLAY_NAME, change.displayName)

            is MatrixRoomStateChange.Topic -> JSONObject()
                .put(KEY_KIND, ROOM_CHANGE_TOPIC)
                .putOptional(KEY_TOPIC, change.topic)
        }
    }

    private fun decodeRoomStateChange(json: JSONObject): MatrixRoomStateChange {
        return when (json.getString(KEY_KIND)) {
            ROOM_CHANGE_AVATAR -> MatrixRoomStateChange.Avatar(
                url = json.optionalString(KEY_URL)
            )

            ROOM_CHANGE_CREATED -> MatrixRoomStateChange.Created(
                federate = json.getBoolean(KEY_FEDERATE)
            )

            ROOM_CHANGE_ENCRYPTION_ENABLED -> MatrixRoomStateChange.EncryptionEnabled
            ROOM_CHANGE_NAME -> MatrixRoomStateChange.Name(
                name = json.optionalString(KEY_NAME)
            )

            ROOM_CHANGE_PINNED_EVENTS -> MatrixRoomStateChange.PinnedEvents(
                change = enumValueOf<MatrixPinnedEventsChange>(json.getString(KEY_CHANGE))
            )

            ROOM_CHANGE_THIRD_PARTY_INVITE -> MatrixRoomStateChange.ThirdPartyInvite(
                displayName = json.getString(KEY_DISPLAY_NAME)
            )

            ROOM_CHANGE_TOPIC -> MatrixRoomStateChange.Topic(
                topic = json.optionalString(KEY_TOPIC)
            )

            else -> error("Unsupported room state change kind")
        }
    }

    private fun JSONObject.putVersionAndKind(kind: String): JSONObject {
        return put(KEY_VERSION, VERSION).put(KEY_KIND, kind)
    }

    private fun JSONObject.requireCurrentVersion(): JSONObject {
        check(getInt(KEY_VERSION) == VERSION)
        return this
    }

    private fun JSONObject.putOptional(key: String, value: Any?): JSONObject {
        if (value != null) put(key, value)
        return this
    }

    private fun JSONObject.optionalString(key: String): String? {
        return if (has(key) && !isNull(key)) getString(key) else null
    }

    private fun JSONObject.optionalLong(key: String): Long? {
        return if (has(key) && !isNull(key)) getLong(key) else null
    }

    private fun JSONArray.toStringList(): List<String> {
        return (0 until length()).mapNotNull { index ->
            if (isNull(index)) null else optString(index).takeIf { it.isNotBlank() }
        }
    }

    private fun List<String>.normalizedUserIds(): List<String> {
        return asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toList()
    }

    private const val KEY_VERSION = "version"
    private const val KEY_KIND = "kind"
    private const val KEY_CHANGE = "change"
    private const val KEY_USER_ID = "userId"
    private const val KEY_USER_DISPLAY_NAME = "userDisplayName"
    private const val KEY_REASON = "reason"
    private const val KEY_DISPLAY_NAME = "displayName"
    private const val KEY_PREVIOUS_DISPLAY_NAME = "previousDisplayName"
    private const val KEY_STATE_KEY = "stateKey"
    private const val KEY_URL = "url"
    private const val KEY_FEDERATE = "federate"
    private const val KEY_NAME = "name"
    private const val KEY_TOPIC = "topic"
    private const val KEY_PARENT_EVENT_ID = "parentEventId"
    private const val KEY_CALL_INTENT = "callIntent"
    private const val KEY_NOTIFICATION_TYPE = "notificationType"
    private const val KEY_EXPIRES_AT_MILLIS = "expiresAtMillis"
    private const val KEY_DECLINED_BY = "declinedBy"
    private const val KEY_OUTCOME = "outcome"

    private const val KIND_MEMBERSHIP = "membership"
    private const val KIND_PROFILE_CHANGE = "profile_change"
    private const val KIND_ROOM_STATE = "room_state"
    private const val KIND_MATRIX_RTC_CALL = "matrix_rtc_call"

    private const val ROOM_CHANGE_AVATAR = "avatar"
    private const val ROOM_CHANGE_CREATED = "created"
    private const val ROOM_CHANGE_ENCRYPTION_ENABLED = "encryption_enabled"
    private const val ROOM_CHANGE_NAME = "name"
    private const val ROOM_CHANGE_PINNED_EVENTS = "pinned_events"
    private const val ROOM_CHANGE_THIRD_PARTY_INVITE = "third_party_invite"
    private const val ROOM_CHANGE_TOPIC = "topic"
}
