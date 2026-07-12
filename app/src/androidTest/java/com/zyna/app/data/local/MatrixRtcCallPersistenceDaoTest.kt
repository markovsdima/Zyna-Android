package com.zyna.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallHistoryOutcome
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallNotificationType
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallTimelineMembership
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallTimelineNotification
import com.zyna.app.data.matrix.MatrixIncomingRtcCallNotification
import com.zyna.app.data.matrix.MatrixIncomingRtcCallNotificationKind
import com.zyna.app.data.matrix.MatrixRtcCallEventDetails
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MatrixRtcCallPersistenceDaoTest {
    private lateinit var database: ZynaDatabase
    private lateinit var repository: LocalCacheRepository
    private lateinit var callDao: MatrixRtcCallHistoryDao
    private lateinit var messageDao: CachedTimelineMessageDao

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, ZynaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LocalCacheRepository(database, context)
        callDao = database.matrixRtcCallHistoryDao()
        messageDao = database.cachedTimelineMessageDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun updateProjection_doesNotWriteWhenProjectedValuesAreUnchanged() = runBlocking {
        val call = callEntity(eventId = "\$call", updatedAtMillis = 10L)
        callDao.upsertCalls(listOf(call))

        assertEquals(
            0,
            callDao.updateCallProjection(
                userId = USER_ID,
                eventId = call.eventId,
                isDirect = call.isDirect,
                hasOwnJoin = call.hasOwnJoin,
                hasRemoteJoin = call.hasRemoteJoin,
                hasOwnLeave = call.hasOwnLeave,
                hasRemoteLeave = call.hasRemoteLeave,
                lastMembershipEventTimestampMillis = call.lastMembershipEventTimestampMillis,
                lastOwnLeaveTimestampMillis = call.lastOwnLeaveTimestampMillis,
                lastRemoteLeaveTimestampMillis = call.lastRemoteLeaveTimestampMillis,
                outcome = call.outcome,
                updatedAtMillis = 20L
            )
        )
        assertEquals(10L, callDao.recentCallsSnapshot(USER_ID, 1).single().updatedAtMillis)

        assertEquals(
            1,
            callDao.updateCallProjection(
                userId = USER_ID,
                eventId = call.eventId,
                isDirect = call.isDirect,
                hasOwnJoin = call.hasOwnJoin,
                hasRemoteJoin = call.hasRemoteJoin,
                hasOwnLeave = call.hasOwnLeave,
                hasRemoteLeave = call.hasRemoteLeave,
                lastMembershipEventTimestampMillis = call.lastMembershipEventTimestampMillis,
                lastOwnLeaveTimestampMillis = call.lastOwnLeaveTimestampMillis,
                lastRemoteLeaveTimestampMillis = call.lastRemoteLeaveTimestampMillis,
                outcome = MatrixRtcCallHistoryOutcome.MISSED.name,
                updatedAtMillis = 30L
            )
        )
        val updated = callDao.recentCallsSnapshot(USER_ID, 1).single()
        assertEquals(MatrixRtcCallHistoryOutcome.MISSED.name, updated.outcome)
        assertEquals(30L, updated.updatedAtMillis)
    }

    @Test
    fun legacyCall_isMaterializedOnceAndStableRefreshDoesNotRewriteProjection() = runBlocking {
        database.cachedRoomDao().upsertRooms(listOf(roomEntity(directUserId = REMOTE_USER_ID)))
        callDao.upsertCalls(
            listOf(
                callEntity(
                    eventId = "\$legacy-call",
                    isDirect = false,
                    updatedAtMillis = 10L
                )
            )
        )

        repository.refreshMatrixRtcCallHistory(USER_ID, limit = 100)

        val materialized = messageDao.roomMessageByEventId(
            userId = USER_ID,
            roomId = ROOM_ID,
            localIdPattern = "local:%",
            eventId = "\$legacy-call"
        )
        assertNotNull(materialized)
        assertEquals("MATRIX_RTC_CALL", materialized?.contentType)
        assertEquals(
            MatrixRtcCallHistoryOutcome.STARTED,
            MatrixTimelineDetailsCodec.decodeMatrixRtcCall(
                materialized?.timelineDetailsJson
            )?.outcome
        )
        val firstProjection = callDao.recentCallsSnapshot(USER_ID, 1).single()
        assertTrue(firstProjection.isDirect)

        delay(5L)
        repository.refreshMatrixRtcCallHistory(USER_ID, limit = 100)

        val stableProjection = callDao.recentCallsSnapshot(USER_ID, 1).single()
        assertEquals(firstProjection.updatedAtMillis, stableProjection.updatedAtMillis)
    }

    @Test
    fun historyRefresh_projectsOnlyExpiredOrDirectnessSensitiveCalls() = runBlocking {
        val now = System.currentTimeMillis()
        database.cachedRoomDao().upsertRooms(listOf(roomEntity(directUserId = REMOTE_USER_ID)))
        val futureCall = callEntity(
            eventId = "\$future-call",
            timestampMillis = now - 2_000L,
            expiresAtMillis = now + 60_000L,
            updatedAtMillis = 10L
        )
        val expiredCall = callEntity(
            eventId = "\$expired-call",
            timestampMillis = now - 3_000L,
            expiresAtMillis = now - 1_000L,
            updatedAtMillis = 20L
        )
        callDao.upsertCalls(listOf(futureCall, expiredCall))
        messageDao.upsertMessages(
            listOf(
                timelineEntity(futureCall),
                timelineEntity(expiredCall)
            )
        )

        repository.refreshMatrixRtcCallHistory(USER_ID, limit = 100)

        val calls = callDao.recentCallsSnapshot(USER_ID, 100).associateBy { it.eventId }
        assertEquals(10L, calls.getValue(futureCall.eventId).updatedAtMillis)
        assertEquals(MatrixRtcCallHistoryOutcome.STARTED.name, calls.getValue(futureCall.eventId).outcome)
        assertEquals(MatrixRtcCallHistoryOutcome.MISSED.name, calls.getValue(expiredCall.eventId).outcome)
        assertTrue(calls.getValue(expiredCall.eventId).updatedAtMillis > 20L)
    }

    @Test
    fun membershipUpdate_keepsCallAndTimelineProjectionsInSync() = runBlocking {
        val now = System.currentTimeMillis()
        val callEventId = "\$call-with-membership"
        database.cachedRoomDao().upsertRooms(listOf(roomEntity(directUserId = REMOTE_USER_ID)))
        repository.cacheMatrixRtcCallTimelineEvents(
            userId = USER_ID,
            notifications = listOf(
                MatrixRtcCallTimelineNotification(
                    eventId = callEventId,
                    roomId = ROOM_ID,
                    parentEventId = "\$call-parent",
                    senderId = REMOTE_USER_ID,
                    senderDisplayName = "Alice",
                    isOutgoing = false,
                    timestampMillis = now,
                    notificationType = MatrixRtcCallNotificationType.RING,
                    callIntent = "audio",
                    expiresAtMillis = now + 60_000L,
                    declinedBy = emptyList()
                )
            ),
            memberships = emptyList()
        )
        repository.cacheMatrixRtcCallTimelineEvents(
            userId = USER_ID,
            notifications = emptyList(),
            memberships = listOf(
                MatrixRtcCallTimelineMembership(
                    eventId = "\$own-membership",
                    roomId = ROOM_ID,
                    eventType = "org.matrix.msc3401.call.member",
                    stateKey = "membership",
                    senderId = USER_ID,
                    timestampMillis = now + 100L,
                    isLeave = false,
                    memberUserId = USER_ID,
                    deviceId = "DEVICE",
                    memberId = "member",
                    callIntent = "audio",
                    expiresAtMillis = now + 60_000L
                )
            )
        )

        val projectedCall = callDao.recentCallsSnapshot(USER_ID, 1).single()
        assertTrue(projectedCall.hasOwnJoin)
        assertEquals(MatrixRtcCallHistoryOutcome.ANSWERED.name, projectedCall.outcome)
        val timelineRow = messageDao.roomMessageByEventId(
            userId = USER_ID,
            roomId = ROOM_ID,
            localIdPattern = "local:%",
            eventId = callEventId
        )
        assertEquals(
            MatrixRtcCallHistoryOutcome.ANSWERED,
            MatrixTimelineDetailsCodec.decodeMatrixRtcCall(
                timelineRow?.timelineDetailsJson
            )?.outcome
        )
    }

    @Test
    fun projectionCandidateQueries_excludeStableAndNonDirectExpiredCalls() = runBlocking {
        val now = System.currentTimeMillis()
        database.cachedRoomDao().upsertRooms(
            listOf(
                roomEntity(roomId = ROOM_ID, directUserId = REMOTE_USER_ID),
                roomEntity(roomId = GROUP_ROOM_ID, directUserId = null)
            )
        )
        val directnessMismatch = callEntity(
            eventId = "\$mismatch",
            isDirect = false,
            expiresAtMillis = now + 60_000L
        )
        val stable = callEntity(
            eventId = "\$stable",
            isDirect = true,
            expiresAtMillis = now + 60_000L
        )
        val nonDirectExpired = callEntity(
            eventId = "\$group-expired",
            roomId = GROUP_ROOM_ID,
            isDirect = false,
            expiresAtMillis = now - 1_000L
        )
        callDao.upsertCalls(listOf(directnessMismatch, stable, nonDirectExpired))

        assertEquals(
            listOf(directnessMismatch.eventId),
            callDao.callsWithRoomDirectnessMismatch(USER_ID, 100).map { it.eventId }
        )
        assertFalse(
            callDao.expiredStartedRingCalls(USER_ID, now, 100)
                .any { it.eventId == nonDirectExpired.eventId }
        )
    }

    @Test
    fun fullTimelineThenSparsePush_preservesFullCallAndTimelineRows() = runBlocking {
        val fullNotification = fullNotification(
            eventId = "\$full-then-sparse",
            nowMillis = System.currentTimeMillis()
        )
        database.cachedRoomDao().upsertRooms(listOf(roomEntity(directUserId = REMOTE_USER_ID)))
        repository.cacheMatrixRtcCallTimelineEvents(
            userId = USER_ID,
            notifications = listOf(fullNotification),
            memberships = emptyList()
        )
        val callBeforeSparse = requireNotNull(
            callDao.callSnapshot(USER_ID, fullNotification.eventId)
        )
        val timelineBeforeSparse = requireNotNull(
            messageDao.roomMessageByEventId(
                userId = USER_ID,
                roomId = ROOM_ID,
                localIdPattern = "local:%",
                eventId = fullNotification.eventId
            )
        )

        delay(5L)
        repository.cacheIncomingMatrixRtcCallNotification(
            userId = USER_ID,
            notification = sparseNotification(fullNotification)
        )

        assertEquals(
            callBeforeSparse,
            callDao.callSnapshot(USER_ID, fullNotification.eventId)
        )
        assertEquals(
            timelineBeforeSparse,
            messageDao.roomMessageByEventId(
                userId = USER_ID,
                roomId = ROOM_ID,
                localIdPattern = "local:%",
                eventId = fullNotification.eventId
            )
        )
        assertFullNotificationPersisted(fullNotification)
    }

    @Test
    fun sparsePushThenFullTimeline_convergesToFullCallAndTimelineRows() = runBlocking {
        val fullNotification = fullNotification(
            eventId = "\$sparse-then-full",
            nowMillis = System.currentTimeMillis()
        )
        database.cachedRoomDao().upsertRooms(listOf(roomEntity(directUserId = REMOTE_USER_ID)))
        repository.cacheIncomingMatrixRtcCallNotification(
            userId = USER_ID,
            notification = sparseNotification(fullNotification)
        )

        delay(5L)
        repository.cacheMatrixRtcCallTimelineEvents(
            userId = USER_ID,
            notifications = listOf(fullNotification),
            memberships = emptyList()
        )

        assertFullNotificationPersisted(fullNotification)
    }

    private suspend fun assertFullNotificationPersisted(
        notification: MatrixRtcCallTimelineNotification
    ) {
        val call = requireNotNull(callDao.callSnapshot(USER_ID, notification.eventId))
        assertEquals(notification.parentEventId, call.parentEventId)
        assertEquals(notification.senderDisplayName, call.senderDisplayName)
        assertEquals(notification.timestampMillis, call.timestampMillis)
        assertEquals(notification.callIntent, call.callIntent)
        assertEquals(
            MatrixRtcCallHistoryProjection.encodeDeclinedBy(notification.declinedBy),
            call.declinedByJson
        )
        assertEquals(MatrixRtcCallHistoryOutcome.DECLINED.name, call.outcome)

        val timeline = requireNotNull(
            messageDao.roomMessageByEventId(
                userId = USER_ID,
                roomId = notification.roomId,
                localIdPattern = "local:%",
                eventId = notification.eventId
            )
        )
        assertEquals(notification.senderDisplayName, timeline.senderDisplayName)
        assertEquals(notification.timestampMillis, timeline.timestampMillis)
        assertEquals("MATRIX_RTC_CALL", timeline.contentType)
        assertEquals(
            MatrixRtcCallEventDetails(
                parentEventId = notification.parentEventId,
                callIntent = notification.callIntent,
                notificationType = notification.notificationType,
                expiresAtMillis = notification.expiresAtMillis,
                declinedBy = notification.declinedBy,
                outcome = MatrixRtcCallHistoryOutcome.DECLINED
            ),
            MatrixTimelineDetailsCodec.decodeMatrixRtcCall(timeline.timelineDetailsJson)
        )
    }

    private fun fullNotification(
        eventId: String,
        nowMillis: Long
    ): MatrixRtcCallTimelineNotification {
        return MatrixRtcCallTimelineNotification(
            eventId = eventId,
            roomId = ROOM_ID,
            parentEventId = "\$full-parent",
            senderId = REMOTE_USER_ID,
            senderDisplayName = "Alice",
            isOutgoing = false,
            timestampMillis = nowMillis - 5_000L,
            notificationType = MatrixRtcCallNotificationType.RING,
            callIntent = "m.video",
            expiresAtMillis = nowMillis + 120_000L,
            declinedBy = listOf("@decliner:example.org")
        )
    }

    private fun sparseNotification(
        fullNotification: MatrixRtcCallTimelineNotification
    ): MatrixIncomingRtcCallNotification {
        return MatrixIncomingRtcCallNotification(
            eventId = fullNotification.eventId,
            roomId = fullNotification.roomId,
            senderId = fullNotification.senderId,
            kind = MatrixIncomingRtcCallNotificationKind.RING,
            isAudioCall = true,
            expiresAtMillis = requireNotNull(fullNotification.expiresAtMillis)
        )
    }

    private fun roomEntity(
        roomId: String = ROOM_ID,
        directUserId: String?
    ): CachedRoomEntity {
        return CachedRoomEntity(
            userId = USER_ID,
            id = roomId,
            displayName = "Room",
            avatarUrl = null,
            directUserId = directUserId,
            lastMessageText = null,
            lastMessageSenderName = null,
            lastMessageAtMillis = null,
            lastOwnMessageStatus = null,
            unreadCount = 0,
            unreadMentionCount = 0,
            isMarkedUnread = false,
            updatedAtMillis = 1L
        )
    }

    private fun callEntity(
        eventId: String,
        roomId: String = ROOM_ID,
        timestampMillis: Long = 1_000L,
        expiresAtMillis: Long? = Long.MAX_VALUE,
        isDirect: Boolean = true,
        updatedAtMillis: Long = 1L
    ): MatrixRtcCallEntity {
        return MatrixRtcCallEntity(
            userId = USER_ID,
            eventId = eventId,
            roomId = roomId,
            parentEventId = "\$parent",
            senderId = REMOTE_USER_ID,
            senderDisplayName = "Alice",
            isOutgoing = false,
            timestampMillis = timestampMillis,
            notificationType = MatrixRtcCallNotificationType.RING.name,
            callIntent = "audio",
            expiresAtMillis = expiresAtMillis,
            declinedByJson = "[]",
            isDirect = isDirect,
            hasOwnJoin = false,
            hasRemoteJoin = false,
            hasOwnLeave = false,
            hasRemoteLeave = false,
            lastMembershipEventTimestampMillis = null,
            lastOwnLeaveTimestampMillis = null,
            lastRemoteLeaveTimestampMillis = null,
            outcome = MatrixRtcCallHistoryOutcome.STARTED.name,
            updatedAtMillis = updatedAtMillis
        )
    }

    private fun timelineEntity(call: MatrixRtcCallEntity): CachedTimelineMessageEntity {
        val timelineDetailsJson = MatrixTimelineDetailsCodec.encodeMatrixRtcCall(
            MatrixRtcCallEventDetails(
                parentEventId = call.parentEventId,
                callIntent = call.callIntent,
                notificationType = MatrixRtcCallNotificationType.RING,
                expiresAtMillis = call.expiresAtMillis,
                declinedBy = emptyList(),
                outcome = MatrixRtcCallHistoryOutcome.STARTED
            )
        )
        return CachedTimelineMessageEntity(
            userId = call.userId,
            roomId = call.roomId,
            id = call.eventId,
            eventId = call.eventId,
            transactionId = null,
            timelineIndex = 0,
            sender = call.senderId,
            senderDisplayName = call.senderDisplayName,
            body = "",
            timestampMillis = call.timestampMillis,
            isOwn = call.isOutgoing,
            contentType = "MATRIX_RTC_CALL",
            imageSourceJson = null,
            imageThumbnailSourceJson = null,
            imageWidth = null,
            imageHeight = null,
            imageCaption = null,
            imageMimeType = null,
            imageBlurhash = null,
            audioSourceJson = null,
            audioFilename = null,
            audioCaption = null,
            audioMimeType = null,
            audioSizeBytes = null,
            audioDurationMillis = null,
            audioWaveform = null,
            audioIsVoice = false,
            deliveryState = "SENT",
            replyEventId = null,
            replySenderId = null,
            replySenderDisplayName = null,
            replyBody = null,
            forwardedFrom = null,
            zynaAttributesJson = null,
            timelineDetailsJson = timelineDetailsJson,
            isEdited = false,
            isEditPending = false,
            isEditFailed = false,
            latestEditEventId = null,
            editTransactionId = null,
            pendingEditBody = null,
            reactionsJson = "[]",
            updatedAtMillis = call.updatedAtMillis
        )
    }

    private companion object {
        const val USER_ID = "@me:example.org"
        const val REMOTE_USER_ID = "@alice:example.org"
        const val ROOM_ID = "!direct:example.org"
        const val GROUP_ROOM_ID = "!group:example.org"
    }
}
