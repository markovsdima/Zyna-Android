package com.zyna.app.data.matrix

import org.matrix.rustcomponents.sdk.AllowRule
import org.matrix.rustcomponents.sdk.JoinRule
import org.matrix.rustcomponents.sdk.RoomPowerLevels
import org.matrix.rustcomponents.sdk.RoomVisibility
import org.matrix.rustcomponents.sdk.StateEventType

sealed interface MatrixSpaceAccessJoinRule {
    data object InviteOnly : MatrixSpaceAccessJoinRule
    data object Public : MatrixSpaceAccessJoinRule
    data class Restricted(
        val roomIds: Set<String>,
        val hasUnsupportedRules: Boolean
    ) : MatrixSpaceAccessJoinRule
    data object Unsupported : MatrixSpaceAccessJoinRule
}

enum class MatrixRoomDirectoryVisibility {
    PRIVATE,
    PUBLIC,
    UNSUPPORTED
}

data class MatrixSpaceAccessPermissions(
    val canChangeJoinRule: Boolean,
    val canChangeAddress: Boolean,
    val canChangeDirectoryVisibility: Boolean
)

internal data class MatrixRoomAddressPermissions(
    val canChangeAddress: Boolean,
    val canChangeDirectoryVisibility: Boolean
)

internal fun RoomPowerLevels.toMatrixRoomAddressPermissions(): MatrixRoomAddressPermissions {
    val canChangeAddress = canOwnUserSendState(StateEventType.RoomCanonicalAlias)
    return MatrixRoomAddressPermissions(
        canChangeAddress = canChangeAddress,
        // Matrix exposes no distinct power-level capability for room-directory publication.
        canChangeDirectoryVisibility = canChangeAddress
    )
}

/**
 * Matrix-independent projection used by the Space access editor.
 *
 * Room access, aliases, and room-directory visibility are deliberately separate: Matrix stores
 * them through different state/API surfaces and none of them implies either of the others.
 */
data class MatrixSpaceAccessSnapshot(
    val roomId: String,
    val joinRule: MatrixSpaceAccessJoinRule,
    val canonicalAlias: String?,
    val alternativeAliases: List<String>,
    val directoryVisibility: MatrixRoomDirectoryVisibility,
    val serverName: String,
    val permissions: MatrixSpaceAccessPermissions
) {
    val aliases: List<String>
        get() = buildList {
            canonicalAlias?.let(::add)
            addAll(alternativeAliases)
        }.distinct()
}

enum class MatrixRoomAliasAvailability {
    AVAILABLE,
    OWNED_BY_ROOM,
    TAKEN
}

internal fun JoinRule?.toMatrixSpaceAccessJoinRule(): MatrixSpaceAccessJoinRule {
    return when (this) {
        JoinRule.Invite, JoinRule.Private -> MatrixSpaceAccessJoinRule.InviteOnly
        JoinRule.Public -> MatrixSpaceAccessJoinRule.Public
        is JoinRule.Restricted -> MatrixSpaceAccessJoinRule.Restricted(
            roomIds = rules.filterIsInstance<AllowRule.RoomMembership>()
                .map(AllowRule.RoomMembership::roomId)
                .toSet(),
            hasUnsupportedRules = rules.any { it !is AllowRule.RoomMembership }
        )
        JoinRule.Knock,
        is JoinRule.KnockRestricted,
        is JoinRule.Custom,
        null -> MatrixSpaceAccessJoinRule.Unsupported
    }
}

internal fun RoomVisibility.toMatrixRoomDirectoryVisibility(): MatrixRoomDirectoryVisibility {
    return when (this) {
        RoomVisibility.Private -> MatrixRoomDirectoryVisibility.PRIVATE
        RoomVisibility.Public -> MatrixRoomDirectoryVisibility.PUBLIC
        is RoomVisibility.Custom -> MatrixRoomDirectoryVisibility.UNSUPPORTED
    }
}

internal fun String.matrixServerNameOrNull(): String? {
    return substringAfter(':', missingDelimiterValue = "")
        .trim()
        .takeIf(String::isNotEmpty)
}
