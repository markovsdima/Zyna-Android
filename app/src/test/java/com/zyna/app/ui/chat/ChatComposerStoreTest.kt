package com.zyna.app.ui.chat

import com.zyna.app.data.matrix.MatrixEditTarget
import com.zyna.app.data.matrix.MatrixForwardImageItem
import com.zyna.app.data.matrix.MatrixForwardTarget
import com.zyna.app.data.matrix.MatrixReplyInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatComposerStoreTest {
    private val store = ChatComposerStore(noOpSendDriver())

    @Test
    fun replyEditAndForwardTargets_areMutuallyExclusive() {
        assertEquals(REPLY, store.selectReply(REPLY)?.replyTarget)

        val editState = requireNotNull(store.selectEdit(EDIT))
        assertNull(editState.replyTarget)
        assertEquals(EDIT, editState.editTarget)

        val pickerState = requireNotNull(store.startForwardPicker(FORWARD))
        assertNull(pickerState.replyTarget)
        assertNull(pickerState.editTarget)
        assertNull(pickerState.forwardTarget)
        assertEquals(FORWARD, pickerState.pendingForwardTarget)

        val roomState = store.enterRoom(FORWARD)
        assertNull(roomState.replyTarget)
        assertNull(roomState.editTarget)
        assertEquals(FORWARD, roomState.forwardTarget)
        assertNull(roomState.pendingForwardTarget)
    }

    @Test
    fun invalidTargets_areRejectedWithoutChangingState() {
        store.selectReply(REPLY)
        val previous = store.state

        assertNull(store.selectReply(REPLY.copy(eventId = "")))
        assertNull(store.selectEdit(EDIT.copy(eventId = "")))
        assertNull(store.selectEdit(EDIT.copy(body = "")))
        assertNull(store.startForwardPicker(MatrixForwardTarget(body = "", forwardedFrom = null)))
        assertEquals(previous, store.state)
    }

    @Test
    fun imageForward_isValidWithoutTextBody() {
        val imageForward = MatrixForwardTarget(
            body = "",
            forwardedFrom = "Alice",
            imageItems = listOf(FORWARD_IMAGE)
        )

        val state = requireNotNull(store.startForwardPicker(imageForward))

        assertEquals(imageForward, state.pendingForwardTarget)
    }

    @Test
    fun cancelForwardPicker_clearsOnlyPendingWorkflow() {
        store.startForwardPicker(FORWARD)

        val state = store.cancelForwardPicker()

        assertNull(state.pendingForwardTarget)
        assertNull(state.forwardTarget)
    }

    @Test
    fun activeAndFullReset_haveDifferentScopes() {
        store.enterRoom(FORWARD)
        assertEquals(ChatComposerState(), store.clearActiveTargets())

        store.startForwardPicker(FORWARD)

        val activeReset = store.clearActiveTargets()
        assertEquals(FORWARD, activeReset.pendingForwardTarget)

        assertEquals(ChatComposerState(), store.clearAll())
    }

    @Test
    fun enteringRegularRoom_clearsPreviousAndPendingTargets() {
        store.selectReply(REPLY)
        store.startForwardPicker(FORWARD)

        assertEquals(ChatComposerState(), store.enterRoom(forwardTarget = null))
    }

    @Test
    fun individualClearActions_removeOnlyTheirTarget() {
        store.selectReply(REPLY)
        assertEquals(ChatComposerState(), store.clearReply())

        store.selectEdit(EDIT)
        assertEquals(ChatComposerState(), store.clearEdit())

        store.enterRoom(FORWARD)
        assertEquals(ChatComposerState(), store.clearForward())
    }

    private companion object {
        val REPLY = MatrixReplyInfo(
            eventId = "reply-event",
            senderId = "@alice:example.org",
            senderDisplayName = "Alice",
            body = "Original"
        )
        val EDIT = MatrixEditTarget(
            messageId = "message",
            eventId = "edit-event",
            body = "Draft"
        )
        val FORWARD = MatrixForwardTarget(
            body = "Forwarded",
            forwardedFrom = "Alice"
        )
        val FORWARD_IMAGE = MatrixForwardImageItem(
            sourceJson = "{}",
            thumbnailSourceJson = null,
            width = 100,
            height = 100,
            caption = null,
            mimeType = "image/jpeg",
            blurhash = null
        )
    }
}

private fun noOpSendDriver(): ChatComposerSendDriver {
    return ChatComposerSendDriver(
        nextId = { "id" },
        prepareTransactionId = { "transaction" },
        prepareTextEdit = { _, _, _, _ -> false },
        createTextEnvelope = { _, _, _, _, _, _ -> },
        createForwardedImageEnvelope = { _, _, _, _, _, _ -> },
        createImageEnvelope = { _, _, _, _, _, _ -> },
        kickOutbox = { _, _ -> }
    )
}
