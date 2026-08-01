package com.zyna.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class RoomListCachePolicyTest {
    @Test
    fun partialLivePrefixKeepsCachedTailInSdkOrder() {
        val first = cachedRoom("!first:example.org", position = 0)
        val second = cachedRoom("!second:example.org", position = 1)
        val third = cachedRoom("!third:example.org", position = 2)

        val result = reconcileCachedRoomListOrder(
            liveRoomIds = listOf(second.id),
            existing = listOf(first, second, third),
            isComplete = false
        )

        assertEquals(
            listOf("!second:example.org", "!first:example.org", "!third:example.org"),
            result.map(CachedRoomListOrder::id)
        )
        assertEquals(0L, result.single { it.id == first.id }.listPosition)
        assertEquals(2L, result.single { it.id == third.id }.listPosition)
    }

    @Test
    fun completeSnapshotRemovesCachedTailButRetainsExplicitGraceRoom() {
        val first = cachedRoom("!first:example.org", position = 0)
        val second = cachedRoom("!second:example.org", position = 1)
        val grace = cachedRoom("!grace:example.org", position = null)

        val result = reconcileCachedRoomListOrder(
            liveRoomIds = listOf(second.id),
            existing = listOf(first, second),
            retainedExtraRoomIds = listOf(grace.id),
            isComplete = true
        )

        assertEquals(
            listOf("!grace:example.org", "!second:example.org"),
            result.map(CachedRoomListOrder::id)
        )
        assertEquals(
            true,
            requireNotNull(result[0].listPosition) < requireNotNull(result[1].listPosition)
        )
    }

    @Test
    fun partialSnapshotRemovesExplicitlyExcludedCachedRoom() {
        val joined = cachedRoom("!joined:example.org", position = 0)
        val left = cachedRoom("!left:example.org", position = 1)

        val result = reconcileCachedRoomListOrder(
            liveRoomIds = listOf(joined.id),
            existing = listOf(joined, left),
            excludedRoomIds = setOf(left.id),
            isComplete = false
        )

        assertEquals(listOf(joined.id), result.map(CachedRoomListOrder::id))
    }

    @Test
    fun movingOneRoomAcrossSparseOrderChangesOnlyOnePosition() {
        val first = cachedRoom("!first:example.org", position = 0)
        val second = cachedRoom("!second:example.org", position = 1_048_576)
        val third = cachedRoom("!third:example.org", position = 2_097_152)
        val existing = listOf(first, second, third)

        val result = reconcileCachedRoomListOrder(
            liveRoomIds = listOf(third.id, first.id, second.id),
            existing = existing,
            isComplete = true
        )

        assertEquals(listOf(third.id, first.id, second.id), result.map(CachedRoomListOrder::id))
        assertEquals(
            setOf(third.id),
            result.filter { room ->
                room.listPosition != existing.single { it.id == room.id }.listPosition
            }.mapTo(mutableSetOf(), CachedRoomListOrder::id)
        )
    }

    @Test
    fun unchangedMigratedOrderReceivesMissingPositions() {
        val first = cachedRoom("!first:example.org", position = null)
        val second = cachedRoom("!second:example.org", position = null)

        val result = reconcileCachedRoomListOrder(
            liveRoomIds = listOf(first.id, second.id),
            existing = listOf(first, second),
            isComplete = true
        )

        assertEquals(listOf(first.id, second.id), result.map(CachedRoomListOrder::id))
        assertEquals(listOf(0L, 1_048_576L), result.map(CachedRoomListOrder::listPosition))
    }

    @Test
    fun unchangedOrderRepairsDuplicatePositions() {
        val first = cachedRoom("!first:example.org", position = 0)
        val second = cachedRoom("!second:example.org", position = 0)

        val result = reconcileCachedRoomListOrder(
            liveRoomIds = listOf(first.id, second.id),
            existing = listOf(first, second),
            isComplete = true
        )

        assertEquals(listOf(first.id, second.id), result.map(CachedRoomListOrder::id))
        assertEquals(true, requireNotNull(result[0].listPosition) < requireNotNull(result[1].listPosition))
    }

    @Test
    fun expiredPendingRoomIsNotRetainedByPartialSnapshots() {
        val active = PendingResolvedRoomSummary(
            room = roomSummary("!active:example.org"),
            remainingAbsentSnapshots = 1,
            expiresAtMillis = 2_000
        )
        val expired = PendingResolvedRoomSummary(
            room = roomSummary("!expired:example.org"),
            remainingAbsentSnapshots = 1,
            expiresAtMillis = 1_000
        )

        val result = activePendingResolvedRooms(
            pendingRooms = mapOf(active.room.id to active, expired.room.id to expired),
            nowMillis = 1_500
        )

        assertEquals(setOf(active.room.id), result.keys)
    }

    @Test
    fun expiryDeletesOnlyRoomsThatWereNewToTheCache() {
        val newRoom = PendingResolvedRoomSummary(
            room = roomSummary("!new:example.org"),
            remainingAbsentSnapshots = 1,
            expiresAtMillis = 1_000,
            removeFromCacheOnExpiry = true
        )
        val existingDirectRoom = PendingResolvedRoomSummary(
            room = roomSummary("!existing:example.org"),
            remainingAbsentSnapshots = 1,
            expiresAtMillis = 1_000,
            removeFromCacheOnExpiry = false
        )

        val result = expiredProvisionalRoomIds(
            pendingCandidates = mapOf(
                newRoom.room.id to newRoom,
                existingDirectRoom.room.id to existingDirectRoom
            ),
            activePendingRoomIds = emptySet()
        )

        assertEquals(setOf(newRoom.room.id), result)
    }

    private fun cachedRoom(id: String, position: Long?): CachedRoomListOrder {
        return CachedRoomListOrder(id = id, listPosition = position)
    }

    private fun roomSummary(id: String) = com.zyna.app.data.matrix.MatrixRoomSummary(
        id = id,
        displayName = id,
        avatarUrl = null
    )
}
