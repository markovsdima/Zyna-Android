package com.zyna.app.ui.roomdetails

import com.zyna.app.data.matrix.MatrixRoomAccess
import com.zyna.app.data.matrix.MatrixRoomKind
import com.zyna.app.data.matrix.MatrixRoomLeaveContext
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomLeaveStoreTest {
    @Test
    fun leaveUsesFreshPreflightAndCompletesCurrentTarget() = runBlocking {
        val fixture = RoomLeaveFixture(coroutineContext)
        fixture.contexts += context()
        fixture.contexts += context()
        try {
            fixture.store.activate(target("!room:example.org"))
            fixture.store.request()
            awaitRoomLeave { fixture.store.state.value.pendingConfirmation != null }
            fixture.store.confirm()
            awaitRoomLeave { fixture.leftTargets.isNotEmpty() }

            assertEquals(listOf("!room:example.org"), fixture.leaveCalls)
            assertEquals("!room:example.org", fixture.leftTargets.single().roomId)
            assertEquals(listOf(true, false), fixture.cachedMemberRequests)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun newlyDetectedLastOwnerRequiresASecondConfirmation() = runBlocking {
        val fixture = RoomLeaveFixture(coroutineContext)
        fixture.contexts += context()
        fixture.contexts += context(isLastOwner = true, joinedMemberCount = 3L)
        fixture.contexts += context(isLastOwner = true, joinedMemberCount = 3L)
        try {
            fixture.store.activate(target("!room:example.org"))
            fixture.store.request()
            awaitRoomLeave { fixture.store.state.value.pendingConfirmation != null }
            fixture.store.confirm()
            awaitRoomLeave {
                fixture.store.state.value.pendingConfirmation
                    ?.context?.needsOwnershipWarning == true
            }

            assertTrue(fixture.leaveCalls.isEmpty())
            fixture.store.confirm()
            awaitRoomLeave { fixture.leftTargets.isNotEmpty() }
            assertEquals(listOf("!room:example.org"), fixture.leaveCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun latePreparationFromOldRouteCannotReplaceNewTarget() = runBlocking {
        val fixture = RoomLeaveFixture(coroutineContext)
        val deferred = CompletableDeferred<MatrixRoomLeaveContext>()
        fixture.blockedContext = deferred
        try {
            fixture.store.activate(target("!old:example.org"))
            fixture.store.request()
            awaitRoomLeave { fixture.store.state.value.isPreparing }
            fixture.store.activate(target("!new:example.org"))
            deferred.complete(context())
            yield()

            assertEquals("!new:example.org", fixture.store.state.value.target?.roomId)
            assertNull(fixture.store.state.value.pendingConfirmation)
            assertFalse(fixture.store.state.value.isPreparing)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun successfulMembershipReconciliationCompletesAfterWriteError() = runBlocking {
        val fixture = RoomLeaveFixture(coroutineContext)
        fixture.contexts += context()
        fixture.contexts += context()
        fixture.leaveFailure = IllegalStateException("transport failed after write")
        fixture.isLeftAfterFailure = true
        try {
            fixture.store.activate(target("!room:example.org"))
            fixture.store.request()
            awaitRoomLeave { fixture.store.state.value.pendingConfirmation != null }
            fixture.store.confirm()
            awaitRoomLeave { fixture.leftTargets.isNotEmpty() }

            assertEquals("!room:example.org", fixture.leftTargets.single().roomId)
            assertNull(fixture.store.state.value.error)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun writeErrorRemainsVisibleWhenMembershipDidNotChange() = runBlocking {
        val fixture = RoomLeaveFixture(coroutineContext)
        fixture.contexts += context()
        fixture.contexts += context()
        fixture.leaveFailure = IllegalStateException("write failed")
        try {
            fixture.store.activate(target("!room:example.org"))
            fixture.store.request()
            awaitRoomLeave { fixture.store.state.value.pendingConfirmation != null }
            fixture.store.confirm()
            awaitRoomLeave { fixture.store.state.value.error == RoomLeaveError.LEAVE }

            assertTrue(fixture.leftTargets.isEmpty())
            assertFalse(fixture.store.state.value.isLeaving)
        } finally {
            fixture.close()
        }
    }
}

private class RoomLeaveFixture(parentContext: CoroutineContext) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(parentContext + job)
    val contexts = ArrayDeque<MatrixRoomLeaveContext>()
    var blockedContext: CompletableDeferred<MatrixRoomLeaveContext>? = null
    val leaveCalls = mutableListOf<String>()
    val leftTargets = mutableListOf<RoomLeaveTarget>()
    val cachedMemberRequests = mutableListOf<Boolean>()
    var leaveFailure: Throwable? = null
    var isLeftAfterFailure = false

    val store = RoomLeaveStore(
        scope = scope,
        driver = RoomLeaveDriver(
            loadContext = { _, _, useCachedMembers ->
                cachedMemberRequests += useCachedMembers
                val blocked = blockedContext
                if (blocked != null) {
                    blockedContext = null
                    withContext(NonCancellable) { blocked.await() }
                } else {
                    contexts.removeFirst()
                }
            },
            leave = { _, roomId ->
                leaveCalls += roomId
                leaveFailure?.let { throw it }
            },
            isLeft = { _, _ -> isLeftAfterFailure }
        ),
        onLeft = leftTargets::add
    )

    suspend fun close() {
        job.cancelAndJoin()
    }
}

private fun target(roomId: String): RoomLeaveTarget {
    return RoomLeaveTarget(
        userId = "@alice:example.org",
        roomId = roomId,
        displayName = "Room",
        kind = MatrixRoomKind.GROUP,
        access = MatrixRoomAccess.PRIVATE
    )
}

private fun context(
    isLastOwner: Boolean = false,
    joinedMemberCount: Long = 2L
): MatrixRoomLeaveContext {
    return MatrixRoomLeaveContext(
        joinedMemberCount = joinedMemberCount,
        isLastOwner = isLastOwner,
        areCreatorsPrivileged = false
    )
}

private suspend fun awaitRoomLeave(condition: () -> Boolean) {
    withTimeout(1_000L) {
        while (!condition()) yield()
    }
}
