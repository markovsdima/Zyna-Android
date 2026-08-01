package com.zyna.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class CachedRoomListOrder(
    val id: String,
    val listPosition: Long?,
    val lastMessageAtMillis: Long? = null,
    val displayName: String = ""
)

data class CachedRoomListPositionUpdate(
    val userId: String,
    val id: String,
    val listPosition: Long
)

@Dao
interface CachedRoomDao {
    @Query("SELECT * FROM rooms WHERE userId = :userId")
    fun observeRooms(userId: String): Flow<List<CachedRoomEntity>>

    @Query("SELECT * FROM rooms WHERE userId = :userId")
    suspend fun roomsSnapshot(userId: String): List<CachedRoomEntity>

    @Query(
        """
        SELECT id, listPosition, lastMessageAtMillis, displayName FROM rooms
        WHERE userId = :userId
        """
    )
    suspend fun roomListOrderSnapshot(userId: String): List<CachedRoomListOrder>

    @Query("SELECT * FROM rooms WHERE userId = :userId AND id IN (:roomIds)")
    suspend fun roomsSnapshotByIds(
        userId: String,
        roomIds: List<String>
    ): List<CachedRoomEntity>

    @Query("SELECT * FROM rooms WHERE userId = :userId AND id = :roomId LIMIT 1")
    suspend fun roomSnapshot(userId: String, roomId: String): CachedRoomEntity?

    @Query("SELECT MIN(listPosition) FROM rooms WHERE userId = :userId")
    suspend fun minimumListPosition(userId: String): Long?

    @Update(entity = CachedRoomEntity::class)
    suspend fun updateListPositions(updates: List<CachedRoomListPositionUpdate>)

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
            detailsRoomVersion = COALESCE(:roomVersion, detailsRoomVersion),
            detailsCreatorSemantics = COALESCE(
                :creatorSemantics,
                detailsCreatorSemantics
            ),
            detailsCanInviteMembers = COALESCE(:canInviteMembers, detailsCanInviteMembers),
            detailsCanChangeName = COALESCE(:canChangeName, detailsCanChangeName),
            detailsCanChangeAvatar = COALESCE(:canChangeAvatar, detailsCanChangeAvatar),
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
        roomVersion: String?,
        creatorSemantics: String?,
        canInviteMembers: Boolean?,
        canChangeName: Boolean?,
        canChangeAvatar: Boolean?,
        detailsUpdatedAtMillis: Long
    ): Int

    @Query("DELETE FROM rooms WHERE userId = :userId AND id IN (:roomIds)")
    suspend fun deleteRooms(userId: String, roomIds: List<String>)

    @Query("DELETE FROM rooms")
    suspend fun clearAllRooms()
}
