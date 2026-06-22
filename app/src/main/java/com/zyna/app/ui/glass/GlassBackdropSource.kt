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
 * RecyclerView capture when no attached child intersects the requested glass region.
 * Optional overlay views are drawn after the list so transient UI under glass can
 * participate in the backdrop.
 */
class RecyclerViewGlassBackdropSource(
    private val recyclerView: RecyclerView,
    override var fallbackColor: Int = Color.TRANSPARENT,
    private val overlayViews: List<View> = emptyList()
) : GlassBackdropSource {
    private val recyclerOffset = PointF()
    private val viewOffset = PointF()
    private val localRegion = RectF()

    override fun capture(canvas: Canvas, regionInRoot: RectF, root: ViewGroup) {
        canvas.drawColor(fallbackColor)
        drawRecyclerView(canvas, regionInRoot, root)
        overlayViews.forEach { overlay ->
            drawOverlayView(canvas, overlay, regionInRoot, root)
        }
    }

    private fun drawRecyclerView(canvas: Canvas, regionInRoot: RectF, root: ViewGroup) {
        if (!recyclerView.isShown || recyclerView.width <= 0 || recyclerView.height <= 0) return
        if (!recyclerView.offsetInRoot(root, recyclerOffset)) return
        localRegion.set(regionInRoot)
        localRegion.offset(-recyclerOffset.x, -recyclerOffset.y)
        if (
            !localRegion.intersects(
                0f,
                0f,
                recyclerView.width.toFloat(),
                recyclerView.height.toFloat()
            )
        ) {
            return
        }
        if (!hasOverlappingChild(localRegion)) return

        val save = canvas.save()
        canvas.translate(recyclerOffset.x, recyclerOffset.y)
        canvas.clipRect(localRegion)
        recyclerView.draw(canvas)
        canvas.restoreToCount(save)
    }

    private fun drawOverlayView(
        canvas: Canvas,
        view: View,
        regionInRoot: RectF,
        root: ViewGroup
    ) {
        if (!view.isShown || view.width <= 0 || view.height <= 0) return
        if (!view.offsetInRoot(root, viewOffset)) return
        localRegion.set(regionInRoot)
        localRegion.offset(-viewOffset.x, -viewOffset.y)
        if (!localRegion.intersects(0f, 0f, view.width.toFloat(), view.height.toFloat())) return

        val save = canvas.save()
        canvas.translate(viewOffset.x, viewOffset.y)
        canvas.clipRect(localRegion)
        view.draw(canvas)
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
