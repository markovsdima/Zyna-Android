package com.zyna.app.data.calls.matrixrtc

import org.matrix.rustcomponents.sdk.Room

class MatrixRustSdkRtcSessionMembershipClient(
    private val membershipClient: MatrixRustSdkRtcMembershipClient,
    private val room: Room,
    private val roomVersion: String? = null,
    private val timestampProvider: () -> Long = { System.currentTimeMillis() }
) : MatrixRtcSessionMembershipClient {
    private var closed = false

    override suspend fun publishOwnLegacyMembership(
        slot: MatrixRtcSlotDescription,
        roomVersion: String?,
        focusSelection: MatrixRtcLegacyCallMembershipFocusSelection,
        fociPreferred: List<MatrixRtcTransport>,
        createdTimestamp: Long?,
        expires: Long,
        callIntent: String?
    ): MatrixRtcCallMembership {
        val published = membershipClient.publishOwnLegacyMembership(
            room = room,
            slot = slot,
            roomVersion = roomVersion ?: this.roomVersion,
            focusSelection = focusSelection,
            fociPreferred = fociPreferred,
            createdTimestamp = createdTimestamp,
            expires = expires,
            callIntent = callIntent
        )
        val effectiveCreatedTimestamp = published.createdTimestamp
            ?: createdTimestamp
            ?: timestampProvider()
        return MatrixRtcCallMembership(
            kind = MatrixRtcCallMembership.Kind.LEGACY_STATE,
            eventId = published.eventId,
            eventType = MatrixRtcRawMembershipEvent.LEGACY_CALL_MEMBER_EVENT_TYPE,
            stateKey = published.stateKey,
            sender = published.identity.userId,
            identity = published.identity,
            slot = slot,
            createdTimestamp = effectiveCreatedTimestamp,
            absoluteExpiryTimestamp = effectiveCreatedTimestamp + expires,
            rtcBackendIdentity = published.identity.legacyRtcBackendIdentity,
            transports = fociPreferred,
            focusSelection = focusSelection.wireValue,
            callIntent = callIntent
        )
    }

    override suspend fun loadActiveMemberships(
        slot: MatrixRtcSlotDescription,
        joinedUserIds: Set<String>?,
        now: Long
    ): List<MatrixRtcCallMembership> {
        return membershipClient.loadActiveMemberships(
            roomId = room.id(),
            slot = slot,
            joinedUserIds = joinedUserIds,
            now = now
        )
    }

    override suspend fun leaveOwnLegacyMembership(
        slot: MatrixRtcSlotDescription,
        roomVersion: String?
    ): String {
        return membershipClient.leaveOwnLegacyMembership(
            room = room,
            slot = slot,
            roomVersion = roomVersion ?: this.roomVersion
        )
    }

    override suspend fun scheduleDelayedLeaveOwnLegacyMembership(
        slot: MatrixRtcSlotDescription,
        roomVersion: String?,
        delayMillis: ULong
    ): String {
        return membershipClient.scheduleDelayedLeaveOwnLegacyMembership(
            room = room,
            slot = slot,
            roomVersion = roomVersion ?: this.roomVersion,
            delayMillis = delayMillis
        )
    }

    override suspend fun restartDelayedEvent(delayId: String) {
        membershipClient.restartDelayedEvent(delayId)
    }

    override suspend fun sendDelayedEvent(delayId: String) {
        membershipClient.sendDelayedEvent(delayId)
    }

    override suspend fun cancelDelayedEvent(delayId: String) {
        membershipClient.cancelDelayedEvent(delayId)
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        room.destroy()
    }
}
