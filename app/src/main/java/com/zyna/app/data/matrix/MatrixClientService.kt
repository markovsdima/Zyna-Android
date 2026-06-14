package com.zyna.app.data.matrix

import android.content.Context
import com.zyna.app.data.session.MatrixSessionStore
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import uniffi.matrix_sdk.BackupDownloadStrategy
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.ClientSessionDelegate
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.SyncService

sealed interface MatrixClientState {
    data object LoggedOut : MatrixClientState
    data object RestoringSession : MatrixClientState
    data object LoggingIn : MatrixClientState
    data class LoggedIn(val userId: String) : MatrixClientState
    data class Syncing(val userId: String) : MatrixClientState
    data class Error(val message: String) : MatrixClientState
}

data class MatrixRoomSummary(
    val id: String,
    val displayName: String,
    val avatarUrl: String?
)

class MatrixClientService(
    private val context: Context,
    private val sessionStore: MatrixSessionStore
) {
    private val _state = MutableStateFlow<MatrixClientState>(MatrixClientState.LoggedOut)
    val state: StateFlow<MatrixClientState> = _state.asStateFlow()

    private val sessionDelegate = AndroidMatrixSessionDelegate(sessionStore)

    private var client: Client? = null
    private var syncService: SyncService? = null

    suspend fun restoreSessionIfAvailable() {
        val session = sessionStore.loadLastSession()
        if (session == null) {
            _state.value = MatrixClientState.LoggedOut
            return
        }

        _state.value = MatrixClientState.RestoringSession
        try {
            val restoredClient = buildClient(session.homeserverUrl)
            restoredClient.restoreSession(session)
            client = restoredClient
            _state.value = MatrixClientState.LoggedIn(session.userId)
            startSync()
        } catch (error: Throwable) {
            client = null
            syncService = null
            _state.value = MatrixClientState.Error(error.displayMessage())
        }
    }

    suspend fun login(homeserver: String, username: String, password: String) {
        _state.value = MatrixClientState.LoggingIn
        var loginClient: Client? = null
        try {
            resetClientForFreshLogin()
            sessionStore.clear()
            loginClient = buildClient(normalizeHomeserver(homeserver))
            loginClient.login(
                username = username.trim(),
                password = password,
                initialDeviceName = "Zyna Android",
                deviceId = null
            )

            val session = loginClient.session()
            sessionStore.save(session)
            client = loginClient
            loginClient = null
            _state.value = MatrixClientState.LoggedIn(session.userId)
            startSync()
        } catch (error: Throwable) {
            loginClient?.close()
            client = null
            syncService = null
            _state.value = MatrixClientState.Error(error.displayMessage())
        }
    }

    private suspend fun resetClientForFreshLogin() {
        syncService?.stop()
        syncService = null
        client?.close()
        client = null
        clearMatrixStoreDirectories()
    }

    suspend fun logout() {
        syncService?.stop()
        syncService = null
        client = null
        sessionStore.clear()
        _state.value = MatrixClientState.LoggedOut
    }

    suspend fun recoverWithRecoveryKey(recoveryKey: String) {
        val activeClient = client ?: error("Matrix client is not ready")
        val trimmedKey = recoveryKey.trim()
        require(trimmedKey.isNotEmpty()) { "Recovery key is empty" }

        try {
            activeClient.encryption().recoverAndFixBackup(trimmedKey)
        } catch (firstError: Throwable) {
            delay(2_000)
            try {
                activeClient.encryption().recoverAndFixBackup(trimmedKey)
            } catch (_: Throwable) {
                throw firstError
            }
        }

        sessionStore.markRecoveryComplete(activeClient.userId())
    }

    fun isRecoveryComplete(userId: String): Boolean {
        return sessionStore.isRecoveryComplete(userId)
    }

    suspend fun roomsSnapshot(): List<MatrixRoomSummary> = withContext(Dispatchers.IO) {
        client?.rooms()
            ?.map { room ->
                MatrixRoomSummary(
                    id = room.id(),
                    displayName = room.displayName()?.takeIf { it.isNotBlank() } ?: room.id(),
                    avatarUrl = room.avatarUrl()
                )
            }
            ?.sortedBy { it.displayName.lowercase() }
            ?: emptyList()
    }

    private suspend fun buildClient(homeserver: String): Client = withContext(Dispatchers.IO) {
        val paths = matrixStorePaths()
        ClientBuilder()
            .serverNameOrHomeserverUrl(homeserver)
            .sqliteStore(SqliteStoreBuilder(paths.dataPath, paths.cachePath))
            .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.DISCOVER_NATIVE)
            .setSessionDelegate(sessionDelegate)
            .autoEnableCrossSigning(true)
            .autoEnableBackups(true)
            .backupDownloadStrategy(BackupDownloadStrategy.AFTER_DECRYPTION_FAILURE)
            .userAgent("Zyna Android")
            .build()
    }

    private suspend fun startSync() {
        val activeClient = client ?: return
        if (syncService != null) {
            _state.value = MatrixClientState.Syncing(activeClient.userId())
            return
        }

        val service = withContext(Dispatchers.IO) {
            activeClient.syncService()
                .withOfflineMode()
                .finish()
        }
        syncService = service
        service.start()
        _state.value = MatrixClientState.Syncing(activeClient.userId())
    }

    private fun matrixStorePaths(): MatrixStorePaths {
        val dataDir = File(context.filesDir, "matrix/data")
        val cacheDir = File(context.cacheDir, "matrix/cache")
        dataDir.mkdirs()
        cacheDir.mkdirs()
        return MatrixStorePaths(
            dataPath = dataDir.absolutePath,
            cachePath = cacheDir.absolutePath
        )
    }

    private suspend fun clearMatrixStoreDirectories() = withContext(Dispatchers.IO) {
        File(context.filesDir, "matrix").deleteRecursively()
        File(context.cacheDir, "matrix").deleteRecursively()
    }

    private fun normalizeHomeserver(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    private fun Throwable.displayMessage(): String {
        return message ?: javaClass.simpleName
    }

    private data class MatrixStorePaths(
        val dataPath: String,
        val cachePath: String
    )
}

private class AndroidMatrixSessionDelegate(
    private val sessionStore: MatrixSessionStore
) : ClientSessionDelegate {
    override fun retrieveSessionFromKeychain(userId: String): Session {
        return sessionStore.loadSession(userId)
            ?: error("No Matrix session stored for $userId")
    }

    override fun saveSessionInKeychain(session: Session) {
        sessionStore.save(session)
    }
}
