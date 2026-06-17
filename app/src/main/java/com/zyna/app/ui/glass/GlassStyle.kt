package com.zyna.app.ui.glass

import android.graphics.Color
import kotlin.math.roundToInt

/** Visual and capture parameters for one glass surface. Size values are pixels. */
data class GlassStyle(
    val cornerRadiusPx: Float,
    val blurRadiusPx: Float,
    val downscale: Int,
    val tintColor: Int,
    val strokeColor: Int,
    val strokeWidthPx: Float,
    val refractionIntensity: Float = 0.18f,
    val bevelWidthPx: Float = 36f,
    val refractionThicknessPx: Float = 18f,
    val refractionIndex: Float = 1.50f,
    val squircleExponent: Float = 6f,
    val chromaSpread: Float = 0.02f,
    val adaptiveAppearance: Float = 1f,
    val adaptiveContrast: Float = 0.34f
) {
    /** Extra backdrop pixels needed so blur/refraction can sample beyond edges. */
    val captureOutsetPx: Float
        get() = blurRadiusPx * 2f + cornerRadiusPx * 0.25f
}

/** Theme colors used by glass controls and their text. */
data class GlassPalette(
    val background: Int,
    val glassTint: Int,
    val glassTintStrong: Int,
    val stroke: Int,
    val text: Int,
    val hint: Int
)

/** Foreground colors derived from the same adaptive material uniforms as the glass shader. */
internal data class GlassAdaptiveMaterial(
    val appearance: Float,
    val contrast: Float
) {
    val primaryForeground: Int
        get() = foreground(darkMaterialWhite = 0.96f, lightMaterialWhite = 0.08f, alpha = 1f)

    val secondaryForeground: Int
        get() = foreground(darkMaterialWhite = 0.74f, lightMaterialWhite = 0.34f, alpha = 0.95f)

    val glyphForeground: Int
        get() = foreground(darkMaterialWhite = 0.86f, lightMaterialWhite = 0.42f, alpha = 1f)

    private fun foreground(
        darkMaterialWhite: Float,
        lightMaterialWhite: Float,
        alpha: Float
    ): Int {
        val t = smoothstep(appearance.coerceIn(0f, 1f))
        val white = darkMaterialWhite + (lightMaterialWhite - darkMaterialWhite) * t
        val channel = (white.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        val alphaChannel = (alpha.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        return Color.argb(alphaChannel, channel, channel, channel)
    }

    private fun smoothstep(value: Float): Float {
        return value * value * (3f - 2f * value)
    }

    companion object {
        val Light = GlassAdaptiveMaterial(appearance = 1f, contrast = 0f)
    }
}

internal fun Float.dpToPx(density: Float): Float = this * density

internal fun Int.dpToPx(density: Float): Int = (this * density).toInt()
