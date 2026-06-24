package com.zyna.app.data.calls.matrixrtc

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MatrixRtcToDeviceKeyTransportTest {
    @Test
    fun sendsCallEncryptionKeysToExactTargets() = runBlocking {
        val client = FakeCustomToDeviceClient()
        val transport = MatrixRtcToDeviceKeyTransport(
            roomId = "!room:example.org",
            ownIdentity = MatrixRtcMembershipIdentity(
                userId = "@alice:example.org",
                deviceId = "ALICEDEVICE",
                memberId = "alice-member"
            ),
            client = client,
            sentTimestampProvider = { 123_456 }
        )
        val targets = listOf(MatrixRtcToDeviceTarget(userId = "@bob:example.org", deviceId = "BOBDEVICE"))

        val failures = transport.sendKey(
            keyBase64Encoded = "base64-key",
            index = 3,
            targets = targets
        )

        assertEquals(emptyList<MatrixRtcCustomToDeviceSendFailure>(), failures)
        assertEquals(MatrixRtcCallEncryptionKeysContent.EVENT_TYPE, client.sentEventType)
        assertEquals(targets, client.sentTargets)

        val sentContent = MatrixRtcCallEncryptionKeysContent.fromJson(client.sentContentJson!!)
        assertEquals(MatrixRtcCallEncryptionKeysContent.Keys(index = 3, key = "base64-key"), sentContent.keys)
        assertEquals(
            MatrixRtcCallEncryptionKeysContent.Member(
                id = "alice-member",
                claimedDeviceId = "ALICEDEVICE"
            ),
            sentContent.member
        )
        assertEquals("!room:example.org", sentContent.roomId)
        assertEquals(MatrixRtcCallEncryptionKeysContent.Session.MATRIX_CALL_ROOM, sentContent.session)
        assertEquals(123_456L, sentContent.sentTimestamp)
    }

    @Test
    fun receivesEncryptedCallEncryptionKeys() {
        val client = FakeCustomToDeviceClient()
        val box = ReceivedKeyBox()
        val transport = MatrixRtcToDeviceKeyTransport(
            roomId = "!room:example.org",
            ownIdentity = MatrixRtcMembershipIdentity(
                userId = "@alice:example.org",
                deviceId = "ALICEDEVICE",
                memberId = "alice-member"
            ),
            client = client,
            onReceivedKey = { result -> box.result = result }
        )
        transport.start()

        assertEquals(MatrixRtcCallEncryptionKeysContent.EVENT_TYPE, client.listenerEventType)
        assertEquals(true, client.listenerEncryptedOnly)

        val content = MatrixRtcCallEncryptionKeysContent(
            keys = MatrixRtcCallEncryptionKeysContent.Keys(index = 9, key = "remote-key"),
            member = MatrixRtcCallEncryptionKeysContent.Member(
                id = "bob-member",
                claimedDeviceId = "BOBDEVICE"
            ),
            roomId = "!room:example.org",
            sentTimestamp = 654_321
        )
        client.emit(
            MatrixRtcCustomToDeviceEvent(
                eventType = MatrixRtcCallEncryptionKeysContent.EVENT_TYPE,
                sender = "@spoofed:example.org",
                contentJson = content.jsonString(),
                rawJson = "{}",
                encryptionInfo = MatrixRtcCustomToDeviceEncryptionInfo(
                    sender = "@bob:example.org",
                    senderDevice = "BOBDEVICE",
                    senderCurve25519KeyBase64 = "curve-key",
                    senderVerified = true
                )
            )
        )

        val received = box.result!!.getOrThrow()
        assertEquals("@bob:example.org", received.sender)
        assertEquals(
            MatrixRtcMembershipIdentity(
                userId = "@bob:example.org",
                deviceId = "BOBDEVICE",
                memberId = "bob-member"
            ),
            received.membership
        )
        assertEquals("remote-key", received.keyBase64Encoded)
        assertEquals(9, received.keyIndex)
        assertEquals(654_321L, received.sentTimestamp)
        assertEquals(true, received.encryptionInfo?.senderVerified)
    }

    @Test
    fun toDeviceTransportUsesUpdatedReceivedKeyHandlerAfterStart() {
        val client = FakeCustomToDeviceClient()
        val firstBox = ReceivedKeyBox()
        val secondBox = ReceivedKeyBox()
        val transport = MatrixRtcToDeviceKeyTransport(
            roomId = "!room:example.org",
            ownIdentity = MatrixRtcMembershipIdentity(
                userId = "@alice:example.org",
                deviceId = "ALICEDEVICE",
                memberId = "alice-member"
            ),
            client = client,
            onReceivedKey = { result -> firstBox.result = result }
        )
        transport.start()
        transport.setReceivedKeyHandler { result -> secondBox.result = result }

        val content = MatrixRtcCallEncryptionKeysContent(
            keys = MatrixRtcCallEncryptionKeysContent.Keys(index = 10, key = "remote-key"),
            member = MatrixRtcCallEncryptionKeysContent.Member(
                id = "bob-member",
                claimedDeviceId = "BOBDEVICE"
            ),
            roomId = "!room:example.org",
            sentTimestamp = 654_322
        )
        client.emit(
            MatrixRtcCustomToDeviceEvent(
                eventType = MatrixRtcCallEncryptionKeysContent.EVENT_TYPE,
                sender = "@spoofed:example.org",
                contentJson = content.jsonString(),
                rawJson = "{}",
                encryptionInfo = MatrixRtcCustomToDeviceEncryptionInfo(
                    sender = "@bob:example.org",
                    senderDevice = "BOBDEVICE",
                    senderCurve25519KeyBase64 = "curve-key",
                    senderVerified = true
                )
            )
        )

        assertNull(firstBox.result)
        val received = secondBox.result!!.getOrThrow()
        assertEquals(10, received.keyIndex)
        assertEquals(
            MatrixRtcMembershipIdentity(
                userId = "@bob:example.org",
                deviceId = "BOBDEVICE",
                memberId = "bob-member"
            ),
            received.membership
        )
    }

    @Test
    fun ignoresCallEncryptionKeysForOtherRooms() {
        val client = FakeCustomToDeviceClient()
        val box = ReceivedKeyBox()
        val transport = MatrixRtcToDeviceKeyTransport(
            roomId = "!room:example.org",
            ownIdentity = MatrixRtcMembershipIdentity(
                userId = "@alice:example.org",
                deviceId = "ALICEDEVICE",
                memberId = "alice-member"
            ),
            client = client,
            onReceivedKey = { result -> box.result = result }
        )
        transport.start()

        val content = MatrixRtcCallEncryptionKeysContent(
            keys = MatrixRtcCallEncryptionKeysContent.Keys(index = 1, key = "other-room-key"),
            member = MatrixRtcCallEncryptionKeysContent.Member(
                id = "bob-member",
                claimedDeviceId = "BOBDEVICE"
            ),
            roomId = "!other:example.org",
            sentTimestamp = 123
        )
        client.emit(
            MatrixRtcCustomToDeviceEvent(
                eventType = MatrixRtcCallEncryptionKeysContent.EVENT_TYPE,
                sender = "@bob:example.org",
                contentJson = content.jsonString(),
                rawJson = "{}",
                encryptionInfo = MatrixRtcCustomToDeviceEncryptionInfo(
                    sender = "@bob:example.org",
                    senderDevice = "BOBDEVICE",
                    senderCurve25519KeyBase64 = "curve-key",
                    senderVerified = true
                )
            )
        )

        assertNull(box.result)
    }

    @Test
    fun fallsBackToClaimedDeviceMemberIdWhenMissing() {
        val client = FakeCustomToDeviceClient()
        val box = ReceivedKeyBox()
        val transport = MatrixRtcToDeviceKeyTransport(
            roomId = "!room:example.org",
            ownIdentity = MatrixRtcMembershipIdentity(
                userId = "@alice:example.org",
                deviceId = "ALICEDEVICE",
                memberId = "alice-member"
            ),
            client = client,
            onReceivedKey = { result -> box.result = result }
        )
        transport.start()

        val content = MatrixRtcCallEncryptionKeysContent(
            keys = MatrixRtcCallEncryptionKeysContent.Keys(index = 2, key = "fallback-key"),
            member = MatrixRtcCallEncryptionKeysContent.Member(
                id = null,
                claimedDeviceId = "BOBDEVICE"
            ),
            roomId = "!room:example.org",
            sentTimestamp = 456
        )
        client.emit(
            MatrixRtcCustomToDeviceEvent(
                eventType = MatrixRtcCallEncryptionKeysContent.EVENT_TYPE,
                sender = "@spoofed:example.org",
                contentJson = content.jsonString(),
                rawJson = "{}",
                encryptionInfo = MatrixRtcCustomToDeviceEncryptionInfo(
                    sender = "@bob:example.org",
                    senderDevice = "BOBDEVICE",
                    senderCurve25519KeyBase64 = "curve-key",
                    senderVerified = true
                )
            )
        )

        val received = box.result!!.getOrThrow()
        assertEquals("@bob:example.org:BOBDEVICE", received.membership.memberId)
    }
}

