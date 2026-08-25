package com.zyna.app.ui.avatar

import com.zyna.app.ui.createroom.CreateRoomTarget

/** Identifies the route-owned edit session that may receive an exported avatar draft. */
internal sealed interface AvatarCropTarget {
    val editSessionId: Long

    data class OwnProfile(
        override val editSessionId: Long
    ) : AvatarCropTarget

    data class RoomProfile(
        val userId: String,
        val roomId: String,
        override val editSessionId: Long
    ) : AvatarCropTarget

    data class CreateRoom(
        val target: CreateRoomTarget,
        override val editSessionId: Long
    ) : AvatarCropTarget
}
