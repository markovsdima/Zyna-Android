package com.zyna.app.data.push

import android.util.Log
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.HttpPusherData
import org.matrix.rustcomponents.sdk.PushFormat
import org.matrix.rustcomponents.sdk.PusherIdentifiers
import org.matrix.rustcomponents.sdk.PusherKind

class MatrixPushRegistrar(
    private val firebaseInstallationIdStore: FirebaseInstallationIdStore,
    private val pushRegistrationStore: MatrixPushRegistrationStore,
    private val appId: String
) {
    suspend fun register(client: Client) {
        withContext(Dispatchers.IO) {
            val installationId = firebaseInstallationIdStore.load()
                ?.takeIf { it.isNotBlank() }
            if (installationId == null) {
                Log.d(TAG, "Matrix push registration skipped: Firebase installation id is not ready")
                return@withContext
            }

            val userId = client.userId()
            val previousInstallationId = pushRegistrationStore.registeredInstallationId(userId)
            val clientSecret = pushRegistrationStore.getOrCreateClientSecret(userId)
            val gatewayUrl = matrixPushGatewayUrlFromHomeserver(client.session().homeserverUrl)
                ?: error("Invalid Matrix push gateway URL")
            client.setPusher(
                identifiers = PusherIdentifiers(
                    pushkey = installationId,
                    appId = appId
                ),
                kind = PusherKind.Http(
                    data = HttpPusherData(
                        url = gatewayUrl,
                        format = PushFormat.EVENT_ID_ONLY,
                        defaultPayload = JSONObject()
                            .put(DEFAULT_PAYLOAD_CLIENT_SECRET_KEY, clientSecret)
                            .toString()
                    )
                ),
                appDisplayName = APP_DISPLAY_NAME,
                deviceDisplayName = DEVICE_DISPLAY_NAME,
                profileTag = PROFILE_TAG,
                lang = currentLanguage()
            )

            if (previousInstallationId != null && previousInstallationId != installationId) {
                deletePusher(
                    client = client,
                    installationId = previousInstallationId,
                    logPrefix = "old Matrix push pusher"
                )
            }
            pushRegistrationStore.markRegistered(userId, installationId)
            Log.d(
                TAG,
                "Matrix push pusher registered app_id=$appId " +
                    "fid_changed=${previousInstallationId != installationId} " +
                    "fid_prefix=${installationId.take(INSTALLATION_ID_LOG_PREFIX_LENGTH)}"
            )
        }
    }

    suspend fun unregister(client: Client) {
        withContext(Dispatchers.IO) {
            val userId = client.userId()
            val installationId = pushRegistrationStore.registeredInstallationId(userId)
                ?: firebaseInstallationIdStore.load()
                    ?.takeIf { it.isNotBlank() }
                ?: return@withContext
            deletePusher(
                client = client,
                installationId = installationId,
                logPrefix = "Matrix push pusher"
            )
            pushRegistrationStore.clearRegistration(userId)
        }
    }

    fun clearLocalState() {
        pushRegistrationStore.clear()
    }

    private suspend fun deletePusher(client: Client, installationId: String, logPrefix: String) {
        try {
            client.deletePusher(
                PusherIdentifiers(
                    pushkey = installationId,
                    appId = appId
                )
            )
            Log.d(
                TAG,
                "$logPrefix deleted app_id=$appId " +
                    "fid_prefix=${installationId.take(INSTALLATION_ID_LOG_PREFIX_LENGTH)}"
            )
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to delete $logPrefix", error)
        }
    }

    private fun currentLanguage(): String {
        return Locale.getDefault().language.takeIf { it.isNotBlank() } ?: DEFAULT_LANGUAGE
    }

    companion object {
        private const val TAG = "MatrixPushRegistrar"
        private const val APP_DISPLAY_NAME = "Zyna Android"
        private const val DEVICE_DISPLAY_NAME = "Android"
        private const val PROFILE_TAG = "mobile_android"
        private const val DEFAULT_LANGUAGE = "en"
        private const val DEFAULT_PAYLOAD_CLIENT_SECRET_KEY = "cs"
        private const val INSTALLATION_ID_LOG_PREFIX_LENGTH = 12
    }
}

internal fun matrixPushGatewayUrlFromHomeserver(homeserverUrl: String): String? {
    val raw = homeserverUrl.trim()
        .let { value ->
            if (value.contains("://")) {
                value
            } else {
                "https://$value"
            }
        }
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    val scheme = uri.scheme
    val host = uri.host
    if ((scheme != "http" && scheme != "https") || host.isNullOrBlank()) {
        return null
    }

    val labels = host.split('.')
        .filter { it.isNotBlank() }
        .toMutableList()
    if (labels.size < 2) {
        return null
    }
    labels[0] = PUSH_GATEWAY_HOST_PREFIX

    return URI(
        scheme,
        uri.userInfo,
        labels.joinToString("."),
        uri.port,
        PUSH_GATEWAY_PATH,
        null,
        null
    ).toString()
}

private const val PUSH_GATEWAY_HOST_PREFIX = "push"
private const val PUSH_GATEWAY_PATH = "/_matrix/push/v1/notify"
