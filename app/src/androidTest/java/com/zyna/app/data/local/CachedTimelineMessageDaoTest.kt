package com.zyna.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CachedTimelineMessageDaoTest {
    private lateinit var database: ZynaDatabase
    private lateinit var dao: CachedTimelineMessageDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ZynaDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.cachedTimelineMessageDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun systemAndCallRows_participateInTimelineButCannotDisplacePreview() = runBlocking {
        val realMessage = timelineEntity(
            id = "\$message",
            timestampMillis = 1_000L,
            contentType = "TEXT",
            body = "Latest real message"
        )
        val semanticRows = (1..20).map { index ->
            timelineEntity(
                id = "\$system-$index",
                timestampMillis = 1_000L + index,
                contentType = if (index % 2 == 0) "SYSTEM_EVENT" else "MATRIX_RTC_CALL"
            )
        }
        dao.upsertMessages(listOf(realMessage) + semanticRows)

        assertEquals(
            listOf(realMessage.id),
            dao.latestRoomPreviewMessages(
                userId = USER_ID,
                roomId = ROOM_ID,
                localIdPattern = "local:%",
                limit = 5
            ).map { it.id }
        )
        assertEquals(
            semanticRows.takeLast(5).map { it.id },
            dao.latestRoomMessagesWindow(
                userId = USER_ID,
                roomId = ROOM_ID,
                localIdPattern = "local:%",
                limit = 5
            ).map { it.id }
        )
    }

    @Test
    fun callProjectionUpdate_repairsRowAndInvalidatesTimelineFlow() = runBlocking {
        val eventId = "\$call"
        dao.upsertMessages(
            listOf(
                timelineEntity(
                    id = eventId,
                    timestampMillis = 2_000L,
                    contentType = "UNSUPPORTED",
                    body = "stale"
                )
            )
        )
        val projectedJson = """{"version":1,"kind":"matrix_rtc_call"}"""
        val observed = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000L) {
                dao.observeLatestRoomMessagesWindow(
                    userId = USER_ID,
                    roomId = ROOM_ID,
                    localIdPattern = "local:%",
                    limit = 10
                ).first { rows ->
                    rows.singleOrNull()?.timelineDetailsJson == projectedJson
                }.single()
            }
        }

        assertEquals(
            1,
            dao.updateMatrixRtcCallTimelineProjection(
                userId = USER_ID,
                roomId = ROOM_ID,
                eventId = eventId,
                timelineDetailsJson = projectedJson,
                updatedAtMillis = 3_000L
            )
        )

        val row = observed.await()
        assertEquals("", row.body)
        assertEquals("MATRIX_RTC_CALL", row.contentType)
        assertEquals(projectedJson, row.timelineDetailsJson)
    }

    private fun timelineEntity(
        id: String,
        timestampMillis: Long,
        contentType: String,
        body: String = ""
    ): CachedTimelineMessageEntity {
        return CachedTimelineMessageEntity(
            userId = USER_ID,
            roomId = ROOM_ID,
            id = id,
            eventId = id,
            transactionId = null,
            timelineIndex = 0,
            sender = "@alice:example.org",
            senderDisplayName = "Alice",
            body = body,
            timestampMillis = timestampMillis,
            isOwn = false,
            contentType = contentType,
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
            timelineDetailsJson = null,
            isEdited = false,
            isEditPending = false,
            isEditFailed = false,
            latestEditEventId = null,
            editTransactionId = null,
            pendingEditBody = null,
            reactionsJson = "[]",
            updatedAtMillis = timestampMillis
        )
    }

    private companion object {
        const val USER_ID = "@me:example.org"
        const val ROOM_ID = "!room:example.org"
    }
}
