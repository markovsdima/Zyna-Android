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
        ORDER BY timestampMillis ASC, id ASC
        """
    )
    fun observeRoomMessages(userId: String, roomId: String): Flow<List<CachedTimelineMessageEntity>>

    @Query("DELETE FROM timeline_messages WHERE userId = :userId AND roomId = :roomId")
    suspend fun clearRoomMessages(userId: String, roomId: String)

    @Query(
        """
        DELETE FROM timeline_messages
        WHERE userId = :userId
            AND roomId = :roomId
            AND (
                id IN (:ids)
                OR eventId IN (:ids)
                OR transactionId IN (:ids)
            )
        """
    )
    suspend fun deleteMessagesByIds(userId: String, roomId: String, ids: List<String>)

    @Query(
        """
        UPDATE timeline_messages
        SET body = :body,
            contentType = :contentType,
            deliveryState = :deliveryState,
            updatedAtMillis = :updatedAtMillis
        WHERE userId = :userId
            AND roomId = :roomId
            AND (
                id IN (:ids)
                OR eventId IN (:ids)
                OR transactionId IN (:ids)
            )
        """
    )
    suspend fun updateMessagesByIds(
        userId: String,
        roomId: String,
        ids: List<String>,
        body: String,
        contentType: String,
        deliveryState: String,
        updatedAtMillis: Long
    ): Int

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM timeline_messages
            WHERE userId = :userId
                AND roomId = :roomId
                AND (
                    id = :id
                    OR eventId = :id
                    OR transactionId = :id
                )
        )
        """
    )
    suspend fun hasMessage(userId: String, roomId: String, id: String): Boolean

    @Query(
        """
        SELECT * FROM timeline_messages
        WHERE userId = :userId
            AND roomId = :roomId
            AND contentType = :contentType
            AND (
                id IN (:ids)
                OR eventId IN (:ids)
                OR transactionId IN (:ids)
            )
        """
    )
    suspend fun messagesMatchingIdsWithContentType(
        userId: String,
        roomId: String,
        ids: List<String>,
        contentType: String
    ): List<CachedTimelineMessageEntity>

    @Query(
        """
        SELECT * FROM timeline_messages
        WHERE userId = :userId
            AND roomId = :roomId
            AND id NOT LIKE :localIdPattern
        ORDER BY timestampMillis DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun latestRoomMessages(
        userId: String,
        roomId: String,
        localIdPattern: String,
        limit: Int
    ): List<CachedTimelineMessageEntity>

    @Query("DELETE FROM timeline_messages WHERE userId = :userId")
    suspend fun clearMessages(userId: String)

    @Query("DELETE FROM timeline_messages")
    suspend fun clearAllMessages()

    @Upsert
    suspend fun upsertMessages(messages: List<CachedTimelineMessageEntity>)
}
