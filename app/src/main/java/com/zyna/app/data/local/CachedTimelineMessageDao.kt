package com.zyna.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CachedTimelineMessageDao {
    @Query(
        """
        SELECT * FROM timeline_messages
        WHERE userId = :userId AND roomId = :roomId
        ORDER BY timelineIndex ASC
        """
    )
    fun observeRoomMessages(userId: String, roomId: String): Flow<List<CachedTimelineMessageEntity>>

    @Query("DELETE FROM timeline_messages WHERE userId = :userId AND roomId = :roomId")
    suspend fun clearRoomMessages(userId: String, roomId: String)

    @Query("DELETE FROM timeline_messages WHERE userId = :userId")
    suspend fun clearMessages(userId: String)

    @Query("DELETE FROM timeline_messages")
    suspend fun clearAllMessages()

    @Upsert
    suspend fun upsertMessages(messages: List<CachedTimelineMessageEntity>)
}
