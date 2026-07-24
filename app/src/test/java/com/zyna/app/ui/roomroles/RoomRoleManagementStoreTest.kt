package com.zyna.app.ui.roomroles

import com.zyna.app.data.matrix.MatrixRoomMember
import com.zyna.app.data.matrix.MatrixRoomMemberMembership
import com.zyna.app.data.matrix.MatrixRoomMemberRole
import com.zyna.app.data.matrix.MatrixRoomRoleChangeContext
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

class RoomRoleManagementStoreTest {
    @Test
    fun memberPromotionUsesFreshPreflightAndPublishesConfirmedRole() = runBlocking {
        val fixture = Fixture(coroutineContext)
        try {
            fixture.activate()

            fixture.store.requestRoleChange(member(), RoomAssignableRole.MODERATOR)
            awaitCondition { fixture.updates.isNotEmpty() }

            assertEquals(RoleUpdate(OTHER_USER_ID, 50), fixture.updates.single())
            assertEquals(1, fixture.loads)
            assertEquals(
                50L,
                fixture.store.state.value.confirmedPowerLevels[OTHER_USER_ID]
            )
            assertEquals(1, fixture.membersRefreshRequestCount)
            assertFalse(fixture.store.state.value.isSaving)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun administratorChangesRequireConfirmation() = runBlocking {
        val fixture = Fixture(coroutineContext)
        try {
            fixture.activate()

            fixture.store.requestRoleChange(member(), RoomAssignableRole.ADMINISTRATOR)
            yield()

            assertEquals(
                RoomAssignableRole.ADMINISTRATOR,
                fixture.store.state.value.pendingConfirmation?.requestedRole
            )
            assertTrue(fixture.updates.isEmpty())
            assertEquals(0, fixture.loads)

            fixture.store.confirmPendingChange()
            awaitCondition { fixture.updates.isNotEmpty() }

            assertEquals(RoleUpdate(OTHER_USER_ID, 100), fixture.updates.single())
            assertNull(fixture.store.state.value.pendingConfirmation)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun administratorDemotionAlsoRequiresConfirmation() = runBlocking {
        val fixture = Fixture(coroutineContext)
        try {
            fixture.activate(capabilities = capabilities(ownPowerLevel = Long.MAX_VALUE))

            fixture.store.requestRoleChange(
                member(powerLevel = 100, role = MatrixRoomMemberRole.ADMIN),
                RoomAssignableRole.MODERATOR
            )

            assertEquals(
                RoomAssignableRole.MODERATOR,
                fixture.store.state.value.pendingConfirmation?.requestedRole
            )
            assertTrue(fixture.updates.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cancellingAdministratorConfirmationDoesNotWrite() = runBlocking {
        val fixture = Fixture(coroutineContext)
        try {
            fixture.activate()
            fixture.store.requestRoleChange(member(), RoomAssignableRole.ADMINISTRATOR)

            fixture.store.cancelPendingChange()
            yield()

            assertNull(fixture.store.state.value.pendingConfirmation)
            assertTrue(fixture.updates.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun liveCapabilityLossDismissesPendingConfirmation() = runBlocking {
        val fixture = Fixture(coroutineContext)
        try {
            fixture.activate()
            fixture.store.requestRoleChange(member(), RoomAssignableRole.ADMINISTRATOR)

            fixture.activate(capabilities = capabilities(canEdit = false))

            assertNull(fixture.store.state.value.pendingConfirmation)
            assertFalse(fixture.store.state.value.capabilities?.canEdit ?: true)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun capabilityLossDuringPreflightRejectsTheWrite() = runBlocking {
        val fixture = Fixture(coroutineContext)
        fixture.contextBehavior = {
            changeContext(canEditPowerLevels = false)
        }
        try {
            fixture.activate()

            fixture.store.requestRoleChange(member(), RoomAssignableRole.MODERATOR)
            awaitCondition {
                fixture.store.state.value.error ==
                    RoomRoleManagementError.PERMISSION_CHANGED
            }

            assertTrue(fixture.updates.isEmpty())
            assertFalse(fixture.store.state.value.isSaving)
            assertEquals(1, fixture.membersRefreshRequestCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun selfOwnerAndEqualPowerPeerAreReadOnlyBeforePreflight() = runBlocking {
        val fixture = Fixture(coroutineContext)
        try {
            fixture.activate()
            val state = fixture.store.state.value

            assertFalse(state.canChange(member(userId = USER_ID)))
            assertFalse(
                state.canChange(
                    member(powerLevel = Long.MAX_VALUE, role = MatrixRoomMemberRole.OWNER)
                )
            )
            assertFalse(
                state.canChange(
                    member(powerLevel = 100, role = MatrixRoomMemberRole.ADMIN)
                )
            )
            fixture.store.requestRoleChange(member(userId = USER_ID), RoomAssignableRole.MODERATOR)
            yield()
            assertEquals(0, fixture.loads)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun moderatorCanAssignModeratorButNotAdministrator() = runBlocking {
        val fixture = Fixture(coroutineContext)
        try {
            fixture.activate(capabilities = capabilities(ownPowerLevel = 50))
            val roles = fixture.store.state.value.assignableRoles(member())

            assertEquals(
                listOf(RoomAssignableRole.MEMBER, RoomAssignableRole.MODERATOR),
                roles
            )
            fixture.store.requestRoleChange(member(), RoomAssignableRole.ADMINISTRATOR)
            yield()
            assertEquals(0, fixture.loads)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun refreshedMemberSnapshotReconcilesConfirmedOverride() = runBlocking {
        val fixture = Fixture(coroutineContext)
        try {
            fixture.activate()
            fixture.store.requestRoleChange(member(), RoomAssignableRole.MODERATOR)
            awaitCondition {
                fixture.store.state.value.confirmedPowerLevels[OTHER_USER_ID] == 50L
            }

            fixture.store.reconcileMembers(
                listOf(member(powerLevel = 50, role = MatrixRoomMemberRole.MODERATOR))
            )

            assertTrue(fixture.store.state.value.confirmedPowerLevels.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun delayedRecheckDropsOptimisticOverrideOnAuthoritativeDivergence() = runBlocking {
        val fixture = Fixture(coroutineContext)
        var contextLoads = 0
        fixture.contextBehavior = {
            contextLoads += 1
            if (contextLoads == 1) {
                changeContext()
            } else {
                changeContext(
                    targetPowerLevel = 75,
                    targetRole = MatrixRoomMemberRole.MODERATOR
                )
            }
        }
        try {
            fixture.activate()
            fixture.store.requestRoleChange(member(), RoomAssignableRole.MODERATOR)
            awaitCondition {
                fixture.store.state.value.confirmedPowerLevels[OTHER_USER_ID] == 50L
            }

            fixture.releaseConfirmationRecheck()
            awaitCondition {
                fixture.store.state.value.error ==
                    RoomRoleManagementError.PERMISSION_CHANGED
            }

            assertFalse(
                fixture.store.state.value.confirmedPowerLevels.containsKey(OTHER_USER_ID)
            )
            assertEquals(2, fixture.loads)
            assertEquals(2, fixture.membersRefreshRequestCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun terminalPreflightFailureStillRequestsAuthoritativeMembersRefresh() = runBlocking {
        val fixture = Fixture(coroutineContext)
        fixture.contextBehavior = { error("member is no longer in the room") }
        try {
            fixture.activate()
            fixture.store.requestRoleChange(member(), RoomAssignableRole.MODERATOR)
            awaitCondition {
                fixture.store.state.value.error == RoomRoleManagementError.SAVE
            }

            assertEquals(2, fixture.loads)
            assertEquals(1, fixture.membersRefreshRequestCount)
            assertTrue(fixture.updates.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun replacingRouteRejectsLateWriteCompletion() = runBlocking {
        val fixture = Fixture(coroutineContext)
        val updateStarted = CompletableDeferred<Unit>()
        val updateGate = CompletableDeferred<Unit>()
        fixture.updateBehavior = {
            updateStarted.complete(Unit)
            withContext(NonCancellable) { updateGate.await() }
        }
        try {
            fixture.activate()
            fixture.store.requestRoleChange(member(), RoomAssignableRole.MODERATOR)
            updateStarted.await()

            fixture.store.deactivate()
            updateGate.complete(Unit)
            yield()

            assertEquals(RoomRoleManagementState(), fixture.store.state.value)
            assertEquals(0, fixture.membersRefreshRequestCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun leavingRouteCancelsDelayedConfirmationRecheck() = runBlocking {
        val fixture = Fixture(coroutineContext)
        try {
            fixture.activate()
            fixture.store.requestRoleChange(member(), RoomAssignableRole.MODERATOR)
            awaitCondition {
                fixture.store.state.value.confirmedPowerLevels[OTHER_USER_ID] == 50L
            }

            fixture.store.deactivate()
            fixture.releaseConfirmationRecheck()
            yield()

            assertEquals(RoomRoleManagementState(), fixture.store.state.value)
            assertEquals(1, fixture.loads)
            assertEquals(1, fixture.membersRefreshRequestCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun serverAppliedWriteIsAcceptedWhenUpdateCallReportsFailure() = runBlocking {
        val fixture = Fixture(coroutineContext)
        var loads = 0
        fixture.contextBehavior = {
            loads += 1
            if (loads == 1) {
                changeContext()
            } else {
                changeContext(targetPowerLevel = 50, targetRole = MatrixRoomMemberRole.MODERATOR)
            }
        }
        fixture.updateBehavior = { error("connection closed") }
        try {
            fixture.activate()
            fixture.store.requestRoleChange(member(), RoomAssignableRole.MODERATOR)
            awaitCondition { fixture.membersRefreshRequestCount == 1 }

            assertNull(fixture.store.state.value.error)
            assertEquals(
                50L,
                fixture.store.state.value.confirmedPowerLevels[OTHER_USER_ID]
            )
            assertEquals(2, fixture.loads)
        } finally {
            fixture.close()
        }
    }
}

private class Fixture(context: CoroutineContext) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(context + job)
    var loads = 0
    var membersRefreshRequestCount = 0
    val updates = mutableListOf<RoleUpdate>()
    val warnings = mutableListOf<Pair<String, Throwable>>()
    var contextBehavior: suspend () -> MatrixRoomRoleChangeContext = { changeContext() }
    var updateBehavior: suspend () -> Unit = {}
    private val confirmationRecheckGate = CompletableDeferred<Unit>()

    val store = RoomRoleManagementStore(
        scope = scope,
        driver = RoomRoleManagementDriver(
            loadChangeContext = { _, _ ->
                loads += 1
                contextBehavior()
            },
            updatePowerLevel = { _, userId, powerLevel ->
                updates += RoleUpdate(userId, powerLevel)
                updateBehavior()
            },
            delayMillis = { confirmationRecheckGate.await() }
        ),
        onMembersRefreshRequested = { membersRefreshRequestCount += 1 },
        onWarning = { message, error -> warnings += message to error }
    )

    fun activate(capabilities: RoomRoleCapabilities = capabilities()) {
        store.activate(
            target = RoomRoleManagementTarget(USER_ID, ROOM_ID),
            capabilities = capabilities
        )
    }

    fun releaseConfirmationRecheck() {
        confirmationRecheckGate.complete(Unit)
    }

    suspend fun close() {
        job.cancelAndJoin()
    }
}

private data class RoleUpdate(
    val userId: String,
    val powerLevel: Long
)

private fun capabilities(
    ownPowerLevel: Long = 100,
    canEdit: Boolean = true
): RoomRoleCapabilities {
    return RoomRoleCapabilities(
        canEdit = canEdit,
        ownPowerLevel = ownPowerLevel
    )
}

private fun member(
    userId: String = OTHER_USER_ID,
    powerLevel: Long = 0,
    role: MatrixRoomMemberRole = MatrixRoomMemberRole.MEMBER
): MatrixRoomMember {
    return MatrixRoomMember(
        userId = userId,
        displayName = "Bob",
        avatarUrl = null,
        membership = MatrixRoomMemberMembership.JOINED,
        role = role,
        powerLevel = powerLevel,
        isNameAmbiguous = false
    )
}

private fun changeContext(
    canEditPowerLevels: Boolean = true,
    targetPowerLevel: Long = 0,
    targetRole: MatrixRoomMemberRole = MatrixRoomMemberRole.MEMBER
): MatrixRoomRoleChangeContext {
    return MatrixRoomRoleChangeContext(
        roomId = ROOM_ID,
        ownUserId = USER_ID,
        ownPowerLevel = 100,
        canEditPowerLevels = canEditPowerLevels,
        targetUserId = OTHER_USER_ID,
        targetPowerLevel = targetPowerLevel,
        targetMembership = MatrixRoomMemberMembership.JOINED,
        targetRole = targetRole
    )
}

private suspend fun awaitCondition(condition: () -> Boolean) {
    withTimeout(2_000) {
        while (!condition()) yield()
    }
}

private const val USER_ID = "@alice:example.org"
private const val OTHER_USER_ID = "@bob:example.org"
private const val ROOM_ID = "!room:example.org"
