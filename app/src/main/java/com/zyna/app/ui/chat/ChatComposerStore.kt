package com.zyna.app.ui.chat

import androidx.annotation.MainThread
import com.zyna.app.data.local.LocalCacheRepository
import com.zyna.app.data.matrix.MatrixChatMessage
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixEditTarget
import com.zyna.app.data.matrix.MatrixForwardImageItem
import com.zyna.app.data.matrix.MatrixForwardTarget
import com.zyna.app.data.matrix.MatrixReplyInfo
import com.zyna.app.data.messaging.CaptionMode
import com.zyna.app.data.messaging.CaptionPlacement
import com.zyna.app.data.messaging.MediaGroupInfo
import com.zyna.app.data.messaging.MediaGroupLayoutOverride
import com.zyna.app.data.messaging.ZynaMessageAttributes
import com.zyna.app.data.outgoing.OutgoingOutboxFailure
import com.zyna.app.data.outgoing.OutgoingOutboxService
import com.zyna.app.data.outgoing.OutgoingPhotoDraft
import com.zyna.app.data.outgoing.OutgoingPhotoDraftItem
import com.zyna.app.data.outgoing.OutgoingVoiceDraft
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

data class ChatComposerState(
    val roomId: String? = null,
    val replyTarget: MatrixReplyInfo? = null,
    val editTarget: MatrixEditTarget? = null,
    val forwardTarget: MatrixForwardTarget? = null,
    val pendingForwardTarget: MatrixForwardTarget? = null,
    val isSending: Boolean = false,
    val errorMessage: String? = null
)

internal data class ChatComposerSendTarget(
    val userId: String,
    val roomId: String
)

internal sealed interface ChatComposerSendRequest {
    val target: ChatComposerSendTarget

    data class Text(
        override val target: ChatComposerSendTarget,
        val envelopeId: String,
        val transactionId: String,
        val body: String,
        val replyInfo: MatrixReplyInfo?,
        val forwardedFrom: String?
    ) : ChatComposerSendRequest

    data class Edit(
        override val target: ChatComposerSendTarget,
        val transactionId: String,
        val editTarget: MatrixEditTarget,
        val body: String
    ) : ChatComposerSendRequest

    data class ForwardImages(
        override val target: ChatComposerSendTarget,
        val forwardTarget: MatrixForwardTarget
    ) : ChatComposerSendRequest

    data class Photos(
        override val target: ChatComposerSendTarget,
        val groupId: String,
        val draft: OutgoingPhotoDraft
    ) : ChatComposerSendRequest

    data class Voice(
        override val target: ChatComposerSendTarget,
        val draft: OutgoingVoiceDraft,
        val replyInfo: MatrixReplyInfo?
    ) : ChatComposerSendRequest
}

internal class ChatComposerSendDriver(
    val nextId: () -> String,
    val prepareTransactionId: () -> String,
    val prepareTextEdit: suspend (
        target: ChatComposerSendTarget,
        editTarget: MatrixEditTarget,
        body: String,
        transactionId: String
    ) -> Boolean,
    val createTextEnvelope: suspend (
        target: ChatComposerSendTarget,
        envelopeId: String,
        transactionId: String,
        body: String,
        replyInfo: MatrixReplyInfo?,
        forwardedFrom: String?
    ) -> Unit,
    val createForwardedImageEnvelope: suspend (
        target: ChatComposerSendTarget,
        envelopeId: String,
        transactionId: String,
        image: MatrixForwardImageItem,
        caption: String?,
        attributes: ZynaMessageAttributes
    ) -> Unit,
    val createImageEnvelope: suspend (
        target: ChatComposerSendTarget,
        envelopeId: String,
        transactionId: String,
        item: OutgoingPhotoDraftItem,
        caption: String?,
        attributes: ZynaMessageAttributes
    ) -> Unit,
    val createVoiceEnvelope: suspend (
        target: ChatComposerSendTarget,
        envelopeId: String,
        transactionId: String,
        draft: OutgoingVoiceDraft,
        replyInfo: MatrixReplyInfo?
    ) -> Unit,
    val kickOutbox: (reason: String, envelopeId: String?) -> Unit
)

