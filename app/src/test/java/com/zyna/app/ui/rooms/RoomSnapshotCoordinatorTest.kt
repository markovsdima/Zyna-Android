package com.zyna.app.ui.rooms

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomSnapshotCoordinatorTest {
    @Test
    fun requestsDuringSync_areFoldedIntoOneSequentialTrailingSync() = runBlocking {
        withTimeout(5_000L) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val activeOperations = AtomicInteger(0)
            val maximumActiveOperations = AtomicInteger(0)
            val operationCount = AtomicInteger(0)
            val committedSnapshots = Collections.synchronizedList(mutableListOf<Int>())
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()
            val releaseSecond = CompletableDeferred<Unit>()
            val coordinator = CoalescingRoomSnapshotCoordinator(scope) { userId ->
                assertEquals(USER_ID, userId)
                val active = activeOperations.incrementAndGet()
                maximumActiveOperations.accumulateAndGet(active) { current, candidate ->
                    maxOf(current, candidate)
                }
                val snapshot = operationCount.incrementAndGet()
                try {
                    when (snapshot) {
                        1 -> {
                            firstStarted.complete(Unit)
                            releaseFirst.await()
                        }
                        2 -> {
                            secondStarted.complete(Unit)
                            releaseSecond.await()
                        }
                    }
                    committedSnapshots += snapshot
                } finally {
                    activeOperations.decrementAndGet()
                }
            }
            coordinator.activateSession(USER_ID)

            try {
                val first = async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.synchronize()
                }
                firstStarted.await()
                val second = async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.synchronize()
                }
                val third = async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.synchronize()
                }

                releaseFirst.complete(Unit)
                first.await()
                secondStarted.await()
                assertFalse(second.isCompleted)
                assertFalse(third.isCompleted)

                releaseSecond.complete(Unit)
                awaitAll(second, third)

                assertEquals(2, operationCount.get())
                assertEquals(1, maximumActiveOperations.get())
                assertEquals(listOf(1, 2), committedSnapshots.toList())
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun deactivateSession_cancelsAndJoinsActiveSyncBeforeNextSession() = runBlocking {
        withTimeout(5_000L) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val operationCount = AtomicInteger(0)
            val firstStarted = CompletableDeferred<Unit>()
            val firstFinished = CompletableDeferred<Unit>()
            val coordinator = CoalescingRoomSnapshotCoordinator(scope) { userId ->
                operationCount.incrementAndGet()
                if (userId == FIRST_USER_ID) {
                    firstStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        firstFinished.complete(Unit)
                    }
                }
            }

            try {
                coordinator.activateSession(FIRST_USER_ID)
                val firstSync = async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.synchronize()
                }
                firstStarted.await()
                val queuedSync = async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.synchronize()
                }

                coordinator.deactivateSession()

                assertTrue(firstFinished.isCompleted)
                assertTrue(
                    runCatching { firstSync.await() }.exceptionOrNull() is
                        CancellationException
                )
                assertTrue(
                    runCatching { queuedSync.await() }.exceptionOrNull() is
                        CancellationException
                )

                coordinator.activateSession(SECOND_USER_ID)
                coordinator.synchronize()

                assertEquals(2, operationCount.get())
            } finally {
                scope.cancel()
            }
        }
    }

    private companion object {
        const val USER_ID = "@me:example.org"
        const val FIRST_USER_ID = "@first:example.org"
        const val SECOND_USER_ID = "@second:example.org"
    }
}
