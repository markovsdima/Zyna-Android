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
    val lastMessageText: String?,
    val lastMessageSenderName: String?,
    val lastMessageAtMillis: Long?,
    val lastOwnMessageStatus: String?,
    val unreadCount: Long,
    val unreadMentionCount: Long,
    val isMarkedUnread: Boolean,
    val updatedAtMillis: Long
)
