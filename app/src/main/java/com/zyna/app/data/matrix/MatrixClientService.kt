package com.zyna.app.data.matrix

import android.content.Context
import com.zyna.app.data.session.MatrixSessionStore
import com.zyna.app.data.session.MatrixStorePassphraseStore
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.DateDividerMode
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.ClientSessionDelegate
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.EventTimelineItem
import org.matrix.rustcomponents.sdk.MessageContent
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.TextMessageContent
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineConfiguration
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineFilter
import org.matrix.rustcomponents.sdk.TimelineFocus
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener
import org.matrix.rustcomponents.sdk.contentWithoutRelationFromMessage
import org.matrix.rustcomponents.sdk.use
import uniffi.matrix_sdk.BackupDownloadStrategy
import uniffi.matrix_sdk_ui.TimelineReadReceiptTracking

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

data class MatrixChatMessage(
    val id: String,
    val sender: String,
    val body: String,
    val timestampMillis: Long,
    val isOwn: Boolean
)

class MatrixClientService(
    private val context: Context,
    private val sessionStore: MatrixSessionStore,
    private val storePassphraseStore: MatrixStorePassphraseStore
) {
    private val _state = MutableStateFlow<MatrixClientState>(MatrixClientState.LoggedOut)
    val state: StateFlow<MatrixClientState> = _state.asStateFlow()

    private val sessionDelegate = AndroidMatrixSessionDelegate(sessionStore)

    private var client: Client? = null
    private var syncService: SyncService? = null
    private val activeTimelineLock = Any()
    private val activeRoomTimelines = mutableMapOf<String, Timeline>()

    suspend fun restoreSessionIfAvailable() {
        val session = sessionStore.loadLastSession()
        if (session == null) {
            clearStoredMatrixState()
            _state.value = MatrixClientState.LoggedOut
            return
        }

        _state.value = MatrixClientState.RestoringSession
        var restoredClient: Client? = null
        try {
            restoredClient = buildClient(session.homeserverUrl)
            restoredClient.restoreSession(session)
            client = restoredClient
            restoredClient = null
            _state.value = MatrixClientState.LoggedIn(session.userId)
            startSync()
        } catch (error: Throwable) {
            restoredClient?.close()
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
            clearStoredMatrixState()
            _state.value = MatrixClientState.Error(error.displayMessage())
        }
    }

    private suspend fun resetClientForFreshLogin() {
        syncService?.stop()
        syncService?.close()
        syncService = null
        client?.close()
        client = null
        clearStoredMatrixState()
    }

    suspend fun logout() {
        syncService?.stop()
        syncService?.close()
        syncService = null
        client?.close()
        client = null
        clearStoredMatrixState()
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

    fun roomTimelineMessages(roomId: String): Flow<List<MatrixChatMessage>> = callbackFlow {
        val activeClient = client ?: error("Matrix client is not ready")
        val room = activeClient.getRoom(roomId) ?: error("Matrix room is not available")
        var timeline: Timeline? = null
        var listenerHandle: TaskHandle? = null
        val entries = mutableListOf<MatrixChatMessage?>()
        val hasEmittedInitialState = AtomicBoolean(false)
        val hasCleanedUp = AtomicBoolean(false)

        fun currentMessages(): List<MatrixChatMessage> = synchronized(entries) {
            entries.visibleChatMessages()
        }

        fun sendCurrentMessages() {
            hasEmittedInitialState.set(true)
            trySendBlocking(currentMessages())
        }

        fun cleanup() {
            if (hasCleanedUp.compareAndSet(false, true)) {
                listenerHandle?.cancelAndDestroy()
                synchronized(activeTimelineLock) {
                    if (activeRoomTimelines[roomId] === timeline) {
                        activeRoomTimelines.remove(roomId)
                    }
                }
                timeline?.destroy()
                room.destroy()
            }
        }

        try {
            val openedTimeline = room.timelineWithConfiguration(
                liveTimelineConfiguration(roomId)
            )
            timeline = openedTimeline
            synchronized(activeTimelineLock) {
                activeRoomTimelines[roomId] = openedTimeline
            }

            listenerHandle = openedTimeline.addListener(
                object : TimelineListener {
                    override fun onUpdate(diff: List<TimelineDiff>) {
                        synchronized(entries) {
                            diff.forEach { entries.applyTimelineDiff(it) }
                        }
                        sendCurrentMessages()
                    }
                }
            )

            val initialLoadingFallbackJob = launch {
                delay(TIMELINE_UPDATE_TIMEOUT_MS)
                if (hasEmittedInitialState.compareAndSet(false, true)) {
                    trySend(currentMessages())
                }
            }

            val paginationJob = launch(Dispatchers.IO) {
                runCatching {
                    openedTimeline.paginateBackwards(TIMELINE_PAGE_SIZE.toUShort())
                }.onFailure { error ->
                    close(error)
                }
            }

            awaitClose {
                initialLoadingFallbackJob.cancel()
                paginationJob.cancel()
                cleanup()
            }
        } catch (error: Throwable) {
            cleanup()
            throw error
        }
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    suspend fun paginateRoomTimelineBackwards(roomId: String): Boolean = withContext(Dispatchers.IO) {
        val activeTimeline = synchronized(activeTimelineLock) {
            activeRoomTimelines[roomId]
        } ?: error("Chat timeline is not ready")

        activeTimeline.paginateBackwards(TIMELINE_PAGE_SIZE.toUShort())
    }

    suspend fun sendTextMessage(roomId: String, body: String) = withContext(Dispatchers.IO) {
        val text = body.trim()
        require(text.isNotEmpty()) { "Message is empty" }
        val activeTimeline = synchronized(activeTimelineLock) {
            activeRoomTimelines[roomId]
        } ?: error("Chat timeline is not ready")

        val messageContent = MessageContent(
            msgType = MessageType.Text(
                TextMessageContent(
                    body = text,
                    formatted = null
                )
            ),
            body = text,
            isEdited = false,
            mentions = null
        )

        try {
            contentWithoutRelationFromMessage(messageContent).use { content ->
                activeTimeline.send(content).destroy()
            }
        } finally {
            messageContent.destroy()
        }
    }

    private fun MutableList<MatrixChatMessage?>.applyTimelineDiff(diff: TimelineDiff) {
        when (diff) {
            is TimelineDiff.Append -> {
                diff.values.forEach { add(it.toChatMessageOrNull()) }
            }
            TimelineDiff.Clear -> clear()
            is TimelineDiff.PushFront -> add(0, diff.value.toChatMessageOrNull())
            is TimelineDiff.PushBack -> add(diff.value.toChatMessageOrNull())
            TimelineDiff.PopFront -> removeFirstOrNull()
            TimelineDiff.PopBack -> removeLastOrNull()
            is TimelineDiff.Insert -> {
                add(diff.index.toInt().coerceIn(0, size), diff.value.toChatMessageOrNull())
            }
            is TimelineDiff.Set -> {
                val index = diff.index.toInt()
                val value = diff.value.toChatMessageOrNull()
                if (index in indices) {
                    set(index, value)
                } else if (index == size) {
                    add(value)
                }
            }
            is TimelineDiff.Remove -> {
                val index = diff.index.toInt()
                if (index in indices) {
                    removeAt(index)
                }
            }
            is TimelineDiff.Truncate -> {
                val length = diff.length.toInt().coerceAtLeast(0)
                if (length < size) {
                    subList(length, size).clear()
                }
            }
            is TimelineDiff.Reset -> {
                clear()
                diff.values.forEach { add(it.toChatMessageOrNull()) }
            }
        }
    }

    private fun List<MatrixChatMessage?>.visibleChatMessages(): List<MatrixChatMessage> {
        return filterNotNull().takeLast(TIMELINE_MAX_VISIBLE_MESSAGES)
    }

    private fun TimelineItem.toChatMessageOrNull(): MatrixChatMessage? = use { item ->
        val event = item.asEvent() ?: return@use null
        event.toChatMessageOrNull()
    }

    private fun EventTimelineItem.toChatMessageOrNull(): MatrixChatMessage? {
        val content = (content as? TimelineItemContent.MsgLike)?.content
            ?: return null
        val body = when (val kind = content.kind) {
            is MsgLikeKind.Message -> kind.content.displayBody()
            is MsgLikeKind.UnableToDecrypt -> "Unable to decrypt message"
            else -> return null
        }

        return MatrixChatMessage(
            id = eventOrTransactionId.stableId(),
            sender = sender,
            body = body,
            timestampMillis = timestamp.toLong(),
            isOwn = isOwn
        )
    }

    private fun EventOrTransactionId.stableId(): String {
        return when (this) {
            is EventOrTransactionId.EventId -> eventId
            is EventOrTransactionId.TransactionId -> transactionId
        }
    }

    private fun MessageContent.displayBody(): String {
        return when (val type = msgType) {
            is MessageType.Text -> type.content.body
            is MessageType.Notice -> type.content.body
            is MessageType.Emote -> type.content.body
            is MessageType.Image -> type.content.caption?.takeIf { it.isNotBlank() } ?: body.ifBlank { "Image" }
            is MessageType.Audio -> type.content.caption?.takeIf { it.isNotBlank() } ?: body.ifBlank { "Audio" }
            is MessageType.Video -> type.content.caption?.takeIf { it.isNotBlank() } ?: body.ifBlank { "Video" }
            is MessageType.File -> type.content.caption?.takeIf { it.isNotBlank() } ?: body.ifBlank { "File" }
            is MessageType.Gallery -> type.content.body
            is MessageType.Location -> type.content.body
            is MessageType.Other -> type.body
        }
    }

    private fun TaskHandle.cancelAndDestroy() {
        cancel()
        destroy()
    }

    private fun liveTimelineConfiguration(roomId: String): TimelineConfiguration {
        return TimelineConfiguration(
            focus = TimelineFocus.Live(hideThreadedEvents = false),
            filter = TimelineFilter.All,
            internalIdPrefix = "room_$roomId",
            dateDividerMode = DateDividerMode.DAILY,
            trackReadReceipts = TimelineReadReceiptTracking.ALL_EVENTS,
            reportUtds = false
        )
    }

    private suspend fun buildClient(homeserver: String): Client {
        val storePassphrase = prepareStorePassphrase()

        return withContext(Dispatchers.IO) {
            val paths = matrixStorePaths()
            ClientBuilder()
                .serverNameOrHomeserverUrl(homeserver)
                .sqliteStore(
                    SqliteStoreBuilder(paths.dataPath, paths.cachePath)
                        .passphrase(storePassphrase)
                )
                .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.DISCOVER_NATIVE)
                .setSessionDelegate(sessionDelegate)
                .autoEnableCrossSigning(true)
                .autoEnableBackups(true)
                .backupDownloadStrategy(BackupDownloadStrategy.AFTER_DECRYPTION_FAILURE)
                .userAgent("Zyna Android")
                .build()
        }
    }

    private suspend fun prepareStorePassphrase(): String {
        val existing = withContext(Dispatchers.IO) {
            storePassphraseStore.loadPassphraseOrNull()
        }
        if (existing != null) {
            return existing
        }

        clearMatrixStoreDirectories()
        return withContext(Dispatchers.IO) {
            storePassphraseStore.createPassphrase()
        }
    }

    private suspend fun clearStoredMatrixState() {
        sessionStore.clear()
        storePassphraseStore.clear()
        clearMatrixStoreDirectories()
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

    private companion object {
        const val TIMELINE_PAGE_SIZE = 50
        const val TIMELINE_MAX_VISIBLE_MESSAGES = 500
        const val TIMELINE_UPDATE_TIMEOUT_MS = 2_000L
    }
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
