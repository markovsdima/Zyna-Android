package com.zyna.app.data.local

import android.content.Context
import androidx.room.withTransaction
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallHistoryItem
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallHistoryOutcome
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallNotificationType
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallTimelineMembership
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallTimelineNotification
import com.zyna.app.data.matrix.MatrixAudioInfo
import com.zyna.app.data.matrix.MatrixChatMessage
import com.zyna.app.data.matrix.MatrixForwardImageItem
import com.zyna.app.data.matrix.MatrixImageInfo
import com.zyna.app.data.matrix.MatrixIncomingRtcCallNotification
import com.zyna.app.data.matrix.MatrixIncomingRtcCallNotificationKind
import com.zyna.app.data.matrix.MatrixLastOwnMessageStatus
import com.zyna.app.data.matrix.MatrixMessageReaction
import com.zyna.app.data.matrix.MatrixMessageContentType
import com.zyna.app.data.matrix.MatrixMessageDeliveryState
import com.zyna.app.data.matrix.MatrixReactionSender
import com.zyna.app.data.matrix.MatrixReplyInfo
import com.zyna.app.data.matrix.MatrixRoomAccess
import com.zyna.app.data.matrix.MatrixRoomCapabilities
import com.zyna.app.data.matrix.MatrixRoomCreatorSemantics
import com.zyna.app.data.matrix.MatrixRoomDetails
import com.zyna.app.data.matrix.MatrixRoomEncryption
import com.zyna.app.data.matrix.MatrixRoomHistoryVisibility
import com.zyna.app.data.matrix.MatrixRoomKind
import com.zyna.app.data.matrix.MatrixRoomSummary
import com.zyna.app.data.matrix.MatrixRtcCallEventDetails
import com.zyna.app.data.matrix.MatrixSpaceMembership
import com.zyna.app.data.matrix.toMatrixChatMessage
import com.zyna.app.data.messaging.ZynaHtmlCodec
import com.zyna.app.data.messaging.ZynaMessageAttributes
import com.zyna.app.data.messaging.normalizedMessageCaption
import com.zyna.app.data.outgoing.OutgoingEnvelopeKind
import com.zyna.app.data.outgoing.OutgoingEditEnvelope
import com.zyna.app.data.outgoing.OutgoingImageEnvelope
import com.zyna.app.data.outgoing.OutgoingMediaStorage
import com.zyna.app.data.outgoing.OutgoingReactionEnvelope
import com.zyna.app.data.outgoing.OutgoingRedactionEnvelope
import com.zyna.app.data.outgoing.OutgoingTextEnvelope
import com.zyna.app.data.outgoing.OutgoingTransportState
import com.zyna.app.data.outgoing.OutgoingVoiceEnvelope
import com.zyna.app.data.outgoing.PendingReactionState
import com.zyna.app.util.ZynaPerfLog
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class CachedRoomPreview(
    val text: String?,
    val senderName: String?,
    val timestampMillis: Long?,
    val lastOwnMessageStatus: String?
)

internal fun selectRoomPreviewForCache(
    incomingRoom: MatrixRoomSummary,
    existingRoom: CachedRoomEntity?
): CachedRoomPreview {
    val incomingPreview = CachedRoomPreview(
        text = incomingRoom.lastMessageText,
        senderName = incomingRoom.lastMessageSenderName,
        timestampMillis = incomingRoom.lastMessageAtMillis,
        lastOwnMessageStatus = incomingRoom.lastOwnMessageStatus?.name
    )
    val existingPreview = CachedRoomPreview(
        text = existingRoom?.lastMessageText,
        senderName = existingRoom?.lastMessageSenderName,
        timestampMillis = existingRoom?.lastMessageAtMillis,
        lastOwnMessageStatus = existingRoom?.lastOwnMessageStatus
    )
    val incomingTimestamp = incomingPreview.timestampMillis ?: return existingPreview
    val existingTimestamp = existingPreview.timestampMillis ?: return incomingPreview

    return when {
        incomingTimestamp < existingTimestamp -> existingPreview
        incomingTimestamp > existingTimestamp -> incomingPreview
        else -> incomingPreview.copy(
            lastOwnMessageStatus = selectEqualTimestampOwnMessageStatus(
                incomingStatus = incomingPreview.lastOwnMessageStatus,
                existingStatus = existingPreview.lastOwnMessageStatus
            )
        )
    }
}

private fun selectEqualTimestampOwnMessageStatus(
    incomingStatus: String?,
    existingStatus: String?
): String? {
    if (incomingStatus == null || existingStatus == null) {
        return incomingStatus
    }
    return if (incomingStatus.deliveryProgressRank() >= existingStatus.deliveryProgressRank()) {
        incomingStatus
    } else {
        existingStatus
    }
}

private fun String.deliveryProgressRank(): Int {
    return when (this) {
        MatrixLastOwnMessageStatus.PENDING.name -> 0
        MatrixLastOwnMessageStatus.FAILED.name -> 1
        MatrixLastOwnMessageStatus.SENT.name -> 2
        MatrixLastOwnMessageStatus.READ.name -> 3
        else -> -1
    }
}

private inline fun <reified T : Enum<T>> String?.toCachedEnumOrDefault(default: T): T {
    return this?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: default
}

internal data class PendingResolvedRoomSummary(
    val room: MatrixRoomSummary,
    val remainingAbsentSnapshots: Int,
    val expiresAtMillis: Long = Long.MAX_VALUE,
    val removeFromCacheOnExpiry: Boolean = false
)

internal data class MergedRoomSnapshot(
    val rooms: List<MatrixRoomSummary>,
    val remainingPendingRooms: Map<String, PendingResolvedRoomSummary>
)

internal fun mergeRoomSnapshotWithPendingResolvedRooms(
    authoritativeRooms: List<MatrixRoomSummary>,
    pendingRooms: Map<String, PendingResolvedRoomSummary>
): MergedRoomSnapshot {
    val authoritativeRoomIds = authoritativeRooms.mapTo(mutableSetOf()) { it.id }
    val absentPendingRooms = pendingRooms.values.filter { pending ->
        pending.room.id !in authoritativeRoomIds && pending.remainingAbsentSnapshots > 0
    }
    val remainingPendingRooms = absentPendingRooms
        .mapNotNull { pending ->
            val remainingSnapshots = pending.remainingAbsentSnapshots - 1
            if (remainingSnapshots > 0) {
                pending.room.id to pending.copy(remainingAbsentSnapshots = remainingSnapshots)
            } else {
                null
            }
        }
        .toMap()

    return MergedRoomSnapshot(
        rooms = authoritativeRooms + absentPendingRooms.map { it.room },
        remainingPendingRooms = remainingPendingRooms
    )
}

internal fun activePendingResolvedRooms(
    pendingRooms: Map<String, PendingResolvedRoomSummary>,
    nowMillis: Long
): Map<String, PendingResolvedRoomSummary> {
    return pendingRooms.filterValues { pending -> pending.expiresAtMillis > nowMillis }
}

internal fun expiredProvisionalRoomIds(
    pendingCandidates: Map<String, PendingResolvedRoomSummary>,
    activePendingRoomIds: Set<String>
): Set<String> {
    return pendingCandidates.asSequence()
        .filter { (roomId, pending) ->
            roomId !in activePendingRoomIds && pending.removeFromCacheOnExpiry
        }
        .map { (roomId, _) -> roomId }
        .toSet()
}

private fun MatrixRoomSummary.toCachedRoomEntity(
    userId: String,
    existingRoom: CachedRoomEntity?,
    updatedAtMillis: Long
): CachedRoomEntity {
    val preview = selectRoomPreviewForCache(this, existingRoom)
    return CachedRoomEntity(
        userId = userId,
        id = id,
        displayName = displayName,
        avatarUrl = avatarUrl,
        directUserId = directUserId ?: existingRoom?.directUserId,
        isSpace = isSpace || existingRoom?.isSpace == true,
        membership = cachedMembership(
            incoming = membership,
            existing = existingRoom?.membership
        ),
        lastMessageText = preview.text,
        lastMessageSenderName = preview.senderName,
        lastMessageAtMillis = preview.timestampMillis,
        lastOwnMessageStatus = preview.lastOwnMessageStatus,
        unreadCount = unreadCount,
        unreadMentionCount = unreadMentionCount,
        isMarkedUnread = isMarkedUnread,
        listPosition = existingRoom?.listPosition,
        updatedAtMillis = updatedAtMillis,
        detailsTopic = if (roomDetails != null) roomDetails.topic else existingRoom?.detailsTopic,
        detailsJoinedMemberCount = roomDetails?.joinedMemberCount
            ?: existingRoom?.detailsJoinedMemberCount,
        detailsEncryption = roomDetails?.encryption?.name ?: existingRoom?.detailsEncryption,
        detailsAccess = roomDetails?.access?.name ?: existingRoom?.detailsAccess,
        detailsHistoryVisibility = roomDetails?.historyVisibility?.name
            ?: existingRoom?.detailsHistoryVisibility,
        detailsPinnedEventCount = roomDetails?.pinnedEventCount
            ?: existingRoom?.detailsPinnedEventCount,
        detailsCanonicalAlias = if (roomDetails != null) {
            roomDetails.canonicalAlias
        } else {
            existingRoom?.detailsCanonicalAlias
        },
        detailsRoomVersion = roomDetails?.roomVersion ?: existingRoom?.detailsRoomVersion,
        detailsCreatorSemantics = roomDetails
            ?.creatorSemantics
            ?.takeUnless { semantics -> semantics == MatrixRoomCreatorSemantics.UNKNOWN }
            ?.name
            ?: existingRoom?.detailsCreatorSemantics,
        detailsCanInviteMembers = roomDetails?.capabilities?.canInviteMembers
            ?: existingRoom?.detailsCanInviteMembers,
        detailsCanChangeName = roomDetails?.capabilities?.canChangeName
            ?: existingRoom?.detailsCanChangeName,
        detailsCanChangeTopic = roomDetails?.capabilities?.canChangeTopic
            ?: existingRoom?.detailsCanChangeTopic,
        detailsCanChangeAvatar = roomDetails?.capabilities?.canChangeAvatar
            ?: existingRoom?.detailsCanChangeAvatar,
        detailsUpdatedAtMillis = if (roomDetails != null) {
            updatedAtMillis
        } else {
            existingRoom?.detailsUpdatedAtMillis
        }
    )
}

internal fun cachedMembership(
    incoming: MatrixSpaceMembership,
    existing: String?
): String? {
    return incoming
        .takeUnless { membership -> membership == MatrixSpaceMembership.UNKNOWN }
        ?.name
        ?: existing
}

private fun CachedRoomEntity.hasSameCachedContent(existing: CachedRoomEntity): Boolean {
    return copy(
        updatedAtMillis = existing.updatedAtMillis,
        detailsUpdatedAtMillis = existing.detailsUpdatedAtMillis
    ) == existing
}

private const val ROOM_LIST_POSITION_STRIDE = 1_048_576L
private val CachedRoomListOrderComparator =
    compareBy<CachedRoomListOrder> { it.listPosition == null }
        .thenBy { it.listPosition ?: Long.MAX_VALUE }
        .thenByDescending { it.lastMessageAtMillis }
        .thenBy { it.displayName.lowercase(Locale.ROOT) }
        .thenBy(CachedRoomListOrder::id)

/**
 * Assigns order-maintenance labels while preserving the largest already ordered subsequence.
 * A one-room SDK move therefore updates one label in the common case instead of rewriting every
 * shifted row. Exhausted integer gaps trigger a rare full relabel with fresh sparse positions.
 */
internal fun assignStableRoomListPositions(
    ordered: List<CachedRoomListOrder>
): List<CachedRoomListOrder> {
    if (ordered.isEmpty()) return emptyList()
    val anchors = longestIncreasingPositionSubsequence(ordered)
    if (anchors.isEmpty()) return ordered.withFreshRoomListPositions()

    val positions = LongArray(ordered.size)
    anchors.forEach { index -> positions[index] = requireNotNull(ordered[index].listPosition) }
    try {
        val firstAnchor = anchors.first()
        for (index in firstAnchor - 1 downTo 0) {
            positions[index] = Math.subtractExact(
                positions[index + 1],
                ROOM_LIST_POSITION_STRIDE
            )
        }
        anchors.zipWithNext().forEach { (leftIndex, rightIndex) ->
            val missingCount = rightIndex - leftIndex - 1
            if (missingCount == 0) return@forEach
            val gap = Math.subtractExact(positions[rightIndex], positions[leftIndex])
            val step = gap / (missingCount + 1L)
            if (step < 1L) return ordered.withFreshRoomListPositions()
            for (offset in 1..missingCount) {
                positions[leftIndex + offset] = Math.addExact(
                    positions[leftIndex],
                    Math.multiplyExact(step, offset.toLong())
                )
            }
        }
        val lastAnchor = anchors.last()
        for (index in lastAnchor + 1..ordered.lastIndex) {
            positions[index] = Math.addExact(
                positions[index - 1],
                ROOM_LIST_POSITION_STRIDE
            )
        }
    } catch (_: ArithmeticException) {
        return ordered.withFreshRoomListPositions()
    }
    return ordered.mapIndexed { index, entity -> entity.copy(listPosition = positions[index]) }
}

private fun longestIncreasingPositionSubsequence(
    rooms: List<CachedRoomListOrder>
): List<Int> {
    val tails = LongArray(rooms.size)
    val tailRoomIndexes = IntArray(rooms.size)
    val previousRoomIndexes = IntArray(rooms.size) { -1 }
    var size = 0
    rooms.forEachIndexed { roomIndex, room ->
        val position = room.listPosition ?: return@forEachIndexed
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (tails[middle] < position) low = middle + 1 else high = middle
        }
        if (low > 0) previousRoomIndexes[roomIndex] = tailRoomIndexes[low - 1]
        tails[low] = position
        tailRoomIndexes[low] = roomIndex
        if (low == size) size += 1
    }
    if (size == 0) return emptyList()

    val result = IntArray(size)
    var roomIndex = tailRoomIndexes[size - 1]
    for (resultIndex in size - 1 downTo 0) {
        result[resultIndex] = roomIndex
        roomIndex = previousRoomIndexes[roomIndex]
    }
    return result.toList()
}

private fun List<CachedRoomListOrder>.withFreshRoomListPositions(): List<CachedRoomListOrder> {
    return mapIndexed { index, entity ->
        entity.copy(listPosition = index.toLong() * ROOM_LIST_POSITION_STRIDE)
    }
}

internal fun reconcileCachedRoomListOrder(
    liveRoomIds: List<String>,
    existing: List<CachedRoomListOrder>,
    retainedExtraRoomIds: List<String> = emptyList(),
    excludedRoomIds: Set<String> = emptySet(),
    isComplete: Boolean
): List<CachedRoomListOrder> {
    val liveIds = liveRoomIds.toHashSet()
    val retainedIds = retainedExtraRoomIds.toHashSet()
    val existingById = existing.associateBy(CachedRoomListOrder::id)
    val cachedTail = if (isComplete) {
        emptyList()
    } else {
        existing.filterNot { room ->
            room.id in liveIds || room.id in retainedIds || room.id in excludedRoomIds
        }
    }
    val orderedIds = (retainedExtraRoomIds + liveRoomIds + cachedTail.map { room -> room.id })
        .filterNot(excludedRoomIds::contains)
        .distinct()
    if (
        orderedIds.size == existing.size &&
        existing.hasStrictlyIncreasingListPositions() &&
        orderedIds.indices.all { index -> orderedIds[index] == existing[index].id }
    ) {
        return existing
    }
    return orderedIds
        .map { roomId ->
            existingById[roomId] ?: CachedRoomListOrder(id = roomId, listPosition = null)
        }
        .let(::assignStableRoomListPositions)
}

private fun List<CachedRoomListOrder>.hasStrictlyIncreasingListPositions(): Boolean {
    var previous: Long? = null
    forEach { room ->
        val position = room.listPosition ?: return false
        if (previous?.let { previousPosition -> position <= previousPosition } == true) {
            return false
        }
        previous = position
    }
    return true
}

