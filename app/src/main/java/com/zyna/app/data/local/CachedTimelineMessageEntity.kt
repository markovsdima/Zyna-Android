package com.zyna.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "timeline_messages",
    primaryKeys = ["userId", "roomId", "id"],
    indices = [
        Index(value = ["userId", "roomId", "timelineIndex"]),
        Index(value = ["userId", "roomId", "timestampMillis"]),
        Index(value = ["userId", "roomId", "eventId"]),
        Index(value = ["userId", "roomId", "transactionId"])
    ]
)
data class CachedTimelineMessageEntity(
    val userId: String,
    val roomId: String,
    val id: String,
    val eventId: String?,
    val transactionId: String?,
    val timelineIndex: Int,
    val sender: String,
    val senderDisplayName: String?,
    val body: String,
    val timestampMillis: Long,
    val isOwn: Boolean,
    @ColumnInfo(defaultValue = "TEXT")
    val contentType: String,
    val imageSourceJson: String?,
    val imageThumbnailSourceJson: String?,
    val imageWidth: Int?,
    val imageHeight: Int?,
    val imageCaption: String?,
    val imageMimeType: String?,
    val imageBlurhash: String?,
    val audioSourceJson: String?,
    val audioFilename: String?,
    val audioCaption: String?,
    val audioMimeType: String?,
    val audioSizeBytes: Long?,
    val audioDurationMillis: Long?,
    val audioWaveform: String?,
    @ColumnInfo(defaultValue = "0")
    val audioIsVoice: Boolean,
    val deliveryState: String,
    val replyEventId: String?,
    val replySenderId: String?,
    val replySenderDisplayName: String?,
    val replyBody: String?,
    val forwardedFrom: String?,
    val zynaAttributesJson: String?,
    val timelineDetailsJson: String?,
    @ColumnInfo(defaultValue = "0")
    val isEdited: Boolean,
    @ColumnInfo(defaultValue = "0")
    val isEditPending: Boolean,
    @ColumnInfo(defaultValue = "0")
    val isEditFailed: Boolean,
    val latestEditEventId: String?,
    val editTransactionId: String?,
    val pendingEditBody: String?,
    @ColumnInfo(defaultValue = "[]")
    val reactionsJson: String,
    val updatedAtMillis: Long
)
