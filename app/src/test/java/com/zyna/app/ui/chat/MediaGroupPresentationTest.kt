package com.zyna.app.ui.chat

import com.zyna.app.data.matrix.MatrixChatMessage
import com.zyna.app.data.matrix.MatrixImageInfo
import com.zyna.app.data.matrix.MatrixMessageContentType
import com.zyna.app.data.messaging.CaptionMode
import com.zyna.app.data.messaging.CaptionPlacement
import com.zyna.app.data.messaging.MediaGroupInfo
import com.zyna.app.data.messaging.ZynaMessageAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaGroupPresentationTest {
    @Test
    fun withMediaGroupPresentation_collapsesCompleteGroupAndDropsZeroWidthCaption() {
        val messages = listOf(
            imageMessage(id = "newer", groupIndex = 1, caption = "\u200B", timestampMillis = 20),
            imageMessage(id = "older", groupIndex = 0, caption = "\u200B", timestampMillis = 10)
        )

        val result = messages.withMediaGroupPresentation(
            hasNewerBoundary = false,
            hasOlderBoundary = false
        )

        assertEquals(1, result.size)
        assertEquals("newer", result.single().id)
        val presentation = result.single().mediaGroupPresentation
        requireNotNull(presentation)
        assertTrue(presentation.rendersCompositeBubble)
        assertNull(presentation.caption)
        assertFalse(presentation.suppressIndividualCaption)
        assertEquals(listOf("older", "newer"), presentation.items.map { it.messageId })
    }

    @Test
    fun withMediaGroupPresentation_usesOlderCarrierForTopCaptionGroup() {
        val messages = listOf(
            imageMessage(
                id = "newer",
                groupIndex = 1,
                caption = "caption",
                timestampMillis = 20,
                captionPlacement = CaptionPlacement.TOP
            ),
            imageMessage(
                id = "older",
                groupIndex = 0,
                caption = "caption",
                timestampMillis = 10,
                captionPlacement = CaptionPlacement.TOP
            )
        )

        val result = messages.withMediaGroupPresentation(
            hasNewerBoundary = false,
            hasOlderBoundary = false
        )

        assertEquals(1, result.size)
        assertEquals("older", result.single().id)
        val presentation = result.single().mediaGroupPresentation
        requireNotNull(presentation)
        assertEquals("caption", presentation.caption)
        assertTrue(presentation.suppressIndividualCaption)
        assertEquals(CaptionPlacement.TOP, presentation.captionPlacement)
    }

    @Test
    fun withMediaGroupPresentation_keepsCompleteGroupSplitAtPaginationBoundary() {
        val messages = listOf(
            imageMessage(id = "newer", groupIndex = 1, timestampMillis = 20),
            imageMessage(id = "older", groupIndex = 0, timestampMillis = 10)
        )

        val result = messages.withMediaGroupPresentation(
            hasNewerBoundary = true,
            hasOlderBoundary = false
        )

        assertEquals(listOf("newer", "older"), result.map { it.id })
        assertTrue(result.all { it.mediaGroupPresentation == null })
    }

    @Test
    fun withMediaGroupPresentation_showsIncomingPlaceholderForIncompleteGroup() {
        val messages = listOf(
            imageMessage(id = "partial", groupIndex = 0, total = 3, timestampMillis = 10)
        )

        val result = messages.withMediaGroupPresentation(
            hasNewerBoundary = false,
            hasOlderBoundary = false
        )

        assertEquals(1, result.size)
        assertEquals("incoming-assembly:group-1", result.single().id)
        assertEquals(MatrixMessageContentType.NOTICE, result.single().contentType)
        assertEquals("Receiving photo 1 of 3", result.single().body)
    }

    private fun imageMessage(
        id: String,
        groupIndex: Int,
        total: Int = 2,
        caption: String? = null,
        timestampMillis: Long,
        captionPlacement: CaptionPlacement = CaptionPlacement.BOTTOM
    ): MatrixChatMessage {
        return MatrixChatMessage(
            id = id,
            eventId = id,
            sender = "@alice:example.org",
            body = "Photo",
            timestampMillis = timestampMillis,
            isOwn = false,
            contentType = MatrixMessageContentType.IMAGE,
            imageInfo = MatrixImageInfo(
                sourceJson = id,
                thumbnailSourceJson = null,
                width = 800,
                height = 600,
                caption = caption,
                mimeType = "image/jpeg",
                blurhash = null
            ),
            zynaAttributes = ZynaMessageAttributes(
                mediaGroup = MediaGroupInfo(
                    id = "group-1",
                    index = groupIndex,
                    total = total,
                    captionMode = CaptionMode.REPLICATED,
                    captionPlacement = captionPlacement
                )
            )
        )
    }
}
