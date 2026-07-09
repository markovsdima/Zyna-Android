package com.zyna.app.data.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging

object FirebaseMessagingRegistration {
    fun requestIfInstallationIdMissing(
        installationIdStore: FirebaseInstallationIdStore,
        reason: String
    ) {
        val installationId = installationIdStore.load()
            ?.takeIf { it.isNotBlank() }
        if (installationId != null) {
            Log.d(TAG, "FCM registration request skipped reason=$reason installation_id_present=true")
            return
        }
        request(reason)
    }

    fun request(reason: String) {
        Log.d(TAG, "Requesting FCM registration reason=$reason")
        try {
            FirebaseMessaging.getInstance()
                .register()
                .addOnSuccessListener {
                    Log.d(TAG, "FCM registration request completed reason=$reason")
                }
                .addOnFailureListener { error ->
                    Log.w(TAG, "FCM registration request failed reason=$reason", error)
                }
        } catch (error: RuntimeException) {
            Log.w(TAG, "FCM registration request failed synchronously reason=$reason", error)
        }
    }

    private const val TAG = "ZynaFCM"
}
