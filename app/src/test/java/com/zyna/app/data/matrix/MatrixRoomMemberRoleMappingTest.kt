package com.zyna.app.data.matrix

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.matrix_sdk.RoomMemberRole

class MatrixRoomMemberRoleMappingTest {
    @Test
    fun privilegedCreatorRemainsDistinctFromDelegatedOwner() {
        assertEquals(
            MatrixRoomMemberRole.CREATOR,
            RoomMemberRole.CREATOR.toMatrixRoomMemberRole(Long.MAX_VALUE)
        )
        assertEquals(
            MatrixRoomMemberRole.OWNER,
            RoomMemberRole.ADMINISTRATOR.toMatrixRoomMemberRole(150)
        )
    }

    @Test
    fun standardPowerLevelsKeepTheirConsumerRoles() {
        assertEquals(
            MatrixRoomMemberRole.ADMIN,
            RoomMemberRole.ADMINISTRATOR.toMatrixRoomMemberRole(100)
        )
        assertEquals(
            MatrixRoomMemberRole.MODERATOR,
            RoomMemberRole.MODERATOR.toMatrixRoomMemberRole(50)
        )
        assertEquals(
            MatrixRoomMemberRole.MEMBER,
            RoomMemberRole.USER.toMatrixRoomMemberRole(0)
        )
    }

    @Test
    fun creatorSemanticsStayUnknownUntilRoomMetadataIsAvailable() {
        assertEquals(
            MatrixRoomCreatorSemantics.UNKNOWN,
            matrixRoomCreatorSemantics(
                roomVersion = null,
                privilegedCreatorsRole = false
            )
        )
        assertEquals(
            MatrixRoomCreatorSemantics.LEGACY,
            matrixRoomCreatorSemantics(
                roomVersion = "11",
                privilegedCreatorsRole = false
            )
        )
        assertEquals(
            MatrixRoomCreatorSemantics.PRIVILEGED,
            matrixRoomCreatorSemantics(
                roomVersion = "12",
                privilegedCreatorsRole = true
            )
        )
    }
}
