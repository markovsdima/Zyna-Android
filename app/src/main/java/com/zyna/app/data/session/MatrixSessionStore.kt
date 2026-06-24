package com.zyna.app.data.session

import android.content.Context
import com.zyna.app.data.security.AndroidKeystoreSecretBox
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersion

class MatrixSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val secretBox = AndroidKeystoreSecretBox(SESSION_KEY_ALIAS)

    fun save(session: Session) {
        val data = encodeSession(session)

        preferences.edit()
            .putString(KEY_ENCRYPTED_LAST_SESSION, secretBox.encryptToJson(data))
            .remove(KEY_LAST_USER_ID)
            .remove(legacyEncryptedSessionKey(session.userId))
            .remove(legacySessionKey(session.userId))
            .apply()
    }

    fun loadLastSession(): Session? {
        val encrypted = preferences.getString(KEY_ENCRYPTED_LAST_SESSION, null)
        if (encrypted != null) {
            return try {
                decodeSession(secretBox.decryptFromJson(encrypted))
            } catch (_: Throwable) {
                clear()
                null
            }
        }

        val legacyUserId = preferences.getString(KEY_LAST_USER_ID, null) ?: return null
        return loadLegacySession(legacyUserId)
    }

    fun loadSession(userId: String): Session? {
        val current = loadLastSession()
        if (current?.userId == userId) {
            return current
        }
        return loadLegacySession(userId)
    }

    private fun loadLegacySession(userId: String): Session? {
        val encrypted = preferences.getString(legacyEncryptedSessionKey(userId), null)
        if (encrypted != null) {
            return loadEncryptedLegacySession(userId, encrypted)
        }
        val legacy = preferences.getString(legacySessionKey(userId), null) ?: return null
        return try {
            decodeSession(legacy).also { session ->
                save(session)
            }
        } catch (_: Throwable) {
            clearSession(userId)
            null
        }
    }

    private fun loadEncryptedLegacySession(userId: String, encrypted: String): Session? {
        return try {
            decodeSession(secretBox.decryptFromJson(encrypted)).also { session ->
                save(session)
            }
        } catch (_: Throwable) {
            clearLegacySession(userId)
            null
        }
    }

    private fun clearSession(userId: String) {
        val editor = preferences.edit()
            .remove(KEY_ENCRYPTED_LAST_SESSION)
            .remove(legacyEncryptedSessionKey(userId))
            .remove(legacySessionKey(userId))

        if (preferences.getString(KEY_LAST_USER_ID, null) == userId) {
            editor.remove(KEY_LAST_USER_ID)
        }

        editor.apply()
    }

    private fun clearLegacySession(userId: String) {
        val editor = preferences.edit()
            .remove(legacyEncryptedSessionKey(userId))
            .remove(legacySessionKey(userId))

        if (preferences.getString(KEY_LAST_USER_ID, null) == userId) {
            editor.remove(KEY_LAST_USER_ID)
        }

        editor.apply()
    }

    private fun encodeSession(session: Session): String {
        return JSONObject()
            .put("accessToken", session.accessToken)
            .put("refreshToken", session.refreshToken)
            .put("userId", session.userId)
            .put("deviceId", session.deviceId)
            .put("homeserverUrl", session.homeserverUrl)
            .put("oauthData", session.oauthData)
            .put("slidingSyncVersion", session.slidingSyncVersion.name)
            .toString()
    }

    private fun decodeSession(data: String): Session {
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
        secretBox.deleteKey()
    }

    fun isRecoveryComplete(userId: String): Boolean {
        if (preferences.getBoolean(KEY_RECOVERY_COMPLETE, false)) {
            return true
        }

        val legacyComplete = preferences.getBoolean(legacyRecoveryCompleteKey(userId), false)
        if (legacyComplete) {
            preferences.edit()
                .putBoolean(KEY_RECOVERY_COMPLETE, true)
                .remove(legacyRecoveryCompleteKey(userId))
                .apply()
        }
        return legacyComplete
    }

    fun markRecoveryComplete(userId: String) {
        preferences.edit()
            .putBoolean(KEY_RECOVERY_COMPLETE, true)
            .remove(legacyRecoveryCompleteKey(userId))
            .apply()
    }

    private fun JSONObject.optNullableString(name: String): String? {
        return if (isNull(name)) null else optString(name)
    }

    private fun legacyEncryptedSessionKey(userId: String): String = "session_encrypted:$userId"

    private fun legacySessionKey(userId: String): String = "session:$userId"

    private fun legacyRecoveryCompleteKey(userId: String): String = "recovery_complete:$userId"

    private companion object {
        const val PREFERENCES_NAME = "matrix_session"
        const val KEY_ENCRYPTED_LAST_SESSION = "encrypted_last_session"
        const val KEY_LAST_USER_ID = "last_user_id"
        const val KEY_RECOVERY_COMPLETE = "recovery_complete"
        const val SESSION_KEY_ALIAS = "com.zyna.app.matrix.session"
    }
}
