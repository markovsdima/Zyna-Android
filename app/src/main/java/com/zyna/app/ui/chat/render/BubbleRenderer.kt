package com.zyna.app.ui.chat.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.roundToInt

internal class BubbleRenderer(
    density: Float
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val radii = FloatArray(8)
    private val radius = 18.dpToPx(density).toFloat()
    private val nearRadius = 6.dpToPx(density).toFloat()

    private var lastLeft = Float.NaN
    private var lastTop = Float.NaN
    private var lastRight = Float.NaN
    private var lastBottom = Float.NaN
    private var lastOutgoing = false
    private var lastFirst = false
    private var lastLast = false

    fun draw(
        canvas: Canvas,
        rect: RectF,
        fillColor: Int,
        message: MessageRenderModel
    ) {
        rebuildPathIfNeeded(rect, message)
        paint.color = fillColor
        canvas.drawPath(path, paint)
    }

    fun drawShader(
        canvas: Canvas,
        rect: RectF,
        shader: Shader,
        message: MessageRenderModel,
        alpha: Int = 255
    ) {
        rebuildPathIfNeeded(rect, message)
        paint.alpha = alpha.coerceIn(0, 255)
        paint.shader = shader
        canvas.drawPath(path, paint)
        paint.shader = null
        paint.alpha = 255
    }

    fun drawOverlay(
        canvas: Canvas,
        rect: RectF,
        color: Int,
        message: MessageRenderModel
    ) {
        rebuildPathIfNeeded(rect, message)
        paint.color = color
        canvas.drawPath(path, paint)
    }

    private fun rebuildPathIfNeeded(rect: RectF, message: MessageRenderModel) {
        if (
            rect.left == lastLeft &&
            rect.top == lastTop &&
            rect.right == lastRight &&
            rect.bottom == lastBottom &&
            message.isOutgoing == lastOutgoing &&
            message.cluster.isFirstInCluster == lastFirst &&
            message.cluster.isLastInCluster == lastLast
        ) {
            return
        }

        lastLeft = rect.left
        lastTop = rect.top
        lastRight = rect.right
        lastBottom = rect.bottom
        lastOutgoing = message.isOutgoing
        lastFirst = message.cluster.isFirstInCluster
        lastLast = message.cluster.isLastInCluster

        val topJoinRadius = if (message.cluster.isFirstInCluster) radius else nearRadius
        val bottomJoinRadius = if (message.cluster.isLastInCluster) radius else nearRadius
        val topLeft = if (!message.isOutgoing) topJoinRadius else radius
        val topRight = if (message.isOutgoing) topJoinRadius else radius
        val bottomRight = if (message.isOutgoing) bottomJoinRadius else radius
        val bottomLeft = if (!message.isOutgoing) bottomJoinRadius else radius

        radii[0] = topLeft
        radii[1] = topLeft
        radii[2] = topRight
        radii[3] = topRight
        radii[4] = bottomRight
        radii[5] = bottomRight
        radii[6] = bottomLeft
        radii[7] = bottomLeft

        path.rewind()
        path.addRoundRect(rect, radii, Path.Direction.CW)
    }
}

internal fun Int.dpToPx(density: Float): Int {
    return (this * density).roundToInt()
}
