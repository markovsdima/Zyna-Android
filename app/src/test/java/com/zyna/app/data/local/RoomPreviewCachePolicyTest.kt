package com.zyna.app.data.local

import com.zyna.app.data.matrix.MatrixLastOwnMessageStatus
import com.zyna.app.data.matrix.MatrixRoomSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomPreviewCachePolicyTest {
    @Test
    fun nonPreviewableSnapshot_preservesEntireExistingPreview() {
        val existing = cachedRoom(
            text = "Existing message",
            senderName = "Alice",
            timestampMillis = 1_000L,
            status = MatrixLastOwnMessageStatus.READ.name
        )
        val incoming = roomSummary(
            text = "Incomplete incoming preview",
            senderName = "Bob",
            timestampMillis = null,
            status = MatrixLastOwnMessageStatus.SENT
        )

        assertEquals(
            CachedRoomPreview(
                text = "Existing message",
                senderName = "Alice",
                timestampMillis = 1_000L,
                lastOwnMessageStatus = MatrixLastOwnMessageStatus.READ.name
            ),
            selectRoomPreviewForCache(incoming, existing)
        )
    }

    @Test
    fun incomingMessageSnapshot_replacesEntireExistingPreviewIncludingNullStatus() {
        val existing = cachedRoom(
            text = "Existing message",
            senderName = "Alice",
            timestampMillis = 1_000L,
            status = MatrixLastOwnMessageStatus.READ.name
        )
        val incoming = roomSummary(
            text = "New message",
            senderName = "Bob",
            timestampMillis = 2_000L,
            status = null
        )

        assertEquals(
            CachedRoomPreview(
                text = "New message",
                senderName = "Bob",
                timestampMillis = 2_000L,
                lastOwnMessageStatus = null
            ),
            selectRoomPreviewForCache(incoming, existing)
        )
    }

    @Test
    fun olderIncomingSnapshot_preservesEntireNewerExistingPreview() {
        val existing = cachedRoom(
            text = "New local message",
            senderName = "You",
            timestampMillis = 2_000L,
            status = MatrixLastOwnMessageStatus.PENDING.name
        )
        val incoming = roomSummary(
            text = "Old SDK message",
            senderName = "Alice",
            timestampMillis = 1_000L,
            status = null
        )

        assertEquals(
            CachedRoomPreview(
                text = "New local message",
                senderName = "You",
                timestampMillis = 2_000L,
                lastOwnMessageStatus = MatrixLastOwnMessageStatus.PENDING.name
            ),
            selectRoomPreviewForCache(incoming, existing)
        )
    }

    @Test
    fun equalTimestamp_usesIncomingTupleButDoesNotRegressDeliveryProgress() {
        val existing = cachedRoom(
            text = "Original text",
            senderName = "You",
            timestampMillis = 2_000L,
            status = MatrixLastOwnMessageStatus.READ.name
        )
        val incoming = roomSummary(
            text = "Edited text",
            senderName = "You",
            timestampMillis = 2_000L,
            status = MatrixLastOwnMessageStatus.SENT
        )

        assertEquals(
            CachedRoomPreview(
                text = "Edited text",
                senderName = "You",
                timestampMillis = 2_000L,
                lastOwnMessageStatus = MatrixLastOwnMessageStatus.READ.name
            ),
            selectRoomPreviewForCache(incoming, existing)
        )
    }

    @Test
    fun equalTimestamp_incomingRemoteTupleClearsStaleOwnMessageStatus() {
        val existing = cachedRoom(
            text = "Own message",
            senderName = "You",
            timestampMillis = 2_000L,
            status = MatrixLastOwnMessageStatus.READ.name
        )
        val incoming = roomSummary(
            text = "Remote message",
            senderName = "Alice",
            timestampMillis = 2_000L,
            status = null
        )

        assertEquals(
            CachedRoomPreview(
                text = "Remote message",
                senderName = "Alice",
                timestampMillis = 2_000L,
                lastOwnMessageStatus = null
            ),
            selectRoomPreviewForCache(incoming, existing)
        )
    }

    @Test
    fun inviteSnapshot_replacesOldMessageAsIntentionalTimestampOnlyTuple() {
        val existing = cachedRoom(
            text = "Existing message",
            senderName = "Alice",
            timestampMillis = 1_000L,
            status = MatrixLastOwnMessageStatus.READ.name
        )
        val incoming = roomSummary(
            text = null,
            senderName = null,
            timestampMillis = 3_000L,
            status = null
        )

        val selected = selectRoomPreviewForCache(incoming, existing)

        assertNull(selected.text)
        assertNull(selected.senderName)
        assertEquals(3_000L, selected.timestampMillis)
        assertNull(selected.lastOwnMessageStatus)
    }

    @Test
    fun pendingResolvedRoom_survivesOneMissingAuthoritativeSnapshotOnly() {
        val pendingRoom = roomSummary(
            id = "!pending:example.org",
            text = null,
            senderName = null,
            timestampMillis = null,
            status = null
        )
        val firstMerge = mergeRoomSnapshotWithPendingResolvedRooms(
            authoritativeRooms = emptyList(),
            pendingRooms = mapOf(
                pendingRoom.id to PendingResolvedRoomSummary(
                    room = pendingRoom,
                    remainingAbsentSnapshots = 1
                )
            )
        )

        assertEquals(listOf(pendingRoom), firstMerge.rooms)
        assertEquals(
            emptyMap<String, PendingResolvedRoomSummary>(),
            firstMerge.remainingPendingRooms
        )

        val secondMerge = mergeRoomSnapshotWithPendingResolvedRooms(
            authoritativeRooms = emptyList(),
            pendingRooms = firstMerge.remainingPendingRooms
        )

        assertEquals(emptyList<MatrixRoomSummary>(), secondMerge.rooms)
        assertEquals(
            emptyMap<String, PendingResolvedRoomSummary>(),
            secondMerge.remainingPendingRooms
        )
    }

    @Test
    fun authoritativeRoom_replacesAndConfirmsPendingResolvedRoom() {
        val pendingRoom = roomSummary(
            id = "!pending:example.org",
            text = null,
            senderName = null,
            timestampMillis = null,
            status = null
        )
        val authoritativeRoom = pendingRoom.copy(displayName = "Synced room")

        val merge = mergeRoomSnapshotWithPendingResolvedRooms(
            authoritativeRooms = listOf(authoritativeRoom),
            pendingRooms = mapOf(
                pendingRoom.id to PendingResolvedRoomSummary(
                    room = pendingRoom,
                    remainingAbsentSnapshots = 1
                )
            )
        )

        assertEquals(listOf(authoritativeRoom), merge.rooms)
        assertEquals(
            emptyMap<String, PendingResolvedRoomSummary>(),
            merge.remainingPendingRooms
        )
    }

    private fun roomSummary(
        id: String = ROOM_ID,
        text: String?,
        senderName: String?,
        timestampMillis: Long?,
        status: MatrixLastOwnMessageStatus?
    ): MatrixRoomSummary {
        return MatrixRoomSummary(
            id = id,
            displayName = "Room",
            avatarUrl = null,
            lastMessageText = text,
            lastMessageSenderName = senderName,
            lastMessageAtMillis = timestampMillis,
            lastOwnMessageStatus = status
        )
    }

    private fun cachedRoom(
        text: String?,
        senderName: String?,
        timestampMillis: Long?,
        status: String?
    ): CachedRoomEntity {
        return CachedRoomEntity(
            userId = "@me:example.org",
            id = ROOM_ID,
            displayName = "Room",
            avatarUrl = null,
            directUserId = null,
            lastMessageText = text,
            lastMessageSenderName = senderName,
            lastMessageAtMillis = timestampMillis,
            lastOwnMessageStatus = status,
            unreadCount = 0,
            unreadMentionCount = 0,
            isMarkedUnread = false,
            updatedAtMillis = 0L
        )
    }

    private companion object {
        const val ROOM_ID = "!room:example.org"
    }
}
