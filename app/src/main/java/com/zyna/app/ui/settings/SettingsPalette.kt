package com.zyna.app.ui.settings

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

internal data class SettingsPalette(
    val background: Int,
    val surface: Int,
    val titleText: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val actionText: Int,
    val separator: Int,
    val selectedFill: Int
) {
    companion object {
        fun from(context: Context): SettingsPalette {
            val isDark = (
                context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK
                ) == Configuration.UI_MODE_NIGHT_YES
            return if (isDark) {
                SettingsPalette(
                    background = Color.rgb(18, 18, 22),
                    surface = Color.rgb(34, 34, 40),
                    titleText = Color.rgb(232, 225, 229),
                    primaryText = Color.rgb(232, 225, 229),
                    secondaryText = Color.rgb(202, 196, 208),
                    actionText = Color.rgb(208, 188, 255),
                    separator = Color.argb(46, 255, 255, 255),
                    selectedFill = Color.argb(36, 208, 188, 255)
                )
            } else {
                SettingsPalette(
                    background = Color.WHITE,
                    surface = Color.rgb(247, 242, 250),
                    titleText = Color.rgb(29, 27, 32),
                    primaryText = Color.rgb(29, 27, 32),
                    secondaryText = Color.rgb(73, 69, 79),
                    actionText = Color.rgb(33, 0, 93),
                    separator = Color.argb(36, 0, 0, 0),
                    selectedFill = Color.argb(28, 33, 0, 93)
                )
            }
        }
    }
}
