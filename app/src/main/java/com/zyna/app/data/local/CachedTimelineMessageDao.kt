package com.zyna.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
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
            AND (
                timestampMillis < :toTimestampMillis
                OR (
                    timestampMillis = :toTimestampMillis
                    AND id <= :toId
                )
            )
        ORDER BY timestampMillis ASC, id ASC
        """
    )
    fun observeRoomMessagesRange(
        userId: String,
        roomId: String,
        localIdPattern: String,
        fromTimestampMillis: Long,
        fromId: String,
        toTimestampMillis: Long,
        toId: String
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
            AND eventId = :eventId
        ORDER BY timestampMillis DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun roomMessageByEventId(
        userId: String,
        roomId: String,
        localIdPattern: String,
        eventId: String
    ): CachedTimelineMessageEntity?

    @Query(
        """
        SELECT * FROM timeline_messages
        WHERE userId = :userId
            AND roomId = :roomId
            AND id NOT LIKE :localIdPattern
            AND (
                timestampMillis < :atTimestampMillis
                OR (
                    timestampMillis = :atTimestampMillis
                    AND id <= :atId
                )
            )
        ORDER BY timestampMillis DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun roomMessagesAtOrBefore(
        userId: String,
        roomId: String,
        localIdPattern: String,
        atTimestampMillis: Long,
        atId: String,
        limit: Int
    ): List<CachedTimelineMessageEntity>

    @Query(
        """
        SELECT * FROM timeline_messages
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
        ORDER BY timestampMillis ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun roomMessagesAfter(
        userId: String,
        roomId: String,
        localIdPattern: String,
        afterTimestampMillis: Long,
        afterId: String,
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
        UPDATE timeline_messages
        SET body = '',
            contentType = 'MATRIX_RTC_CALL',
            timelineDetailsJson = :timelineDetailsJson,
            updatedAtMillis = :updatedAtMillis
        WHERE userId = :userId
            AND roomId = :roomId
            AND (eventId = :eventId OR id = :eventId)
            AND (
                body != ''
                OR contentType != 'MATRIX_RTC_CALL'
                OR timelineDetailsJson IS NOT :timelineDetailsJson
            )
        """
    )
    suspend fun updateMatrixRtcCallTimelineProjection(
        userId: String,
        roomId: String,
        eventId: String,
        timelineDetailsJson: String,
        updatedAtMillis: Long
    ): Int

    @Query(
        """
        UPDATE timeline_messages
        SET isEditPending = 1,
            isEditFailed = 0,
            editTransactionId = :editTransactionId,
            pendingEditBody = :pendingEditBody,
            updatedAtMillis = :updatedAtMillis
        WHERE userId = :userId
            AND roomId = :roomId
            AND eventId = :eventId
            AND isOwn = 1
            AND contentType = 'TEXT'
        """
    )
    suspend fun preparePendingTextEdit(
        userId: String,
        roomId: String,
        eventId: String,
        editTransactionId: String,
        pendingEditBody: String,
        updatedAtMillis: Long
    ): Int

    @Query(
        """
        SELECT * FROM timeline_messages
        WHERE userId = :userId
            AND isEditPending = 1
            AND editTransactionId IS NOT NULL
            AND editTransactionId != ''
            AND pendingEditBody IS NOT NULL
            AND eventId IS NOT NULL
            AND eventId != ''
        ORDER BY timestampMillis ASC, id ASC
        """
    )
    suspend fun pendingTextEdits(userId: String): List<CachedTimelineMessageEntity>

    @Query(
        """
        UPDATE timeline_messages
        SET body = :body,
            isEdited = 1,
            isEditPending = 0,
            isEditFailed = 0,
            latestEditEventId = :latestEditEventId,
            editTransactionId = NULL,
            pendingEditBody = NULL,
            updatedAtMillis = :updatedAtMillis
        WHERE userId = :userId
            AND roomId = :roomId
            AND eventId = :eventId
            AND editTransactionId = :editTransactionId
            AND contentType = 'TEXT'
        """
    )
    suspend fun markPendingTextEditAccepted(
        userId: String,
        roomId: String,
        eventId: String,
        editTransactionId: String,
        latestEditEventId: String,
        body: String,
        updatedAtMillis: Long
    ): Int

    @Query(
        """
        UPDATE timeline_messages
        SET isEditPending = 0,
            isEditFailed = 1,
            editTransactionId = NULL,
            pendingEditBody = NULL,
            updatedAtMillis = :updatedAtMillis
        WHERE userId = :userId
            AND roomId = :roomId
            AND eventId = :eventId
            AND editTransactionId = :editTransactionId
        """
    )
    suspend fun markPendingTextEditFailed(
        userId: String,
        roomId: String,
        eventId: String,
        editTransactionId: String,
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
            AND contentType NOT IN ('SYSTEM_EVENT', 'MATRIX_RTC_CALL')
        ORDER BY timestampMillis DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun latestRoomPreviewMessages(
        userId: String,
        roomId: String,
        localIdPattern: String,
        limit: Int
    ): List<CachedTimelineMessageEntity>

    @Query("DELETE FROM timeline_messages WHERE userId = :userId")
    suspend fun clearMessages(userId: String)

    @Query("DELETE FROM timeline_messages")
    suspend fun clearAllMessages()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageIfAbsent(message: CachedTimelineMessageEntity): Long

    @Upsert
    suspend fun upsertMessages(messages: List<CachedTimelineMessageEntity>)
}
