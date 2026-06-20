package com.zyna.app.ui.app

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import com.zyna.app.BuildConfig
import com.zyna.app.data.local.LocalCacheRepository
import com.zyna.app.data.local.TimelineFlushSummary
import com.zyna.app.data.local.TimelineWindowChangeOrigin
import com.zyna.app.data.media.MatrixMediaLoader
import com.zyna.app.data.matrix.MatrixChatMessage
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixClientState
import com.zyna.app.data.matrix.MatrixEditTarget
import com.zyna.app.data.matrix.MatrixForwardTarget
import com.zyna.app.data.matrix.MatrixMessageContentType
import com.zyna.app.data.matrix.MatrixMessageDeliveryState
import com.zyna.app.data.matrix.MatrixReplyInfo
import com.zyna.app.data.matrix.MatrixRoomSummary
import com.zyna.app.data.messaging.CaptionMode
import com.zyna.app.data.messaging.CaptionPlacement
import com.zyna.app.data.messaging.MediaGroupInfo
import com.zyna.app.data.messaging.ZynaMessageAttributes
import com.zyna.app.data.outgoing.OutgoingOutboxService
import com.zyna.app.data.outgoing.OutgoingPhotoDraft
import com.zyna.app.data.timeline.RoomTimelineWindowStore
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface AppRoute {
    data object Login : AppRoute
    data class RecoveryKey(val userId: String) : AppRoute
    data object Rooms : AppRoute
    data object ForwardPicker : AppRoute
    data class Chat(
        val roomId: String,
        val displayName: String
    ) : AppRoute
}

data class AppUiState(
    val route: AppRoute = AppRoute.Login,
    val matrixState: MatrixClientState = MatrixClientState.LoggedOut,
    val rooms: List<MatrixRoomSummary> = emptyList(),
    val isRefreshingRooms: Boolean = false,
    val isRecovering: Boolean = false,
    val recoveryErrorMessage: String? = null,
    val chatMessages: List<MatrixChatMessage> = emptyList(),
    val chatWindowChangeOrigin: TimelineWindowChangeOrigin = TimelineWindowChangeOrigin.INITIAL_LOAD,
    val chatTimelineFlushSummary: TimelineFlushSummary? = null,
    val isLoadingChat: Boolean = false,
    val isLoadingOlderChatMessages: Boolean = false,
    val canLoadOlderChatMessages: Boolean = true,
    val canLoadNewerChatMessages: Boolean = false,
    val isChatAtLiveEdge: Boolean = true,
    val chatErrorMessage: String? = null,
    val isSendingChatMessage: Boolean = false,
    val chatSendErrorMessage: String? = null,
    val chatReplyTarget: MatrixReplyInfo? = null,
    val chatEditTarget: MatrixEditTarget? = null,
    val chatForwardTarget: MatrixForwardTarget? = null,
    val pendingForwardTarget: MatrixForwardTarget? = null,
    val forwardReturnRoute: AppRoute? = null,
    val chatJumpTargetEventId: String? = null,
    val chatScrollToLiveEdgeRequested: Boolean = false
) {
    val isBusy: Boolean
        get() = matrixState is MatrixClientState.LoggingIn ||
            matrixState is MatrixClientState.RestoringSession

    val errorMessage: String?
        get() = (matrixState as? MatrixClientState.Error)?.message
}

private data class VisibleReadReceiptTarget(
    val roomId: String,
    val eventId: String
)

private sealed interface PendingReadReceiptSend {
    val target: VisibleReadReceiptTarget

    data class Bootstrap(
        override val target: VisibleReadReceiptTarget
    ) : PendingReadReceiptSend

    data class Advance(
        override val target: VisibleReadReceiptTarget
    ) : PendingReadReceiptSend
}

