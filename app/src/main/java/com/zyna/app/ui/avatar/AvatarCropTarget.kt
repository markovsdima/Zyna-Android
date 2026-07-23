package com.zyna.app.ui.avatar

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
        val userId: String,
        override val editSessionId: Long
    ) : AvatarCropTarget
}
