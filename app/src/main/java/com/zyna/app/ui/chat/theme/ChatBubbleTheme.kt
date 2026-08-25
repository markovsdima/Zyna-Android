package com.zyna.app.ui.chat.theme

import android.content.Context
import android.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MessageBubbleGradientSpec(
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

data class ChatBubbleTheme(
    val id: String,
    val title: String,
    val outgoingGradient: MessageBubbleGradientSpec
) {
    val actionAccentColor: Int
        get() = outgoingGradient.colors.getOrElse(1) { outgoingGradient.colors.last() }
}

object ChatBubbleThemes {
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

class ChatBubbleThemeStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val _selectedTheme = MutableStateFlow(loadSelectedTheme())
    val selectedTheme: StateFlow<ChatBubbleTheme> = _selectedTheme.asStateFlow()

    fun setSelectedTheme(id: String) {
        val theme = ChatBubbleThemes.theme(id) ?: return
        if (_selectedTheme.value == theme) {
            return
        }
        preferences.edit()
            .putString(KEY_SELECTED_THEME_ID, theme.id)
            .apply()
        _selectedTheme.value = theme
    }

    private fun loadSelectedTheme(): ChatBubbleTheme {
        return preferences.getString(KEY_SELECTED_THEME_ID, null)
            ?.let(ChatBubbleThemes::theme)
            ?: ChatBubbleThemes.fallback
    }

    private companion object {
        const val PREFERENCES_NAME = "chat_bubble_theme"
        const val KEY_SELECTED_THEME_ID = "chatBubbleTheme.selectedThemeId"
    }
}
