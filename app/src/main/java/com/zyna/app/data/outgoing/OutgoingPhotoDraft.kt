package com.zyna.app.data.outgoing

import com.zyna.app.data.messaging.CaptionPlacement
import com.zyna.app.data.messaging.MediaGroupLayoutOverride

data class OutgoingPhotoDraftItem(
    val localPath: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val thumbnailLocalPath: String?,
    val thumbnailMimeType: String?,
    val thumbnailWidth: Int?,
    val thumbnailHeight: Int?,
    val thumbnailSizeBytes: Long?,
    val blurhash: String?
)

data class OutgoingPhotoDraft(
    val items: List<OutgoingPhotoDraftItem>,
    val caption: String?,
    val captionPlacement: CaptionPlacement,
    val layoutOverride: MediaGroupLayoutOverride?
)
