package com.zyna.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestListSubmissionCoordinatorTest {
    @Test
    fun samePendingValue_isSubmittedOnceAndUsesLatestCallback() {
        val pending = mutableListOf<() -> Unit>()
        val coordinator = LatestListSubmissionCoordinator<Any>(
            submitValue = { _, onCommitted -> pending += onCommitted }
        )
        val target = Any()
        var firstCalled = false
        var secondCommittedNew: Boolean? = null

        coordinator.submit(target) { firstCalled = true }
        coordinator.submit(target) { secondCommittedNew = it }

        assertEquals(1, pending.size)
        pending.single().invoke()
        assertFalse(firstCalled)
        assertEquals(true, secondCommittedNew)
    }

    @Test
    fun alreadyCommittedValue_completesImmediatelyWithoutNewCommit() {
        val target = Any()
        val pending = mutableListOf<() -> Unit>()
        val coordinator = LatestListSubmissionCoordinator<Any>(
            submitValue = { _, onCommitted -> pending += onCommitted }
        )
        coordinator.submit(target) {}
        pending.single().invoke()
        var committedNew: Boolean? = null

        coordinator.submit(target) { committedNew = it }

        assertEquals(false, committedNew)
        assertFalse(coordinator.willSubmitNew(target))
    }

    @Test
    fun supersededCommit_doesNotCompleteOldGeneration() {
        val pending = mutableListOf<Pair<Any, () -> Unit>>()
        val coordinator = LatestListSubmissionCoordinator<Any>(
            submitValue = { value, onCommitted -> pending += value to onCommitted }
        )
        val first = Any()
        val second = Any()
        var firstCalled = false
        var secondCalled = false

        coordinator.submit(first) { firstCalled = true }
        coordinator.submit(second) { secondCalled = it }
        pending[0].second.invoke()
        pending[1].second.invoke()

        assertFalse(firstCalled)
        assertTrue(secondCalled)
    }
}
