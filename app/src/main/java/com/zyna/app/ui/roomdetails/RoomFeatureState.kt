package com.zyna.app.ui.roomdetails

import com.zyna.app.ui.invitemembers.InviteMembersState
import com.zyna.app.ui.roommembers.RoomMembersState

/**
 * Immutable render input for independently owned room-management feature states.
 */
data class RoomFeatureState(
    val details: RoomDetailsState,
    val members: RoomMembersState,
    val inviteMembers: InviteMembersState
)
