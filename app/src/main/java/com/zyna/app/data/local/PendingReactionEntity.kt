package com.zyna.app.data.local

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "pending_reactions",
    primaryKeys = ["userId", "roomId", "id"],
    indices = [
        Index(value = ["userId", "roomId", "targetEventId", "reactionKey"]),
        Index(value = ["userId", "state", "updatedAtMillis"]),
        Index(value = ["userId", "roomId", "reactionEventId"])
    ]
)
data class PendingReactionEntity(
    val userId: String,
    val roomId: String,
    val id: String,
    val targetEventId: String,
    val reactionKey: String,
    val state: String,
    val transactionId: String?,
    val reactionEventId: String?,
    val redactionTransactionId: String?,
    val redactionEventId: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val failureMessage: String?,
    val lastAttemptAtMillis: Long?,
    val attemptCount: Int
)
