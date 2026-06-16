package com.zyna.app.data.local

import androidx.room.withTransaction
import com.zyna.app.data.matrix.MatrixChatMessage
import com.zyna.app.data.matrix.MatrixRoomSummary
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LocalCacheRepository(
    private val database: ZynaDatabase
) {
    private val roomDao = database.cachedRoomDao()
    private val messageDao = database.cachedTimelineMessageDao()

    fun observeRooms(userId: String): Flow<List<MatrixRoomSummary>> {
        return roomDao.observeRooms(userId).map { rooms ->
            rooms.map { it.toRoomSummary() }
                .sortedBy { it.displayName.lowercase(Locale.ROOT) }
        }
    }

    suspend fun cacheRoomsSnapshot(userId: String, rooms: List<MatrixRoomSummary>) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            roomDao.clearRooms(userId)
            roomDao.upsertRooms(
                rooms.map { room ->
                    CachedRoomEntity(
                        userId = userId,
                        id = room.id,
                        displayName = room.displayName,
                        avatarUrl = room.avatarUrl,
                        updatedAtMillis = now
                    )
                }
            )
        }
    }

    fun observeRoomTimeline(userId: String, roomId: String): Flow<List<MatrixChatMessage>> {
        return messageDao.observeRoomMessages(userId, roomId).map { messages ->
            messages.map { it.toChatMessage() }
        }
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

        messageDao.upsertMessages(
            messages.mapIndexed { index, message ->
                CachedTimelineMessageEntity(
                    userId = userId,
                    roomId = roomId,
                    id = message.id,
                    timelineIndex = index,
                    sender = message.sender,
                    body = message.body,
                    timestampMillis = message.timestampMillis,
                    isOwn = message.isOwn,
                    updatedAtMillis = now
                )
            }
        )
    }

    suspend fun clearAll() {
        database.withTransaction {
            messageDao.clearAllMessages()
            roomDao.clearAllRooms()
        }
    }

    private fun CachedRoomEntity.toRoomSummary(): MatrixRoomSummary {
        return MatrixRoomSummary(
            id = id,
            displayName = displayName,
            avatarUrl = avatarUrl
        )
    }

    private fun CachedTimelineMessageEntity.toChatMessage(): MatrixChatMessage {
        return MatrixChatMessage(
            id = id,
            sender = sender,
            body = body,
            timestampMillis = timestampMillis,
            isOwn = isOwn
        )
    }
}
