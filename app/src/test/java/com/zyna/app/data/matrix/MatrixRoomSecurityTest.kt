package com.zyna.app.data.matrix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.matrix.rustcomponents.sdk.AllowRule
import org.matrix.rustcomponents.sdk.JoinRule

class MatrixRoomSecurityTest {
    @Test
    fun restrictedRulePreservesEveryParentAndUnsupportedRules() {
        val mapped = JoinRule.Restricted(
            rules = listOf(
                AllowRule.RoomMembership("!story-a:example.org"),
                AllowRule.RoomMembership("!story-b:example.org"),
                AllowRule.Custom("{\"type\":\"com.example.custom\"}")
            )
        ).toMatrixRoomSecurityJoinRule() as MatrixRoomSecurityJoinRule.Restricted

        assertEquals(
            setOf("!story-a:example.org", "!story-b:example.org"),
            mapped.spaceIds
        )
        assertTrue(mapped.hasUnsupportedRules)
    }

    @Test
    fun standardRulesDoNotConflateKnockWithInviteOnly() {
        assertEquals(
            MatrixRoomSecurityJoinRule.InviteOnly,
            JoinRule.Invite.toMatrixRoomSecurityJoinRule()
        )
        assertEquals(
            MatrixRoomSecurityJoinRule.InviteOnly,
            JoinRule.Private.toMatrixRoomSecurityJoinRule()
        )
        assertEquals(
            MatrixRoomSecurityJoinRule.Public,
            JoinRule.Public.toMatrixRoomSecurityJoinRule()
        )
        assertEquals(
            MatrixRoomSecurityJoinRule.Unsupported,
            JoinRule.Knock.toMatrixRoomSecurityJoinRule()
        )
    }
}
