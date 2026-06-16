package com.zyna.app.data.local

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "timeline_messages",
    primaryKeys = ["userId", "roomId", "id"],
    indices = [
        Index(value = ["userId", "roomId", "timelineIndex"]),
        Index(value = ["userId", "roomId", "timestampMillis"])
    ]
)
data class CachedTimelineMessageEntity(
    val userId: String,
    val roomId: String,
    val id: String,
    val timelineIndex: Int,
    val sender: String,
    val body: String,
    val timestampMillis: Long,
    val isOwn: Boolean,
    val deliveryState: String,
    val updatedAtMillis: Long
)
