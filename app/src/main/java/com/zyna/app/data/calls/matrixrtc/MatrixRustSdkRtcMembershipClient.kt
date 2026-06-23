package com.zyna.app.data.calls.matrixrtc

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.DelayedEventException
import org.matrix.rustcomponents.sdk.Room

data class MatrixRustSdkRtcPublishedMembership(
    val eventId: String,
    val identity: MatrixRtcMembershipIdentity,
    val stateKey: String,
    val createdTimestamp: Long?
)

sealed class MatrixRtcSessionDelayedEventException(
    message: String? = null
) : Exception(message) {
    data object Unsupported : MatrixRtcSessionDelayedEventException()
    data object NotFound : MatrixRtcSessionDelayedEventException()
    data class RateLimited(val retryAfterMillis: ULong?) : MatrixRtcSessionDelayedEventException()
    data class MaxDelayExceeded(val maxDelayMillis: ULong?) : MatrixRtcSessionDelayedEventException()
    data class Generic(val msg: String, val details: String?) : MatrixRtcSessionDelayedEventException(
        if (details.isNullOrBlank()) msg else "$msg: $details"
    )
}

class MatrixRustSdkRtcMembershipClient(
    private val client: Client,
    private val httpClient: MatrixRtcHttpClient = MatrixRtcUrlConnectionHttpClient()
) {
    suspend fun loadRawMembershipEvents(roomId: String): List<MatrixRtcRawMembershipEvent> {
        val session = client.session()
        val response = httpClient.execute(
            MatrixRtcHttpRequest(
                url = matrixClientUrl(
                    session.homeserverUrl,
                    "/_matrix/client/v3/rooms/${percentEncodePathComponent(roomId)}/state"
                ),
                headers = mapOf("Authorization" to "Bearer ${session.accessToken}")
            )
        )
        if (response.statusCode !in 200..299) {
            throw HttpStatusException(response.statusCode)
        }

        return matrixRtcRawMembershipEventsFromStateJson(response.body.decodeToString())
    }

    suspend fun loadActiveMemberships(
        roomId: String,
        slot: MatrixRtcSlotDescription = MatrixRtcSlotDescription.MATRIX_CALL_ROOM,
        joinedUserIds: Set<String>? = null,
        now: Long = System.currentTimeMillis()
    ): List<MatrixRtcCallMembership> {
        return MatrixRtcCallMembershipParser.activeMemberships(
            events = loadRawMembershipEvents(roomId),
            slot = slot,
            joinedUserIds = joinedUserIds,
            now = now
        )
    }

    suspend fun publishOwnLegacyMembership(
        room: Room,
        slot: MatrixRtcSlotDescription = MatrixRtcSlotDescription.MATRIX_CALL_ROOM,
        roomVersion: String? = null,
        focusSelection: MatrixRtcLegacyCallMembershipFocusSelection =
            MatrixRtcLegacyCallMembershipFocusSelection.OLDEST_MEMBERSHIP,
        fociPreferred: List<MatrixRtcTransport>,
        createdTimestamp: Long? = null,
        expires: Long = MatrixRtcCallMembership.DEFAULT_EXPIRE_DURATION_MILLIS,
        callIntent: String? = null
    ): MatrixRustSdkRtcPublishedMembership {
        val session = client.session()
        val stateEvent = MatrixRtcLegacyCallMembershipStateEvent.create(
            userId = session.userId,
            deviceId = session.deviceId,
            slot = slot,
            roomVersion = roomVersion,
            focusSelection = focusSelection,
            fociPreferred = fociPreferred,
            createdTimestamp = createdTimestamp,
            expires = expires,
            callIntent = callIntent
        )
        val eventId = room.sendStateEventRaw(
            eventType = MatrixRtcRawMembershipEvent.LEGACY_CALL_MEMBER_EVENT_TYPE,
            stateKey = stateEvent.stateKey,
            content = stateEvent.contentJson()
        )
        return MatrixRustSdkRtcPublishedMembership(
            eventId = eventId,
            identity = stateEvent.identity,
            stateKey = stateEvent.stateKey,
            createdTimestamp = createdTimestamp
        )
    }

    suspend fun leaveOwnLegacyMembership(
        room: Room,
        slot: MatrixRtcSlotDescription = MatrixRtcSlotDescription.MATRIX_CALL_ROOM,
        roomVersion: String? = null
    ): String {
        val stateKey = ownLegacyMembershipStateKey(slot, roomVersion)
        return room.sendStateEventRaw(
            eventType = MatrixRtcRawMembershipEvent.LEGACY_CALL_MEMBER_EVENT_TYPE,
            stateKey = stateKey,
            content = MatrixRtcLegacyCallMembershipStateEvent.LEAVE_CONTENT_JSON
        )
    }

    suspend fun scheduleDelayedLeaveOwnLegacyMembership(
        room: Room,
        slot: MatrixRtcSlotDescription = MatrixRtcSlotDescription.MATRIX_CALL_ROOM,
        roomVersion: String? = null,
        delayMillis: ULong
    ): String {
        return try {
            client.scheduleDelayedStateEvent(
                roomId = room.id(),
                eventType = MatrixRtcRawMembershipEvent.LEGACY_CALL_MEMBER_EVENT_TYPE,
                stateKey = ownLegacyMembershipStateKey(slot, roomVersion),
                contentJson = MatrixRtcLegacyCallMembershipStateEvent.LEAVE_CONTENT_JSON,
                delayMs = delayMillis
            )
        } catch (error: DelayedEventException) {
            throw error.toMatrixRtcDelayedEventException()
        }
    }

    suspend fun restartDelayedEvent(delayId: String) {
        try {
            client.restartDelayedEvent(delayId)
        } catch (error: DelayedEventException) {
            throw error.toMatrixRtcDelayedEventException()
        }
    }

    suspend fun sendDelayedEvent(delayId: String) {
        try {
            client.sendDelayedEvent(delayId)
        } catch (error: DelayedEventException) {
            throw error.toMatrixRtcDelayedEventException()
        }
    }

    suspend fun cancelDelayedEvent(delayId: String) {
        try {
            client.cancelDelayedEvent(delayId)
        } catch (error: DelayedEventException) {
            throw error.toMatrixRtcDelayedEventException()
        }
    }

    private fun ownLegacyMembershipStateKey(
        slot: MatrixRtcSlotDescription,
        roomVersion: String?
    ): String {
        val session = client.session()
        return MatrixRtcLegacyCallMembershipStateEvent.stateKey(
            userId = session.userId,
            deviceId = session.deviceId,
            slot = slot,
            roomVersion = roomVersion
        )
    }
}

