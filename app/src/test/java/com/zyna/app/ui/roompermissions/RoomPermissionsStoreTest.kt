package com.zyna.app.ui.roompermissions

import com.zyna.app.data.matrix.MatrixRoomPermission
import com.zyna.app.data.matrix.MatrixRoomPermissions
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val USER_ID = "@alice:example.org"
private const val OTHER_USER_ID = "@bob:example.org"
private const val ROOM_ID = "!room:example.org"

class RoomPermissionsStoreTest {
    @Test
    fun activationPublishesLivePermissions() = runBlocking {
        val fixture = RoomPermissionsStoreFixture(coroutineContext)
        try {
            fixture.store.activate(target())
            fixture.awaitObservation()
            fixture.emit(permissions(changeName = 50))

            awaitCondition { fixture.store.state.value.permissions != null }

            assertEquals(50L, fixture.store.state.value.permissions?.level(
                MatrixRoomPermission.CHANGE_NAME
            ))
            assertFalse(fixture.store.state.value.isLoading)
            assertNull(fixture.store.state.value.error)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun repeatedActivationUsesTheLastSnapshotForTheFirstFrame() = runBlocking {
        val fixture = RoomPermissionsStoreFixture(coroutineContext)
        val initial = permissions(changeName = 50)
        try {
            fixture.activateWith(initial)
            fixture.store.deactivate()

            fixture.store.activate(target())

            assertEquals(initial, fixture.store.state.value.permissions)
            assertFalse(fixture.store.state.value.isLoading)
            assertTrue(fixture.store.state.value.isRefreshing)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun saveRechecksCapabilityAndUpdatesOnlySelectedPermission() = runBlocking {
        val fixture = RoomPermissionsStoreFixture(coroutineContext)
        fixture.loadBehavior = { permissions(changeName = 50, sendMessages = 0) }
        try {
            fixture.activateWith(permissions(changeName = 50, sendMessages = 0))

            fixture.store.setPermission(
                MatrixRoomPermission.CHANGE_NAME,
                RoomPermissionAudience.ADMINISTRATORS
            )
            awaitCondition { fixture.updates.isNotEmpty() }

            assertEquals(
                PermissionUpdate(
                    roomId = ROOM_ID,
                    permission = MatrixRoomPermission.CHANGE_NAME,
                    level = 100
                ),
                fixture.updates.single()
            )
            assertEquals(1, fixture.loadCount)
            assertEquals(
                100L,
                fixture.store.state.value.permissions?.level(MatrixRoomPermission.CHANGE_NAME)
            )
            assertEquals(
                0L,
                fixture.store.state.value.permissions?.level(MatrixRoomPermission.SEND_MESSAGES)
            )
            assertFalse(fixture.store.state.value.isSaving)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun staleLiveValueCannotSnapBackAConfirmedSave() = runBlocking {
        val fixture = RoomPermissionsStoreFixture(coroutineContext)
        fixture.loadBehavior = { permissions(changeName = 50) }
        try {
            fixture.activateWith(permissions(changeName = 50))
            fixture.store.setPermission(
                MatrixRoomPermission.CHANGE_NAME,
                RoomPermissionAudience.ADMINISTRATORS
            )
            awaitCondition { fixture.updates.isNotEmpty() }

            fixture.emit(permissions(changeName = 50, sendMessages = 50))
            awaitCondition {
                fixture.store.state.value.permissions?.level(
                    MatrixRoomPermission.SEND_MESSAGES
                ) == 50L
            }

            assertEquals(
                100L,
                fixture.store.state.value.permissions?.level(MatrixRoomPermission.CHANGE_NAME)
            )

            fixture.emit(permissions(changeName = 100, sendMessages = 50))
            awaitCondition {
                fixture.store.state.value.permissions?.level(
                    MatrixRoomPermission.CHANGE_NAME
                ) == 100L
            }
            fixture.emit(permissions(changeName = 50, sendMessages = 50))
            awaitCondition {
                fixture.store.state.value.permissions?.level(
                    MatrixRoomPermission.CHANGE_NAME
                ) == 50L
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun delayedConfirmationAcceptsANewerAuthoritativeValue() = runBlocking {
        val fixture = RoomPermissionsStoreFixture(coroutineContext)
        val recheckGate = CompletableDeferred<Unit>()
        var loads = 0
        fixture.loadBehavior = {
            loads += 1
            permissions(changeName = if (loads == 1) 50 else 0)
        }
        fixture.delayBehavior = { recheckGate.await() }
        try {
            fixture.activateWith(permissions(changeName = 50))
            fixture.store.setPermission(
                MatrixRoomPermission.CHANGE_NAME,
                RoomPermissionAudience.ADMINISTRATORS
            )
            awaitCondition {
                fixture.store.state.value.permissions?.level(
                    MatrixRoomPermission.CHANGE_NAME
                ) == 100L
            }

            recheckGate.complete(Unit)
            awaitCondition {
                fixture.store.state.value.permissions?.level(
                    MatrixRoomPermission.CHANGE_NAME
                ) == 0L
            }

            assertEquals(2, fixture.loadCount)
            assertNull(fixture.store.state.value.error)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun capabilityLossDuringPreflightPreventsMutation() = runBlocking {
        val fixture = RoomPermissionsStoreFixture(coroutineContext)
        fixture.loadBehavior = { permissions(changeName = 50, canEdit = false) }
        try {
            fixture.activateWith(permissions(changeName = 50))

            fixture.store.setPermission(
                MatrixRoomPermission.CHANGE_NAME,
                RoomPermissionAudience.ADMINISTRATORS
            )
            awaitCondition {
                fixture.store.state.value.error == RoomPermissionsError.PERMISSION_CHANGED
            }

            assertTrue(fixture.updates.isEmpty())
            assertFalse(fixture.store.state.value.permissions?.canEdit ?: true)
            assertFalse(fixture.store.state.value.isSaving)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun failedSaveRefreshesTheSnapshotAndLeavesTheScreenUsable() = runBlocking {
        val fixture = RoomPermissionsStoreFixture(coroutineContext)
        var loads = 0
        fixture.loadBehavior = {
            loads += 1
            permissions(changeName = if (loads == 1) 50 else 75)
        }
        fixture.updateBehavior = { error("server rejected update") }
        try {
            fixture.activateWith(permissions(changeName = 50))

            fixture.store.setPermission(
                MatrixRoomPermission.CHANGE_NAME,
                RoomPermissionAudience.ADMINISTRATORS
            )
            awaitCondition { fixture.store.state.value.error == RoomPermissionsError.SAVE }

            assertEquals(2, fixture.loadCount)
            assertEquals(
                75L,
                fixture.store.state.value.permissions?.level(MatrixRoomPermission.CHANGE_NAME)
            )
            assertFalse(fixture.store.state.value.isSaving)
            assertTrue(fixture.store.state.value.permissions?.canEdit == true)
            assertEquals("Failed to update room permission", fixture.warnings.single().first)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun moderatorCannotRaiseAPermissionAboveTheirOwnPowerLevel() = runBlocking {
        val fixture = RoomPermissionsStoreFixture(coroutineContext)
        try {
            fixture.activateWith(
                permissions(changeName = 50, canEdit = true, ownPowerLevel = 50)
            )

            fixture.store.setPermission(
                MatrixRoomPermission.CHANGE_NAME,
                RoomPermissionAudience.ADMINISTRATORS
            )
            yield()

            assertTrue(fixture.updates.isEmpty())
            assertEquals(0, fixture.loadCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun replacingSessionRejectsLateSaveEvenWhenDriverSwallowsCancellation() = runBlocking {
        val fixture = RoomPermissionsStoreFixture(coroutineContext)
        val updateStarted = CompletableDeferred<Unit>()
        val updateGate = CompletableDeferred<Unit>()
        fixture.loadBehavior = { permissions(changeName = 50) }
        fixture.updateBehavior = {
            updateStarted.complete(Unit)
            withContext(NonCancellable) { updateGate.await() }
        }
        try {
            fixture.activateWith(permissions(changeName = 50))
            fixture.store.setPermission(
                MatrixRoomPermission.CHANGE_NAME,
                RoomPermissionAudience.ADMINISTRATORS
            )
            updateStarted.await()

            val replacement = RoomPermissionsTarget(OTHER_USER_ID, ROOM_ID)
            fixture.store.activate(replacement)
            fixture.awaitObservation()
            fixture.emit(permissions(changeName = 0))
            awaitCondition {
                fixture.store.state.value.target == replacement &&
                    fixture.store.state.value.permissions?.level(
                        MatrixRoomPermission.CHANGE_NAME
                    ) == 0L
            }
            updateGate.complete(Unit)
            yield()

            assertEquals(replacement, fixture.store.state.value.target)
            assertEquals(
                0L,
                fixture.store.state.value.permissions?.level(MatrixRoomPermission.CHANGE_NAME)
            )
            assertFalse(fixture.store.state.value.isSaving)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun customThresholdInTheSameAudienceDoesNotRewriteTheServerValue() = runBlocking {
        val fixture = RoomPermissionsStoreFixture(coroutineContext)
        try {
            fixture.activateWith(permissions(changeName = 75))

            fixture.store.setPermission(
                MatrixRoomPermission.CHANGE_NAME,
                RoomPermissionAudience.MODERATORS
            )
            yield()

            assertTrue(fixture.updates.isEmpty())
            assertEquals(0, fixture.loadCount)
            assertEquals(
                75L,
                fixture.store.state.value.permissions?.level(MatrixRoomPermission.CHANGE_NAME)
            )
        } finally {
            fixture.close()
        }
    }
}

private data class PermissionUpdate(
    val roomId: String,
    val permission: MatrixRoomPermission,
    val level: Long
)

private class RoomPermissionsStoreFixture(parentContext: CoroutineContext) {
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(parentContext + scopeJob)
    private val permissionUpdates = MutableSharedFlow<MatrixRoomPermissions>(
        replay = 1,
        extraBufferCapacity = 1
    )

    var loadCount = 0
    var loadBehavior: suspend () -> MatrixRoomPermissions = { permissions() }
    var updateBehavior: suspend (PermissionUpdate) -> Unit = {}
    var delayBehavior: suspend (Long) -> Unit = {
        CompletableDeferred<Unit>().await()
    }
    val updates = mutableListOf<PermissionUpdate>()
    val warnings = mutableListOf<Pair<String, Throwable>>()

    val store = RoomPermissionsStore(
        scope = scope,
        driver = RoomPermissionsDriver(
            observePermissions = { permissionUpdates },
            loadPermissions = {
                loadCount += 1
                loadBehavior()
            },
            updatePermission = { roomId, permission, level ->
                val update = PermissionUpdate(roomId, permission, level)
                updates += update
                updateBehavior(update)
            },
            delayMillis = { durationMillis -> delayBehavior(durationMillis) }
        ),
        onWarning = { message, error -> warnings += message to error }
    )

    suspend fun activateWith(initial: MatrixRoomPermissions) {
        store.activate(target())
        awaitObservation()
        emit(initial)
        awaitCondition { store.state.value.permissions == initial }
    }

    suspend fun awaitObservation() {
        awaitCondition { permissionUpdates.subscriptionCount.value > 0 }
    }

    suspend fun emit(value: MatrixRoomPermissions) {
        permissionUpdates.emit(value)
    }

    suspend fun close() {
        scopeJob.cancelAndJoin()
    }
}

private fun target() = RoomPermissionsTarget(USER_ID, ROOM_ID)

private fun permissions(
    changeName: Long = 50,
    sendMessages: Long = 0,
    canEdit: Boolean = true,
    ownPowerLevel: Long = 100
): MatrixRoomPermissions {
    return MatrixRoomPermissions(
        roomId = ROOM_ID,
        levels = MatrixRoomPermission.entries.associateWith { permission ->
            when (permission) {
                MatrixRoomPermission.CHANGE_NAME -> changeName
                MatrixRoomPermission.SEND_MESSAGES -> sendMessages
                else -> 50
            }
        },
        canEdit = canEdit,
        ownPowerLevel = ownPowerLevel
    )
}

private suspend fun awaitCondition(condition: () -> Boolean) {
    withTimeout(1_000L) {
        while (!condition()) {
            yield()
        }
    }
}
