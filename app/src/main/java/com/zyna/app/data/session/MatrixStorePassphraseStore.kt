package com.zyna.app.data.session

import android.content.Context
import android.util.Base64
import com.zyna.app.data.security.AndroidKeystoreSecretBox
import java.security.SecureRandom

class MatrixStorePassphraseStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val secretBox = AndroidKeystoreSecretBox(PASSPHRASE_KEY_ALIAS)
    private val secureRandom = SecureRandom()

    fun loadPassphraseOrNull(): String? {
        val encrypted = preferences.getString(KEY_ENCRYPTED_PASSPHRASE, null)
            ?: return null

        return try {
            secretBox.decryptFromJson(encrypted)
        } catch (_: Throwable) {
            clear()
            null
        }
    }

    fun createPassphrase(): String {
        val generated = generatePassphrase()
        preferences.edit()
            .putString(KEY_ENCRYPTED_PASSPHRASE, secretBox.encryptToJson(generated))
            .apply()
        return generated
    }

    fun clear() {
        preferences.edit().clear().apply()
        secretBox.deleteKey()
    }

    private fun generatePassphrase(): String {
        val bytes = ByteArray(PASSPHRASE_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private companion object {
        const val PREFERENCES_NAME = "matrix_store_secrets"
        const val KEY_ENCRYPTED_PASSPHRASE = "encrypted_store_passphrase"
        const val PASSPHRASE_KEY_ALIAS = "com.zyna.app.matrix.store_passphrase"
        const val PASSPHRASE_BYTES = 32
    }
}
