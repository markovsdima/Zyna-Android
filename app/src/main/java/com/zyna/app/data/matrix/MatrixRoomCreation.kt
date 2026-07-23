package com.zyna.app.data.matrix

import com.zyna.app.data.calls.matrixrtc.MatrixRtcRoomPowerLevelPermissions
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.matrix.rustcomponents.sdk.JoinRule
import org.matrix.rustcomponents.sdk.PowerLevels
import org.matrix.rustcomponents.sdk.RoomHistoryVisibility
import org.matrix.rustcomponents.sdk.RoomPreset
import org.matrix.rustcomponents.sdk.RoomVisibility

enum class MatrixGroupAccess {
    PRIVATE,
    PUBLIC
}

enum class MatrixGroupPostingPermission {
    ALL_MEMBERS,
    MODERATORS_ONLY
}

data class MatrixGroupCreationRequest(
    val name: String,
    val topic: String?,
    val avatarUrl: String?,
    val access: MatrixGroupAccess,
    /** Local part only. The SDK combines it with the active homeserver. */
    val aliasLocalPart: String?,
    val postingPermission: MatrixGroupPostingPermission
)

/**
 * Maps the product-level creation contract to Matrix room creation parameters.
 *
 * Public rooms are intentionally unencrypted: publishing an encrypted room would advertise a
 * history that newly joined users cannot decrypt. Private groups are invite-only and encrypted.
 */
internal fun MatrixGroupCreationRequest.toCreateRoomParameters(): CreateRoomParameters {
    val normalizedName = name.trim()
    require(normalizedName.isNotEmpty()) { "Room name is required" }
    val normalizedAlias = aliasLocalPart?.trim()?.takeIf { it.isNotEmpty() }
    if (access == MatrixGroupAccess.PUBLIC) {
        require(normalizedAlias != null) { "Public room alias is required" }
    }

    val isPrivate = access == MatrixGroupAccess.PRIVATE
    return CreateRoomParameters(
        name = normalizedName,
        topic = topic?.trim()?.takeIf { it.isNotEmpty() },
        isEncrypted = isPrivate,
        isDirect = false,
        visibility = if (isPrivate) RoomVisibility.Private else RoomVisibility.Public,
        preset = if (isPrivate) RoomPreset.PRIVATE_CHAT else RoomPreset.PUBLIC_CHAT,
        invite = null,
        avatar = avatarUrl?.takeIf { it.isNotBlank() },
        powerLevelContentOverride = postingPermission.toPowerLevels(),
        joinRuleOverride = if (isPrivate) JoinRule.Invite else JoinRule.Public,
        historyVisibilityOverride = if (isPrivate) RoomHistoryVisibility.Invited else null,
        canonicalAlias = normalizedAlias,
        isSpace = false
    )
}

private fun MatrixGroupPostingPermission.toPowerLevels(): PowerLevels {
    val moderatorsOnly = this == MatrixGroupPostingPermission.MODERATORS_ONLY
    return PowerLevels(
        usersDefault = if (moderatorsOnly) 0 else null,
        eventsDefault = if (moderatorsOnly) MODERATOR_POWER_LEVEL else null,
        stateDefault = null,
        ban = null,
        kick = null,
        redact = null,
        invite = null,
        notifications = null,
        users = emptyMap(),
        // Regular members must still be able to publish MatrixRTC membership/notification events.
        // `power_level_content_override.events` replaces the homeserver's generated map rather
        // than merging individual event types. Preserve the standard protected room events before
        // adding the MatrixRTC participant whitelist.
        events = DEFAULT_ROOM_EVENT_POWER_LEVELS +
            MatrixRtcRoomPowerLevelPermissions.participantEventOverrides
    )
}

internal val DEFAULT_ROOM_EVENT_POWER_LEVELS = mapOf(
    "m.room.name" to MODERATOR_POWER_LEVEL,
    "m.room.power_levels" to ADMIN_POWER_LEVEL,
    "m.room.history_visibility" to ADMIN_POWER_LEVEL,
    "m.room.canonical_alias" to MODERATOR_POWER_LEVEL,
    "m.room.avatar" to MODERATOR_POWER_LEVEL,
    "m.room.tombstone" to ADMIN_POWER_LEVEL,
    "m.room.server_acl" to ADMIN_POWER_LEVEL,
    "m.room.encryption" to ADMIN_POWER_LEVEL
)

private const val MODERATOR_POWER_LEVEL = 50
private const val ADMIN_POWER_LEVEL = 100
