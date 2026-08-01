package com.zyna.app.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class CachedSpaceListRow(
    @Embedded(prefix = "snapshot_")
    val snapshot: CachedSpaceListSnapshotEntity,
    @Embedded(prefix = "entry_")
    val entry: CachedSpaceListEntryEntity?
)

@Dao
interface CachedSpaceDao {
    @Query(
        """
        SELECT
            snapshots.userId AS snapshot_userId,
            snapshots.listId AS snapshot_listId,
            snapshots.spaceRoomId AS snapshot_spaceRoomId,
            snapshots.spaceDisplayName AS snapshot_spaceDisplayName,
            snapshots.spaceAvatarUrl AS snapshot_spaceAvatarUrl,
            snapshots.spaceTopic AS snapshot_spaceTopic,
            snapshots.spaceMembership AS snapshot_spaceMembership,
            snapshots.spaceJoinedMemberCount AS snapshot_spaceJoinedMemberCount,
            snapshots.spaceChildrenCount AS snapshot_spaceChildrenCount,
            snapshots.spaceCanonicalAlias AS snapshot_spaceCanonicalAlias,
            snapshots.spaceJoinRule AS snapshot_spaceJoinRule,
            snapshots.spaceWorldReadable AS snapshot_spaceWorldReadable,
            snapshots.spaceGuestCanJoin AS snapshot_spaceGuestCanJoin,
            snapshots.spaceIsDirect AS snapshot_spaceIsDirect,
            snapshots.spaceIsDm AS snapshot_spaceIsDm,
            snapshots.spaceViaJson AS snapshot_spaceViaJson,
            snapshots.isKnown AS snapshot_isKnown,
            snapshots.endReached AS snapshot_endReached,
            snapshots.updatedAtMillis AS snapshot_updatedAtMillis,
            entries.userId AS entry_userId,
            entries.listId AS entry_listId,
            entries.roomId AS entry_roomId,
            entries.position AS entry_position,
            entries.displayName AS entry_displayName,
            entries.avatarUrl AS entry_avatarUrl,
            entries.topic AS entry_topic,
            entries.kind AS entry_kind,
            entries.membership AS entry_membership,
            entries.joinedMemberCount AS entry_joinedMemberCount,
            entries.childrenCount AS entry_childrenCount,
            entries.canonicalAlias AS entry_canonicalAlias,
            entries.joinRule AS entry_joinRule,
            entries.worldReadable AS entry_worldReadable,
            entries.guestCanJoin AS entry_guestCanJoin,
            entries.isDirect AS entry_isDirect,
            entries.isDm AS entry_isDm,
            entries.viaJson AS entry_viaJson
        FROM space_list_snapshots AS snapshots
        LEFT JOIN space_list_entries AS entries
            ON entries.userId = snapshots.userId
            AND entries.listId = snapshots.listId
        WHERE snapshots.userId = :userId AND snapshots.listId = :listId
        ORDER BY entries.position ASC
        """
    )
    fun observeList(
        userId: String,
        listId: String
    ): Flow<List<CachedSpaceListRow>>

    @Query(
        """
        SELECT * FROM space_list_snapshots
        WHERE userId = :userId
        ORDER BY updatedAtMillis DESC
        LIMIT :limit
        """
    )
    suspend fun recentSnapshots(
        userId: String,
        limit: Int
    ): List<CachedSpaceListSnapshotEntity>

    @Query(
        """
        SELECT * FROM space_list_entries
        WHERE userId = :userId AND listId IN (:listIds)
        ORDER BY listId ASC, position ASC
        """
    )
    suspend fun entriesForLists(
        userId: String,
        listIds: List<String>
    ): List<CachedSpaceListEntryEntity>

    @Upsert
    suspend fun upsertSnapshot(snapshot: CachedSpaceListSnapshotEntity)

    @Upsert
    suspend fun upsertEntries(entries: List<CachedSpaceListEntryEntity>)

    @Query(
        """
        DELETE FROM space_list_entries
        WHERE userId = :userId AND listId = :listId
        """
    )
    suspend fun deleteEntries(userId: String, listId: String)

    @Query("DELETE FROM space_list_entries")
    suspend fun clearAllEntries()

    @Query("DELETE FROM space_list_snapshots")
    suspend fun clearAllSnapshots()
}
