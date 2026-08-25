package com.zyna.app.ui.roomdetails

import com.zyna.app.data.matrix.MatrixRoomAliasAvailability
import com.zyna.app.data.matrix.MatrixRoomDirectoryVisibility
import com.zyna.app.data.matrix.MatrixRoomHistoryVisibility
import com.zyna.app.data.matrix.MatrixRoomSecurityJoinRule
import com.zyna.app.data.matrix.MatrixRoomSecurityPermissions
import com.zyna.app.data.matrix.MatrixRoomSecuritySnapshot
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomSecurityStoreTest {
    @Test
    fun serverNameCaseDoesNotCreateAnAddressDraft() = runBlocking {
        val fixture = RoomSecurityFixture(coroutineContext)
        fixture.snapshot = snapshot(canonicalAlias = "#Town:Example.ORG")
        try {
            fixture.activate()

            val state = fixture.store.state.value
            assertEquals("#Town:example.org", state.localAddress)
            assertEquals("#Town:example.org", state.editFullAddress)
            assertFalse(state.hasAddressChange)
            assertFalse(state.hasUnsavedChanges)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun unsupportedJoinRuleDoesNotBlockHistoryEditing() = runBlocking {
        val fixture = RoomSecurityFixture(coroutineContext)
        fixture.snapshot = snapshot(joinRule = MatrixRoomSecurityJoinRule.Unsupported)
        try {
            fixture.activate()
            fixture.store.setHistoryVisibility(MatrixRoomHistoryVisibility.JOINED)

            assertNull(fixture.store.state.value.editAccess)
            assertTrue(fixture.store.state.value.canSave)
            fixture.store.save()
            awaitCondition { fixture.finished.isNotEmpty() }

            assertEquals(listOf("history:JOINED"), fixture.writeCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun supportedRestrictedRulePreservesAllAuthorizedParents() = runBlocking {
        val fixture = RoomSecurityFixture(coroutineContext)
        fixture.snapshot = snapshot(
            joinRule = MatrixRoomSecurityJoinRule.Restricted(
                spaceIds = setOf(STORY_A_ID, STORY_B_ID),
                hasUnsupportedRules = false
            )
        )
        try {
            fixture.activate()

            assertEquals(
                RoomSecurityAccessOption.PARENT_SPACE_MEMBERS,
                fixture.store.state.value.editAccess
            )
            assertEquals(
                setOf(STORY_A_ID, STORY_B_ID),
                fixture.store.state.value.editAuthorizedSpaceIds
            )
            assertTrue(fixture.store.state.value.isJoinRuleSupported)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun publishingPublicRoomWritesAddressThenAccessThenDirectory() = runBlocking {
        val fixture = RoomSecurityFixture(coroutineContext)
        try {
            fixture.activate()
            fixture.store.setAccess(RoomSecurityAccessOption.PUBLIC)
            fixture.store.setAddressLocalPart("town-square")
            awaitCondition {
                fixture.store.state.value.addressAvailability ==
                    RoomSecurityAddressAvailability.AVAILABLE
            }
            fixture.store.setDirectoryVisibility(true)

            fixture.store.save()
            awaitCondition { fixture.finished.isNotEmpty() }

            assertEquals(
                listOf(
                    "address:#town-square:example.org",
                    "access:Public",
                    "directory:true"
                ),
                fixture.writeCalls
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun closingPublicRoomHidesItBeforeChangingAccess() = runBlocking {
        val fixture = RoomSecurityFixture(coroutineContext)
        fixture.snapshot = snapshot(
            joinRule = MatrixRoomSecurityJoinRule.Public,
            canonicalAlias = "#town-square:example.org",
            directoryVisibility = MatrixRoomDirectoryVisibility.PUBLIC
        )
        try {
            fixture.activate()
            fixture.store.setAccess(RoomSecurityAccessOption.INVITE_ONLY)

            assertFalse(fixture.store.state.value.editIsVisibleInDirectory)
            fixture.store.save()
            awaitCondition { fixture.finished.isNotEmpty() }

            assertEquals(
                listOf("directory:false", "access:InviteOnly"),
                fixture.writeCalls
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun enablingEncryptionNarrowsWorldReadableHistoryBeforeEncryption() = runBlocking {
        val fixture = RoomSecurityFixture(coroutineContext)
        fixture.snapshot = snapshot(
            joinRule = MatrixRoomSecurityJoinRule.Public,
            historyVisibility = MatrixRoomHistoryVisibility.WORLD_READABLE
        )
        try {
            fixture.activate()
            fixture.store.requestEncryptionChange(true)
            assertTrue(fixture.store.state.value.isEncryptionConfirmationVisible)

            fixture.store.confirmEncryption()
            assertEquals(
                MatrixRoomHistoryVisibility.INVITED,
                fixture.store.state.value.editHistoryVisibility
            )
            fixture.store.save()
            awaitCondition { fixture.finished.isNotEmpty() }

            assertEquals(listOf("history:INVITED", "encryption:true"), fixture.writeCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun removingPublishedAddressHidesRoomBeforeRemovingAlias() = runBlocking {
        val fixture = RoomSecurityFixture(coroutineContext)
        fixture.snapshot = snapshot(
            joinRule = MatrixRoomSecurityJoinRule.Public,
            canonicalAlias = "#town-square:example.org",
            directoryVisibility = MatrixRoomDirectoryVisibility.PUBLIC
        )
        try {
            fixture.activate()
            fixture.store.setAddressLocalPart("")

            assertEquals(
                RoomSecurityAddressAvailability.REMOVAL_READY,
                fixture.store.state.value.addressAvailability
            )
            assertFalse(fixture.store.state.value.editIsVisibleInDirectory)
            fixture.store.save()
            awaitCondition { fixture.finished.isNotEmpty() }

            assertEquals(listOf("directory:false", "address:null"), fixture.writeCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun freshPermissionRevocationStopsBeforeMutation() = runBlocking {
        val fixture = RoomSecurityFixture(coroutineContext)
        try {
            fixture.activate()
            fixture.store.setHistoryVisibility(MatrixRoomHistoryVisibility.JOINED)
            fixture.snapshot = fixture.snapshot.copy(
                permissions = fixture.snapshot.permissions.copy(
                    canChangeHistoryVisibility = false
                )
            )

            fixture.store.save()
            awaitCondition {
                fixture.store.state.value.error == RoomSecurityError.PERMISSION_CHANGED
            }

            assertTrue(fixture.writeCalls.isEmpty())
            assertFalse(fixture.store.state.value.canChangeHistoryVisibility)
            assertTrue(fixture.store.state.value.hasHistoryChange)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun concurrentRemoteChangeRequiresASecondChoice() = runBlocking {
        val fixture = RoomSecurityFixture(coroutineContext)
        try {
            fixture.activate()
            fixture.store.setAccess(RoomSecurityAccessOption.PUBLIC)
            fixture.snapshot = fixture.snapshot.copy(
                joinRule = MatrixRoomSecurityJoinRule.Restricted(
                    spaceIds = setOf(STORY_A_ID),
                    hasUnsupportedRules = false
                )
            )

            fixture.store.save()
            awaitCondition {
                fixture.store.state.value.error == RoomSecurityError.REMOTE_CHANGED
            }

            assertEquals(
                RoomSecurityAccessOption.PARENT_SPACE_MEMBERS,
                fixture.store.state.value.editAccess
            )
            assertFalse(fixture.store.state.value.hasUnsavedChanges)
            assertTrue(fixture.writeCalls.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun serverAppliedWriteAfterReportedFailureFinishesSuccessfully() = runBlocking {
        val fixture = RoomSecurityFixture(coroutineContext)
        fixture.setJoinRuleBehavior = { rule ->
            fixture.applyJoinRule(rule)
            error("SDK reported failure")
        }
        try {
            fixture.activate()
            fixture.store.setAccess(RoomSecurityAccessOption.PUBLIC)

            fixture.store.save()
            awaitCondition { fixture.finished.isNotEmpty() }

            assertEquals(listOf(target() to true), fixture.finished)
            assertEquals(RoomSecurityState(), fixture.store.state.value)
            assertTrue(fixture.warnings.any { it.first.contains("server confirmed") })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun partialSaveRetriesOnlyTheRemainingFields() = runBlocking {
        val fixture = RoomSecurityFixture(coroutineContext)
        fixture.snapshot = snapshot(
            joinRule = MatrixRoomSecurityJoinRule.Public,
            canonicalAlias = "#town-square:example.org",
            directoryVisibility = MatrixRoomDirectoryVisibility.PUBLIC
        )
        var accessAttempts = 0
        fixture.setJoinRuleBehavior = { rule ->
            accessAttempts += 1
            if (accessAttempts == 1) error("join-rule failed")
            fixture.applyJoinRule(rule)
        }
        try {
            fixture.activate()
            fixture.store.setAccess(RoomSecurityAccessOption.INVITE_ONLY)

            fixture.store.save()
            awaitCondition {
                fixture.store.state.value.error == RoomSecurityError.PARTIAL_SAVE
            }

            assertFalse(fixture.store.state.value.hasDirectoryChange)
            assertTrue(fixture.store.state.value.hasAccessChange)
            fixture.store.save()
            awaitCondition { fixture.finished.isNotEmpty() }

            assertEquals(1, fixture.writeCalls.count { it == "directory:false" })
            assertEquals(2, accessAttempts)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun unsavedChangesRequireDiscardConfirmation() = runBlocking {
        val fixture = RoomSecurityFixture(coroutineContext)
        try {
            fixture.activate()
            fixture.store.setHistoryVisibility(MatrixRoomHistoryVisibility.JOINED)

            fixture.store.requestExit()
            assertTrue(fixture.store.state.value.isDiscardConfirmationVisible)
            assertTrue(fixture.finished.isEmpty())

            fixture.store.confirmDiscard()
            assertEquals(listOf(target() to false), fixture.finished)
        } finally {
            fixture.close()
        }
    }
}

private class RoomSecurityFixture(parentContext: CoroutineContext) {
    private val scope = CoroutineScope(parentContext + SupervisorJob())
    private val permissionUpdates = MutableSharedFlow<MatrixRoomSecurityPermissions>(replay = 1)
    val writeCalls = CopyOnWriteArrayList<String>()
    val finished = CopyOnWriteArrayList<Pair<RoomSecurityTarget, Boolean>>()
    val warnings = CopyOnWriteArrayList<Pair<String, Throwable>>()

    var snapshot = snapshot()
    var aliasAvailability = MatrixRoomAliasAvailability.AVAILABLE
    var setJoinRuleBehavior: suspend (MatrixRoomSecurityJoinRule) -> Unit = {
        applyJoinRule(it)
    }

    val store = RoomSecurityStore(
        scope = scope,
        driver = RoomSecurityDriver(
            load = { _, _ -> snapshot },
            loadParentSpaces = { _, _ -> emptyList() },
            observePermissions = { _, _ -> permissionUpdates },
            isAliasValid = { alias -> alias.startsWith("#") && ':' in alias },
            checkAlias = { _, _, _ -> aliasAvailability },
            setAddress = { _, _, address ->
                writeCalls += "address:$address"
                snapshot = snapshot.copy(
                    canonicalAlias = address,
                    alternativeAliases = emptyList()
                )
            },
            setJoinRule = { _, _, rule ->
                writeCalls += "access:${rule.label()}"
                setJoinRuleBehavior(rule)
            },
            setHistoryVisibility = { _, _, visibility ->
                writeCalls += "history:$visibility"
                snapshot = snapshot.copy(historyVisibility = visibility)
            },
            enableEncryption = { _, _ ->
                writeCalls += "encryption:true"
                snapshot = snapshot.copy(isEncrypted = true)
            },
            setDirectoryVisibility = { _, _, isVisible ->
                writeCalls += "directory:$isVisible"
                snapshot = snapshot.copy(
                    directoryVisibility = if (isVisible) {
                        MatrixRoomDirectoryVisibility.PUBLIC
                    } else {
                        MatrixRoomDirectoryVisibility.PRIVATE
                    }
                )
            }
        ),
        onFinished = { target, didSave -> finished += target to didSave },
        onWarning = { message, error -> warnings += message to error },
        aliasCheckDebounceMillis = 0L
    )

    suspend fun activate() {
        permissionUpdates.emit(snapshot.permissions)
        store.activate(target())
        awaitCondition { !store.state.value.isLoading }
    }

    fun applyJoinRule(rule: MatrixRoomSecurityJoinRule) {
        snapshot = snapshot.copy(joinRule = rule)
    }

    fun close() {
        scope.cancel()
    }
}

private fun snapshot(
    joinRule: MatrixRoomSecurityJoinRule = MatrixRoomSecurityJoinRule.InviteOnly,
    historyVisibility: MatrixRoomHistoryVisibility = MatrixRoomHistoryVisibility.SHARED,
    isEncrypted: Boolean? = false,
    canonicalAlias: String? = null,
    directoryVisibility: MatrixRoomDirectoryVisibility =
        MatrixRoomDirectoryVisibility.PRIVATE,
    permissions: MatrixRoomSecurityPermissions = MatrixRoomSecurityPermissions(
        canChangeJoinRule = true,
        canChangeHistoryVisibility = true,
        canEnableEncryption = true,
        canChangeAddress = true,
        canChangeDirectoryVisibility = true
    )
): MatrixRoomSecuritySnapshot {
    return MatrixRoomSecuritySnapshot(
        roomId = ROOM_ID,
        joinRule = joinRule,
        historyVisibility = historyVisibility,
        isEncrypted = isEncrypted,
        canonicalAlias = canonicalAlias,
        alternativeAliases = emptyList(),
        directoryVisibility = directoryVisibility,
        serverName = "example.org",
        permissions = permissions
    )
}

private fun target(): RoomSecurityTarget {
    return RoomSecurityTarget(
        userId = USER_ID,
        roomId = ROOM_ID,
        displayName = "Town square"
    )
}

private fun MatrixRoomSecurityJoinRule.label(): String {
    return when (this) {
        MatrixRoomSecurityJoinRule.InviteOnly -> "InviteOnly"
        MatrixRoomSecurityJoinRule.Public -> "Public"
        is MatrixRoomSecurityJoinRule.Restricted -> "Restricted"
        MatrixRoomSecurityJoinRule.Unsupported -> "Unsupported"
    }
}

private suspend fun awaitCondition(condition: () -> Boolean) {
    repeat(200) {
        if (condition()) return
        delay(5)
    }
    error("Condition was not met")
}

private const val USER_ID = "@me:example.org"
private const val ROOM_ID = "!room:example.org"
private const val STORY_A_ID = "!story-a:example.org"
private const val STORY_B_ID = "!story-b:example.org"
