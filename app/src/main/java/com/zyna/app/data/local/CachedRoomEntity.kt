package com.zyna.app.data.local

import androidx.room.Entity

@Entity(
    tableName = "rooms",
    primaryKeys = ["userId", "id"]
)
data class CachedRoomEntity(
    val userId: String,
    val id: String,
    val displayName: String,
    val avatarUrl: String?,
    val directUserId: String?,
    val isSpace: Boolean = false,
    val lastMessageText: String?,
    val lastMessageSenderName: String?,
    val lastMessageAtMillis: Long?,
    val lastOwnMessageStatus: String?,
    val unreadCount: Long,
    val unreadMentionCount: Long,
    val isMarkedUnread: Boolean,
    val updatedAtMillis: Long,
    val detailsTopic: String? = null,
    val detailsJoinedMemberCount: Long? = null,
    val detailsEncryption: String? = null,
    val detailsAccess: String? = null,
    val detailsHistoryVisibility: String? = null,
    val detailsPinnedEventCount: Int? = null,
    val detailsCanonicalAlias: String? = null,
    val detailsRoomVersion: String? = null,
    val detailsCreatorSemantics: String? = null,
    val detailsCanInviteMembers: Boolean? = null,
    val detailsCanChangeName: Boolean? = null,
    val detailsCanChangeAvatar: Boolean? = null,
    val detailsUpdatedAtMillis: Long? = null
)
