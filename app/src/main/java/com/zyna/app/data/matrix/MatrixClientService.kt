package com.zyna.app.data.matrix

import android.content.Context
import android.util.Log
import com.zyna.app.data.media.BlurHashCodec
import com.zyna.app.data.messaging.CaptionPlacement
import com.zyna.app.data.messaging.MediaGroupLayoutOverride
import com.zyna.app.data.local.TimelineFlushSummary
import com.zyna.app.data.messaging.ZynaHtmlCodec
import com.zyna.app.data.messaging.ZynaMessageAttributes
import com.zyna.app.data.messaging.normalizedMessageCaption
import com.zyna.app.data.session.MatrixSessionStore
import com.zyna.app.data.session.MatrixStorePassphraseStore
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.DateDividerMode
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.ClientSessionDelegate
import org.matrix.rustcomponents.sdk.EmbeddedEventDetails
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.EventTimelineItem
import org.matrix.rustcomponents.sdk.FormattedBody
import org.matrix.rustcomponents.sdk.ImageInfo
import org.matrix.rustcomponents.sdk.ImageMessageContent
import org.matrix.rustcomponents.sdk.LatestEventValue
import org.matrix.rustcomponents.sdk.MediaSource
import org.matrix.rustcomponents.sdk.MessageFormat
import org.matrix.rustcomponents.sdk.MessageContent
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeContent
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.ProfileDetails
import org.matrix.rustcomponents.sdk.ReceiptType
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomListEntriesDynamicFilterKind
import org.matrix.rustcomponents.sdk.RoomListEntriesListener
import org.matrix.rustcomponents.sdk.RoomListEntriesUpdate
import org.matrix.rustcomponents.sdk.RoomListService
import org.matrix.rustcomponents.sdk.RoomListServiceState
import org.matrix.rustcomponents.sdk.RoomListServiceStateListener
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineConfiguration
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineFilter
import org.matrix.rustcomponents.sdk.TimelineFocus
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener
import org.matrix.rustcomponents.sdk.genTransactionId
import org.matrix.rustcomponents.sdk.use
import org.json.JSONObject
import uniffi.matrix_sdk.BackupDownloadStrategy
import uniffi.matrix_sdk_ui.LatestEventValueLocalState
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
    val avatarUrl: String?,
    val lastMessageText: String? = null,
    val lastMessageSenderName: String? = null,
    val lastMessageAtMillis: Long? = null,
    val lastOwnMessageStatus: MatrixLastOwnMessageStatus? = null,
    val unreadCount: Long = 0,
    val unreadMentionCount: Long = 0,
    val isMarkedUnread: Boolean = false
)

enum class MatrixLastOwnMessageStatus {
    PENDING,
    SENT,
    READ,
    FAILED
}

enum class MatrixMessageDeliveryState {
    SENT,
    SENDING,
    FAILED
}

enum class MatrixMessageContentType {
    TEXT,
    NOTICE,
    EMOTE,
    IMAGE,
    AUDIO,
    VIDEO,
    FILE,
    GALLERY,
    LOCATION,
    UNABLE_TO_DECRYPT,
    REDACTED,
    UNSUPPORTED
}

data class MatrixReplyInfo(
    val eventId: String,
    val senderId: String,
    val senderDisplayName: String?,
    val body: String
)

data class MatrixEditTarget(
    val messageId: String,
    val eventId: String,
    val body: String
)

data class MatrixForwardTarget(
    val body: String,
    val forwardedFrom: String?,
    val caption: String? = null,
    val imageItems: List<MatrixForwardImageItem> = emptyList(),
    val captionPlacement: CaptionPlacement = CaptionPlacement.BOTTOM,
    val layoutOverride: MediaGroupLayoutOverride? = null
)

data class MatrixForwardImageItem(
    val sourceJson: String,
    val thumbnailSourceJson: String?,
    val width: Int?,
    val height: Int?,
    val caption: String?,
    val mimeType: String?,
    val blurhash: String?
)

data class MatrixImageInfo(
    val sourceJson: String,
    val thumbnailSourceJson: String?,
    val width: Int?,
    val height: Int?,
    val caption: String?,
    val mimeType: String?,
    val blurhash: String?,
    val localPath: String? = null
)

data class MatrixMediaGroupItem(
    val messageId: String,
    val eventId: String?,
    val transactionId: String?,
    val imageInfo: MatrixImageInfo,
    val deliveryState: MatrixMessageDeliveryState
)

data class MatrixMediaGroupPresentation(
    val id: String,
    val totalHint: Int,
    val caption: String?,
    val captionPlacement: com.zyna.app.data.messaging.CaptionPlacement,
    val layoutOverride: com.zyna.app.data.messaging.MediaGroupLayoutOverride?,
    val suppressIndividualCaption: Boolean,
    val items: List<MatrixMediaGroupItem>,
    val rendersCompositeBubble: Boolean,
    val hidesStandaloneBubble: Boolean
)