/**
 * Owns target selection and durable enqueue rules for the chat composer.
 *
 * Target selection and request creation are main-thread confined. [state] is
 * the single source of truth rendered by the active chat. [scope] must dispatch
 * onto the same main thread from which public methods are called. Durable
 * enqueue survives room changes, while its UI completion is generation-guarded.
 */
internal class ChatComposerStore(
    private val scope: CoroutineScope,
    private val sendDriver: ChatComposerSendDriver,
    sendFailures: Flow<OutgoingOutboxFailure> = emptyFlow(),
    initialState: ChatComposerState = ChatComposerState()
) {
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<ChatComposerState> = _state.asStateFlow()

    private val currentState: ChatComposerState
        get() = _state.value

    private var activeTarget: ChatComposerSendTarget? = null
    private var roomGeneration = 0L

    init {
        scope.launch {
            sendFailures.collect { failure ->
                val target = activeTarget
                if (target?.roomId == failure.roomId) {
                    setState(currentState.copy(errorMessage = failure.message))
                }
            }
        }
    }

    @MainThread
    fun selectReply(target: MatrixReplyInfo): ChatComposerState? {
        if (target.eventId.isBlank()) {
            return null
        }
        return currentState.copy(
            replyTarget = target,
            editTarget = null,
            forwardTarget = null
        ).also(::setState)
    }

    @MainThread
    fun selectEdit(target: MatrixEditTarget): ChatComposerState? {
        if (target.eventId.isBlank() || target.body.isBlank()) {
            return null
        }
        return currentState.copy(
            replyTarget = null,
            editTarget = target,
            forwardTarget = null
        ).also(::setState)
    }

    @MainThread
    fun startForwardPicker(target: MatrixForwardTarget): ChatComposerState? {
        if (target.body.isBlank() && target.imageItems.isEmpty()) {
            return null
        }
        return currentState.copy(
            replyTarget = null,
            editTarget = null,
            forwardTarget = null,
            pendingForwardTarget = target
        ).also(::setState)
    }

    @MainThread
    fun createSendRequest(
        target: ChatComposerSendTarget,
        body: String
    ): ChatComposerSendRequest? {
        val forwardTarget = currentState.forwardTarget
        val isForwardingMedia = forwardTarget?.imageItems?.isNotEmpty() == true
        val text = if (isForwardingMedia) {
            forwardTarget?.body?.trim().orEmpty().ifBlank { "Photo" }
        } else {
            forwardTarget?.body?.trim() ?: body.trim()
        }
        if (text.isEmpty() || currentState.isSending) {
            return null
        }

        val envelopeId = "text:${sendDriver.nextId()}"
        val transactionId = sendDriver.prepareTransactionId()
        val editTarget = if (forwardTarget == null) currentState.editTarget else null

        return when {
            editTarget != null -> ChatComposerSendRequest.Edit(
                target = target,
                transactionId = transactionId,
                editTarget = editTarget,
                body = text
            )
            isForwardingMedia -> ChatComposerSendRequest.ForwardImages(
                target = target,
                forwardTarget = requireNotNull(forwardTarget)
            )
            else -> ChatComposerSendRequest.Text(
                target = target,
                envelopeId = envelopeId,
                transactionId = transactionId,
                body = text,
                replyInfo = if (forwardTarget == null) currentState.replyTarget else null,
                forwardedFrom = forwardTarget?.forwardedFrom
            )
        }
    }

    @MainThread
    fun createPhotoSendRequest(
        target: ChatComposerSendTarget,
        draft: OutgoingPhotoDraft
    ): ChatComposerSendRequest.Photos? {
        val items = draft.items.filter { it.localPath.isNotBlank() }
        if (items.isEmpty() || currentState.isSending) {
            return null
        }
        return ChatComposerSendRequest.Photos(
            target = target,
            groupId = "photo-group:${sendDriver.nextId()}",
            draft = draft.copy(items = items)
        )
    }

    @MainThread
    fun createVoiceSendRequest(
        target: ChatComposerSendTarget,
        draft: OutgoingVoiceDraft
    ): ChatComposerSendRequest.Voice? {
        if (draft.localPath.isBlank() || currentState.isSending) {
            return null
        }
        return ChatComposerSendRequest.Voice(
            target = target,
            draft = draft,
            replyInfo = currentState.replyTarget
        )
    }

    suspend fun enqueue(request: ChatComposerSendRequest) {
        when (request) {
            is ChatComposerSendRequest.Text -> enqueueText(request)
            is ChatComposerSendRequest.Edit -> enqueueEdit(request)
            is ChatComposerSendRequest.ForwardImages -> enqueueForwardedImages(request)
            is ChatComposerSendRequest.Photos -> enqueuePhotos(request)
            is ChatComposerSendRequest.Voice -> enqueueVoice(request)
        }
    }

    @MainThread
    fun sendText(target: ChatComposerSendTarget, body: String): Boolean {
        if (activeTarget != target) {
            return false
        }
        val request = createSendRequest(target, body) ?: return false
        return launchSend(
            request = request,
            clearActiveTargetsOnSuccess = true
        )
    }

    @MainThread
    fun sendPhotos(
        target: ChatComposerSendTarget,
        draft: OutgoingPhotoDraft
    ): Boolean {
        if (activeTarget != target) {
            return false
        }
        val request = createPhotoSendRequest(target, draft) ?: return false
        return launchSend(
            request = request,
            clearActiveTargetsOnSuccess = false
        )
    }

    @MainThread
    fun sendVoice(
        target: ChatComposerSendTarget,
        draft: OutgoingVoiceDraft,
        onEnqueued: () -> Unit = {}
    ): Boolean {
        if (activeTarget != target) {
            return false
        }
        val request = createVoiceSendRequest(target, draft) ?: return false
        return launchSend(
            request = request,
            clearActiveTargetsOnSuccess = true,
            onEnqueued = onEnqueued
        )
    }

    @MainThread
    fun cancelForwardPicker(): ChatComposerState {
        return currentState.copy(pendingForwardTarget = null).also(::setState)
    }

    @MainThread
    fun enterRoom(
        target: ChatComposerSendTarget,
        forwardTarget: MatrixForwardTarget?
    ): ChatComposerState {
        roomGeneration += 1
        activeTarget = target
        return ChatComposerState(
            roomId = target.roomId,
            forwardTarget = forwardTarget
        ).also(::setState)
    }

    @MainThread
    fun clearReply(): ChatComposerState {
        return currentState.copy(replyTarget = null).also(::setState)
    }

    @MainThread
    fun clearEdit(): ChatComposerState {
        return currentState.copy(editTarget = null).also(::setState)
    }

    @MainThread
    fun clearForward(): ChatComposerState {
        return currentState.copy(forwardTarget = null).also(::setState)
    }

    @MainThread
    fun clearActiveTargets(): ChatComposerState {
        return currentState.copy(
            replyTarget = null,
            editTarget = null,
            forwardTarget = null
        ).also(::setState)
    }

    @MainThread
    fun deactivateRoom(): ChatComposerState {
        roomGeneration += 1
        activeTarget = null
        return ChatComposerState(
            pendingForwardTarget = currentState.pendingForwardTarget
        ).also(::setState)
    }

    @MainThread
    fun clearAll(): ChatComposerState {
        roomGeneration += 1
        activeTarget = null
        return ChatComposerState().also(::setState)
    }

    @MainThread
    fun clearError(target: ChatComposerSendTarget) {
        if (activeTarget == target && currentState.errorMessage != null) {
            setState(currentState.copy(errorMessage = null))
        }
    }

    @MainThread
    fun reportError(target: ChatComposerSendTarget, message: String) {
        if (activeTarget == target) {
            setState(currentState.copy(errorMessage = message))
        }
    }

    private fun launchSend(
        request: ChatComposerSendRequest,
        clearActiveTargetsOnSuccess: Boolean,
        onEnqueued: () -> Unit = {}
    ): Boolean {
        val target = request.target
        if (activeTarget != target || currentState.isSending) {
            return false
        }
        val generation = roomGeneration
        setState(
            currentState.copy(
                isSending = true,
                errorMessage = null
            )
        )
        scope.launch {
            try {
                enqueue(request)
                onEnqueued()
                updateActiveRoom(target, generation) { state ->
                    state.copy(
                        replyTarget = if (clearActiveTargetsOnSuccess) {
                            null
                        } else {
                            state.replyTarget
                        },
                        editTarget = if (clearActiveTargetsOnSuccess) {
                            null
                        } else {
                            state.editTarget
                        },
                        forwardTarget = if (clearActiveTargetsOnSuccess) {
                            null
                        } else {
                            state.forwardTarget
                        },
                        isSending = false,
                        errorMessage = null
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                updateActiveRoom(target, generation) { state ->
                    state.copy(
                        isSending = false,
                        errorMessage = error.message ?: error.javaClass.simpleName
                    )
                }
            }
        }
        return true
    }

    private inline fun updateActiveRoom(
        target: ChatComposerSendTarget,
        generation: Long,
        transform: (ChatComposerState) -> ChatComposerState
    ) {
        if (activeTarget == target && roomGeneration == generation) {
            setState(transform(currentState))
        }
    }

    private suspend fun enqueueText(request: ChatComposerSendRequest.Text) {
        sendDriver.createTextEnvelope(
            request.target,
            request.envelopeId,
            request.transactionId,
            request.body,
            request.replyInfo,
            request.forwardedFrom
        )
        sendDriver.kickOutbox("new-envelope", request.envelopeId)
    }

    private suspend fun enqueueEdit(request: ChatComposerSendRequest.Edit) {
        val didPrepare = sendDriver.prepareTextEdit(
            request.target,
            request.editTarget,
            request.body,
            request.transactionId
        )
        if (didPrepare) {
            sendDriver.kickOutbox("new-edit", null)
        }
    }

    private suspend fun enqueueForwardedImages(
        request: ChatComposerSendRequest.ForwardImages
    ) {
        val forwardTarget = request.forwardTarget
        val items = forwardTarget.imageItems
        val groupId = "forwarded-photo-group:${sendDriver.nextId()}"

        items.forEachIndexed { index, item ->
            val envelopeId = "image:${sendDriver.nextId()}"
            val transactionId = sendDriver.prepareTransactionId()
            val attributes = createImageAttributes(
                forwardedFrom = forwardTarget.forwardedFrom,
                groupId = groupId,
                index = index,
                total = items.size,
                captionPlacement = forwardTarget.captionPlacement,
                layoutOverride = forwardTarget.layoutOverride
            )
            sendDriver.createForwardedImageEnvelope(
                request.target,
                envelopeId,
                transactionId,
                item,
                forwardTarget.caption,
                attributes
            )
        }
        sendDriver.kickOutbox("new-forwarded-images", null)
    }

    private suspend fun enqueuePhotos(request: ChatComposerSendRequest.Photos) {
        val draft = request.draft
        val items = draft.items

        items.forEachIndexed { index, item ->
            val envelopeId = "image:${sendDriver.nextId()}"
            val transactionId = sendDriver.prepareTransactionId()
            val attributes = createImageAttributes(
                forwardedFrom = null,
                groupId = request.groupId,
                index = index,
                total = items.size,
                captionPlacement = draft.captionPlacement,
                layoutOverride = draft.layoutOverride
            )
            sendDriver.createImageEnvelope(
                request.target,
                envelopeId,
                transactionId,
                item,
                draft.caption,
                attributes
            )
        }
        sendDriver.kickOutbox("new-images", null)
    }

    private suspend fun enqueueVoice(request: ChatComposerSendRequest.Voice) {
        val envelopeId = "voice:${sendDriver.nextId()}"
        val transactionId = sendDriver.prepareTransactionId()
        sendDriver.createVoiceEnvelope(
            request.target,
            envelopeId,
            transactionId,
            request.draft,
            request.replyInfo
        )
        sendDriver.kickOutbox("new-voice", envelopeId)
    }

    private fun createImageAttributes(
        forwardedFrom: String?,
        groupId: String,
        index: Int,
        total: Int,
        captionPlacement: CaptionPlacement,
        layoutOverride: MediaGroupLayoutOverride?
    ): ZynaMessageAttributes {
        val shouldWriteMediaGroup = total > 1 ||
            captionPlacement != CaptionPlacement.BOTTOM ||
            layoutOverride != null
        return ZynaMessageAttributes(
            forwardedFrom = forwardedFrom,
            mediaGroup = if (shouldWriteMediaGroup) {
                MediaGroupInfo(
                    id = groupId,
                    index = index,
                    total = total,
                    captionMode = CaptionMode.REPLICATED,
                    captionPlacement = captionPlacement,
                    layoutOverride = layoutOverride.takeIf { total > 1 }
                )
            } else {
                null
            }
        )
    }

    private fun setState(nextState: ChatComposerState) {
        _state.value = nextState
    }
}

internal fun createChatComposerStore(
    scope: CoroutineScope,
    matrixClientService: MatrixClientService,
    localCacheRepository: LocalCacheRepository,
    outgoingOutboxService: OutgoingOutboxService,
    nextId: () -> String = { UUID.randomUUID().toString() }
): ChatComposerStore {
    return ChatComposerStore(
        scope = scope,
        sendDriver = ChatComposerSendDriver(
            nextId = nextId,
            prepareTransactionId = matrixClientService::prepareTransactionId,
            prepareTextEdit = { target, editTarget, body, transactionId ->
                localCacheRepository.prepareOutgoingTextEdit(
                    userId = target.userId,
                    roomId = target.roomId,
                    targetMessage = MatrixChatMessage(
                        id = editTarget.messageId,
                        eventId = editTarget.eventId,
                        sender = target.userId,
                        body = editTarget.body,
                        timestampMillis = 0L,
                        isOwn = true
                    ),
                    body = body,
                    transactionId = transactionId
                )
            },
            createTextEnvelope = { target, envelopeId, transactionId, body,
                replyInfo, forwardedFrom ->
                localCacheRepository.createOutgoingTextEnvelope(
                    userId = target.userId,
                    roomId = target.roomId,
                    envelopeId = envelopeId,
                    transactionId = transactionId,
                    body = body,
                    replyInfo = replyInfo,
                    forwardedFrom = forwardedFrom
                )
            },
            createForwardedImageEnvelope = { target, envelopeId, transactionId, image,
                caption, attributes ->
                localCacheRepository.createOutgoingForwardedImageEnvelope(
                    userId = target.userId,
                    roomId = target.roomId,
                    envelopeId = envelopeId,
                    transactionId = transactionId,
                    image = image,
                    caption = caption,
                    zynaAttributes = attributes
                )
            },
            createImageEnvelope = { target, envelopeId, transactionId, item,
                caption, attributes ->
                localCacheRepository.createOutgoingImageEnvelope(
                    userId = target.userId,
                    roomId = target.roomId,
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
                    caption = caption,
                    zynaAttributes = attributes
                )
            },
            createVoiceEnvelope = { target, envelopeId, transactionId, draft,
                replyInfo ->
                localCacheRepository.createOutgoingVoiceEnvelope(
                    userId = target.userId,
                    roomId = target.roomId,
                    envelopeId = envelopeId,
                    transactionId = transactionId,
                    localPath = draft.localPath,
                    mimeType = draft.mimeType,
                    sizeBytes = draft.sizeBytes,
                    durationMillis = draft.durationMillis,
                    waveform = draft.waveform,
                    replyInfo = replyInfo
                )
            },
            kickOutbox = outgoingOutboxService::kick
        ),
        sendFailures = outgoingOutboxService.sendFailures
    )
}
