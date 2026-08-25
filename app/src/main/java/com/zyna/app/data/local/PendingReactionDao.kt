package com.zyna.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingReactionDao {
    @Query(
        """
        SELECT * FROM pending_reactions
        WHERE userId = :userId
            AND roomId = :roomId
        ORDER BY updatedAtMillis ASC, id ASC
        """
    )
    fun observeRoomPendingReactions(
        userId: String,
        roomId: String
    ): Flow<List<PendingReactionEntity>>

    @Query(
        """
        SELECT * FROM pending_reactions
        WHERE userId = :userId
            AND roomId = :roomId
        ORDER BY updatedAtMillis ASC, id ASC
        """
    )
    suspend fun roomPendingReactionsSnapshot(
        userId: String,
        roomId: String
    ): List<PendingReactionEntity>

    @Query(
        """
        SELECT * FROM pending_reactions
        WHERE userId = :userId
            AND (
                state = 'ADD_QUEUED'
                OR state = 'ADD_AFTER_REMOVE_QUEUED'
                OR (
                    state = 'REMOVE_QUEUED'
                    AND redactionTransactionId IS NOT NULL
                    AND (
                        reactionEventId IS NOT NULL
                        OR transactionId IS NOT NULL
                    )
                )
            )
        ORDER BY updatedAtMillis ASC, id ASC
        """
    )
    suspend fun outboxCandidates(userId: String): List<PendingReactionEntity>

    @Query(
        """
        SELECT * FROM pending_reactions
        WHERE userId = :userId
            AND id = :id
            AND (
                state = 'ADD_QUEUED'
                OR state = 'ADD_AFTER_REMOVE_QUEUED'
                OR (
                    state = 'REMOVE_QUEUED'
                    AND redactionTransactionId IS NOT NULL
                    AND (
                        reactionEventId IS NOT NULL
                        OR transactionId IS NOT NULL
                    )
                )
            )
        LIMIT 1
        """
    )
    suspend fun outboxCandidate(userId: String, id: String): PendingReactionEntity?

    @Query(
        """
        SELECT * FROM pending_reactions
        WHERE userId = :userId
            AND roomId = :roomId
            AND targetEventId = :targetEventId
            AND reactionKey = :reactionKey
        ORDER BY updatedAtMillis DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun latestReaction(
        userId: String,
        roomId: String,
        targetEventId: String,
        reactionKey: String
    ): PendingReactionEntity?

    @Query(
        """
        SELECT * FROM pending_reactions
        WHERE userId = :userId
            AND roomId = :roomId
            AND id = :id
        LIMIT 1
        """
    )
    suspend fun reactionById(userId: String, roomId: String, id: String): PendingReactionEntity?

    @Query(
        """
        UPDATE pending_reactions
        SET lastAttemptAtMillis = :lastAttemptAtMillis,
            attemptCount = attemptCount + 1,
            updatedAtMillis = :updatedAtMillis
        WHERE userId = :userId
            AND roomId = :roomId
            AND id = :id
        """
    )
    suspend fun markAttemptStarted(
        userId: String,
        roomId: String,
        id: String,
        lastAttemptAtMillis: Long,
        updatedAtMillis: Long
    ): Int

    @Upsert
    suspend fun upsertReaction(entity: PendingReactionEntity)

    @Query(
        """
        DELETE FROM pending_reactions
        WHERE userId = :userId
            AND roomId = :roomId
            AND id = :id
        """
    )
    suspend fun deleteReaction(userId: String, roomId: String, id: String): Int

    @Query("DELETE FROM pending_reactions")
    suspend fun clearAll()
}
