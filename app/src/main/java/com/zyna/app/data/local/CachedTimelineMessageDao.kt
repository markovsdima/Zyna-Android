package com.zyna.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CachedTimelineMessageDao {
    @Query(
        """
        SELECT * FROM (
            SELECT * FROM timeline_messages
            WHERE userId = :userId
                AND roomId = :roomId
                AND id NOT LIKE :localIdPattern
            ORDER BY timestampMillis DESC, id DESC
            LIMIT :limit
        )
        ORDER BY timestampMillis ASC, id ASC
        """
    )
    fun observeLatestRoomMessagesWindow(
        userId: String,
        roomId: String,
        localIdPattern: String,
        limit: Int
    ): Flow<List<CachedTimelineMessageEntity>>

    @Query(
        """
        SELECT * FROM timeline_messages
        WHERE userId = :userId
            AND roomId = :roomId
            AND id NOT LIKE :localIdPattern
            AND (
                timestampMillis > :fromTimestampMillis
                OR (
                    timestampMillis = :fromTimestampMillis
                    AND id >= :fromId
                )
            )
        ORDER BY timestampMillis ASC, id ASC
        """
    )
    fun observeRoomMessagesFrom(
        userId: String,
        roomId: String,
        localIdPattern: String,
        fromTimestampMillis: Long,
        fromId: String
    ): Flow<List<CachedTimelineMessageEntity>>

    @Query(
        """
        SELECT * FROM (
            SELECT * FROM timeline_messages
            WHERE userId = :userId
                AND roomId = :roomId
                AND id NOT LIKE :localIdPattern
            ORDER BY timestampMillis DESC, id DESC
            LIMIT :limit
        )
        ORDER BY timestampMillis ASC, id ASC
        """
    )
    suspend fun latestRoomMessagesWindow(
        userId: String,
        roomId: String,
        localIdPattern: String,
        limit: Int
    ): List<CachedTimelineMessageEntity>

    @Query(
        """
        SELECT * FROM timeline_messages
        WHERE userId = :userId
            AND roomId = :roomId
            AND id NOT LIKE :localIdPattern
            AND (
                timestampMillis < :beforeTimestampMillis
                OR (
                    timestampMillis = :beforeTimestampMillis
                    AND id < :beforeId
                )
            )
        ORDER BY timestampMillis DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun roomMessagesBefore(
        userId: String,
        roomId: String,
        localIdPattern: String,
        beforeTimestampMillis: Long,
        beforeId: String,
        limit: Int
    ): List<CachedTimelineMessageEntity>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM timeline_messages
            WHERE userId = :userId
                AND roomId = :roomId
                AND id NOT LIKE :localIdPattern
                AND (
                    timestampMillis < :beforeTimestampMillis
                    OR (
                        timestampMillis = :beforeTimestampMillis
                        AND id < :beforeId
                    )
                )
            LIMIT 1
        )
        """
    )
    suspend fun hasRoomMessagesBefore(
        userId: String,
        roomId: String,
        localIdPattern: String,
        beforeTimestampMillis: Long,
        beforeId: String
    ): Boolean

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM timeline_messages
            WHERE userId = :userId
                AND roomId = :roomId
                AND id NOT LIKE :localIdPattern
                AND (
                    timestampMillis > :afterTimestampMillis
                    OR (
                        timestampMillis = :afterTimestampMillis
                        AND id > :afterId
                    )
                )
            LIMIT 1
        )
        """
    )
    suspend fun hasRoomMessagesAfter(
        userId: String,
        roomId: String,
        localIdPattern: String,
        afterTimestampMillis: Long,
        afterId: String
    ): Boolean

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
        DELETE FROM timeline_messages
        WHERE userId = :userId
            AND roomId = :roomId
            AND id != :keepId
            AND eventId = :eventId
            AND sender = :sender
            AND (
                ABS(timestampMillis - :timestampMillis) <= :timestampToleranceMillis
                OR (
                    contentType = :contentType
                    AND body = :body
                )
            )
        """
    )
    suspend fun deleteSafeEventDuplicates(
        userId: String,
        roomId: String,
        keepId: String,
        eventId: String,
        sender: String,
        timestampMillis: Long,
        timestampToleranceMillis: Long,
        contentType: String,
        body: String
    ): Int

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
            AND (
                id IN (:ids)
                OR eventId IN (:ids)
                OR transactionId IN (:ids)
            )
        """
    )
    suspend fun messagesMatchingIds(
        userId: String,
        roomId: String,
        ids: List<String>
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
