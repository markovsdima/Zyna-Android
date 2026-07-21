package com.zyna.app.data.profile

import kotlin.math.min

data class ProfileAvatarCropSpec(
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val zoom: Float = MIN_PROFILE_AVATAR_ZOOM
)

data class ProfileAvatarSourceRect(
    val left: Float,
    val top: Float,
    val size: Float
) {
    val right: Float
        get() = left + size

    val bottom: Float
        get() = top + size
}

object ProfileAvatarCropGeometry {
    fun clamp(
        crop: ProfileAvatarCropSpec,
        sourceWidth: Int,
        sourceHeight: Int
    ): ProfileAvatarCropSpec {
        val width = sourceWidth.coerceAtLeast(1).toFloat()
        val height = sourceHeight.coerceAtLeast(1).toFloat()
        val zoom = crop.zoom
            .takeIf(Float::isFinite)
            ?.coerceIn(MIN_PROFILE_AVATAR_ZOOM, MAX_PROFILE_AVATAR_ZOOM)
            ?: MIN_PROFILE_AVATAR_ZOOM
        val cropSize = min(width, height) / zoom
        val halfCrop = cropSize / 2f
        val centerX = (crop.centerX.takeIf(Float::isFinite) ?: 0.5f) * width
        val centerY = (crop.centerY.takeIf(Float::isFinite) ?: 0.5f) * height
        return ProfileAvatarCropSpec(
            centerX = centerX.coerceIn(halfCrop, width - halfCrop) / width,
            centerY = centerY.coerceIn(halfCrop, height - halfCrop) / height,
            zoom = zoom
        )
    }

    fun sourceRect(
        crop: ProfileAvatarCropSpec,
        sourceWidth: Int,
        sourceHeight: Int
    ): ProfileAvatarSourceRect {
        val width = sourceWidth.coerceAtLeast(1).toFloat()
        val height = sourceHeight.coerceAtLeast(1).toFloat()
        val clamped = clamp(crop, sourceWidth, sourceHeight)
        val size = min(width, height) / clamped.zoom
        return ProfileAvatarSourceRect(
            left = clamped.centerX * width - size / 2f,
            top = clamped.centerY * height - size / 2f,
            size = size
        )
    }

    fun transform(
        crop: ProfileAvatarCropSpec,
        sourceWidth: Int,
        sourceHeight: Int,
        viewportSize: Float,
        centroidX: Float,
        centroidY: Float,
        panX: Float,
        panY: Float,
        zoomFactor: Float
    ): ProfileAvatarCropSpec {
        val width = sourceWidth.coerceAtLeast(1).toFloat()
        val height = sourceHeight.coerceAtLeast(1).toFloat()
        val viewport = viewportSize.coerceAtLeast(1f)
        val current = clamp(crop, sourceWidth, sourceHeight)
        val currentCropSize = min(width, height) / current.zoom
        val currentScale = viewport / currentCropSize
        val nextZoom = (current.zoom * zoomFactor.takeIf(Float::isFinite).orOne())
            .coerceIn(MIN_PROFILE_AVATAR_ZOOM, MAX_PROFILE_AVATAR_ZOOM)
        val nextCropSize = min(width, height) / nextZoom
        val nextScale = viewport / nextCropSize
        val viewportCenter = viewport / 2f

        val focusSourceX = current.centerX * width +
            (centroidX - viewportCenter) / currentScale
        val focusSourceY = current.centerY * height +
            (centroidY - viewportCenter) / currentScale
        val nextCenterX = focusSourceX -
            (centroidX - viewportCenter) / nextScale -
            panX / nextScale
        val nextCenterY = focusSourceY -
            (centroidY - viewportCenter) / nextScale -
            panY / nextScale

        return clamp(
            crop = ProfileAvatarCropSpec(
                centerX = nextCenterX / width,
                centerY = nextCenterY / height,
                zoom = nextZoom
            ),
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight
        )
    }
}

const val MIN_PROFILE_AVATAR_ZOOM = 1f
const val MAX_PROFILE_AVATAR_ZOOM = 4f

private fun Float?.orOne(): Float {
    return this ?: 1f
}
