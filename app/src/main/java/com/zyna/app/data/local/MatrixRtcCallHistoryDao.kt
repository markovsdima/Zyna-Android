package com.zyna.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MatrixRtcCallHistoryDao {
    @Query(
        """
        SELECT * FROM matrix_rtc_calls
        WHERE userId = :userId
        ORDER BY timestampMillis DESC, eventId DESC
        LIMIT :limit
        """
    )
    fun observeRecentCalls(userId: String, limit: Int): Flow<List<MatrixRtcCallEntity>>

    @Query(
        """
        SELECT * FROM matrix_rtc_calls
        WHERE userId = :userId
        ORDER BY timestampMillis DESC, eventId DESC
        LIMIT :limit
        """
    )
    suspend fun recentCallsSnapshot(userId: String, limit: Int): List<MatrixRtcCallEntity>

    @Query(
        """
        SELECT * FROM matrix_rtc_calls
        WHERE userId = :userId AND roomId = :roomId
            AND timestampMillis >= :fromTimestampMillis
            AND timestampMillis <= :toTimestampMillis
        ORDER BY timestampMillis ASC, eventId ASC
        """
    )
    suspend fun callsInRoomWindow(
        userId: String,
        roomId: String,
        fromTimestampMillis: Long,
        toTimestampMillis: Long
    ): List<MatrixRtcCallEntity>

    @Query(
        """
        SELECT * FROM matrix_rtc_call_memberships
        WHERE userId = :userId AND roomId = :roomId
            AND timestampMillis >= :fromTimestampMillis
            AND timestampMillis <= :toTimestampMillis
        ORDER BY timestampMillis ASC, eventId ASC
        """
    )
    suspend fun membershipsInRoomWindow(
        userId: String,
        roomId: String,
        fromTimestampMillis: Long,
        toTimestampMillis: Long
    ): List<MatrixRtcCallMembershipEntity>

    @Upsert
    suspend fun upsertCalls(calls: List<MatrixRtcCallEntity>)

    @Upsert
    suspend fun upsertMemberships(memberships: List<MatrixRtcCallMembershipEntity>)

    @Query(
        """
        UPDATE matrix_rtc_calls
        SET isDirect = :isDirect,
            hasOwnJoin = :hasOwnJoin,
            hasRemoteJoin = :hasRemoteJoin,
            hasOwnLeave = :hasOwnLeave,
            hasRemoteLeave = :hasRemoteLeave,
            lastMembershipEventTimestampMillis = :lastMembershipEventTimestampMillis,
            lastOwnLeaveTimestampMillis = :lastOwnLeaveTimestampMillis,
            lastRemoteLeaveTimestampMillis = :lastRemoteLeaveTimestampMillis,
            outcome = :outcome,
            updatedAtMillis = :updatedAtMillis
        WHERE userId = :userId AND eventId = :eventId
        """
    )
    suspend fun updateCallProjection(
        userId: String,
        eventId: String,
        isDirect: Boolean,
        hasOwnJoin: Boolean,
        hasRemoteJoin: Boolean,
        hasOwnLeave: Boolean,
        hasRemoteLeave: Boolean,
        lastMembershipEventTimestampMillis: Long?,
        lastOwnLeaveTimestampMillis: Long?,
        lastRemoteLeaveTimestampMillis: Long?,
        outcome: String,
        updatedAtMillis: Long
    )

    @Query(
        """
        SELECT * FROM matrix_rtc_calls
        WHERE userId = :userId
            AND notificationType = 'RING'
            AND outcome = 'STARTED'
            AND expiresAtMillis IS NOT NULL
            AND expiresAtMillis <= :nowMillis
        ORDER BY timestampMillis ASC, eventId ASC
        LIMIT :limit
        """
    )
    suspend fun expiredStartedRingCalls(
        userId: String,
        nowMillis: Long,
        limit: Int
    ): List<MatrixRtcCallEntity>

    @Query("DELETE FROM matrix_rtc_calls")
    suspend fun clearAllCalls()

    @Query("DELETE FROM matrix_rtc_call_memberships")
    suspend fun clearAllMemberships()
}
