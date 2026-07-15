package com.zyna.app.ui.chat

import com.zyna.app.data.matrix.MatrixEditTarget
import com.zyna.app.data.matrix.MatrixForwardImageItem
import com.zyna.app.data.matrix.MatrixForwardTarget
import com.zyna.app.data.matrix.MatrixReplyInfo
import com.zyna.app.data.messaging.CaptionMode
import com.zyna.app.data.messaging.CaptionPlacement
import com.zyna.app.data.messaging.MediaGroupLayoutOverride
import com.zyna.app.data.messaging.ZynaMessageAttributes
import com.zyna.app.data.outgoing.OutgoingPhotoDraft
import com.zyna.app.data.outgoing.OutgoingPhotoDraftItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatComposerSendTest {
    @Test
    fun blankOrBusySend_isRejectedBeforeIdentifiersAreAllocated() {
        val recorder = RecordingSendDriver()
        val store = ChatComposerStore(recorder.driver)

        assertNull(store.createSendRequest(TARGET, "   ", isSending = false))
        assertNull(store.createSendRequest(TARGET, "Message", isSending = true))

        assertEquals(0, recorder.idCallCount)
        assertEquals(0, recorder.transactionCallCount)
    }

    @Test
    fun textRequest_trimsBodyAndCarriesReplyTarget() {
        val recorder = RecordingSendDriver()
        val store = ChatComposerStore(recorder.driver)
        store.selectReply(REPLY)

        val request = store.createSendRequest(
            target = TARGET,
            body = "  Hello  ",
            isSending = false
        )

        assertEquals(
            ChatComposerSendRequest.Text(
                target = TARGET,
                envelopeId = "text:id-1",
                transactionId = "transaction-1",
                body = "Hello",
                replyInfo = REPLY,
                forwardedFrom = null
            ),
            request
        )
    }

    @Test
    fun editRequest_takesPrecedenceOverReplyTarget() {
        val recorder = RecordingSendDriver()
        val store = ChatComposerStore(recorder.driver)
        store.selectReply(REPLY)
        store.selectEdit(EDIT)

        val request = store.createSendRequest(
            target = TARGET,
            body = "  Updated  ",
            isSending = false
        )

        assertEquals(
            ChatComposerSendRequest.Edit(
                target = TARGET,
                transactionId = "transaction-1",
                editTarget = EDIT,
                body = "Updated"
            ),
            request
        )
        assertEquals(1, recorder.idCallCount)
    }

    @Test
    fun textForward_usesForwardBodyAndSuppressesOtherTargets() {
        val recorder = RecordingSendDriver()
        val store = ChatComposerStore(recorder.driver)
        store.selectReply(REPLY)
        store.enterRoom(TEXT_FORWARD)

        val request = store.createSendRequest(
            target = TARGET,
            body = "Ignored composer body",
            isSending = false
        )

        assertEquals(
            ChatComposerSendRequest.Text(
                target = TARGET,
                envelopeId = "text:id-1",
                transactionId = "transaction-1",
                body = "Forwarded body",
                replyInfo = null,
                forwardedFrom = "Alice"
            ),
            request
        )
    }

    @Test
    fun imageForward_isAcceptedWithoutTextBody() {
        val recorder = RecordingSendDriver()
        val store = ChatComposerStore(recorder.driver)
        val forward = MatrixForwardTarget(
            body = "",
            forwardedFrom = "Alice",
            imageItems = listOf(IMAGE_A)
        )
        store.enterRoom(forward)

        val request = store.createSendRequest(
            target = TARGET,
            body = "",
            isSending = false
        )

        assertEquals(ChatComposerSendRequest.ForwardImages(TARGET, forward), request)
        assertEquals(1, recorder.idCallCount)
        assertEquals(1, recorder.transactionCallCount)
    }

    @Test
    fun photoRequest_filtersInvalidItemsAndReservesGroupId() {
        val recorder = RecordingSendDriver()
        val store = ChatComposerStore(recorder.driver)
        val draft = photoDraft(
            items = listOf(PHOTO_A.copy(localPath = ""), PHOTO_A)
        )

        val request = store.createPhotoSendRequest(
            target = TARGET,
            draft = draft,
            isSending = false
        )

        assertEquals(
            ChatComposerSendRequest.Photos(
                target = TARGET,
                groupId = "photo-group:id-1",
                draft = draft.copy(items = listOf(PHOTO_A))
            ),
            request
        )
        assertEquals(1, recorder.idCallCount)
        assertEquals(0, recorder.transactionCallCount)
    }

    @Test
    fun emptyOrBusyPhotoRequest_isRejectedBeforeGroupIdIsAllocated() {
        val recorder = RecordingSendDriver()
        val store = ChatComposerStore(recorder.driver)

        assertNull(
            store.createPhotoSendRequest(
                target = TARGET,
                draft = photoDraft(items = listOf(PHOTO_A.copy(localPath = ""))),
                isSending = false
            )
        )
        assertNull(
            store.createPhotoSendRequest(
                target = TARGET,
                draft = photoDraft(items = listOf(PHOTO_A)),
                isSending = true
            )
        )

        assertEquals(0, recorder.idCallCount)
        assertEquals(0, recorder.transactionCallCount)
    }

    @Test
    fun enqueueText_createsDurableEnvelopeBeforeKickingOutbox() = runBlocking {
        val recorder = RecordingSendDriver()
        val store = ChatComposerStore(recorder.driver)
        val request = ChatComposerSendRequest.Text(
            target = TARGET,
            envelopeId = "text:envelope",
            transactionId = "transaction",
            body = "Hello",
            replyInfo = REPLY,
            forwardedFrom = null
        )

        store.enqueue(request)

        assertEquals(
            listOf(
                TextEnvelope(
                    target = TARGET,
                    envelopeId = "text:envelope",
                    transactionId = "transaction",
                    body = "Hello",
                    replyInfo = REPLY,
                    forwardedFrom = null
                )
            ),
            recorder.textEnvelopes
        )
        assertEquals(listOf("text", "kick:new-envelope:text:envelope"), recorder.events)
    }

    @Test
    fun enqueueEdit_kicksOutboxOnlyWhenEditWasPrepared() = runBlocking {
        val preparedRecorder = RecordingSendDriver(didPrepareEdit = true)
        val rejectedRecorder = RecordingSendDriver(didPrepareEdit = false)
        val request = ChatComposerSendRequest.Edit(
            target = TARGET,
            transactionId = "transaction",
            editTarget = EDIT,
            body = "Updated"
        )

        ChatComposerStore(preparedRecorder.driver).enqueue(request)
        ChatComposerStore(rejectedRecorder.driver).enqueue(request)

        val expectedEdit = PreparedEdit(TARGET, EDIT, "Updated", "transaction")
        assertEquals(listOf(expectedEdit), preparedRecorder.preparedEdits)
        assertEquals(listOf(expectedEdit), rejectedRecorder.preparedEdits)
        assertEquals(listOf("edit", "kick:new-edit:null"), preparedRecorder.events)
        assertEquals(listOf("edit"), rejectedRecorder.events)
    }

    @Test
    fun enqueueImageForward_writesGroupMetadataBeforeSingleKick() = runBlocking {
        val recorder = RecordingSendDriver()
        val store = ChatComposerStore(recorder.driver)
        val layout = MediaGroupLayoutOverride(primarySplitPermille = 620)
        val forward = MatrixForwardTarget(
            body = "",
            forwardedFrom = "Alice",
            caption = "Caption",
            imageItems = listOf(IMAGE_A, IMAGE_B),
            captionPlacement = CaptionPlacement.TOP,
            layoutOverride = layout
        )

        store.enqueue(ChatComposerSendRequest.ForwardImages(TARGET, forward))

        assertEquals(2, recorder.imageEnvelopes.size)
        recorder.imageEnvelopes.forEachIndexed { index, envelope ->
            assertEquals(TARGET, envelope.target)
            assertEquals("image:id-${index + 2}", envelope.envelopeId)
            assertEquals("transaction-${index + 1}", envelope.transactionId)
            assertEquals(forward.imageItems[index], envelope.image)
            assertEquals("Caption", envelope.caption)
            assertEquals("Alice", envelope.attributes.forwardedFrom)
            assertEquals("forwarded-photo-group:id-1", envelope.attributes.mediaGroup?.id)
            assertEquals(index, envelope.attributes.mediaGroup?.index)
            assertEquals(2, envelope.attributes.mediaGroup?.total)
            assertEquals(CaptionMode.REPLICATED, envelope.attributes.mediaGroup?.captionMode)
            assertEquals(CaptionPlacement.TOP, envelope.attributes.mediaGroup?.captionPlacement)
            assertEquals(layout, envelope.attributes.mediaGroup?.layoutOverride)
        }
        assertEquals(
            listOf("image", "image", "kick:new-forwarded-images:null"),
            recorder.events
        )
    }

    @Test
    fun enqueueSingleDefaultImage_doesNotWriteGroupMetadata() = runBlocking {
        val recorder = RecordingSendDriver()
        val store = ChatComposerStore(recorder.driver)
        val forward = MatrixForwardTarget(
            body = "",
            forwardedFrom = "Alice",
            imageItems = listOf(IMAGE_A)
        )

        store.enqueue(ChatComposerSendRequest.ForwardImages(TARGET, forward))

        val envelope = recorder.imageEnvelopes.single()
        assertEquals("Alice", envelope.attributes.forwardedFrom)
        assertNull(envelope.attributes.mediaGroup)
        assertTrue(recorder.events.last().startsWith("kick:new-forwarded-images"))
        assertFalse(recorder.events.dropLast(1).any { it.startsWith("kick:") })
    }

    @Test
    fun enqueuePhotos_writesGroupMetadataBeforeSingleKick() = runBlocking {
        val recorder = RecordingSendDriver()
        val store = ChatComposerStore(recorder.driver)
        val layout = MediaGroupLayoutOverride(primarySplitPermille = 640)
        val draft = photoDraft(
            items = listOf(PHOTO_A, PHOTO_B),
            caption = "Caption",
            captionPlacement = CaptionPlacement.TOP,
            layoutOverride = layout
        )
        val request = requireNotNull(
            store.createPhotoSendRequest(TARGET, draft, isSending = false)
        )

        store.enqueue(request)

        assertEquals(2, recorder.photoEnvelopes.size)
        recorder.photoEnvelopes.forEachIndexed { index, envelope ->
            assertEquals(TARGET, envelope.target)
            assertEquals("image:id-${index + 2}", envelope.envelopeId)
            assertEquals("transaction-${index + 1}", envelope.transactionId)
            assertEquals(draft.items[index], envelope.item)
            assertEquals("Caption", envelope.caption)
            assertNull(envelope.attributes.forwardedFrom)
            assertEquals("photo-group:id-1", envelope.attributes.mediaGroup?.id)
            assertEquals(index, envelope.attributes.mediaGroup?.index)
            assertEquals(2, envelope.attributes.mediaGroup?.total)
            assertEquals(CaptionMode.REPLICATED, envelope.attributes.mediaGroup?.captionMode)
            assertEquals(CaptionPlacement.TOP, envelope.attributes.mediaGroup?.captionPlacement)
            assertEquals(layout, envelope.attributes.mediaGroup?.layoutOverride)
        }
        assertEquals(
            listOf("photo", "photo", "kick:new-images:null"),
            recorder.events
        )
    }

    @Test
    fun enqueueSingleDefaultPhoto_doesNotWriteGroupMetadata() = runBlocking {
        val recorder = RecordingSendDriver()
        val store = ChatComposerStore(recorder.driver)
        val request = requireNotNull(
            store.createPhotoSendRequest(
                target = TARGET,
                draft = photoDraft(items = listOf(PHOTO_A)),
                isSending = false
            )
        )

        store.enqueue(request)

        assertEquals(ZynaMessageAttributes(), recorder.photoEnvelopes.single().attributes)
        assertEquals(listOf("photo", "kick:new-images:null"), recorder.events)
    }

    private companion object {
        val TARGET = ChatComposerSendTarget(
            userId = "@me:example.org",
            roomId = "!room:example.org"
        )
        val REPLY = MatrixReplyInfo(
            eventId = "reply-event",
            senderId = "@alice:example.org",
            senderDisplayName = "Alice",
            body = "Original"
        )
        val EDIT = MatrixEditTarget(
            messageId = "message",
            eventId = "edit-event",
            body = "Original draft"
        )
        val TEXT_FORWARD = MatrixForwardTarget(
            body = "  Forwarded body  ",
            forwardedFrom = "Alice"
        )
        val IMAGE_A = MatrixForwardImageItem(
            sourceJson = "{\"url\":\"a\"}",
            thumbnailSourceJson = null,
            width = 100,
            height = 80,
            caption = null,
            mimeType = "image/jpeg",
            blurhash = null
        )
        val IMAGE_B = IMAGE_A.copy(sourceJson = "{\"url\":\"b\"}")
        val PHOTO_A = OutgoingPhotoDraftItem(
            localPath = "/tmp/a.jpg",
            mimeType = "image/jpeg",
            width = 100,
            height = 80,
            sizeBytes = 1_000,
            thumbnailLocalPath = "/tmp/a-thumbnail.jpg",
            thumbnailMimeType = "image/jpeg",
            thumbnailWidth = 50,
            thumbnailHeight = 40,
            thumbnailSizeBytes = 250,
            blurhash = "blur-a"
        )
        val PHOTO_B = PHOTO_A.copy(
            localPath = "/tmp/b.jpg",
            thumbnailLocalPath = "/tmp/b-thumbnail.jpg",
            blurhash = "blur-b"
        )

        fun photoDraft(
            items: List<OutgoingPhotoDraftItem>,
            caption: String? = null,
            captionPlacement: CaptionPlacement = CaptionPlacement.BOTTOM,
            layoutOverride: MediaGroupLayoutOverride? = null
        ): OutgoingPhotoDraft {
            return OutgoingPhotoDraft(
                items = items,
                caption = caption,
                captionPlacement = captionPlacement,
                layoutOverride = layoutOverride
            )
        }
    }
}

