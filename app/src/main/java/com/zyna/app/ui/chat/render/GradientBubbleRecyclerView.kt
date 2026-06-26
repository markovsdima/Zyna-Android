package com.zyna.app.ui.chat.render

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

internal class GradientBubbleRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : RecyclerView(context, attrs) {
    private val bubbleGradientRenderer = MessageBubbleGradientRenderer()

    override fun dispatchDraw(canvas: Canvas) {
        drawVisibleBubbleGradients(canvas)
        super.dispatchDraw(canvas)
    }

    private fun drawVisibleBubbleGradients(canvas: Canvas) {
        if (width <= 0 || height <= 0) {
            return
        }
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child !is MessageCellView || child.visibility != View.VISIBLE) {
                continue
            }
            val childAlpha = (child.alpha * 255f).roundToInt().coerceIn(0, 255)
            if (childAlpha <= 0) {
                continue
            }

            val childLeft = child.left.toFloat() + child.translationX
            val childTop = child.top.toFloat() + child.translationY
            val save = canvas.save()
            canvas.translate(childLeft, childTop)
            child.drawBubbleBackgroundInParent(
                canvas = canvas,
                gradientRenderer = bubbleGradientRenderer,
                viewportWidth = width,
                viewportHeight = height,
                viewportOffsetX = childLeft,
                viewportOffsetY = childTop,
                alpha = childAlpha
            )
            canvas.restoreToCount(save)
        }
    }
}
