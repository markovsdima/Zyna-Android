package com.zyna.app.ui.glass

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

internal fun Float.dpToPx(density: Float): Float = this * density

internal fun Int.dpToPx(density: Float): Int = (this * density).toInt()