data class MatrixChatMessage(
    /** Stable UI/cache identity: eventId, transactionId, or local outbox id. */
    val id: String,
    val eventId: String? = null,
    val transactionId: String? = null,
    val sender: String,
    val senderDisplayName: String? = null,
    val body: String,
    val timestampMillis: Long,
    val isOwn: Boolean,
    val contentType: MatrixMessageContentType = MatrixMessageContentType.TEXT,
    val imageInfo: MatrixImageInfo? = null,
    val deliveryState: MatrixMessageDeliveryState = MatrixMessageDeliveryState.SENT,
    val replyInfo: MatrixReplyInfo? = null,
    val forwardedFrom: String? = null,
    val zynaAttributes: ZynaMessageAttributes = ZynaMessageAttributes(),
    val isEdited: Boolean = false,
    val isEditPending: Boolean = false,
    val isEditFailed: Boolean = false,
    val latestEditEventId: String? = null,
    val editTransactionId: String? = null,
    val pendingEditBody: String? = null,
    val outgoingEnvelopeId: String? = null,
    val canRetryOutgoingEnvelope: Boolean = false,
    val canDiscardOutgoingEnvelope: Boolean = false,
    val mediaGroupPresentation: MatrixMediaGroupPresentation? = null
) {
    val isRemote: Boolean
        get() = eventId != null
}

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
    private var roomListService: RoomListService? = null
    private val activeTimelineLock = Any()
    private val activeRoomTimelines = mutableMapOf<String, Timeline>()
    private val timelinePaginationMutex = Mutex()

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
            roomListService?.close()
            roomListService = null
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
            roomListService?.close()
            roomListService = null
            syncService = null
            clearStoredMatrixState()
            _state.value = MatrixClientState.Error(error.displayMessage())
        }
    }

    private suspend fun resetClientForFreshLogin() {
        roomListService?.close()
        roomListService = null
        syncService?.stop()
        syncService?.close()
        syncService = null
        client?.close()
        client = null
        clearStoredMatrixState()
    }

    suspend fun logout() {
        roomListService?.close()
        roomListService = null
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
                room.use { activeRoom ->
                    activeRoom.toRoomSummary()
                }
            }
            ?.sortedBy { it.displayName.lowercase() }
            ?: emptyList()
    }

    fun roomListChangeSignals(): Flow<Unit> = callbackFlow {
        val service = roomListService
        if (service == null) {
            close(IllegalStateException("Matrix room list service is not ready"))
            return@callbackFlow
        }

        val stateListenerHandle = service.state(
            object : RoomListServiceStateListener {
                override fun onUpdate(state: RoomListServiceState) {
                    if (state == RoomListServiceState.RUNNING) {
                        trySendBlocking(Unit)
                    }
                }
            }
        )
        val roomList = service.allRooms()
        val entriesListener = object : RoomListEntriesListener {
            override fun onUpdate(roomEntriesUpdate: List<RoomListEntriesUpdate>) {
                trySendBlocking(Unit)
                roomEntriesUpdate.forEach { it.destroy() }
            }
        }
        val entriesResult = roomList.entriesWithDynamicAdapters(
            pageSize = ROOM_LIST_LIVE_PAGE_SIZE.toUInt(),
            listener = entriesListener
        )
        val entriesController = entriesResult.controller()
        entriesController.setFilter(RoomListEntriesDynamicFilterKind.NonLeft)
        trySend(Unit)

        awaitClose {
            entriesResult.entriesStream().cancelAndDestroy()
            entriesController.destroy()
            entriesResult.destroy()
            roomList.destroy()
            stateListenerHandle.cancelAndDestroy()
        }
    }.buffer(Channel.CONFLATED)
        .flowOn(Dispatchers.IO)

    fun roomTimelineMessageUpserts(roomId: String): Flow<MatrixTimelineUpdate> = callbackFlow {
        val activeClient = client ?: error("Matrix client is not ready")
        val room = activeClient.getRoom(roomId) ?: error("Matrix room is not available")
        var timeline: Timeline? = null
        var listenerHandle: TaskHandle? = null
        val hasEmittedInitialState = AtomicBoolean(false)
        val hasCleanedUp = AtomicBoolean(false)
        val diffBatcher = MatrixTimelineDiffBatcher(
            scope = this,
            debounceMillis = TIMELINE_EMIT_COALESCE_MS,
            mapTimelineItem = { item -> item.toChatMessageOrNull() },
            onFlush = { update ->
                hasEmittedInitialState.set(true)
                trySendBlocking(update)
            }
        )

        fun cleanup() {
            if (hasCleanedUp.compareAndSet(false, true)) {
                diffBatcher.cancel()
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
                        diffBatcher.receive(diff)
                    }
                }
            )

            val initialLoadingFallbackJob = launch {
                delay(TIMELINE_UPDATE_TIMEOUT_MS)
                if (hasEmittedInitialState.compareAndSet(false, true)) {
                    trySend(
                        MatrixTimelineUpdate(
                            messages = emptyList(),
                            flushSummary = TimelineFlushSummary()
                        )
                    )
                }
            }

            val paginationJob = launch(Dispatchers.IO) {
                runCatching {
                    timelinePaginationMutex.withLock {
                        for (page in 0 until TIMELINE_INITIAL_BACKFILL_PAGES) {
                            val hasReachedStart = openedTimeline.paginateBackwards(
                                TIMELINE_PAGE_SIZE.toUShort()
                            )
                            if (hasReachedStart) {
                                break
                            }
                        }
                    }
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
    }.distinctUntilChanged()
        .buffer(Channel.UNLIMITED)
        .flowOn(Dispatchers.IO)

    suspend fun paginateRoomTimelineBackwards(roomId: String): Boolean = withContext(Dispatchers.IO) {
        val activeTimeline = synchronized(activeTimelineLock) {
            activeRoomTimelines[roomId]
        } ?: error("Chat timeline is not ready")

        timelinePaginationMutex.withLock {
            for (page in 0 until TIMELINE_INTERACTIVE_BACKFILL_PAGES) {
                val hasReachedStart = activeTimeline.paginateBackwards(
                    TIMELINE_PAGE_SIZE.toUShort()
                )
                if (hasReachedStart) {
                    return@withLock true
                }
            }
            false
        }
    }

    suspend fun paginateRoomTimelineForwards(roomId: String): Boolean = withContext(Dispatchers.IO) {
        val activeTimeline = synchronized(activeTimelineLock) {
            activeRoomTimelines[roomId]
        } ?: error("Chat timeline is not ready")

        timelinePaginationMutex.withLock {
            for (page in 0 until TIMELINE_INTERACTIVE_BACKFILL_PAGES) {
                val hasReachedEnd = activeTimeline.paginateForwards(
                    TIMELINE_PAGE_SIZE.toUShort()
                )
                if (hasReachedEnd) {
                    return@withLock true
                }
            }
            false
        }
    }

    fun prepareTransactionId(): String {
        return genTransactionId()
    }

    suspend fun sendTextMessage(
        roomId: String,
        body: String,
        transactionId: String,
        replyInfo: MatrixReplyInfo? = null,
        forwardedFrom: String? = null
    ): String = withContext(Dispatchers.IO) {
        val text = body.trim()
        require(text.isNotEmpty()) { "Message is empty" }
        val activeClient = client ?: error("Matrix client is not ready")
        val room = activeClient.getRoom(roomId) ?: error("Matrix room is not available")
        val reply = replyInfo?.takeIf { it.eventId.isNotBlank() }
        val forwardedSender = forwardedFrom?.trim()?.takeIf { it.isNotBlank() }
        val content = JSONObject()
            .put("msgtype", "m.text")
            .put("body", if (reply == null) text else plainReplyBody(text, reply))
            .put(TRANSACTION_ID_CONTENT_KEY, transactionId)
        val formattedBody = formattedBody(
            roomId = roomId,
            body = text,
            replyInfo = reply,
            forwardedFrom = forwardedSender
        )
        if (reply != null) {
            content.put(
                "m.relates_to",
                JSONObject().put(
                    "m.in_reply_to",
                    JSONObject().put("event_id", reply.eventId)
                )
            )
        }
        if (formattedBody != null) {
            content.put("format", "org.matrix.custom.html")
            content.put("formatted_body", formattedBody)
        }
        val contentJson = content
            .toString()

        room.sendRawWithTransactionIdReturningEventId(
            eventType = "m.room.message",
            content = contentJson,
            transactionId = transactionId
        )
    }

    suspend fun sendImageMessage(
        roomId: String,
        localPath: String,
        mimeType: String,
        sizeBytes: Long,
        width: Int,
        height: Int,
        caption: String?,
        transactionId: String,
        zynaAttributesJson: String?,
        blurhash: String? = null,
        thumbnailLocalPath: String? = null,
        thumbnailMimeType: String? = null,
        thumbnailSizeBytes: Long? = null,
        thumbnailWidth: Int? = null,
        thumbnailHeight: Int? = null
    ): String = withContext(Dispatchers.IO) {
        val uploadedImageJson = uploadImageForEvent(
            roomId = roomId,
            localPath = localPath,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            width = width,
            height = height,
            blurhash = blurhash,
            thumbnailLocalPath = thumbnailLocalPath,
            thumbnailMimeType = thumbnailMimeType,
            thumbnailSizeBytes = thumbnailSizeBytes,
            thumbnailWidth = thumbnailWidth,
            thumbnailHeight = thumbnailHeight
        )
        sendUploadedImageMessage(
            roomId = roomId,
            uploadedImageJson = uploadedImageJson,
            caption = caption,
            transactionId = transactionId,
            zynaAttributesJson = zynaAttributesJson
        )
    }

    suspend fun uploadImageForEvent(
        roomId: String,
        localPath: String,
        mimeType: String,
        sizeBytes: Long,
        width: Int,
        height: Int,
        blurhash: String? = null,
        thumbnailLocalPath: String? = null,
        thumbnailMimeType: String? = null,
        thumbnailSizeBytes: Long? = null,
        thumbnailWidth: Int? = null,
        thumbnailHeight: Int? = null
    ): String = withContext(Dispatchers.IO) {
        val imageFile = File(localPath)
        require(imageFile.isFile) { "Image file is not available" }
        val thumbnailFile = thumbnailLocalPath
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.isFile }
        val activeClient = client ?: error("Matrix client is not ready")
        val room = activeClient.getRoom(roomId) ?: error("Matrix room is not available")
        val mediaBlurhash = blurhash?.takeIf { it.isNotBlank() }
            ?: thumbnailFile?.absolutePath?.let { path -> BlurHashCodec.encodeFile(path) }
            ?: BlurHashCodec.encodeFile(imageFile.absolutePath)
        room.uploadImageForEvent(
            originalFilePath = imageFile.absolutePath,
            thumbnailFilePath = thumbnailFile?.absolutePath,
            originalMimetype = mimeType.ifBlank { "image/jpeg" },
            originalSize = sizeBytes.takeIf { it > 0L }
                ?.toULong()
                ?: imageFile.length().coerceAtLeast(1L).toULong(),
            originalWidth = width.coerceAtLeast(1).toULong(),
            originalHeight = height.coerceAtLeast(1).toULong(),
            thumbnailMimetype = thumbnailFile?.let {
                thumbnailMimeType?.takeIf { it.isNotBlank() } ?: "image/jpeg"
            },
            thumbnailSize = thumbnailFile?.let {
                (thumbnailSizeBytes?.takeIf { it > 0L } ?: thumbnailFile.length().coerceAtLeast(1L))
                    .toULong()
            },
            thumbnailWidth = thumbnailFile?.let {
                thumbnailWidth?.takeIf { it > 0 }?.toULong()
            },
            thumbnailHeight = thumbnailFile?.let {
                thumbnailHeight?.takeIf { it > 0 }?.toULong()
            },
            blurhash = mediaBlurhash
        )
    }

    suspend fun sendUploadedImageMessage(
        roomId: String,
        uploadedImageJson: String,
        caption: String?,
        transactionId: String,
        zynaAttributesJson: String?
    ): String = withContext(Dispatchers.IO) {
        val activeClient = client ?: error("Matrix client is not ready")
        val room = activeClient.getRoom(roomId) ?: error("Matrix room is not available")
        val normalizedCaption = caption.normalizedMessageCaption()
        val zynaAttributes = ZynaHtmlCodec.decodeAttributesJson(zynaAttributesJson)
        val plainCaption = normalizedCaption
            ?: ZERO_WIDTH_SPACE.takeUnless { zynaAttributes.isEmpty }
        val formattedCaption = formattedMediaCaption(
            caption = normalizedCaption,
            attributes = zynaAttributes
        )

        room.sendUploadedImageWithTransactionIdReturningEventId(
            uploadedImageJson = uploadedImageJson,
            transactionId = transactionId,
            caption = plainCaption,
            formattedCaption = formattedCaption,
            replyEventId = null
        )
    }

    suspend fun sendForwardedImageMessage(
        roomId: String,
        image: MatrixForwardImageItem,
        caption: String?,
        transactionId: String,
        zynaAttributesJson: String?
    ): String = withContext(Dispatchers.IO) {
        val activeClient = client ?: error("Matrix client is not ready")
        val room = activeClient.getRoom(roomId) ?: error("Matrix room is not available")
        val source = MediaSource.fromJson(image.sourceJson)
        val thumbnailSource = image.thumbnailSourceJson
            ?.takeIf { it.isNotBlank() }
            ?.let { MediaSource.fromJson(it) }
        val normalizedCaption = caption.normalizedMessageCaption()
        val zynaAttributes = ZynaHtmlCodec.decodeAttributesJson(zynaAttributesJson)
        val plainCaption = normalizedCaption
            ?: ZERO_WIDTH_SPACE.takeUnless { zynaAttributes.isEmpty }
        val formattedCaption = formattedMediaCaption(
            caption = normalizedCaption,
            attributes = zynaAttributes
        )
        val msgType = MessageType.Image(
            ImageMessageContent(
                filename = "image.jpg",
                caption = plainCaption,
                formattedCaption = formattedCaption?.let {
                    FormattedBody(format = MessageFormat.Html, body = it)
                },
                source = source,
                info = ImageInfo(
                    height = image.height?.takeIf { it > 0 }?.toULong(),
                    width = image.width?.takeIf { it > 0 }?.toULong(),
                    mimetype = image.mimeType?.takeIf { it.isNotBlank() } ?: "image/jpeg",
                    size = null,
                    thumbnailInfo = null,
                    thumbnailSource = thumbnailSource,
                    blurhash = image.blurhash?.takeIf { it.isNotBlank() },
                    isAnimated = null
                )
            )
        )
        msgType.use { messageType ->
            room.sendMessageTypeWithTransactionIdReturningEventId(
                msgType = messageType,
                transactionId = transactionId,
                replyEventId = null
            )
        }
    }

    suspend fun sendTextEdit(
        roomId: String,
        eventId: String,
        body: String,
        transactionId: String
    ): String = withContext(Dispatchers.IO) {
        val text = body.trim()
        require(eventId.isNotBlank()) { "Edited message event id is empty" }
        require(text.isNotEmpty()) { "Edited message is empty" }
        val activeClient = client ?: error("Matrix client is not ready")
        val room = activeClient.getRoom(roomId) ?: error("Matrix room is not available")
        val newContent = JSONObject()
            .put("msgtype", "m.text")
            .put("body", text)
        val content = JSONObject()
            .put("msgtype", "m.text")
            .put("body", "* $text")
            .put("m.new_content", newContent)
            .put(
                "m.relates_to",
                JSONObject()
                    .put("rel_type", "m.replace")
                    .put("event_id", eventId)
            )
            .put(TRANSACTION_ID_CONTENT_KEY, transactionId)

        room.sendRawWithTransactionIdReturningEventId(
            eventType = "m.room.message",
            content = content.toString(),
            transactionId = transactionId
        )
    }

    suspend fun redactMessage(
        roomId: String,
        eventId: String,
        transactionId: String,
        reason: String? = null
    ): String = withContext(Dispatchers.IO) {
        require(eventId.isNotBlank()) { "Message event id is empty" }
        val activeClient = client ?: error("Matrix client is not ready")
        val room = activeClient.getRoom(roomId) ?: error("Matrix room is not available")

        room.redactWithTransactionIdReturningEventId(
            eventId = eventId,
            reason = reason,
            transactionId = transactionId
        )
    }

    suspend fun sendReadReceipt(
        roomId: String,
        eventId: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (eventId.isBlank()) {
            return@withContext false
        }

        val activeTimeline = synchronized(activeTimelineLock) {
            activeRoomTimelines[roomId]
        } ?: return@withContext false

        try {
            activeTimeline.sendReadReceipt(
                receiptType = ReceiptType.READ,
                eventId = eventId
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "sendReadReceipt(.read) failed event=$eventId", error)
        }

        try {
            activeTimeline.sendReadReceipt(
                receiptType = ReceiptType.FULLY_READ,
                eventId = eventId
            )
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "sendReadReceipt(.fullyRead) failed event=$eventId", error)
            false
        }
    }

    suspend fun loadMediaContent(sourceJson: String): ByteArray = withContext(Dispatchers.IO) {
        val activeClient = client ?: error("Matrix client is not ready")
        val source = MediaSource.fromJson(sourceJson)
        source.use { mediaSource ->
            activeClient.getMediaContent(mediaSource)
        }
    }

    suspend fun loadMediaThumbnail(
        sourceJson: String,
        width: Int,
        height: Int
    ): ByteArray = withContext(Dispatchers.IO) {
        val activeClient = client ?: error("Matrix client is not ready")
        val source = MediaSource.fromJson(sourceJson)
        source.use { mediaSource ->
            activeClient.getMediaThumbnail(
                mediaSource = mediaSource,
                width = width.coerceAtLeast(1).toULong(),
                height = height.coerceAtLeast(1).toULong()
            )
        }
    }

    private fun TimelineItem.toChatMessageOrNull(): MatrixChatMessage? = use { item ->
        val event = item.asEvent() ?: return@use null
        event.toChatMessageOrNull()
    }

    private fun EventTimelineItem.toChatMessageOrNull(): MatrixChatMessage? {
        val msgLike = (content as? TimelineItemContent.MsgLike)?.content
            ?: return null
        val replyInfo = msgLike.replyInfoOrNull()
        val messageBody = when (val kind = msgLike.kind) {
            is MsgLikeKind.Message -> MatrixMessageBody(
                body = kind.content.displayBody(),
                contentType = kind.content.contentType(),
                imageInfo = kind.content.imageInfoOrNull()
            )
            MsgLikeKind.Redacted -> MatrixMessageBody(
                body = "Deleted message",
                contentType = MatrixMessageContentType.REDACTED,
                imageInfo = null
            )
            is MsgLikeKind.UnableToDecrypt -> MatrixMessageBody(
                body = "Unable to decrypt message",
                contentType = MatrixMessageContentType.UNABLE_TO_DECRYPT,
                imageInfo = null
            )
            else -> return null
        }
        val body = if (replyInfo == null) {
            messageBody.body
        } else {
            messageBody.body.stripMatrixReplyFallback()
        }
        val eventId = eventOrTransactionId.eventIdOrNull()
        val transactionId = eventOrTransactionId.transactionIdOrNull()
        val messageContent = (msgLike.kind as? MsgLikeKind.Message)?.content
        val isEdited = messageContent?.isEdited ?: false
        val zynaAttributes = lazyProvider.latestJson()
            ?.zynaAttributesFromRawEvent()
            ?: messageContent?.zynaAttributes()
            ?: ZynaMessageAttributes()

        return MatrixChatMessage(
            id = eventOrTransactionId.stableId(),
            eventId = eventId,
            transactionId = transactionId,
            sender = sender,
            senderDisplayName = senderProfile.displayNameOrNull(),
            body = body,
            timestampMillis = timestamp.toLong(),
            isOwn = isOwn,
            contentType = messageBody.contentType,
            imageInfo = messageBody.imageInfo,
            replyInfo = replyInfo,
            forwardedFrom = zynaAttributes.forwardedFrom,
            zynaAttributes = zynaAttributes,
            isEdited = isEdited
        )
    }

    private fun MsgLikeContent.replyInfoOrNull(): MatrixReplyInfo? {
        val replyDetails = inReplyTo ?: return null
        val eventId = runCatching { replyDetails.eventId() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val embeddedEvent = runCatching { replyDetails.event() }.getOrNull()
        return try {
            val readyEvent = embeddedEvent as? EmbeddedEventDetails.Ready
            val embeddedContent = readyEvent?.content as? TimelineItemContent.MsgLike
            val embeddedMessageBody = embeddedContent?.content?.replyPreviewBodyOrNull()

            MatrixReplyInfo(
                eventId = eventId,
                senderId = readyEvent?.sender.orEmpty(),
                senderDisplayName = (readyEvent?.senderProfile as? ProfileDetails.Ready)
                    ?.displayName
                    ?.takeIf { it.isNotBlank() },
                body = embeddedMessageBody?.stripMatrixReplyFallback().orEmpty()
            )
        } finally {
            embeddedEvent?.destroy()
        }
    }

    private fun MsgLikeContent.replyPreviewBodyOrNull(): String? {
        return when (val kind = kind) {
            is MsgLikeKind.Message -> kind.content.displayBody()
            MsgLikeKind.Redacted -> "Deleted message"
            is MsgLikeKind.UnableToDecrypt -> "Unable to decrypt message"
            else -> null
        }
    }

    private fun EventOrTransactionId.stableId(): String {
        return when (this) {
            is EventOrTransactionId.EventId -> eventId
            is EventOrTransactionId.TransactionId -> transactionId
        }
    }

    private fun EventOrTransactionId.eventIdOrNull(): String? {
        return (this as? EventOrTransactionId.EventId)?.eventId
    }

    private fun EventOrTransactionId.transactionIdOrNull(): String? {
        return (this as? EventOrTransactionId.TransactionId)?.transactionId
    }

    private fun MessageContent.displayBody(): String {
        return when (val type = msgType) {
            is MessageType.Text -> type.content.body
            is MessageType.Notice -> type.content.body
            is MessageType.Emote -> type.content.body
            is MessageType.Image -> type.content.caption.normalizedMessageCaption() ?: "Photo"
            is MessageType.Audio -> type.content.caption.normalizedMessageCaption() ?: body.ifBlank { "Audio" }
            is MessageType.Video -> type.content.caption.normalizedMessageCaption() ?: body.ifBlank { "Video" }
            is MessageType.File -> type.content.caption.normalizedMessageCaption() ?: body.ifBlank { "File" }
            is MessageType.Gallery -> type.content.body
            is MessageType.Location -> type.content.body
            is MessageType.Other -> type.body
        }
    }

    private fun MessageContent.contentType(): MatrixMessageContentType {
        return when (msgType) {
            is MessageType.Text -> MatrixMessageContentType.TEXT
            is MessageType.Notice -> MatrixMessageContentType.NOTICE
            is MessageType.Emote -> MatrixMessageContentType.EMOTE
            is MessageType.Image -> MatrixMessageContentType.IMAGE
            is MessageType.Audio -> MatrixMessageContentType.AUDIO
            is MessageType.Video -> MatrixMessageContentType.VIDEO
            is MessageType.File -> MatrixMessageContentType.FILE
            is MessageType.Gallery -> MatrixMessageContentType.GALLERY
            is MessageType.Location -> MatrixMessageContentType.LOCATION
            is MessageType.Other -> MatrixMessageContentType.UNSUPPORTED
        }
    }

    private fun MessageContent.imageInfoOrNull(): MatrixImageInfo? {
        val image = (msgType as? MessageType.Image)?.content ?: return null
        val sourceJson = runCatching { image.source.toJson() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val info = image.info
        return MatrixImageInfo(
            sourceJson = sourceJson,
            thumbnailSourceJson = info?.thumbnailSource
                ?.let { source -> runCatching { source.toJson() }.getOrNull() }
                ?.takeIf { it.isNotBlank() },
            width = info?.width?.toIntOrNull(),
            height = info?.height?.toIntOrNull(),
            caption = image.caption.normalizedMessageCaption(),
            mimeType = info?.mimetype?.takeIf { it.isNotBlank() },
            blurhash = info?.blurhash?.takeIf { it.isNotBlank() }
        )
    }

    private fun plainReplyBody(body: String, replyInfo: MatrixReplyInfo): String {
        val quotedLines = replyInfo.body
            .lineSequence()
            .mapIndexed { index, line ->
                if (index == 0) {
                    "> <${replyInfo.senderId}> $line"
                } else {
                    "> $line"
                }
            }
            .toList()
            .ifEmpty { listOf("> <${replyInfo.senderId}>") }
        return quotedLines.joinToString(separator = "\n") + "\n\n" + body
    }

    private fun formattedBody(
        roomId: String,
        body: String,
        replyInfo: MatrixReplyInfo?,
        forwardedFrom: String?
    ): String? {
        if (replyInfo == null && forwardedFrom.isNullOrBlank()) {
            return null
        }
        var html = ZynaHtmlCodec.escapeForHtmlAttribute(body).htmlLineBreaks()
        if (replyInfo != null) {
            html = htmlReplyFallback(roomId, replyInfo) + html
        }
        return ZynaHtmlCodec.encode(
            userHtml = html,
            attributes = ZynaMessageAttributes(forwardedFrom = forwardedFrom)
        )
    }

    private fun formattedMediaCaption(
        caption: String?,
        attributes: ZynaMessageAttributes
    ): String? {
        if (caption == null && attributes.isEmpty) {
            return null
        }

        val userHtml = caption
            ?.let { ZynaHtmlCodec.escapeForHtmlAttribute(it).htmlLineBreaks() }
            ?: ZERO_WIDTH_SPACE
        return ZynaHtmlCodec.encode(
            userHtml = userHtml,
            attributes = attributes
        )
    }

    private fun htmlReplyFallback(roomId: String, replyInfo: MatrixReplyInfo): String {
        val roomEventLink = ZynaHtmlCodec.escapeForHtmlAttribute(
            "https://matrix.to/#/$roomId/${replyInfo.eventId}"
        )
        val senderLink = ZynaHtmlCodec.escapeForHtmlAttribute(
            "https://matrix.to/#/${replyInfo.senderId}"
        )
        val senderName = ZynaHtmlCodec.escapeForHtmlAttribute(
            replyInfo.senderDisplayName ?: replyInfo.senderId
        )
        val quotedBody = ZynaHtmlCodec.escapeForHtmlAttribute(replyInfo.body).htmlLineBreaks()

        return "<mx-reply><blockquote><a href=\"$roomEventLink\">In reply to</a> " +
            "<a href=\"$senderLink\">$senderName</a><br>$quotedBody</blockquote></mx-reply>"
    }

    private fun MessageContent.zynaAttributes(): ZynaMessageAttributes? {
        val formatted = formattedHtmlBodyOrNull() ?: return null
        return ZynaHtmlCodec.decode(formatted)
    }

    private fun String.zynaAttributesFromRawEvent(): ZynaMessageAttributes? {
        val content = runCatching { JSONObject(this).optJSONObject("content") }
            .getOrNull()
            ?: return null
        return content.zynaAttributesFromContent()
    }

    private fun JSONObject.zynaAttributesFromContent(): ZynaMessageAttributes? {
        val editedAttributes = optJSONObject("m.new_content")
            ?.zynaAttributesFromContent()
        if (editedAttributes != null && !editedAttributes.isEmpty) {
            return editedAttributes
        }

        val formatted = optStringOrNull("formatted_body") ?: return null
        return ZynaHtmlCodec.decode(formatted)
    }

    private fun MessageContent.formattedHtmlBodyOrNull(): String? {
        return when (val type = msgType) {
            is MessageType.Text -> type.content.formatted?.body
            is MessageType.Notice -> type.content.formatted?.body
            is MessageType.Emote -> type.content.formatted?.body
            else -> null
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        return opt(key) as? String
    }

    private fun String.stripMatrixReplyFallback(): String {
        val normalized = replace("\r\n", "\n").replace('\r', '\n')
        val separatorIndex = normalized.indexOf("\n\n")
        if (separatorIndex <= 0) {
            return this
        }

        val quotedPart = normalized.substring(0, separatorIndex)
        val isReplyFallback = quotedPart
            .lineSequence()
            .filter { it.isNotBlank() }
            .all { it.startsWith(">") }
        return if (isReplyFallback) normalized.substring(separatorIndex + 2) else this
    }

    private fun String.htmlLineBreaks(): String {
        return replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\n", "<br>")
    }

    private suspend fun Room.toRoomSummary(): MatrixRoomSummary {
        val roomInfo = runCatching { roomInfo() }.getOrNull()
        try {
            val latestPreview = latestEvent().toRoomPreview()
            return MatrixRoomSummary(
                id = id(),
                displayName = displayName()
                    ?.takeIf { it.isNotBlank() }
                    ?: roomInfo?.displayName?.takeIf { it.isNotBlank() }
                    ?: id(),
                avatarUrl = avatarUrl() ?: roomInfo?.avatarUrl,
                lastMessageText = latestPreview.body,
                lastMessageSenderName = latestPreview.senderName,
                lastMessageAtMillis = latestPreview.timestampMillis,
                lastOwnMessageStatus = resolveLastOwnMessageStatus(latestPreview),
                unreadCount = roomInfo?.numUnreadMessages?.toLong() ?: 0,
                unreadMentionCount = roomInfo?.numUnreadMentions?.toLong() ?: 0,
                isMarkedUnread = roomInfo?.isMarkedUnread ?: false
            )
        } finally {
            roomInfo?.destroy()
        }
    }

    private suspend fun Room.resolveLastOwnMessageStatus(
        preview: MatrixRoomPreview
    ): MatrixLastOwnMessageStatus? {
        if (!preview.needsReadReceiptSummary) {
            return preview.localOwnMessageStatus
        }

        val readReceiptSummary = runCatching {
            latestOwnMainTimelineReadReceiptSummary()
        }.getOrNull()
        return if (readReceiptSummary?.hasReadReceiptFromOtherUser == true) {
            MatrixLastOwnMessageStatus.READ
        } else {
            preview.localOwnMessageStatus
        }
    }

    private fun LatestEventValue.toRoomPreview(): MatrixRoomPreview = use { latestEvent ->
        when (latestEvent) {
            LatestEventValue.None -> MatrixRoomPreview()
            is LatestEventValue.Remote -> MatrixRoomPreview(
                body = latestEvent.content.roomPreviewBody() ?: "",
                senderName = latestEvent.sender.previewSenderName(
                    isOwn = latestEvent.isOwn,
                    profile = latestEvent.profile
                ),
                timestampMillis = latestEvent.timestamp.toLong(),
                localOwnMessageStatus = if (latestEvent.isOwn) {
                    MatrixLastOwnMessageStatus.SENT
                } else {
                    null
                },
                needsReadReceiptSummary = latestEvent.isOwn
            )
            is LatestEventValue.Local -> MatrixRoomPreview(
                body = latestEvent.content.roomPreviewBody() ?: "",
                senderName = latestEvent.sender.previewSenderName(
                    isOwn = true,
                    profile = latestEvent.profile
                ),
                timestampMillis = latestEvent.timestamp.toLong(),
                localOwnMessageStatus = latestEvent.state.toLastOwnMessageStatus()
            )
            is LatestEventValue.RemoteInvite -> MatrixRoomPreview(
                timestampMillis = latestEvent.timestamp.toLong()
            )
        }
    }

    private fun TimelineItemContent.roomPreviewBody(): String? {
        val messageContent = (this as? TimelineItemContent.MsgLike)?.content
            ?: return null
        return when (val kind = messageContent.kind) {
            is MsgLikeKind.Message -> kind.content.roomPreviewBody()
            is MsgLikeKind.Sticker -> "Sticker"
            is MsgLikeKind.Poll -> "Poll: ${kind.question}"
            MsgLikeKind.Redacted -> "Deleted message"
            is MsgLikeKind.UnableToDecrypt -> "Unable to decrypt message"
            is MsgLikeKind.LiveLocation -> "Live location"
            is MsgLikeKind.Other -> null
        }
    }

    private fun MessageContent.roomPreviewBody(): String {
        return when (val type = msgType) {
            is MessageType.Text -> type.content.body
            is MessageType.Notice -> type.content.body
            is MessageType.Emote -> type.content.body
            is MessageType.Image -> "Photo"
            is MessageType.Audio -> "Audio"
            is MessageType.Video -> "Video"
            is MessageType.File -> "File"
            is MessageType.Location -> "Location"
            is MessageType.Gallery,
            is MessageType.Other -> "Message"
        }
    }

    private fun String.previewSenderName(isOwn: Boolean, profile: ProfileDetails): String {
        if (isOwn) return OWN_MESSAGE_PREVIEW_SENDER

        val displayName = (profile as? ProfileDetails.Ready)?.displayName
        return displayName?.takeIf { it.isNotBlank() } ?: this
    }

    private fun ProfileDetails.displayNameOrNull(): String? {
        return (this as? ProfileDetails.Ready)
            ?.displayName
            ?.takeIf { it.isNotBlank() }
    }

    private fun ULong.toIntOrNull(): Int? {
        return takeIf { it in 1UL..Int.MAX_VALUE.toULong() }?.toInt()
    }

    private fun LatestEventValueLocalState.toLastOwnMessageStatus(): MatrixLastOwnMessageStatus {
        return when (this) {
            LatestEventValueLocalState.IS_SENDING -> MatrixLastOwnMessageStatus.PENDING
            LatestEventValueLocalState.HAS_BEEN_SENT -> MatrixLastOwnMessageStatus.SENT
            LatestEventValueLocalState.CANNOT_BE_SENT -> MatrixLastOwnMessageStatus.FAILED
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
        val roomList = service.roomListService()
        syncService = service
        roomListService = roomList
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

    private data class MatrixRoomPreview(
        val body: String? = null,
        val senderName: String? = null,
        val timestampMillis: Long? = null,
        val localOwnMessageStatus: MatrixLastOwnMessageStatus? = null,
        val needsReadReceiptSummary: Boolean = false
    )

    private data class MatrixMessageBody(
        val body: String,
        val contentType: MatrixMessageContentType,
        val imageInfo: MatrixImageInfo?
    )

    private companion object {
        const val TAG = "MatrixClientService"
        const val TIMELINE_PAGE_SIZE = 100
        const val TIMELINE_INITIAL_BACKFILL_PAGES = 5
        const val TIMELINE_INTERACTIVE_BACKFILL_PAGES = 3
        const val TIMELINE_EMIT_COALESCE_MS = 50L
        const val TIMELINE_UPDATE_TIMEOUT_MS = 2_000L
        const val ROOM_LIST_LIVE_PAGE_SIZE = 512
        const val TRANSACTION_ID_CONTENT_KEY = "com.zyna.client_txn_id"
        const val OWN_MESSAGE_PREVIEW_SENDER = "You"
        const val ZERO_WIDTH_SPACE = "\u200B"
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
