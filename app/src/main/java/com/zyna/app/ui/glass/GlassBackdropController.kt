package com.zyna.app.ui.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import java.lang.ref.WeakReference
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Coordinates glass surfaces inside one root view.
 *
 * Each surface asks this controller for backdrop pixels during draw. The
 * controller merges nearby surface bounds so a cluster of glass controls can
 * share one captured backdrop for the same content version.
 */
class GlassBackdropController(
    private val root: ViewGroup
) {
    var source: GlassBackdropSource? = null
        set(value) {
            field = value
            invalidateBackdrop()
        }

    private val surfaceRefs = ArrayList<WeakReference<GlassBackdropSurface>>()
    private val regions = ArrayList<GlassCaptureRegion>()
    private val pendingBounds = ArrayList<Rect>()
    private val tmpPoint = PointF()
    private val tmpTargetRoot = RectF()
    private val tmpPendingRect = Rect()
    private var regionsDirty = true
    private var contentVersion = 0L

    fun register(surface: GlassBackdropSurface) {
        pruneSurfaces()
        if (surfaceRefs.any { it.get() === surface }) {
            return
        }
        surfaceRefs.add(WeakReference(surface))
        invalidateRegions()
    }

    fun unregister(surface: GlassBackdropSurface) {
        surfaceRefs.removeAll { it.get() == null || it.get() === surface }
        invalidateRegions()
    }

    fun invalidateRegions() {
        regionsDirty = true
        invalidateBackdrop()
    }

    fun invalidateBackdrop() {
        contentVersion++
        root.postInvalidateOnAnimation()
        forEachSurface { it.view.postInvalidateOnAnimation() }
    }

    internal fun drawBackdropContent(
        canvas: Canvas,
        surface: GlassBackdropSurface,
        localBounds: RectF,
        style: GlassStyle
    ) {
        val backdropSource = source
        if (backdropSource == null || root.width <= 0 || root.height <= 0) {
            canvas.drawColor(style.tintColor)
            return
        }

        if (!surface.view.offsetInRoot(root, tmpPoint)) {
            canvas.drawColor(style.tintColor)
            return
        }

        tmpTargetRoot.set(localBounds)
        tmpTargetRoot.offset(tmpPoint.x, tmpPoint.y)

        ensureRegions()
        val region = findRegionFor(tmpTargetRoot) ?: run {
            canvas.drawColor(style.tintColor)
            return
        }
        region.updateIfNeeded(backdropSource, root, style, contentVersion)
        region.draw(canvas, tmpTargetRoot, localBounds)
    }

    private fun ensureRegions() {
        if (!regionsDirty) {
            return
        }

        collectMergedBounds()
        while (regions.size < pendingBounds.size) {
            regions.add(GlassCaptureRegion())
        }
        while (regions.size > pendingBounds.size) {
            regions.removeAt(regions.lastIndex).release()
        }
        for (index in pendingBounds.indices) {
            regions[index].setBounds(pendingBounds[index])
        }
        regionsDirty = false
    }

    private fun findRegionFor(boundsInRoot: RectF): GlassCaptureRegion? {
        val left = boundsInRoot.left.roundToInt()
        val top = boundsInRoot.top.roundToInt()
        val right = boundsInRoot.right.roundToInt()
        val bottom = boundsInRoot.bottom.roundToInt()
        for (region in regions) {
            if (region.bounds.contains(left, top, right, bottom)) {
                return region
            }
        }
        return regions.firstOrNull { it.bounds.intersects(left, top, right, bottom) }
    }

    private fun collectMergedBounds() {
        pendingBounds.clear()
        val rootWidth = root.width
        val rootHeight = root.height
        if (rootWidth <= 0 || rootHeight <= 0) {
            return
        }

        forEachSurface { surface ->
            val view = surface.view
            if (!surface.shouldCaptureBackdrop || view.width <= 0 || view.height <= 0) {
                return@forEachSurface
            }
            if (!view.offsetInRoot(root, tmpPoint)) {
                return@forEachSurface
            }

            val outset = surface.glassStyle.captureOutsetPx
            tmpPendingRect.set(
                (tmpPoint.x - outset).roundToInt().coerceIn(0, rootWidth),
                (tmpPoint.y - outset).roundToInt().coerceIn(0, rootHeight),
                (tmpPoint.x + view.width + outset).roundToInt().coerceIn(0, rootWidth),
                (tmpPoint.y + view.height + outset).roundToInt().coerceIn(0, rootHeight)
            )
            if (tmpPendingRect.width() <= 0 || tmpPendingRect.height() <= 0) {
                return@forEachSurface
            }
            mergeIntoPending(tmpPendingRect)
        }
    }

    private fun mergeIntoPending(rect: Rect) {
        for (existing in pendingBounds) {
            if (Rect.intersects(existing, rect) || existing.nearlyTouches(rect, 24)) {
                existing.union(rect)
                mergeOverlaps()
                return
            }
        }
        pendingBounds.add(Rect(rect))
    }

    private fun mergeOverlaps() {
        var changed: Boolean
        do {
            changed = false
            var outer = 0
            while (outer < pendingBounds.size) {
                var inner = outer + 1
                while (inner < pendingBounds.size) {
                    val a = pendingBounds[outer]
                    val b = pendingBounds[inner]
                    if (Rect.intersects(a, b) || a.nearlyTouches(b, 24)) {
                        a.union(b)
                        pendingBounds.removeAt(inner)
                        changed = true
                    } else {
                        inner++
                    }
                }
                outer++
            }
        } while (changed)
    }

    private fun forEachSurface(block: (GlassBackdropSurface) -> Unit) {
        val iterator = surfaceRefs.iterator()
        while (iterator.hasNext()) {
            val surface = iterator.next().get()
            if (surface == null) {
                iterator.remove()
            } else {
                block(surface)
            }
        }
    }

    private fun pruneSurfaces() {
        surfaceRefs.removeAll { it.get() == null }
    }
}

