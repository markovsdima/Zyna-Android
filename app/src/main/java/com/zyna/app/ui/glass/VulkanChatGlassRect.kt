package com.zyna.app.ui.glass

internal data class VulkanChatGlassRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val cornerRadius: Float,
    val opacity: Float
) {
    fun isValid(): Boolean {
        return right > left && bottom > top && opacity > 0f && cornerRadius >= 0f
    }
}
