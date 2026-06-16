package com.zyna.app.data.local

import androidx.room.withTransaction
import com.zyna.app.data.matrix.MatrixChatMessage
import com.zyna.app.data.matrix.MatrixLastOwnMessageStatus
import com.zyna.app.data.matrix.MatrixMessageContentType
import com.zyna.app.data.matrix.MatrixMessageDeliveryState
import com.zyna.app.data.matrix.MatrixRoomSummary
import com.zyna.app.data.outgoing.OutgoingEnvelopeKind
import com.zyna.app.data.outgoing.OutgoingTextEnvelope
import com.zyna.app.data.outgoing.OutgoingTransportState
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class LocalCacheRepository(
    private val database: ZynaDatabase
) {
    private val roomDao = database.cachedRoomDao()
    private val messageDao = database.cachedTimelineMessageDao()
    private val outgoingDao = database.outgoingEnvelopeDao()

    fun observeRooms(userId: String): Flow<List<MatrixRoomSummary>> {
        return roomDao.observeRooms(userId).map { rooms ->
            rooms.map { it.toRoomSummary() }
                .sortedWith(RoomSummaryComparator)
        }
    }

    suspend fun cacheRoomsSnapshot(userId: String, rooms: List<MatrixRoomSummary>) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            val existingRoomsById = roomDao.roomsSnapshot(userId).associateBy { it.id }
            roomDao.clearRooms(userId)
            roomDao.upsertRooms(
                rooms.map { room ->
                    val existingRoom = existingRoomsById[room.id]
                    CachedRoomEntity(
                        userId = userId,
                        id = room.id,
                        displayName = room.displayName,
                        avatarUrl = room.avatarUrl,
                        lastMessageText = room.lastMessageText ?: existingRoom?.lastMessageText,
                        lastMessageSenderName = room.lastMessageSenderName
                            ?: existingRoom?.lastMessageSenderName,
                        lastMessageAtMillis = room.lastMessageAtMillis
                            ?: existingRoom?.lastMessageAtMillis,
                        lastOwnMessageStatus = if (room.lastMessageAtMillis == null) {
                            existingRoom?.lastOwnMessageStatus
                        } else {
                            room.lastOwnMessageStatus?.name
                        },
                        unreadCount = room.unreadCount,
                        unreadMentionCount = room.unreadMentionCount,
                        isMarkedUnread = room.isMarkedUnread,
                        updatedAtMillis = now
                    )
                }
            )
        }
    }

    fun observeRoomTimeline(userId: String, roomId: String): Flow<List<MatrixChatMessage>> {
        return combine(
            messageDao.observeRoomMessages(userId, roomId),
            outgoingDao.observeActiveRoomEnvelopes(userId, roomId)
        ) { messages, outgoingEnvelopes ->
            mergeTimelineWithOutgoing(messages, outgoingEnvelopes)
        }
    }

    suspend fun cacheRoomTimelineMessages(
        userId: String,
        roomId: String,
        messages: List<MatrixChatMessage>
    ) {
        val now = System.currentTimeMillis()
        if (messages.isEmpty()) {
            return
        }

        database.withTransaction {
            messageDao.upsertMessages(messages.toEntities(userId, roomId, now))
            retireOutgoingEnvelopesDeliveredBy(messages, userId, roomId, now)
            updateRoomPreview(userId, roomId, now)
        }
    }

    suspend fun createOutgoingTextEnvelope(
        userId: String,
        roomId: String,
        envelopeId: String,
        transactionId: String,
        body: String
    ) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            outgoingDao.upsertEnvelope(
                OutgoingEnvelopeEntity(
                    userId = userId,
                    roomId = roomId,
                    id = envelopeId,
                    kind = OutgoingEnvelopeKind.TEXT.name,
                    transportState = OutgoingTransportState.QUEUED.name,
                    transactionId = transactionId,
                    eventId = null,
                    body = body,
                    createdAtMillis = now,
                    updatedAtMillis = now,
                    failureMessage = null
                )
            )
            updateRoomPreview(userId, roomId, now)
        }
    }

    suspend fun markOutgoingDispatchStarted(userId: String, roomId: String, envelopeId: String) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            outgoingDao.markDispatchStarted(
                userId = userId,
                roomId = roomId,
                id = envelopeId,
                updatedAtMillis = now
            )
            updateRoomPreview(userId, roomId, now)
        }
    }

    suspend fun markOutgoingDispatchRetrying(
        userId: String,
        roomId: String,
        envelopeId: String,
        failureMessage: String?
    ) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            outgoingDao.markDispatchRetrying(
                userId = userId,
                roomId = roomId,
                id = envelopeId,
                failureMessage = failureMessage,
                updatedAtMillis = now
            )
            updateRoomPreview(userId, roomId, now)
        }
    }

    suspend fun markOutgoingDispatchAccepted(
        userId: String,
        roomId: String,
        envelopeId: String,
        eventId: String
    ) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            outgoingDao.markDispatchAccepted(
                userId = userId,
                roomId = roomId,
                id = envelopeId,
                eventId = eventId,
                updatedAtMillis = now
            )
            if (messageDao.hasMessage(userId, roomId, eventId)) {
                retireOutgoingEnvelopesByEventIds(userId, roomId, listOf(eventId), now)
            }
            updateRoomPreview(userId, roomId, now)
        }
    }

    suspend fun markOutgoingDispatchFailed(
        userId: String,
        roomId: String,
        envelopeId: String,
        failureMessage: String?
    ) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            outgoingDao.markDispatchFailed(
                userId = userId,
                roomId = roomId,
                id = envelopeId,
                failureMessage = failureMessage,
                updatedAtMillis = now
            )
            updateRoomPreview(userId, roomId, now)
        }
    }

    suspend fun retryFailedOutgoingTextEnvelope(
        userId: String,
        roomId: String,
        envelopeId: String
    ): Boolean {
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val didRetry = outgoingDao.markFailedTextEnvelopeQueued(
                userId = userId,
                roomId = roomId,
                id = envelopeId,
                updatedAtMillis = now
            ) > 0
            if (didRetry) {
                updateRoomPreview(userId, roomId, now)
            }
            didRetry
        }
    }

    suspend fun debugMarkOutgoingTextEnvelopeFailed(
        userId: String,
        roomId: String,
        envelopeId: String
    ): Boolean {
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val didMark = outgoingDao.debugMarkActiveTextEnvelopeFailed(
                userId = userId,
                roomId = roomId,
                id = envelopeId,
                failureMessage = "Debug forced send failure",
                updatedAtMillis = now
            ) > 0
            if (didMark) {
                updateRoomPreview(userId, roomId, now)
            }
            didMark
        }
    }

    suspend fun discardFailedOutgoingTextEnvelope(
        userId: String,
        roomId: String,
        envelopeId: String
    ): Boolean {
        return database.withTransaction {
            val envelope = outgoingDao.failedTextEnvelope(
                userId = userId,
                roomId = roomId,
                id = envelopeId
            )
            val didDelete = outgoingDao.deleteFailedTextEnvelope(
                userId = userId,
                roomId = roomId,
                id = envelopeId
            ) > 0
            if (didDelete && envelope != null) {
                val hiddenIds = listOfNotNull(envelope.transactionId, envelope.eventId)
                if (hiddenIds.isNotEmpty()) {
                    messageDao.deleteMessagesByIds(userId, roomId, hiddenIds)
                }
                updateRoomPreview(userId, roomId, System.currentTimeMillis())
            }
            didDelete
        }
    }

    suspend fun outgoingTextDispatchCandidates(
        userId: String,
        envelopeIds: Set<String>? = null
    ): List<OutgoingTextEnvelope> {
        val entities = if (envelopeIds == null) {
            outgoingDao.textDispatchCandidates(userId)
        } else {
            envelopeIds.mapNotNull { id -> outgoingDao.textDispatchCandidate(userId, id) }
        }
        return entities.mapNotNull { it.toOutgoingTextEnvelopeOrNull() }
    }

    suspend fun clearAll() {
        database.withTransaction {
            messageDao.clearAllMessages()
            roomDao.clearAllRooms()
            outgoingDao.clearAllEnvelopes()
        }
    }

    private fun CachedRoomEntity.toRoomSummary(): MatrixRoomSummary {
        return MatrixRoomSummary(
            id = id,
            displayName = displayName,
            avatarUrl = avatarUrl,
            lastMessageText = lastMessageText,
            lastMessageSenderName = lastMessageSenderName,
            lastMessageAtMillis = lastMessageAtMillis,
            lastOwnMessageStatus = lastOwnMessageStatus.toLastOwnMessageStatusOrNull(),
            unreadCount = unreadCount,
            unreadMentionCount = unreadMentionCount,
            isMarkedUnread = isMarkedUnread
        )
    }

    private fun CachedTimelineMessageEntity.toChatMessage(): MatrixChatMessage {
        return MatrixChatMessage(
            id = id,
            eventId = eventId,
            transactionId = transactionId,
            sender = sender,
            body = body,
            timestampMillis = timestampMillis,
            isOwn = isOwn,
            contentType = contentType.toMatrixContentType(),
            deliveryState = deliveryState.toMatrixDeliveryState()
        )
    }

    private fun List<MatrixChatMessage>.toEntities(
        userId: String,
        roomId: String,
        updatedAtMillis: Long
    ): List<CachedTimelineMessageEntity> {
        return mapIndexed { index, message ->
            message.toEntity(userId, roomId, index, updatedAtMillis)
        }
    }

    private fun MatrixChatMessage.toEntity(
        userId: String,
        roomId: String,
        timelineIndex: Int,
        updatedAtMillis: Long
    ): CachedTimelineMessageEntity {
        return CachedTimelineMessageEntity(
            userId = userId,
            roomId = roomId,
            id = id,
            eventId = eventId,
            transactionId = transactionId,
            timelineIndex = timelineIndex,
            sender = sender,
            body = body,
            timestampMillis = timestampMillis,
            isOwn = isOwn,
            contentType = contentType.name,
            deliveryState = deliveryState.name,
            updatedAtMillis = updatedAtMillis
        )
    }

    private suspend fun retireOutgoingEnvelopesDeliveredBy(
        messages: List<MatrixChatMessage>,
        userId: String,
        roomId: String,
        updatedAtMillis: Long
    ) {
        val eventIds = messages
            .mapNotNull { it.eventId }
            .distinct()
        if (eventIds.isEmpty()) return

        retireOutgoingEnvelopesByEventIds(userId, roomId, eventIds, updatedAtMillis)
    }

    private suspend fun retireOutgoingEnvelopesByEventIds(
        userId: String,
        roomId: String,
        eventIds: List<String>,
        updatedAtMillis: Long
    ) {
        if (eventIds.isEmpty()) return

        val transactionIds = outgoingDao.transactionIdsForEventIds(
            userId = userId,
            roomId = roomId,
            eventIds = eventIds
        )
        outgoingDao.retireByEventIds(
            userId = userId,
            roomId = roomId,
            eventIds = eventIds,
            updatedAtMillis = updatedAtMillis
        )
        if (transactionIds.isNotEmpty()) {
            messageDao.deleteMessagesByIds(userId, roomId, transactionIds)
        }
    }

    private suspend fun updateRoomPreview(
        userId: String,
        roomId: String,
        updatedAtMillis: Long
    ) {
        val messages = messageDao.roomMessagesSnapshot(userId, roomId)
        val outgoingEnvelopes = outgoingDao.activeRoomEnvelopesSnapshot(userId, roomId)
        val latestMessage = mergeTimelineWithOutgoing(messages, outgoingEnvelopes).lastOrNull()
        roomDao.updateRoomPreview(
            userId = userId,
            roomId = roomId,
            lastMessageText = latestMessage?.body,
            lastMessageSenderName = latestMessage?.previewSenderName(),
            lastMessageAtMillis = latestMessage?.timestampMillis,
            lastOwnMessageStatus = latestMessage?.lastOwnMessageStatus()?.name,
            updatedAtMillis = updatedAtMillis
        )
    }

    private fun mergeTimelineWithOutgoing(
        messages: List<CachedTimelineMessageEntity>,
        outgoingEnvelopes: List<OutgoingEnvelopeEntity>
    ): List<MatrixChatMessage> {
        val hiddenTimelineIds = outgoingEnvelopes
            .flatMap { envelope -> listOfNotNull(envelope.transactionId, envelope.eventId) }
            .toSet()
        val timelineMessages = messages
            .filter { message ->
                !message.id.startsWith(LOCAL_MESSAGE_ID_PREFIX) &&
                    message.identityIds().none { it in hiddenTimelineIds }
            }
            .map { it.toChatMessage() }
        val outgoingMessages = outgoingEnvelopes
            .mapNotNull { it.toChatMessageOrNull() }

        return (timelineMessages + outgoingMessages)
            .sortedWith(compareBy<MatrixChatMessage> { it.timestampMillis }.thenBy { it.id })
    }

    private fun OutgoingEnvelopeEntity.toChatMessageOrNull(): MatrixChatMessage? {
        if (kind != OutgoingEnvelopeKind.TEXT.name) return null
        val state = transportState.toOutgoingTransportState()

        return MatrixChatMessage(
            id = "outgoing:$id",
            eventId = eventId,
            transactionId = transactionId,
            sender = userId,
            body = body,
            timestampMillis = createdAtMillis,
            isOwn = true,
            contentType = MatrixMessageContentType.TEXT,
            deliveryState = state.toOutgoingDeliveryState(),
            outgoingEnvelopeId = id,
            canRetryOutgoingEnvelope = state == OutgoingTransportState.FAILED,
            canDiscardOutgoingEnvelope = state == OutgoingTransportState.FAILED
        )
    }

    private fun MatrixChatMessage.previewSenderName(): String? {
        return if (isOwn) {
            OWN_MESSAGE_PREVIEW_SENDER
        } else {
            sender.takeIf { it.isNotBlank() }
        }
    }

    private fun MatrixChatMessage.lastOwnMessageStatus(): MatrixLastOwnMessageStatus? {
        if (!isOwn) return null

        return when (deliveryState) {
            MatrixMessageDeliveryState.SENDING -> MatrixLastOwnMessageStatus.PENDING
            MatrixMessageDeliveryState.SENT -> MatrixLastOwnMessageStatus.SENT
            MatrixMessageDeliveryState.FAILED -> MatrixLastOwnMessageStatus.FAILED
        }
    }

    private fun OutgoingEnvelopeEntity.toOutgoingTextEnvelopeOrNull(): OutgoingTextEnvelope? {
        if (kind != OutgoingEnvelopeKind.TEXT.name) return null

        return OutgoingTextEnvelope(
            userId = userId,
            roomId = roomId,
            id = id,
            transportState = transportState.toOutgoingTransportState(),
            transactionId = transactionId,
            eventId = eventId,
            body = body,
            createdAtMillis = createdAtMillis,
            failureMessage = failureMessage
        )
    }

    private fun String.toMatrixDeliveryState(): MatrixMessageDeliveryState {
        return runCatching { MatrixMessageDeliveryState.valueOf(this) }
            .getOrDefault(MatrixMessageDeliveryState.SENT)
    }

    private fun String.toMatrixContentType(): MatrixMessageContentType {
        return runCatching { MatrixMessageContentType.valueOf(this) }
            .getOrDefault(MatrixMessageContentType.TEXT)
    }

    private fun CachedTimelineMessageEntity.identityIds(): List<String> {
        return listOfNotNull(id, eventId, transactionId)
    }

    private fun String?.toLastOwnMessageStatusOrNull(): MatrixLastOwnMessageStatus? {
        return this?.let {
            runCatching { MatrixLastOwnMessageStatus.valueOf(it) }.getOrNull()
        }
    }

    private fun String.toOutgoingTransportState(): OutgoingTransportState {
        return runCatching { OutgoingTransportState.valueOf(this) }
            .getOrDefault(OutgoingTransportState.FAILED)
    }

    private fun OutgoingTransportState.toOutgoingDeliveryState(): MatrixMessageDeliveryState {
        return when (this) {
            OutgoingTransportState.QUEUED,
            OutgoingTransportState.SENDING,
            OutgoingTransportState.RETRYING -> MatrixMessageDeliveryState.SENDING
            OutgoingTransportState.SENT,
            OutgoingTransportState.RETIRED -> MatrixMessageDeliveryState.SENT
            OutgoingTransportState.FAILED -> MatrixMessageDeliveryState.FAILED
        }
    }

    private companion object {
        val RoomSummaryComparator = compareByDescending<MatrixRoomSummary> { it.lastMessageAtMillis }
            .thenBy { it.displayName.lowercase(Locale.ROOT) }
            .thenBy { it.id }

        const val LOCAL_MESSAGE_ID_PREFIX = "local:"
        const val OWN_MESSAGE_PREVIEW_SENDER = "You"
    }
}
