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
            AND roomId = :roomId
            AND transportState != 'RETIRED'
        ORDER BY createdAtMillis ASC, id ASC
        """
    )
    suspend fun activeRoomEnvelopesSnapshot(
        userId: String,
        roomId: String
    ): List<OutgoingEnvelopeEntity>

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
        SELECT * FROM outgoing_envelopes
        WHERE userId = :userId
            AND id = :id
            AND kind = 'TEXT'
            AND transportState IN ('QUEUED', 'SENDING', 'RETRYING')
        LIMIT 1
        """
    )
    suspend fun textDispatchCandidate(userId: String, id: String): OutgoingEnvelopeEntity?

    @Query(
        """
        SELECT * FROM outgoing_envelopes
        WHERE userId = :userId
            AND kind = 'IMAGE'
            AND transportState IN ('QUEUED', 'SENDING', 'RETRYING')
        ORDER BY createdAtMillis ASC, id ASC
        """
    )
    suspend fun imageDispatchCandidates(userId: String): List<OutgoingEnvelopeEntity>

    @Query(
        """
        SELECT * FROM outgoing_envelopes
        WHERE userId = :userId
            AND id = :id
            AND kind = 'IMAGE'
            AND transportState IN ('QUEUED', 'SENDING', 'RETRYING')
        LIMIT 1
        """
    )
    suspend fun imageDispatchCandidate(userId: String, id: String): OutgoingEnvelopeEntity?

    @Query(
        """
        SELECT * FROM outgoing_envelopes
        WHERE userId = :userId
            AND kind = 'REDACTION'
            AND transportState IN ('QUEUED', 'SENDING', 'RETRYING')
            AND targetEventId IS NOT NULL
        ORDER BY createdAtMillis ASC, id ASC
        """
    )
    suspend fun redactionDispatchCandidates(userId: String): List<OutgoingEnvelopeEntity>

    @Query(
        """
        SELECT * FROM outgoing_envelopes
        WHERE userId = :userId
            AND id = :id
            AND kind = 'REDACTION'
            AND transportState IN ('QUEUED', 'SENDING', 'RETRYING')
            AND targetEventId IS NOT NULL
        LIMIT 1
        """
    )
    suspend fun redactionDispatchCandidate(userId: String, id: String): OutgoingEnvelopeEntity?

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

    @Query(
        """
        SELECT imageLocalPath FROM outgoing_envelopes
        WHERE userId = :userId
            AND roomId = :roomId
            AND eventId IN (:eventIds)
            AND imageLocalPath IS NOT NULL
        UNION
        SELECT imageThumbnailLocalPath FROM outgoing_envelopes
        WHERE userId = :userId
            AND roomId = :roomId
            AND eventId IN (:eventIds)
            AND imageThumbnailLocalPath IS NOT NULL
        """
    )
    suspend fun imageLocalPathsForEventIds(
        userId: String,
        roomId: String,
        eventIds: List<String>
    ): List<String>

    @Query(
        """
        SELECT imageLocalPath FROM outgoing_envelopes
        WHERE imageLocalPath IS NOT NULL
            AND transportState != 'RETIRED'
        UNION
        SELECT imageThumbnailLocalPath FROM outgoing_envelopes
        WHERE imageThumbnailLocalPath IS NOT NULL
            AND transportState != 'RETIRED'
        """
    )
    suspend fun activeImageLocalPaths(): List<String>

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
        SET transportState = 'RETRYING',
            failureMessage = :failureMessage,
            updatedAtMillis = :updatedAtMillis
        WHERE userId = :userId
            AND roomId = :roomId
            AND id = :id
            AND transportState IN ('QUEUED', 'SENDING', 'RETRYING')
        """
    )
    suspend fun markDispatchRetrying(
        userId: String,
        roomId: String,
        id: String,
        failureMessage: String?,
        updatedAtMillis: Long
    ): Int

    @Query(
        """
        UPDATE outgoing_envelopes
        SET imageUploadedJson = :uploadedImageJson,
            imageUploadedAtMillis = :updatedAtMillis,
            failureMessage = NULL,
            updatedAtMillis = :updatedAtMillis
        WHERE userId = :userId
            AND roomId = :roomId
            AND id = :id
            AND kind = 'IMAGE'
            AND transportState IN ('QUEUED', 'SENDING', 'RETRYING')
        """
    )
    suspend fun markImageUploadAccepted(
        userId: String,
        roomId: String,
        id: String,
        uploadedImageJson: String,
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
        SET transportState = 'FAILED',
            failureMessage = :failureMessage,
            updatedAtMillis = :updatedAtMillis
        WHERE userId = :userId
            AND roomId = :roomId
            AND id = :id
            AND kind IN ('TEXT', 'IMAGE')
            AND transportState IN ('QUEUED', 'SENDING', 'RETRYING')
        """
    )
    suspend fun debugMarkActiveMessageEnvelopeFailed(
        userId: String,
        roomId: String,
        id: String,
        failureMessage: String?,
        updatedAtMillis: Long
    ): Int

    @Query(
        """
        UPDATE outgoing_envelopes
        SET transportState = 'QUEUED',
            failureMessage = NULL,
            updatedAtMillis = :updatedAtMillis
        WHERE userId = :userId
            AND roomId = :roomId
            AND id = :id
            AND kind IN ('TEXT', 'IMAGE')
            AND transportState = 'FAILED'
        """
    )
    suspend fun markFailedMessageEnvelopeQueued(
        userId: String,
        roomId: String,
        id: String,
        updatedAtMillis: Long
    ): Int

    @Query(
        """
        DELETE FROM outgoing_envelopes
        WHERE userId = :userId
            AND roomId = :roomId
            AND id = :id
            AND kind IN ('TEXT', 'IMAGE')
            AND transportState = 'FAILED'
        """
    )
    suspend fun deleteFailedMessageEnvelope(
        userId: String,
        roomId: String,
        id: String
    ): Int

    @Query(
        """
        SELECT * FROM outgoing_envelopes
        WHERE userId = :userId
            AND roomId = :roomId
            AND id = :id
            AND kind IN ('TEXT', 'IMAGE')
            AND transportState = 'FAILED'
        LIMIT 1
        """
    )
    suspend fun failedMessageEnvelope(
        userId: String,
        roomId: String,
        id: String
    ): OutgoingEnvelopeEntity?

    @Query(
        """
        DELETE FROM outgoing_envelopes
        WHERE userId = :userId
            AND roomId = :roomId
            AND id = :id
        """
    )
    suspend fun deleteEnvelope(
        userId: String,
        roomId: String,
        id: String
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
