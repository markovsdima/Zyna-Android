package com.zyna.app.ui.chat

import com.zyna.app.data.matrix.MatrixRoomCallInfo
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCallInfoCoordinatorTest {
    @Test
    fun disabledTarget_startsOnceWhenObservationIsEnabled() = runBlocking {
        val fixture = CoordinatorFixture(coroutineContext)
        try {
            fixture.coordinator.activate(TARGET_A)
            yield()

            assertTrue(fixture.startedTargets.isEmpty())
            assertEquals(ChatCallInfoState(roomId = TARGET_A.roomId), fixture.coordinator.state.value)

            fixture.coordinator.setEnabled(true)
            yield()
            fixture.coordinator.setEnabled(true)
            yield()

            assertEquals(listOf(TARGET_A), fixture.startedTargets)
            assertEquals(
                listOf(
                    "chatCallInfoObserver target roomId=${TARGET_A.roomId}",
                    "chatCallInfoObserver enabled=true",
                    "chatCallInfoObserver start roomId=${TARGET_A.roomId}"
                ),
                fixture.logs
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun sameActiveTarget_isNotRestartedAndNewTargetCancelsPreviousJob() = runBlocking {
        val fixture = CoordinatorFixture(coroutineContext)
        try {
            fixture.coordinator.setEnabled(true)
            fixture.coordinator.activate(TARGET_A)
            yield()

            fixture.coordinator.activate(TARGET_A)
            yield()

            assertEquals(listOf(TARGET_A), fixture.startedTargets)
            assertTrue(fixture.stoppedTargets.isEmpty())

            fixture.coordinator.activate(TARGET_B)
            yield()

            assertEquals(listOf(TARGET_A, TARGET_B), fixture.startedTargets)
            assertEquals(listOf(TARGET_A), fixture.stoppedTargets)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun disablingPausesAndReenablingRestartsSavedTarget() = runBlocking {
        val fixture = CoordinatorFixture(coroutineContext)
        try {
            fixture.coordinator.setEnabled(true)
            fixture.coordinator.activate(TARGET_A)
            yield()

            fixture.coordinator.setEnabled(false)
            yield()

            assertEquals(listOf(TARGET_A), fixture.stoppedTargets)

            fixture.coordinator.setEnabled(true)
            yield()

            assertEquals(listOf(TARGET_A, TARGET_A), fixture.startedTargets)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun clearDropsTargetButPreservesEnabledState() = runBlocking {
        val fixture = CoordinatorFixture(coroutineContext)
        try {
            fixture.coordinator.setEnabled(true)
            fixture.coordinator.activate(TARGET_A)
            yield()

            fixture.coordinator.clear()
            yield()
            assertEquals(ChatCallInfoState(), fixture.coordinator.state.value)
            fixture.coordinator.setEnabled(false)
            fixture.coordinator.setEnabled(true)
            yield()

            assertEquals(listOf(TARGET_A), fixture.startedTargets)
            assertEquals(listOf(TARGET_A), fixture.stoppedTargets)

            fixture.coordinator.activate(TARGET_B)
            yield()

            assertEquals(listOf(TARGET_A, TARGET_B), fixture.startedTargets)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun callInfoState_isScopedToLatestTarget() = runBlocking {
        val fixture = CoordinatorFixture(coroutineContext)
        try {
            fixture.coordinator.setEnabled(true)
            fixture.coordinator.activate(TARGET_A)
            yield()

            fixture.emitCallInfo(observationIndex = 0, activeCallInfo(TARGET_A.roomId))
            assertEquals(TARGET_A.roomId, fixture.coordinator.state.value.roomId)
            assertEquals("Join", fixture.coordinator.state.value.banner?.actionLabel)

            fixture.coordinator.activate(TARGET_B)
            yield()
            assertEquals(ChatCallInfoState(roomId = TARGET_B.roomId), fixture.coordinator.state.value)

            fixture.emitCallInfo(observationIndex = 0, activeCallInfo(TARGET_A.roomId))
            assertEquals(ChatCallInfoState(roomId = TARGET_B.roomId), fixture.coordinator.state.value)

            fixture.emitCallInfo(observationIndex = 1, activeCallInfo(TARGET_B.roomId))
            assertEquals("Join", fixture.coordinator.state.value.banner?.actionLabel)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun pausePreservesBannerAndRejectsPreviousObservationAfterResume() = runBlocking {
        val fixture = CoordinatorFixture(coroutineContext)
        try {
            fixture.coordinator.setEnabled(true)
            fixture.coordinator.activate(TARGET_A)
            yield()
            fixture.emitCallInfo(observationIndex = 0, activeCallInfo(TARGET_A.roomId))
            val activeState = fixture.coordinator.state.value

            fixture.coordinator.setEnabled(false)
            yield()
            fixture.emitCallInfo(observationIndex = 0, inactiveCallInfo(TARGET_A.roomId))
            assertEquals(activeState, fixture.coordinator.state.value)

            fixture.coordinator.setEnabled(true)
            yield()
            fixture.emitCallInfo(observationIndex = 1, inactiveCallInfo(TARGET_A.roomId))
            assertEquals(null, fixture.coordinator.state.value.banner)

            fixture.emitCallInfo(observationIndex = 0, activeCallInfo(TARGET_A.roomId))
            assertEquals(null, fixture.coordinator.state.value.banner)

            fixture.coordinator.clear()
            fixture.emitCallInfo(observationIndex = 0, activeCallInfo(TARGET_A.roomId))
            assertEquals(ChatCallInfoState(), fixture.coordinator.state.value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun observationError_clearsOnlyCurrentTargetBanner() = runBlocking {
        val fixture = CoordinatorFixture(coroutineContext)
        val currentError = IllegalStateException("current failed")
        val staleError = IllegalStateException("stale failed")
        try {
            fixture.coordinator.setEnabled(true)
            fixture.coordinator.activate(TARGET_A)
            yield()
            fixture.emitCallInfo(observationIndex = 0, activeCallInfo(TARGET_A.roomId))
            fixture.failObservation(observationIndex = 0, currentError)

            assertEquals(ChatCallInfoState(roomId = TARGET_A.roomId), fixture.coordinator.state.value)

            fixture.coordinator.activate(TARGET_B)
            yield()
            fixture.emitCallInfo(observationIndex = 1, activeCallInfo(TARGET_B.roomId))
            val latestState = fixture.coordinator.state.value
            fixture.failObservation(observationIndex = 0, staleError)

            assertEquals(latestState, fixture.coordinator.state.value)
            assertEquals(
                listOf(TARGET_A to currentError, TARGET_A to staleError),
                fixture.observationErrors
            )
        } finally {
            fixture.close()
        }
    }

    private companion object {
        val TARGET_A = ChatCallInfoTarget(
            userId = "@me:example.org",
            roomId = "!room-a:example.org"
        )
        val TARGET_B = ChatCallInfoTarget(
            userId = "@me:example.org",
            roomId = "!room-b:example.org"
        )
    }
}

private fun activeCallInfo(roomId: String): MatrixRoomCallInfo {
    return MatrixRoomCallInfo(
        roomId = roomId,
        hasRoomCall = true,
        activeParticipantUserIds = listOf("@alice:example.org"),
        isAudioCall = true
    )
}

private fun inactiveCallInfo(roomId: String): MatrixRoomCallInfo {
    return MatrixRoomCallInfo(
        roomId = roomId,
        hasRoomCall = false,
        activeParticipantUserIds = emptyList(),
        isAudioCall = true
    )
}

private class CoordinatorFixture(parentContext: CoroutineContext) {
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(parentContext + scopeJob)

    val startedTargets = mutableListOf<ChatCallInfoTarget>()
    val stoppedTargets = mutableListOf<ChatCallInfoTarget>()
    val observations = mutableListOf<CoordinatorObservation>()
    val observationErrors = mutableListOf<Pair<ChatCallInfoTarget, Throwable>>()
    val logs = mutableListOf<String>()

    val coordinator = ChatCallInfoCoordinator(
        scope = scope,
        observeTarget = { target, onCallInfo, onError ->
            startedTargets += target
            observations += CoordinatorObservation(target, onCallInfo, onError)
            try {
                awaitCancellation()
            } finally {
                stoppedTargets += target
            }
        },
        projectBanner = { target, callInfo ->
            if (callInfo.hasRoomCall && callInfo.isAudioCall) {
                ChatCallBannerState(
                    title = target.roomId,
                    actionLabel = "Join",
                    isLocalCall = false,
                    remoteMembershipCount = callInfo.activeParticipantCount
                )
            } else {
                null
            }
        },
        onObservationError = { target, error -> observationErrors += target to error },
        onLog = logs::add
    )

    fun emitCallInfo(observationIndex: Int, callInfo: MatrixRoomCallInfo) {
        observations[observationIndex].onCallInfo(callInfo)
    }

    fun failObservation(observationIndex: Int, error: Throwable) {
        observations[observationIndex].onError(error)
    }

    suspend fun close() {
        scopeJob.cancelAndJoin()
    }
}

private data class CoordinatorObservation(
    val target: ChatCallInfoTarget,
    val onCallInfo: (MatrixRoomCallInfo) -> Unit,
    val onError: (Throwable) -> Unit
)
