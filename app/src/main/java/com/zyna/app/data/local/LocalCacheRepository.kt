package com.zyna.app.data.local

import androidx.room.withTransaction
import com.zyna.app.data.matrix.MatrixChatMessage
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
                .sortedBy { it.displayName.lowercase(Locale.ROOT) }
        }
    }

    suspend fun cacheRoomsSnapshot(userId: String, rooms: List<MatrixRoomSummary>) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            roomDao.clearRooms(userId)
            roomDao.upsertRooms(
                rooms.map { room ->
                    CachedRoomEntity(
                        userId = userId,
                        id = room.id,
                        displayName = room.displayName,
                        avatarUrl = room.avatarUrl,
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
    }

    suspend fun markOutgoingDispatchStarted(userId: String, roomId: String, envelopeId: String) {
        outgoingDao.markDispatchStarted(
            userId = userId,
            roomId = roomId,
            id = envelopeId,
            updatedAtMillis = System.currentTimeMillis()
        )
    }

    suspend fun markOutgoingDispatchRetrying(
        userId: String,
        roomId: String,
        envelopeId: String,
        failureMessage: String?
    ) {
        outgoingDao.markDispatchRetrying(
            userId = userId,
            roomId = roomId,
            id = envelopeId,
            failureMessage = failureMessage,
            updatedAtMillis = System.currentTimeMillis()
        )
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
        }
    }

    suspend fun markOutgoingDispatchFailed(
        userId: String,
        roomId: String,
        envelopeId: String,
        failureMessage: String?
    ) {
        outgoingDao.markDispatchFailed(
            userId = userId,
            roomId = roomId,
            id = envelopeId,
            failureMessage = failureMessage,
            updatedAtMillis = System.currentTimeMillis()
        )
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
            avatarUrl = avatarUrl
        )
    }

    private fun CachedTimelineMessageEntity.toChatMessage(): MatrixChatMessage {
        return MatrixChatMessage(
            id = id,
            sender = sender,
            body = body,
            timestampMillis = timestampMillis,
            isOwn = isOwn,
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
            timelineIndex = timelineIndex,
            sender = sender,
            body = body,
            timestampMillis = timestampMillis,
            isOwn = isOwn,
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
            .map { it.id }
            .filter { id -> id.startsWith(EVENT_ID_PREFIX) }
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
                    message.id !in hiddenTimelineIds
            }
            .map { it.toChatMessage() }
        val outgoingMessages = outgoingEnvelopes
            .mapNotNull { it.toChatMessageOrNull() }

        return (timelineMessages + outgoingMessages)
            .sortedWith(compareBy<MatrixChatMessage> { it.timestampMillis }.thenBy { it.id })
    }

    private fun OutgoingEnvelopeEntity.toChatMessageOrNull(): MatrixChatMessage? {
        if (kind != OutgoingEnvelopeKind.TEXT.name) return null

        return MatrixChatMessage(
            id = "outgoing:$id",
            sender = userId,
            body = body,
            timestampMillis = createdAtMillis,
            isOwn = true,
            deliveryState = transportState.toOutgoingDeliveryState()
        )
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

    private fun String.toOutgoingTransportState(): OutgoingTransportState {
        return runCatching { OutgoingTransportState.valueOf(this) }
            .getOrDefault(OutgoingTransportState.FAILED)
    }

    private fun String.toOutgoingDeliveryState(): MatrixMessageDeliveryState {
        return when (toOutgoingTransportState()) {
            OutgoingTransportState.QUEUED,
            OutgoingTransportState.SENDING,
            OutgoingTransportState.RETRYING -> MatrixMessageDeliveryState.SENDING
            OutgoingTransportState.SENT,
            OutgoingTransportState.RETIRED -> MatrixMessageDeliveryState.SENT
            OutgoingTransportState.FAILED -> MatrixMessageDeliveryState.FAILED
        }
    }

    private companion object {
        const val EVENT_ID_PREFIX = "$"
        const val LOCAL_MESSAGE_ID_PREFIX = "local:"
    }
}
