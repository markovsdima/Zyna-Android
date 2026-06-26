package com.zyna.app.ui.chat.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max

internal data class MessageBubbleGradientSpec(
    val colors: List<Int>,
    val positions: List<Float>? = null,
    val startX: Float = 0.5f,
    val startY: Float = 0f,
    val endX: Float = 0.5f,
    val endY: Float = 1f
) {
    init {
        require(colors.size >= 2) { "Bubble gradient needs at least two colors." }
        require(positions == null || positions.size == colors.size) {
            "Bubble gradient positions must match color count."
        }
    }
}

internal data class ChatBubbleTheme(
    val id: String,
    val title: String,
    val outgoingGradient: MessageBubbleGradientSpec
) {
    val actionAccentColor: Int
        get() = outgoingGradient.colors.getOrElse(1) { outgoingGradient.colors.last() }
}

internal object ChatBubbleThemes {
    val zynaBlue = theme(
        id = "zynaBlue",
        title = "Zyna Blue",
        colors = listOf(0x8AB8FF, 0x5C8EFA, 0x3954D6)
    )
    val auroraLime = theme(
        id = "auroraLime",
        title = "Aurora Lime",
        colors = listOf(0xB6D94A, 0x2DBA70, 0x057A67)
    )
    val forest = theme(
        id = "forest",
        title = "Forest",
        colors = listOf(0x72B878, 0x2FA66A, 0x075C4A)
    )
    val violetBlue = theme(
        id = "violetBlue",
        title = "Violet Blue",
        colors = listOf(0xB05CFF, 0x6B4DFF, 0x1268FF)
    )
    val emberRose = theme(
        id = "emberRose",
        title = "Ember Rose",
        colors = listOf(0xFF9AAE, 0xE6507A, 0x7B3FF2)
    )
    val lagoonTeal = theme(
        id = "lagoonTeal",
        title = "Lagoon Teal",
        colors = listOf(0x5DE2E7, 0x20B7BC, 0x1768D8)
    )
    val solarCoral = theme(
        id = "solarCoral",
        title = "Solar Coral",
        colors = listOf(0xFFB15C, 0xFF6B61, 0xC43BE8)
    )
    val mango = theme(
        id = "mango",
        title = "Mango",
        colors = listOf(0xFFD36E, 0xFF9D3D, 0xEF4E5D)
    )
    val graphiteCyan = theme(
        id = "graphiteCyan",
        title = "Graphite Cyan",
        colors = listOf(0x556070, 0x2563EB, 0x06B6D4)
    )
    val rubyPlum = theme(
        id = "rubyPlum",
        title = "Ruby Plum",
        colors = listOf(0xFF5A7D, 0xC72E7E, 0x5B32D6)
    )
    val deepOcean = theme(
        id = "deepOcean",
        title = "Deep Ocean",
        colors = listOf(0x7DD3FC, 0x0EA5A4, 0x1E3A8A),
        startX = 0f,
        startY = 0f,
        endX = 1f,
        endY = 1f
    )
    val northernNight = theme(
        id = "northernNight",
        title = "Northern Night",
        colors = listOf(0x22D3EE, 0x6366F1, 0x312E81),
        startX = 0f,
        startY = 0f,
        endX = 1f,
        endY = 1f
    )
    val irisMint = theme(
        id = "irisMint",
        title = "Iris Mint",
        colors = listOf(0xA78BFA, 0x2DD4BF, 0x0F766E),
        startX = 1f,
        startY = 0f,
        endX = 0f,
        endY = 1f
    )
    val midnight = theme(
        id = "midnight",
        title = "Midnight",
        colors = listOf(0x94A3B8, 0x475569, 0x0F172A)
    )

    val all: List<ChatBubbleTheme> = listOf(
        zynaBlue,
        auroraLime,
        forest,
        violetBlue,
        emberRose,
        lagoonTeal,
        solarCoral,
        mango,
        graphiteCyan,
        rubyPlum,
        deepOcean,
        northernNight,
        irisMint,
        midnight
    )

    val fallback: ChatBubbleTheme = zynaBlue

    fun theme(id: String): ChatBubbleTheme? {
        return all.firstOrNull { it.id == id }
    }

    private fun theme(
        id: String,
        title: String,
        colors: List<Int>,
        startX: Float = 0.5f,
        startY: Float = 0f,
        endX: Float = 0.5f,
        endY: Float = 1f
    ): ChatBubbleTheme {
        return ChatBubbleTheme(
            id = id,
            title = title,
            outgoingGradient = MessageBubbleGradientSpec(
                colors = colors.map(::opaqueRgb),
                positions = gradientPositions(colors.size),
                startX = startX,
                startY = startY,
                endX = endX,
                endY = endY
            )
        )
    }

    private fun opaqueRgb(hex: Int): Int {
        return Color.rgb(
            (hex shr 16) and 0xFF,
            (hex shr 8) and 0xFF,
            hex and 0xFF
        )
    }

    private fun gradientPositions(colorCount: Int): List<Float>? {
        if (colorCount <= 1) return null
        if (colorCount == 3) return listOf(0f, 0.48f, 1f)
        return List(colorCount) { index -> index.toFloat() / (colorCount - 1).toFloat() }
    }
}

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
