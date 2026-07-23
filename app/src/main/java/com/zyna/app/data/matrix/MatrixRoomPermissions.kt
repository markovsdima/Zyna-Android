package com.zyna.app.data.matrix

import org.matrix.rustcomponents.sdk.RoomPowerLevels
import org.matrix.rustcomponents.sdk.RoomPowerLevelsValues
import org.matrix.rustcomponents.sdk.StateEventType
import uniffi.matrix_sdk.RoomPowerLevelChanges

enum class MatrixRoomPermission {
    CHANGE_NAME,
    CHANGE_AVATAR,
    CHANGE_TOPIC,
    SEND_MESSAGES,
    REDACT_MESSAGES,
    INVITE_MEMBERS,
    REMOVE_MEMBERS,
    BAN_MEMBERS,
    MANAGE_SPACE_CHILDREN
}

data class MatrixRoomPermissions(
    val roomId: String,
    val levels: Map<MatrixRoomPermission, Long>,
    val canEdit: Boolean,
    val ownPowerLevel: Long
) {
    fun level(permission: MatrixRoomPermission): Long? = levels[permission]

    fun canEdit(permission: MatrixRoomPermission): Boolean {
        val currentLevel = level(permission) ?: return false
        return canEdit && ownPowerLevel >= currentLevel
    }

    fun canSet(level: Long): Boolean = canEdit && ownPowerLevel >= level
}

/**
 * Projects the SDK-owned power-level handle into an immutable Matrix-independent value.
 *
 * The handle must remain alive while this function runs and can be destroyed immediately after
 * the projection is returned.
 */
internal fun RoomPowerLevels.toMatrixRoomPermissions(
    roomId: String,
    ownUserId: String,
    privilegedCreatorsRole: Boolean,
    creators: List<String>?
): MatrixRoomPermissions {
    val values = values()
    return values.toMatrixRoomPermissions(
        roomId = roomId,
        canEdit = canOwnUserSendState(StateEventType.RoomPowerLevels),
        ownPowerLevel = resolveOwnPowerLevel(
            ownUserId = ownUserId,
            userPowerLevels = userPowerLevels(),
            usersDefault = values.usersDefault,
            privilegedCreatorsRole = privilegedCreatorsRole,
            creators = creators
        )
    )
}

/**
 * Privileged creators have an implicit infinite power level and are intentionally absent from
 * the power-level event's `users` map in newer room versions.
 */
internal fun resolveOwnPowerLevel(
    ownUserId: String,
    userPowerLevels: Map<String, Long>,
    usersDefault: Long,
    privilegedCreatorsRole: Boolean,
    creators: List<String>?
): Long {
    return if (privilegedCreatorsRole && ownUserId in creators.orEmpty()) {
        Long.MAX_VALUE
    } else {
        userPowerLevels[ownUserId] ?: usersDefault
    }
}

internal fun RoomPowerLevelsValues.toMatrixRoomPermissions(
    roomId: String,
    canEdit: Boolean,
    ownPowerLevel: Long
): MatrixRoomPermissions {
    return MatrixRoomPermissions(
        roomId = roomId,
        levels = mapOf(
            MatrixRoomPermission.CHANGE_NAME to roomName,
            MatrixRoomPermission.CHANGE_AVATAR to roomAvatar,
            MatrixRoomPermission.CHANGE_TOPIC to roomTopic,
            MatrixRoomPermission.SEND_MESSAGES to eventsDefault,
            MatrixRoomPermission.REDACT_MESSAGES to redact,
            MatrixRoomPermission.INVITE_MEMBERS to invite,
            MatrixRoomPermission.REMOVE_MEMBERS to kick,
            MatrixRoomPermission.BAN_MEMBERS to ban,
            MatrixRoomPermission.MANAGE_SPACE_CHILDREN to spaceChild
        ),
        canEdit = canEdit,
        ownPowerLevel = ownPowerLevel
    )
}

/**
 * Creates a narrow SDK patch. `null` fields are left unchanged by the Rust SDK, so unrelated
 * thresholds, per-user roles, custom events, and MatrixRTC event overrides remain intact.
 */
internal fun MatrixRoomPermission.toPowerLevelChanges(level: Long): RoomPowerLevelChanges {
    return when (this) {
        MatrixRoomPermission.CHANGE_NAME -> RoomPowerLevelChanges(roomName = level)
        MatrixRoomPermission.CHANGE_AVATAR -> RoomPowerLevelChanges(roomAvatar = level)
        MatrixRoomPermission.CHANGE_TOPIC -> RoomPowerLevelChanges(roomTopic = level)
        MatrixRoomPermission.SEND_MESSAGES -> RoomPowerLevelChanges(eventsDefault = level)
        MatrixRoomPermission.REDACT_MESSAGES -> RoomPowerLevelChanges(redact = level)
        MatrixRoomPermission.INVITE_MEMBERS -> RoomPowerLevelChanges(invite = level)
        MatrixRoomPermission.REMOVE_MEMBERS -> RoomPowerLevelChanges(kick = level)
        MatrixRoomPermission.BAN_MEMBERS -> RoomPowerLevelChanges(ban = level)
        MatrixRoomPermission.MANAGE_SPACE_CHILDREN -> RoomPowerLevelChanges(spaceChild = level)
    }
}