class AppViewModel(
    private val matrixClientService: MatrixClientService,
    private val localCacheRepository: LocalCacheRepository,
    private val outgoingOutboxService: OutgoingOutboxService,
    private val matrixMediaLoader: MatrixMediaLoader
) : ViewModel() {
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()
    private var chatTimelineJob: Job? = null
    private var chatPaginationJob: Job? = null
    private var chatCacheJob: Job? = null
    private var openRoomJob: Job? = null
    private var chatTimelineWindowStore: RoomTimelineWindowStore? = null
    private var roomCacheJob: Job? = null
    private var roomListLiveJob: Job? = null
    private var roomCacheUserId: String? = null
    private var roomListLiveUserId: String? = null
    private var readReceiptJob: Job? = null
    private var readReceiptBaselineTarget: VisibleReadReceiptTarget? = null
    private var pendingReadReceiptSend: PendingReadReceiptSend? = null

    init {
        outgoingOutboxService.start(viewModelScope)

        viewModelScope.launch {
            runCatching { localCacheRepository.cleanupOrphanOutgoingMediaFiles() }
        }

        viewModelScope.launch {
            matrixClientService.state.collect { matrixState ->
                val previousUserId = _uiState.value.matrixState.userIdOrNull()
                val nextUserId = matrixState.userIdOrNull()
                val didChangeUser = previousUserId != null &&
                    nextUserId != null &&
                    previousUserId != nextUserId
                if (
                    nextUserId == null ||
                    didChangeUser ||
                    matrixState is MatrixClientState.Error
                ) {
                    stopChatTimeline()
                }
                if (nextUserId == null || didChangeUser || matrixState is MatrixClientState.Error) {
                    stopRoomCache()
                    stopRoomListLiveRefresh()
                }

                _uiState.update { current ->
                    val shouldClearSessionData = nextUserId == null || didChangeUser
                    val shouldClearChat = shouldClearSessionData ||
                        matrixState is MatrixClientState.Error

                    current.copy(
                        matrixState = matrixState,
                        route = routeForState(
                            matrixState,
                            if (didChangeUser) AppRoute.Login else current.route
                        ),
                        rooms = if (shouldClearSessionData) {
                            emptyList()
                        } else {
                            current.rooms
                        },
                        chatMessages = if (shouldClearChat) emptyList() else current.chatMessages,
                        chatWindowChangeOrigin = if (shouldClearChat) {
                            TimelineWindowChangeOrigin.INITIAL_LOAD
                        } else {
                            current.chatWindowChangeOrigin
                        },
                        chatTimelineFlushSummary = if (shouldClearChat) {
                            null
                        } else {
                            current.chatTimelineFlushSummary
                        },
                        isLoadingChat = if (shouldClearChat) false else current.isLoadingChat,
                        isLoadingOlderChatMessages = if (shouldClearChat) {
                            false
                        } else {
                            current.isLoadingOlderChatMessages
                        },
                        canLoadOlderChatMessages = if (shouldClearChat) {
                            true
                        } else {
                            current.canLoadOlderChatMessages
                        },
                        canLoadNewerChatMessages = if (shouldClearChat) {
                            false
                        } else {
                            current.canLoadNewerChatMessages
                        },
                        isChatAtLiveEdge = if (shouldClearChat) true else current.isChatAtLiveEdge,
                        chatErrorMessage = if (shouldClearChat) null else current.chatErrorMessage,
                        isSendingChatMessage = if (shouldClearChat) false else current.isSendingChatMessage,
                        chatSendErrorMessage = if (shouldClearChat) null else current.chatSendErrorMessage,
                        chatReplyTarget = if (shouldClearChat) null else current.chatReplyTarget,
                        chatEditTarget = if (shouldClearChat) null else current.chatEditTarget,
                        chatForwardTarget = if (shouldClearChat) null else current.chatForwardTarget,
                        pendingForwardTarget = if (shouldClearSessionData) {
                            null
                        } else {
                            current.pendingForwardTarget
                        },
                        forwardReturnRoute = if (shouldClearSessionData) {
                            null
                        } else {
                            current.forwardReturnRoute
                        },
                        chatJumpTargetEventId = if (shouldClearChat) {
                            null
                        } else {
                            current.chatJumpTargetEventId
                        },
                        chatScrollToLiveEdgeRequested = if (shouldClearChat) {
                            false
                        } else {
                            current.chatScrollToLiveEdgeRequested
                        }
                    )
                }

                if (nextUserId != null) {
                    startRoomCache(nextUserId)
                }

                if (matrixState is MatrixClientState.Syncing) {
                    val userId = matrixState.userId
                    if (matrixClientService.isRecoveryComplete(userId)) {
                        startRoomListLiveRefresh(userId)
                        refreshRooms()
                    }
                }
            }
        }

        viewModelScope.launch {
            outgoingOutboxService.sendFailures.collect { failure ->
                _uiState.update {
                    if (!it.isRouteForRoom(failure.roomId)) {
                        it
                    } else it.copy(chatSendErrorMessage = failure.message)
                }
            }
        }

        viewModelScope.launch {
            matrixClientService.restoreSessionIfAvailable()
        }
    }

    fun login(homeserver: String, username: String, password: String) {
        viewModelScope.launch {
            matrixClientService.login(
                homeserver = homeserver,
                username = username,
                password = password
            )
        }
    }

    fun refreshRooms() {
        viewModelScope.launch {
            refreshRoomsNow()
        }
    }

    private suspend fun refreshRoomsNow() {
        _uiState.update { it.copy(isRefreshingRooms = true) }
        try {
            val userId = _uiState.value.matrixState.userIdOrNull() ?: return
            val rooms = matrixClientService.roomsSnapshot()
            localCacheRepository.cacheRoomsSnapshot(userId, rooms)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to refresh rooms", error)
        } finally {
            _uiState.update {
                it.copy(isRefreshingRooms = false)
            }
        }
    }

    fun submitRecoveryKey(recoveryKey: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRecovering = true,
                    recoveryErrorMessage = null
                )
            }

            try {
                matrixClientService.recoverWithRecoveryKey(recoveryKey)
                _uiState.update {
                    it.copy(
                        route = AppRoute.Rooms,
                        isRecovering = false,
                        recoveryErrorMessage = null
                    )
                }
                val userId = matrixClientService.state.value.userIdOrNull() ?: return@launch
                startRoomListLiveRefresh(userId)
                refreshRoomsNow()
                outgoingOutboxService.kick(reason = "recovery-complete")
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        isRecovering = false,
                        recoveryErrorMessage = error.message ?: error.javaClass.simpleName
                    )
                }
            }
        }
    }

    fun openRoom(room: MatrixRoomSummary) {
        openRoom(room, forwardTarget = null)
    }

    private fun openRoom(
        room: MatrixRoomSummary,
        forwardTarget: MatrixForwardTarget?
    ) {
        val userId = _uiState.value.matrixState.userIdOrNull() ?: return
        stopChatTimeline()

        val timelineStore = RoomTimelineWindowStore(
            userId = userId,
            roomId = room.id,
            localCacheRepository = localCacheRepository
        )
        openRoomJob = viewModelScope.launch {
            val initialMessages = try {
                timelineStore.initialMessagesSnapshot()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to load initial chat window from cache", error)
                emptyList()
            }

            _uiState.update {
                if (it.matrixState.userIdOrNull() != userId) {
                    it
                } else it.copy(
                    route = AppRoute.Chat(
                        roomId = room.id,
                        displayName = room.displayName
                    ),
                    chatMessages = initialMessages,
                    chatWindowChangeOrigin = TimelineWindowChangeOrigin.INITIAL_LOAD,
                    chatTimelineFlushSummary = null,
                    isLoadingChat = initialMessages.isEmpty(),
                    isLoadingOlderChatMessages = false,
                    canLoadOlderChatMessages = true,
                    canLoadNewerChatMessages = false,
                    isChatAtLiveEdge = true,
                    chatErrorMessage = null,
                    isSendingChatMessage = false,
                    chatSendErrorMessage = null,
                    chatReplyTarget = null,
                    chatEditTarget = null,
                    chatForwardTarget = forwardTarget,
                    pendingForwardTarget = null,
                    forwardReturnRoute = null,
                    chatJumpTargetEventId = null,
                    chatScrollToLiveEdgeRequested = false
                )
            }

            if (_uiState.value.isRouteForRoom(userId, room.id)) {
                startChatTimeline(
                    userId = userId,
                    roomId = room.id,
                    resetMessages = false,
                    timelineStore = timelineStore
                )
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (openRoomJob == job) {
                    openRoomJob = null
                }
            }
        }
    }

    fun closeChat() {
        stopChatTimeline()
        _uiState.update {
            it.copy(
                route = AppRoute.Rooms,
                chatMessages = emptyList(),
                chatWindowChangeOrigin = TimelineWindowChangeOrigin.INITIAL_LOAD,
                chatTimelineFlushSummary = null,
                isLoadingChat = false,
                isLoadingOlderChatMessages = false,
                canLoadOlderChatMessages = true,
                canLoadNewerChatMessages = false,
                isChatAtLiveEdge = true,
                chatErrorMessage = null,
                isSendingChatMessage = false,
                chatSendErrorMessage = null,
                chatReplyTarget = null,
                chatEditTarget = null,
                chatForwardTarget = null,
                pendingForwardTarget = null,
                forwardReturnRoute = null,
                chatJumpTargetEventId = null,
                chatScrollToLiveEdgeRequested = false
            )
        }
    }

    fun refreshCurrentChat() {
        val route = _uiState.value.route as? AppRoute.Chat ?: return
        val userId = _uiState.value.matrixState.userIdOrNull() ?: return
        startChatTimeline(userId, route.roomId, resetMessages = false)
    }

    fun sendChatMessage(body: String): Boolean {
        val route = _uiState.value.route as? AppRoute.Chat ?: return false
        val userId = _uiState.value.matrixState.userIdOrNull() ?: return false
        val state = _uiState.value
        val forwardTarget = state.chatForwardTarget
        val isForwardingMedia = forwardTarget?.imageItems?.isNotEmpty() == true
        val text = if (isForwardingMedia) {
            forwardTarget?.body?.trim().orEmpty().ifBlank { "Photo" }
        } else {
            forwardTarget?.body?.trim() ?: body.trim()
        }
        if (text.isEmpty() || state.isSendingChatMessage) {
            return false
        }
        val replyInfo = if (forwardTarget == null) state.chatReplyTarget else null
        val editTarget = if (forwardTarget == null) state.chatEditTarget else null
        val envelopeId = "text:${UUID.randomUUID()}"
        val transactionId = matrixClientService.prepareTransactionId()

        _uiState.update {
            if (!it.isRouteForRoom(route.roomId)) {
                it
            } else it.copy(
                isSendingChatMessage = true,
                chatSendErrorMessage = null
            )
        }

        viewModelScope.launch {
            try {
                if (editTarget != null) {
                    val didPrepare = localCacheRepository.prepareOutgoingTextEdit(
                        userId = userId,
                        roomId = route.roomId,
                        targetMessage = MatrixChatMessage(
                            id = editTarget.messageId,
                            eventId = editTarget.eventId,
                            sender = userId,
                            body = editTarget.body,
                            timestampMillis = 0L,
                            isOwn = true
                        ),
                        body = text,
                        transactionId = transactionId
                    )
                    if (didPrepare) {
                        outgoingOutboxService.kick(reason = "new-edit")
                    }
                } else if (isForwardingMedia) {
                    val mediaForwardTarget = forwardTarget ?: return@launch
                    val items = mediaForwardTarget.imageItems
                    val groupId = "forwarded-photo-group:${UUID.randomUUID()}"
                    val shouldWriteMediaGroup = items.size > 1 ||
                        mediaForwardTarget.captionPlacement != CaptionPlacement.BOTTOM ||
                        mediaForwardTarget.layoutOverride != null
                    items.forEachIndexed { index, item ->
                        val envelopeId = "image:${UUID.randomUUID()}"
                        val transactionId = matrixClientService.prepareTransactionId()
                        val attributes = ZynaMessageAttributes(
                            forwardedFrom = mediaForwardTarget.forwardedFrom,
                            mediaGroup = if (shouldWriteMediaGroup) {
                                MediaGroupInfo(
                                    id = groupId,
                                    index = index,
                                    total = items.size,
                                    captionMode = CaptionMode.REPLICATED,
                                    captionPlacement = mediaForwardTarget.captionPlacement,
                                    layoutOverride = mediaForwardTarget.layoutOverride
                                        .takeIf { items.size > 1 }
                                )
                            } else {
                                null
                            }
                        )
                        localCacheRepository.createOutgoingForwardedImageEnvelope(
                            userId = userId,
                            roomId = route.roomId,
                            envelopeId = envelopeId,
                            transactionId = transactionId,
                            image = item,
                            caption = mediaForwardTarget.caption,
                            zynaAttributes = attributes
                        )
                    }
                    outgoingOutboxService.kick(reason = "new-forwarded-images")
                } else {
                    localCacheRepository.createOutgoingTextEnvelope(
                        userId = userId,
                        roomId = route.roomId,
                        envelopeId = envelopeId,
                        transactionId = transactionId,
                        body = text,
                        replyInfo = replyInfo,
                        forwardedFrom = forwardTarget?.forwardedFrom
                    )
                    outgoingOutboxService.kick(
                        reason = "new-envelope",
                        envelopeId = envelopeId
                    )
                }
                _uiState.update {
                    if (!it.isRouteForRoom(route.roomId)) {
                        it
                    } else it.copy(
                        isSendingChatMessage = false,
                        chatSendErrorMessage = null,
                        chatReplyTarget = null,
                        chatEditTarget = null,
                        chatForwardTarget = null
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    if (!it.isRouteForRoom(route.roomId)) {
                        it
                    } else it.copy(
                        isSendingChatMessage = false,
                        chatSendErrorMessage = error.message ?: error.javaClass.simpleName
                    )
                }
            }
        }

        return true
    }

    fun sendPhotoMessages(draft: OutgoingPhotoDraft): Boolean {
        val route = _uiState.value.route as? AppRoute.Chat ?: return false
        val userId = _uiState.value.matrixState.userIdOrNull() ?: return false
        val items = draft.items.filter { it.localPath.isNotBlank() }
        if (items.isEmpty() || _uiState.value.isSendingChatMessage) {
            return false
        }

        val groupId = "photo-group:${UUID.randomUUID()}"
        val shouldWriteMediaGroup = items.size > 1 ||
            draft.captionPlacement != CaptionPlacement.BOTTOM ||
            draft.layoutOverride != null

        _uiState.update {
            if (!it.isRouteForRoom(route.roomId)) {
                it
            } else it.copy(
                isSendingChatMessage = true,
                chatSendErrorMessage = null
            )
        }

        viewModelScope.launch {
            try {
                items.forEachIndexed { index, item ->
                    val envelopeId = "image:${UUID.randomUUID()}"
                    val transactionId = matrixClientService.prepareTransactionId()
                    val attributes = if (shouldWriteMediaGroup) {
                        ZynaMessageAttributes(
                            mediaGroup = MediaGroupInfo(
                                id = groupId,
                                index = index,
                                total = items.size,
                                captionMode = CaptionMode.REPLICATED,
                                captionPlacement = draft.captionPlacement,
                                layoutOverride = draft.layoutOverride.takeIf { items.size > 1 }
                            )
                        )
                    } else {
                        ZynaMessageAttributes()
                    }
                    localCacheRepository.createOutgoingImageEnvelope(
                        userId = userId,
                        roomId = route.roomId,
                        envelopeId = envelopeId,
                        transactionId = transactionId,
                        localPath = item.localPath,
                        mimeType = item.mimeType,
                        width = item.width,
                        height = item.height,
                        sizeBytes = item.sizeBytes,
                        thumbnailLocalPath = item.thumbnailLocalPath,
                        thumbnailMimeType = item.thumbnailMimeType,
                        thumbnailWidth = item.thumbnailWidth,
                        thumbnailHeight = item.thumbnailHeight,
                        thumbnailSizeBytes = item.thumbnailSizeBytes,
                        blurhash = item.blurhash,
                        caption = draft.caption,
                        zynaAttributes = attributes
                    )
                }
                outgoingOutboxService.kick(reason = "new-images")
                _uiState.update {
                    if (!it.isRouteForRoom(route.roomId)) {
                        it
                    } else it.copy(
                        isSendingChatMessage = false,
                        chatSendErrorMessage = null
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    if (!it.isRouteForRoom(route.roomId)) {
                        it
                    } else it.copy(
                        isSendingChatMessage = false,
                        chatSendErrorMessage = error.message ?: error.javaClass.simpleName
                    )
                }
            }
        }

        return true
    }

    fun setChatReplyTarget(replyInfo: MatrixReplyInfo) {
        val route = _uiState.value.route as? AppRoute.Chat ?: return
        if (replyInfo.eventId.isBlank()) {
            return
        }
        _uiState.update {
            if (!it.isRouteForRoom(route.roomId)) {
                it
            } else {
                it.copy(
                    chatReplyTarget = replyInfo,
                    chatEditTarget = null,
                    chatForwardTarget = null
                )
            }
        }
    }

    fun setChatEditTarget(editTarget: MatrixEditTarget) {
        val route = _uiState.value.route as? AppRoute.Chat ?: return
        if (editTarget.eventId.isBlank() || editTarget.body.isBlank()) {
            return
        }
        _uiState.update {
            if (!it.isRouteForRoom(route.roomId)) {
                it
            } else {
                it.copy(
                    chatReplyTarget = null,
                    chatEditTarget = editTarget,
                    chatForwardTarget = null
                )
            }
        }
    }

    fun startForwardMessage(target: MatrixForwardTarget) {
        if (target.body.isBlank() && target.imageItems.isEmpty()) {
            return
        }
        _uiState.update { current ->
            current.copy(
                route = AppRoute.ForwardPicker,
                pendingForwardTarget = target,
                forwardReturnRoute = current.route,
                chatReplyTarget = null,
                chatEditTarget = null,
                chatForwardTarget = null
            )
        }
    }

    fun cancelForwardPicker() {
        _uiState.update { current ->
            current.copy(
                route = current.forwardReturnRoute ?: AppRoute.Rooms,
                pendingForwardTarget = null,
                forwardReturnRoute = null
            )
        }
    }

    fun selectForwardRoom(room: MatrixRoomSummary) {
        val target = _uiState.value.pendingForwardTarget ?: return
        openRoom(room, forwardTarget = target)
    }

    fun clearChatForwardTarget() {
        _uiState.update {
            if (it.chatForwardTarget == null) {
                it
            } else {
                it.copy(chatForwardTarget = null)
            }
        }
    }

    fun clearChatReplyTarget() {
        _uiState.update {
            if (it.chatReplyTarget == null) {
                it
            } else {
                it.copy(chatReplyTarget = null)
            }
        }
    }

    fun clearChatEditTarget() {
        _uiState.update {
            if (it.chatEditTarget == null) {
                it
            } else {
                it.copy(chatEditTarget = null)
            }
        }
    }

    fun retryOutgoingEnvelope(envelopeId: String) {
        val route = _uiState.value.route as? AppRoute.Chat ?: return
        val userId = _uiState.value.matrixState.userIdOrNull() ?: return

        viewModelScope.launch {
            try {
                val didRetry = localCacheRepository.retryFailedOutgoingMessageEnvelope(
                    userId = userId,
                    roomId = route.roomId,
                    envelopeId = envelopeId
                )
                if (!didRetry) {
                    return@launch
                }
                _uiState.update {
                    if (!it.isRouteForRoom(userId, route.roomId)) {
                        it
                    } else it.copy(chatSendErrorMessage = null)
                }
                outgoingOutboxService.kick(
                    reason = "manual-retry",
                    envelopeId = envelopeId
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    if (!it.isRouteForRoom(userId, route.roomId)) {
                        it
                    } else it.copy(chatSendErrorMessage = error.message ?: error.javaClass.simpleName)
                }
            }
        }
    }

    fun discardOutgoingEnvelope(envelopeId: String) {
        val route = _uiState.value.route as? AppRoute.Chat ?: return
        val userId = _uiState.value.matrixState.userIdOrNull() ?: return

        viewModelScope.launch {
            try {
                val didDiscard = localCacheRepository.discardFailedOutgoingMessageEnvelope(
                    userId = userId,
                    roomId = route.roomId,
                    envelopeId = envelopeId
                )
                if (!didDiscard) {
                    return@launch
                }
                _uiState.update {
                    if (!it.isRouteForRoom(userId, route.roomId)) {
                        it
                    } else it.copy(chatSendErrorMessage = null)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    if (!it.isRouteForRoom(userId, route.roomId)) {
                        it
                    } else it.copy(chatSendErrorMessage = error.message ?: error.javaClass.simpleName)
                }
            }
        }
    }

    fun redactMessage(messageId: String) {
        redactMessages(listOf(messageId))
    }

    fun redactMessages(messageIds: List<String>) {
        val route = _uiState.value.route as? AppRoute.Chat ?: return
        val userId = _uiState.value.matrixState.userIdOrNull() ?: return
        val distinctMessageIds = messageIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (distinctMessageIds.isEmpty()) {
            return
        }
        val targetMessagesById = _uiState.value.chatMessages.associateBy { it.id }
        val targetMessages = distinctMessageIds
            .mapNotNull { targetMessagesById[it] }
            .filter { targetMessage ->
                targetMessage.isOwn &&
                    targetMessage.eventId != null &&
                    targetMessage.contentType != MatrixMessageContentType.REDACTED &&
                    targetMessage.deliveryState == MatrixMessageDeliveryState.SENT
            }
        if (targetMessages.isEmpty()) {
            return
        }

        viewModelScope.launch {
            try {
                val createdEnvelopeIds = mutableListOf<String>()
                for (targetMessage in targetMessages) {
                    val envelopeId = "redaction:${UUID.randomUUID()}"
                    val transactionId = matrixClientService.prepareTransactionId()
                    val didCreate = localCacheRepository.createOutgoingRedactionEnvelope(
                        userId = userId,
                        roomId = route.roomId,
                        envelopeId = envelopeId,
                        transactionId = transactionId,
                        targetMessage = targetMessage
                    )
                    if (didCreate) {
                        createdEnvelopeIds += envelopeId
                    }
                }
                if (createdEnvelopeIds.isEmpty()) {
                    return@launch
                }
                _uiState.update {
                    if (!it.isRouteForRoom(userId, route.roomId)) {
                        it
                    } else it.copy(chatSendErrorMessage = null)
                }
                outgoingOutboxService.kick(
                    reason = if (createdEnvelopeIds.size == 1) "new-redaction" else "new-redactions",
                    envelopeId = createdEnvelopeIds.singleOrNull()
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    if (!it.isRouteForRoom(userId, route.roomId)) {
                        it
                    } else it.copy(chatSendErrorMessage = error.message ?: error.javaClass.simpleName)
                }
            }
        }
    }

    fun debugMarkOutgoingEnvelopeFailed(envelopeId: String) {
        if (!BuildConfig.DEBUG) {
            return
        }
        val route = _uiState.value.route as? AppRoute.Chat ?: return
        val userId = _uiState.value.matrixState.userIdOrNull() ?: return

        viewModelScope.launch {
            try {
                localCacheRepository.debugMarkOutgoingMessageEnvelopeFailed(
                    userId = userId,
                    roomId = route.roomId,
                    envelopeId = envelopeId
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    if (!it.isRouteForRoom(userId, route.roomId)) {
                        it
                    } else it.copy(chatSendErrorMessage = error.message ?: error.javaClass.simpleName)
                }
            }
        }
    }

    fun loadOlderChatMessages() {
        val route = _uiState.value.route as? AppRoute.Chat ?: return
        val state = _uiState.value
        val userId = state.matrixState.userIdOrNull() ?: return
        if (
            state.isLoadingChat ||
            state.isLoadingOlderChatMessages ||
            !state.canLoadOlderChatMessages ||
            chatPaginationJob?.isActive == true
        ) {
            return
        }

        _uiState.update {
            if (!it.isRouteForRoom(userId, route.roomId)) {
                it
            } else it.copy(isLoadingOlderChatMessages = true)
        }

        chatPaginationJob = viewModelScope.launch {
            try {
                val timelineStore = chatTimelineWindowStore
                    ?.takeIf { it.matches(userId, route.roomId) }
                val didLoadFromCache = timelineStore?.expandOlderFromCache() == true
                if (didLoadFromCache) {
                    _uiState.update {
                        if (!it.isRouteForRoom(userId, route.roomId)) {
                            it
                        } else it.copy(
                            isLoadingOlderChatMessages = false,
                            canLoadOlderChatMessages = true,
                            canLoadNewerChatMessages = timelineStore.canLoadNewerFromCache,
                            isChatAtLiveEdge = timelineStore.isAtLiveEdge
                        )
                    }
                    return@launch
                }

                val hasReachedStart = matrixClientService.paginateRoomTimelineBackwards(route.roomId)
                val didLoadFromFreshCache =
                    timelineStore?.expandOlderFromCacheAfterMaterialization() == true
                _uiState.update {
                    if (!it.isRouteForRoom(userId, route.roomId)) {
                        it
                    } else it.copy(
                        isLoadingOlderChatMessages = false,
                        canLoadOlderChatMessages = !hasReachedStart || didLoadFromFreshCache,
                        canLoadNewerChatMessages = timelineStore?.canLoadNewerFromCache
                            ?: it.canLoadNewerChatMessages,
                        isChatAtLiveEdge = timelineStore?.isAtLiveEdge ?: it.isChatAtLiveEdge
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    if (!it.isRouteForRoom(userId, route.roomId)) {
                        it
                    } else it.copy(isLoadingOlderChatMessages = false)
                }
            }
        }
    }

    fun loadNewerChatMessages() {
        val route = _uiState.value.route as? AppRoute.Chat ?: return
        val state = _uiState.value
        val userId = state.matrixState.userIdOrNull() ?: return
        if (
            state.isLoadingChat ||
            state.isLoadingOlderChatMessages ||
            !state.canLoadNewerChatMessages ||
            chatPaginationJob?.isActive == true
        ) {
            return
        }

        _uiState.update {
            if (!it.isRouteForRoom(userId, route.roomId)) {
                it
            } else it.copy(isLoadingOlderChatMessages = true)
        }

        chatPaginationJob = viewModelScope.launch {
            try {
                val timelineStore = chatTimelineWindowStore
                    ?.takeIf { it.matches(userId, route.roomId) }
                val didLoadFromCache = timelineStore?.expandNewerFromCache() == true
                if (didLoadFromCache) {
                    _uiState.update {
                        if (!it.isRouteForRoom(userId, route.roomId)) {
                            it
                        } else it.copy(
                            isLoadingOlderChatMessages = false,
                            canLoadNewerChatMessages = timelineStore.canLoadNewerFromCache,
                            isChatAtLiveEdge = timelineStore.isAtLiveEdge
                        )
                    }
                    return@launch
                }

                val hasReachedEnd = matrixClientService.paginateRoomTimelineForwards(route.roomId)
                val didLoadFromFreshCache =
                    timelineStore?.expandNewerFromCacheAfterMaterialization() == true
                if (hasReachedEnd && !didLoadFromFreshCache) {
                    timelineStore?.markNewerFullyLoaded()
                }
                _uiState.update {
                    if (!it.isRouteForRoom(userId, route.roomId)) {
                        it
                    } else it.copy(
                        isLoadingOlderChatMessages = false,
                        canLoadNewerChatMessages = if (hasReachedEnd && !didLoadFromFreshCache) {
                            false
                        } else {
                            timelineStore?.canLoadNewerFromCache == true || !hasReachedEnd
                        },
                        isChatAtLiveEdge = timelineStore?.isAtLiveEdge ?: true
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    if (!it.isRouteForRoom(userId, route.roomId)) {
                        it
                    } else it.copy(isLoadingOlderChatMessages = false)
                }
            }
        }
    }

    fun jumpToChatEvent(eventId: String) {
        val normalizedEventId = eventId.takeIf { it.isNotBlank() } ?: return
        val route = _uiState.value.route as? AppRoute.Chat ?: return
        val state = _uiState.value
        val userId = state.matrixState.userIdOrNull() ?: return
        if (chatPaginationJob?.isActive == true) {
            logTeleport("jump cancels activePagination target=${normalizedEventId.shortLogId()}")
            chatPaginationJob?.cancel()
            chatPaginationJob = null
        }
        logTeleport(
            "jump request target=${normalizedEventId.shortLogId()} " +
                "messages=${state.chatMessages.size} canOlder=${state.canLoadOlderChatMessages} " +
                "canNewer=${state.canLoadNewerChatMessages} live=${state.isChatAtLiveEdge}"
        )

        val isTargetInCurrentWindow = state.chatMessages.any { message ->
            message.eventId == normalizedEventId || message.id == normalizedEventId
        }
        if (isTargetInCurrentWindow) {
            logTeleport("jump local target=${normalizedEventId.shortLogId()}")
            _uiState.update {
                if (!it.isRouteForRoom(userId, route.roomId)) {
                    it
                } else it.copy(
                    chatWindowChangeOrigin = TimelineWindowChangeOrigin.JUMP,
                    chatTimelineFlushSummary = null,
                    isLoadingOlderChatMessages = false,
                    chatSendErrorMessage = null,
                    chatJumpTargetEventId = normalizedEventId
                )
            }
            resetReadReceiptTracking()
            return
        }

        _uiState.update {
            if (!it.isRouteForRoom(userId, route.roomId)) {
                it
            } else it.copy(
                isLoadingOlderChatMessages = true,
                chatSendErrorMessage = null,
                chatJumpTargetEventId = normalizedEventId
            )
        }
        resetReadReceiptTracking()

        chatPaginationJob = viewModelScope.launch {
            var hasReachedStart = false
            var didJump = false
            try {
                val timelineStore = chatTimelineWindowStore
                    ?.takeIf { it.matches(userId, route.roomId) }
                if (timelineStore == null) {
                    logTeleport("jump abort noStore target=${normalizedEventId.shortLogId()}")
                    _uiState.update {
                        if (!it.isRouteForRoom(userId, route.roomId)) {
                            it
                        } else it.copy(
                            isLoadingOlderChatMessages = false,
                            chatJumpTargetEventId = if (it.chatJumpTargetEventId == normalizedEventId) {
                                null
                            } else {
                                it.chatJumpTargetEventId
                            }
                        )
                    }
                    return@launch
                }

                didJump = timelineStore.jumpToEvent(normalizedEventId)
                logTeleport(
                    "jump cache target=${normalizedEventId.shortLogId()} didJump=$didJump " +
                        "canOlder=${timelineStore.canLoadOlderFromCache} " +
                        "canNewer=${timelineStore.canLoadNewerFromCache} live=${timelineStore.isAtLiveEdge}"
                )
                var attempts = 0
                while (
                    !didJump &&
                    !hasReachedStart &&
                    attempts < JUMP_PAGINATION_ATTEMPTS
                ) {
                    attempts += 1
                    hasReachedStart = matrixClientService.paginateRoomTimelineBackwards(route.roomId)
                    didJump = timelineStore.jumpToEventAfterMaterialization(normalizedEventId)
                    logTeleport(
                        "jump attempt=$attempts target=${normalizedEventId.shortLogId()} " +
                            "reachedStart=$hasReachedStart didJump=$didJump"
                    )
                }
                logTeleport(
                    "jump final target=${normalizedEventId.shortLogId()} didJump=$didJump " +
                        "attempts=$attempts reachedStart=$hasReachedStart " +
                        "canOlder=${timelineStore.canLoadOlderFromCache} " +
                        "canNewer=${timelineStore.canLoadNewerFromCache} live=${timelineStore.isAtLiveEdge}"
                )

                _uiState.update {
                    if (!it.isRouteForRoom(userId, route.roomId)) {
                        it
                    } else it.copy(
                        isLoadingOlderChatMessages = false,
                        canLoadOlderChatMessages = when {
                            didJump -> timelineStore.canLoadOlderFromCache || !hasReachedStart
                            hasReachedStart -> false
                            else -> it.canLoadOlderChatMessages
                        },
                        canLoadNewerChatMessages = if (didJump) {
                            timelineStore.canLoadNewerFromCache
                        } else {
                            it.canLoadNewerChatMessages
                        },
                        isChatAtLiveEdge = timelineStore.isAtLiveEdge,
                        chatJumpTargetEventId = if (didJump) {
                            it.chatJumpTargetEventId
                        } else if (it.chatJumpTargetEventId == normalizedEventId) {
                            null
                        } else {
                            it.chatJumpTargetEventId
                        }
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to jump to chat event", error)
                _uiState.update {
                    if (!it.isRouteForRoom(userId, route.roomId)) {
                        it
                    } else it.copy(
                        isLoadingOlderChatMessages = false,
                        chatJumpTargetEventId = if (it.chatJumpTargetEventId == normalizedEventId) {
                            null
                        } else {
                            it.chatJumpTargetEventId
                        }
                    )
                }
            }
        }
    }

    fun clearChatJumpTarget(eventId: String) {
        _uiState.update {
            if (it.chatJumpTargetEventId == eventId) {
                it.copy(chatJumpTargetEventId = null)
            } else {
                it
            }
        }
    }

    fun jumpToChatLiveEdge() {
        val route = _uiState.value.route as? AppRoute.Chat ?: return
        val state = _uiState.value
        val userId = state.matrixState.userIdOrNull() ?: return
        if (chatPaginationJob?.isActive == true) {
            logTeleport("live cancels activePagination")
            chatPaginationJob?.cancel()
            chatPaginationJob = null
        }

        _uiState.update {
            if (!it.isRouteForRoom(userId, route.roomId)) {
                it
            } else it.copy(
                isLoadingOlderChatMessages = true,
                chatSendErrorMessage = null,
                chatJumpTargetEventId = null,
                chatScrollToLiveEdgeRequested = true
            )
        }
        resetReadReceiptTracking()

        chatPaginationJob = viewModelScope.launch {
            try {
                val timelineStore = chatTimelineWindowStore
                    ?.takeIf { it.matches(userId, route.roomId) }
                val didJump = timelineStore?.jumpToLiveEdge() == true
                logTeleport(
                    "live final didJump=$didJump " +
                        "canOlder=${timelineStore?.canLoadOlderFromCache} " +
                        "canNewer=${timelineStore?.canLoadNewerFromCache} live=${timelineStore?.isAtLiveEdge}"
                )
                _uiState.update {
                    if (!it.isRouteForRoom(userId, route.roomId)) {
                        it
                    } else it.copy(
                        isLoadingOlderChatMessages = false,
                        canLoadOlderChatMessages = timelineStore?.canLoadOlderFromCache ?: true,
                        canLoadNewerChatMessages = false,
                        isChatAtLiveEdge = true,
                        chatScrollToLiveEdgeRequested = didJump
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to jump to live edge", error)
                _uiState.update {
                    if (!it.isRouteForRoom(userId, route.roomId)) {
                        it
                    } else it.copy(
                        isLoadingOlderChatMessages = false,
                        chatScrollToLiveEdgeRequested = false
                    )
                }
            }
        }
    }

    fun clearChatScrollToLiveEdgeRequest() {
        _uiState.update {
            if (it.chatScrollToLiveEdgeRequested) {
                it.copy(chatScrollToLiveEdgeRequested = false)
            } else {
                it
            }
        }
    }

    fun updateVisibleReadReceiptCandidate(
        roomId: String,
        eventId: String?,
        canEstablishBaseline: Boolean
    ) {
        val route = _uiState.value.route as? AppRoute.Chat ?: return
        if (route.roomId != roomId) {
            return
        }
        if (eventId.isNullOrBlank()) {
            readReceiptJob?.cancel()
            readReceiptJob = null
            pendingReadReceiptSend = null
            return
        }

        val target = VisibleReadReceiptTarget(
            roomId = roomId,
            eventId = eventId
        )

        if (readReceiptBaselineTarget == null) {
            if (!canEstablishBaseline) {
                return
            }
            val pendingBootstrap = PendingReadReceiptSend.Bootstrap(target)
            if (pendingReadReceiptSend == pendingBootstrap) {
                return
            }

            scheduleReadReceiptSend(
                target = target,
                pending = pendingBootstrap
            )
            return
        }

        if (!shouldAdvanceReadReceipt(target = target)) {
            return
        }

        val pendingAdvance = PendingReadReceiptSend.Advance(target)
        if (pendingReadReceiptSend == pendingAdvance) {
            return
        }

        scheduleReadReceiptSend(
            target = target,
            pending = pendingAdvance
        )
    }

    fun logout() {
        stopChatTimeline()
        stopRoomCache()
        stopRoomListLiveRefresh()
        viewModelScope.launch {
            localCacheRepository.clearAll()
            withContext(Dispatchers.IO) {
                matrixMediaLoader.clear()
            }
            matrixClientService.logout()
        }
    }

    private fun routeForState(
        state: MatrixClientState,
        currentRoute: AppRoute
    ): AppRoute {
        return when (state) {
            MatrixClientState.LoggedOut,
            is MatrixClientState.Error -> AppRoute.Login
            is MatrixClientState.LoggedIn -> {
                val userId = state.userId

                if (!matrixClientService.isRecoveryComplete(userId)) {
                    AppRoute.RecoveryKey(userId)
                } else if (currentRoute is AppRoute.Login || currentRoute is AppRoute.RecoveryKey) {
                    AppRoute.Rooms
                } else {
                    currentRoute
                }
            }
            is MatrixClientState.Syncing -> {
                val userId = state.userId

                if (!matrixClientService.isRecoveryComplete(userId)) {
                    AppRoute.RecoveryKey(userId)
                } else if (currentRoute is AppRoute.Login || currentRoute is AppRoute.RecoveryKey) {
                    AppRoute.Rooms
                } else {
                    currentRoute
                }
            }
            MatrixClientState.LoggingIn,
            MatrixClientState.RestoringSession -> currentRoute
        }
    }

    private fun startChatTimeline(
        userId: String,
        roomId: String,
        resetMessages: Boolean,
        timelineStore: RoomTimelineWindowStore = RoomTimelineWindowStore(
            userId = userId,
            roomId = roomId,
            localCacheRepository = localCacheRepository
        )
    ) {
        chatTimelineJob?.cancel()
        chatPaginationJob?.cancel()
        chatPaginationJob = null
        chatCacheJob?.cancel()
        chatCacheJob = null
        chatTimelineWindowStore = timelineStore
        _uiState.update {
            val nextMessages = if (resetMessages) emptyList() else it.chatMessages
            it.copy(
                chatMessages = nextMessages,
                chatWindowChangeOrigin = if (resetMessages) {
                    TimelineWindowChangeOrigin.INITIAL_LOAD
                } else {
                    it.chatWindowChangeOrigin
                },
                chatTimelineFlushSummary = if (resetMessages) null else it.chatTimelineFlushSummary,
                isLoadingChat = nextMessages.isEmpty(),
                isLoadingOlderChatMessages = false,
                canLoadOlderChatMessages = true,
                canLoadNewerChatMessages = if (resetMessages) false else it.canLoadNewerChatMessages,
                isChatAtLiveEdge = if (resetMessages) true else it.isChatAtLiveEdge,
                chatErrorMessage = null,
                chatJumpTargetEventId = if (resetMessages) null else it.chatJumpTargetEventId,
                chatScrollToLiveEdgeRequested = if (resetMessages) {
                    false
                } else {
                    it.chatScrollToLiveEdgeRequested
                }
            )
        }
        chatCacheJob = viewModelScope.launch {
            timelineStore.messages.collect { update ->
                _uiState.update {
                    if (!it.isRouteForRoom(userId, roomId)) {
                        it
                    } else it.copy(
                        chatMessages = update.messages,
                        chatWindowChangeOrigin = update.origin,
                        chatTimelineFlushSummary = update.flushSummary,
                        isLoadingChat = if (update.messages.isNotEmpty()) false else it.isLoadingChat,
                        canLoadNewerChatMessages = update.hasNewerInDb,
                        isChatAtLiveEdge = timelineStore.isAtLiveEdge
                    )
                }
            }
        }
        chatTimelineJob = viewModelScope.launch {
            try {
                matrixClientService.roomTimelineMessageUpserts(roomId).collect { timelineUpdate ->
                    val messages = timelineUpdate.messages
                    if (messages.isNotEmpty()) {
                        timelineStore.recordTimelineFlush(timelineUpdate.flushSummary)
                        localCacheRepository.cacheRoomTimelineMessages(userId, roomId, messages)
                        timelineStore.refreshInitialWindowFromCacheIfNeeded(
                            timelineUpdate.flushSummary
                        )
                    }
                    _uiState.update {
                        if (!it.isRouteForRoom(userId, roomId)) {
                            it
                        } else it.copy(
                            isLoadingChat = false,
                            isLoadingOlderChatMessages = false,
                            chatErrorMessage = null
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    if (!it.isRouteForRoom(userId, roomId)) {
                        it
                    } else it.copy(
                        isLoadingChat = false,
                        isLoadingOlderChatMessages = false,
                        chatErrorMessage = error.message ?: error.javaClass.simpleName
                    )
                }
            }
        }
    }

    private fun stopChatTimeline() {
        openRoomJob?.cancel()
        openRoomJob = null
        chatTimelineJob?.cancel()
        chatTimelineJob = null
        chatCacheJob?.cancel()
        chatCacheJob = null
        chatTimelineWindowStore = null
        chatPaginationJob?.cancel()
        chatPaginationJob = null
        resetReadReceiptTracking()
    }

    private fun scheduleReadReceiptSend(
        target: VisibleReadReceiptTarget,
        pending: PendingReadReceiptSend
    ) {
        readReceiptJob?.cancel()
        pendingReadReceiptSend = pending
        readReceiptJob = viewModelScope.launch {
            delay(READ_RECEIPT_SEND_DELAY_MS)

            val didSend = try {
                matrixClientService.sendReadReceipt(
                    roomId = target.roomId,
                    eventId = target.eventId
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to send read receipt", error)
                false
            }

            finishReadReceiptSend(pending, didSend = didSend)
        }
    }

    private fun finishReadReceiptSend(
        pending: PendingReadReceiptSend,
        didSend: Boolean
    ) {
        if (pendingReadReceiptSend == pending) {
            pendingReadReceiptSend = null
        }

        if (!didSend) {
            return
        }

        when (pending) {
            is PendingReadReceiptSend.Bootstrap -> {
                establishReadReceiptBaseline(target = pending.target)
            }
            is PendingReadReceiptSend.Advance -> {
                establishReadReceiptBaseline(target = pending.target)
            }
        }
    }

    private fun shouldAdvanceReadReceipt(target: VisibleReadReceiptTarget): Boolean {
        val baselineTarget = readReceiptBaselineTarget
        if (baselineTarget != null && !isReadReceiptTargetNewer(target, reference = baselineTarget)) {
            return false
        }

        val pendingTarget = pendingReadReceiptSend?.target
        if (pendingTarget != null && !isReadReceiptTargetNewer(target, reference = pendingTarget)) {
            return false
        }

        return true
    }

    private fun isReadReceiptTargetNewer(
        target: VisibleReadReceiptTarget,
        reference: VisibleReadReceiptTarget
    ): Boolean {
        if (target.roomId != reference.roomId || target.eventId == reference.eventId) {
            return false
        }

        val targetIndex = messageIndex(eventId = target.eventId)
        val referenceIndex = messageIndex(eventId = reference.eventId)

        return when {
            targetIndex != null && referenceIndex != null -> targetIndex > referenceIndex
            targetIndex != null && referenceIndex == null -> true
            else -> false
        }
    }

    private fun messageIndex(eventId: String): Int? {
        return _uiState.value.chatMessages.indexOfFirst { it.eventId == eventId }
            .takeIf { it >= 0 }
    }

    private fun establishReadReceiptBaseline(target: VisibleReadReceiptTarget) {
        val currentBaseline = readReceiptBaselineTarget
        if (currentBaseline != null && !isReadReceiptTargetNewer(target, reference = currentBaseline)) {
            return
        }

        readReceiptBaselineTarget = target

        val pendingTarget = pendingReadReceiptSend?.target
        if (pendingTarget != null && !isReadReceiptTargetNewer(pendingTarget, reference = target)) {
            readReceiptJob?.cancel()
            readReceiptJob = null
            pendingReadReceiptSend = null
        }
    }

    private fun resetReadReceiptTracking() {
        readReceiptJob?.cancel()
        readReceiptJob = null
        readReceiptBaselineTarget = null
        pendingReadReceiptSend = null
    }

    private fun startRoomCache(userId: String) {
        if (roomCacheUserId == userId && roomCacheJob?.isActive == true) {
            return
        }

        roomCacheJob?.cancel()
        roomCacheUserId = userId
        roomCacheJob = viewModelScope.launch {
            localCacheRepository.observeRooms(userId).collect { rooms ->
                _uiState.update {
                    if (it.matrixState.userIdOrNull() == userId) {
                        it.copy(rooms = rooms)
                    } else {
                        it
                    }
                }
            }
        }
    }

    private fun stopRoomCache() {
        roomCacheJob?.cancel()
        roomCacheJob = null
        roomCacheUserId = null
    }

    private fun startRoomListLiveRefresh(userId: String) {
        if (roomListLiveUserId == userId && roomListLiveJob?.isActive == true) {
            return
        }

        roomListLiveJob?.cancel()
        roomListLiveUserId = userId
        roomListLiveJob = viewModelScope.launch {
            try {
                matrixClientService.roomListChangeSignals().collectLatest {
                    if (matrixClientService.isRecoveryComplete(userId)) {
                        refreshRoomsNow()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to observe room list state", error)
            }
        }
    }

    private fun stopRoomListLiveRefresh() {
        roomListLiveJob?.cancel()
        roomListLiveJob = null
        roomListLiveUserId = null
    }

    private fun AppUiState.isRouteForRoom(roomId: String): Boolean {
        return (route as? AppRoute.Chat)?.roomId == roomId
    }

    private fun AppUiState.isRouteForRoom(userId: String, roomId: String): Boolean {
        return matrixState.userIdOrNull() == userId && isRouteForRoom(roomId)
    }

    private fun MatrixClientState.userIdOrNull(): String? {
        return when (this) {
            is MatrixClientState.LoggedIn -> userId
            is MatrixClientState.Syncing -> userId
            MatrixClientState.LoggedOut,
            is MatrixClientState.Error,
            MatrixClientState.LoggingIn,
            MatrixClientState.RestoringSession -> null
        }
    }

    private fun logTeleport(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TELEPORT_LOG_TAG, message)
        }
    }

    private companion object {
        const val TAG = "AppViewModel"
        const val TELEPORT_LOG_TAG = "ZynaChatTeleport"
        const val READ_RECEIPT_SEND_DELAY_MS = 250L
        const val JUMP_PAGINATION_ATTEMPTS = 8
    }
}

private fun String.shortLogId(): String {
    return takeLast(10)
}

class AppViewModelFactory(
    private val matrixClientService: MatrixClientService,
    private val localCacheRepository: LocalCacheRepository,
    private val outgoingOutboxService: OutgoingOutboxService,
    private val matrixMediaLoader: MatrixMediaLoader
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        if (modelClass.isAssignableFrom(AppViewModel::class.java)) {
            return AppViewModel(
                matrixClientService = matrixClientService,
                localCacheRepository = localCacheRepository,
                outgoingOutboxService = outgoingOutboxService,
                matrixMediaLoader = matrixMediaLoader
            ) as T
        }
        error("Unknown ViewModel class: ${modelClass.name}")
    }
}
