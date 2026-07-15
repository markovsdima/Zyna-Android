package com.zyna.app.ui.chat

import com.zyna.app.data.local.LocalCacheRepository
import com.zyna.app.data.matrix.MatrixChatMessage
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixMessageContentType
import com.zyna.app.data.outgoing.OutgoingOutboxService

internal data class ChatMessageActionTarget(
    val userId: String,
    val roomId: String
)

internal sealed interface ChatMessageActionRequest {
    val target: ChatMessageActionTarget

    data class AddReaction(
        override val target: ChatMessageActionTarget,
        val targetEventId: String,
        val reactionKey: String
    ) : ChatMessageActionRequest

    data class RemoveReaction(
        override val target: ChatMessageActionTarget,
        val targetEventId: String,
        val reactionKey: String
    ) : ChatMessageActionRequest
}

internal class ChatMessageActionDriver(
    val prepareTransactionId: () -> String,
    val prepareReactionAdd: suspend (
        target: ChatMessageActionTarget,
        targetEventId: String,
        reactionKey: String,
        transactionId: String
    ) -> String?,
    val prepareReactionRemoval: suspend (
        target: ChatMessageActionTarget,
        targetEventId: String,
        reactionKey: String,
        reactionEventId: String?,
        transactionId: String
    ) -> String?,
    val findOwnReactionEventId: suspend (
        target: ChatMessageActionTarget,
        targetEventId: String,
        reactionKey: String
    ) -> String?,
    val kickOutbox: (reason: String, envelopeId: String?) -> Unit
)

/**
 * Owns validation and durable enqueue rules for actions on chat messages.
 *
 * Request creation is side-effect free. Execution writes an action to the
 * local outbox before asking the delivery coordinator to dispatch it.
 */
internal class ChatMessageActionStore(
    private val driver: ChatMessageActionDriver
) {
    fun createReactionRequest(
        target: ChatMessageActionTarget,
        message: MatrixChatMessage,
        reactionKey: String
    ): ChatMessageActionRequest? {
        val key = reactionKey.takeIf { it.isNotBlank() } ?: return null
        val targetEventId = message.eventId?.takeIf { it.isNotBlank() } ?: return null
        if (
            message.contentType == MatrixMessageContentType.REDACTED ||
            message.outgoingEnvelopeId != null
        ) {
            return null
        }

        val ownReaction = message.reactions.firstOrNull {
            it.key == key && it.isOwn
        }
        return if (ownReaction != null && !ownReaction.isPendingRemoval) {
            ChatMessageActionRequest.RemoveReaction(target, targetEventId, key)
        } else {
            ChatMessageActionRequest.AddReaction(target, targetEventId, key)
        }
    }

    suspend fun execute(request: ChatMessageActionRequest) {
        when (request) {
            is ChatMessageActionRequest.AddReaction -> addReaction(request)
            is ChatMessageActionRequest.RemoveReaction -> removeReaction(request)
        }
    }

    private suspend fun addReaction(request: ChatMessageActionRequest.AddReaction) {
        val transactionId = driver.prepareTransactionId()
        val reactionId = driver.prepareReactionAdd(
            request.target,
            request.targetEventId,
            request.reactionKey,
            transactionId
        )
        if (reactionId != null) {
            driver.kickOutbox("new-reaction", reactionId)
        }
    }

    private suspend fun removeReaction(request: ChatMessageActionRequest.RemoveReaction) {
        val transactionId = driver.prepareTransactionId()
        var reactionId = driver.prepareReactionRemoval(
            request.target,
            request.targetEventId,
            request.reactionKey,
            null,
            transactionId
        )
        if (reactionId == null) {
            val reactionEventId = driver.findOwnReactionEventId(
                request.target,
                request.targetEventId,
                request.reactionKey
            )
            if (reactionEventId != null) {
                reactionId = driver.prepareReactionRemoval(
                    request.target,
                    request.targetEventId,
                    request.reactionKey,
                    reactionEventId,
                    transactionId
                )
            }
        }
        if (reactionId != null) {
            driver.kickOutbox("new-reaction-removal", reactionId)
        }
    }
}

internal fun createChatMessageActionStore(
    matrixClientService: MatrixClientService,
    localCacheRepository: LocalCacheRepository,
    outgoingOutboxService: OutgoingOutboxService
): ChatMessageActionStore {
    return ChatMessageActionStore(
        driver = ChatMessageActionDriver(
            prepareTransactionId = matrixClientService::prepareTransactionId,
            prepareReactionAdd = { target, targetEventId, reactionKey, transactionId ->
                localCacheRepository.prepareOutgoingReactionAdd(
                    userId = target.userId,
                    roomId = target.roomId,
                    targetEventId = targetEventId,
                    reactionKey = reactionKey,
                    transactionId = transactionId
                )
            },
            prepareReactionRemoval = {
                    target, targetEventId, reactionKey, reactionEventId, transactionId ->
                localCacheRepository.prepareOutgoingReactionRemoval(
                    userId = target.userId,
                    roomId = target.roomId,
                    targetEventId = targetEventId,
                    reactionKey = reactionKey,
                    reactionEventId = reactionEventId,
                    transactionId = transactionId
                )
            },
            findOwnReactionEventId = { target, targetEventId, reactionKey ->
                matrixClientService.findOwnReactionEventId(
                    roomId = target.roomId,
                    targetEventId = targetEventId,
                    reactionKey = reactionKey,
                    userId = target.userId
                )
            },
            kickOutbox = outgoingOutboxService::kick
        )
    )
}
