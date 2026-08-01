package com.zyna.app.ui.chat

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRouteRevealCoordinatorTest {
    @Test
    fun readyBeforeFallbackRevealsOnce() = runBlocking {
        val fixture = Fixture(coroutineContext)
        try {
            val requestId = fixture.begin(revealResult = true)

            assertTrue(fixture.coordinator.ready(requestId))
            fixture.fallbackGate.complete(Unit)
            yield()

            assertEquals(1, fixture.revealCount)
            assertEquals(0, fixture.abandonedCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun slowBootstrapRevealsAtFallbackAndDoesNotRevealAgainWhenReady() = runBlocking {
        val fixture = Fixture(coroutineContext)
        try {
            val requestId = fixture.begin(revealResult = true)
            fixture.fallbackGate.complete(Unit)
            yield()

            assertEquals(1, fixture.revealCount)
            assertTrue(fixture.coordinator.ready(requestId))
            assertEquals(1, fixture.revealCount)
            assertEquals(0, fixture.abandonedCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun staleRequestCannotRevealAfterReplacement() = runBlocking {
        val fixture = Fixture(coroutineContext)
        try {
            val firstRequestId = fixture.begin(revealResult = true)
            val latestRequestId = fixture.begin(revealResult = true)
            fixture.fallbackGate.complete(Unit)
            yield()

            assertFalse(fixture.coordinator.ready(firstRequestId))
            assertTrue(fixture.coordinator.ready(latestRequestId))
            assertEquals(1, fixture.revealCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun invalidOwnerAbandonsSlowRequest() = runBlocking {
        val fixture = Fixture(coroutineContext)
        try {
            val requestId = fixture.begin(revealResult = false)
            fixture.fallbackGate.complete(Unit)
            yield()

            assertEquals(1, fixture.revealCount)
            assertEquals(1, fixture.abandonedCount)
            assertFalse(fixture.coordinator.ready(requestId))
        } finally {
            fixture.close()
        }
    }

    private class Fixture(parentContext: CoroutineContext) {
        private val scope = CoroutineScope(parentContext + SupervisorJob())
        val fallbackGate = CompletableDeferred<Unit>()
        val coordinator = ChatRouteRevealCoordinator(
            scope = scope,
            awaitFallback = { fallbackGate.await() }
        )
        var revealCount = 0
        var abandonedCount = 0

        fun begin(revealResult: Boolean): Long {
            return coordinator.begin(
                reveal = {
                    revealCount += 1
                    revealResult
                },
                onAbandoned = { abandonedCount += 1 }
            )
        }

        fun close() {
            coordinator.cancel()
            scope.cancel()
        }
    }
}
