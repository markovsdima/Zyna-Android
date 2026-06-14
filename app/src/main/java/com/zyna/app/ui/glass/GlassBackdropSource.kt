package com.zyna.app.ui.glass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/** Draws the UI that should be visible behind glass into root-relative bounds. */
interface GlassBackdropSource {
    val fallbackColor: Int

    fun capture(canvas: Canvas, regionInRoot: RectF, root: ViewGroup)
}

/**
 * Backdrop source for chat lists.
 *
 * It keeps RecyclerView's own draw path for visual correctness, but skips the
 * capture when no attached child intersects the requested glass region.
 */
class RecyclerViewGlassBackdropSource(
    private val recyclerView: RecyclerView,
    override var fallbackColor: Int = Color.TRANSPARENT
) : GlassBackdropSource {
    private val recyclerOffset = PointF()
    private val localRegion = RectF()

    override fun capture(canvas: Canvas, regionInRoot: RectF, root: ViewGroup) {
        canvas.drawColor(fallbackColor)
        if (!recyclerView.isShown || recyclerView.width <= 0 || recyclerView.height <= 0) {
            return
        }
        if (!recyclerView.offsetInRoot(root, recyclerOffset)) {
            return
        }

        localRegion.set(regionInRoot)
        localRegion.offset(-recyclerOffset.x, -recyclerOffset.y)
        if (!localRegion.intersects(0f, 0f, recyclerView.width.toFloat(), recyclerView.height.toFloat())) {
            return
        }
        if (!hasOverlappingChild(localRegion)) {
            return
        }

        val save = canvas.save()
        canvas.translate(recyclerOffset.x, recyclerOffset.y)
        canvas.clipRect(localRegion)
        recyclerView.draw(canvas)
        canvas.restoreToCount(save)
    }

    private fun hasOverlappingChild(region: RectF): Boolean {
        for (index in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(index)
            val left = child.x
            val top = child.y
            val right = left + child.width
            val bottom = top + child.height
            if (region.intersects(left, top, right, bottom)) {
                return true
            }
        }
        return false
    }
}

internal fun View.offsetInRoot(root: View, out: PointF): Boolean {
    if (!isAttachedToWindow || !root.isAttachedToWindow) {
        out.set(0f, 0f)
        return false
    }

    val viewLocation = IntArray(2)
    val rootLocation = IntArray(2)
    getLocationOnScreen(viewLocation)
    root.getLocationOnScreen(rootLocation)
    out.set(
        (viewLocation[0] - rootLocation[0]).toFloat(),
        (viewLocation[1] - rootLocation[1]).toFloat()
    )
    return true
}
