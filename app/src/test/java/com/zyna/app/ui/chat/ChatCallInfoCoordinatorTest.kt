package com.zyna.app.ui.chat

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

private class CoordinatorFixture(parentContext: CoroutineContext) {
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(parentContext + scopeJob)

    val startedTargets = mutableListOf<ChatCallInfoTarget>()
    val stoppedTargets = mutableListOf<ChatCallInfoTarget>()
    val logs = mutableListOf<String>()

    val coordinator = ChatCallInfoCoordinator(
        scope = scope,
        observeTarget = { target ->
            startedTargets += target
            try {
                awaitCancellation()
            } finally {
                stoppedTargets += target
            }
        },
        onLog = logs::add
    )

    suspend fun close() {
        scopeJob.cancelAndJoin()
    }
}
