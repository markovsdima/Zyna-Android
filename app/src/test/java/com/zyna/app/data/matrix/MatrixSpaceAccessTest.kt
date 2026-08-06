package com.zyna.app.data.matrix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.matrix.rustcomponents.sdk.AllowRule
import org.matrix.rustcomponents.sdk.JoinRule
import org.matrix.rustcomponents.sdk.RoomVisibility

class MatrixSpaceAccessTest {
    @Test
    fun restrictedRulePreservesRoomIdsAndUnsupportedRules() {
        val mapped = JoinRule.Restricted(
            rules = listOf(
                AllowRule.RoomMembership("!parent:example.org"),
                AllowRule.Custom("{\"type\":\"com.example.custom\"}")
            )
        ).toMatrixSpaceAccessJoinRule() as MatrixSpaceAccessJoinRule.Restricted

        assertEquals(setOf("!parent:example.org"), mapped.roomIds)
        assertTrue(mapped.hasUnsupportedRules)
    }

    @Test
    fun standardJoinRulesMapWithoutConflatingKnock() {
        assertEquals(
            MatrixSpaceAccessJoinRule.InviteOnly,
            JoinRule.Invite.toMatrixSpaceAccessJoinRule()
        )
        assertEquals(
            MatrixSpaceAccessJoinRule.Public,
            JoinRule.Public.toMatrixSpaceAccessJoinRule()
        )
        assertEquals(
            MatrixSpaceAccessJoinRule.Unsupported,
            JoinRule.Knock.toMatrixSpaceAccessJoinRule()
        )
    }

    @Test
    fun customDirectoryVisibilityRemainsUnsupported() {
        assertEquals(
            MatrixRoomDirectoryVisibility.PRIVATE,
            RoomVisibility.Private.toMatrixRoomDirectoryVisibility()
        )
        assertEquals(
            MatrixRoomDirectoryVisibility.PUBLIC,
            RoomVisibility.Public.toMatrixRoomDirectoryVisibility()
        )
        assertEquals(
            MatrixRoomDirectoryVisibility.UNSUPPORTED,
            RoomVisibility.Custom("internal").toMatrixRoomDirectoryVisibility()
        )
    }

    @Test
    fun matrixServerNameKeepsOptionalPort() {
        assertEquals("example.org:8448", "@me:example.org:8448".matrixServerNameOrNull())
        assertNull("@me".matrixServerNameOrNull())
    }
}
