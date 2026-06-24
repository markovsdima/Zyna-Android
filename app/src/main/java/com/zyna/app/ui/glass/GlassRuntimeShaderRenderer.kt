package com.zyna.app.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.zyna.app.R

/** Applies the AGSL refraction shader to already captured backdrop content. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class GlassRuntimeShaderRenderer(context: Context) {
    private val shader = try {
        RuntimeShader(context.readRawText(R.raw.zyna_glass_refraction))
    } catch (throwable: Throwable) {
        Log.w(TAG, "RuntimeShader compile failed; falling back to plain glass", throwable)
        null
    }
    private val node = RenderNode("ZynaGlassShader")
    private val fallbackTintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shaderEnabled = shader != null
    private var failureLogged = shader == null

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        style: GlassStyle,
        drawContent: (Canvas) -> Unit
    ) {
        val runtimeShader = shader
        if (
            runtimeShader == null ||
            !shaderEnabled ||
            !canvas.isHardwareAccelerated ||
            width <= 0 ||
            height <= 0
        ) {
            logFallbackReason(runtimeShader, canvas, width, height)
            drawFallback(canvas, width, height, style, drawContent)
            return
        }

        val shaderReady = try {
            updateUniforms(runtimeShader, width, height, style)
            node.setPosition(0, 0, width, height)
            node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(runtimeShader, "img"))
            true
        } catch (throwable: Throwable) {
            logShaderFailure(throwable)
            false
        }

        if (!shaderReady) {
            shaderEnabled = false
            drawFallback(canvas, width, height, style, drawContent)
            return
        }

        val recordingCanvas = node.beginRecording(width, height)
        try {
            drawContent(recordingCanvas)
        } finally {
            node.endRecording()
        }

        canvas.drawRenderNode(node)
    }

    private fun drawFallback(
        canvas: Canvas,
        width: Int,
        height: Int,
        style: GlassStyle,
        drawContent: (Canvas) -> Unit
    ) {
        drawContent(canvas)
        if (width > 0 && height > 0) {
            fallbackTintPaint.color = style.tintColor
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fallbackTintPaint)
        }
    }

    private fun updateUniforms(shader: RuntimeShader, width: Int, height: Int, style: GlassStyle) {
        val alpha = Color.alpha(style.tintColor) / 255f
        val red = Color.red(style.tintColor) / 255f * alpha
        val green = Color.green(style.tintColor) / 255f * alpha
        val blue = Color.blue(style.tintColor) / 255f * alpha

        shader.setFloatUniform("resolution", width.toFloat(), height.toFloat())
        shader.setFloatUniform("center", width / 2f, height / 2f)
        shader.setFloatUniform("halfSize", width / 2f, height / 2f)
        shader.setFloatUniform("radius", style.cornerRadiusPx)
        shader.setFloatUniform(
            "bezelWidth",
            maxOf(style.bevelWidthPx, 1f)
        )
        shader.setFloatUniform("glassThickness", style.refractionThicknessPx)
        shader.setFloatUniform("ior", style.refractionIndex)
        shader.setFloatUniform("squircleN", style.squircleExponent)
        shader.setFloatUniform("refractScale", style.refractionIntensity)
        shader.setFloatUniform("chromaSpread", style.chromaSpread)
        shader.setFloatUniform("adaptiveAppearance", style.adaptiveAppearance)
        shader.setFloatUniform("adaptiveContrast", style.adaptiveContrast)
        shader.setFloatUniform("tintPremul", red, green, blue, alpha)
    }

    private fun logFallbackReason(runtimeShader: RuntimeShader?, canvas: Canvas, width: Int, height: Int) {
        if (failureLogged) {
            return
        }
        failureLogged = true
        val reason = when {
            runtimeShader == null -> "shader_null"
            !shaderEnabled -> "shader_disabled"
            !canvas.isHardwareAccelerated -> "software_canvas"
            width <= 0 || height <= 0 -> "bad_size_${width}x$height"
            else -> "unknown"
        }
        Log.w(TAG, "RuntimeShader fallback before draw: reason=$reason, sdk=${Build.VERSION.SDK_INT}")
    }

    private fun logShaderFailure(throwable: Throwable) {
        if (failureLogged) {
            return
        }
        failureLogged = true
        Log.w(TAG, "RuntimeShader apply failed; falling back to plain glass", throwable)
    }

    private companion object {
        const val TAG = "ZynaGlass"
    }
}

private fun Context.readRawText(rawId: Int): String {
    return resources.openRawResource(rawId).bufferedReader().use { it.readText() }
}
