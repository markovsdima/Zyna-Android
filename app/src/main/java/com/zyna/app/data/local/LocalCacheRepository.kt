package com.zyna.app.data.local

import android.content.Context
import androidx.room.withTransaction
import com.zyna.app.data.matrix.MatrixChatMessage
import com.zyna.app.data.matrix.MatrixForwardImageItem
import com.zyna.app.data.matrix.MatrixImageInfo
import com.zyna.app.data.matrix.MatrixLastOwnMessageStatus
import com.zyna.app.data.matrix.MatrixMessageContentType
import com.zyna.app.data.matrix.MatrixMessageDeliveryState
import com.zyna.app.data.matrix.MatrixReplyInfo
import com.zyna.app.data.matrix.MatrixRoomSummary
import com.zyna.app.data.messaging.ZynaHtmlCodec
import com.zyna.app.data.messaging.ZynaMessageAttributes
import com.zyna.app.data.messaging.normalizedMessageCaption
import com.zyna.app.data.outgoing.OutgoingEnvelopeKind
import com.zyna.app.data.outgoing.OutgoingEditEnvelope
import com.zyna.app.data.outgoing.OutgoingImageEnvelope
import com.zyna.app.data.outgoing.OutgoingMediaStorage
import com.zyna.app.data.outgoing.OutgoingRedactionEnvelope
import com.zyna.app.data.outgoing.OutgoingTextEnvelope
import com.zyna.app.data.outgoing.OutgoingTransportState
import com.zyna.app.util.ZynaPerfLog
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class LocalCacheRepository(
    private val database: ZynaDatabase,
    private val context: Context
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
                        directUserId = room.directUserId ?: existingRoom?.directUserId,
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

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeRoomTimelineWindow(
        userId: String,
        roomId: String,
        boundsFlow: Flow<TimelineWindowBounds?>,
        initialLimit: Int
    ): Flow<List<MatrixChatMessage>> {
        return boundsFlow.flatMapLatest { bounds ->
            val oldestAnchor = bounds?.oldestAnchor
            val newestAnchor = bounds?.newestAnchor
            val messagesFlow = when {
                oldestAnchor == null -> {
                    messageDao.observeLatestRoomMessagesWindow(
                        userId = userId,
                        roomId = roomId,
                        localIdPattern = LOCAL_MESSAGE_ID_PATTERN,
                        limit = initialLimit
                    )
                }
                newestAnchor == null -> {
                    messageDao.observeRoomMessagesFrom(
                        userId = userId,
                        roomId = roomId,
                        localIdPattern = LOCAL_MESSAGE_ID_PATTERN,
                        fromTimestampMillis = oldestAnchor.timestampMillis,
                        fromId = oldestAnchor.id
                    )
                }
                else -> {
                    messageDao.observeRoomMessagesRange(
                        userId = userId,
                        roomId = roomId,
                        localIdPattern = LOCAL_MESSAGE_ID_PATTERN,
                        fromTimestampMillis = oldestAnchor.timestampMillis,
                        fromId = oldestAnchor.id,
                        toTimestampMillis = newestAnchor.timestampMillis,
                        toId = newestAnchor.id
                    )
                }
            }
            combine(
                messagesFlow,
                outgoingDao.observeActiveRoomEnvelopes(userId, roomId)
            ) { messages, outgoingEnvelopes ->
                mergeTimelineWithOutgoing(messages, outgoingEnvelopes, bounds)
            }
        }.flowOn(Dispatchers.Default)
    }

    suspend fun latestRoomTimelineWindowAnchor(
        userId: String,
        roomId: String,
        limit: Int
    ): TimelineWindowAnchor? = withContext(Dispatchers.IO) {
        latestRoomTimelineWindowEntities(
            userId = userId,
            roomId = roomId,
            limit = limit
        )
            .firstOrNull()
            ?.toTimelineWindowAnchor()
    }

    suspend fun latestRoomTimelineWindowSnapshot(
        userId: String,
        roomId: String,
        limit: Int
    ): TimelineWindowSnapshot<MatrixChatMessage> = withContext(Dispatchers.IO) {
        val totalStart = ZynaPerfLog.start()
        ZynaPerfLog.mark { "cache.latestWindow.begin roomId=$roomId limit=$limit" }
        val messagesStart = ZynaPerfLog.start()
        val messages = latestRoomTimelineWindowEntities(
            userId = userId,
            roomId = roomId,
            limit = limit
        )
        ZynaPerfLog.end(
            messagesStart,
            "cache.latestWindow.messagesQuery"
        ) {
            "roomId=$roomId count=${messages.size}"
        }
        val outgoingStart = ZynaPerfLog.start()
        val outgoingEnvelopes = outgoingDao.activeRoomEnvelopesSnapshot(userId, roomId)
        ZynaPerfLog.end(
            outgoingStart,
            "cache.latestWindow.outgoingQuery"
        ) {
            "roomId=$roomId count=${outgoingEnvelopes.size}"
        }
        val oldestAnchor = messages.firstOrNull()?.toTimelineWindowAnchor()
        val newestAnchor = messages.lastOrNull()?.toTimelineWindowAnchor()
        val mergeStart = ZynaPerfLog.start()
        val mergedMessages = mergeTimelineWithOutgoing(
            messages = messages,
            outgoingEnvelopes = outgoingEnvelopes,
            bounds = TimelineWindowBounds(oldestAnchor = oldestAnchor)
        )
        ZynaPerfLog.end(
            mergeStart,
            "cache.latestWindow.merge"
        ) {
            "roomId=$roomId merged=${mergedMessages.size}"
        }
        val hasOlderStart = ZynaPerfLog.start()
        val hasOlder = oldestAnchor?.let { anchor ->
            hasOlderRoomTimelineMessages(
                userId = userId,
                roomId = roomId,
                anchor = anchor
            )
        } ?: false
        ZynaPerfLog.end(
            hasOlderStart,
            "cache.latestWindow.hasOlder"
        ) {
            "roomId=$roomId value=$hasOlder"
        }
        val hasNewerStart = ZynaPerfLog.start()
        val hasNewer = newestAnchor?.let { anchor ->
            hasNewerRoomTimelineMessages(
                userId = userId,
                roomId = roomId,
                anchor = anchor
            )
        } ?: false
        ZynaPerfLog.end(
            hasNewerStart,
            "cache.latestWindow.hasNewer"
        ) {
            "roomId=$roomId value=$hasNewer"
        }

        TimelineWindowSnapshot(
            anchor = oldestAnchor,
            messages = mergedMessages,
            newestAnchor = newestAnchor,
            hasOlderInDb = hasOlder,
            hasNewerInDb = hasNewer
        ).also {
            ZynaPerfLog.end(
                totalStart,
                "cache.latestWindow.total"
            ) {
                "roomId=$roomId count=${it.messages.size}"
            }
        }
    }

    suspend fun roomTimelineWindowAroundEvent(
        userId: String,
        roomId: String,
        eventId: String,
        limit: Int
    ): TimelineWindowSnapshot<MatrixChatMessage>? = withContext(Dispatchers.IO) {
        val target = messageDao.roomMessageByEventId(
            userId = userId,
            roomId = roomId,
            localIdPattern = LOCAL_MESSAGE_ID_PATTERN,
            eventId = eventId
        ) ?: return@withContext null

        val halfLimit = (limit / 2).coerceAtLeast(1)
        val atOrBeforeTarget = messageDao.roomMessagesAtOrBefore(
            userId = userId,
            roomId = roomId,
            localIdPattern = LOCAL_MESSAGE_ID_PATTERN,
            atTimestampMillis = target.timestampMillis,
            atId = target.id,
            limit = halfLimit
        )
        val afterTarget = messageDao.roomMessagesAfter(
            userId = userId,
            roomId = roomId,
            localIdPattern = LOCAL_MESSAGE_ID_PATTERN,
            afterTimestampMillis = target.timestampMillis,
            afterId = target.id,
            limit = halfLimit
        )
        val messages = (atOrBeforeTarget.asReversed() + target + afterTarget)
            .dedupeTimelineWindowEntities()
        val oldestAnchor = messages.firstOrNull()?.toTimelineWindowAnchor()
        val newestAnchor = messages.lastOrNull()?.toTimelineWindowAnchor()
        val bounds = TimelineWindowBounds(
            oldestAnchor = oldestAnchor,
            newestAnchor = newestAnchor
        )
        val outgoingEnvelopes = outgoingDao.activeRoomEnvelopesSnapshot(userId, roomId)

        TimelineWindowSnapshot(
            anchor = oldestAnchor,
            messages = mergeTimelineWithOutgoing(
                messages = messages,
                outgoingEnvelopes = outgoingEnvelopes,
                bounds = bounds
            ),
            newestAnchor = newestAnchor,
            hasOlderInDb = oldestAnchor?.let { anchor ->
                hasOlderRoomTimelineMessages(
                    userId = userId,
                    roomId = roomId,
                    anchor = anchor
                )
            } ?: false,
            hasNewerInDb = newestAnchor?.let { anchor ->
                hasNewerRoomTimelineMessages(
                    userId = userId,
                    roomId = roomId,
                    anchor = anchor
                )
            } ?: false
        )
    }

    suspend fun olderRoomTimelineWindowAnchor(
        userId: String,
        roomId: String,
        anchor: TimelineWindowAnchor,
        limit: Int
    ): TimelineWindowAnchor? = withContext(Dispatchers.IO) {
        messageDao.roomMessagesBefore(
            userId = userId,
            roomId = roomId,
            localIdPattern = LOCAL_MESSAGE_ID_PATTERN,
            beforeTimestampMillis = anchor.timestampMillis,
            beforeId = anchor.id,
            limit = limit
        )
            .lastOrNull()
            ?.toTimelineWindowAnchor()
    }

    suspend fun newerRoomTimelineWindowAnchor(
        userId: String,
        roomId: String,
        anchor: TimelineWindowAnchor,
        limit: Int
    ): TimelineWindowAnchor? = withContext(Dispatchers.IO) {
        messageDao.roomMessagesAfter(
            userId = userId,
            roomId = roomId,
            localIdPattern = LOCAL_MESSAGE_ID_PATTERN,
            afterTimestampMillis = anchor.timestampMillis,
            afterId = anchor.id,
            limit = limit
        )
            .lastOrNull()
            ?.toTimelineWindowAnchor()
    }

    suspend fun hasOlderRoomTimelineMessages(
        userId: String,
        roomId: String,
        anchor: TimelineWindowAnchor
    ): Boolean = withContext(Dispatchers.IO) {
        messageDao.hasRoomMessagesBefore(
            userId = userId,
            roomId = roomId,
            localIdPattern = LOCAL_MESSAGE_ID_PATTERN,
            beforeTimestampMillis = anchor.timestampMillis,
            beforeId = anchor.id
        )
    }

    suspend fun hasNewerRoomTimelineMessages(
        userId: String,
        roomId: String,
        anchor: TimelineWindowAnchor
    ): Boolean = withContext(Dispatchers.IO) {
        messageDao.hasRoomMessagesAfter(
            userId = userId,
            roomId = roomId,
            localIdPattern = LOCAL_MESSAGE_ID_PATTERN,
            afterTimestampMillis = anchor.timestampMillis,
            afterId = anchor.id
        )
    }

    private suspend fun latestRoomTimelineWindowEntities(
        userId: String,
        roomId: String,
        limit: Int
    ): List<CachedTimelineMessageEntity> {
        return messageDao.latestRoomMessagesWindow(
            userId = userId,
            roomId = roomId,
            localIdPattern = LOCAL_MESSAGE_ID_PATTERN,
            limit = limit
        )
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
            val incomingEntities = messages.toEntities(userId, roomId, now)
            val existingMessagesByIdentity = existingMessagesMatching(
                userId = userId,
                roomId = roomId,
                incomingMessages = incomingEntities
            )
                .cachedMessagesByIdentity()
            val hasIncomingReplies = incomingEntities.any { it.replyEventId != null }
            val existingReplyInfosByIdentity = if (hasIncomingReplies) {
                existingMessagesByIdentity.values
                    .distinctBy { it.id }
                    .cachedReplyInfosByIdentity()
            } else {
                emptyMap()
            }
            val outgoingReplyInfosByIdentity = if (hasIncomingReplies) {
                outgoingDao.activeRoomEnvelopesSnapshot(userId, roomId)
                    .outgoingReplyInfosByIdentity()
            } else {
                emptyMap()
            }
            val replyTargetInfosByIdentity = if (hasIncomingReplies) {
                replyTargetsMatching(
                    userId = userId,
                    roomId = roomId,
                    incomingMessages = incomingEntities
                )
                    .replyTargetInfosByIdentity() + incomingEntities.replyTargetInfosByIdentity()
            } else {
                emptyMap()
            }
            val existingRedactionsByIdentity = existingMessagesByIdentity.values
                .distinctBy { it.id }
                .redactionsByIdentity()
            val entities = incomingEntities
                .preserveExistingTimelineState(existingMessagesByIdentity)
                .enrichReplyInfos(
                    existingReplyInfosByIdentity = existingReplyInfosByIdentity,
                    outgoingReplyInfosByIdentity = outgoingReplyInfosByIdentity,
                    replyTargetInfosByIdentity = replyTargetInfosByIdentity
                )
                .preserveExistingRedactions(existingRedactionsByIdentity)
            messageDao.upsertMessages(entities)
            deleteSafeEventDuplicates(entities)
            retireOutgoingEnvelopesDeliveredBy(messages, userId, roomId, now)
            updateRoomPreview(userId, roomId, now)
        }
    }

    suspend fun createOutgoingTextEnvelope(
        userId: String,
        roomId: String,
        envelopeId: String,
        transactionId: String,
        body: String,
        replyInfo: MatrixReplyInfo?,
        forwardedFrom: String?
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
                    targetEventId = null,
                    targetTransactionId = null,
                    targetBody = null,
                    targetContentType = null,
                    replyEventId = replyInfo?.eventId,
                    replySenderId = replyInfo?.senderId,
                    replySenderDisplayName = replyInfo?.senderDisplayName,
                    replyBody = replyInfo?.body,
                    forwardedFrom = forwardedFrom,
                    imageLocalPath = null,
                    imageMimeType = null,
                    imageWidth = null,
                    imageHeight = null,
                    imageSizeBytes = null,
                    imageThumbnailLocalPath = null,
                    imageThumbnailMimeType = null,
                    imageThumbnailWidth = null,
                    imageThumbnailHeight = null,
                    imageThumbnailSizeBytes = null,
                    imageCaption = null,
                    zynaAttributesJson = null,
                    imageSourceJson = null,
                    imageThumbnailSourceJson = null,
                    imageBlurhash = null,
                    imageUploadedJson = null,
                    imageUploadedAtMillis = null,
                    body = body,
                    createdAtMillis = now,
                    updatedAtMillis = now,
                    failureMessage = null
                )
            )
            updateRoomPreview(userId, roomId, now)
        }
    }

    suspend fun createOutgoingImageEnvelope(
        userId: String,
        roomId: String,
        envelopeId: String,
        transactionId: String,
        localPath: String,
        mimeType: String,
        width: Int,
        height: Int,
        sizeBytes: Long,
        thumbnailLocalPath: String?,
        thumbnailMimeType: String?,
        thumbnailWidth: Int?,
        thumbnailHeight: Int?,
        thumbnailSizeBytes: Long?,
        blurhash: String?,
        caption: String?,
        zynaAttributes: ZynaMessageAttributes
    ) {
        val now = System.currentTimeMillis()
        val normalizedCaption = caption.normalizedMessageCaption()
        database.withTransaction {
            outgoingDao.upsertEnvelope(
                OutgoingEnvelopeEntity(
                    userId = userId,
                    roomId = roomId,
                    id = envelopeId,
                    kind = OutgoingEnvelopeKind.IMAGE.name,
                    transportState = OutgoingTransportState.QUEUED.name,
                    transactionId = transactionId,
                    eventId = null,
                    targetEventId = null,
                    targetTransactionId = null,
                    targetBody = null,
                    targetContentType = null,
                    replyEventId = null,
                    replySenderId = null,
                    replySenderDisplayName = null,
                    replyBody = null,
                    forwardedFrom = null,
                    imageLocalPath = localPath,
                    imageMimeType = mimeType,
                    imageWidth = width,
                    imageHeight = height,
                    imageSizeBytes = sizeBytes,
                    imageThumbnailLocalPath = thumbnailLocalPath,
                    imageThumbnailMimeType = thumbnailMimeType,
                    imageThumbnailWidth = thumbnailWidth,
                    imageThumbnailHeight = thumbnailHeight,
                    imageThumbnailSizeBytes = thumbnailSizeBytes,
                    imageCaption = normalizedCaption,
                    zynaAttributesJson = ZynaHtmlCodec.encodeAttributesJson(zynaAttributes),
                    imageSourceJson = null,
                    imageThumbnailSourceJson = null,
                    imageBlurhash = blurhash,
                    imageUploadedJson = null,
                    imageUploadedAtMillis = null,
                    body = normalizedCaption ?: "Photo",
                    createdAtMillis = now,
                    updatedAtMillis = now,
                    failureMessage = null
                )
            )
            updateRoomPreview(userId, roomId, now)
        }
    }

    suspend fun createOutgoingForwardedImageEnvelope(
        userId: String,
        roomId: String,
        envelopeId: String,
        transactionId: String,
        image: MatrixForwardImageItem,
        caption: String?,
        zynaAttributes: ZynaMessageAttributes
    ) {
        val now = System.currentTimeMillis()
        val normalizedCaption = caption.normalizedMessageCaption()
        database.withTransaction {
            outgoingDao.upsertEnvelope(
                OutgoingEnvelopeEntity(
                    userId = userId,
                    roomId = roomId,
                    id = envelopeId,
                    kind = OutgoingEnvelopeKind.IMAGE.name,
                    transportState = OutgoingTransportState.QUEUED.name,
                    transactionId = transactionId,
                    eventId = null,
                    targetEventId = null,
                    targetTransactionId = null,
                    targetBody = null,
                    targetContentType = null,
                    replyEventId = null,
                    replySenderId = null,
                    replySenderDisplayName = null,
                    replyBody = null,
                    forwardedFrom = null,
                    imageLocalPath = null,
                    imageMimeType = image.mimeType,
                    imageWidth = image.width,
                    imageHeight = image.height,
                    imageSizeBytes = null,
                    imageThumbnailLocalPath = null,
                    imageThumbnailMimeType = null,
                    imageThumbnailWidth = null,
                    imageThumbnailHeight = null,
                    imageThumbnailSizeBytes = null,
                    imageCaption = normalizedCaption,
                    zynaAttributesJson = ZynaHtmlCodec.encodeAttributesJson(zynaAttributes),
                    imageSourceJson = image.sourceJson,
                    imageThumbnailSourceJson = image.thumbnailSourceJson,
                    imageBlurhash = image.blurhash,
                    imageUploadedJson = null,
                    imageUploadedAtMillis = null,
                    body = normalizedCaption ?: "Photo",
                    createdAtMillis = now,
                    updatedAtMillis = now,
                    failureMessage = null
                )
            )
            updateRoomPreview(userId, roomId, now)
        }
    }

    suspend fun createOutgoingRedactionEnvelope(
        userId: String,
        roomId: String,
        envelopeId: String,
        transactionId: String,
        targetMessage: MatrixChatMessage
    ): Boolean {
        val targetEventId = targetMessage.eventId ?: return false
        val now = System.currentTimeMillis()
        database.withTransaction {
            outgoingDao.upsertEnvelope(
                OutgoingEnvelopeEntity(
                    userId = userId,
                    roomId = roomId,
                    id = envelopeId,
                    kind = OutgoingEnvelopeKind.REDACTION.name,
                    transportState = OutgoingTransportState.QUEUED.name,
                    transactionId = transactionId,
                    eventId = null,
                    targetEventId = targetEventId,
                    targetTransactionId = targetMessage.transactionId,
                    targetBody = targetMessage.body,
                    targetContentType = targetMessage.contentType.name,
                    replyEventId = null,
                    replySenderId = null,
                    replySenderDisplayName = null,
                    replyBody = null,
                    forwardedFrom = null,
                    imageLocalPath = null,
                    imageMimeType = null,
                    imageWidth = null,
                    imageHeight = null,
                    imageSizeBytes = null,
                    imageThumbnailLocalPath = null,
                    imageThumbnailMimeType = null,
                    imageThumbnailWidth = null,
                    imageThumbnailHeight = null,
                    imageThumbnailSizeBytes = null,
                    imageCaption = null,
                    zynaAttributesJson = null,
                    imageSourceJson = null,
                    imageThumbnailSourceJson = null,
                    imageBlurhash = null,
                    imageUploadedJson = null,
                    imageUploadedAtMillis = null,
                    body = "",
                    createdAtMillis = now,
                    updatedAtMillis = now,
                    failureMessage = null
                )
            )
            markCachedMessageRedacted(
                userId = userId,
                roomId = roomId,
                ids = targetMessage.identityIds(),
                updatedAtMillis = now
            )
            updateRoomPreview(userId, roomId, now)
        }
        return true
    }

    suspend fun prepareOutgoingTextEdit(
        userId: String,
        roomId: String,
        targetMessage: MatrixChatMessage,
        body: String,
        transactionId: String
    ): Boolean {
        val eventId = targetMessage.eventId ?: return false
        val trimmedBody = body.trim()
        if (
            trimmedBody.isEmpty() ||
            trimmedBody == targetMessage.body.trim() ||
            targetMessage.contentType != MatrixMessageContentType.TEXT ||
            !targetMessage.isOwn ||
            targetMessage.isEditPending
        ) {
            return false
        }

        val now = System.currentTimeMillis()
        return database.withTransaction {
            val didPrepare = messageDao.preparePendingTextEdit(
                userId = userId,
                roomId = roomId,
                eventId = eventId,
                editTransactionId = transactionId,
                pendingEditBody = trimmedBody,
                updatedAtMillis = now
            ) > 0
            if (didPrepare) {
                updateRoomPreview(userId, roomId, now)
            }
            didPrepare
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

    suspend fun markOutgoingImageUploadAccepted(
        userId: String,
        roomId: String,
        envelopeId: String,
        uploadedImageJson: String
    ): Boolean {
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val didUpdate = outgoingDao.markImageUploadAccepted(
                userId = userId,
                roomId = roomId,
                id = envelopeId,
                uploadedImageJson = uploadedImageJson,
                updatedAtMillis = now
            ) > 0
            if (didUpdate) {
                updateRoomPreview(userId, roomId, now)
            }
            didUpdate
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

    suspend fun retryFailedOutgoingMessageEnvelope(
        userId: String,
        roomId: String,
        envelopeId: String
    ): Boolean {
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val didRetry = outgoingDao.markFailedMessageEnvelopeQueued(
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

    suspend fun debugMarkOutgoingMessageEnvelopeFailed(
        userId: String,
        roomId: String,
        envelopeId: String
    ): Boolean {
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val didMark = outgoingDao.debugMarkActiveMessageEnvelopeFailed(
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

    suspend fun discardFailedOutgoingMessageEnvelope(
        userId: String,
        roomId: String,
        envelopeId: String
    ): Boolean {
        return database.withTransaction {
            val envelope = outgoingDao.failedMessageEnvelope(
                userId = userId,
                roomId = roomId,
                id = envelopeId
            )
            val didDelete = outgoingDao.deleteFailedMessageEnvelope(
                userId = userId,
                roomId = roomId,
                id = envelopeId
            ) > 0
            if (didDelete && envelope != null) {
                val hiddenIds = listOfNotNull(envelope.transactionId, envelope.eventId)
                if (hiddenIds.isNotEmpty()) {
                    messageDao.deleteMessagesByIds(userId, roomId, hiddenIds)
                }
                deleteLocalFiles(
                    listOfNotNull(
                        envelope.imageLocalPath,
                        envelope.imageThumbnailLocalPath
                    )
                )
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

    suspend fun outgoingImageDispatchCandidates(
        userId: String,
        envelopeIds: Set<String>? = null
    ): List<OutgoingImageEnvelope> {
        val entities = if (envelopeIds == null) {
            outgoingDao.imageDispatchCandidates(userId)
        } else {
            envelopeIds.mapNotNull { id -> outgoingDao.imageDispatchCandidate(userId, id) }
        }
        return entities.mapNotNull { it.toOutgoingImageEnvelopeOrNull() }
    }

    suspend fun outgoingRedactionDispatchCandidates(
        userId: String,
        envelopeIds: Set<String>? = null
    ): List<OutgoingRedactionEnvelope> {
        val entities = if (envelopeIds == null) {
            outgoingDao.redactionDispatchCandidates(userId)
        } else {
            envelopeIds.mapNotNull { id -> outgoingDao.redactionDispatchCandidate(userId, id) }
        }
        return entities.mapNotNull { it.toOutgoingRedactionEnvelopeOrNull() }
    }

    suspend fun outgoingEditDispatchCandidates(userId: String): List<OutgoingEditEnvelope> {
        return messageDao.pendingTextEdits(userId)
            .mapNotNull { it.toOutgoingEditEnvelopeOrNull() }
    }

    suspend fun markOutgoingRedactionDispatchAccepted(
        userId: String,
        roomId: String,
        envelopeId: String,
        redactionEventId: String
    ) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            outgoingDao.markDispatchAccepted(
                userId = userId,
                roomId = roomId,
                id = envelopeId,
                eventId = redactionEventId,
                updatedAtMillis = now
            )
            outgoingDao.deleteEnvelope(
                userId = userId,
                roomId = roomId,
                id = envelopeId
            )
            updateRoomPreview(userId, roomId, now)
        }
    }

    suspend fun markOutgoingRedactionDispatchResolved(
        userId: String,
        roomId: String,
        envelopeId: String
    ) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            outgoingDao.deleteEnvelope(
                userId = userId,
                roomId = roomId,
                id = envelopeId
            )
            updateRoomPreview(userId, roomId, now)
        }
    }

    suspend fun markOutgoingRedactionDispatchTerminalFailure(
        userId: String,
        roomId: String,
        envelopeId: String,
        failureMessage: String?
    ) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            val envelope = outgoingDao.redactionDispatchCandidate(userId, envelopeId)
            if (envelope != null) {
                restoreCachedMessageFromRedaction(envelope, now)
            }
            outgoingDao.markDispatchFailed(
                userId = userId,
                roomId = roomId,
                id = envelopeId,
                failureMessage = failureMessage,
                updatedAtMillis = now
            )
            outgoingDao.deleteEnvelope(
                userId = userId,
                roomId = roomId,
                id = envelopeId
            )
            updateRoomPreview(userId, roomId, now)
        }
    }

    suspend fun markOutgoingEditDispatchAccepted(
        userId: String,
        roomId: String,
        eventId: String,
        transactionId: String,
        editEventId: String,
        body: String
    ) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            messageDao.markPendingTextEditAccepted(
                userId = userId,
                roomId = roomId,
                eventId = eventId,
                editTransactionId = transactionId,
                latestEditEventId = editEventId,
                body = body,
                updatedAtMillis = now
            )
            updateRoomPreview(userId, roomId, now)
        }
    }

    suspend fun markOutgoingEditDispatchTerminalFailure(
        userId: String,
        roomId: String,
        eventId: String,
        transactionId: String
    ) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            messageDao.markPendingTextEditFailed(
                userId = userId,
                roomId = roomId,
                eventId = eventId,
                editTransactionId = transactionId,
                updatedAtMillis = now
            )
            updateRoomPreview(userId, roomId, now)
        }
    }

    suspend fun clearAll() {
        database.withTransaction {
            messageDao.clearAllMessages()
            roomDao.clearAllRooms()
            outgoingDao.clearAllEnvelopes()
        }
        cleanupOrphanOutgoingMediaFiles()
    }

    suspend fun cleanupOrphanOutgoingMediaFiles() = withContext(Dispatchers.IO) {
        val mediaDir = File(context.filesDir, OutgoingMediaStorage.DIRECTORY_NAME)
        val files = mediaDir.listFiles()?.filter { it.isFile }.orEmpty()
        if (files.isEmpty()) return@withContext

        val activePaths = outgoingDao.activeImageLocalPaths().toSet()
        files.forEach { file ->
            if (file.absolutePath !in activePaths) {
                runCatching { file.delete() }
            }
        }
    }

    private fun CachedRoomEntity.toRoomSummary(): MatrixRoomSummary {
        return MatrixRoomSummary(
            id = id,
            displayName = displayName,
            avatarUrl = avatarUrl,
            directUserId = directUserId,
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
        val displayBody = pendingEditBody
            ?.takeIf { isEditPending && it.isNotBlank() }
            ?: body
        return MatrixChatMessage(
            id = id,
            eventId = eventId,
            transactionId = transactionId,
            sender = sender,
            senderDisplayName = senderDisplayName,
            body = displayBody,
            timestampMillis = timestampMillis,
            isOwn = isOwn,
            contentType = contentType.toMatrixContentType(),
            imageInfo = imageInfoOrNull(),
            deliveryState = deliveryState.toMatrixDeliveryState(),
            replyInfo = replyInfoOrNull(),
            forwardedFrom = forwardedFrom,
            zynaAttributes = ZynaHtmlCodec.decodeAttributesJson(zynaAttributesJson),
            isEdited = isEdited,
            isEditPending = isEditPending,
            isEditFailed = isEditFailed,
            latestEditEventId = latestEditEventId,
            editTransactionId = editTransactionId,
            pendingEditBody = pendingEditBody
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
            senderDisplayName = senderDisplayName,
            body = body,
            timestampMillis = timestampMillis,
            isOwn = isOwn,
            contentType = contentType.name,
            imageSourceJson = imageInfo?.sourceJson,
            imageThumbnailSourceJson = imageInfo?.thumbnailSourceJson,
            imageWidth = imageInfo?.width,
            imageHeight = imageInfo?.height,
            imageCaption = imageInfo?.caption.normalizedMessageCaption(),
            imageMimeType = imageInfo?.mimeType,
            imageBlurhash = imageInfo?.blurhash,
            deliveryState = deliveryState.name,
            replyEventId = replyInfo?.eventId,
            replySenderId = replyInfo?.senderId,
            replySenderDisplayName = replyInfo?.senderDisplayName,
            replyBody = replyInfo?.body,
            forwardedFrom = forwardedFrom,
            zynaAttributesJson = ZynaHtmlCodec.encodeAttributesJson(zynaAttributes),
            isEdited = isEdited,
            isEditPending = isEditPending,
            isEditFailed = isEditFailed,
            latestEditEventId = latestEditEventId,
            editTransactionId = editTransactionId,
            pendingEditBody = pendingEditBody,
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
        val imageLocalPaths = outgoingDao.imageLocalPathsForEventIds(
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
        deleteLocalFiles(imageLocalPaths)
    }

    private suspend fun updateRoomPreview(
        userId: String,
        roomId: String,
        updatedAtMillis: Long
    ) {
        val outgoingEnvelopes = outgoingDao.activeRoomEnvelopesSnapshot(userId, roomId)
        val hiddenTimelineIds = outgoingEnvelopes
            .flatMap { envelope -> listOfNotNull(envelope.transactionId, envelope.eventId) }
            .toSet()
        val latestTimelineMessages = messageDao.latestRoomMessages(
            userId = userId,
            roomId = roomId,
            localIdPattern = LOCAL_MESSAGE_ID_PATTERN,
            limit = ROOM_PREVIEW_CANDIDATE_LIMIT
        )
            .filter { message -> message.identityIds().none { it in hiddenTimelineIds } }
            .map { it.toChatMessage() }
        val latestOutgoingMessages = outgoingEnvelopes
            .mapNotNull { it.toChatMessageOrNull() }
        val latestMessage = (latestTimelineMessages + latestOutgoingMessages)
            .maxWithOrNull(compareBy<MatrixChatMessage> { it.timestampMillis }.thenBy { it.id })
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
        outgoingEnvelopes: List<OutgoingEnvelopeEntity>,
        bounds: TimelineWindowBounds? = null
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
            .filter { message -> bounds?.contains(message) ?: true }

        return (timelineMessages + outgoingMessages)
            .sortedWith(compareBy<MatrixChatMessage> { it.timestampMillis }.thenBy { it.id })
    }

    private fun TimelineWindowBounds.contains(message: MatrixChatMessage): Boolean {
        val oldest = oldestAnchor
        val newest = newestAnchor
        if (oldest != null && message.isOlderThan(oldest)) {
            return false
        }
        if (newest != null && message.isNewerThan(newest)) {
            return false
        }
        return true
    }

    private fun MatrixChatMessage.isOlderThan(anchor: TimelineWindowAnchor): Boolean {
        return timestampMillis < anchor.timestampMillis ||
            (timestampMillis == anchor.timestampMillis && id < anchor.id)
    }

    private fun MatrixChatMessage.isNewerThan(anchor: TimelineWindowAnchor): Boolean {
        return timestampMillis > anchor.timestampMillis ||
            (timestampMillis == anchor.timestampMillis && id > anchor.id)
    }

    private fun OutgoingEnvelopeEntity.toChatMessageOrNull(): MatrixChatMessage? {
        val state = transportState.toOutgoingTransportState()

        return when (kind) {
            OutgoingEnvelopeKind.TEXT.name -> MatrixChatMessage(
                id = "outgoing:$id",
                eventId = eventId,
                transactionId = transactionId,
                sender = userId,
                body = body,
                timestampMillis = createdAtMillis,
                isOwn = true,
                contentType = MatrixMessageContentType.TEXT,
                deliveryState = state.toOutgoingDeliveryState(),
                replyInfo = replyInfoOrNull(),
                forwardedFrom = forwardedFrom,
                outgoingEnvelopeId = id,
                canRetryOutgoingEnvelope = state == OutgoingTransportState.FAILED,
                canDiscardOutgoingEnvelope = state == OutgoingTransportState.FAILED
            )
            OutgoingEnvelopeKind.IMAGE.name -> {
                val localPath = imageLocalPath?.takeIf { it.isNotBlank() }
                val sourceJson = imageSourceJson?.takeIf { it.isNotBlank() }
                    ?: localPath?.let { "local:$it" }
                    ?: return null
                val zynaAttributes = ZynaHtmlCodec.decodeAttributesJson(zynaAttributesJson)
                MatrixChatMessage(
                    id = "outgoing:$id",
                    eventId = eventId,
                    transactionId = transactionId,
                    sender = userId,
                    body = imageCaption ?: "Photo",
                    timestampMillis = createdAtMillis,
                    isOwn = true,
                    contentType = MatrixMessageContentType.IMAGE,
                    imageInfo = MatrixImageInfo(
                        sourceJson = sourceJson,
                        thumbnailSourceJson = imageThumbnailSourceJson,
                        width = imageWidth,
                        height = imageHeight,
                        caption = imageCaption,
                        mimeType = imageMimeType,
                        blurhash = imageBlurhash,
                        localPath = localPath
                    ),
                    forwardedFrom = zynaAttributes.forwardedFrom,
                    zynaAttributes = zynaAttributes,
                    deliveryState = state.toOutgoingDeliveryState(),
                    outgoingEnvelopeId = id,
                    canRetryOutgoingEnvelope = state == OutgoingTransportState.FAILED,
                    canDiscardOutgoingEnvelope = state == OutgoingTransportState.FAILED
                )
            }
            else -> null
        }
    }

    private suspend fun markCachedMessageRedacted(
        userId: String,
        roomId: String,
        ids: List<String>,
        updatedAtMillis: Long
    ) {
        if (ids.isEmpty()) return

        messageDao.updateMessagesByIds(
            userId = userId,
            roomId = roomId,
            ids = ids,
            body = REDACTED_MESSAGE_BODY,
            contentType = MatrixMessageContentType.REDACTED.name,
            deliveryState = MatrixMessageDeliveryState.SENT.name,
            updatedAtMillis = updatedAtMillis
        )
    }

    private suspend fun restoreCachedMessageFromRedaction(
        envelope: OutgoingEnvelopeEntity,
        updatedAtMillis: Long
    ) {
        val ids = listOfNotNull(envelope.targetEventId, envelope.targetTransactionId)
        if (ids.isEmpty()) return

        messageDao.updateMessagesByIds(
            userId = envelope.userId,
            roomId = envelope.roomId,
            ids = ids,
            body = envelope.targetBody ?: "",
            contentType = envelope.targetContentType ?: MatrixMessageContentType.TEXT.name,
            deliveryState = MatrixMessageDeliveryState.SENT.name,
            updatedAtMillis = updatedAtMillis
        )
    }

    private fun MatrixChatMessage.previewSenderName(): String? {
        return if (isOwn) {
            OWN_MESSAGE_PREVIEW_SENDER
        } else {
            senderDisplayName?.takeIf { it.isNotBlank() }
                ?: sender.takeIf { it.isNotBlank() }
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

    private fun deleteLocalFiles(paths: List<String>) {
        paths.asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { path -> runCatching { File(path).delete() } }
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
            replyInfo = replyInfoOrNull(),
            forwardedFrom = forwardedFrom,
            createdAtMillis = createdAtMillis,
            failureMessage = failureMessage
        )
    }

    private fun OutgoingEnvelopeEntity.toOutgoingImageEnvelopeOrNull(): OutgoingImageEnvelope? {
        if (kind != OutgoingEnvelopeKind.IMAGE.name) return null
        val localPath = imageLocalPath?.takeIf { it.isNotBlank() }
        val uploadedImageJson = imageUploadedJson?.takeIf { it.isNotBlank() }
        val sourceJson = imageSourceJson?.takeIf { it.isNotBlank() }
        if (localPath == null && uploadedImageJson == null && sourceJson == null) return null

        return OutgoingImageEnvelope(
            userId = userId,
            roomId = roomId,
            id = id,
            transportState = transportState.toOutgoingTransportState(),
            transactionId = transactionId,
            eventId = eventId,
            localPath = localPath,
            mimeType = imageMimeType?.takeIf { it.isNotBlank() } ?: "image/jpeg",
            width = imageWidth?.takeIf { it > 0 } ?: 1,
            height = imageHeight?.takeIf { it > 0 } ?: 1,
            sizeBytes = imageSizeBytes?.takeIf { it > 0L } ?: 0L,
            thumbnailLocalPath = imageThumbnailLocalPath?.takeIf { it.isNotBlank() },
            thumbnailMimeType = imageThumbnailMimeType?.takeIf { it.isNotBlank() },
            thumbnailWidth = imageThumbnailWidth?.takeIf { it > 0 },
            thumbnailHeight = imageThumbnailHeight?.takeIf { it > 0 },
            thumbnailSizeBytes = imageThumbnailSizeBytes?.takeIf { it > 0L },
            caption = imageCaption,
            zynaAttributesJson = zynaAttributesJson,
            sourceJson = sourceJson,
            thumbnailSourceJson = imageThumbnailSourceJson?.takeIf { it.isNotBlank() },
            blurhash = imageBlurhash?.takeIf { it.isNotBlank() },
            uploadedImageJson = uploadedImageJson,
            createdAtMillis = createdAtMillis,
            failureMessage = failureMessage
        )
    }

    private fun OutgoingEnvelopeEntity.toOutgoingRedactionEnvelopeOrNull(): OutgoingRedactionEnvelope? {
        if (kind != OutgoingEnvelopeKind.REDACTION.name) return null
        val redactionTargetEventId = targetEventId ?: return null

        return OutgoingRedactionEnvelope(
            userId = userId,
            roomId = roomId,
            id = id,
            transportState = transportState.toOutgoingTransportState(),
            transactionId = transactionId,
            redactionEventId = eventId,
            targetEventId = redactionTargetEventId,
            targetTransactionId = targetTransactionId,
            targetBody = targetBody.orEmpty(),
            targetContentType = targetContentType ?: MatrixMessageContentType.TEXT.name,
            createdAtMillis = createdAtMillis,
            failureMessage = failureMessage
        )
    }

    private fun CachedTimelineMessageEntity.toOutgoingEditEnvelopeOrNull(): OutgoingEditEnvelope? {
        val eventId = eventId?.takeIf { it.isNotBlank() } ?: return null
        val editTransactionId = editTransactionId?.takeIf { it.isNotBlank() } ?: return null
        val pendingBody = pendingEditBody?.takeIf { it.isNotBlank() } ?: return null
        if (!isEditPending || contentType != MatrixMessageContentType.TEXT.name) {
            return null
        }

        return OutgoingEditEnvelope(
            userId = userId,
            roomId = roomId,
            eventId = eventId,
            transactionId = editTransactionId,
            body = pendingBody,
            createdAtMillis = updatedAtMillis
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

    private fun CachedTimelineMessageEntity.toTimelineWindowAnchor(): TimelineWindowAnchor {
        return TimelineWindowAnchor(
            timestampMillis = timestampMillis,
            id = id
        )
    }

    private fun List<CachedTimelineMessageEntity>.dedupeTimelineWindowEntities(): List<CachedTimelineMessageEntity> {
        val seenEventIds = mutableSetOf<String>()
        val seenTransactionIds = mutableSetOf<String>()
        val seenIds = mutableSetOf<String>()
        return sortedWith(
            compareBy<CachedTimelineMessageEntity> { it.timestampMillis }.thenBy { it.id }
        )
            .filter { message ->
                val eventId = message.eventId?.takeIf { it.isNotBlank() }
                if (eventId != null) {
                    return@filter seenEventIds.add(eventId)
                }
                val transactionId = message.transactionId?.takeIf { it.isNotBlank() }
                if (transactionId != null) {
                    return@filter seenTransactionIds.add(transactionId)
                }
                seenIds.add(message.id)
            }
    }

    private fun CachedTimelineMessageEntity.replyInfoOrNull(): MatrixReplyInfo? {
        val eventId = replyEventId?.takeIf { it.isNotBlank() } ?: return null
        return MatrixReplyInfo(
            eventId = eventId,
            senderId = replySenderId.orEmpty(),
            senderDisplayName = replySenderDisplayName?.takeIf { it.isNotBlank() },
            body = replyBody.orEmpty()
        )
    }

    private fun CachedTimelineMessageEntity.imageInfoOrNull(): MatrixImageInfo? {
        val sourceJson = imageSourceJson?.takeIf { it.isNotBlank() } ?: return null
        return MatrixImageInfo(
            sourceJson = sourceJson,
            thumbnailSourceJson = imageThumbnailSourceJson?.takeIf { it.isNotBlank() },
            width = imageWidth,
            height = imageHeight,
            caption = imageCaption.normalizedMessageCaption(),
            mimeType = imageMimeType?.takeIf { it.isNotBlank() },
            blurhash = imageBlurhash?.takeIf { it.isNotBlank() }
        )
    }

    private fun OutgoingEnvelopeEntity.replyInfoOrNull(): MatrixReplyInfo? {
        val eventId = replyEventId?.takeIf { it.isNotBlank() } ?: return null
        return MatrixReplyInfo(
            eventId = eventId,
            senderId = replySenderId.orEmpty(),
            senderDisplayName = replySenderDisplayName?.takeIf { it.isNotBlank() },
            body = replyBody.orEmpty()
        )
    }

    private fun MatrixChatMessage.identityIds(): List<String> {
        return listOfNotNull(id, eventId, transactionId)
    }

    private suspend fun deleteSafeEventDuplicates(
        messages: List<CachedTimelineMessageEntity>
    ) {
        messages
            .filter { it.eventId != null }
            .distinctBy { it.eventId }
            .forEach { message ->
                val eventId = message.eventId ?: return@forEach
                messageDao.deleteSafeEventDuplicates(
                    userId = message.userId,
                    roomId = message.roomId,
                    keepId = message.id,
                    eventId = eventId,
                    sender = message.sender,
                    timestampMillis = message.timestampMillis,
                    timestampToleranceMillis = DEDUPE_TIMESTAMP_TOLERANCE_MS,
                    contentType = message.contentType,
                    body = message.body
                )
            }
    }

    private suspend fun existingMessagesMatching(
        userId: String,
        roomId: String,
        incomingMessages: List<CachedTimelineMessageEntity>
    ): List<CachedTimelineMessageEntity> {
        val identityIds = incomingMessages
            .flatMap { it.identityIds() }
            .distinct()
        if (identityIds.isEmpty()) {
            return emptyList()
        }

        return identityIds
            .chunked(REDACTION_ID_QUERY_CHUNK_SIZE)
            .flatMap { ids ->
                messageDao.messagesMatchingIds(
                    userId = userId,
                    roomId = roomId,
                    ids = ids
                )
            }
            .distinctBy { it.id }
    }

    private suspend fun replyTargetsMatching(
        userId: String,
        roomId: String,
        incomingMessages: List<CachedTimelineMessageEntity>
    ): List<CachedTimelineMessageEntity> {
        val replyEventIds = incomingMessages
            .mapNotNull { it.replyEventId }
            .distinct()
        if (replyEventIds.isEmpty()) {
            return emptyList()
        }

        return replyEventIds
            .chunked(REDACTION_ID_QUERY_CHUNK_SIZE)
            .flatMap { ids ->
                messageDao.messagesMatchingIds(
                    userId = userId,
                    roomId = roomId,
                    ids = ids
                )
            }
            .distinctBy { it.id }
    }

    private fun List<CachedTimelineMessageEntity>.redactionsByIdentity(): Map<String, CachedTimelineMessageEntity> {
        return filter { it.isRedacted() }
            .flatMap { redacted -> redacted.identityIds().map { identity -> identity to redacted } }
            .toMap()
    }

    private fun List<CachedTimelineMessageEntity>.cachedMessagesByIdentity(): Map<String, CachedTimelineMessageEntity> {
        return flatMap { message ->
            message.identityIds().map { identity -> identity to message }
        }.toMap()
    }

    private fun List<CachedTimelineMessageEntity>.cachedReplyInfosByIdentity(): Map<String, MatrixReplyInfo> {
        return mapNotNull { message ->
            message.replyInfoOrNull()
                ?.takeIf { it.isInformative() }
                ?.let { replyInfo -> message.identityIds().map { identity -> identity to replyInfo } }
        }
            .flatten()
            .toMap()
    }

    private fun List<OutgoingEnvelopeEntity>.outgoingReplyInfosByIdentity(): Map<String, MatrixReplyInfo> {
        return mapNotNull { envelope ->
            envelope.replyInfoOrNull()
                ?.takeIf { it.isInformative() }
                ?.let { replyInfo -> envelope.identityIds().map { identity -> identity to replyInfo } }
        }
            .flatten()
            .toMap()
    }

    private fun List<CachedTimelineMessageEntity>.replyTargetInfosByIdentity(): Map<String, MatrixReplyInfo> {
        return flatMap { target ->
            target.identityIds().map { identity ->
                identity to target.toReplyTargetInfo(replyEventId = identity)
            }
        }.toMap()
    }

    private fun List<CachedTimelineMessageEntity>.preserveExistingTimelineState(
        existingMessagesByIdentity: Map<String, CachedTimelineMessageEntity>
    ): List<CachedTimelineMessageEntity> {
        if (existingMessagesByIdentity.isEmpty()) {
            return this
        }

        return map { incoming ->
            val existing = incoming.identityIds()
                .firstNotNullOfOrNull { identity -> existingMessagesByIdentity[identity] }
                ?: return@map incoming
            incoming.copy(
                body = if (existing.isEdited && !incoming.isEdited) existing.body else incoming.body,
                eventId = incoming.eventId ?: existing.eventId,
                transactionId = incoming.transactionId ?: existing.transactionId,
                senderDisplayName = incoming.senderDisplayName ?: existing.senderDisplayName,
                imageSourceJson = incoming.imageSourceJson ?: existing.imageSourceJson,
                imageThumbnailSourceJson = incoming.imageThumbnailSourceJson
                    ?: existing.imageThumbnailSourceJson,
                imageWidth = incoming.imageWidth ?: existing.imageWidth,
                imageHeight = incoming.imageHeight ?: existing.imageHeight,
                imageCaption = incoming.imageCaption ?: existing.imageCaption.normalizedMessageCaption(),
                imageMimeType = incoming.imageMimeType ?: existing.imageMimeType,
                imageBlurhash = incoming.imageBlurhash ?: existing.imageBlurhash,
                replyEventId = incoming.replyEventId ?: existing.replyEventId,
                replySenderId = incoming.replySenderId ?: existing.replySenderId,
                replySenderDisplayName = incoming.replySenderDisplayName
                    ?: existing.replySenderDisplayName,
                replyBody = incoming.replyBody ?: existing.replyBody,
                forwardedFrom = incoming.forwardedFrom ?: existing.forwardedFrom,
                zynaAttributesJson = incoming.zynaAttributesJson ?: existing.zynaAttributesJson,
                isEdited = incoming.isEdited || existing.isEdited,
                isEditPending = if (incoming.isEdited) false else existing.isEditPending,
                isEditFailed = if (incoming.isEdited) false else existing.isEditFailed,
                latestEditEventId = incoming.latestEditEventId ?: existing.latestEditEventId,
                editTransactionId = if (incoming.isEdited) null else existing.editTransactionId,
                pendingEditBody = if (incoming.isEdited) null else existing.pendingEditBody
            )
        }
    }

    private fun List<CachedTimelineMessageEntity>.enrichReplyInfos(
        existingReplyInfosByIdentity: Map<String, MatrixReplyInfo>,
        outgoingReplyInfosByIdentity: Map<String, MatrixReplyInfo>,
        replyTargetInfosByIdentity: Map<String, MatrixReplyInfo>
    ): List<CachedTimelineMessageEntity> {
        if (
            existingReplyInfosByIdentity.isEmpty() &&
            outgoingReplyInfosByIdentity.isEmpty() &&
            replyTargetInfosByIdentity.isEmpty()
        ) {
            return this
        }

        return map { message ->
            val replyEventId = message.replyEventId ?: return@map message
            if (message.replyInfoOrNull()?.isInformative() == true) {
                return@map message
            }

            val resolved = message.identityIds()
                .firstNotNullOfOrNull { identity ->
                    existingReplyInfosByIdentity[identity] ?: outgoingReplyInfosByIdentity[identity]
                }
                ?: replyTargetInfosByIdentity[replyEventId]?.copy(eventId = replyEventId)
                ?: return@map message

            message.copy(
                replySenderId = resolved.senderId,
                replySenderDisplayName = resolved.senderDisplayName,
                replyBody = resolved.body
            )
        }
    }

    private fun List<CachedTimelineMessageEntity>.preserveExistingRedactions(
        existingRedactionsByIdentity: Map<String, CachedTimelineMessageEntity>
    ): List<CachedTimelineMessageEntity> {
        if (existingRedactionsByIdentity.isEmpty()) {
            return this
        }

        return map { incoming ->
            if (incoming.isRedacted()) {
                incoming
            } else {
                val existingRedaction = incoming.identityIds()
                    .firstNotNullOfOrNull { identity -> existingRedactionsByIdentity[identity] }
                incoming.withPreservedRedaction(existingRedaction)
            }
        }
    }

    private fun CachedTimelineMessageEntity.withPreservedRedaction(
        existingRedaction: CachedTimelineMessageEntity?
    ): CachedTimelineMessageEntity {
        if (existingRedaction == null) {
            return this
        }

        return copy(
            eventId = eventId ?: existingRedaction.eventId,
            transactionId = transactionId ?: existingRedaction.transactionId,
            body = existingRedaction.body.takeIf { it.isNotBlank() } ?: REDACTED_MESSAGE_BODY,
            contentType = MatrixMessageContentType.REDACTED.name
        )
    }

    private fun CachedTimelineMessageEntity.isRedacted(): Boolean {
        return contentType == MatrixMessageContentType.REDACTED.name
    }

    private fun CachedTimelineMessageEntity.toReplyTargetInfo(replyEventId: String): MatrixReplyInfo {
        return MatrixReplyInfo(
            eventId = replyEventId,
            senderId = sender,
            senderDisplayName = if (isOwn) OWN_MESSAGE_PREVIEW_SENDER else null,
            body = if (isRedacted()) REDACTED_MESSAGE_BODY else body
        )
    }

    private fun OutgoingEnvelopeEntity.identityIds(): List<String> {
        return listOfNotNull(id, eventId, transactionId, "outgoing:$id")
    }

    private fun MatrixReplyInfo.isInformative(): Boolean {
        return senderId.isNotBlank() ||
            senderDisplayName?.isNotBlank() == true ||
            body.isNotBlank()
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
        const val LOCAL_MESSAGE_ID_PATTERN = "$LOCAL_MESSAGE_ID_PREFIX%"
        const val OWN_MESSAGE_PREVIEW_SENDER = "You"
        const val ROOM_PREVIEW_CANDIDATE_LIMIT = 64
        const val REDACTION_ID_QUERY_CHUNK_SIZE = 250
        const val DEDUPE_TIMESTAMP_TOLERANCE_MS = 50L
        const val REDACTED_MESSAGE_BODY = "Deleted message"
    }
}
