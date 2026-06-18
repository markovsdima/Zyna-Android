package com.zyna.app.data.outgoing

import com.zyna.app.data.matrix.MatrixReplyInfo

enum class OutgoingEnvelopeKind {
    TEXT,
    REDACTION
}

enum class OutgoingTransportState {
    QUEUED,
    SENDING,
    RETRYING,
    SENT,
    FAILED,
    RETIRED
}

data class OutgoingTextEnvelope(
    val userId: String,
    val roomId: String,
    val id: String,
    val transportState: OutgoingTransportState,
    val transactionId: String,
    val eventId: String?,
    val body: String,
    val replyInfo: MatrixReplyInfo?,
    val createdAtMillis: Long,
    val failureMessage: String?
)

data class OutgoingRedactionEnvelope(
    val userId: String,
    val roomId: String,
    val id: String,
    val transportState: OutgoingTransportState,
    val transactionId: String,
    val redactionEventId: String?,
    val targetEventId: String,
    val targetTransactionId: String?,
    val targetBody: String,
    val targetContentType: String,
    val createdAtMillis: Long,
    val failureMessage: String?
)
