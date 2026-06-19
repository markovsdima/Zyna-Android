package com.zyna.app.data.outgoing

import com.zyna.app.data.matrix.MatrixReplyInfo

enum class OutgoingEnvelopeKind {
    TEXT,
    IMAGE,
    REDACTION,
    EDIT
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
    val forwardedFrom: String?,
    val createdAtMillis: Long,
    val failureMessage: String?
)

data class OutgoingImageEnvelope(
    val userId: String,
    val roomId: String,
    val id: String,
    val transportState: OutgoingTransportState,
    val transactionId: String,
    val eventId: String?,
    val localPath: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val caption: String?,
    val zynaAttributesJson: String?,
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

data class OutgoingEditEnvelope(
    val userId: String,
    val roomId: String,
    val eventId: String,
    val transactionId: String,
    val body: String,
    val createdAtMillis: Long
) {
    val id: String
        get() = "edit:$roomId:$eventId"
}
