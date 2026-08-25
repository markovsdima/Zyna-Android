package com.zyna.app.data.matrix

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatrixRoomAliasMutationTest {
    @Test
    fun routeCancellationDoesNotInterruptTheCriticalReplacement() = runBlocking {
        val mappingCreationStarted = CompletableDeferred<Unit>()
        val continueCreation = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()

        val mutation = launch {
            replaceOwnedRoomAliasSafely(
                roomId = ROOM_ID,
                previousAlias = OLD_ALIAS,
                desiredAlias = NEW_ALIAS,
                ensureDesiredAliasMapping = {
                    calls += "ensure-new-mapping"
                    mappingCreationStarted.complete(Unit)
                    continueCreation.await()
                },
                updateAliasState = { calls += "update-state" },
                resolvePreviousAliasRoomId = { ROOM_ID },
                removePreviousAliasMapping = {
                    calls += "remove-old-mapping"
                    true
                }
            )
        }

        mappingCreationStarted.await()
        mutation.cancel()
        continueCreation.complete(Unit)
        mutation.join()

        assertEquals(
            listOf("ensure-new-mapping", "update-state", "remove-old-mapping"),
            calls
        )
    }

    @Test
    fun equivalentServerNameCaseDoesNotRemoveTheReplacementMapping() = runBlocking {
        var didResolvePreviousAlias = false
        var didRemovePreviousAlias = false

        replaceOwnedRoomAliasSafely(
            roomId = ROOM_ID,
            previousAlias = "#town:Example.ORG",
            desiredAlias = "#town:example.org",
            ensureDesiredAliasMapping = { Unit },
            updateAliasState = { Unit },
            resolvePreviousAliasRoomId = {
                didResolvePreviousAlias = true
                ROOM_ID
            },
            removePreviousAliasMapping = {
                didRemovePreviousAlias = true
                true
            }
        )

        assertFalse(didResolvePreviousAlias)
        assertFalse(didRemovePreviousAlias)
    }

    @Test
    fun canonicalServerNamePreservesTheAliasLocalpart() {
        assertEquals(
            "#Town:example.org",
            "#Town:Example.ORG".withCanonicalMatrixServerName("example.org")
        )
        assertTrue(matrixRoomAliasesEqual("#Town:Example.ORG", "#Town:example.org"))
        assertFalse(matrixRoomAliasesEqual("#Town:example.org", "#town:example.org"))
    }

    @Test
    fun routeCancellationDoesNotInterruptTheCriticalMutation() = runBlocking {
        val mappingRemovalStarted = CompletableDeferred<Unit>()
        val continueRemoval = CompletableDeferred<Unit>()
        var didClearState = false

        val mutation = launch {
            removeOwnedRoomAliasSafely(
                roomId = ROOM_ID,
                resolveAliasRoomId = { ROOM_ID },
                removeAliasMapping = {
                    mappingRemovalStarted.complete(Unit)
                    continueRemoval.await()
                    true
                },
                clearAliasState = { didClearState = true },
                ensureAliasMapping = { Unit },
                restoreAliasState = { Unit }
            )
        }

        mappingRemovalStarted.await()
        mutation.cancel()
        continueRemoval.complete(Unit)
        mutation.join()

        assertTrue(didClearState)
    }

    @Test
    fun successfulRemovalClearsMappingBeforeState() = runBlocking {
        var resolvedRoomId: String? = ROOM_ID
        val calls = mutableListOf<String>()

        removeOwnedRoomAliasSafely(
            roomId = ROOM_ID,
            resolveAliasRoomId = { resolvedRoomId },
            removeAliasMapping = {
                calls += "remove-mapping"
                resolvedRoomId = null
                true
            },
            clearAliasState = { calls += "clear-state" },
            ensureAliasMapping = { calls += "restore-mapping" },
            restoreAliasState = { calls += "restore-state" }
        )

        assertEquals(listOf("remove-mapping", "clear-state"), calls)
    }

    @Test
    fun removalReportedAsFailedContinuesWhenMappingIsActuallyGone() = runBlocking {
        var resolvedRoomId: String? = ROOM_ID
        val calls = mutableListOf<String>()

        removeOwnedRoomAliasSafely(
            roomId = ROOM_ID,
            resolveAliasRoomId = { resolvedRoomId },
            removeAliasMapping = {
                calls += "remove-mapping"
                resolvedRoomId = null
                error("SDK reported failure")
            },
            clearAliasState = { calls += "clear-state" },
            ensureAliasMapping = { calls += "restore-mapping" },
            restoreAliasState = { calls += "restore-state" }
        )

        assertEquals(listOf("remove-mapping", "clear-state"), calls)
    }

    @Test
    fun failedStateWriteRestoresMappingBeforeOriginalState() = runBlocking {
        var resolvedRoomId: String? = ROOM_ID
        val calls = mutableListOf<String>()

        val result = runCatching {
            removeOwnedRoomAliasSafely(
                roomId = ROOM_ID,
                resolveAliasRoomId = { resolvedRoomId },
                removeAliasMapping = {
                    calls += "remove-mapping"
                    resolvedRoomId = null
                    true
                },
                clearAliasState = {
                    calls += "clear-state"
                    error("state failed")
                },
                ensureAliasMapping = {
                    calls += "restore-mapping"
                    resolvedRoomId = ROOM_ID
                },
                restoreAliasState = { calls += "restore-state" }
            )
        }

        assertTrue(result.isFailure)
        assertEquals(
            listOf("remove-mapping", "clear-state", "restore-mapping", "restore-state"),
            calls
        )
        assertEquals(ROOM_ID, resolvedRoomId)
    }

    @Test
    fun stateIsNotRestoredWhenMappingCannotBeRestored() = runBlocking {
        var resolvedRoomId: String? = ROOM_ID
        var didTryRestoreMapping = false
        var didRestoreState = false

        val result = runCatching {
            removeOwnedRoomAliasSafely(
                roomId = ROOM_ID,
                resolveAliasRoomId = { resolvedRoomId },
                removeAliasMapping = {
                    resolvedRoomId = null
                    true
                },
                clearAliasState = { error("state failed") },
                ensureAliasMapping = {
                    didTryRestoreMapping = true
                    error("mapping restore failed")
                },
                restoreAliasState = { didRestoreState = true }
            )
        }

        assertTrue(result.isFailure)
        assertTrue(didTryRestoreMapping)
        assertFalse(didRestoreState)
    }

    @Test
    fun definiteMappingFailureStopsBeforeChangingState() = runBlocking {
        var didClearState = false

        val result = runCatching {
            removeOwnedRoomAliasSafely(
                roomId = ROOM_ID,
                resolveAliasRoomId = { ROOM_ID },
                removeAliasMapping = { false },
                clearAliasState = { didClearState = true },
                ensureAliasMapping = { Unit },
                restoreAliasState = { Unit }
            )
        }

        assertTrue(result.isFailure)
        assertFalse(didClearState)
    }
}

private const val ROOM_ID = "!room:example.org"
private const val OLD_ALIAS = "#old:example.org"
private const val NEW_ALIAS = "#new:example.org"
