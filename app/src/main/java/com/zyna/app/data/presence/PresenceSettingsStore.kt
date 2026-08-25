package com.zyna.app.data.presence

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PresenceSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val _selectedProvider = MutableStateFlow(loadSelectedProvider())

    val selectedProvider: StateFlow<PresenceProviderMode> = _selectedProvider.asStateFlow()

    fun setSelectedProvider(provider: PresenceProviderMode) {
        if (_selectedProvider.value == provider) {
            return
        }
        preferences.edit()
            .putString(KEY_SELECTED_PROVIDER_ID, provider.id)
            .apply()
        _selectedProvider.value = provider
    }

    private fun loadSelectedProvider(): PresenceProviderMode {
        return PresenceProviderMode.fromId(
            preferences.getString(KEY_SELECTED_PROVIDER_ID, null)
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "presence_settings"
        const val KEY_SELECTED_PROVIDER_ID = "selected_provider_id"
    }
}
