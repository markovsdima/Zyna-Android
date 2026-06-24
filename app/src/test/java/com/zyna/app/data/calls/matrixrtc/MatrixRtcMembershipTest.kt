package com.zyna.app.data.calls.matrixrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MatrixRtcMembershipTest {
    @Test
    fun parsesLegacyRoomMembership() {
        val event = legacyEvent(
            eventId = "\$legacy1",
            sender = "@alice:example.org",
            originServerTimestamp = 1_000,
            contentJson = """
                {
                  "application": "m.call",
                  "call_id": "",
                  "scope": "m.room",
                  "device_id": "ALICEDEVICE",
                  "focus_active": {
                    "type": "livekit",
                    "focus_selection": "oldest_membership"
                  },
                  "foci_preferred": [
                    {
                      "type": "livekit",
                      "livekit_service_url": "https://livekit.example.org"
                    }
                  ],
                  "expires": 10000,
                  "m.call.intent": "m.audio"
                }
            """.trimIndent()
        )

        val membership = MatrixRtcCallMembershipParser.parse(event)

        assertEquals(MatrixRtcCallMembership.Kind.LEGACY_STATE, membership.kind)
        assertEquals(MatrixRtcSlotDescription.MATRIX_CALL_ROOM, membership.slot)
        assertEquals("", membership.slot.legacyCallId)
        assertEquals(
            MatrixRtcMembershipIdentity(
                userId = "@alice:example.org",
                deviceId = "ALICEDEVICE",
                memberId = "@alice:example.org:ALICEDEVICE"
            ),
            membership.identity
        )
        assertEquals(1_000L, membership.createdTimestamp)
        assertEquals(11_000L, membership.absoluteExpiryTimestamp)
        assertEquals("@alice:example.org:ALICEDEVICE", membership.rtcBackendIdentity)
        assertEquals("livekit", membership.transports.first().type)
        assertEquals("https://livekit.example.org", membership.transports.first().liveKitServiceUrl)
        assertEquals("oldest_membership", membership.focusSelection)
        assertEquals("m.audio", membership.callIntent)
    }

    @Test
    fun parsesLegacyMembershipIdWithoutChangingBackendIdentity() {
        val event = legacyEvent(
            eventId = "\$legacy2",
            sender = "@alice:example.org",
            originServerTimestamp = 1_000,
            contentJson = """
                {
                  "application": "m.call",
                  "call_id": "",
                  "device_id": "ALICEDEVICE",
                  "focus_active": {
                    "type": "livekit",
                    "focus_selection": "multi_sfu"
                  },
                  "foci_preferred": [],
                  "created_ts": 500,
                  "membershipID": "custom-member"
                }
            """.trimIndent()
        )

        val membership = MatrixRtcCallMembershipParser.parse(event)

        assertEquals("custom-member", membership.memberId)
        assertEquals(500L, membership.createdTimestamp)
        assertEquals("@alice:example.org:ALICEDEVICE", membership.rtcBackendIdentity)
    }

    @Test
    fun filtersActiveMembershipsForSlotAndJoinedUsers() {
        val events = listOf(
            legacyEvent(
                eventId = "\$valid2",
                sender = "@bob:example.org",
                originServerTimestamp = 2_000,
                contentJson = legacyMembershipJson("BOBDEVICE", createdTimestamp = 2_000, expires = 50_000)
            ),
            legacyEvent(
                eventId = "\$expired",
                sender = "@expired:example.org",
                originServerTimestamp = 1_000,
                contentJson = legacyMembershipJson("OLDDEVICE", createdTimestamp = 1_000, expires = 100)
            ),
            legacyEvent(
                eventId = "\$other-slot",
                sender = "@other:example.org",
                originServerTimestamp = 3_000,
                contentJson = legacyMembershipJson(
                    deviceId = "OTHERDEVICE",
                    callId = "breakout",
                    createdTimestamp = 3_000,
                    expires = 50_000
                )
            ),
            legacyEvent(
                eventId = "\$not-joined",
                sender = "@mallory:example.org",
                originServerTimestamp = 4_000,
                contentJson = legacyMembershipJson("MALLORYDEVICE", createdTimestamp = 4_000, expires = 50_000)
            ),
            legacyEvent(
                eventId = "\$valid1",
                sender = "@alice:example.org",
                originServerTimestamp = 1_500,
                contentJson = legacyMembershipJson("ALICEDEVICE", createdTimestamp = 1_500, expires = 50_000)
            )
        )

        val memberships = MatrixRtcCallMembershipParser.activeMemberships(
            events = events,
            slot = MatrixRtcSlotDescription.MATRIX_CALL_ROOM,
            joinedUserIds = setOf("@alice:example.org", "@bob:example.org"),
            now = 10_000
        )

        assertEquals(listOf("@alice:example.org", "@bob:example.org"), memberships.map { it.userId })
        assertEquals(listOf(1_500L, 2_000L), memberships.map { it.createdTimestamp })
    }

    @Test
    fun buildsUniqueToDeviceTargetsExcludingOwnDevice() {
        val memberships = MatrixRtcCallMembershipParser.activeMemberships(
            events = listOf(
                legacyEvent(
                    eventId = "\$own",
                    sender = "@alice:example.org",
                    originServerTimestamp = 1_000,
                    contentJson = legacyMembershipJson("ALICEDEVICE", createdTimestamp = 1_000, expires = 50_000)
                ),
                legacyEvent(
                    eventId = "\$bob1",
                    sender = "@bob:example.org",
                    originServerTimestamp = 2_000,
                    contentJson = legacyMembershipJson("BOBDEVICE", createdTimestamp = 2_000, expires = 50_000)
                ),
                legacyEvent(
                    eventId = "\$bob2",
                    sender = "@bob:example.org",
                    originServerTimestamp = 3_000,
                    contentJson = legacyMembershipJson("BOBDEVICE", createdTimestamp = 3_000, expires = 50_000)
                )
            ),
            now = 10_000
        )

        val targets = MatrixRtcCallMembershipParser.toDeviceTargets(
            memberships = memberships,
            excluding = MatrixRtcMembershipIdentity(
                userId = "@alice:example.org",
                deviceId = "ALICEDEVICE",
                memberId = "@alice:example.org:ALICEDEVICE"
            )
        )

        assertEquals(
            listOf(MatrixRtcToDeviceTarget(userId = "@bob:example.org", deviceId = "BOBDEVICE")),
            targets
        )
    }

    @Test
    fun parsesRtcMemberWithHashedBackendIdentity() {
        val event = MatrixRtcRawMembershipEvent(
            eventId = "\$rtc1",
            eventType = MatrixRtcRawMembershipEvent.RTC_MEMBER_EVENT_TYPE,
            stateKey = null,
            sender = "@alice:example.com",
            originServerTimestamp = 1_000,
            contentJson = """
                {
                  "slot_id": "m.call#ROOM",
                  "member": {
                    "user_id": "@alice:example.com",
                    "device_id": "DEVICE123",
                    "id": "memberABC"
                  },
                  "application": {
                    "type": "m.call",
                    "m.call.intent": "m.video"
                  },
                  "rtc_transports": [
                    {
                      "type": "livekit",
                      "livekit_service_url": "https://livekit.example.org"
                    }
                  ],
                  "versions": ["1"],
                  "sticky_key": "memberABC"
                }
            """.trimIndent()
        )

        val membership = MatrixRtcCallMembershipParser.parse(event)

        assertEquals(MatrixRtcCallMembership.Kind.RTC, membership.kind)
        assertEquals(MatrixRtcSlotDescription.MATRIX_CALL_ROOM, membership.slot)
        assertEquals(
            MatrixRtcMembershipIdentity(
                userId = "@alice:example.com",
                deviceId = "DEVICE123",
                memberId = "memberABC"
            ),
            membership.identity
        )
        assertNull(membership.absoluteExpiryTimestamp)
        assertEquals("J+T45tGruxc+HrUOqJJlyQSV33m728Cme4+vt8/SWrU", membership.rtcBackendIdentity)
        assertEquals("m.video", membership.callIntent)
    }

    @Test
    fun buildsLegacyOwnMembershipStateKey() {
        assertEquals(
            "_@alice:example.org_ALICEDEVICE_m.call",
            MatrixRtcLegacyCallMembershipStateEvent.stateKey(
                userId = "@alice:example.org",
                deviceId = "ALICEDEVICE",
                slot = MatrixRtcSlotDescription.MATRIX_CALL_ROOM
            )
        )
        assertEquals(
            "_@alice:example.org_ALICEDEVICE_m.callbreakout",
            MatrixRtcLegacyCallMembershipStateEvent.stateKey(
                userId = "@alice:example.org",
                deviceId = "ALICEDEVICE",
                slot = MatrixRtcSlotDescription(application = "m.call", id = "breakout")
            )
        )
        assertEquals(
            "@alice:example.org_ALICEDEVICE_m.call",
            MatrixRtcLegacyCallMembershipStateEvent.stateKey(
                userId = "@alice:example.org",
                deviceId = "ALICEDEVICE",
                slot = MatrixRtcSlotDescription.MATRIX_CALL_ROOM,
                roomVersion = "org.matrix.msc3757.10"
            )
        )
    }

    @Test
    fun buildsLegacyOwnMembershipContent() {
        val event = MatrixRtcLegacyCallMembershipStateEvent.create(
            userId = "@alice:example.org",
            deviceId = "ALICEDEVICE",
            focusSelection = MatrixRtcLegacyCallMembershipFocusSelection.MULTI_SFU,
            fociPreferred = listOf(MatrixRtcTransport.liveKit("https://livekit.example.org")),
            createdTimestamp = 123_456,
            expires = 14_400_000,
            callIntent = "m.audio"
        )

        val content = MatrixRtcLegacyCallMembershipContent.fromJson(event.contentJson())

        assertEquals(
            MatrixRtcMembershipIdentity(
                userId = "@alice:example.org",
                deviceId = "ALICEDEVICE",
                memberId = "@alice:example.org:ALICEDEVICE"
            ),
            event.identity
        )
        assertEquals("_@alice:example.org_ALICEDEVICE_m.call", event.stateKey)
        assertEquals("m.call", content.application)
        assertEquals("", content.callId)
        assertEquals("m.room", content.scope)
        assertEquals("ALICEDEVICE", content.deviceId)
        assertEquals("@alice:example.org:ALICEDEVICE", content.membershipId)
        assertEquals("livekit", content.focusActive.type)
        assertEquals(MatrixRtcLegacyCallMembershipFocusSelection.MULTI_SFU, content.focusActive.focusSelection)
        assertEquals("https://livekit.example.org", content.fociPreferred.first().liveKitServiceUrl)
        assertEquals(123_456L, content.createdTimestamp)
        assertEquals(14_400_000L, content.expires)
        assertEquals("m.audio", content.callIntent)
    }

    @Test
    fun buildsLegacyOwnJoinContentWithoutCreatedTimestamp() {
        val event = MatrixRtcLegacyCallMembershipStateEvent.create(
            userId = "@alice:example.org",
            deviceId = "ALICEDEVICE",
            focusSelection = MatrixRtcLegacyCallMembershipFocusSelection.OLDEST_MEMBERSHIP,
            fociPreferred = emptyList(),
            createdTimestamp = null,
            callIntent = null
        )

        val content = MatrixRtcLegacyCallMembershipContent.fromJson(event.contentJson())

        assertNull(content.createdTimestamp)
        assertNull(content.callIntent)
        assertEquals(MatrixRtcCallMembership.DEFAULT_EXPIRE_DURATION_MILLIS, content.expires)
        assertEquals("{}", MatrixRtcLegacyCallMembershipStateEvent.LEAVE_CONTENT_JSON)
    }

    private fun legacyEvent(
        eventId: String,
        sender: String,
        originServerTimestamp: Long,
        contentJson: String
    ): MatrixRtcRawMembershipEvent {
        return MatrixRtcRawMembershipEvent(
            eventId = eventId,
            eventType = MatrixRtcRawMembershipEvent.LEGACY_CALL_MEMBER_EVENT_TYPE,
            stateKey = "_${sender}_DEVICE_m.call",
            sender = sender,
            originServerTimestamp = originServerTimestamp,
            contentJson = contentJson
        )
    }

    private fun legacyMembershipJson(
        deviceId: String,
        callId: String = "",
        createdTimestamp: Long,
        expires: Long
    ): String {
        return """
            {
              "application": "m.call",
              "call_id": "$callId",
              "device_id": "$deviceId",
              "focus_active": {
                "type": "livekit",
                "focus_selection": "oldest_membership"
              },
              "foci_preferred": [],
              "created_ts": $createdTimestamp,
              "expires": $expires
            }
        """.trimIndent()
    }
}