class LocalCacheRepository(
    private val database: ZynaDatabase,
    private val context: Context
) {
    private val roomDao = database.cachedRoomDao()
    private val messageDao = database.cachedTimelineMessageDao()
    private val outgoingDao = database.outgoingEnvelopeDao()
    private val pendingReactionDao = database.pendingReactionDao()
    private val matrixRtcCallHistoryDao = database.matrixRtcCallHistoryDao()
    private val roomCacheWriteMutex = Mutex()
    private val roomListOrderByUserId =
        ConcurrentHashMap<String, List<CachedRoomListOrder>>()
    private val pendingResolvedRoomsByUserId =
        mutableMapOf<String, Map<String, PendingResolvedRoomSummary>>()

    fun observeRooms(userId: String): Flow<List<MatrixRoomSummary>> {
        return roomDao.observeRooms(userId).map { rooms ->
            rooms.sortedWith(CachedRoomComparator)
                .map { it.toRoomSummary() }
        }.flowOn(Dispatchers.Default)
    }

    fun observeRoomDetails(userId: String, roomId: String): Flow<MatrixRoomDetails?> {
        return roomDao.observeRoom(userId, roomId)
            .map { room -> room.toRoomDetailsOrNull() }
            .distinctUntilChanged()
    }

    suspend fun cacheRoomDetails(userId: String, details: MatrixRoomDetails) {
        val now = System.currentTimeMillis()
        roomCacheWriteMutex.withLock {
            database.withTransaction {
                val updated = roomDao.updateRoomDetails(
                    userId = userId,
                    roomId = details.roomId,
                    topic = details.topic,
                    joinedMemberCount = details.joinedMemberCount,
                    encryption = details.encryption.name,
                    access = details.access.name,
                    historyVisibility = details.historyVisibility.name,
                    pinnedEventCount = details.pinnedEventCount,
                    canonicalAlias = details.canonicalAlias,
                    roomVersion = details.roomVersion,
                    creatorSemantics = details.creatorSemantics
                        .takeUnless { semantics ->
                            semantics == MatrixRoomCreatorSemantics.UNKNOWN
                        }
                        ?.name,
                    canInviteMembers = details.capabilities.canInviteMembers,
                    canChangeName = details.capabilities.canChangeName,
                    canChangeTopic = details.capabilities.canChangeTopic,
                    canChangeAvatar = details.capabilities.canChangeAvatar,
                    detailsUpdatedAtMillis = now
                )
                if (updated == 0) {
                    roomDao.upsertRooms(
                        listOf(
                            CachedRoomEntity(
                                userId = userId,
                                id = details.roomId,
                                displayName = details.displayName,
                                avatarUrl = details.avatarUrl,
                                directUserId = details.directUserId,
                                isSpace = details.kind == MatrixRoomKind.SPACE,
                                lastMessageText = null,
                                lastMessageSenderName = null,
                                lastMessageAtMillis = null,
                                lastOwnMessageStatus = null,
                                unreadCount = 0,
                                unreadMentionCount = 0,
                                isMarkedUnread = false,
                                updatedAtMillis = now,
                                detailsTopic = details.topic,
                                detailsJoinedMemberCount = details.joinedMemberCount,
                                detailsEncryption = details.encryption.name,
                                detailsAccess = details.access.name,
                                detailsHistoryVisibility = details.historyVisibility.name,
                                detailsPinnedEventCount = details.pinnedEventCount,
                                detailsCanonicalAlias = details.canonicalAlias,
                                detailsRoomVersion = details.roomVersion,
                                detailsCreatorSemantics = details.creatorSemantics
                                    .takeUnless { semantics ->
                                        semantics == MatrixRoomCreatorSemantics.UNKNOWN
                                    }
                                    ?.name,
                                detailsCanInviteMembers = details.capabilities.canInviteMembers,
                                detailsCanChangeName = details.capabilities.canChangeName,
                                detailsCanChangeTopic = details.capabilities.canChangeTopic,
                                detailsCanChangeAvatar = details.capabilities.canChangeAvatar,
                                detailsUpdatedAtMillis = now
                            )
                        )
                    )
                }
            }
            invalidateRoomListOrder(userId)
        }
    }

    fun observeMatrixRtcCallHistory(
        userId: String,
        limit: Int
    ): Flow<List<MatrixRtcCallHistoryItem>> {
        return combine(
            matrixRtcCallHistoryDao.observeRecentCalls(userId, limit),
            roomDao.observeRooms(userId)
        ) { calls, rooms ->
            val roomsById = rooms.associateBy { it.id }
            calls.map { call -> call.toHistoryItem(roomsById[call.roomId]) }
        }.flowOn(Dispatchers.Default)
    }

    suspend fun cacheCreatedRoomSummary(userId: String, room: MatrixRoomSummary) {
        cacheRoomSummary(userId, room, removeFromCacheOnExpiry = true)
    }

    suspend fun cacheResolvedRoomSummary(userId: String, room: MatrixRoomSummary) {
        cacheRoomSummary(userId, room, removeFromCacheOnExpiry = false)
    }

    private suspend fun cacheRoomSummary(
        userId: String,
        room: MatrixRoomSummary,
        removeFromCacheOnExpiry: Boolean
    ) {
        val now = System.currentTimeMillis()
        roomCacheWriteMutex.withLock {
            database.withTransaction {
                val existingRoom = roomDao.roomSnapshot(userId = userId, roomId = room.id)
                val provisionalPosition = existingRoom?.listPosition
                    ?: roomDao.minimumListPosition(userId)
                        ?.let { minimum ->
                            runCatching {
                                Math.subtractExact(minimum, ROOM_LIST_POSITION_STRIDE)
                            }.getOrDefault(Long.MIN_VALUE)
                        }
                    ?: 0L
                roomDao.upsertRooms(
                    listOf(
                        room.toCachedRoomEntity(
                            userId = userId,
                            existingRoom = existingRoom,
                            updatedAtMillis = now
                        ).copy(listPosition = provisionalPosition)
                    )
                )
            }
            invalidateRoomListOrder(userId)
            val previousPending = pendingResolvedRoomsByUserId[userId].orEmpty()
            pendingResolvedRoomsByUserId[userId] = buildMap {
                put(room.id, PendingResolvedRoomSummary(
                    room = room,
                    remainingAbsentSnapshots = RESOLVED_ROOM_ABSENT_SNAPSHOT_GRACE_COUNT,
                    expiresAtMillis = now + PENDING_RESOLVED_ROOM_RETENTION_MILLIS,
                    removeFromCacheOnExpiry = removeFromCacheOnExpiry
                ))
                previousPending.forEach { (pendingRoomId, pendingRoom) ->
                    if (pendingRoomId != room.id) put(pendingRoomId, pendingRoom)
                }
            }
        }
    }

    suspend fun cacheRoomsSnapshot(
        userId: String,
        rooms: List<MatrixRoomSummary>,
        updatedRoomIds: Set<String>,
        excludedRoomIds: Set<String>,
        isComplete: Boolean
    ) {
        val now = System.currentTimeMillis()
        roomCacheWriteMutex.withLock {
            val pendingCandidates = pendingResolvedRoomsByUserId[userId]
                .orEmpty()
                .filterKeys { roomId -> roomId !in excludedRoomIds }
            val pendingRooms = activePendingResolvedRooms(
                pendingRooms = pendingCandidates,
                nowMillis = now
            )
            val expiredPendingRoomIds = expiredProvisionalRoomIds(
                pendingCandidates = pendingCandidates,
                activePendingRoomIds = pendingRooms.keys
            )
            var remainingPendingRooms: Map<String, PendingResolvedRoomSummary> = pendingRooms
            var nextOrder: List<CachedRoomListOrder>? = null
            database.withTransaction {
                val existingOrder = roomListOrderByUserId[userId]
                    ?: roomDao.roomListOrderSnapshot(userId)
                        .sortedWith(CachedRoomListOrderComparator)
                val liveRooms = rooms
                    .filterNot { room -> room.id in excludedRoomIds }
                    .distinctBy(MatrixRoomSummary::id)
                val liveRoomIds = liveRooms.map(MatrixRoomSummary::id)
                val liveRoomIdSet = liveRoomIds.toHashSet()
                val removedRoomIds = excludedRoomIds +
                    expiredPendingRoomIds.filterNot(liveRoomIdSet::contains)
                val retainedExtraSummaries = if (isComplete) {
                    val mergedSnapshot = mergeRoomSnapshotWithPendingResolvedRooms(
                        authoritativeRooms = liveRooms,
                        pendingRooms = pendingRooms
                    )
                    remainingPendingRooms = mergedSnapshot.remainingPendingRooms
                    mergedSnapshot.rooms.filterNot { room -> room.id in liveRoomIdSet }
                } else {
                    val absentPendingRooms = pendingRooms
                        .filterKeys { roomId -> roomId !in liveRoomIdSet }
                    remainingPendingRooms = absentPendingRooms
                    absentPendingRooms.values
                        .map(PendingResolvedRoomSummary::room)
                }
                val retainedExtraRoomIds = retainedExtraSummaries.map(MatrixRoomSummary::id)
                val contentSummaries = liveRooms
                    .filter { room -> room.id in updatedRoomIds } + retainedExtraSummaries
                val existingRoomsById = roomSnapshotsByIds(
                    userId = userId,
                    roomIds = contentSummaries.map(MatrixRoomSummary::id)
                ).associateBy(CachedRoomEntity::id)
                val orderedRooms = reconcileCachedRoomListOrder(
                    liveRoomIds = liveRoomIds,
                    existing = existingOrder,
                    retainedExtraRoomIds = retainedExtraRoomIds,
                    excludedRoomIds = removedRoomIds,
                    isComplete = isComplete
                )
                val positionsByRoomId = orderedRooms.associate { order ->
                    order.id to requireNotNull(order.listPosition)
                }
                val targetIds = positionsByRoomId.keys
                val deletedRoomIds = existingOrder.asSequence()
                    .map(CachedRoomListOrder::id)
                    .filter { roomId ->
                        roomId in removedRoomIds || (isComplete && roomId !in targetIds)
                    }
                    .toList()
                deletedRoomIds.chunked(ROOM_DELETE_CHUNK_SIZE).forEach { roomIds ->
                    roomDao.deleteRooms(userId, roomIds)
                }

                val contentEntities = contentSummaries.map { room ->
                    room.toCachedRoomEntity(
                        userId = userId,
                        existingRoom = existingRoomsById[room.id],
                        updatedAtMillis = now
                    ).copy(listPosition = requireNotNull(positionsByRoomId[room.id]))
                }
                val contentIds = contentEntities.mapTo(mutableSetOf(), CachedRoomEntity::id)
                val existingPositionsById = existingOrder.associate { order ->
                    order.id to order.listPosition
                }
                val positionChanges = orderedRooms.mapNotNull { order ->
                    val existingPosition = existingPositionsById[order.id]
                    val nextPosition = requireNotNull(order.listPosition)
                    if (order.id !in contentIds && existingPosition != nextPosition) {
                        CachedRoomListPositionUpdate(userId, order.id, nextPosition)
                    } else {
                        null
                    }
                }
                positionChanges.chunked(ROOM_POSITION_UPDATE_CHUNK_SIZE).forEach { changes ->
                    roomDao.updateListPositions(changes)
                }
                val changedEntities = contentEntities.filter { entity ->
                    val existing = existingRoomsById[entity.id]
                    existing == null || !entity.hasSameCachedContent(existing)
                }
                if (changedEntities.isNotEmpty()) roomDao.upsertRooms(changedEntities)

                val roomDirectnessChanged = deletedRoomIds.isNotEmpty() ||
                    contentEntities.any { entity ->
                        val existing = existingRoomsById[entity.id]
                        existing == null || existing.directUserId != entity.directUserId
                    }
                if (roomDirectnessChanged) {
                    val roomsById = roomDao.roomsSnapshot(userId).associateBy { it.id }
                    materializeMissingMatrixRtcTimelineRows(
                        userId = userId,
                        roomsById = roomsById,
                        now = now,
                        limit = CALL_TIMELINE_BACKFILL_BATCH_LIMIT
                    )
                    refreshMatrixRtcCallProjectionsForRoomState(
                        userId = userId,
                        roomsById = roomsById,
                        now = now,
                        limit = CALL_HISTORY_ROOM_REFRESH_LIMIT
                    )
                }
                nextOrder = orderedRooms
            }
            roomListOrderByUserId[userId] = requireNotNull(nextOrder)
            updatePendingResolvedRooms(userId, remainingPendingRooms)
        }
    }

    private suspend fun roomSnapshotsByIds(
        userId: String,
        roomIds: List<String>
    ): List<CachedRoomEntity> {
        return roomIds.distinct()
            .chunked(ROOM_LOOKUP_CHUNK_SIZE)
            .flatMap { ids -> roomDao.roomsSnapshotByIds(userId, ids) }
    }

    private fun updatePendingResolvedRooms(
        userId: String,
        remaining: Map<String, PendingResolvedRoomSummary>
    ) {
        if (remaining.isEmpty()) {
            pendingResolvedRoomsByUserId.remove(userId)
        } else {
            pendingResolvedRoomsByUserId[userId] = remaining
        }
    }

    /** Writers that may add a row or change explicit ordering invalidate the derived mirror. */
    private fun invalidateRoomListOrder(userId: String) {
        roomListOrderByUserId.remove(userId)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeRoomTimelineWindow(
        userId: String,
        roomId: String,
        boundsFlow: Flow<TimelineWindowBounds?>,
        initialLimit: Int
    ): Flow<List<MatrixChatMessage>> {
        return boundsFlow.flatMapLatest { bounds ->
            val oldestAnchor = bounds?.oldestAnchor
            val newestAnchor = bounds?.newestAnchor
            val messagesFlow = when {
                oldestAnchor == null -> {
                    messageDao.observeLatestRoomMessagesWindow(
                        userId = userId,
                        roomId = roomId,
                        localIdPattern = LOCAL_MESSAGE_ID_PATTERN,
                        limit = initialLimit
                    )
                }
                newestAnchor == null -> {
                    messageDao.observeRoomMessagesFrom(
                        userId = userId,
                        roomId = roomId,
                        localIdPattern = LOCAL_MESSAGE_ID_PATTERN,
                        fromTimestampMillis = oldestAnchor.timestampMillis,
                        fromId = oldestAnchor.id
                    )
                }
                else -> {
                    messageDao.observeRoomMessagesRange(
                        userId = userId,
                        roomId = roomId,
                        localIdPattern = LOCAL_MESSAGE_ID_PATTERN,
                        fromTimestampMillis = oldestAnchor.timestampMillis,
                        fromId = oldestAnchor.id,
                        toTimestampMillis = newestAnchor.timestampMillis,
                        toId = newestAnchor.id
                    )
                }
            }
            combine(
                messagesFlow,
                outgoingDao.observeActiveRoomEnvelopes(userId, roomId),
                pendingReactionDao.observeRoomPendingReactions(userId, roomId)
            ) { messages, outgoingEnvelopes, pendingReactions ->
                mergeTimelineWithOutgoing(messages, outgoingEnvelopes, bounds)
                    .applyPendingReactions(pendingReactions, userId)
            }
        }.flowOn(Dispatchers.Default)
    }

    suspend fun latestRoomTimelineWindowAnchor(
        userId: String,
        roomId: String,
        limit: Int
    ): TimelineWindowAnchor? = withContext(Dispatchers.IO) {
        latestRoomTimelineWindowEntities(
            userId = userId,
            roomId = roomId,
            limit = limit
        )
            .firstOrNull()
            ?.toTimelineWindowAnchor()
    }

    suspend fun latestRoomTimelineWindowSnapshot(
        userId: String,
        roomId: String,
        limit: Int
    ): TimelineWindowSnapshot<MatrixChatMessage> = withContext(Dispatchers.IO) {
        val totalStart = ZynaPerfLog.start()
        ZynaPerfLog.mark { "cache.latestWindow.begin roomId=$roomId limit=$limit" }
        val messagesStart = ZynaPerfLog.start()
        val messages = latestRoomTimelineWindowEntities(
            userId = userId,
            roomId = roomId,
            limit = limit
        )
        ZynaPerfLog.end(
            messagesStart,
            "cache.latestWindow.messagesQuery"
        ) {
            "roomId=$roomId count=${messages.size}"
        }
        val outgoingStart = ZynaPerfLog.start()
        val outgoingEnvelopes = outgoingDao.activeRoomEnvelopesSnapshot(userId, roomId)
        val pendingReactions = pendingReactionDao.roomPendingReactionsSnapshot(userId, roomId)
        ZynaPerfLog.end(
            outgoingStart,
            "cache.latestWindow.outgoingQuery"
        ) {
            "roomId=$roomId count=${outgoingEnvelopes.size}"
        }
        val oldestAnchor = messages.firstOrNull()?.toTimelineWindowAnchor()
        val newestAnchor = messages.lastOrNull()?.toTimelineWindowAnchor()
        val mergeStart = ZynaPerfLog.start()
        val mergedMessages = mergeTimelineWithOutgoing(
            messages = messages,
            outgoingEnvelopes = outgoingEnvelopes,
            bounds = TimelineWindowBounds(oldestAnchor = oldestAnchor)
        ).applyPendingReactions(pendingReactions, userId)
        ZynaPerfLog.end(
            mergeStart,
            "cache.latestWindow.merge"
        ) {
            "roomId=$roomId merged=${mergedMessages.size}"
        }
        val hasOlderStart = ZynaPerfLog.start()
        val hasOlder = oldestAnchor?.let { anchor ->
            hasOlderRoomTimelineMessages(
                userId = userId,
                roomId = roomId,
                anchor = anchor
            )
        } ?: false
        ZynaPerfLog.end(
            hasOlderStart,
            "cache.latestWindow.hasOlder"
        ) {
            "roomId=$roomId value=$hasOlder"
        }
        val hasNewerStart = ZynaPerfLog.start()
        val hasNewer = newestAnchor?.let { anchor ->
            hasNewerRoomTimelineMessages(
                userId = userId,
                roomId = roomId,
                anchor = anchor
            )
        } ?: false
        ZynaPerfLog.end(
            hasNewerStart,
            "cache.latestWindow.hasNewer"
        ) {
            "roomId=$roomId value=$hasNewer"
        }

        TimelineWindowSnapshot(
            anchor = oldestAnchor,
            messages = mergedMessages,
            newestAnchor = newestAnchor,
            hasOlderInDb = hasOlder,
            hasNewerInDb = hasNewer
        ).also {
            ZynaPerfLog.end(
                totalStart,
                "cache.latestWindow.total"
            ) {
                "roomId=$roomId count=${it.messages.size}"
            }
        }
    }

    suspend fun roomTimelineWindowAroundEvent(
        userId: String,
        roomId: String,
        eventId: String,
        limit: Int
    ): TimelineWindowSnapshot<MatrixChatMessage>? = withContext(Dispatchers.IO) {
        val target = messageDao.roomMessageByEventId(
            userId = userId,
            roomId = roomId,
            localIdPattern = LOCAL_MESSAGE_ID_PATTERN,
            eventId = eventId
        ) ?: return@withContext null

        val halfLimit = (limit / 2).coerceAtLeast(1)
        val atOrBeforeTarget = messageDao.roomMessagesAtOrBefore(
            userId = userId,
            roomId = roomId,
            localIdPattern = LOCAL_MESSAGE_ID_PATTERN,
            atTimestampMillis = target.timestampMillis,
            atId = target.id,
            limit = halfLimit
        )
        val afterTarget = messageDao.roomMessagesAfter(
            userId = userId,
            roomId = roomId,
            localIdPattern = LOCAL_MESSAGE_ID_PATTERN,
            afterTimestampMillis = target.timestampMillis,
            afterId = target.id,
            limit = halfLimit
        )
        val messages = (atOrBeforeTarget.asReversed() + target + afterTarget)
            .dedupeTimelineWindowEntities()
        val oldestAnchor = messages.firstOrNull()?.toTimelineWindowAnchor()
        val newestAnchor = messages.lastOrNull()?.toTimelineWindowAnchor()
        val bounds = TimelineWindowBounds(
            oldestAnchor = oldestAnchor,
            newestAnchor = newestAnchor
        )
        val outgoingEnvelopes = outgoingDao.activeRoomEnvelopesSnapshot(userId, roomId)
        val pendingReactions = pendingReactionDao.roomPendingReactionsSnapshot(userId, roomId)

        TimelineWindowSnapshot(
            anchor = oldestAnchor,
            messages = mergeTimelineWithOutgoing(
                messages = messages,
                outgoingEnvelopes = outgoingEnvelopes,
                bounds = bounds
            ).applyPendingReactions(pendingReactions, userId),
            newestAnchor = newestAnchor,
            hasOlderInDb = oldestAnchor?.let { anchor ->
                hasOlderRoomTimelineMessages(
                    userId = userId,
                    roomId = roomId,
                    anchor = anchor
                )
            } ?: false,
            hasNewerInDb = newestAnchor?.let { anchor ->
                hasNewerRoomTimelineMessages(
                    userId = userId,
                    roomId = roomId,
                    anchor = anchor
                )
            } ?: false
        )
    }

    suspend fun olderRoomTimelineWindowAnchor(
        userId: String,
        roomId: String,
        anchor: TimelineWindowAnchor,
        limit: Int
    ): TimelineWindowAnchor? = withContext(Dispatchers.IO) {
        messageDao.roomMessagesBefore(
            userId = userId,
            roomId = roomId,
            localIdPattern = LOCAL_MESSAGE_ID_PATTERN,
            beforeTimestampMillis = anchor.timestampMillis,
            beforeId = anchor.id,
            limit = limit
        )
            .lastOrNull()
            ?.toTimelineWindowAnchor()
    }

    suspend fun newerRoomTimelineWindowAnchor(
        userId: String,
        roomId: String,
        anchor: TimelineWindowAnchor,
        limit: Int
    ): TimelineWindowAnchor? = withContext(Dispatchers.IO) {
        messageDao.roomMessagesAfter(
            userId = userId,
            roomId = roomId,
            localIdPattern = LOCAL_MESSAGE_ID_PATTERN,
            afterTimestampMillis = anchor.timestampMillis,
            afterId = anchor.id,
            limit = limit
        )
            .lastOrNull()
            ?.toTimelineWindowAnchor()
    }

    suspend fun hasOlderRoomTimelineMessages(
        userId: String,
        roomId: String,
        anchor: TimelineWindowAnchor
    ): Boolean = withContext(Dispatchers.IO) {
        messageDao.hasRoomMessagesBefore(
            userId = userId,
            roomId = roomId,
            localIdPattern = LOCAL_MESSAGE_ID_PATTERN,
            beforeTimestampMillis = anchor.timestampMillis,
            beforeId = anchor.id
        )
    }

    suspend fun hasNewerRoomTimelineMessages(
        userId: String,
        roomId: String,
        anchor: TimelineWindowAnchor
    ): Boolean = withContext(Dispatchers.IO) {
        messageDao.hasRoomMessagesAfter(
            userId = userId,
            roomId = roomId,
            localIdPattern = LOCAL_MESSAGE_ID_PATTERN,
            afterTimestampMillis = anchor.timestampMillis,
            afterId = anchor.id
        )
    }

    private suspend fun latestRoomTimelineWindowEntities(
        userId: String,
        roomId: String,
        limit: Int
    ): List<CachedTimelineMessageEntity> {
        return messageDao.latestRoomMessagesWindow(
            userId = userId,
            roomId = roomId,
            localIdPattern = LOCAL_MESSAGE_ID_PATTERN,
            limit = limit
        )
    }

    suspend fun cacheRoomTimelineMessages(
        userId: String,
        roomId: String,
        messages: List<MatrixChatMessage>
    ) {
        val now = System.currentTimeMillis()
        if (messages.isEmpty()) {
            return
        }

        database.withTransaction {
            val incomingEntities = messages.toEntities(userId, roomId, now)
            val existingMessagesByIdentity = existingMessagesMatching(
                userId = userId,
                roomId = roomId,
                incomingMessages = incomingEntities
            )
                .cachedMessagesByIdentity()
            val hasIncomingReplies = incomingEntities.any { it.replyEventId != null }
            val existingReplyInfosByIdentity = if (hasIncomingReplies) {
                existingMessagesByIdentity.values
                    .distinctBy { it.id }
                    .cachedReplyInfosByIdentity()
            } else {
                emptyMap()
            }
            val outgoingReplyInfosByIdentity = if (hasIncomingReplies) {
                outgoingDao.activeRoomEnvelopesSnapshot(userId, roomId)
                    .outgoingReplyInfosByIdentity()
            } else {
                emptyMap()
            }
            val replyTargetInfosByIdentity = if (hasIncomingReplies) {
                replyTargetsMatching(
                    userId = userId,
                    roomId = roomId,
                    incomingMessages = incomingEntities
                )
                    .replyTargetInfosByIdentity() + incomingEntities.replyTargetInfosByIdentity()
            } else {
                emptyMap()
            }
            val existingRedactionsByIdentity = existingMessagesByIdentity.values
                .distinctBy { it.id }
                .redactionsByIdentity()
            val entities = incomingEntities
                .preserveExistingTimelineState(existingMessagesByIdentity)
                .enrichReplyInfos(
                    existingReplyInfosByIdentity = existingReplyInfosByIdentity,
                    outgoingReplyInfosByIdentity = outgoingReplyInfosByIdentity,
                    replyTargetInfosByIdentity = replyTargetInfosByIdentity
                )
                .preserveExistingRedactions(existingRedactionsByIdentity)
            messageDao.upsertMessages(entities)
            deleteSafeEventDuplicates(entities)
            retireOutgoingEnvelopesDeliveredBy(messages, userId, roomId, now)
            updateRoomPreview(userId, roomId, now)
        }
    }

    private enum class MatrixRtcNotificationSource {
        TIMELINE,
        SPARSE_INCOMING_PUSH
    }

    suspend fun cacheMatrixRtcCallTimelineEvents(
        userId: String,
        notifications: List<MatrixRtcCallTimelineNotification>,
        memberships: List<MatrixRtcCallTimelineMembership>
    ) {
        cacheMatrixRtcCallTimelineEvents(
            userId = userId,
            notifications = notifications,
            memberships = memberships,
            notificationSource = MatrixRtcNotificationSource.TIMELINE
        )
    }

    private suspend fun cacheMatrixRtcCallTimelineEvents(
        userId: String,
        notifications: List<MatrixRtcCallTimelineNotification>,
        memberships: List<MatrixRtcCallTimelineMembership>,
        notificationSource: MatrixRtcNotificationSource
    ) {
        if (notifications.isEmpty() && memberships.isEmpty()) {
            return
        }

        val now = System.currentTimeMillis()
        database.withTransaction {
            val roomsById = roomDao.roomsSnapshot(userId).associateBy { it.id }
            val callEntities = notifications.map { notification ->
                notification.toEntity(
                    userId = userId,
                    isDirect = roomsById[notification.roomId].isDirectRoom(),
                    now = now
                )
            }
            val membershipEntities = memberships.map { membership ->
                membership.toEntity(userId = userId, now = now)
            }

            val persistedCallEntities = when (notificationSource) {
                MatrixRtcNotificationSource.TIMELINE -> {
                    if (callEntities.isNotEmpty()) {
                        matrixRtcCallHistoryDao.upsertCalls(callEntities)
                    }
                    callEntities
                }

                MatrixRtcNotificationSource.SPARSE_INCOMING_PUSH -> callEntities.map { call ->
                    val insertedRowId = matrixRtcCallHistoryDao.insertCallIfAbsent(call)
                    if (insertedRowId != -1L) {
                        call
                    } else {
                        requireNotNull(
                            matrixRtcCallHistoryDao.callSnapshot(
                                userId = userId,
                                eventId = call.eventId
                            )
                        )
                    }
                }
            }

            if (
                persistedCallEntities.isNotEmpty() &&
                notificationSource == MatrixRtcNotificationSource.TIMELINE
            ) {
                val timelineCallEntities = notifications.map { notification ->
                    notification.toMatrixChatMessage(
                        outcome = MatrixRtcCallHistoryOutcome.STARTED
                    ).toEntity(
                        userId = userId,
                        roomId = notification.roomId,
                        timelineIndex = 0,
                        updatedAtMillis = now
                    )
                }
                messageDao.upsertMessages(timelineCallEntities)
                deleteSafeEventDuplicates(timelineCallEntities)
            }
            if (membershipEntities.isNotEmpty()) {
                matrixRtcCallHistoryDao.upsertMemberships(membershipEntities)
            }

            val affectedCallsByEventId = LinkedHashMap<String, MatrixRtcCallEntity>()
            persistedCallEntities.forEach { call ->
                affectedCallsByEventId[call.eventId] = call
            }
            membershipEntities.forEach { membership ->
                matrixRtcCallHistoryDao.callsInRoomWindow(
                    userId = userId,
                    roomId = membership.roomId,
                    fromTimestampMillis =
                        MatrixRtcCallHistoryProjection.affectedCallLowerBound(
                            membership.timestampMillis
                        ),
                    toTimestampMillis =
                        MatrixRtcCallHistoryProjection.affectedCallUpperBound(
                            membership.timestampMillis
                        )
                ).forEach { call ->
                    affectedCallsByEventId[call.eventId] = call
                }
            }
            matrixRtcCallHistoryDao.expiredStartedRingCalls(
                userId = userId,
                nowMillis = now,
                limit = CALL_HISTORY_EXPIRED_REFRESH_LIMIT
            ).forEach { call ->
                affectedCallsByEventId[call.eventId] = call
            }

            val timelineNotificationEventIds = if (
                notificationSource == MatrixRtcNotificationSource.TIMELINE
            ) {
                persistedCallEntities.mapTo(mutableSetOf()) { it.eventId }
            } else {
                emptySet()
            }
            affectedCallsByEventId.values.forEach { call ->
                refreshMatrixRtcCallProjection(
                    userId = userId,
                    call = call,
                    roomsById = roomsById,
                    now = now,
                    ensureTimelineRow = call.eventId !in timelineNotificationEventIds
                )
            }
        }
    }

    suspend fun cacheIncomingMatrixRtcCallNotification(
        userId: String,
        notification: MatrixIncomingRtcCallNotification
    ) {
        cacheMatrixRtcCallTimelineEvents(
            userId = userId,
            notifications = listOf(notification.toTimelineNotification()),
            memberships = emptyList(),
            notificationSource = MatrixRtcNotificationSource.SPARSE_INCOMING_PUSH
        )
    }

    suspend fun refreshMatrixRtcCallHistory(userId: String, limit: Int) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            val roomsById = roomDao.roomsSnapshot(userId).associateBy { it.id }
            materializeMissingMatrixRtcTimelineRows(
                userId = userId,
                roomsById = roomsById,
                now = now,
                limit = limit
            )
            refreshMatrixRtcCallProjectionsForRoomState(
                userId = userId,
                roomsById = roomsById,
                now = now,
                limit = limit
            )
        }
    }

    suspend fun createOutgoingTextEnvelope(
        userId: String,
        roomId: String,
        envelopeId: String,
        transactionId: String,
        body: String,
        replyInfo: MatrixReplyInfo?,
        forwardedFrom: String?
    ) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            outgoingDao.upsertEnvelope(
                OutgoingEnvelopeEntity(
                    userId = userId,
                    roomId = roomId,
                    id = envelopeId,
                    kind = OutgoingEnvelopeKind.TEXT.name,
                    transportState = OutgoingTransportState.QUEUED.name,
                    transactionId = transactionId,
                    eventId = null,
                    targetEventId = null,
                    targetTransactionId = null,
                    targetBody = null,
                    targetContentType = null,
                    replyEventId = replyInfo?.eventId,
                    replySenderId = replyInfo?.senderId,
                    replySenderDisplayName = replyInfo?.senderDisplayName,
                    replyBody = replyInfo?.body,
                    forwardedFrom = forwardedFrom,
                    imageLocalPath = null,
                    imageMimeType = null,
                    imageWidth = null,
                    imageHeight = null,
                    imageSizeBytes = null,
                    imageThumbnailLocalPath = null,
                    imageThumbnailMimeType = null,
                    imageThumbnailWidth = null,
                    imageThumbnailHeight = null,
                    imageThumbnailSizeBytes = null,
                    imageCaption = null,
                    zynaAttributesJson = null,
                    imageSourceJson = null,
                    imageThumbnailSourceJson = null,
                    imageBlurhash = null,
                    imageUploadedJson = null,
                    imageUploadedAtMillis = null,
                    body = body,
                    createdAtMillis = now,
                    updatedAtMillis = now,
                    failureMessage = null
                )
            )
            updateRoomPreview(userId, roomId, now)
        }
    }

    suspend fun createOutgoingImageEnvelope(
        userId: String,
        roomId: String,
        envelopeId: String,
        transactionId: String,
        localPath: String,
        mimeType: String,
        width: Int,
        height: Int,
        sizeBytes: Long,
        thumbnailLocalPath: String?,
        thumbnailMimeType: String?,
        thumbnailWidth: Int?,
        thumbnailHeight: Int?,
        thumbnailSizeBytes: Long?,
        blurhash: String?,
        caption: String?,
        zynaAttributes: ZynaMessageAttributes
    ) {
        val now = System.currentTimeMillis()
        val normalizedCaption = caption.normalizedMessageCaption()
        database.withTransaction {
            outgoingDao.upsertEnvelope(
                OutgoingEnvelopeEntity(
                    userId = userId,
                    roomId = roomId,
                    id = envelopeId,
                    kind = OutgoingEnvelopeKind.IMAGE.name,
                    transportState = OutgoingTransportState.QUEUED.name,
                    transactionId = transactionId,
                    eventId = null,
                    targetEventId = null,
                    targetTransactionId = null,
                    targetBody = null,
                    targetContentType = null,
                    replyEventId = null,
                    replySenderId = null,
                    replySenderDisplayName = null,
                    replyBody = null,
                    forwardedFrom = null,
                    imageLocalPath = localPath,
                    imageMimeType = mimeType,
                    imageWidth = width,
                    imageHeight = height,
                    imageSizeBytes = sizeBytes,
                    imageThumbnailLocalPath = thumbnailLocalPath,
                    imageThumbnailMimeType = thumbnailMimeType,
                    imageThumbnailWidth = thumbnailWidth,
                    imageThumbnailHeight = thumbnailHeight,
                    imageThumbnailSizeBytes = thumbnailSizeBytes,
                    imageCaption = normalizedCaption,
                    zynaAttributesJson = ZynaHtmlCodec.encodeAttributesJson(zynaAttributes),
                    imageSourceJson = null,
                    imageThumbnailSourceJson = null,
                    imageBlurhash = blurhash,
                    imageUploadedJson = null,
                    imageUploadedAtMillis = null,
                    body = normalizedCaption ?: "Photo",
                    createdAtMillis = now,
                    updatedAtMillis = now,
                    failureMessage = null
                )
            )
            updateRoomPreview(userId, roomId, now)
        }
    }

    suspend fun createOutgoingForwardedImageEnvelope(
        userId: String,
        roomId: String,
        envelopeId: String,
        transactionId: String,
        image: MatrixForwardImageItem,
        caption: String?,
        zynaAttributes: ZynaMessageAttributes
    ) {
        val now = System.currentTimeMillis()
        val normalizedCaption = caption.normalizedMessageCaption()
        database.withTransaction {
            outgoingDao.upsertEnvelope(
                OutgoingEnvelopeEntity(
                    userId = userId,
                    roomId = roomId,
                    id = envelopeId,
                    kind = OutgoingEnvelopeKind.IMAGE.name,
                    transportState = OutgoingTransportState.QUEUED.name,
                    transactionId = transactionId,
                    eventId = null,
                    targetEventId = null,
                    targetTransactionId = null,
                    targetBody = null,
                    targetContentType = null,
                    replyEventId = null,
                    replySenderId = null,
                    replySenderDisplayName = null,
                    replyBody = null,
                    forwardedFrom = null,
                    imageLocalPath = null,
                    imageMimeType = image.mimeType,
                    imageWidth = image.width,
                    imageHeight = image.height,
                    imageSizeBytes = null,
                    imageThumbnailLocalPath = null,
                    imageThumbnailMimeType = null,
                    imageThumbnailWidth = null,
                    imageThumbnailHeight = null,
                    imageThumbnailSizeBytes = null,
                    imageCaption = normalizedCaption,
                    zynaAttributesJson = ZynaHtmlCodec.encodeAttributesJson(zynaAttributes),
                    imageSourceJson = image.sourceJson,
                    imageThumbnailSourceJson = image.thumbnailSourceJson,
                    imageBlurhash = image.blurhash,
                    imageUploadedJson = null,
                    imageUploadedAtMillis = null,
                    body = normalizedCaption ?: "Photo",
                    createdAtMillis = now,
                    updatedAtMillis = now,
                    failureMessage = null
                )
            )
            updateRoomPreview(userId, roomId, now)
        }
    }

    suspend fun createOutgoingVoiceEnvelope(
        userId: String,
        roomId: String,
        envelopeId: String,
        transactionId: String,
        localPath: String,
        mimeType: String,
        sizeBytes: Long,
        durationMillis: Long,
        waveform: List<Float>,
        replyInfo: MatrixReplyInfo?
    ) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            outgoingDao.upsertEnvelope(
                OutgoingEnvelopeEntity(
                    userId = userId,
                    roomId = roomId,
                    id = envelopeId,
                    kind = OutgoingEnvelopeKind.VOICE.name,
                    transportState = OutgoingTransportState.QUEUED.name,
                    transactionId = transactionId,
                    eventId = null,
                    targetEventId = null,
                    targetTransactionId = null,
                    targetBody = null,
                    targetContentType = null,
                    replyEventId = replyInfo?.eventId,
                    replySenderId = replyInfo?.senderId,
                    replySenderDisplayName = replyInfo?.senderDisplayName,
                    replyBody = replyInfo?.body,
                    forwardedFrom = null,
                    imageLocalPath = null,
                    imageMimeType = null,
                    imageWidth = null,
                    imageHeight = null,
                    imageSizeBytes = null,
                    imageThumbnailLocalPath = null,
                    imageThumbnailMimeType = null,
                    imageThumbnailWidth = null,
                    imageThumbnailHeight = null,
                    imageThumbnailSizeBytes = null,
                    imageCaption = null,
                    zynaAttributesJson = null,
                    imageSourceJson = null,
                    imageThumbnailSourceJson = null,
                    imageBlurhash = null,
                    imageUploadedJson = null,
                    imageUploadedAtMillis = null,
                    body = VOICE_MESSAGE_BODY,
                    createdAtMillis = now,
                    updatedAtMillis = now,
                    failureMessage = null,
                    voiceLocalPath = localPath,
                    voiceMimeType = mimeType,
                    voiceSizeBytes = sizeBytes,
                    voiceDurationMillis = durationMillis,
                    voiceWaveform = waveform.toWaveformCacheString(),
                    voiceUploadedJson = null,
                    voiceUploadedAtMillis = null
                )
            )
            updateRoomPreview(userId, roomId, now)
        }
    }

    suspend fun createOutgoingRedactionEnvelope(
        userId: String,
        roomId: String,
        envelopeId: String,
        transactionId: String,
        targetMessage: MatrixChatMessage
    ): Boolean {
        val targetEventId = targetMessage.eventId ?: return false
        val now = System.currentTimeMillis()
        database.withTransaction {
            outgoingDao.upsertEnvelope(
                OutgoingEnvelopeEntity(
                    userId = userId,
                    roomId = roomId,
                    id = envelopeId,
                    kind = OutgoingEnvelopeKind.REDACTION.name,
                    transportState = OutgoingTransportState.QUEUED.name,
                    transactionId = transactionId,
                    eventId = null,
                    targetEventId = targetEventId,
                    targetTransactionId = targetMessage.transactionId,
                    targetBody = targetMessage.body,
                    targetContentType = targetMessage.contentType.name,
                    replyEventId = null,
                    replySenderId = null,
                    replySenderDisplayName = null,
                    replyBody = null,
                    forwardedFrom = null,
                    imageLocalPath = null,
                    imageMimeType = null,
                    imageWidth = null,
                    imageHeight = null,
                    imageSizeBytes = null,
                    imageThumbnailLocalPath = null,
                    imageThumbnailMimeType = null,
                    imageThumbnailWidth = null,
                    imageThumbnailHeight = null,
                    imageThumbnailSizeBytes = null,
                    imageCaption = null,
                    zynaAttributesJson = null,
                    imageSourceJson = null,
                    imageThumbnailSourceJson = null,
                    imageBlurhash = null,
                    imageUploadedJson = null,
                    imageUploadedAtMillis = null,
                    body = "",
                    createdAtMillis = now,
                    updatedAtMillis = now,
                    failureMessage = null
                )
            )
            markCachedMessageRedacted(
                userId = userId,
                roomId = roomId,
                ids = targetMessage.identityIds(),
                updatedAtMillis = now
            )
            updateRoomPreview(userId, roomId, now)
        }
        return true
    }

    suspend fun prepareOutgoingTextEdit(
        userId: String,
        roomId: String,
        targetMessage: MatrixChatMessage,
        body: String,
        transactionId: String
    ): Boolean {
        val eventId = targetMessage.eventId ?: return false
        val trimmedBody = body.trim()
        if (
            trimmedBody.isEmpty() ||
            trimmedBody == targetMessage.body.trim() ||
            targetMessage.contentType != MatrixMessageContentType.TEXT ||
            !targetMessage.isOwn ||
            targetMessage.isEditPending
        ) {
            return false
        }

        val now = System.currentTimeMillis()
        return database.withTransaction {
            val didPrepare = messageDao.preparePendingTextEdit(
                userId = userId,
                roomId = roomId,
                eventId = eventId,
                editTransactionId = transactionId,
                pendingEditBody = trimmedBody,
                updatedAtMillis = now
            ) > 0
            if (didPrepare) {
                updateRoomPreview(userId, roomId, now)
            }
            didPrepare
        }
    }

    suspend fun markOutgoingDispatchStarted(userId: String, roomId: String, envelopeId: String) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            outgoingDao.markDispatchStarted(
                userId = userId,
                roomId = roomId,
                id = envelopeId,
                updatedAtMillis = now
            )
            updateRoomPreview(userId, roomId, now)
        }
    }

    suspend fun markOutgoingDispatchRetrying(
        userId: String,
        roomId: String,
        envelopeId: String,
        failureMessage: String?
    ) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            outgoingDao.markDispatchRetrying(
                userId = userId,
                roomId = roomId,
                id = envelopeId,
                failureMessage = failureMessage,
                updatedAtMillis = now
            )
            updateRoomPreview(userId, roomId, now)
        }
    }

    suspend fun markOutgoingImageUploadAccepted(
        userId: String,
        roomId: String,
        envelopeId: String,
        uploadedImageJson: String
    ): Boolean {
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val didUpdate = outgoingDao.markImageUploadAccepted(
                userId = userId,
                roomId = roomId,
                id = envelopeId,
                uploadedImageJson = uploadedImageJson,
                updatedAtMillis = now
            ) > 0
            if (didUpdate) {
                updateRoomPreview(userId, roomId, now)
            }
            didUpdate
        }
    }

    suspend fun markOutgoingVoiceUploadAccepted(
        userId: String,
        roomId: String,
        envelopeId: String,
        uploadedVoiceJson: String
    ): Boolean {
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val didUpdate = outgoingDao.markVoiceUploadAccepted(
                userId = userId,
                roomId = roomId,
                id = envelopeId,
                uploadedVoiceJson = uploadedVoiceJson,
                updatedAtMillis = now
            ) > 0
            if (didUpdate) {
                updateRoomPreview(userId, roomId, now)
            }
            didUpdate
        }
    }

    suspend fun markOutgoingDispatchAccepted(
        userId: String,
        roomId: String,
        envelopeId: String,
        eventId: String
    ) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            outgoingDao.markDispatchAccepted(
                userId = userId,
                roomId = roomId,
                id = envelopeId,
                eventId = eventId,
                updatedAtMillis = now
            )
            if (messageDao.hasMessage(userId, roomId, eventId)) {
                retireOutgoingEnvelopesByEventIds(userId, roomId, listOf(eventId), now)
            }
            updateRoomPreview(userId, roomId, now)
        }
    }

    suspend fun markOutgoingDispatchFailed(
        userId: String,
        roomId: String,
        envelopeId: String,
        failureMessage: String?
    ) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            outgoingDao.markDispatchFailed(
                userId = userId,
                roomId = roomId,
                id = envelopeId,
                failureMessage = failureMessage,
                updatedAtMillis = now
            )
            updateRoomPreview(userId, roomId, now)
        }
    }

    suspend fun retryFailedOutgoingMessageEnvelope(
        userId: String,
        roomId: String,
        envelopeId: String
    ): Boolean {
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val didRetry = outgoingDao.markFailedMessageEnvelopeQueued(
                userId = userId,
                roomId = roomId,
                id = envelopeId,
                updatedAtMillis = now
            ) > 0
            if (didRetry) {
                updateRoomPreview(userId, roomId, now)
            }
            didRetry
        }
    }

    suspend fun debugMarkOutgoingMessageEnvelopeFailed(
        userId: String,
        roomId: String,
        envelopeId: String
    ): Boolean {
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val didMark = outgoingDao.debugMarkActiveMessageEnvelopeFailed(
                userId = userId,
                roomId = roomId,
                id = envelopeId,
                failureMessage = "Debug forced send failure",
                updatedAtMillis = now
            ) > 0
            if (didMark) {
                updateRoomPreview(userId, roomId, now)
            }
            didMark
        }
    }

    suspend fun discardFailedOutgoingMessageEnvelope(
        userId: String,
        roomId: String,
        envelopeId: String
    ): Boolean {
        return database.withTransaction {
            val envelope = outgoingDao.failedMessageEnvelope(
                userId = userId,
                roomId = roomId,
                id = envelopeId
            )
            val didDelete = outgoingDao.deleteFailedMessageEnvelope(
                userId = userId,
                roomId = roomId,
                id = envelopeId
            ) > 0
            if (didDelete && envelope != null) {
                val hiddenIds = listOfNotNull(envelope.transactionId, envelope.eventId)
                if (hiddenIds.isNotEmpty()) {
                    messageDao.deleteMessagesByIds(userId, roomId, hiddenIds)
                }
                deleteLocalFiles(
                    listOfNotNull(
                        envelope.imageLocalPath,
                        envelope.imageThumbnailLocalPath,
                        envelope.voiceLocalPath
                    )
                )
                updateRoomPreview(userId, roomId, System.currentTimeMillis())
            }
            didDelete
        }
    }

    suspend fun outgoingTextDispatchCandidates(
        userId: String,
        envelopeIds: Set<String>? = null
    ): List<OutgoingTextEnvelope> {
        val entities = if (envelopeIds == null) {
            outgoingDao.textDispatchCandidates(userId)
        } else {
            envelopeIds.mapNotNull { id -> outgoingDao.textDispatchCandidate(userId, id) }
        }
        return entities.mapNotNull { it.toOutgoingTextEnvelopeOrNull() }
    }

    suspend fun outgoingImageDispatchCandidates(
        userId: String,
        envelopeIds: Set<String>? = null
    ): List<OutgoingImageEnvelope> {
        val entities = if (envelopeIds == null) {
            outgoingDao.imageDispatchCandidates(userId)
        } else {
            envelopeIds.mapNotNull { id -> outgoingDao.imageDispatchCandidate(userId, id) }
        }
        return entities.mapNotNull { it.toOutgoingImageEnvelopeOrNull() }
    }

    suspend fun outgoingVoiceDispatchCandidates(
        userId: String,
        envelopeIds: Set<String>? = null
    ): List<OutgoingVoiceEnvelope> {
        val entities = if (envelopeIds == null) {
            outgoingDao.voiceDispatchCandidates(userId)
        } else {
            envelopeIds.mapNotNull { id -> outgoingDao.voiceDispatchCandidate(userId, id) }
        }
        return entities.mapNotNull { it.toOutgoingVoiceEnvelopeOrNull() }
    }

    suspend fun outgoingRedactionDispatchCandidates(
        userId: String,
        envelopeIds: Set<String>? = null
    ): List<OutgoingRedactionEnvelope> {
        val entities = if (envelopeIds == null) {
            outgoingDao.redactionDispatchCandidates(userId)
        } else {
            envelopeIds.mapNotNull { id -> outgoingDao.redactionDispatchCandidate(userId, id) }
        }
        return entities.mapNotNull { it.toOutgoingRedactionEnvelopeOrNull() }
    }

    suspend fun outgoingEditDispatchCandidates(userId: String): List<OutgoingEditEnvelope> {
        return messageDao.pendingTextEdits(userId)
            .mapNotNull { it.toOutgoingEditEnvelopeOrNull() }
    }

    suspend fun prepareOutgoingReactionAdd(
        userId: String,
        roomId: String,
        targetEventId: String,
        reactionKey: String,
        transactionId: String
    ): String? {
        val normalizedTarget = targetEventId.takeIf { it.isNotBlank() } ?: return null
        val normalizedKey = reactionKey.takeIf { it.isNotBlank() } ?: return null
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val existing = latestRetainedReaction(
                userId = userId,
                roomId = roomId,
                targetEventId = normalizedTarget,
                reactionKey = normalizedKey,
                nowMillis = now
            )
            val next = when (existing?.decodedState()) {
                PendingReactionState.ADD_QUEUED -> existing.copy(
                    transactionId = existing.transactionId ?: transactionId,
                    redactionTransactionId = null,
                    redactionEventId = null,
                    failureMessage = null,
                    updatedAtMillis = now
                )
                PendingReactionState.ADD_ACCEPTED -> existing.copy(
                    failureMessage = null,
                    updatedAtMillis = now
                )
                PendingReactionState.REMOVE_QUEUED -> {
                    val isRemoveAfterPendingAdd = existing.reactionEventId.isNullOrBlank()
                    when {
                        isRemoveAfterPendingAdd -> existing.copy(
                            state = PendingReactionState.ADD_QUEUED.name,
                            transactionId = existing.transactionId ?: transactionId,
                            redactionTransactionId = null,
                            redactionEventId = null,
                            failureMessage = null,
                            updatedAtMillis = now
                        )
                        !existing.hasAttemptStarted() -> existing.copy(
                            state = PendingReactionState.ADD_ACCEPTED.name,
                            redactionTransactionId = null,
                            redactionEventId = null,
                            failureMessage = null,
                            updatedAtMillis = now
                        )
                        else -> existing.copy(
                            state = PendingReactionState.ADD_AFTER_REMOVE_QUEUED.name,
                            transactionId = transactionId,
                            failureMessage = null,
                            updatedAtMillis = now
                        )
                    }
                }
                PendingReactionState.ADD_AFTER_REMOVE_QUEUED -> existing.copy(
                    failureMessage = null,
                    updatedAtMillis = now
                )
                PendingReactionState.REMOVED,
                PendingReactionState.FAILED,
                null -> PendingReactionEntity(
                    userId = userId,
                    roomId = roomId,
                    id = existing?.id ?: "reaction:${UUID.randomUUID()}",
                    targetEventId = normalizedTarget,
                    reactionKey = normalizedKey,
                    state = PendingReactionState.ADD_QUEUED.name,
                    transactionId = transactionId,
                    reactionEventId = null,
                    redactionTransactionId = null,
                    redactionEventId = null,
                    createdAtMillis = existing?.createdAtMillis ?: now,
                    updatedAtMillis = now,
                    failureMessage = null,
                    lastAttemptAtMillis = null,
                    attemptCount = 0
                )
            }
            pendingReactionDao.upsertReaction(next)
            next.id.takeIf { next.decodedState().isReactionOutboxState() }
        }
    }

    suspend fun prepareOutgoingReactionRemoval(
        userId: String,
        roomId: String,
        targetEventId: String,
        reactionKey: String,
        reactionEventId: String?,
        transactionId: String
    ): String? {
        val normalizedTarget = targetEventId.takeIf { it.isNotBlank() } ?: return null
        val normalizedKey = reactionKey.takeIf { it.isNotBlank() } ?: return null
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val existing = latestRetainedReaction(
                userId = userId,
                roomId = roomId,
                targetEventId = normalizedTarget,
                reactionKey = normalizedKey,
                nowMillis = now
            )

            val resolvedReactionEventId = existing?.reactionEventId?.takeIf { it.isNotBlank() }
                ?: reactionEventId?.takeIf { it.isNotBlank() }
            val next = when (existing?.decodedState()) {
                PendingReactionState.ADD_QUEUED -> {
                    if (existing.reactionEventId.isNullOrBlank() && !existing.hasAttemptStarted()) {
                        existing.copy(
                            state = PendingReactionState.REMOVED.name,
                            transactionId = null,
                            redactionTransactionId = null,
                            redactionEventId = null,
                            failureMessage = null,
                            updatedAtMillis = now
                        )
                    } else {
                        existing.copy(
                            state = PendingReactionState.REMOVE_QUEUED.name,
                            reactionEventId = resolvedReactionEventId,
                            redactionTransactionId = transactionId,
                            redactionEventId = null,
                            failureMessage = null,
                            updatedAtMillis = now
                        )
                    }
                }
                PendingReactionState.ADD_ACCEPTED -> {
                    resolvedReactionEventId ?: return@withTransaction null
                    existing.copy(
                        state = PendingReactionState.REMOVE_QUEUED.name,
                        transactionId = null,
                        reactionEventId = resolvedReactionEventId,
                        redactionTransactionId = transactionId,
                        redactionEventId = null,
                        failureMessage = null,
                        updatedAtMillis = now
                    )
                }
                PendingReactionState.REMOVE_QUEUED -> existing.copy(
                    state = PendingReactionState.REMOVE_QUEUED.name,
                    reactionEventId = resolvedReactionEventId,
                    redactionTransactionId = existing.redactionTransactionId ?: transactionId,
                    redactionEventId = null,
                    failureMessage = null,
                    updatedAtMillis = now
                )
                PendingReactionState.ADD_AFTER_REMOVE_QUEUED -> existing.copy(
                    state = PendingReactionState.REMOVE_QUEUED.name,
                    transactionId = null,
                    reactionEventId = resolvedReactionEventId,
                    redactionEventId = null,
                    failureMessage = null,
                    updatedAtMillis = now
                )
                PendingReactionState.REMOVED -> return@withTransaction null
                PendingReactionState.FAILED,
                null -> {
                    resolvedReactionEventId ?: return@withTransaction null
                    PendingReactionEntity(
                        userId = userId,
                        roomId = roomId,
                        id = existing?.id ?: "reaction:${UUID.randomUUID()}",
                        targetEventId = normalizedTarget,
                        reactionKey = normalizedKey,
                        state = PendingReactionState.REMOVE_QUEUED.name,
                        transactionId = null,
                        reactionEventId = resolvedReactionEventId,
                        redactionTransactionId = transactionId,
                        redactionEventId = null,
                        createdAtMillis = existing?.createdAtMillis ?: now,
                        updatedAtMillis = now,
                        failureMessage = null,
                        lastAttemptAtMillis = null,
                        attemptCount = 0
                    )
                }
            }
            pendingReactionDao.upsertReaction(next)
            next.id.takeIf { next.decodedState().isReactionOutboxState() }
        }
    }

    suspend fun outgoingReactionDispatchCandidates(
        userId: String,
        reactionIds: Set<String>? = null
    ): List<OutgoingReactionEnvelope> {
        val entities = if (reactionIds == null) {
            pendingReactionDao.outboxCandidates(userId)
        } else {
            reactionIds.mapNotNull { id -> pendingReactionDao.outboxCandidate(userId, id) }
        }
        return entities.mapNotNull { it.toOutgoingReactionEnvelopeOrNull() }
    }

    suspend fun markOutgoingReactionAttemptStarted(candidate: OutgoingReactionEnvelope) {
        val now = System.currentTimeMillis()
        pendingReactionDao.markAttemptStarted(
            userId = candidate.userId,
            roomId = candidate.roomId,
            id = candidate.id,
            lastAttemptAtMillis = now,
            updatedAtMillis = now
        )
    }

    suspend fun markOutgoingReactionAddAccepted(
        candidate: OutgoingReactionEnvelope,
        reactionEventId: String
    ): OutgoingReactionEnvelope? {
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val current = pendingReactionDao.reactionById(
                userId = candidate.userId,
                roomId = candidate.roomId,
                id = candidate.id
            ) ?: return@withTransaction null
            when {
                current.decodedState() == PendingReactionState.ADD_QUEUED &&
                    current.transactionId == candidate.transactionId -> {
                    pendingReactionDao.upsertReaction(
                        current.copy(
                            state = PendingReactionState.ADD_ACCEPTED.name,
                            reactionEventId = reactionEventId,
                            redactionTransactionId = null,
                            redactionEventId = null,
                            failureMessage = null,
                            updatedAtMillis = now
                        )
                    )
                    null
                }
                current.decodedState() == PendingReactionState.REMOVE_QUEUED &&
                    current.transactionId == candidate.transactionId &&
                    current.reactionEventId.isNullOrBlank() -> {
                    val next = current.copy(
                        reactionEventId = reactionEventId,
                        failureMessage = null,
                        updatedAtMillis = now
                    )
                    pendingReactionDao.upsertReaction(next)
                    next.toOutgoingReactionEnvelopeOrNull()
                }
                else -> null
            }
        }
    }

    suspend fun markOutgoingReactionRemovalAccepted(
        candidate: OutgoingReactionEnvelope,
        redactionEventId: String?
    ): OutgoingReactionEnvelope? {
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val current = pendingReactionDao.reactionById(
                userId = candidate.userId,
                roomId = candidate.roomId,
                id = candidate.id
            ) ?: return@withTransaction null
            when {
                current.decodedState() == PendingReactionState.REMOVE_QUEUED &&
                    current.redactionTransactionId == candidate.redactionTransactionId -> {
                    pendingReactionDao.upsertReaction(
                        current.copy(
                            state = PendingReactionState.REMOVED.name,
                            transactionId = null,
                            redactionEventId = redactionEventId,
                            failureMessage = null,
                            updatedAtMillis = now
                        )
                    )
                    null
                }
                current.decodedState() == PendingReactionState.ADD_AFTER_REMOVE_QUEUED &&
                    current.redactionTransactionId == candidate.redactionTransactionId -> {
                    val next = current.copy(
                        state = PendingReactionState.ADD_QUEUED.name,
                        reactionEventId = null,
                        redactionTransactionId = null,
                        redactionEventId = redactionEventId,
                        failureMessage = null,
                        updatedAtMillis = now
                    )
                    pendingReactionDao.upsertReaction(next)
                    next.toOutgoingReactionEnvelopeOrNull()
                }
                else -> null
            }
        }
    }

    suspend fun markOutgoingReactionRetrying(
        candidate: OutgoingReactionEnvelope,
        failureMessage: String?
    ) {
        val now = System.currentTimeMillis()
        val current = pendingReactionDao.reactionById(
            userId = candidate.userId,
            roomId = candidate.roomId,
            id = candidate.id
        ) ?: return
        if (!current.matchesReactionCandidate(candidate)) {
            return
        }
        pendingReactionDao.upsertReaction(
            current.copy(
                failureMessage = failureMessage,
                updatedAtMillis = now
            )
        )
    }

    suspend fun markOutgoingReactionTerminalFailure(
        candidate: OutgoingReactionEnvelope,
        failureMessage: String?
    ) {
        val now = System.currentTimeMillis()
        val current = pendingReactionDao.reactionById(
            userId = candidate.userId,
            roomId = candidate.roomId,
            id = candidate.id
        ) ?: return
        if (!current.matchesReactionCandidate(candidate)) {
            return
        }
        val next = when (candidate.state) {
            PendingReactionState.ADD_QUEUED -> when (current.decodedState()) {
                PendingReactionState.REMOVE_QUEUED -> current.copy(
                    state = PendingReactionState.REMOVED.name,
                    transactionId = null,
                    redactionTransactionId = null,
                    redactionEventId = null,
                    failureMessage = failureMessage,
                    updatedAtMillis = now
                )
                else -> current.copy(
                    state = PendingReactionState.FAILED.name,
                    failureMessage = failureMessage,
                    updatedAtMillis = now
                )
            }
            PendingReactionState.REMOVE_QUEUED -> {
                if (candidate.reactionEventId.isNullOrBlank()) {
                    when (current.decodedState()) {
                        PendingReactionState.ADD_QUEUED -> current.copy(
                            state = PendingReactionState.FAILED.name,
                            redactionTransactionId = null,
                            redactionEventId = null,
                            failureMessage = failureMessage,
                            updatedAtMillis = now
                        )
                        else -> current.copy(
                            state = PendingReactionState.REMOVED.name,
                            transactionId = null,
                            redactionTransactionId = null,
                            redactionEventId = null,
                            failureMessage = failureMessage,
                            updatedAtMillis = now
                        )
                    }
                } else {
                    current.copy(
                        state = PendingReactionState.ADD_ACCEPTED.name,
                        transactionId = null,
                        redactionTransactionId = null,
                        redactionEventId = null,
                        failureMessage = failureMessage,
                        updatedAtMillis = now
                    )
                }
            }
            PendingReactionState.ADD_AFTER_REMOVE_QUEUED -> current.copy(
                state = PendingReactionState.ADD_ACCEPTED.name,
                transactionId = null,
                redactionTransactionId = null,
                redactionEventId = null,
                failureMessage = failureMessage,
                updatedAtMillis = now
            )
            PendingReactionState.ADD_ACCEPTED,
            PendingReactionState.REMOVED,
            PendingReactionState.FAILED -> current.copy(
                failureMessage = failureMessage,
                updatedAtMillis = now
            )
        }
        pendingReactionDao.upsertReaction(next)
    }

    suspend fun markOutgoingRedactionDispatchAccepted(
        userId: String,
        roomId: String,
        envelopeId: String,
        redactionEventId: String
    ) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            outgoingDao.markDispatchAccepted(
                userId = userId,
                roomId = roomId,
                id = envelopeId,
                eventId = redactionEventId,
                updatedAtMillis = now
            )
            outgoingDao.deleteEnvelope(
                userId = userId,
                roomId = roomId,
                id = envelopeId
            )
            updateRoomPreview(userId, roomId, now)
        }
    }

    suspend fun markOutgoingRedactionDispatchResolved(
        userId: String,
        roomId: String,
        envelopeId: String
    ) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            outgoingDao.deleteEnvelope(
                userId = userId,
                roomId = roomId,
                id = envelopeId
            )
            updateRoomPreview(userId, roomId, now)
        }
    }

    suspend fun markOutgoingRedactionDispatchTerminalFailure(
        userId: String,
        roomId: String,
        envelopeId: String,
        failureMessage: String?
    ) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            val envelope = outgoingDao.redactionDispatchCandidate(userId, envelopeId)
            if (envelope != null) {
                restoreCachedMessageFromRedaction(envelope, now)
            }
            outgoingDao.markDispatchFailed(
                userId = userId,
                roomId = roomId,
                id = envelopeId,
                failureMessage = failureMessage,
                updatedAtMillis = now
            )
            outgoingDao.deleteEnvelope(
                userId = userId,
                roomId = roomId,
                id = envelopeId
            )
            updateRoomPreview(userId, roomId, now)
        }
    }

    suspend fun markOutgoingEditDispatchAccepted(
        userId: String,
        roomId: String,
        eventId: String,
        transactionId: String,
        editEventId: String,
        body: String
    ) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            messageDao.markPendingTextEditAccepted(
                userId = userId,
                roomId = roomId,
                eventId = eventId,
                editTransactionId = transactionId,
                latestEditEventId = editEventId,
                body = body,
                updatedAtMillis = now
            )
            updateRoomPreview(userId, roomId, now)
        }
    }

    suspend fun markOutgoingEditDispatchTerminalFailure(
        userId: String,
        roomId: String,
        eventId: String,
        transactionId: String
    ) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            messageDao.markPendingTextEditFailed(
                userId = userId,
                roomId = roomId,
                eventId = eventId,
                editTransactionId = transactionId,
                updatedAtMillis = now
            )
            updateRoomPreview(userId, roomId, now)
        }
    }

    suspend fun clearAll() {
        roomCacheWriteMutex.withLock {
            database.withTransaction {
                messageDao.clearAllMessages()
                roomDao.clearAllRooms()
                outgoingDao.clearAllEnvelopes()
                pendingReactionDao.clearAll()
                matrixRtcCallHistoryDao.clearAllMemberships()
                matrixRtcCallHistoryDao.clearAllCalls()
            }
            roomListOrderByUserId.clear()
            pendingResolvedRoomsByUserId.clear()
        }
        cleanupOrphanOutgoingMediaFiles()
    }

    private suspend fun materializeMissingMatrixRtcTimelineRows(
        userId: String,
        roomsById: Map<String, CachedRoomEntity>,
        now: Long,
        limit: Int
    ) {
        matrixRtcCallHistoryDao.callsMissingTimelineRows(
            userId = userId,
            limit = limit.coerceAtLeast(1)
        ).forEach { call ->
            refreshMatrixRtcCallProjection(
                userId = userId,
                call = call,
                roomsById = roomsById,
                now = now,
                ensureTimelineRow = true
            )
        }
    }

    private suspend fun refreshMatrixRtcCallProjectionsForRoomState(
        userId: String,
        roomsById: Map<String, CachedRoomEntity>,
        now: Long,
        limit: Int
    ) {
        val callsByEventId = LinkedHashMap<String, MatrixRtcCallEntity>()
        matrixRtcCallHistoryDao.callsWithRoomDirectnessMismatch(
            userId = userId,
            limit = limit.coerceAtLeast(1)
        ).forEach { call ->
            callsByEventId[call.eventId] = call
        }
        matrixRtcCallHistoryDao.expiredStartedRingCalls(
            userId = userId,
            nowMillis = now,
            limit = CALL_HISTORY_EXPIRED_REFRESH_LIMIT
        ).forEach { call ->
            callsByEventId[call.eventId] = call
        }
        callsByEventId.values.forEach { call ->
            refreshMatrixRtcCallProjection(
                userId = userId,
                call = call,
                roomsById = roomsById,
                now = now,
                ensureTimelineRow = true
            )
        }
    }

    private suspend fun refreshMatrixRtcCallProjection(
        userId: String,
        call: MatrixRtcCallEntity,
        roomsById: Map<String, CachedRoomEntity>,
        now: Long,
        ensureTimelineRow: Boolean
    ) {
        val memberships = matrixRtcCallHistoryDao.membershipsInRoomWindow(
            userId = userId,
            roomId = call.roomId,
            fromTimestampMillis = MatrixRtcCallHistoryProjection
                .membershipLowerBound(call.timestampMillis),
            toTimestampMillis = MatrixRtcCallHistoryProjection.membershipUpperBound(call)
        )
        val projection = MatrixRtcCallHistoryProjection.project(
            call = call,
            isDirect = roomsById[call.roomId].isDirectRoom(),
            currentUserId = userId,
            memberships = memberships,
            nowMillis = now
        )
        val projectedTimelineEntity = if (ensureTimelineRow) {
            call.toTimelineNotification()
                .toMatrixChatMessage(outcome = projection.outcome)
                .toEntity(
                    userId = userId,
                    roomId = call.roomId,
                    timelineIndex = 0,
                    updatedAtMillis = now
                )
        } else {
            null
        }
        val insertedTimelineRowId = projectedTimelineEntity?.let { entity ->
            messageDao.insertMessageIfAbsent(entity)
        }
        val projectionChanged = matrixRtcCallHistoryDao.updateCallProjection(
            userId = userId,
            eventId = call.eventId,
            isDirect = projection.isDirect,
            hasOwnJoin = projection.hasOwnJoin,
            hasRemoteJoin = projection.hasRemoteJoin,
            hasOwnLeave = projection.hasOwnLeave,
            hasRemoteLeave = projection.hasRemoteLeave,
            lastMembershipEventTimestampMillis =
                projection.lastMembershipEventTimestampMillis,
            lastOwnLeaveTimestampMillis = projection.lastOwnLeaveTimestampMillis,
            lastRemoteLeaveTimestampMillis = projection.lastRemoteLeaveTimestampMillis,
            outcome = projection.outcome.name,
            updatedAtMillis = now
        )
        if (ensureTimelineRow || projectionChanged > 0) {
            messageDao.updateMatrixRtcCallTimelineProjection(
                userId = userId,
                roomId = call.roomId,
                eventId = call.eventId,
                timelineDetailsJson = MatrixTimelineDetailsCodec.encodeMatrixRtcCall(
                    MatrixRtcCallEventDetails(
                        parentEventId = call.parentEventId,
                        callIntent = call.callIntent,
                        notificationType = call.notificationType
                            .toMatrixRtcCallNotificationType(),
                        expiresAtMillis = call.expiresAtMillis,
                        declinedBy = MatrixRtcCallHistoryProjection.decodeDeclinedBy(
                            call.declinedByJson
                        ),
                        outcome = projection.outcome
                    )
                ),
                updatedAtMillis = now
            )
        }
        if (insertedTimelineRowId != null && insertedTimelineRowId != -1L) {
            deleteSafeEventDuplicates(listOf(requireNotNull(projectedTimelineEntity)))
        }
    }

    suspend fun cleanupOrphanOutgoingMediaFiles() = withContext(Dispatchers.IO) {
        val mediaDir = File(context.filesDir, OutgoingMediaStorage.DIRECTORY_NAME)
        val files = mediaDir.listFiles()?.filter { it.isFile }.orEmpty()
        if (files.isEmpty()) return@withContext

        val activePaths = outgoingDao.activeMediaLocalPaths().toSet()
        files.forEach { file ->
            if (file.absolutePath !in activePaths) {
                runCatching { file.delete() }
            }
        }
    }

    private fun CachedRoomEntity.toRoomSummary(): MatrixRoomSummary {
        return MatrixRoomSummary(
            id = id,
            displayName = displayName,
            avatarUrl = avatarUrl,
            directUserId = directUserId,
            isSpace = isSpace,
            membership = membership.toCachedEnumOrDefault(
                MatrixSpaceMembership.UNKNOWN
            ),
            lastMessageText = lastMessageText,
            lastMessageSenderName = lastMessageSenderName,
            lastMessageAtMillis = lastMessageAtMillis,
            lastOwnMessageStatus = lastOwnMessageStatus.toLastOwnMessageStatusOrNull(),
            unreadCount = unreadCount,
            unreadMentionCount = unreadMentionCount,
            isMarkedUnread = isMarkedUnread,
            roomDetails = toRoomDetailsOrNull()
        )
    }

    private fun CachedRoomEntity?.toRoomDetailsOrNull(): MatrixRoomDetails? {
        val room = this ?: return null
        if (room.detailsUpdatedAtMillis == null) {
            return null
        }
        return MatrixRoomDetails(
            roomId = room.id,
            displayName = room.displayName,
            avatarUrl = room.avatarUrl,
            directUserId = room.directUserId,
            kind = when {
                room.isSpace -> MatrixRoomKind.SPACE
                !room.directUserId.isNullOrBlank() -> MatrixRoomKind.DIRECT
                else -> MatrixRoomKind.GROUP
            },
            topic = room.detailsTopic,
            joinedMemberCount = room.detailsJoinedMemberCount ?: 0,
            encryption = room.detailsEncryption.toCachedEnumOrDefault(
                MatrixRoomEncryption.UNKNOWN
            ),
            access = room.detailsAccess.toCachedEnumOrDefault(MatrixRoomAccess.UNKNOWN),
            historyVisibility = room.detailsHistoryVisibility.toCachedEnumOrDefault(
                MatrixRoomHistoryVisibility.CUSTOM
            ),
            pinnedEventCount = room.detailsPinnedEventCount ?: 0,
            canonicalAlias = room.detailsCanonicalAlias,
            roomVersion = room.detailsRoomVersion,
            creatorSemantics = room.detailsCreatorSemantics.toCachedEnumOrDefault(
                MatrixRoomCreatorSemantics.UNKNOWN
            ),
            capabilities = MatrixRoomCapabilities(
                canInviteMembers = room.detailsCanInviteMembers,
                canChangeName = room.detailsCanChangeName,
                canChangeTopic = room.detailsCanChangeTopic,
                canChangeAvatar = room.detailsCanChangeAvatar
            )
        )
    }

    private fun MatrixRtcCallTimelineNotification.toEntity(
        userId: String,
        isDirect: Boolean,
        now: Long
    ): MatrixRtcCallEntity {
        return MatrixRtcCallEntity(
            userId = userId,
            eventId = eventId,
            roomId = roomId,
            parentEventId = parentEventId,
            senderId = senderId,
            senderDisplayName = senderDisplayName,
            isOutgoing = isOutgoing,
            timestampMillis = timestampMillis,
            notificationType = notificationType.name,
            callIntent = callIntent,
            expiresAtMillis = expiresAtMillis,
            declinedByJson = MatrixRtcCallHistoryProjection.encodeDeclinedBy(declinedBy),
            isDirect = isDirect,
            hasOwnJoin = false,
            hasRemoteJoin = false,
            hasOwnLeave = false,
            hasRemoteLeave = false,
            lastMembershipEventTimestampMillis = null,
            lastOwnLeaveTimestampMillis = null,
            lastRemoteLeaveTimestampMillis = null,
            outcome = MatrixRtcCallHistoryOutcome.STARTED.name,
            updatedAtMillis = now
        )
    }

    private fun MatrixRtcCallTimelineMembership.toEntity(
        userId: String,
        now: Long
    ): MatrixRtcCallMembershipEntity {
        return MatrixRtcCallMembershipEntity(
            userId = userId,
            eventId = eventId,
            roomId = roomId,
            eventType = eventType,
            stateKey = stateKey,
            senderId = senderId,
            timestampMillis = timestampMillis,
            isLeave = isLeave,
            memberUserId = memberUserId,
            deviceId = deviceId,
            memberId = memberId,
            callIntent = callIntent,
            expiresAtMillis = expiresAtMillis,
            updatedAtMillis = now
        )
    }

    private fun MatrixIncomingRtcCallNotification.toTimelineNotification():
        MatrixRtcCallTimelineNotification {
        val fallbackTimestamp = expiresAtMillis - DEFAULT_CALL_NOTIFICATION_LIFETIME_MS
        return MatrixRtcCallTimelineNotification(
            eventId = eventId,
            roomId = roomId,
            parentEventId = null,
            senderId = senderId,
            senderDisplayName = null,
            isOutgoing = false,
            timestampMillis = fallbackTimestamp.coerceAtMost(System.currentTimeMillis()),
            notificationType = when (kind) {
                MatrixIncomingRtcCallNotificationKind.RING -> MatrixRtcCallNotificationType.RING
                MatrixIncomingRtcCallNotificationKind.NOTIFICATION ->
                    MatrixRtcCallNotificationType.NOTIFICATION
            },
            callIntent = if (isAudioCall) "audio" else null,
            expiresAtMillis = expiresAtMillis,
            declinedBy = emptyList()
        )
    }

    private fun MatrixRtcCallEntity.toTimelineNotification(): MatrixRtcCallTimelineNotification {
        return MatrixRtcCallTimelineNotification(
            eventId = eventId,
            roomId = roomId,
            parentEventId = parentEventId,
            senderId = senderId,
            senderDisplayName = senderDisplayName,
            isOutgoing = isOutgoing,
            timestampMillis = timestampMillis,
            notificationType = notificationType.toMatrixRtcCallNotificationType(),
            callIntent = callIntent,
            expiresAtMillis = expiresAtMillis,
            declinedBy = MatrixRtcCallHistoryProjection.decodeDeclinedBy(declinedByJson)
        )
    }

    private fun MatrixRtcCallEntity.toHistoryItem(room: CachedRoomEntity?): MatrixRtcCallHistoryItem {
        return MatrixRtcCallHistoryItem(
            eventId = eventId,
            roomId = roomId,
            roomName = room?.displayName
                ?: senderDisplayName?.takeIf { it.isNotBlank() }
                ?: senderId,
            roomAvatarUrl = room?.avatarUrl,
            senderId = senderId,
            senderDisplayName = senderDisplayName,
            isOutgoing = isOutgoing,
            timestampMillis = timestampMillis,
            notificationType = notificationType.toMatrixRtcCallNotificationType(),
            callIntent = callIntent,
            outcome = outcome.toMatrixRtcCallHistoryOutcome(),
            expiresAtMillis = expiresAtMillis
        )
    }

    private fun CachedRoomEntity?.isDirectRoom(): Boolean {
        return !this?.directUserId.isNullOrBlank()
    }

    private fun String.toMatrixRtcCallNotificationType(): MatrixRtcCallNotificationType {
        return runCatching { MatrixRtcCallNotificationType.valueOf(this) }
            .getOrDefault(MatrixRtcCallNotificationType.NOTIFICATION)
    }

    private fun String.toMatrixRtcCallHistoryOutcome(): MatrixRtcCallHistoryOutcome {
        return runCatching { MatrixRtcCallHistoryOutcome.valueOf(this) }
            .getOrDefault(MatrixRtcCallHistoryOutcome.STARTED)
    }

    private fun CachedTimelineMessageEntity.toChatMessage(): MatrixChatMessage {
        val displayBody = pendingEditBody
            ?.takeIf { isEditPending && it.isNotBlank() }
            ?: body
        val decodedContentType = contentType.toMatrixContentType()
        return MatrixChatMessage(
            id = id,
            eventId = eventId,
            transactionId = transactionId,
            sender = sender,
            senderDisplayName = senderDisplayName,
            body = displayBody,
            timestampMillis = timestampMillis,
            isOwn = isOwn,
            contentType = decodedContentType,
            imageInfo = imageInfoOrNull(),
            audioInfo = audioInfoOrNull(),
            deliveryState = deliveryState.toMatrixDeliveryState(),
            replyInfo = replyInfoOrNull(),
            forwardedFrom = forwardedFrom,
            zynaAttributes = ZynaHtmlCodec.decodeAttributesJson(zynaAttributesJson),
            isEdited = isEdited,
            isEditPending = isEditPending,
            isEditFailed = isEditFailed,
            latestEditEventId = latestEditEventId,
            editTransactionId = editTransactionId,
            pendingEditBody = pendingEditBody,
            reactions = reactionsJson.decodeReactions(),
            systemEventDetails = if (decodedContentType == MatrixMessageContentType.SYSTEM_EVENT) {
                MatrixTimelineDetailsCodec.decodeSystemEvent(timelineDetailsJson)
            } else {
                null
            },
            matrixRtcCallDetails = if (
                decodedContentType == MatrixMessageContentType.MATRIX_RTC_CALL
            ) {
                MatrixTimelineDetailsCodec.decodeMatrixRtcCall(timelineDetailsJson)
            } else {
                null
            }
        )
    }

    private fun List<MatrixChatMessage>.toEntities(
        userId: String,
        roomId: String,
        updatedAtMillis: Long
    ): List<CachedTimelineMessageEntity> {
        return mapIndexed { index, message ->
            message.toEntity(userId, roomId, index, updatedAtMillis)
        }
    }

    private fun MatrixChatMessage.toEntity(
        userId: String,
        roomId: String,
        timelineIndex: Int,
        updatedAtMillis: Long
    ): CachedTimelineMessageEntity {
        return CachedTimelineMessageEntity(
            userId = userId,
            roomId = roomId,
            id = id,
            eventId = eventId,
            transactionId = transactionId,
            timelineIndex = timelineIndex,
            sender = sender,
            senderDisplayName = senderDisplayName,
            body = body,
            timestampMillis = timestampMillis,
            isOwn = isOwn,
            contentType = contentType.name,
            imageSourceJson = imageInfo?.sourceJson,
            imageThumbnailSourceJson = imageInfo?.thumbnailSourceJson,
            imageWidth = imageInfo?.width,
            imageHeight = imageInfo?.height,
            imageCaption = imageInfo?.caption.normalizedMessageCaption(),
            imageMimeType = imageInfo?.mimeType,
            imageBlurhash = imageInfo?.blurhash,
            audioSourceJson = audioInfo?.sourceJson,
            audioFilename = audioInfo?.filename,
            audioCaption = audioInfo?.caption.normalizedMessageCaption(),
            audioMimeType = audioInfo?.mimeType,
            audioSizeBytes = audioInfo?.sizeBytes,
            audioDurationMillis = audioInfo?.durationMillis,
            audioWaveform = audioInfo?.waveform.toWaveformCacheString(),
            audioIsVoice = audioInfo?.isVoice == true,
            deliveryState = deliveryState.name,
            replyEventId = replyInfo?.eventId,
            replySenderId = replyInfo?.senderId,
            replySenderDisplayName = replyInfo?.senderDisplayName,
            replyBody = replyInfo?.body,
            forwardedFrom = forwardedFrom,
            zynaAttributesJson = ZynaHtmlCodec.encodeAttributesJson(zynaAttributes),
            timelineDetailsJson = when (contentType) {
                MatrixMessageContentType.SYSTEM_EVENT -> systemEventDetails
                    ?.let(MatrixTimelineDetailsCodec::encodeSystemEvent)

                MatrixMessageContentType.MATRIX_RTC_CALL -> matrixRtcCallDetails
                    ?.let(MatrixTimelineDetailsCodec::encodeMatrixRtcCall)

                else -> null
            },
            isEdited = isEdited,
            isEditPending = isEditPending,
            isEditFailed = isEditFailed,
            latestEditEventId = latestEditEventId,
            editTransactionId = editTransactionId,
            pendingEditBody = pendingEditBody,
            reactionsJson = reactions.encodeReactions(),
            updatedAtMillis = updatedAtMillis
        )
    }

    private suspend fun retireOutgoingEnvelopesDeliveredBy(
        messages: List<MatrixChatMessage>,
        userId: String,
        roomId: String,
        updatedAtMillis: Long
    ) {
        val eventIds = messages
            .mapNotNull { it.eventId }
            .distinct()
        if (eventIds.isEmpty()) return

        retireOutgoingEnvelopesByEventIds(userId, roomId, eventIds, updatedAtMillis)
    }

    private suspend fun retireOutgoingEnvelopesByEventIds(
        userId: String,
        roomId: String,
        eventIds: List<String>,
        updatedAtMillis: Long
    ) {
        if (eventIds.isEmpty()) return

        val transactionIds = outgoingDao.transactionIdsForEventIds(
            userId = userId,
            roomId = roomId,
            eventIds = eventIds
        )
        val mediaLocalPaths = outgoingDao.mediaLocalPathsForEventIds(
            userId = userId,
            roomId = roomId,
            eventIds = eventIds
        )
        outgoingDao.retireByEventIds(
            userId = userId,
            roomId = roomId,
            eventIds = eventIds,
            updatedAtMillis = updatedAtMillis
        )
        if (transactionIds.isNotEmpty()) {
            messageDao.deleteMessagesByIds(userId, roomId, transactionIds)
        }
        deleteLocalFiles(mediaLocalPaths)
    }

    private suspend fun updateRoomPreview(
        userId: String,
        roomId: String,
        updatedAtMillis: Long
    ) {
        val outgoingEnvelopes = outgoingDao.activeRoomEnvelopesSnapshot(userId, roomId)
        val hiddenTimelineIds = outgoingEnvelopes
            .flatMap { envelope -> listOfNotNull(envelope.transactionId, envelope.eventId) }
            .toSet()
        val latestTimelineMessages = messageDao.latestRoomPreviewMessages(
            userId = userId,
            roomId = roomId,
            localIdPattern = LOCAL_MESSAGE_ID_PATTERN,
            limit = ROOM_PREVIEW_CANDIDATE_LIMIT
        )
            .filter { message -> message.identityIds().none { it in hiddenTimelineIds } }
            .map { it.toChatMessage() }
        val latestOutgoingMessages = outgoingEnvelopes
            .mapNotNull { it.toChatMessageOrNull() }
        val latestMessage = (latestTimelineMessages + latestOutgoingMessages)
            .maxWithOrNull(compareBy<MatrixChatMessage> { it.timestampMillis }.thenBy { it.id })
        roomDao.updateRoomPreview(
            userId = userId,
            roomId = roomId,
            lastMessageText = latestMessage?.body,
            lastMessageSenderName = latestMessage?.previewSenderName(),
            lastMessageAtMillis = latestMessage?.timestampMillis,
            lastOwnMessageStatus = latestMessage?.lastOwnMessageStatus()?.name,
            updatedAtMillis = updatedAtMillis
        )
        // A warm order mirror is published only after every row has an explicit sparse label.
        // Preview fields are fallback sort keys solely for rows that have no label, so this
        // hot timeline write cannot change the order represented by a warm mirror.
    }

    private fun mergeTimelineWithOutgoing(
        messages: List<CachedTimelineMessageEntity>,
        outgoingEnvelopes: List<OutgoingEnvelopeEntity>,
        bounds: TimelineWindowBounds? = null
    ): List<MatrixChatMessage> {
        val hiddenTimelineIds = outgoingEnvelopes
            .flatMap { envelope -> listOfNotNull(envelope.transactionId, envelope.eventId) }
            .toSet()
        val timelineMessages = messages
            .filter { message ->
                !message.id.startsWith(LOCAL_MESSAGE_ID_PREFIX) &&
                    message.identityIds().none { it in hiddenTimelineIds }
            }
            .map { it.toChatMessage() }
        val outgoingMessages = outgoingEnvelopes
            .mapNotNull { it.toChatMessageOrNull() }
            .filter { message -> bounds?.contains(message) ?: true }

        return (timelineMessages + outgoingMessages)
            .sortedWith(compareBy<MatrixChatMessage> { it.timestampMillis }.thenBy { it.id })
    }

    private fun TimelineWindowBounds.contains(message: MatrixChatMessage): Boolean {
        val oldest = oldestAnchor
        val newest = newestAnchor
        if (oldest != null && message.isOlderThan(oldest)) {
            return false
        }
        if (newest != null && message.isNewerThan(newest)) {
            return false
        }
        return true
    }

    private fun MatrixChatMessage.isOlderThan(anchor: TimelineWindowAnchor): Boolean {
        return timestampMillis < anchor.timestampMillis ||
            (timestampMillis == anchor.timestampMillis && id < anchor.id)
    }

    private fun MatrixChatMessage.isNewerThan(anchor: TimelineWindowAnchor): Boolean {
        return timestampMillis > anchor.timestampMillis ||
            (timestampMillis == anchor.timestampMillis && id > anchor.id)
    }

    private fun OutgoingEnvelopeEntity.toChatMessageOrNull(): MatrixChatMessage? {
        val state = transportState.toOutgoingTransportState()

        return when (kind) {
            OutgoingEnvelopeKind.TEXT.name -> MatrixChatMessage(
                id = "outgoing:$id",
                eventId = eventId,
                transactionId = transactionId,
                sender = userId,
                body = body,
                timestampMillis = createdAtMillis,
                isOwn = true,
                contentType = MatrixMessageContentType.TEXT,
                deliveryState = state.toOutgoingDeliveryState(),
                replyInfo = replyInfoOrNull(),
                forwardedFrom = forwardedFrom,
                outgoingEnvelopeId = id,
                canRetryOutgoingEnvelope = state == OutgoingTransportState.FAILED,
                canDiscardOutgoingEnvelope = state == OutgoingTransportState.FAILED
            )
            OutgoingEnvelopeKind.IMAGE.name -> {
                val localPath = imageLocalPath?.takeIf { it.isNotBlank() }
                val sourceJson = imageSourceJson?.takeIf { it.isNotBlank() }
                    ?: localPath?.let { "local:$it" }
                    ?: return null
                val zynaAttributes = ZynaHtmlCodec.decodeAttributesJson(zynaAttributesJson)
                MatrixChatMessage(
                    id = "outgoing:$id",
                    eventId = eventId,
                    transactionId = transactionId,
                    sender = userId,
                    body = imageCaption ?: "Photo",
                    timestampMillis = createdAtMillis,
                    isOwn = true,
                    contentType = MatrixMessageContentType.IMAGE,
                    imageInfo = MatrixImageInfo(
                        sourceJson = sourceJson,
                        thumbnailSourceJson = imageThumbnailSourceJson,
                        width = imageWidth,
                        height = imageHeight,
                        caption = imageCaption,
                        mimeType = imageMimeType,
                        blurhash = imageBlurhash,
                        localPath = localPath
                    ),
                    forwardedFrom = zynaAttributes.forwardedFrom,
                    zynaAttributes = zynaAttributes,
                    deliveryState = state.toOutgoingDeliveryState(),
                    outgoingEnvelopeId = id,
                    canRetryOutgoingEnvelope = state == OutgoingTransportState.FAILED,
                    canDiscardOutgoingEnvelope = state == OutgoingTransportState.FAILED
                )
            }
            OutgoingEnvelopeKind.VOICE.name -> {
                val localPath = voiceLocalPath?.takeIf { it.isNotBlank() } ?: return null
                MatrixChatMessage(
                    id = "outgoing:$id",
                    eventId = eventId,
                    transactionId = transactionId,
                    sender = userId,
                    body = VOICE_MESSAGE_BODY,
                    timestampMillis = createdAtMillis,
                    isOwn = true,
                    contentType = MatrixMessageContentType.AUDIO,
                    audioInfo = MatrixAudioInfo(
                        sourceJson = "local:$localPath",
                        filename = File(localPath).name,
                        caption = null,
                        mimeType = voiceMimeType?.takeIf { it.isNotBlank() } ?: "audio/mp4",
                        sizeBytes = voiceSizeBytes?.takeIf { it > 0L },
                        durationMillis = voiceDurationMillis?.takeIf { it > 0L },
                        waveform = voiceWaveform.toWaveformList(),
                        isVoice = true,
                        localPath = localPath
                    ),
                    deliveryState = state.toOutgoingDeliveryState(),
                    replyInfo = replyInfoOrNull(),
                    outgoingEnvelopeId = id,
                    canRetryOutgoingEnvelope = state == OutgoingTransportState.FAILED,
                    canDiscardOutgoingEnvelope = state == OutgoingTransportState.FAILED
                )
            }
            else -> null
        }
    }

    private suspend fun markCachedMessageRedacted(
        userId: String,
        roomId: String,
        ids: List<String>,
        updatedAtMillis: Long
    ) {
        if (ids.isEmpty()) return

        messageDao.updateMessagesByIds(
            userId = userId,
            roomId = roomId,
            ids = ids,
            body = REDACTED_MESSAGE_BODY,
            contentType = MatrixMessageContentType.REDACTED.name,
            deliveryState = MatrixMessageDeliveryState.SENT.name,
            updatedAtMillis = updatedAtMillis
        )
    }

    private suspend fun restoreCachedMessageFromRedaction(
        envelope: OutgoingEnvelopeEntity,
        updatedAtMillis: Long
    ) {
        val ids = listOfNotNull(envelope.targetEventId, envelope.targetTransactionId)
        if (ids.isEmpty()) return

        messageDao.updateMessagesByIds(
            userId = envelope.userId,
            roomId = envelope.roomId,
            ids = ids,
            body = envelope.targetBody ?: "",
            contentType = envelope.targetContentType ?: MatrixMessageContentType.TEXT.name,
            deliveryState = MatrixMessageDeliveryState.SENT.name,
            updatedAtMillis = updatedAtMillis
        )
    }

    private fun MatrixChatMessage.previewSenderName(): String? {
        return if (isOwn) {
            OWN_MESSAGE_PREVIEW_SENDER
        } else {
            senderDisplayName?.takeIf { it.isNotBlank() }
                ?: sender.takeIf { it.isNotBlank() }
        }
    }

    private fun MatrixChatMessage.lastOwnMessageStatus(): MatrixLastOwnMessageStatus? {
        if (!isOwn) return null

        return when (deliveryState) {
            MatrixMessageDeliveryState.SENDING -> MatrixLastOwnMessageStatus.PENDING
            MatrixMessageDeliveryState.SENT -> MatrixLastOwnMessageStatus.SENT
            MatrixMessageDeliveryState.FAILED -> MatrixLastOwnMessageStatus.FAILED
        }
    }

    private fun deleteLocalFiles(paths: List<String>) {
        paths.asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { path -> runCatching { File(path).delete() } }
    }

    private fun OutgoingEnvelopeEntity.toOutgoingTextEnvelopeOrNull(): OutgoingTextEnvelope? {
        if (kind != OutgoingEnvelopeKind.TEXT.name) return null

        return OutgoingTextEnvelope(
            userId = userId,
            roomId = roomId,
            id = id,
            transportState = transportState.toOutgoingTransportState(),
            transactionId = transactionId,
            eventId = eventId,
            body = body,
            replyInfo = replyInfoOrNull(),
            forwardedFrom = forwardedFrom,
            createdAtMillis = createdAtMillis,
            failureMessage = failureMessage
        )
    }

    private fun OutgoingEnvelopeEntity.toOutgoingImageEnvelopeOrNull(): OutgoingImageEnvelope? {
        if (kind != OutgoingEnvelopeKind.IMAGE.name) return null
        val localPath = imageLocalPath?.takeIf { it.isNotBlank() }
        val uploadedImageJson = imageUploadedJson?.takeIf { it.isNotBlank() }
        val sourceJson = imageSourceJson?.takeIf { it.isNotBlank() }
        if (localPath == null && uploadedImageJson == null && sourceJson == null) return null

        return OutgoingImageEnvelope(
            userId = userId,
            roomId = roomId,
            id = id,
            transportState = transportState.toOutgoingTransportState(),
            transactionId = transactionId,
            eventId = eventId,
            localPath = localPath,
            mimeType = imageMimeType?.takeIf { it.isNotBlank() } ?: "image/jpeg",
            width = imageWidth?.takeIf { it > 0 } ?: 1,
            height = imageHeight?.takeIf { it > 0 } ?: 1,
            sizeBytes = imageSizeBytes?.takeIf { it > 0L } ?: 0L,
            thumbnailLocalPath = imageThumbnailLocalPath?.takeIf { it.isNotBlank() },
            thumbnailMimeType = imageThumbnailMimeType?.takeIf { it.isNotBlank() },
            thumbnailWidth = imageThumbnailWidth?.takeIf { it > 0 },
            thumbnailHeight = imageThumbnailHeight?.takeIf { it > 0 },
            thumbnailSizeBytes = imageThumbnailSizeBytes?.takeIf { it > 0L },
            caption = imageCaption,
            zynaAttributesJson = zynaAttributesJson,
            sourceJson = sourceJson,
            thumbnailSourceJson = imageThumbnailSourceJson?.takeIf { it.isNotBlank() },
            blurhash = imageBlurhash?.takeIf { it.isNotBlank() },
            uploadedImageJson = uploadedImageJson,
            createdAtMillis = createdAtMillis,
            failureMessage = failureMessage
        )
    }

    private fun OutgoingEnvelopeEntity.toOutgoingVoiceEnvelopeOrNull(): OutgoingVoiceEnvelope? {
        if (kind != OutgoingEnvelopeKind.VOICE.name) return null
        val localPath = voiceLocalPath?.takeIf { it.isNotBlank() } ?: return null

        return OutgoingVoiceEnvelope(
            userId = userId,
            roomId = roomId,
            id = id,
            transportState = transportState.toOutgoingTransportState(),
            transactionId = transactionId,
            eventId = eventId,
            localPath = localPath,
            mimeType = voiceMimeType?.takeIf { it.isNotBlank() } ?: "audio/mp4",
            sizeBytes = voiceSizeBytes?.takeIf { it > 0L } ?: 0L,
            durationMillis = voiceDurationMillis?.takeIf { it > 0L } ?: 1L,
            waveform = voiceWaveform.toWaveformList(),
            replyInfo = replyInfoOrNull(),
            uploadedVoiceJson = voiceUploadedJson?.takeIf { it.isNotBlank() },
            createdAtMillis = createdAtMillis,
            failureMessage = failureMessage
        )
    }

    private fun OutgoingEnvelopeEntity.toOutgoingRedactionEnvelopeOrNull(): OutgoingRedactionEnvelope? {
        if (kind != OutgoingEnvelopeKind.REDACTION.name) return null
        val redactionTargetEventId = targetEventId ?: return null

        return OutgoingRedactionEnvelope(
            userId = userId,
            roomId = roomId,
            id = id,
            transportState = transportState.toOutgoingTransportState(),
            transactionId = transactionId,
            redactionEventId = eventId,
            targetEventId = redactionTargetEventId,
            targetTransactionId = targetTransactionId,
            targetBody = targetBody.orEmpty(),
            targetContentType = targetContentType ?: MatrixMessageContentType.TEXT.name,
            createdAtMillis = createdAtMillis,
            failureMessage = failureMessage
        )
    }

    private fun CachedTimelineMessageEntity.toOutgoingEditEnvelopeOrNull(): OutgoingEditEnvelope? {
        val eventId = eventId?.takeIf { it.isNotBlank() } ?: return null
        val editTransactionId = editTransactionId?.takeIf { it.isNotBlank() } ?: return null
        val pendingBody = pendingEditBody?.takeIf { it.isNotBlank() } ?: return null
        if (!isEditPending || contentType != MatrixMessageContentType.TEXT.name) {
            return null
        }

        return OutgoingEditEnvelope(
            userId = userId,
            roomId = roomId,
            eventId = eventId,
            transactionId = editTransactionId,
            body = pendingBody,
            createdAtMillis = updatedAtMillis
        )
    }

    private fun String.toMatrixDeliveryState(): MatrixMessageDeliveryState {
        return runCatching { MatrixMessageDeliveryState.valueOf(this) }
            .getOrDefault(MatrixMessageDeliveryState.SENT)
    }

    private fun String.toMatrixContentType(): MatrixMessageContentType {
        return runCatching { MatrixMessageContentType.valueOf(this) }
            .getOrDefault(MatrixMessageContentType.TEXT)
    }

    private fun CachedTimelineMessageEntity.identityIds(): List<String> {
        return listOfNotNull(id, eventId, transactionId)
    }

    private fun CachedTimelineMessageEntity.toTimelineWindowAnchor(): TimelineWindowAnchor {
        return TimelineWindowAnchor(
            timestampMillis = timestampMillis,
            id = id
        )
    }

    private fun List<CachedTimelineMessageEntity>.dedupeTimelineWindowEntities(): List<CachedTimelineMessageEntity> {
        val seenEventIds = mutableSetOf<String>()
        val seenTransactionIds = mutableSetOf<String>()
        val seenIds = mutableSetOf<String>()
        return sortedWith(
            compareBy<CachedTimelineMessageEntity> { it.timestampMillis }.thenBy { it.id }
        )
            .filter { message ->
                val eventId = message.eventId?.takeIf { it.isNotBlank() }
                if (eventId != null) {
                    return@filter seenEventIds.add(eventId)
                }
                val transactionId = message.transactionId?.takeIf { it.isNotBlank() }
                if (transactionId != null) {
                    return@filter seenTransactionIds.add(transactionId)
                }
                seenIds.add(message.id)
            }
    }

    private fun CachedTimelineMessageEntity.replyInfoOrNull(): MatrixReplyInfo? {
        val eventId = replyEventId?.takeIf { it.isNotBlank() } ?: return null
        return MatrixReplyInfo(
            eventId = eventId,
            senderId = replySenderId.orEmpty(),
            senderDisplayName = replySenderDisplayName?.takeIf { it.isNotBlank() },
            body = replyBody.orEmpty()
        )
    }

    private fun CachedTimelineMessageEntity.imageInfoOrNull(): MatrixImageInfo? {
        val sourceJson = imageSourceJson?.takeIf { it.isNotBlank() } ?: return null
        return MatrixImageInfo(
            sourceJson = sourceJson,
            thumbnailSourceJson = imageThumbnailSourceJson?.takeIf { it.isNotBlank() },
            width = imageWidth,
            height = imageHeight,
            caption = imageCaption.normalizedMessageCaption(),
            mimeType = imageMimeType?.takeIf { it.isNotBlank() },
            blurhash = imageBlurhash?.takeIf { it.isNotBlank() }
        )
    }

    private fun CachedTimelineMessageEntity.audioInfoOrNull(): MatrixAudioInfo? {
        val sourceJson = audioSourceJson?.takeIf { it.isNotBlank() } ?: return null
        return MatrixAudioInfo(
            sourceJson = sourceJson,
            filename = audioFilename?.takeIf { it.isNotBlank() },
            caption = audioCaption.normalizedMessageCaption(),
            mimeType = audioMimeType?.takeIf { it.isNotBlank() },
            sizeBytes = audioSizeBytes?.takeIf { it > 0L },
            durationMillis = audioDurationMillis?.takeIf { it > 0L },
            waveform = audioWaveform.toWaveformList(),
            isVoice = audioIsVoice
        )
    }

    private fun OutgoingEnvelopeEntity.replyInfoOrNull(): MatrixReplyInfo? {
        val eventId = replyEventId?.takeIf { it.isNotBlank() } ?: return null
        return MatrixReplyInfo(
            eventId = eventId,
            senderId = replySenderId.orEmpty(),
            senderDisplayName = replySenderDisplayName?.takeIf { it.isNotBlank() },
            body = replyBody.orEmpty()
        )
    }

    private fun MatrixChatMessage.identityIds(): List<String> {
        return listOfNotNull(id, eventId, transactionId)
    }

    private suspend fun deleteSafeEventDuplicates(
        messages: List<CachedTimelineMessageEntity>
    ) {
        messages
            .filter { it.eventId != null }
            .distinctBy { it.eventId }
            .forEach { message ->
                val eventId = message.eventId ?: return@forEach
                messageDao.deleteSafeEventDuplicates(
                    userId = message.userId,
                    roomId = message.roomId,
                    keepId = message.id,
                    eventId = eventId,
                    sender = message.sender,
                    timestampMillis = message.timestampMillis,
                    timestampToleranceMillis = DEDUPE_TIMESTAMP_TOLERANCE_MS,
                    contentType = message.contentType,
                    body = message.body
                )
            }
    }

    private suspend fun existingMessagesMatching(
        userId: String,
        roomId: String,
        incomingMessages: List<CachedTimelineMessageEntity>
    ): List<CachedTimelineMessageEntity> {
        val identityIds = incomingMessages
            .flatMap { it.identityIds() }
            .distinct()
        if (identityIds.isEmpty()) {
            return emptyList()
        }

        return identityIds
            .chunked(REDACTION_ID_QUERY_CHUNK_SIZE)
            .flatMap { ids ->
                messageDao.messagesMatchingIds(
                    userId = userId,
                    roomId = roomId,
                    ids = ids
                )
            }
            .distinctBy { it.id }
    }

    private suspend fun replyTargetsMatching(
        userId: String,
        roomId: String,
        incomingMessages: List<CachedTimelineMessageEntity>
    ): List<CachedTimelineMessageEntity> {
        val replyEventIds = incomingMessages
            .mapNotNull { it.replyEventId }
            .distinct()
        if (replyEventIds.isEmpty()) {
            return emptyList()
        }

        return replyEventIds
            .chunked(REDACTION_ID_QUERY_CHUNK_SIZE)
            .flatMap { ids ->
                messageDao.messagesMatchingIds(
                    userId = userId,
                    roomId = roomId,
                    ids = ids
                )
            }
            .distinctBy { it.id }
    }

    private fun List<CachedTimelineMessageEntity>.redactionsByIdentity(): Map<String, CachedTimelineMessageEntity> {
        return filter { it.isRedacted() }
            .flatMap { redacted -> redacted.identityIds().map { identity -> identity to redacted } }
            .toMap()
    }

    private fun List<CachedTimelineMessageEntity>.cachedMessagesByIdentity(): Map<String, CachedTimelineMessageEntity> {
        return flatMap { message ->
            message.identityIds().map { identity -> identity to message }
        }.toMap()
    }

    private fun List<CachedTimelineMessageEntity>.cachedReplyInfosByIdentity(): Map<String, MatrixReplyInfo> {
        return mapNotNull { message ->
            message.replyInfoOrNull()
                ?.takeIf { it.isInformative() }
                ?.let { replyInfo -> message.identityIds().map { identity -> identity to replyInfo } }
        }
            .flatten()
            .toMap()
    }

    private fun List<OutgoingEnvelopeEntity>.outgoingReplyInfosByIdentity(): Map<String, MatrixReplyInfo> {
        return mapNotNull { envelope ->
            envelope.replyInfoOrNull()
                ?.takeIf { it.isInformative() }
                ?.let { replyInfo -> envelope.identityIds().map { identity -> identity to replyInfo } }
        }
            .flatten()
            .toMap()
    }

    private fun List<CachedTimelineMessageEntity>.replyTargetInfosByIdentity(): Map<String, MatrixReplyInfo> {
        return flatMap { target ->
            target.identityIds().map { identity ->
                identity to target.toReplyTargetInfo(replyEventId = identity)
            }
        }.toMap()
    }

    private fun List<CachedTimelineMessageEntity>.preserveExistingTimelineState(
        existingMessagesByIdentity: Map<String, CachedTimelineMessageEntity>
    ): List<CachedTimelineMessageEntity> {
        if (existingMessagesByIdentity.isEmpty()) {
            return this
        }

        return map { incoming ->
            val existing = incoming.identityIds()
                .firstNotNullOfOrNull { identity -> existingMessagesByIdentity[identity] }
                ?: return@map incoming
            incoming.copy(
                body = if (existing.isEdited && !incoming.isEdited) existing.body else incoming.body,
                eventId = incoming.eventId ?: existing.eventId,
                transactionId = incoming.transactionId ?: existing.transactionId,
                senderDisplayName = incoming.senderDisplayName ?: existing.senderDisplayName,
                imageSourceJson = incoming.imageSourceJson ?: existing.imageSourceJson,
                imageThumbnailSourceJson = incoming.imageThumbnailSourceJson
                    ?: existing.imageThumbnailSourceJson,
                imageWidth = incoming.imageWidth ?: existing.imageWidth,
                imageHeight = incoming.imageHeight ?: existing.imageHeight,
                imageCaption = incoming.imageCaption ?: existing.imageCaption.normalizedMessageCaption(),
                imageMimeType = incoming.imageMimeType ?: existing.imageMimeType,
                imageBlurhash = incoming.imageBlurhash ?: existing.imageBlurhash,
                audioSourceJson = incoming.audioSourceJson ?: existing.audioSourceJson,
                audioFilename = incoming.audioFilename ?: existing.audioFilename,
                audioCaption = incoming.audioCaption ?: existing.audioCaption.normalizedMessageCaption(),
                audioMimeType = incoming.audioMimeType ?: existing.audioMimeType,
                audioSizeBytes = incoming.audioSizeBytes ?: existing.audioSizeBytes,
                audioDurationMillis = incoming.audioDurationMillis ?: existing.audioDurationMillis,
                audioWaveform = incoming.audioWaveform ?: existing.audioWaveform,
                audioIsVoice = incoming.audioIsVoice || existing.audioIsVoice,
                replyEventId = incoming.replyEventId ?: existing.replyEventId,
                replySenderId = incoming.replySenderId ?: existing.replySenderId,
                replySenderDisplayName = incoming.replySenderDisplayName
                    ?: existing.replySenderDisplayName,
                replyBody = incoming.replyBody ?: existing.replyBody,
                forwardedFrom = incoming.forwardedFrom ?: existing.forwardedFrom,
                zynaAttributesJson = incoming.zynaAttributesJson ?: existing.zynaAttributesJson,
                timelineDetailsJson = incoming.timelineDetailsJson
                    ?: existing.timelineDetailsJson,
                isEdited = incoming.isEdited || existing.isEdited,
                isEditPending = if (incoming.isEdited) false else existing.isEditPending,
                isEditFailed = if (incoming.isEdited) false else existing.isEditFailed,
                latestEditEventId = incoming.latestEditEventId ?: existing.latestEditEventId,
                editTransactionId = if (incoming.isEdited) null else existing.editTransactionId,
                pendingEditBody = if (incoming.isEdited) null else existing.pendingEditBody
            )
        }
    }

    private fun List<CachedTimelineMessageEntity>.enrichReplyInfos(
        existingReplyInfosByIdentity: Map<String, MatrixReplyInfo>,
        outgoingReplyInfosByIdentity: Map<String, MatrixReplyInfo>,
        replyTargetInfosByIdentity: Map<String, MatrixReplyInfo>
    ): List<CachedTimelineMessageEntity> {
        if (
            existingReplyInfosByIdentity.isEmpty() &&
            outgoingReplyInfosByIdentity.isEmpty() &&
            replyTargetInfosByIdentity.isEmpty()
        ) {
            return this
        }

        return map { message ->
            val replyEventId = message.replyEventId ?: return@map message
            if (message.replyInfoOrNull()?.isInformative() == true) {
                return@map message
            }

            val resolved = message.identityIds()
                .firstNotNullOfOrNull { identity ->
                    existingReplyInfosByIdentity[identity] ?: outgoingReplyInfosByIdentity[identity]
                }
                ?: replyTargetInfosByIdentity[replyEventId]?.copy(eventId = replyEventId)
                ?: return@map message

            message.copy(
                replySenderId = resolved.senderId,
                replySenderDisplayName = resolved.senderDisplayName,
                replyBody = resolved.body
            )
        }
    }

    private fun List<CachedTimelineMessageEntity>.preserveExistingRedactions(
        existingRedactionsByIdentity: Map<String, CachedTimelineMessageEntity>
    ): List<CachedTimelineMessageEntity> {
        if (existingRedactionsByIdentity.isEmpty()) {
            return this
        }

        return map { incoming ->
            if (incoming.isRedacted()) {
                incoming
            } else {
                val existingRedaction = incoming.identityIds()
                    .firstNotNullOfOrNull { identity -> existingRedactionsByIdentity[identity] }
                incoming.withPreservedRedaction(existingRedaction)
            }
        }
    }

    private fun CachedTimelineMessageEntity.withPreservedRedaction(
        existingRedaction: CachedTimelineMessageEntity?
    ): CachedTimelineMessageEntity {
        if (existingRedaction == null) {
            return this
        }

        return copy(
            eventId = eventId ?: existingRedaction.eventId,
            transactionId = transactionId ?: existingRedaction.transactionId,
            body = existingRedaction.body.takeIf { it.isNotBlank() } ?: REDACTED_MESSAGE_BODY,
            contentType = MatrixMessageContentType.REDACTED.name
        )
    }

    private fun CachedTimelineMessageEntity.isRedacted(): Boolean {
        return contentType == MatrixMessageContentType.REDACTED.name
    }

    private fun CachedTimelineMessageEntity.toReplyTargetInfo(replyEventId: String): MatrixReplyInfo {
        return MatrixReplyInfo(
            eventId = replyEventId,
            senderId = sender,
            senderDisplayName = if (isOwn) OWN_MESSAGE_PREVIEW_SENDER else null,
            body = if (isRedacted()) REDACTED_MESSAGE_BODY else body
        )
    }

    private fun OutgoingEnvelopeEntity.identityIds(): List<String> {
        return listOfNotNull(id, eventId, transactionId, "outgoing:$id")
    }

    private fun MatrixReplyInfo.isInformative(): Boolean {
        return senderId.isNotBlank() ||
            senderDisplayName?.isNotBlank() == true ||
            body.isNotBlank()
    }

    private fun String?.toLastOwnMessageStatusOrNull(): MatrixLastOwnMessageStatus? {
        return this?.let {
            runCatching { MatrixLastOwnMessageStatus.valueOf(it) }.getOrNull()
        }
    }

    private fun String.toOutgoingTransportState(): OutgoingTransportState {
        return runCatching { OutgoingTransportState.valueOf(this) }
            .getOrDefault(OutgoingTransportState.FAILED)
    }

    private fun OutgoingTransportState.toOutgoingDeliveryState(): MatrixMessageDeliveryState {
        return when (this) {
            OutgoingTransportState.QUEUED,
            OutgoingTransportState.SENDING,
            OutgoingTransportState.RETRYING -> MatrixMessageDeliveryState.SENDING
            OutgoingTransportState.SENT,
            OutgoingTransportState.RETIRED -> MatrixMessageDeliveryState.SENT
            OutgoingTransportState.FAILED -> MatrixMessageDeliveryState.FAILED
        }
    }

    private fun PendingReactionEntity.toOutgoingReactionEnvelopeOrNull(): OutgoingReactionEnvelope? {
        val decodedState = decodedState()
        when (decodedState) {
            PendingReactionState.ADD_QUEUED -> {
                if (transactionId.isNullOrBlank()) return null
            }
            PendingReactionState.REMOVE_QUEUED -> {
                if (
                    redactionTransactionId.isNullOrBlank() ||
                    (reactionEventId.isNullOrBlank() && transactionId.isNullOrBlank())
                ) {
                    return null
                }
            }
            PendingReactionState.ADD_AFTER_REMOVE_QUEUED -> {
                if (
                    transactionId.isNullOrBlank() ||
                    reactionEventId.isNullOrBlank() ||
                    redactionTransactionId.isNullOrBlank()
                ) {
                    return null
                }
            }
            PendingReactionState.ADD_ACCEPTED,
            PendingReactionState.REMOVED,
            PendingReactionState.FAILED -> return null
        }

        return OutgoingReactionEnvelope(
            userId = userId,
            roomId = roomId,
            id = id,
            state = decodedState,
            targetEventId = targetEventId,
            reactionKey = reactionKey,
            transactionId = transactionId,
            reactionEventId = reactionEventId,
            redactionTransactionId = redactionTransactionId,
            failureMessage = failureMessage
        )
    }

    private fun PendingReactionEntity.decodedState(): PendingReactionState {
        return runCatching { PendingReactionState.valueOf(state) }
            .getOrDefault(PendingReactionState.FAILED)
    }

    private suspend fun latestRetainedReaction(
        userId: String,
        roomId: String,
        targetEventId: String,
        reactionKey: String,
        nowMillis: Long
    ): PendingReactionEntity? {
        val existing = pendingReactionDao.latestReaction(
            userId = userId,
            roomId = roomId,
            targetEventId = targetEventId,
            reactionKey = reactionKey
        ) ?: return null
        if (!existing.isExpiredTerminal(nowMillis)) {
            return existing
        }
        pendingReactionDao.deleteReaction(
            userId = existing.userId,
            roomId = existing.roomId,
            id = existing.id
        )
        return null
    }

    private fun PendingReactionEntity.isExpiredTerminal(nowMillis: Long): Boolean {
        return when (decodedState()) {
            PendingReactionState.ADD_ACCEPTED,
            PendingReactionState.REMOVED -> nowMillis - updatedAtMillis >
                TERMINAL_REACTION_OVERLAY_TTL_MS
            PendingReactionState.FAILED -> true
            PendingReactionState.ADD_QUEUED,
            PendingReactionState.ADD_AFTER_REMOVE_QUEUED,
            PendingReactionState.REMOVE_QUEUED -> false
        }
    }

    private fun PendingReactionEntity.hasAttemptStarted(): Boolean {
        return lastAttemptAtMillis != null || attemptCount > 0
    }

    private fun PendingReactionEntity.matchesReactionCandidate(
        candidate: OutgoingReactionEnvelope
    ): Boolean {
        return when (candidate.state) {
            PendingReactionState.ADD_QUEUED -> transactionId == candidate.transactionId &&
                decodedState() in setOf(
                    PendingReactionState.ADD_QUEUED,
                    PendingReactionState.REMOVE_QUEUED
                )
            PendingReactionState.REMOVE_QUEUED -> {
                val currentState = decodedState()
                if (candidate.reactionEventId.isNullOrBlank()) {
                    transactionId == candidate.transactionId &&
                        currentState in setOf(
                            PendingReactionState.ADD_QUEUED,
                            PendingReactionState.REMOVE_QUEUED
                        )
                } else {
                    redactionTransactionId == candidate.redactionTransactionId &&
                        currentState in setOf(
                            PendingReactionState.REMOVE_QUEUED,
                            PendingReactionState.ADD_AFTER_REMOVE_QUEUED
                        )
                }
            }
            PendingReactionState.ADD_AFTER_REMOVE_QUEUED ->
                redactionTransactionId == candidate.redactionTransactionId &&
                    decodedState() in setOf(
                        PendingReactionState.ADD_AFTER_REMOVE_QUEUED,
                        PendingReactionState.REMOVE_QUEUED
                    )
            PendingReactionState.ADD_ACCEPTED,
            PendingReactionState.REMOVED,
            PendingReactionState.FAILED -> false
        }
    }

    private fun PendingReactionState.isReactionOutboxState(): Boolean {
        return this == PendingReactionState.ADD_QUEUED ||
            this == PendingReactionState.REMOVE_QUEUED ||
            this == PendingReactionState.ADD_AFTER_REMOVE_QUEUED
    }

    private fun List<MatrixChatMessage>.applyPendingReactions(
        pendingReactions: List<PendingReactionEntity>,
        currentUserId: String
    ): List<MatrixChatMessage> {
        if (isEmpty() || pendingReactions.isEmpty()) {
            return this
        }
        val now = System.currentTimeMillis()
        val latestByTargetAndKey = pendingReactions
            .filter { it.targetEventId.isNotBlank() && it.reactionKey.isNotBlank() }
            .filterNot { it.isExpiredTerminal(now) }
            .groupBy { "${it.targetEventId}\u001F${it.reactionKey}" }
            .mapValues { (_, records) -> records.maxBy { it.updatedAtMillis } }
        if (latestByTargetAndKey.isEmpty()) {
            return this
        }

        return map { message ->
            val eventId = message.eventId ?: return@map message
            val relevant = latestByTargetAndKey.values.filter { it.targetEventId == eventId }
            if (relevant.isEmpty()) {
                return@map message
            }
            relevant.fold(message) { current, pending ->
                current.applyPendingReaction(pending, currentUserId)
            }
        }
    }

    private fun MatrixChatMessage.applyPendingReaction(
        pendingReaction: PendingReactionEntity,
        currentUserId: String
    ): MatrixChatMessage {
        return when (pendingReaction.decodedState()) {
            PendingReactionState.ADD_QUEUED,
            PendingReactionState.ADD_AFTER_REMOVE_QUEUED,
            PendingReactionState.ADD_ACCEPTED -> copy(
                reactions = reactions.upsertOwnReaction(
                    key = pendingReaction.reactionKey,
                    currentUserId = currentUserId,
                    timestampMillis = pendingReaction.updatedAtMillis,
                    isPendingRemoval = false
                )
            )
            PendingReactionState.REMOVE_QUEUED,
            PendingReactionState.REMOVED -> copy(
                reactions = reactions.markOwnReactionPendingRemoval(pendingReaction.reactionKey)
            )
            PendingReactionState.FAILED -> this
        }
    }

    private fun List<MatrixMessageReaction>.upsertOwnReaction(
        key: String,
        currentUserId: String,
        timestampMillis: Long,
        isPendingRemoval: Boolean
    ): List<MatrixMessageReaction> {
        val ownSender = MatrixReactionSender(
            userId = currentUserId,
            timestampMillis = timestampMillis
        )
        var didUpdate = false
        val updated = map { reaction ->
            if (reaction.key != key) {
                return@map reaction
            }
            didUpdate = true
            reaction.copy(
                senders = (listOf(ownSender) + reaction.senders.filter { it.userId != currentUserId })
                    .sortedByDescending { it.timestampMillis },
                isOwn = true,
                isPendingRemoval = isPendingRemoval,
                legacyCount = null
            )
        }
        val result = if (didUpdate) {
            updated
        } else {
            updated + MatrixMessageReaction(
                key = key,
                senders = listOf(ownSender),
                isOwn = true,
                isPendingRemoval = isPendingRemoval
            )
        }
        return result.sortedReactions()
    }

    private fun List<MatrixMessageReaction>.markOwnReactionPendingRemoval(
        key: String
    ): List<MatrixMessageReaction> {
        var didUpdate = false
        val updated = map { reaction ->
            if (reaction.key == key && reaction.isOwn && !reaction.isPendingRemoval) {
                didUpdate = true
                reaction.copy(isPendingRemoval = true)
            } else {
                reaction
            }
        }
        return if (didUpdate) updated.sortedReactions() else this
    }

    private fun List<MatrixMessageReaction>.sortedReactions(): List<MatrixMessageReaction> {
        return filter { it.key.isNotBlank() && it.count > 0 }
            .sortedWith(
                compareByDescending<MatrixMessageReaction> { it.count }
                    .thenByDescending { it.senders.firstOrNull()?.timestampMillis ?: 0L }
                    .thenBy { it.key }
            )
    }

    private fun List<MatrixMessageReaction>.encodeReactions(): String {
        if (isEmpty()) {
            return "[]"
        }
        return JSONArray().also { root ->
            forEach { reaction ->
                root.put(
                    JSONObject()
                        .put("key", reaction.key)
                        .put("isOwn", reaction.isOwn)
                        .put("legacyCount", reaction.legacyCount)
                        .put(
                            "senders",
                            JSONArray().also { senders ->
                                reaction.senders.forEach { sender ->
                                    senders.put(
                                        JSONObject()
                                            .put("userId", sender.userId)
                                            .put("timestampMillis", sender.timestampMillis)
                                    )
                                }
                            }
                        )
                )
            }
        }.toString()
    }

    private fun String?.decodeReactions(): List<MatrixMessageReaction> {
        if (isNullOrBlank()) {
            return emptyList()
        }
        return runCatching {
            val root = JSONArray(this)
            buildList {
                for (index in 0 until root.length()) {
                    val item = root.optJSONObject(index) ?: continue
                    val key = item.optString("key").takeIf { it.isNotBlank() } ?: continue
                    val sendersJson = item.optJSONArray("senders") ?: JSONArray()
                    val senders = buildList {
                        for (senderIndex in 0 until sendersJson.length()) {
                            val sender = sendersJson.optJSONObject(senderIndex) ?: continue
                            val userId = sender.optString("userId").takeIf { it.isNotBlank() }
                                ?: continue
                            add(
                                MatrixReactionSender(
                                    userId = userId,
                                    timestampMillis = sender.optLong("timestampMillis", 0L)
                                )
                            )
                        }
                    }
                    add(
                        MatrixMessageReaction(
                            key = key,
                            senders = senders.sortedByDescending { it.timestampMillis },
                            isOwn = item.optBoolean("isOwn", false),
                            legacyCount = item.optNullableInt("legacyCount")
                        )
                    )
                }
            }.sortedReactions()
        }.getOrDefault(emptyList())
    }

    private fun JSONObject.optNullableInt(name: String): Int? {
        return if (has(name) && !isNull(name)) optInt(name) else null
    }

    private fun List<Float>?.toWaveformCacheString(): String? {
        if (isNullOrEmpty()) {
            return null
        }
        return joinToString(separator = ",") { sample ->
            (sample.coerceIn(0f, 1f) * WAVEFORM_CACHE_SCALE).roundToInt().toString()
        }
    }

    private fun String?.toWaveformList(): List<Float> {
        if (isNullOrBlank()) {
            return emptyList()
        }
        return split(',')
            .mapNotNull { token ->
                token.toIntOrNull()
                    ?.coerceIn(0, WAVEFORM_CACHE_SCALE)
                    ?.let { it.toFloat() / WAVEFORM_CACHE_SCALE.toFloat() }
            }
    }

    private companion object {
        val CachedRoomComparator = compareBy<CachedRoomEntity> { it.listPosition == null }
            .thenBy { it.listPosition ?: Long.MAX_VALUE }
            .thenByDescending { it.lastMessageAtMillis }
            .thenBy { it.displayName.lowercase(Locale.ROOT) }
            .thenBy { it.id }

        const val LOCAL_MESSAGE_ID_PREFIX = "local:"
        const val LOCAL_MESSAGE_ID_PATTERN = "$LOCAL_MESSAGE_ID_PREFIX%"
        const val OWN_MESSAGE_PREVIEW_SENDER = "You"
        const val ROOM_PREVIEW_CANDIDATE_LIMIT = 64
        const val ROOM_DELETE_CHUNK_SIZE = 250
        const val ROOM_LOOKUP_CHUNK_SIZE = 250
        const val ROOM_POSITION_UPDATE_CHUNK_SIZE = 250
        const val RESOLVED_ROOM_ABSENT_SNAPSHOT_GRACE_COUNT = 1
        const val PENDING_RESOLVED_ROOM_RETENTION_MILLIS = 2 * 60 * 1000L
        const val REDACTION_ID_QUERY_CHUNK_SIZE = 250
        const val DEDUPE_TIMESTAMP_TOLERANCE_MS = 50L
        const val REDACTED_MESSAGE_BODY = "Deleted message"
        const val VOICE_MESSAGE_BODY = "Voice message"
        const val WAVEFORM_CACHE_SCALE = 1000
        const val TERMINAL_REACTION_OVERLAY_TTL_MS = 30 * 1000L
        const val CALL_HISTORY_EXPIRED_REFRESH_LIMIT = 100
        const val CALL_HISTORY_ROOM_REFRESH_LIMIT = 100
        const val CALL_TIMELINE_BACKFILL_BATCH_LIMIT = 100
        const val DEFAULT_CALL_NOTIFICATION_LIFETIME_MS = 30_000L
    }
}
