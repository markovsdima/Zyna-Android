package com.zyna.app.data.timeline

import com.zyna.app.data.local.LocalCacheRepository
import com.zyna.app.data.local.TimelineFlushSummary
import com.zyna.app.data.local.TimelineWindowChangeOrigin
import com.zyna.app.data.local.TimelineWindowAnchor
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
    private val anchor = MutableStateFlow<TimelineWindowAnchor?>(null)
    private var newestAnchor: TimelineWindowAnchor? = null
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
            anchorFlow = anchor,
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

    val isAtLiveEdge: Boolean
        get() = !hasNewerInDb

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
        applySnapshotState(snapshot)
        didFillInitialWindow = snapshot.messages.size >= initialLimit
        return snapshot.messages
    }

    suspend fun refreshInitialWindowFromCacheIfNeeded(
        flushSummary: TimelineFlushSummary? = null
    ): Boolean {
        if (didExpandOlder || didFillInitialWindow) {
            return false
        }

        val snapshot = localCacheRepository.latestRoomTimelineWindowSnapshot(
            userId = userId,
            roomId = roomId,
            limit = initialLimit
        )
        val initialAnchor = snapshot.anchor ?: return false
        val previousAnchor = anchor.value

        applySnapshotState(snapshot)
        didFillInitialWindow = snapshot.messages.size >= initialLimit
        if (previousAnchor != initialAnchor) {
            pendingOrigin = TimelineWindowChangeOrigin.TIMELINE_FLUSH
            pendingFlushSummary = flushSummary
        }
        return previousAnchor != initialAnchor
    }

    suspend fun expandOlderFromCache(): Boolean {
        val currentAnchor = anchor.value ?: initializeAnchorFromCache()
            ?: return false

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

        anchor.value = olderAnchor
        pendingOrigin = TimelineWindowChangeOrigin.DATABASE_PAGINATION
        hasOlderInDb = localCacheRepository.hasOlderRoomTimelineMessages(
            userId = userId,
            roomId = roomId,
            anchor = olderAnchor
        )
        newestAnchor?.let { newest ->
            hasNewerInDb = localCacheRepository.hasNewerRoomTimelineMessages(
                userId = userId,
                roomId = roomId,
                anchor = newest
            )
        }
        didExpandOlder = true
        return true
    }

    suspend fun expandOlderFromCacheAfterMaterialization(
        timeoutMillis: Long = MATERIALIZATION_TIMEOUT_MS
    ): Boolean {
        if (expandOlderFromCache()) {
            return true
        }

        val currentAnchor = anchor.value ?: return false
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

    private suspend fun initializeAnchorFromCache(): TimelineWindowAnchor? {
        val snapshot = localCacheRepository.latestRoomTimelineWindowSnapshot(
            userId = userId,
            roomId = roomId,
            limit = initialLimit
        )
        applySnapshotState(snapshot)
        didFillInitialWindow = snapshot.messages.size >= initialLimit
        return snapshot.anchor
    }

    private fun applySnapshotState(snapshot: TimelineWindowSnapshot<MatrixChatMessage>) {
        anchor.value = snapshot.anchor
        newestAnchor = snapshot.newestAnchor
        hasOlderInDb = snapshot.hasOlderInDb
        hasNewerInDb = snapshot.hasNewerInDb
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
