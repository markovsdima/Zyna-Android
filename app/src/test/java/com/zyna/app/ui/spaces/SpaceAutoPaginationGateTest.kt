package com.zyna.app.ui.spaces

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceAutoPaginationGateTest {
    @Test
    fun repeatedRenderSchedulesOnlyOneCheckForTheSamePage() {
        val gate = SpaceAutoPaginationGate()
        val status = status(loadedRoomCount = 20)

        assertTrue(gate.shouldSchedule(status))
        assertFalse(gate.shouldSchedule(status))
        assertTrue(
            gate.shouldRequestAfterLayout(
                status = status,
                canScrollForward = false
            )
        )
        assertFalse(
            gate.shouldRequestAfterLayout(
                status = status,
                canScrollForward = true
            )
        )
    }

    @Test
    fun loadingAndNewPageAllowANewCheck() {
        val gate = SpaceAutoPaginationGate()
        val firstPage = status(loadedRoomCount = 20)

        assertTrue(gate.shouldSchedule(firstPage))
        assertFalse(gate.shouldSchedule(firstPage.copy(isPaginating = true)))
        assertTrue(gate.shouldSchedule(firstPage))
        assertTrue(gate.shouldSchedule(firstPage.copy(loadedRoomCount = 40)))
    }

    @Test
    fun unknownAndCompletedListsDoNotSchedule() {
        val gate = SpaceAutoPaginationGate()

        assertFalse(gate.shouldSchedule(status(isKnown = false)))
        assertFalse(gate.shouldSchedule(status(endReached = true)))
    }

    private fun status(
        loadedRoomCount: Int = 0,
        isKnown: Boolean = true,
        isPaginating: Boolean = false,
        endReached: Boolean = false
    ): SpacePaginationStatus {
        return SpacePaginationStatus(
            spaceId = "!space:example.org",
            loadedRoomCount = loadedRoomCount,
            isKnown = isKnown,
            isPaginating = isPaginating,
            endReached = endReached
        )
    }
}
