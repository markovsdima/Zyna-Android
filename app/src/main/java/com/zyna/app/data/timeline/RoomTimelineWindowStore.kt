package com.zyna.app.data.timeline

import com.zyna.app.data.local.LocalCacheRepository
import com.zyna.app.data.local.TimelineFlushSummary
import com.zyna.app.data.local.TimelineWindowChangeOrigin
import com.zyna.app.data.local.TimelineWindowBounds
import com.zyna.app.data.local.TimelineWindowSnapshot
import com.zyna.app.data.local.TimelineWindowUpdate
import com.zyna.app.data.matrix.MatrixChatMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

class RoomTimelineWindowStore(
    private val userId: String,
    private val roomId: String,
    private val localCacheRepository: LocalCacheRepository,
    private val initialLimit: Int = INITIAL_MESSAGE_LIMIT,
    private val pageSize: Int = OLDER_PAGE_SIZE
) {
    private val bounds = MutableStateFlow<TimelineWindowBounds?>(null)
    private var hasOlderInDb = false
    private var hasNewerInDb = false
    private var didExpandOlder = false
    private var didFillInitialWindow = false
    private var pendingOrigin = TimelineWindowChangeOrigin.INITIAL_LOAD
    private var pendingFlushSummary: TimelineFlushSummary? = null

    val messages: Flow<TimelineWindowUpdate<MatrixChatMessage>> =
        localCacheRepository.observeRoomTimelineWindow(
            userId = userId,
            roomId = roomId,
            boundsFlow = bounds,
            initialLimit = initialLimit
        ).map { messages ->
            TimelineWindowUpdate(
                messages = messages,
                origin = consumePendingOrigin(),
                hasOlderInDb = hasOlderInDb,
                hasNewerInDb = hasNewerInDb,
                flushSummary = consumePendingFlushSummary()
            )
        }

    fun matches(userId: String, roomId: String): Boolean {
        return this.userId == userId && this.roomId == roomId
    }

    val canLoadOlderFromCache: Boolean
        get() = hasOlderInDb

    val canLoadNewerFromCache: Boolean
        get() = hasNewerInDb

    val isAtLiveEdge: Boolean
        get() = bounds.value?.newestAnchor == null

    fun recordTimelineFlush(summary: TimelineFlushSummary) {
        pendingOrigin = TimelineWindowChangeOrigin.TIMELINE_FLUSH
        pendingFlushSummary = summary
    }

    suspend fun initialMessagesSnapshot(): List<MatrixChatMessage> {
        val snapshot = localCacheRepository.latestRoomTimelineWindowSnapshot(
            userId = userId,
            roomId = roomId,
            limit = initialLimit
        )
        applySnapshotState(snapshot, keepLiveEdge = true)
        didFillInitialWindow = snapshot.messages.size >= initialLimit
        return snapshot.messages
    }

    suspend fun refreshInitialWindowFromCacheIfNeeded(
        flushSummary: TimelineFlushSummary? = null
    ): Boolean {
        if (didExpandOlder || didFillInitialWindow || bounds.value?.newestAnchor != null) {
            return false
        }

        val snapshot = localCacheRepository.latestRoomTimelineWindowSnapshot(
            userId = userId,
            roomId = roomId,
            limit = initialLimit
        )
        val initialAnchor = snapshot.anchor ?: return false
        val previousAnchor = bounds.value?.oldestAnchor
        val didMoveAnchor = previousAnchor != initialAnchor

        if (didMoveAnchor) {
            pendingOrigin = TimelineWindowChangeOrigin.TIMELINE_FLUSH
            pendingFlushSummary = flushSummary
        }
        applySnapshotState(snapshot, keepLiveEdge = true)
        didFillInitialWindow = snapshot.messages.size >= initialLimit
        return didMoveAnchor
    }

    suspend fun expandOlderFromCache(): Boolean {
        val currentBounds = bounds.value ?: initializeBoundsFromCache()
            ?: return false
        val currentAnchor = currentBounds.oldestAnchor ?: return false

        val olderAnchor = localCacheRepository.olderRoomTimelineWindowAnchor(
            userId = userId,
            roomId = roomId,
            anchor = currentAnchor,
            limit = pageSize
        )
        if (olderAnchor == null) {
            hasOlderInDb = false
            return false
        }

        val nextHasOlderInDb = localCacheRepository.hasOlderRoomTimelineMessages(
            userId = userId,
            roomId = roomId,
            anchor = olderAnchor
        )
        val nextHasNewerInDb = currentBounds.newestAnchor?.let { newest ->
            localCacheRepository.hasNewerRoomTimelineMessages(
                userId = userId,
                roomId = roomId,
                anchor = newest
            )
        } ?: false
        pendingOrigin = TimelineWindowChangeOrigin.DATABASE_PAGINATION
        hasOlderInDb = nextHasOlderInDb
        hasNewerInDb = nextHasNewerInDb
        bounds.value = currentBounds.copy(oldestAnchor = olderAnchor)
        didExpandOlder = true
        return true
    }

    suspend fun expandNewerFromCache(): Boolean {
        val currentBounds = bounds.value ?: initializeBoundsFromCache()
            ?: return false
        val currentAnchor = currentBounds.newestAnchor ?: return false

        val newerAnchor = localCacheRepository.newerRoomTimelineWindowAnchor(
            userId = userId,
            roomId = roomId,
            anchor = currentAnchor,
            limit = pageSize
        )
        if (newerAnchor == null) {
            hasNewerInDb = false
            return false
        }

        val nextHasNewerInDb = localCacheRepository.hasNewerRoomTimelineMessages(
            userId = userId,
            roomId = roomId,
            anchor = newerAnchor
        )
        val nextHasOlderInDb = currentBounds.oldestAnchor?.let { oldest ->
            localCacheRepository.hasOlderRoomTimelineMessages(
                userId = userId,
                roomId = roomId,
                anchor = oldest
            )
        } ?: false
        pendingOrigin = TimelineWindowChangeOrigin.DATABASE_PAGINATION
        hasOlderInDb = nextHasOlderInDb
        hasNewerInDb = nextHasNewerInDb
        bounds.value = currentBounds.copy(
            newestAnchor = if (nextHasNewerInDb) newerAnchor else null
        )
        return true
    }

    suspend fun expandOlderFromCacheAfterMaterialization(
        timeoutMillis: Long = MATERIALIZATION_TIMEOUT_MS
    ): Boolean {
        if (expandOlderFromCache()) {
            return true
        }

        val currentAnchor = bounds.value?.oldestAnchor ?: return false
        return withTimeoutOrNull(timeoutMillis) {
            while (true) {
                delay(MATERIALIZATION_POLL_INTERVAL_MS)
                if (
                    localCacheRepository.hasOlderRoomTimelineMessages(
                        userId = userId,
                        roomId = roomId,
                        anchor = currentAnchor
                    ) && expandOlderFromCache()
                ) {
                    return@withTimeoutOrNull true
                }
            }

            false
        } == true
    }

    suspend fun expandNewerFromCacheAfterMaterialization(
        timeoutMillis: Long = MATERIALIZATION_TIMEOUT_MS
    ): Boolean {
        if (expandNewerFromCache()) {
            return true
        }

        val currentAnchor = bounds.value?.newestAnchor ?: return false
        return withTimeoutOrNull(timeoutMillis) {
            while (true) {
                delay(MATERIALIZATION_POLL_INTERVAL_MS)
                if (
                    localCacheRepository.hasNewerRoomTimelineMessages(
                        userId = userId,
                        roomId = roomId,
                        anchor = currentAnchor
                    ) && expandNewerFromCache()
                ) {
                    return@withTimeoutOrNull true
                }
            }

            false
        } == true
    }

    fun markNewerFullyLoaded() {
        val currentBounds = bounds.value ?: return
        if (currentBounds.newestAnchor == null && !hasNewerInDb) {
            return
        }
        pendingOrigin = TimelineWindowChangeOrigin.DATABASE_PAGINATION
        hasNewerInDb = false
        bounds.value = currentBounds.copy(newestAnchor = null)
    }

    suspend fun jumpToEvent(eventId: String): Boolean {
        val snapshot = localCacheRepository.roomTimelineWindowAroundEvent(
            userId = userId,
            roomId = roomId,
            eventId = eventId,
            limit = initialLimit
        ) ?: return false

        pendingOrigin = TimelineWindowChangeOrigin.JUMP
        pendingFlushSummary = null
        applySnapshotState(snapshot, keepLiveEdge = !snapshot.hasNewerInDb)
        didExpandOlder = false
        didFillInitialWindow = true
        return true
    }

    suspend fun jumpToEventAfterMaterialization(
        eventId: String,
        timeoutMillis: Long = MATERIALIZATION_TIMEOUT_MS
    ): Boolean {
        if (jumpToEvent(eventId)) {
            return true
        }

        return withTimeoutOrNull(timeoutMillis) {
            while (true) {
                delay(MATERIALIZATION_POLL_INTERVAL_MS)
                if (jumpToEvent(eventId)) {
                    return@withTimeoutOrNull true
                }
            }

            false
        } == true
    }

    private suspend fun initializeBoundsFromCache(): TimelineWindowBounds? {
        val snapshot = localCacheRepository.latestRoomTimelineWindowSnapshot(
            userId = userId,
            roomId = roomId,
            limit = initialLimit
        )
        applySnapshotState(snapshot, keepLiveEdge = true)
        didFillInitialWindow = snapshot.messages.size >= initialLimit
        return bounds.value
    }

    private fun applySnapshotState(
        snapshot: TimelineWindowSnapshot<MatrixChatMessage>,
        keepLiveEdge: Boolean
    ) {
        hasOlderInDb = snapshot.hasOlderInDb
        hasNewerInDb = !keepLiveEdge && snapshot.hasNewerInDb
        bounds.value = TimelineWindowBounds(
            oldestAnchor = snapshot.anchor,
            newestAnchor = if (keepLiveEdge) null else snapshot.newestAnchor
        )
    }

    private fun consumePendingOrigin(): TimelineWindowChangeOrigin {
        val origin = pendingOrigin
        pendingOrigin = TimelineWindowChangeOrigin.TIMELINE_FLUSH
        return origin
    }

    private fun consumePendingFlushSummary(): TimelineFlushSummary? {
        val summary = pendingFlushSummary
        pendingFlushSummary = null
        return summary
    }

    private companion object {
        const val INITIAL_MESSAGE_LIMIT = 200
        const val OLDER_PAGE_SIZE = 50
        const val MATERIALIZATION_TIMEOUT_MS = 2_000L
        const val MATERIALIZATION_POLL_INTERVAL_MS = 50L
    }
}
