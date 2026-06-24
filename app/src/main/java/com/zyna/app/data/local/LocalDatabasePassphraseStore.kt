package com.zyna.app.data.local

import android.content.Context
import android.util.Base64
import com.zyna.app.data.security.AndroidKeystoreSecretBox
import java.security.SecureRandom

class LocalDatabasePassphraseStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val secretBox = AndroidKeystoreSecretBox(KEY_ALIAS)
    private val secureRandom = SecureRandom()

    fun loadOrCreatePassphrase(): LocalDatabasePassphrase {
        val encrypted = preferences.getString(KEY_ENCRYPTED_PASSPHRASE, null)
        if (encrypted != null) {
            return try {
                LocalDatabasePassphrase(
                    bytes = decode(secretBox.decryptFromJson(encrypted)),
                    didReset = false
                )
            } catch (_: Throwable) {
                clear()
                createPassphrase(didReset = true)
            }
        }

        return createPassphrase(didReset = false)
    }

    fun clear() {
        preferences.edit().clear().apply()
        secretBox.deleteKey()
    }

    private fun createPassphrase(didReset: Boolean): LocalDatabasePassphrase {
        val passphrase = ByteArray(PASSPHRASE_BYTES).also(secureRandom::nextBytes)
        preferences.edit()
            .putString(KEY_ENCRYPTED_PASSPHRASE, secretBox.encryptToJson(encode(passphrase)))
            .apply()
        return LocalDatabasePassphrase(bytes = passphrase, didReset = didReset)
    }

    private fun encode(value: ByteArray): String {
        return Base64.encodeToString(value, Base64.NO_WRAP)
    }

    private fun decode(value: String): ByteArray {
        return Base64.decode(value, Base64.NO_WRAP)
    }

    private companion object {
        const val PREFERENCES_NAME = "local_database_secrets"
        const val KEY_ENCRYPTED_PASSPHRASE = "encrypted_database_passphrase"
        const val KEY_ALIAS = "com.zyna.app.local_database.passphrase"
        const val PASSPHRASE_BYTES = 32
    }
}

data class LocalDatabasePassphrase(
    val bytes: ByteArray,
    val didReset: Boolean
)