data class MatrixRtcCallNotificationSendResult(
    val sentNotification: Boolean,
    val notificationEventId: String?,
    val sentLegacyFallback: Boolean,
    val legacyFallbackEventId: String?,
    val notificationType: MatrixRtcCallNotificationType,
    val senderTimestamp: Long,
    val lifetimeMillis: Long
)

interface MatrixRtcCallNotificationClient {
    suspend fun sendCallNotification(
        parentEventId: String,
        slot: MatrixRtcSlotDescription = MatrixRtcSlotDescription.MATRIX_CALL_ROOM,
        notificationType: MatrixRtcCallNotificationType = MatrixRtcCallNotificationType.RING,
        callIntent: String? = null
    ): MatrixRtcCallNotificationSendResult
}

class MatrixRustSdkRtcCallNotificationClient(
    private val room: Room,
    private val timestampProvider: () -> Long = { System.currentTimeMillis() }
) : MatrixRtcCallNotificationClient {
    override suspend fun sendCallNotification(
        parentEventId: String,
        slot: MatrixRtcSlotDescription,
        notificationType: MatrixRtcCallNotificationType,
        callIntent: String?
    ): MatrixRtcCallNotificationSendResult {
        val content = MatrixRtcCallNotificationContent.create(
            parentEventId = parentEventId,
            notificationType = notificationType,
            senderTimestamp = timestampProvider(),
            callIntent = callIntent
        )
        val legacyContent = MatrixRtcLegacyCallNotifyContent.create(
            slot = slot,
            notificationType = notificationType
        )

        var firstError: Throwable? = null
        var notificationEventId: String? = null
        var legacyFallbackEventId: String? = null

        try {
            notificationEventId = room.sendRawWithTransactionIdReturningEventId(
                eventType = MatrixRtcCallNotificationContent.EVENT_TYPE,
                content = content.jsonString(),
                transactionId = "matrixrtc-notification-${UUID.randomUUID()}"
            )
        } catch (error: Throwable) {
            firstError = error
        }

        try {
            legacyFallbackEventId = room.sendRawWithTransactionIdReturningEventId(
                eventType = MatrixRtcLegacyCallNotifyContent.EVENT_TYPE,
                content = legacyContent.jsonString(),
                transactionId = "matrixrtc-legacy-notify-${UUID.randomUUID()}"
            )
        } catch (error: Throwable) {
            if (firstError == null) {
                firstError = error
            }
        }

        if (notificationEventId == null && legacyFallbackEventId == null) {
            throw firstError ?: IllegalStateException("MatrixRTC call notification send failed")
        }

        return MatrixRtcCallNotificationSendResult(
            sentNotification = notificationEventId != null,
            notificationEventId = notificationEventId,
            sentLegacyFallback = legacyFallbackEventId != null,
            legacyFallbackEventId = legacyFallbackEventId,
            notificationType = notificationType,
            senderTimestamp = content.senderTimestamp,
            lifetimeMillis = content.lifetime
        )
    }
}

