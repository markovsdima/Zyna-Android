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
    val updatedAtMillis: Long
)
