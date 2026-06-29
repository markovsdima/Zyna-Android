package com.zyna.app.ui.theme

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppThemeMode(
    val id: String,
    val title: String
) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    companion object {
        val default: AppThemeMode = SYSTEM

        fun fromId(id: String?): AppThemeMode {
            return entries.firstOrNull { it.id == id } ?: default
        }
    }
}

class AppThemeStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val _selectedMode = MutableStateFlow(loadMode())

    val selectedMode: StateFlow<AppThemeMode> = _selectedMode.asStateFlow()

    fun setSelectedMode(mode: AppThemeMode) {
        if (_selectedMode.value == mode) {
            return
        }
        preferences.edit()
            .putString(KEY_SELECTED_MODE_ID, mode.id)
            .apply()
        _selectedMode.value = mode
        applyMode(mode)
    }

    fun applySavedMode() {
        applyMode(_selectedMode.value)
    }

    private fun loadMode(): AppThemeMode {
        return AppThemeMode.fromId(preferences.getString(KEY_SELECTED_MODE_ID, null))
    }

    private companion object {
        const val PREFERENCES_NAME = "app_theme"
        const val KEY_SELECTED_MODE_ID = "selected_mode_id"

        fun applyMode(mode: AppThemeMode) {
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    AppThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    AppThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                    AppThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                }
            )
        }
    }
}
