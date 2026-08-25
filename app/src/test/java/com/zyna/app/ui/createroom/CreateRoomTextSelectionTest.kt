package com.zyna.app.ui.createroom

import org.junit.Assert.assertEquals
import org.junit.Test

class CreateRoomTextSelectionTest {
    @Test
    fun caseNormalizationKeepsMiddleCursorPosition() {
        assertEquals(
            3,
            remapSelectionAfterNormalization(
                oldValue = "myRoom",
                newValue = "myroom",
                selection = 3
            )
        )
    }

    @Test
    fun removingLeadingHashMovesCursorWithRemainingText() {
        assertEquals(
            0,
            remapSelectionAfterNormalization(
                oldValue = "#room",
                newValue = "room",
                selection = 1
            )
        )
        assertEquals(
            4,
            remapSelectionAfterNormalization(
                oldValue = "#room",
                newValue = "room",
                selection = 5
            )
        )
    }

    @Test
    fun removingServerSuffixKeepsCursorAtEndOfLocalPart() {
        assertEquals(
            4,
            remapSelectionAfterNormalization(
                oldValue = "room:example.org",
                newValue = "room",
                selection = "room:example.org".length
            )
        )
    }

    @Test
    fun initialRenderPlacesCursorAtEnd() {
        assertEquals(
            4,
            remapSelectionAfterNormalization(
                oldValue = "",
                newValue = "room",
                selection = 0
            )
        )
    }
}
