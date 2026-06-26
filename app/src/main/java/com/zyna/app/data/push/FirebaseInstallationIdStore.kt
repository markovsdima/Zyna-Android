package com.zyna.app.data.push

import android.content.Context

class FirebaseInstallationIdStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun save(installationId: String) {
        preferences.edit()
            .putString(KEY_INSTALLATION_ID, installationId)
            .commit()
    }

    fun load(): String? = preferences.getString(KEY_INSTALLATION_ID, null)

    fun clear() {
        preferences.edit()
            .remove(KEY_INSTALLATION_ID)
            .commit()
    }

    companion object {
        private const val PREFERENCES_NAME = "firebase_installation_id"
        private const val KEY_INSTALLATION_ID = "installation_id"
    }
}
