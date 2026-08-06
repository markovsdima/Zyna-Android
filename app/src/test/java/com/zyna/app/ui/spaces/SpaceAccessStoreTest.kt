package com.zyna.app.ui.spaces

import com.zyna.app.data.matrix.MatrixRoomAliasAvailability
import com.zyna.app.data.matrix.MatrixRoomCreationAccess
import com.zyna.app.data.matrix.MatrixRoomDirectoryVisibility
import com.zyna.app.data.matrix.MatrixSpaceAccessJoinRule
import com.zyna.app.data.matrix.MatrixSpaceAccessPermissions
import com.zyna.app.data.matrix.MatrixSpaceAccessSnapshot
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

class SpaceAccessStoreTest {
    @Test
    fun restrictedRuleIsEditableOnlyForTheActiveParent() = runBlocking {
        val fixture = SpaceAccessFixture(coroutineContext)
        fixture.snapshot = snapshot(
            joinRule = MatrixSpaceAccessJoinRule.Restricted(
                roomIds = setOf(PARENT_ID),
                hasUnsupportedRules = false
            )
        )
        try {
            fixture.activate()

            assertEquals(SpaceAccessOption.PARENT_MEMBERS, fixture.store.state.value.access)
            assertTrue(fixture.store.state.value.isJoinRuleSupported)

            fixture.store.deactivate()
            fixture.activate(target(parentSpaceId = null))

            assertNull(fixture.store.state.value.access)
            assertFalse(fixture.store.state.value.isJoinRuleSupported)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun unsupportedJoinRuleDoesNotBlockAddressEditing() = runBlocking {
        val fixture = SpaceAccessFixture(coroutineContext)
        fixture.snapshot = snapshot(joinRule = MatrixSpaceAccessJoinRule.Unsupported)
        try {
            fixture.activate()
            fixture.store.setAddressLocalPart("story")
            awaitCondition {
                fixture.store.state.value.addressAvailability ==
                    SpaceAddressAvailability.AVAILABLE
            }

            assertTrue(fixture.store.state.value.canSave)
            fixture.store.save()
            awaitCondition { fixture.finished.isNotEmpty() }

            assertEquals(listOf("address:#story:example.org"), fixture.writeCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun unsupportedDirectoryVisibilityDoesNotBlockJoinRuleEditing() = runBlocking {
        val fixture = SpaceAccessFixture(coroutineContext)
        fixture.snapshot = snapshot(
            directoryVisibility = MatrixRoomDirectoryVisibility.UNSUPPORTED
        )
        try {
            fixture.activate()
            fixture.store.setAccess(SpaceAccessOption.PUBLIC)

            assertTrue(fixture.store.state.value.canSave)
            fixture.store.save()
            awaitCondition { fixture.finished.isNotEmpty() }

            assertEquals(listOf("access:Public"), fixture.writeCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun unsupportedJoinRuleDoesNotBlockHidingFromDirectory() = runBlocking {
        val fixture = SpaceAccessFixture(coroutineContext)
        fixture.snapshot = snapshot(
            joinRule = MatrixSpaceAccessJoinRule.Unsupported,
            canonicalAlias = "#story:example.org",
            directoryVisibility = MatrixRoomDirectoryVisibility.PUBLIC
        )
        try {
            fixture.activate()
            fixture.store.setDirectoryVisibility(false)

            assertTrue(fixture.store.state.value.canSave)
            fixture.store.save()
            awaitCondition { fixture.finished.isNotEmpty() }

            assertEquals(listOf("directory:false"), fixture.writeCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun closingPublicAccessHidesFromDirectoryBeforeChangingJoinRule() = runBlocking {
        val fixture = SpaceAccessFixture(coroutineContext)
        fixture.snapshot = snapshot(
            joinRule = MatrixSpaceAccessJoinRule.Public,
            canonicalAlias = "#story:example.org",
            directoryVisibility = MatrixRoomDirectoryVisibility.PUBLIC
        )
        try {
            fixture.activate()
            fixture.store.setAccess(SpaceAccessOption.PRIVATE)

            assertFalse(fixture.store.state.value.editIsVisibleInDirectory)
            fixture.store.save()
            awaitCondition { fixture.finished.isNotEmpty() }

            assertEquals(listOf("directory:false", "access:Private"), fixture.writeCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun publishingPublicSpaceWritesAddressThenAccessThenDirectory() = runBlocking {
        val fixture = SpaceAccessFixture(coroutineContext)
        try {
            fixture.activate()
            fixture.store.setAccess(SpaceAccessOption.PUBLIC)
            fixture.store.setAddressLocalPart("new-story")
            awaitCondition {
                fixture.store.state.value.addressAvailability ==
                    SpaceAddressAvailability.AVAILABLE
            }
            fixture.store.setDirectoryVisibility(true)

            fixture.store.save()
            awaitCondition { fixture.finished.isNotEmpty() }

            assertEquals(
                listOf(
                    "address:#new-story:example.org",
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
    fun freshPermissionRevocationStopsBeforeMutation() = runBlocking {
        val fixture = SpaceAccessFixture(coroutineContext)
        try {
            fixture.activate()
            fixture.store.setAccess(SpaceAccessOption.PUBLIC)
            fixture.snapshot = fixture.snapshot.copy(
                permissions = MatrixSpaceAccessPermissions(
                    canChangeJoinRule = false,
                    canChangeAddress = true,
                    canChangeDirectoryVisibility = true
                )
            )

            fixture.store.save()
            awaitCondition {
                fixture.store.state.value.error == SpaceAccessError.PERMISSION_CHANGED
            }

            assertTrue(fixture.writeCalls.isEmpty())
            assertFalse(fixture.store.state.value.canChangeAccess)
            assertTrue(fixture.store.state.value.hasAccessChange)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun directoryPermissionIsPreflightedIndependentlyFromAddressPermission() = runBlocking {
        val fixture = SpaceAccessFixture(coroutineContext)
        fixture.snapshot = snapshot(
            joinRule = MatrixSpaceAccessJoinRule.Public,
            canonicalAlias = "#story:example.org"
        )
        try {
            fixture.activate()
            fixture.store.setDirectoryVisibility(true)
            fixture.snapshot = fixture.snapshot.copy(
                permissions = fixture.snapshot.permissions.copy(
                    canChangeDirectoryVisibility = false
                )
            )

            fixture.store.save()
            awaitCondition {
                fixture.store.state.value.error == SpaceAccessError.PERMISSION_CHANGED
            }

            assertTrue(fixture.store.state.value.canChangeAddress)
            assertFalse(fixture.store.state.value.canChangeDirectoryVisibility)
            assertTrue(fixture.writeCalls.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun restoredLivePermissionClearsTheRevocationError() = runBlocking {
        val fixture = SpaceAccessFixture(coroutineContext)
        try {
            fixture.activate()
            fixture.store.setAccess(SpaceAccessOption.PUBLIC)
            fixture.emitPermissions(
                fixture.snapshot.permissions.copy(canChangeJoinRule = false)
            )
            awaitCondition {
                fixture.store.state.value.error == SpaceAccessError.PERMISSION_CHANGED
            }

            fixture.emitPermissions(fixture.snapshot.permissions)
            awaitCondition {
                fixture.store.state.value.canChangeAccess &&
                    fixture.store.state.value.error == null
            }

            assertTrue(fixture.store.state.value.canSave)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun partialSaveDoesNotRepeatDirectoryWrite() = runBlocking {
        val fixture = SpaceAccessFixture(coroutineContext)
        fixture.snapshot = snapshot(
            joinRule = MatrixSpaceAccessJoinRule.Public,
            canonicalAlias = "#story:example.org",
            directoryVisibility = MatrixRoomDirectoryVisibility.PUBLIC
        )
        var accessAttempts = 0
        fixture.setAccessBehavior = { access ->
            accessAttempts += 1
            if (accessAttempts == 1) error("join-rule failed")
            fixture.applyAccess(access)
        }
        try {
            fixture.activate()
            fixture.store.setAccess(SpaceAccessOption.PRIVATE)

            fixture.store.save()
            awaitCondition {
                fixture.store.state.value.error == SpaceAccessError.PARTIAL_SAVE
            }

            val partial = fixture.store.state.value
            assertFalse(partial.hasDirectoryChange)
            assertTrue(partial.hasAccessChange)
            assertTrue(partial.canSave)

            fixture.store.save()
            awaitCondition { fixture.finished.isNotEmpty() }

            assertEquals(1, fixture.writeCalls.count { it == "directory:false" })
            assertEquals(2, accessAttempts)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun serverAppliedJoinRuleAfterReportedFailureFinishesSuccessfully() = runBlocking {
        val fixture = SpaceAccessFixture(coroutineContext)
        fixture.setAccessBehavior = { access ->
            fixture.applyAccess(access)
            error("SDK reported failure")
        }
        try {
            fixture.activate()
            fixture.store.setAccess(SpaceAccessOption.PUBLIC)

            fixture.store.save()
            awaitCondition { fixture.finished.isNotEmpty() }

            assertEquals(listOf(target() to true), fixture.finished)
            assertEquals(SpaceAccessState(), fixture.store.state.value)
            assertTrue(fixture.warnings.any { it.first.contains("server confirmed") })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun concurrentRemoteChangeRequiresASecondChoice() = runBlocking {
        val fixture = SpaceAccessFixture(coroutineContext)
        try {
            fixture.activate()
            fixture.store.setAccess(SpaceAccessOption.PUBLIC)
            fixture.snapshot = fixture.snapshot.copy(
                joinRule = MatrixSpaceAccessJoinRule.Restricted(
                    roomIds = setOf(PARENT_ID),
                    hasUnsupportedRules = false
                )
            )

            fixture.store.save()
            awaitCondition {
                fixture.store.state.value.error == SpaceAccessError.REMOTE_CHANGED
            }

            assertEquals(SpaceAccessOption.PARENT_MEMBERS, fixture.store.state.value.access)
            assertEquals(SpaceAccessOption.PARENT_MEMBERS, fixture.store.state.value.editAccess)
            assertFalse(fixture.store.state.value.hasUnsavedChanges)
            assertTrue(fixture.writeCalls.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun untouchedFieldsAdoptFreshRemoteValuesInsteadOfBeingWrittenBack() = runBlocking {
        val fixture = SpaceAccessFixture(coroutineContext)
        try {
            fixture.activate()
            fixture.store.setAddressLocalPart("story")
            awaitCondition {
                fixture.store.state.value.addressAvailability ==
                    SpaceAddressAvailability.AVAILABLE
            }
            fixture.snapshot = fixture.snapshot.copy(
                joinRule = MatrixSpaceAccessJoinRule.Public,
                directoryVisibility = MatrixRoomDirectoryVisibility.PUBLIC
            )

            fixture.store.save()
            awaitCondition { fixture.finished.isNotEmpty() }

            assertEquals(listOf("address:#story:example.org"), fixture.writeCalls)
            assertEquals(MatrixSpaceAccessJoinRule.Public, fixture.snapshot.joinRule)
            assertEquals(
                MatrixRoomDirectoryVisibility.PUBLIC,
                fixture.snapshot.directoryVisibility
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun aliasOwnedByTheSameSpaceCanBeRecoveredAndSaved() = runBlocking {
        val fixture = SpaceAccessFixture(coroutineContext)
        fixture.aliasAvailability = MatrixRoomAliasAvailability.OWNED_BY_ROOM
        try {
            fixture.activate()
            fixture.store.setAddressLocalPart("recovered")
            awaitCondition {
                fixture.store.state.value.addressAvailability ==
                    SpaceAddressAvailability.OWNED_BY_SPACE
            }

            assertTrue(fixture.store.state.value.canSave)
            fixture.store.save()
            awaitCondition { fixture.finished.isNotEmpty() }

            assertEquals(
                listOf("address:#recovered:example.org"),
                fixture.writeCalls
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun clearingExistingAddressExplainsThatRemovalIsUnsupported() = runBlocking {
        val fixture = SpaceAccessFixture(coroutineContext)
        fixture.snapshot = snapshot(canonicalAlias = "#story:example.org")
        try {
            fixture.activate()
            fixture.store.setAddressLocalPart("")

            assertEquals(
                SpaceAddressAvailability.REMOVAL_UNSUPPORTED,
                fixture.store.state.value.addressAvailability
            )
            assertTrue(fixture.store.state.value.hasAddressChange)
            assertFalse(fixture.store.state.value.canSave)
            assertTrue(fixture.writeCalls.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun unsavedChangesRequireDiscardConfirmation() = runBlocking {
        val fixture = SpaceAccessFixture(coroutineContext)
        try {
            fixture.activate()
            fixture.store.setAccess(SpaceAccessOption.PUBLIC)

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

private class SpaceAccessFixture(parentContext: CoroutineContext) {
    private val scope = CoroutineScope(parentContext + SupervisorJob())
    private val permissionUpdates = MutableSharedFlow<MatrixSpaceAccessPermissions>(replay = 1)
    val writeCalls = CopyOnWriteArrayList<String>()
    val finished = CopyOnWriteArrayList<Pair<SpaceAccessTarget, Boolean>>()
    val warnings = CopyOnWriteArrayList<Pair<String, Throwable>>()

    var snapshot = snapshot()
    var aliasAvailability = MatrixRoomAliasAvailability.AVAILABLE
    var setAccessBehavior: suspend (MatrixRoomCreationAccess) -> Unit = { applyAccess(it) }

    val store = SpaceAccessStore(
        scope = scope,
        driver = SpaceAccessDriver(
            load = { _, _ -> snapshot },
            observePermissions = { _, _ -> permissionUpdates },
            isAliasValid = { alias -> alias.startsWith("#") && ':' in alias },
            checkAlias = { _, _, _ -> aliasAvailability },
            setAddress = { _, _, alias ->
                writeCalls += "address:$alias"
                snapshot = snapshot.copy(
                    canonicalAlias = alias,
                    alternativeAliases = emptyList()
                )
            },
            setJoinRule = { _, _, access ->
                writeCalls += "access:${when (access) {
                    MatrixRoomCreationAccess.Private -> "Private"
                    MatrixRoomCreationAccess.Public -> "Public"
                    is MatrixRoomCreationAccess.Restricted -> "Restricted"
                }}"
                setAccessBehavior(access)
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

    suspend fun activate(target: SpaceAccessTarget = target()) {
        permissionUpdates.emit(snapshot.permissions)
        store.activate(target)
        awaitCondition { !store.state.value.isLoading }
    }

    suspend fun emitPermissions(permissions: MatrixSpaceAccessPermissions) {
        permissionUpdates.emit(permissions)
    }

    fun applyAccess(access: MatrixRoomCreationAccess) {
        snapshot = snapshot.copy(
            joinRule = when (access) {
                MatrixRoomCreationAccess.Private -> MatrixSpaceAccessJoinRule.InviteOnly
                MatrixRoomCreationAccess.Public -> MatrixSpaceAccessJoinRule.Public
                is MatrixRoomCreationAccess.Restricted ->
                    MatrixSpaceAccessJoinRule.Restricted(
                        roomIds = setOf(access.parentSpaceId),
                        hasUnsupportedRules = false
                    )
            }
        )
    }

    fun close() {
        scope.cancel()
    }
}

private fun snapshot(
    joinRule: MatrixSpaceAccessJoinRule = MatrixSpaceAccessJoinRule.InviteOnly,
    canonicalAlias: String? = null,
    directoryVisibility: MatrixRoomDirectoryVisibility =
        MatrixRoomDirectoryVisibility.PRIVATE,
    permissions: MatrixSpaceAccessPermissions = MatrixSpaceAccessPermissions(
        canChangeJoinRule = true,
        canChangeAddress = true,
        canChangeDirectoryVisibility = true
    )
): MatrixSpaceAccessSnapshot {
    return MatrixSpaceAccessSnapshot(
        roomId = SPACE_ID,
        joinRule = joinRule,
        canonicalAlias = canonicalAlias,
        alternativeAliases = emptyList(),
        directoryVisibility = directoryVisibility,
        serverName = "example.org",
        permissions = permissions
    )
}

private fun target(parentSpaceId: String? = PARENT_ID): SpaceAccessTarget {
    return SpaceAccessTarget(
        userId = USER_ID,
        spaceId = SPACE_ID,
        parentSpaceId = parentSpaceId,
        displayName = "Track"
    )
}

private suspend fun awaitCondition(condition: () -> Boolean) {
    repeat(200) {
        if (condition()) return
        delay(5)
    }
    error("Condition was not met")
}

private const val USER_ID = "@me:example.org"
private const val SPACE_ID = "!track:example.org"
private const val PARENT_ID = "!story:example.org"