/** A view that can draw captured backdrop content through its own glass style. */
interface GlassBackdropSurface {
    val view: View
    val glassStyle: GlassStyle
    val shouldCaptureBackdrop: Boolean
        get() = view.isShown && view.alpha > 0f
}

/** Cached backing content for one merged root-relative capture area. */
private class GlassCaptureRegion {
    val bounds = Rect()
    private var invalidated = true
    private var lastStyleKey = 0L
    private var lastContentVersion = Long.MIN_VALUE
    private val hardwareNode: HardwareGlassNode? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) HardwareGlassNode() else null
    private val bitmapNode = BitmapGlassNode()

    fun setBounds(newBounds: Rect) {
        if (bounds != newBounds) {
            bounds.set(newBounds)
            invalidated = true
        }
    }

    fun updateIfNeeded(
        source: GlassBackdropSource,
        root: ViewGroup,
        style: GlassStyle,
        contentVersion: Long
    ) {
        val styleKey = style.captureStyleKey()
        if (!invalidated && contentVersion == lastContentVersion && styleKey == lastStyleKey) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hardwareNode != null) {
            hardwareNode.update(source, root, bounds, style)
        } else {
            bitmapNode.update(source, root, bounds, style)
        }

        lastStyleKey = styleKey
        lastContentVersion = contentVersion
        invalidated = false
    }

    fun draw(canvas: Canvas, targetRootBounds: RectF, targetLocalBounds: RectF) {
        val surfaceRootX = targetRootBounds.left - targetLocalBounds.left
        val surfaceRootY = targetRootBounds.top - targetLocalBounds.top
        val localLeft = bounds.left - surfaceRootX
        val localTop = bounds.top - surfaceRootY

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hardwareNode != null && canvas.isHardwareAccelerated) {
            hardwareNode.draw(canvas, localLeft, localTop)
        } else {
            bitmapNode.draw(canvas, localLeft, localTop, bounds.width().toFloat(), bounds.height().toFloat())
        }
    }

    fun release() {
        bitmapNode.release()
    }
}

/** Android 12+ path: capture into RenderNodes and use RenderEffect for blur. */
@RequiresApi(Build.VERSION_CODES.S)
private class HardwareGlassNode {
    private val sourceNode = RenderNode("ZynaGlassSource")
    private val blurNode = RenderNode("ZynaGlassBlur")
    private val restoredNode = RenderNode("ZynaGlassRestored")
    private val captureBounds = RectF()
    private var cachedBlurRadius = -1f
    private var cachedBlurEffect: RenderEffect? = null

