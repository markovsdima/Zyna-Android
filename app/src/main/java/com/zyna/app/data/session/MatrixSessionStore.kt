package com.zyna.app.data.session

import android.content.Context
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersion

class MatrixSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun save(session: Session) {
        val data = JSONObject()
            .put("accessToken", session.accessToken)
            .put("refreshToken", session.refreshToken)
            .put("userId", session.userId)
            .put("deviceId", session.deviceId)
            .put("homeserverUrl", session.homeserverUrl)
            .put("oauthData", session.oauthData)
            .put("slidingSyncVersion", session.slidingSyncVersion.name)
            .toString()

        preferences.edit()
            .putString(KEY_LAST_USER_ID, session.userId)
            .putString(sessionKey(session.userId), data)
            .apply()
    }

    fun loadLastSession(): Session? {
        val userId = preferences.getString(KEY_LAST_USER_ID, null) ?: return null
        return loadSession(userId)
    }

    fun loadSession(userId: String): Session? {
        val data = preferences.getString(sessionKey(userId), null) ?: return null
        val json = JSONObject(data)
        return Session(
            accessToken = json.getString("accessToken"),
            refreshToken = json.optNullableString("refreshToken"),
            userId = json.getString("userId"),
            deviceId = json.getString("deviceId"),
            homeserverUrl = json.getString("homeserverUrl"),
            oauthData = json.optNullableString("oauthData"),
            slidingSyncVersion = SlidingSyncVersion.valueOf(
                json.optString("slidingSyncVersion", SlidingSyncVersion.NATIVE.name)
            )
        )
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    fun isRecoveryComplete(userId: String): Boolean {
        return preferences.getBoolean(recoveryCompleteKey(userId), false)
    }

    fun markRecoveryComplete(userId: String) {
        preferences.edit()
            .putBoolean(recoveryCompleteKey(userId), true)
            .apply()
    }

    private fun JSONObject.optNullableString(name: String): String? {
        return if (isNull(name)) null else optString(name)
    }

    private fun sessionKey(userId: String): String = "session:$userId"

    private fun recoveryCompleteKey(userId: String): String = "recovery_complete:$userId"

    private companion object {
        const val PREFERENCES_NAME = "matrix_session"
        const val KEY_LAST_USER_ID = "last_user_id"
    }
}
