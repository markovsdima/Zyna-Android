package com.zyna.app.data.outgoing

enum class OutgoingEnvelopeKind {
    TEXT
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
    val createdAtMillis: Long,
    val failureMessage: String?
)
