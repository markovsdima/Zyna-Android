package com.zyna.app.ui.glass

internal data class VulkanChatGlassRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val cornerRadius: Float,
    val opacity: Float,
    val bezelWidth: Float = cornerRadius * (36f / 26f),
    val glassThickness: Float = cornerRadius * (55f / 26f)
) {
    fun isValid(): Boolean {
        return right > left &&
            bottom > top &&
            opacity > 0f &&
            cornerRadius >= 0f &&
            bezelWidth > 0f &&
            glassThickness > 0f
    }
}
