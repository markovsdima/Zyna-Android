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
import com.zyna.app.data.outgoing.OutgoingOutboxFailure
import com.zyna.app.data.outgoing.OutgoingVoiceDraft
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatComposerSendTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun blankSend_isRejectedBeforeIdentifiersAreAllocated() {
        val recorder = RecordingSendDriver()
        val store = store(recorder.driver)

        assertNull(store.createSendRequest(TARGET, "   "))

        assertEquals(0, recorder.idCallCount)
        assertEquals(0, recorder.transactionCallCount)
    }

    @Test
    fun textRequest_trimsBodyAndCarriesReplyTarget() {
        val recorder = RecordingSendDriver()
        val store = store(recorder.driver)
        store.selectReply(REPLY)

        val request = store.createSendRequest(
            target = TARGET,
            body = "  Hello  "
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
        val store = store(recorder.driver)
        store.selectReply(REPLY)
        store.selectEdit(EDIT)

        val request = store.createSendRequest(
            target = TARGET,
            body = "  Updated  "
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
        val store = store(recorder.driver)
        store.selectReply(REPLY)
        store.enterRoom(TARGET, TEXT_FORWARD)

        val request = store.createSendRequest(
            target = TARGET,
            body = "Ignored composer body"
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
        val store = store(recorder.driver)
        val forward = MatrixForwardTarget(
            body = "",
            forwardedFrom = "Alice",
            imageItems = listOf(IMAGE_A)
        )
        store.enterRoom(TARGET, forward)

        val request = store.createSendRequest(
            target = TARGET,
            body = ""
        )

        assertEquals(ChatComposerSendRequest.ForwardImages(TARGET, forward), request)
        assertEquals(1, recorder.idCallCount)
        assertEquals(1, recorder.transactionCallCount)
    }

    @Test
    fun photoRequest_filtersInvalidItemsAndReservesGroupId() {
        val recorder = RecordingSendDriver()
        val store = store(recorder.driver)
        val draft = photoDraft(
            items = listOf(PHOTO_A.copy(localPath = ""), PHOTO_A)
        )

        val request = store.createPhotoSendRequest(
            target = TARGET,
            draft = draft
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
    fun emptyPhotoRequest_isRejectedBeforeGroupIdIsAllocated() {
        val recorder = RecordingSendDriver()
        val store = store(recorder.driver)

        assertNull(
            store.createPhotoSendRequest(
                target = TARGET,
                draft = photoDraft(items = listOf(PHOTO_A.copy(localPath = "")))
            )
        )

        assertEquals(0, recorder.idCallCount)
        assertEquals(0, recorder.transactionCallCount)
    }

    @Test
    fun blankVoiceRequest_isRejectedBeforeIdentifiersAreAllocated() {
        val recorder = RecordingSendDriver()
        val store = store(recorder.driver)

        assertNull(
            store.createVoiceSendRequest(
                target = TARGET,
                draft = VOICE.copy(localPath = " ")
            )
        )

        assertEquals(0, recorder.idCallCount)
        assertEquals(0, recorder.transactionCallCount)
    }

    @Test
    fun voiceRequest_capturesReplyWithoutAllocatingIdentifiers() {
        val recorder = RecordingSendDriver()
        val store = store(recorder.driver)
        store.selectReply(REPLY)

        val request = store.createVoiceSendRequest(
            target = TARGET,
            draft = VOICE
        )

        assertEquals(ChatComposerSendRequest.Voice(TARGET, VOICE, REPLY), request)
        assertEquals(0, recorder.idCallCount)
        assertEquals(0, recorder.transactionCallCount)
    }

    @Test
    fun acceptedSend_ownsBusyStateAndClearsActiveTargetsAfterEnqueue() {
        val gate = CompletableDeferred<Unit>()
        val recorder = RecordingSendDriver(textEnvelopeGate = gate)
        val store = store(recorder.driver)
        store.enterRoom(TARGET, forwardTarget = null)
        store.selectReply(REPLY)

        assertTrue(store.sendText(TARGET, "Message"))

        assertTrue(store.state.value.isSending)
        assertFalse(store.sendText(TARGET, "Another message"))
        assertEquals(1, recorder.idCallCount)

        gate.complete(Unit)

        assertFalse(store.state.value.isSending)
        assertNull(store.state.value.replyTarget)
        assertNull(store.state.value.errorMessage)
    }

    @Test
    fun photoSend_preservesComposerTargetsAfterEnqueue() {
        val recorder = RecordingSendDriver()
        val store = store(recorder.driver)
        store.enterRoom(TARGET, forwardTarget = null)
        store.selectReply(REPLY)

        assertTrue(
            store.sendPhotos(
                target = TARGET,
                draft = photoDraft(items = listOf(PHOTO_A))
            )
        )

        assertFalse(store.state.value.isSending)
        assertEquals(REPLY, store.state.value.replyTarget)
        assertEquals(listOf("photo", "kick:new-images:null"), recorder.events)
    }

    @Test
    fun voiceSend_runsCleanupAndClearsActiveTargetsAfterEnqueue() {
        val recorder = RecordingSendDriver()
        val store = store(recorder.driver)
        var cleanupCount = 0
        store.enterRoom(TARGET, forwardTarget = null)
        store.selectReply(REPLY)

        assertTrue(
            store.sendVoice(
                target = TARGET,
                draft = VOICE,
                onEnqueued = { cleanupCount += 1 }
            )
        )

        assertFalse(store.state.value.isSending)
        assertNull(store.state.value.replyTarget)
        assertEquals(1, cleanupCount)
        assertEquals(listOf("voice", "kick:new-voice:voice:id-1"), recorder.events)
    }

    @Test
    fun staleSendCompletion_doesNotMutateReopenedRoom() {
        val gate = CompletableDeferred<Unit>()
        val recorder = RecordingSendDriver(textEnvelopeGate = gate)
        val store = store(recorder.driver)
        store.enterRoom(TARGET, forwardTarget = null)
        store.selectReply(REPLY)
        assertTrue(store.sendText(TARGET, "Message"))

        store.enterRoom(OTHER_TARGET, forwardTarget = null)
        store.enterRoom(TARGET, forwardTarget = null)
        store.selectReply(OTHER_REPLY)
        gate.complete(Unit)

        assertEquals(TARGET.roomId, store.state.value.roomId)
        assertEquals(OTHER_REPLY, store.state.value.replyTarget)
        assertFalse(store.state.value.isSending)
        assertNull(store.state.value.errorMessage)
    }

    @Test
    fun deactivatedRoom_doesNotReceiveSendCompletion() {
        val gate = CompletableDeferred<Unit>()
        val recorder = RecordingSendDriver(textEnvelopeGate = gate)
        val store = store(recorder.driver)
        store.enterRoom(TARGET, forwardTarget = null)
        assertTrue(store.sendText(TARGET, "Message"))

        store.deactivateRoom()
        gate.complete(Unit)

        assertEquals(ChatComposerState(), store.state.value)
        assertEquals(1, recorder.textEnvelopes.size)
        assertEquals(listOf("text", "kick:new-envelope:text:id-1"), recorder.events)
    }

    @Test
    fun sendFailure_isScopedToAcceptedRoomGeneration() {
        val expected = IllegalStateException("enqueue failed")
        val recorder = RecordingSendDriver(textEnvelopeError = expected)
        val store = store(recorder.driver)
        store.enterRoom(TARGET, forwardTarget = null)

        assertTrue(store.sendText(TARGET, "Message"))

        assertFalse(store.state.value.isSending)
        assertEquals("enqueue failed", store.state.value.errorMessage)
    }

    @Test
    fun outboxFailure_updatesOnlyMatchingActiveRoom() {
        val failures = MutableSharedFlow<OutgoingOutboxFailure>(extraBufferCapacity = 2)
        val store = ChatComposerStore(
            scope = scope,
            sendDriver = RecordingSendDriver().driver,
            sendFailures = failures
        )
        store.enterRoom(TARGET, forwardTarget = null)

        assertTrue(failures.tryEmit(OutgoingOutboxFailure(OTHER_TARGET.roomId, "Other")))
        assertNull(store.state.value.errorMessage)

        assertTrue(failures.tryEmit(OutgoingOutboxFailure(TARGET.roomId, "Failed")))
        assertEquals("Failed", store.state.value.errorMessage)
    }

    @Test
    fun enqueueText_createsDurableEnvelopeBeforeKickingOutbox() = runBlocking {
        val recorder = RecordingSendDriver()
        val store = store(recorder.driver)
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

        store(preparedRecorder.driver).enqueue(request)
        store(rejectedRecorder.driver).enqueue(request)

        val expectedEdit = PreparedEdit(TARGET, EDIT, "Updated", "transaction")
        assertEquals(listOf(expectedEdit), preparedRecorder.preparedEdits)
        assertEquals(listOf(expectedEdit), rejectedRecorder.preparedEdits)
        assertEquals(listOf("edit", "kick:new-edit:null"), preparedRecorder.events)
        assertEquals(listOf("edit"), rejectedRecorder.events)
    }

    @Test
    fun enqueueImageForward_writesGroupMetadataBeforeSingleKick() = runBlocking {
        val recorder = RecordingSendDriver()
        val store = store(recorder.driver)
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
        val store = store(recorder.driver)
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
        val store = store(recorder.driver)
        val layout = MediaGroupLayoutOverride(primarySplitPermille = 640)
        val draft = photoDraft(
            items = listOf(PHOTO_A, PHOTO_B),
            caption = "Caption",
            captionPlacement = CaptionPlacement.TOP,
            layoutOverride = layout
        )
        val request = requireNotNull(
            store.createPhotoSendRequest(TARGET, draft)
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
        val store = store(recorder.driver)
        val request = requireNotNull(
            store.createPhotoSendRequest(
                target = TARGET,
                draft = photoDraft(items = listOf(PHOTO_A))
            )
        )

        store.enqueue(request)

        assertEquals(ZynaMessageAttributes(), recorder.photoEnvelopes.single().attributes)
        assertEquals(listOf("photo", "kick:new-images:null"), recorder.events)
    }

    @Test
    fun enqueueVoice_usesCapturedReplyBeforeKickingOutbox() = runBlocking {
        val recorder = RecordingSendDriver()
        val store = store(recorder.driver)
        store.selectReply(REPLY)
        val request = requireNotNull(
            store.createVoiceSendRequest(TARGET, VOICE)
        )
        store.clearReply()

        store.enqueue(request)

        assertEquals(
            listOf(
                VoiceEnvelope(
                    target = TARGET,
                    envelopeId = "voice:id-1",
                    transactionId = "transaction-1",
                    draft = VOICE,
                    replyInfo = REPLY
                )
            ),
            recorder.voiceEnvelopes
        )
        assertEquals(listOf("voice", "kick:new-voice:voice:id-1"), recorder.events)
    }

    private companion object {
        val TARGET = ChatComposerSendTarget(
            userId = "@me:example.org",
            roomId = "!room:example.org"
        )
        val OTHER_TARGET = ChatComposerSendTarget(
            userId = "@me:example.org",
            roomId = "!other:example.org"
        )
        val REPLY = MatrixReplyInfo(
            eventId = "reply-event",
            senderId = "@alice:example.org",
            senderDisplayName = "Alice",
            body = "Original"
        )
        val OTHER_REPLY = REPLY.copy(
            eventId = "other-reply-event",
            body = "Other original"
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
        val VOICE = OutgoingVoiceDraft(
            localPath = "/tmp/voice.ogg",
            mimeType = "audio/ogg",
            sizeBytes = 2_000,
            durationMillis = 1_500,
            waveform = listOf(0.1f, 0.5f, 0.2f)
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

    private fun store(driver: ChatComposerSendDriver): ChatComposerStore {
        return ChatComposerStore(scope, driver)
    }
}

private class RecordingSendDriver(
    private val didPrepareEdit: Boolean = true,
    private val textEnvelopeGate: CompletableDeferred<Unit>? = null,
    private val textEnvelopeError: Throwable? = null
) {
    var idCallCount = 0
        private set
    var transactionCallCount = 0
        private set
    val preparedEdits = mutableListOf<PreparedEdit>()
    val textEnvelopes = mutableListOf<TextEnvelope>()
    val imageEnvelopes = mutableListOf<ImageEnvelope>()
    val photoEnvelopes = mutableListOf<PhotoEnvelope>()
    val voiceEnvelopes = mutableListOf<VoiceEnvelope>()
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
            textEnvelopeGate?.await()
            textEnvelopeError?.let { throw it }
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
        createVoiceEnvelope = {
                target, envelopeId, transactionId, draft, replyInfo ->
            voiceEnvelopes += VoiceEnvelope(
                target,
                envelopeId,
                transactionId,
                draft,
                replyInfo
            )
            events += "voice"
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

private data class VoiceEnvelope(
    val target: ChatComposerSendTarget,
    val envelopeId: String,
    val transactionId: String,
    val draft: OutgoingVoiceDraft,
    val replyInfo: MatrixReplyInfo?
)
