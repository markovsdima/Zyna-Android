package com.zyna.app.data.local

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "outgoing_envelopes",
    primaryKeys = ["userId", "roomId", "id"],
    indices = [
        Index(value = ["userId", "roomId", "transportState"]),
        Index(value = ["userId", "roomId", "transactionId"], unique = true),
        Index(value = ["userId", "roomId", "eventId"])
    ]
)
data class OutgoingEnvelopeEntity(
    val userId: String,
    val roomId: String,
    val id: String,
    val kind: String,
    val transportState: String,
    val transactionId: String,
    val eventId: String?,
    val body: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val failureMessage: String?
)
