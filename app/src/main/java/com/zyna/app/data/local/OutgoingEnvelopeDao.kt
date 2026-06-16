package com.zyna.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface OutgoingEnvelopeDao {
    @Query(
        """
        SELECT * FROM outgoing_envelopes
        WHERE userId = :userId
            AND roomId = :roomId
            AND transportState != 'RETIRED'
        ORDER BY createdAtMillis ASC, id ASC
        """
    )
    fun observeActiveRoomEnvelopes(userId: String, roomId: String): Flow<List<OutgoingEnvelopeEntity>>

    @Query(
        """
        SELECT * FROM outgoing_envelopes
        WHERE userId = :userId
            AND kind = 'TEXT'
            AND transportState IN ('QUEUED', 'SENDING', 'RETRYING')
        ORDER BY createdAtMillis ASC, id ASC
        """
    )
    suspend fun textDispatchCandidates(userId: String): List<OutgoingEnvelopeEntity>

    @Query(
        """
        SELECT transactionId FROM outgoing_envelopes
        WHERE userId = :userId
            AND roomId = :roomId
            AND eventId IN (:eventIds)
        """
    )
    suspend fun transactionIdsForEventIds(
        userId: String,
        roomId: String,
        eventIds: List<String>
    ): List<String>

    @Upsert
    suspend fun upsertEnvelope(envelope: OutgoingEnvelopeEntity)

    @Query(
        """
        UPDATE outgoing_envelopes
        SET transportState = 'SENDING',
            failureMessage = NULL,
            updatedAtMillis = :updatedAtMillis
        WHERE userId = :userId
            AND roomId = :roomId
            AND id = :id
            AND transportState IN ('QUEUED', 'SENDING', 'RETRYING')
        """
    )
    suspend fun markDispatchStarted(
        userId: String,
        roomId: String,
        id: String,
        updatedAtMillis: Long
    ): Int

    @Query(
        """
        UPDATE outgoing_envelopes
        SET transportState = 'SENT',
            eventId = :eventId,
            failureMessage = NULL,
            updatedAtMillis = :updatedAtMillis
        WHERE userId = :userId
            AND roomId = :roomId
            AND id = :id
        """
    )
    suspend fun markDispatchAccepted(
        userId: String,
        roomId: String,
        id: String,
        eventId: String,
        updatedAtMillis: Long
    ): Int

    @Query(
        """
        UPDATE outgoing_envelopes
        SET transportState = 'FAILED',
            failureMessage = :failureMessage,
            updatedAtMillis = :updatedAtMillis
        WHERE userId = :userId
            AND roomId = :roomId
            AND id = :id
        """
    )
    suspend fun markDispatchFailed(
        userId: String,
        roomId: String,
        id: String,
        failureMessage: String?,
        updatedAtMillis: Long
    ): Int

    @Query(
        """
        UPDATE outgoing_envelopes
        SET transportState = 'RETIRED',
            updatedAtMillis = :updatedAtMillis
        WHERE userId = :userId
            AND roomId = :roomId
            AND eventId IN (:eventIds)
            AND transportState != 'RETIRED'
        """
    )
    suspend fun retireByEventIds(
        userId: String,
        roomId: String,
        eventIds: List<String>,
        updatedAtMillis: Long
    ): Int

    @Query("DELETE FROM outgoing_envelopes WHERE userId = :userId")
    suspend fun clearEnvelopes(userId: String)

    @Query("DELETE FROM outgoing_envelopes")
    suspend fun clearAllEnvelopes()
}
