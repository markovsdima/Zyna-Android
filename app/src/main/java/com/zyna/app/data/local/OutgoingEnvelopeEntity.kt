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
    val targetEventId: String?,
    val targetTransactionId: String?,
    val targetBody: String?,
    val targetContentType: String?,
    val replyEventId: String?,
    val replySenderId: String?,
    val replySenderDisplayName: String?,
    val replyBody: String?,
    val forwardedFrom: String?,
    val imageLocalPath: String?,
    val imageMimeType: String?,
    val imageWidth: Int?,
    val imageHeight: Int?,
    val imageSizeBytes: Long?,
    val imageThumbnailLocalPath: String?,
    val imageThumbnailMimeType: String?,
    val imageThumbnailWidth: Int?,
    val imageThumbnailHeight: Int?,
    val imageThumbnailSizeBytes: Long?,
    val imageCaption: String?,
    val zynaAttributesJson: String?,
    val imageSourceJson: String?,
    val imageThumbnailSourceJson: String?,
    val imageBlurhash: String?,
    val imageUploadedJson: String?,
    val imageUploadedAtMillis: Long?,
    val body: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val failureMessage: String?,
    val voiceLocalPath: String? = null,
    val voiceMimeType: String? = null,
    val voiceSizeBytes: Long? = null,
    val voiceDurationMillis: Long? = null,
    val voiceWaveform: String? = null,
    val voiceUploadedJson: String? = null,
    val voiceUploadedAtMillis: Long? = null
)
