package com.zyna.app.ui.chat.render

import android.graphics.Canvas

internal interface MessageContentRenderer {
    fun supports(content: MessageContent): Boolean

    fun measure(
        message: MessageRenderModel,
        theme: MessageRenderTheme,
        maxWidthPx: Int
    ): MessageContentLayout

    fun draw(canvas: Canvas, layout: MessageContentLayout)
}

internal interface MessageContentLayout {
    val width: Int
    val height: Int
}
