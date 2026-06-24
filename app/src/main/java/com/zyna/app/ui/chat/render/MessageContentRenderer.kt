package com.zyna.app.ui.chat.render

import android.graphics.Canvas

internal interface MessageContentRenderer {
    fun supports(content: MessageContent): Boolean

    fun chrome(message: MessageRenderModel): MessageContentChrome {
        return MessageContentChrome.PADDED_BUBBLE
    }

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

internal enum class MessageContentChrome {
    PADDED_BUBBLE,
    FLUSH_BUBBLE,
    BARE
}