    fun update(source: GlassBackdropSource, root: ViewGroup, bounds: Rect, style: GlassStyle) {
        val shouldBlur = style.blurRadiusPx > 0.5f
        val downscale = if (shouldBlur) style.downscale.coerceIn(1, 12) else 1
        val fullWidth = max(1, bounds.width())
        val fullHeight = max(1, bounds.height())
        val scaledWidth = max(1, ceil(fullWidth / downscale.toFloat()).toInt())
        val scaledHeight = max(1, ceil(fullHeight / downscale.toFloat()).toInt())

        sourceNode.setPosition(0, 0, scaledWidth, scaledHeight)
        val sourceCanvas = sourceNode.beginRecording(scaledWidth, scaledHeight)
        sourceCanvas.scale(1f / downscale, 1f / downscale)
        sourceCanvas.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
        captureBounds.set(bounds)
        source.capture(sourceCanvas, captureBounds, root)
        sourceNode.endRecording()

        if (shouldBlur) {
            val blurRadius = max(1f, style.blurRadiusPx / downscale)
            blurNode.setRenderEffect(blurEffectFor(blurRadius))
            blurNode.setPosition(0, 0, scaledWidth, scaledHeight)
            val blurCanvas = blurNode.beginRecording(scaledWidth, scaledHeight)
            blurCanvas.drawRenderNode(sourceNode)
            blurNode.endRecording()
        }

        restoredNode.setPosition(0, 0, fullWidth, fullHeight)
        val restoredCanvas = restoredNode.beginRecording(fullWidth, fullHeight)
        if (shouldBlur) {
            restoredCanvas.scale(downscale.toFloat(), downscale.toFloat())
            restoredCanvas.drawRenderNode(blurNode)
        } else {
            restoredCanvas.drawRenderNode(sourceNode)
        }
        restoredNode.endRecording()
    }

    fun draw(canvas: Canvas, left: Float, top: Float) {
        val save = canvas.save()
        canvas.translate(left, top)
        canvas.drawRenderNode(restoredNode)
        canvas.restoreToCount(save)
    }

    private fun blurEffectFor(radius: Float): RenderEffect {
        val effect = cachedBlurEffect
        if (effect != null && cachedBlurRadius == radius) {
            return effect
        }
        cachedBlurRadius = radius
        return RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP).also {
            cachedBlurEffect = it
        }
    }
}

/** Older/software fallback: capture into a reusable bitmap and blur on CPU. */
private class BitmapGlassNode {
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private val captureBounds = RectF()

    fun update(source: GlassBackdropSource, root: ViewGroup, bounds: Rect, style: GlassStyle) {
        val shouldBlur = style.blurRadiusPx > 0.5f
        val downscale = if (shouldBlur) style.downscale.coerceIn(1, 12) else 1
        val scaledWidth = max(1, ceil(bounds.width() / downscale.toFloat()).toInt())
        val scaledHeight = max(1, ceil(bounds.height() / downscale.toFloat()).toInt())

        val target = bitmap
            ?.takeIf { !it.isRecycled && it.width == scaledWidth && it.height == scaledHeight }
            ?: Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888).also {
                bitmap?.recycle()
                bitmap = it
            }

        target.eraseColor(0)
        val bitmapCanvas = Canvas(target)
        bitmapCanvas.scale(1f / downscale, 1f / downscale)
        bitmapCanvas.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
        captureBounds.set(bounds)
        source.capture(bitmapCanvas, captureBounds, root)

        if (shouldBlur) {
            val blurRadius = max(1, (style.blurRadiusPx / downscale).roundToInt())
            GlassBoxBlur.blur(target, blurRadius, passes = 2)
        }
    }

    fun draw(canvas: Canvas, left: Float, top: Float, width: Float, height: Float) {
        val target = bitmap ?: return
        dst.set(left, top, left + width, top + height)
        canvas.drawBitmap(target, null, dst, paint)
    }

    fun release() {
        bitmap?.recycle()
        bitmap = null
    }
}

private fun GlassStyle.captureStyleKey(): Long {
    return blurRadiusPx.toBits().toLong() * 31L + downscale.toLong()
}

private fun Rect.nearlyTouches(other: Rect, distance: Int): Boolean {
    return left <= other.right + distance &&
        right + distance >= other.left &&
        top <= other.bottom + distance &&
        bottom + distance >= other.top
}
