package com.zyna.app.data.local

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "matrix_rtc_call_memberships",
    primaryKeys = ["userId", "eventId"],
    indices = [
        Index(value = ["userId", "roomId", "timestampMillis"]),
        Index(value = ["userId", "roomId", "stateKey"]),
        Index(value = ["userId", "memberUserId", "timestampMillis"])
    ]
)
data class MatrixRtcCallMembershipEntity(
    val userId: String,
    val eventId: String,
    val roomId: String,
    val eventType: String,
    val stateKey: String?,
    val senderId: String,
    val timestampMillis: Long,
    val isLeave: Boolean,
    val memberUserId: String?,
    val deviceId: String?,
    val memberId: String?,
    val callIntent: String?,
    val expiresAtMillis: Long?,
    val updatedAtMillis: Long
)
