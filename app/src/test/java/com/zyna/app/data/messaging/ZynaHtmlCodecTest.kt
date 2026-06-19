package com.zyna.app.data.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZynaHtmlCodecTest {
    @Test
    fun encodeDecode_roundTripsMediaGroupAttributes() {
        val attributes = ZynaMessageAttributes(
            forwardedFrom = "Alice",
            mediaGroup = MediaGroupInfo(
                id = "group-1",
                index = 2,
                total = 4,
                captionMode = CaptionMode.REPLICATED,
                captionPlacement = CaptionPlacement.TOP,
                layoutOverride = MediaGroupLayoutOverride(
                    primarySplitPermille = 640,
                    secondarySplitPermille = 430
                )
            )
        )

        val html = ZynaHtmlCodec.encode("<b>hello</b>", attributes)

        assertTrue(html.contains("data-zyna="))
        assertEquals(attributes, ZynaHtmlCodec.decode(html))
        assertEquals(
            attributes,
            ZynaHtmlCodec.decodeAttributesJson(ZynaHtmlCodec.encodeAttributesJson(attributes))
        )
    }

    @Test
    fun decode_defaultsMissingCaptionPlacementToBottom() {
        val json = """
            {"v":1,"mg":{"id":"group-2","index":1,"total":3,
            "captionMode":"replicated","layout":{"primarySplit":620}}}
        """.trimIndent().replace("\n", "")
        val html = "<span data-zyna=\"${ZynaHtmlCodec.escapeForHtmlAttribute(json)}\"></span>"

        val mediaGroup = ZynaHtmlCodec.decode(html).mediaGroup

        assertEquals(
            MediaGroupInfo(
                id = "group-2",
                index = 1,
                total = 3,
                captionMode = CaptionMode.REPLICATED,
                captionPlacement = CaptionPlacement.BOTTOM,
                layoutOverride = MediaGroupLayoutOverride(primarySplitPermille = 620)
            ),
            mediaGroup
        )
    }

    @Test
    fun decode_ignoresInvalidMediaGroupButKeepsOtherAttributes() {
        val json = """{"v":1,"fwd":"Alice","mg":{"id":"bad","index":0,"total":2}}"""
        val html = "<span data-zyna=\"${ZynaHtmlCodec.escapeForHtmlAttribute(json)}\"></span>"

        val attributes = ZynaHtmlCodec.decode(html)

        assertEquals("Alice", attributes.forwardedFrom)
        assertNull(attributes.mediaGroup)
    }

    @Test
    fun encodeAttributesJson_returnsNullForEmptyAttributes() {
        assertNull(ZynaHtmlCodec.encodeAttributesJson(ZynaMessageAttributes()))
    }
}
