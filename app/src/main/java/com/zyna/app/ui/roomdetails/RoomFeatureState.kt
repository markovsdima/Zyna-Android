package com.zyna.app.ui.roomdetails

import com.zyna.app.ui.createroom.CreateRoomState
import com.zyna.app.ui.invitemembers.InviteMembersState
import com.zyna.app.ui.roommembers.RoomMembersState
import com.zyna.app.ui.roommembers.RoomMemberModerationState
import com.zyna.app.ui.roompermissions.RoomPermissionsState
import com.zyna.app.ui.roomroles.RoomRoleManagementState
import com.zyna.app.ui.roomprofile.RoomProfileEditorState

/**
 * Immutable render input for independently owned room-management feature states.
 */
data class RoomFeatureState(
    val details: RoomDetailsState,
    val profileEditor: RoomProfileEditorState,
    val createRoom: CreateRoomState,
    val members: RoomMembersState,
    val memberModeration: RoomMemberModerationState = RoomMemberModerationState(),
    val inviteMembers: InviteMembersState,
    val permissions: RoomPermissionsState = RoomPermissionsState(),
    val roles: RoomRoleManagementState = RoomRoleManagementState()
)
