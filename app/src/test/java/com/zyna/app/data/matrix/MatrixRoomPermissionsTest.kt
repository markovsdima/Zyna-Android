package com.zyna.app.data.matrix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.matrix.rustcomponents.sdk.RoomPowerLevelsValues
import uniffi.matrix_sdk.RoomPowerLevelChanges

class MatrixRoomPermissionsTest {
    @Test
    fun sdkValuesProjectEverySupportedPermission() {
        val permissions = RoomPowerLevelsValues(
            ban = 91,
            invite = 92,
            kick = 93,
            redact = 94,
            eventsDefault = 95,
            stateDefault = 96,
            usersDefault = 97,
            roomName = 98,
            roomAvatar = 99,
            roomTopic = 100,
            spaceChild = 101,
            beacon = 102,
            beaconInfo = 103
        ).toMatrixRoomPermissions(
            roomId = ROOM_ID,
            canEdit = true,
            ownPowerLevel = 100
        )

        assertEquals(ROOM_ID, permissions.roomId)
        assertEquals(true, permissions.canEdit)
        assertEquals(100L, permissions.ownPowerLevel)
        assertEquals(98L, permissions.level(MatrixRoomPermission.CHANGE_NAME))
        assertEquals(99L, permissions.level(MatrixRoomPermission.CHANGE_AVATAR))
        assertEquals(100L, permissions.level(MatrixRoomPermission.CHANGE_TOPIC))
        assertEquals(95L, permissions.level(MatrixRoomPermission.SEND_MESSAGES))
        assertEquals(94L, permissions.level(MatrixRoomPermission.REDACT_MESSAGES))
        assertEquals(92L, permissions.level(MatrixRoomPermission.INVITE_MEMBERS))
        assertEquals(93L, permissions.level(MatrixRoomPermission.REMOVE_MEMBERS))
        assertEquals(91L, permissions.level(MatrixRoomPermission.BAN_MEMBERS))
        assertEquals(101L, permissions.level(MatrixRoomPermission.MANAGE_SPACE_CHILDREN))
    }

    @Test
    fun everyPermissionCreatesANarrowSdkPatch() {
        val expected = mapOf(
            MatrixRoomPermission.CHANGE_NAME to RoomPowerLevelChanges(roomName = LEVEL),
            MatrixRoomPermission.CHANGE_AVATAR to RoomPowerLevelChanges(roomAvatar = LEVEL),
            MatrixRoomPermission.CHANGE_TOPIC to RoomPowerLevelChanges(roomTopic = LEVEL),
            MatrixRoomPermission.SEND_MESSAGES to RoomPowerLevelChanges(eventsDefault = LEVEL),
            MatrixRoomPermission.REDACT_MESSAGES to RoomPowerLevelChanges(redact = LEVEL),
            MatrixRoomPermission.INVITE_MEMBERS to RoomPowerLevelChanges(invite = LEVEL),
            MatrixRoomPermission.REMOVE_MEMBERS to RoomPowerLevelChanges(kick = LEVEL),
            MatrixRoomPermission.BAN_MEMBERS to RoomPowerLevelChanges(ban = LEVEL),
            MatrixRoomPermission.MANAGE_SPACE_CHILDREN to
                RoomPowerLevelChanges(spaceChild = LEVEL)
        )

        MatrixRoomPermission.entries.forEach { permission ->
            assertEquals(expected.getValue(permission), permission.toPowerLevelChanges(LEVEL))
        }
    }

    @Test
    fun unsupportedPermissionIsNotInventedByProjection() {
        val permissions = MatrixRoomPermissions(
            roomId = ROOM_ID,
            levels = emptyMap(),
            canEdit = false,
            ownPowerLevel = 0
        )

        assertNull(permissions.level(MatrixRoomPermission.CHANGE_NAME))
    }

    @Test
    fun privilegedCreatorUsesImplicitInfinitePowerLevel() {
        assertEquals(
            Long.MAX_VALUE,
            resolveOwnPowerLevel(
                ownUserId = "@creator:example.org",
                userPowerLevels = emptyMap(),
                usersDefault = 0,
                privilegedCreatorsRole = true,
                creators = listOf("@creator:example.org")
            )
        )
    }

    @Test
    fun regularUserUsesExplicitOrDefaultPowerLevel() {
        assertEquals(
            75L,
            resolveOwnPowerLevel(
                ownUserId = "@alice:example.org",
                userPowerLevels = mapOf("@alice:example.org" to 75),
                usersDefault = 10,
                privilegedCreatorsRole = true,
                creators = listOf("@creator:example.org")
            )
        )
        assertEquals(
            10L,
            resolveOwnPowerLevel(
                ownUserId = "@bob:example.org",
                userPowerLevels = emptyMap(),
                usersDefault = 10,
                privilegedCreatorsRole = false,
                creators = null
            )
        )
    }
}

private const val ROOM_ID = "!room:example.org"
private const val LEVEL = 73L
