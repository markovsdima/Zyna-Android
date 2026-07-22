package com.zyna.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CachedRoomDao {
    @Query("SELECT * FROM rooms WHERE userId = :userId")
    fun observeRooms(userId: String): Flow<List<CachedRoomEntity>>

    @Query("SELECT * FROM rooms WHERE userId = :userId")
    suspend fun roomsSnapshot(userId: String): List<CachedRoomEntity>

    @Query("SELECT * FROM rooms WHERE userId = :userId AND id = :roomId LIMIT 1")
    suspend fun roomSnapshot(userId: String, roomId: String): CachedRoomEntity?

    @Query("SELECT * FROM rooms WHERE userId = :userId AND id = :roomId LIMIT 1")
    fun observeRoom(userId: String, roomId: String): Flow<CachedRoomEntity?>

    @Upsert
    suspend fun upsertRooms(rooms: List<CachedRoomEntity>)

    @Query(
        """
        UPDATE rooms
        SET lastMessageText = :lastMessageText,
            lastMessageSenderName = :lastMessageSenderName,
            lastMessageAtMillis = :lastMessageAtMillis,
            lastOwnMessageStatus = :lastOwnMessageStatus,
            updatedAtMillis = :updatedAtMillis
        WHERE userId = :userId AND id = :roomId
        """
    )
    suspend fun updateRoomPreview(
        userId: String,
        roomId: String,
        lastMessageText: String?,
        lastMessageSenderName: String?,
        lastMessageAtMillis: Long?,
        lastOwnMessageStatus: String?,
        updatedAtMillis: Long
    ): Int

    @Query(
        """
        UPDATE rooms
        SET detailsTopic = :topic,
            detailsJoinedMemberCount = :joinedMemberCount,
            detailsEncryption = :encryption,
            detailsAccess = :access,
            detailsHistoryVisibility = :historyVisibility,
            detailsPinnedEventCount = :pinnedEventCount,
            detailsCanonicalAlias = :canonicalAlias,
            detailsCanInviteMembers = COALESCE(:canInviteMembers, detailsCanInviteMembers),
            detailsUpdatedAtMillis = :detailsUpdatedAtMillis
        WHERE userId = :userId AND id = :roomId
        """
    )
    suspend fun updateRoomDetails(
        userId: String,
        roomId: String,
        topic: String?,
        joinedMemberCount: Long,
        encryption: String,
        access: String,
        historyVisibility: String,
        pinnedEventCount: Int,
        canonicalAlias: String?,
        canInviteMembers: Boolean?,
        detailsUpdatedAtMillis: Long
    ): Int

    @Query("DELETE FROM rooms WHERE userId = :userId")
    suspend fun clearRooms(userId: String)

    @Query("DELETE FROM rooms")
    suspend fun clearAllRooms()
}
