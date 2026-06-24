package com.zyna.app.ui.chat.viewer

import android.graphics.RectF
import com.zyna.app.data.matrix.MatrixImageInfo

internal data class PhotoViewerItem(
    val id: String,
    val imageInfo: MatrixImageInfo,
    val caption: String?
)

internal interface PhotoViewerSource {
    val isPhotoViewerSourceAvailable: Boolean

    fun photoSourceBoundsInScreen(itemId: String): RectF?
}

internal data class PhotoViewerOpenRequest(
    val source: PhotoViewerSource,
    val messageId: String,
    val items: List<PhotoViewerItem>,
    val selectedIndex: Int,
    val sourceBoundsInScreen: RectF,
    val sourceCornerRadiusPx: Float
)