private class RecordingSendDriver(
    private val didPrepareEdit: Boolean = true
) {
    var idCallCount = 0
        private set
    var transactionCallCount = 0
        private set
    val preparedEdits = mutableListOf<PreparedEdit>()
    val textEnvelopes = mutableListOf<TextEnvelope>()
    val imageEnvelopes = mutableListOf<ImageEnvelope>()
    val photoEnvelopes = mutableListOf<PhotoEnvelope>()
    val events = mutableListOf<String>()

    val driver = ChatComposerSendDriver(
        nextId = {
            idCallCount += 1
            "id-$idCallCount"
        },
        prepareTransactionId = {
            transactionCallCount += 1
            "transaction-$transactionCallCount"
        },
        prepareTextEdit = { target, editTarget, body, transactionId ->
            preparedEdits += PreparedEdit(target, editTarget, body, transactionId)
            events += "edit"
            didPrepareEdit
        },
        createTextEnvelope = {
                target, envelopeId, transactionId, body, replyInfo, forwardedFrom ->
            textEnvelopes += TextEnvelope(
                target,
                envelopeId,
                transactionId,
                body,
                replyInfo,
                forwardedFrom
            )
            events += "text"
        },
        createForwardedImageEnvelope = {
                target, envelopeId, transactionId, image, caption, attributes ->
            imageEnvelopes += ImageEnvelope(
                target,
                envelopeId,
                transactionId,
                image,
                caption,
                attributes
            )
            events += "image"
        },
        createImageEnvelope = {
                target, envelopeId, transactionId, item, caption, attributes ->
            photoEnvelopes += PhotoEnvelope(
                target,
                envelopeId,
                transactionId,
                item,
                caption,
                attributes
            )
            events += "photo"
        },
        kickOutbox = { reason, envelopeId ->
            events += "kick:$reason:$envelopeId"
        }
    )
}

private data class PreparedEdit(
    val target: ChatComposerSendTarget,
    val editTarget: MatrixEditTarget,
    val body: String,
    val transactionId: String
)

private data class TextEnvelope(
    val target: ChatComposerSendTarget,
    val envelopeId: String,
    val transactionId: String,
    val body: String,
    val replyInfo: MatrixReplyInfo?,
    val forwardedFrom: String?
)

private data class ImageEnvelope(
    val target: ChatComposerSendTarget,
    val envelopeId: String,
    val transactionId: String,
    val image: MatrixForwardImageItem,
    val caption: String?,
    val attributes: ZynaMessageAttributes
)

private data class PhotoEnvelope(
    val target: ChatComposerSendTarget,
    val envelopeId: String,
    val transactionId: String,
    val item: OutgoingPhotoDraftItem,
    val caption: String?,
    val attributes: ZynaMessageAttributes
)
