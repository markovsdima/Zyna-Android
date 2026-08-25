package com.zyna.app.data.local

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "matrix_rtc_calls",
    primaryKeys = ["userId", "eventId"],
    indices = [
        Index(value = ["userId", "timestampMillis"]),
        Index(value = ["userId", "roomId", "timestampMillis"]),
        Index(value = ["userId", "outcome", "expiresAtMillis"])
    ]
)
data class MatrixRtcCallEntity(
    val userId: String,
    val eventId: String,
    val roomId: String,
    val parentEventId: String?,
    val senderId: String,
    val senderDisplayName: String?,
    val isOutgoing: Boolean,
    val timestampMillis: Long,
    val notificationType: String,
    val callIntent: String?,
    val expiresAtMillis: Long?,
    val declinedByJson: String,
    val isDirect: Boolean,
    val hasOwnJoin: Boolean,
    val hasRemoteJoin: Boolean,
    val hasOwnLeave: Boolean,
    val hasRemoteLeave: Boolean,
    val lastMembershipEventTimestampMillis: Long?,
    val lastOwnLeaveTimestampMillis: Long?,
    val lastRemoteLeaveTimestampMillis: Long?,
    val outcome: String,
    val updatedAtMillis: Long
)
