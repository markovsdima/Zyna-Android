package com.zyna.app.data.matrix

import org.matrix.rustcomponents.sdk.AllowRule
import org.matrix.rustcomponents.sdk.JoinRule
import org.matrix.rustcomponents.sdk.RoomPowerLevels
import org.matrix.rustcomponents.sdk.StateEventType

sealed interface MatrixRoomSecurityJoinRule {
    data object InviteOnly : MatrixRoomSecurityJoinRule
    data object Public : MatrixRoomSecurityJoinRule
    data class Restricted(
        val spaceIds: Set<String>,
        val hasUnsupportedRules: Boolean
    ) : MatrixRoomSecurityJoinRule
    data object Unsupported : MatrixRoomSecurityJoinRule
}

data class MatrixRoomSecurityPermissions(
    val canChangeJoinRule: Boolean,
    val canChangeHistoryVisibility: Boolean,
    val canEnableEncryption: Boolean,
    val canChangeAddress: Boolean,
    val canChangeDirectoryVisibility: Boolean
)

/**
 * Matrix-independent projection used by the security editor for an ordinary group room.
 *
 * Join rules, history visibility, encryption, aliases, and directory publication are distinct
 * Matrix state/API surfaces. Keeping each field explicit prevents one setting from being inferred
 * from another and lets the editor preserve unsupported remote values safely.
 */
data class MatrixRoomSecuritySnapshot(
    val roomId: String,
    val joinRule: MatrixRoomSecurityJoinRule,
    val historyVisibility: MatrixRoomHistoryVisibility,
    val isEncrypted: Boolean?,
    val canonicalAlias: String?,
    val alternativeAliases: List<String>,
    val directoryVisibility: MatrixRoomDirectoryVisibility,
    val serverName: String,
    val permissions: MatrixRoomSecurityPermissions
) {
    val aliases: List<String>
        get() = buildList {
            canonicalAlias?.let(::add)
            addAll(alternativeAliases)
        }.distinct()
}

internal fun JoinRule?.toMatrixRoomSecurityJoinRule(): MatrixRoomSecurityJoinRule {
    return when (this) {
        JoinRule.Invite, JoinRule.Private -> MatrixRoomSecurityJoinRule.InviteOnly
        JoinRule.Public -> MatrixRoomSecurityJoinRule.Public
        is JoinRule.Restricted -> MatrixRoomSecurityJoinRule.Restricted(
            spaceIds = rules.filterIsInstance<AllowRule.RoomMembership>()
                .map(AllowRule.RoomMembership::roomId)
                .filter(String::isNotBlank)
                .toSet(),
            hasUnsupportedRules = rules.any { it !is AllowRule.RoomMembership }
        )
        JoinRule.Knock,
        is JoinRule.KnockRestricted,
        is JoinRule.Custom,
        null -> MatrixRoomSecurityJoinRule.Unsupported
    }
}

internal fun RoomPowerLevels.toMatrixRoomSecurityPermissions(): MatrixRoomSecurityPermissions {
    val addressPermissions = toMatrixRoomAddressPermissions()
    return MatrixRoomSecurityPermissions(
        canChangeJoinRule = canOwnUserSendState(StateEventType.RoomJoinRules),
        canChangeHistoryVisibility = canOwnUserSendState(
            StateEventType.RoomHistoryVisibility
        ),
        canEnableEncryption = canOwnUserSendState(StateEventType.RoomEncryption),
        canChangeAddress = addressPermissions.canChangeAddress,
        canChangeDirectoryVisibility = addressPermissions.canChangeDirectoryVisibility
    )
}
