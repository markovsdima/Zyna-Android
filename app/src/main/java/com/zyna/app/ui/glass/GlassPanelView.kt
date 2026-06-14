package com.zyna.app.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.View

/**
 * One rounded glass surface.
 *
 * The view clips itself, asks the controller for captured backdrop pixels, then
 * applies AGSL refraction on Android 13+ or a tinted backdrop fallback.
 */
class GlassPanelView @JvmOverloads constructor(
    context: Context,
    private val controller: GlassBackdropController,
    attrs: AttributeSet? = null
) : View(context, attrs), GlassBackdropSurface {
    override val view: View
        get() = this

    override var glassStyle: GlassStyle = defaultStyle()
        set(value) {
            if (field == value) {
                return
            }
            field = value
            controller.invalidateRegions()
            invalidate()
        }

    private val bounds = RectF()
    private val clipPath = Path()
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private var shaderRenderer: GlassRuntimeShaderRenderer? = null

    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        controller.register(this)
    }

    override fun onDetachedFromWindow() {
        controller.unregister(this)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        controller.invalidateRegions()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) {
            return
        }

        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        clipPath.rewind()
        clipPath.addRoundRect(
            bounds,
            glassStyle.cornerRadiusPx,
            glassStyle.cornerRadiusPx,
            Path.Direction.CW
        )

        val save = canvas.save()
        canvas.clipPath(clipPath)
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            glassStyle.refractionIntensity > 0f &&
            canvas.isHardwareAccelerated
        ) {
            val renderer = shaderRenderer ?: GlassRuntimeShaderRenderer(context).also {
                shaderRenderer = it
            }
            renderer.draw(canvas, width, height, glassStyle) { recordingCanvas ->
                controller.drawBackdropContent(recordingCanvas, this, bounds, glassStyle)
            }
        } else {
            controller.drawBackdropContent(canvas, this, bounds, glassStyle)
            tintPaint.color = glassStyle.tintColor
            canvas.drawRect(bounds, tintPaint)
        }
        canvas.restoreToCount(save)

        if (glassStyle.strokeWidthPx > 0f) {
            strokePaint.strokeWidth = glassStyle.strokeWidthPx
            strokePaint.color = glassStyle.strokeColor
            val inset = glassStyle.strokeWidthPx / 2f
            bounds.inset(inset, inset)
            canvas.drawRoundRect(
                bounds,
                (glassStyle.cornerRadiusPx - inset).coerceAtLeast(0f),
                (glassStyle.cornerRadiusPx - inset).coerceAtLeast(0f),
                strokePaint
            )
        }
    }

    private fun defaultStyle(): GlassStyle {
        val density = resources.displayMetrics.density
        return GlassStyle(
            cornerRadiusPx = 24f.dpToPx(density),
            blurRadiusPx = 4f.dpToPx(density),
            downscale = 3,
            tintColor = 0x9fffffff.toInt(),
            strokeColor = 0x66ffffff,
            strokeWidthPx = 1f.dpToPx(density),
            refractionIntensity = 1.1f,
            bevelWidthPx = 36f.dpToPx(density),
            refractionThicknessPx = 55f.dpToPx(density),
            chromaSpread = 0.02f,
            adaptiveContrast = 0.24f
        )
    }
}
