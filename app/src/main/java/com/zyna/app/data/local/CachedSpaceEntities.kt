package com.zyna.app.data.local

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "space_list_snapshots",
    primaryKeys = ["userId", "listId"]
)
data class CachedSpaceListSnapshotEntity(
    val userId: String,
    val listId: String,
    val spaceRoomId: String?,
    val spaceDisplayName: String?,
    val spaceAvatarUrl: String?,
    val spaceTopic: String?,
    val spaceMembership: String?,
    val spaceJoinedMemberCount: Long?,
    val spaceChildrenCount: Long?,
    val spaceCanonicalAlias: String?,
    val spaceJoinRule: String?,
    val spaceWorldReadable: Boolean?,
    val spaceGuestCanJoin: Boolean?,
    val spaceIsDirect: Boolean?,
    val spaceIsDm: Boolean?,
    val spaceViaJson: String?,
    val isKnown: Boolean,
    val endReached: Boolean,
    val updatedAtMillis: Long
)

@Entity(
    tableName = "space_list_entries",
    primaryKeys = ["userId", "listId", "roomId"],
    indices = [
        Index(value = ["userId", "listId", "position"])
    ]
)
data class CachedSpaceListEntryEntity(
    val userId: String,
    val listId: String,
    val roomId: String,
    val position: Int,
    val displayName: String,
    val avatarUrl: String?,
    val topic: String?,
    val kind: String,
    val membership: String,
    val joinedMemberCount: Long,
    val childrenCount: Long,
    val canonicalAlias: String?,
    val joinRule: String,
    val worldReadable: Boolean?,
    val guestCanJoin: Boolean,
    val isDirect: Boolean?,
    val isDm: Boolean?,
    val viaJson: String
)
