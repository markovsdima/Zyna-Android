package com.zyna.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
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
        WHERE userId = :userId AND eventId = :eventId
        LIMIT 1
        """
    )
    suspend fun callSnapshot(userId: String, eventId: String): MatrixRtcCallEntity?

    @Query(
        """
        SELECT calls.*
        FROM matrix_rtc_calls AS calls
        LEFT JOIN rooms AS room
            ON room.userId = calls.userId AND room.id = calls.roomId
        WHERE calls.userId = :userId
            AND calls.isDirect IS NOT (
                CASE
                    WHEN room.directUserId IS NOT NULL AND TRIM(room.directUserId) != '' THEN 1
                    ELSE 0
                END
            )
        ORDER BY calls.timestampMillis DESC, calls.eventId DESC
        LIMIT :limit
        """
    )
    suspend fun callsWithRoomDirectnessMismatch(
        userId: String,
        limit: Int
    ): List<MatrixRtcCallEntity>

    @Query(
        """
        SELECT calls.*
        FROM matrix_rtc_calls AS calls
        WHERE calls.userId = :userId
            AND NOT EXISTS (
                SELECT 1
                FROM timeline_messages AS timeline
                WHERE timeline.userId = calls.userId
                    AND timeline.roomId = calls.roomId
                    AND (timeline.eventId = calls.eventId OR timeline.id = calls.eventId)
                    AND timeline.contentType = 'MATRIX_RTC_CALL'
                    AND timeline.timelineDetailsJson IS NOT NULL
            )
        ORDER BY calls.timestampMillis DESC, calls.eventId DESC
        LIMIT :limit
        """
    )
    suspend fun callsMissingTimelineRows(
        userId: String,
        limit: Int
    ): List<MatrixRtcCallEntity>

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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCallIfAbsent(call: MatrixRtcCallEntity): Long

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
            AND (
                isDirect IS NOT :isDirect
                OR hasOwnJoin IS NOT :hasOwnJoin
                OR hasRemoteJoin IS NOT :hasRemoteJoin
                OR hasOwnLeave IS NOT :hasOwnLeave
                OR hasRemoteLeave IS NOT :hasRemoteLeave
                OR lastMembershipEventTimestampMillis IS NOT
                    :lastMembershipEventTimestampMillis
                OR lastOwnLeaveTimestampMillis IS NOT :lastOwnLeaveTimestampMillis
                OR lastRemoteLeaveTimestampMillis IS NOT :lastRemoteLeaveTimestampMillis
                OR outcome IS NOT :outcome
            )
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
    ): Int

    @Query(
        """
        SELECT * FROM matrix_rtc_calls
        WHERE userId = :userId
            AND isDirect = 1
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
