package com.zyna.app.data.outgoing

import com.zyna.app.data.matrix.MatrixReplyInfo

enum class OutgoingEnvelopeKind {
    TEXT,
    IMAGE,
    VOICE,
    REDACTION,
    EDIT
}

enum class PendingReactionState {
    ADD_QUEUED,
    ADD_ACCEPTED,
    ADD_AFTER_REMOVE_QUEUED,
    REMOVE_QUEUED,
    REMOVED,
    FAILED
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
    val localPath: String?,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val thumbnailLocalPath: String?,
    val thumbnailMimeType: String?,
    val thumbnailWidth: Int?,
    val thumbnailHeight: Int?,
    val thumbnailSizeBytes: Long?,
    val caption: String?,
    val zynaAttributesJson: String?,
    val sourceJson: String?,
    val thumbnailSourceJson: String?,
    val blurhash: String?,
    val uploadedImageJson: String?,
    val createdAtMillis: Long,
    val failureMessage: String?
)

data class OutgoingVoiceEnvelope(
    val userId: String,
    val roomId: String,
    val id: String,
    val transportState: OutgoingTransportState,
    val transactionId: String,
    val eventId: String?,
    val localPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMillis: Long,
    val waveform: List<Float>,
    val replyInfo: MatrixReplyInfo?,
    val uploadedVoiceJson: String?,
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

data class OutgoingReactionEnvelope(
    val userId: String,
    val roomId: String,
    val id: String,
    val state: PendingReactionState,
    val targetEventId: String,
    val reactionKey: String,
    val transactionId: String?,
    val reactionEventId: String?,
    val redactionTransactionId: String?,
    val failureMessage: String?
)