private class ReceivedKeyBox {
    var result: Result<MatrixRtcReceivedCallEncryptionKey>? = null
}

private class FakeCustomToDeviceClient : MatrixRtcCustomToDeviceEncrypting {
    var sentEventType: String? = null
    var sentTargets: List<MatrixRtcToDeviceTarget>? = null
    var sentContentJson: String? = null
    var sendFailures: List<MatrixRtcCustomToDeviceSendFailure> = emptyList()

    var listenerEventType: String? = null
    var listenerEncryptedOnly: Boolean? = null
    private var listener: ((MatrixRtcCustomToDeviceEvent) -> Unit)? = null

    override suspend fun encryptAndSendRawToDevice(
        eventType: String,
        targets: List<MatrixRtcToDeviceTarget>,
        contentJson: String
    ): List<MatrixRtcCustomToDeviceSendFailure> {
        sentEventType = eventType
        sentTargets = targets
        sentContentJson = contentJson
        return sendFailures
    }

    override fun addCustomToDeviceEventListener(
        eventType: String,
        encryptedOnly: Boolean,
        listener: (MatrixRtcCustomToDeviceEvent) -> Unit
    ): MatrixRtcCancellable {
        listenerEventType = eventType
        listenerEncryptedOnly = encryptedOnly
        this.listener = listener
        return MatrixRtcNoopCancellable
    }

    fun emit(event: MatrixRtcCustomToDeviceEvent) {
        listener?.invoke(event)
    }
}
