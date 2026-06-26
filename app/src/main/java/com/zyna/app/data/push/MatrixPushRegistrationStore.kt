package com.zyna.app.data.push

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

class MatrixPushRegistrationStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val secureRandom = SecureRandom()

    @Synchronized
    fun getOrCreateClientSecret(userId: String): String {
        val key = clientSecretKey(userId)
        val existing = preferences.getString(key, null)
            ?.takeIf { it.isNotBlank() }
        if (existing != null) {
            return existing
        }

        val secret = generateClientSecret()
        val didSave = preferences.edit()
            .putString(key, secret)
            .putString(userIdForClientSecretKey(secret), userId)
            .commit()
        check(didSave) { "Failed to persist Matrix push client secret" }
        return secret
    }

    fun userIdForClientSecret(clientSecret: String): String? {
        return preferences.getString(userIdForClientSecretKey(clientSecret), null)
    }

    fun registeredInstallationId(userId: String): String? {
        return preferences.getString(registeredInstallationIdKey(userId), null)
            ?.takeIf { it.isNotBlank() }
    }

    fun markRegistered(userId: String, installationId: String) {
        val didSave = preferences.edit()
            .putString(registeredInstallationIdKey(userId), installationId)
            .commit()
        check(didSave) { "Failed to persist Matrix push registration" }
    }

    fun clearRegistration(userId: String) {
        preferences.edit()
            .remove(registeredInstallationIdKey(userId))
            .commit()
    }

    fun clear() {
        preferences.edit()
            .clear()
            .commit()
    }

    private fun generateClientSecret(): String {
        val bytes = ByteArray(CLIENT_SECRET_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private fun clientSecretKey(userId: String): String = "client_secret:$userId"

    private fun userIdForClientSecretKey(clientSecret: String): String = "user_id:$clientSecret"

    private fun registeredInstallationIdKey(userId: String): String =
        "registered_installation_id:$userId"

    companion object {
        private const val PREFERENCES_NAME = "matrix_push_registration"
        private const val CLIENT_SECRET_BYTES = 24
    }
}
