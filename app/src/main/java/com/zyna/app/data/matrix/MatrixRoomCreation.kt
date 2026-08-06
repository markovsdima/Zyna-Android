package com.zyna.app.data.matrix

import com.zyna.app.data.calls.matrixrtc.MatrixRtcRoomPowerLevelPermissions
import org.matrix.rustcomponents.sdk.AllowRule
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.matrix.rustcomponents.sdk.JoinRule
import org.matrix.rustcomponents.sdk.PowerLevels
import org.matrix.rustcomponents.sdk.RoomHistoryVisibility
import org.matrix.rustcomponents.sdk.RoomPreset
import org.matrix.rustcomponents.sdk.RoomVisibility

enum class MatrixRoomCreationKind {
    ROOM,
    SPACE
}

sealed interface MatrixRoomCreationAccess {
    data object Private : MatrixRoomCreationAccess
    data object Public : MatrixRoomCreationAccess
    data class Restricted(val parentSpaceId: String) : MatrixRoomCreationAccess
}

enum class MatrixRoomPostingPermission {
    ALL_MEMBERS,
    MODERATORS_ONLY
}

data class MatrixRoomCreationRequest(
    val name: String,
    val topic: String?,
    val avatarUrl: String?,
    val kind: MatrixRoomCreationKind,
    val access: MatrixRoomCreationAccess,
    /** Local part only. The SDK combines it with the active homeserver. */
    val aliasLocalPart: String?,
    val postingPermission: MatrixRoomPostingPermission
)

/**
 * Maps the product-level creation contract to Matrix room creation parameters.
 *
 * Public and parent-restricted rooms are intentionally unencrypted. Anyone who satisfies their
 * advertised access rule can join without a separate invitation, so E2EE would promise privacy
 * without providing readable shared history to those future members. Private rooms require an
 * explicit invitation and remain encrypted. Spaces never carry timeline messages and use a high
 * default event power level instead of E2EE.
 */
internal fun MatrixRoomCreationRequest.toCreateRoomParameters(): CreateRoomParameters {
    val normalizedName = name.trim()
    require(normalizedName.isNotEmpty()) { "Room name is required" }
    val normalizedAlias = aliasLocalPart?.trim()?.takeIf { it.isNotEmpty() }
    if (access == MatrixRoomCreationAccess.Public) {
        require(normalizedAlias != null) { "Public room alias is required" }
    }

    val isSpace = kind == MatrixRoomCreationKind.SPACE
    val isPublic = access == MatrixRoomCreationAccess.Public
    val normalizedAccess = when (access) {
        MatrixRoomCreationAccess.Private -> MatrixRoomCreationAccess.Private
        MatrixRoomCreationAccess.Public -> MatrixRoomCreationAccess.Public
        is MatrixRoomCreationAccess.Restricted -> {
            val parentSpaceId = access.parentSpaceId.trim()
            require(parentSpaceId.isNotEmpty()) { "Parent Space id is required" }
            MatrixRoomCreationAccess.Restricted(parentSpaceId)
        }
    }
    val isRestricted = normalizedAccess is MatrixRoomCreationAccess.Restricted
    return CreateRoomParameters(
        name = normalizedName,
        topic = topic?.trim()?.takeIf { it.isNotEmpty() },
        isEncrypted = !isSpace && !isPublic && !isRestricted,
        isDirect = false,
        visibility = if (isPublic) RoomVisibility.Public else RoomVisibility.Private,
        preset = if (isPublic) RoomPreset.PUBLIC_CHAT else RoomPreset.PRIVATE_CHAT,
        invite = null,
        avatar = avatarUrl?.takeIf { it.isNotBlank() },
        powerLevelContentOverride = if (isSpace) {
            spacePowerLevels(isPublic = isPublic)
        } else {
            postingPermission.toRoomPowerLevels()
        },
        joinRuleOverride = when (normalizedAccess) {
            MatrixRoomCreationAccess.Private -> JoinRule.Invite
            MatrixRoomCreationAccess.Public -> JoinRule.Public
            is MatrixRoomCreationAccess.Restricted -> JoinRule.Restricted(
                rules = listOf(AllowRule.RoomMembership(normalizedAccess.parentSpaceId))
            )
        },
        historyVisibilityOverride = when {
            isPublic -> null
            isRestricted -> RoomHistoryVisibility.Shared
            else -> RoomHistoryVisibility.Invited
        },
        canonicalAlias = normalizedAlias,
        isSpace = isSpace
    )
}

private fun MatrixRoomPostingPermission.toRoomPowerLevels(): PowerLevels {
    val moderatorsOnly = this == MatrixRoomPostingPermission.MODERATORS_ONLY
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

private fun spacePowerLevels(isPublic: Boolean): PowerLevels {
    return PowerLevels(
        usersDefault = null,
        // Spaces are containers, not chat timelines. Only owners may send ordinary events.
        eventsDefault = SPACE_DEFAULT_EVENT_POWER_LEVEL,
        stateDefault = null,
        ban = null,
        kick = null,
        redact = null,
        invite = if (isPublic) 0 else MODERATOR_POWER_LEVEL,
        notifications = null,
        users = emptyMap(),
        events = emptyMap()
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
private const val SPACE_DEFAULT_EVENT_POWER_LEVEL = 100
