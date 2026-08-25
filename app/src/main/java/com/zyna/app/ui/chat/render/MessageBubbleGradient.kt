package com.zyna.app.ui.chat.render

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Shader
import com.zyna.app.ui.chat.theme.MessageBubbleGradientSpec
import kotlin.math.max

internal class MessageBubbleGradientRenderer {
    private val matrix = Matrix()
    private var cachedSpec: MessageBubbleGradientSpec? = null
    private var cachedWidth = 0
    private var cachedHeight = 0
    private var cachedShader: LinearGradient? = null

    fun drawBubble(
        canvas: Canvas,
        bubbleRenderer: BubbleRenderer,
        rect: RectF,
        message: MessageRenderModel,
        spec: MessageBubbleGradientSpec,
        viewportWidth: Int,
        viewportHeight: Int,
        viewportOffsetX: Float,
        viewportOffsetY: Float,
        alpha: Int = 255
    ): Boolean {
        val shader = shaderFor(spec, viewportWidth, viewportHeight) ?: return false
        matrix.reset()
        matrix.setTranslate(-viewportOffsetX, -viewportOffsetY)
        shader.setLocalMatrix(matrix)
        bubbleRenderer.drawShader(
            canvas = canvas,
            rect = rect,
            shader = shader,
            message = message,
            alpha = alpha
        )
        return true
    }

    private fun shaderFor(
        spec: MessageBubbleGradientSpec,
        viewportWidth: Int,
        viewportHeight: Int
    ): LinearGradient? {
        if (viewportWidth <= 0 || viewportHeight <= 0 || spec.colors.size < 2) {
            cachedShader = null
            cachedSpec = null
            cachedWidth = 0
            cachedHeight = 0
            return null
        }
        if (
            cachedShader != null &&
            cachedSpec == spec &&
            cachedWidth == viewportWidth &&
            cachedHeight == viewportHeight
        ) {
            return cachedShader
        }

        cachedSpec = spec
        cachedWidth = viewportWidth
        cachedHeight = viewportHeight

        val width = max(1, viewportWidth).toFloat()
        val height = max(1, viewportHeight).toFloat()
        cachedShader = LinearGradient(
            spec.startX * width,
            spec.startY * height,
            spec.endX * width,
            spec.endY * height,
            spec.colors.toIntArray(),
            spec.positions?.toFloatArray(),
            Shader.TileMode.CLAMP
        )
        return cachedShader
    }
}