internal fun matrixRtcRawMembershipEventsFromStateJson(stateJson: String): List<MatrixRtcRawMembershipEvent> {
    val events = JSONArray(stateJson)
    return (0 until events.length()).mapNotNull { index ->
        events.getJSONObject(index).toRawMembershipEventOrNull()
    }
}

private fun JSONObject.toRawMembershipEventOrNull(): MatrixRtcRawMembershipEvent? {
    val eventType = getString("type")
    if (
        eventType != MatrixRtcRawMembershipEvent.LEGACY_CALL_MEMBER_EVENT_TYPE &&
        eventType != MatrixRtcRawMembershipEvent.RTC_MEMBER_EVENT_TYPE
    ) {
        return null
    }
    val eventId = stringOrNull("event_id") ?: return null
    val sender = stringOrNull("sender") ?: return null
    val timestamp = longOrNull("origin_server_ts") ?: return null
    return MatrixRtcRawMembershipEvent(
        eventId = eventId,
        eventType = eventType,
        stateKey = stringOrNull("state_key"),
        sender = sender,
        originServerTimestamp = timestamp,
        contentJson = getJSONObject("content").toString()
    )
}

private fun DelayedEventException.toMatrixRtcDelayedEventException(): MatrixRtcSessionDelayedEventException {
    return when (this) {
        is DelayedEventException.DelayedEventsUnsupported ->
            MatrixRtcSessionDelayedEventException.Unsupported
        is DelayedEventException.NotFound ->
            MatrixRtcSessionDelayedEventException.NotFound
        is DelayedEventException.RateLimited ->
            MatrixRtcSessionDelayedEventException.RateLimited(retryAfterMs)
        is DelayedEventException.MaxDelayExceeded ->
            MatrixRtcSessionDelayedEventException.MaxDelayExceeded(maxDelayMs)
        is DelayedEventException.Generic ->
            MatrixRtcSessionDelayedEventException.Generic(msg, details)
    }
}
