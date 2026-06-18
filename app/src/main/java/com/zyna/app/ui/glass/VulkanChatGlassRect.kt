package com.zyna.app.ui.glass

internal data class VulkanChatGlassRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val cornerRadius: Float,
    val opacity: Float,
    val bezelWidth: Float = cornerRadius * (36f / 26f),
    val glassThickness: Float = cornerRadius * (55f / 26f),
    val adaptiveAppearance: Float = 1f,
    val adaptiveContrast: Float = 0f,
    val shapeKind: Float = SHAPE_ROUNDED_RECT
) {
    fun isValid(): Boolean {
        return right > left &&
            bottom > top &&
            opacity > 0f &&
            cornerRadius >= 0f &&
            bezelWidth > 0f &&
            glassThickness > 0f &&
            adaptiveAppearance in 0f..1f &&
            adaptiveContrast in 0f..1f &&
            shapeKind in SHAPE_ROUNDED_RECT..SHAPE_CIRCLE
    }

    companion object {
        const val SHAPE_ROUNDED_RECT = 0f
        const val SHAPE_CIRCLE = 1f
    }
}
