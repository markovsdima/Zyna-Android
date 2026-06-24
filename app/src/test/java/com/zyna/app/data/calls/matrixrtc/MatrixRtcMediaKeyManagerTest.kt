package com.zyna.app.data.calls.matrixrtc

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatrixRtcMediaKeyManagerTest {
    @Test
    fun sharesOutboundMediaKeyWithActiveMemberships() = runBlocking {
        val client = MediaFakeCustomToDeviceClient()
        val ownMembership = legacyMembership("\$own", "@alice:example.org", "ALICEDEVICE", 1_000)
        val bobMembership = legacyMembership("\$bob", "@bob:example.org", "BOBDEVICE", 2_000)
        val keyBox = MediaKeyEventBox()
        val manager = MatrixRtcMediaKeyManager(
            ownMembership = ownMembership,
            memberships = listOf(ownMembership, bobMembership),
            transport = MatrixRtcToDeviceKeyTransport(
                roomId = "!room:example.org",
                ownIdentity = ownMembership.identity,
                client = client
            ),
            keyGenerator = StaticMediaKeyGenerator("own-key"),
            onKeyChanged = { event -> keyBox.events += event }
        )

        val result = manager.shareCurrentKey()

        assertTrue(result.failures.isEmpty())
        assertEquals(
            listOf(MatrixRtcToDeviceTarget("@bob:example.org", "BOBDEVICE")),
            result.sharedWith
        )
        assertEquals(MatrixRtcCallEncryptionKeysContent.EVENT_TYPE, client.sentEventType)
        assertEquals(
            listOf(MatrixRtcToDeviceTarget("@bob:example.org", "BOBDEVICE")),
            client.sentTargets
        )

        val content = MatrixRtcCallEncryptionKeysContent.fromJson(client.sentContentJson!!)
        assertEquals(MatrixRtcCallEncryptionKeysContent.Keys(index = 0, key = "own-key"), content.keys)
        assertEquals(
            MatrixRtcCallEncryptionKeysContent.Member(
                id = "@alice:example.org:ALICEDEVICE",
                claimedDeviceId = "ALICEDEVICE"
            ),
            content.member
        )
        assertEquals("!room:example.org", content.roomId)

        val ownKey = keyBox.events.first().key
        assertEquals("own-key", ownKey.keyBase64Encoded)
        assertEquals(0, ownKey.keyIndex)
        assertEquals(ownMembership.identity, ownKey.membership)
        assertEquals("@alice:example.org:ALICEDEVICE", ownKey.rtcBackendIdentity)
    }

    @Test
    fun doesNotReshareOutboundMediaKeyToSameMembership() = runBlocking {
        val client = MediaFakeCustomToDeviceClient()
        val ownMembership = legacyMembership("\$own", "@alice:example.org", "ALICEDEVICE", 1_000)
        val bobMembership = legacyMembership("\$bob", "@bob:example.org", "BOBDEVICE", 2_000)
        val manager = mediaKeyManager(
            client = client,
            ownMembership = ownMembership,
            memberships = listOf(ownMembership, bobMembership)
        )

        manager.shareCurrentKey()
        val secondResult = manager.shareCurrentKey()

        assertTrue(secondResult.sharedWith.isEmpty())
        assertEquals(1, client.sendCount)
    }

    @Test
    fun forceResharesCurrentOutboundMediaKeyToSameMembership() = runBlocking {
        val client = MediaFakeCustomToDeviceClient()
        val ownMembership = legacyMembership("\$own", "@alice:example.org", "ALICEDEVICE", 1_000)
        val bobMembership = legacyMembership("\$bob", "@bob:example.org", "BOBDEVICE", 2_000)
        val manager = mediaKeyManager(
            client = client,
            ownMembership = ownMembership,
            memberships = listOf(ownMembership, bobMembership)
        )

        manager.shareCurrentKey()
        val reshareResult = manager.reshareCurrentKey()

        assertEquals(
            listOf(MatrixRtcToDeviceTarget("@bob:example.org", "BOBDEVICE")),
            reshareResult.sharedWith
        )
        assertEquals(2, client.sendCount)
        assertEquals(
            listOf(
                listOf(MatrixRtcToDeviceTarget("@bob:example.org", "BOBDEVICE")),
                listOf(MatrixRtcToDeviceTarget("@bob:example.org", "BOBDEVICE"))
            ),
            client.sentTargetsHistory
        )
    }

    @Test
    fun failedForceReshareDoesNotReportTargetAsShared() = runBlocking {
        val client = MediaFakeCustomToDeviceClient()
        val ownMembership = legacyMembership("\$own", "@alice:example.org", "ALICEDEVICE", 1_000)
        val bobMembership = legacyMembership("\$bob", "@bob:example.org", "BOBDEVICE", 2_000)
        val manager = mediaKeyManager(
            client = client,
            ownMembership = ownMembership,
            memberships = listOf(ownMembership, bobMembership)
        )

        val failure = MatrixRtcCustomToDeviceSendFailure(
            userId = "@bob:example.org",
            deviceId = "BOBDEVICE",
            reason = MatrixRtcCustomToDeviceSendFailureReason.SEND_FAILED
        )
        client.sendFailures = listOf(failure)
        val failedReshare = manager.reshareCurrentKey()
        assertEquals(listOf(failure), failedReshare.failures)
        assertTrue(failedReshare.sharedWith.isEmpty())

        client.sendFailures = emptyList()
        val successfulReshare = manager.reshareCurrentKey()
        assertTrue(successfulReshare.failures.isEmpty())
        assertEquals(
            listOf(MatrixRtcToDeviceTarget("@bob:example.org", "BOBDEVICE")),
            successfulReshare.sharedWith
        )
        assertEquals(2, client.sendCount)
    }

    @Test
    fun sharesCurrentOutboundMediaKeyWithJoinerInsideGracePeriod() = runBlocking {
        val client = MediaFakeCustomToDeviceClient()
        val clock = MediaKeyTestClock(now = 10_000)
        val ownMembership = legacyMembership("\$own", "@alice:example.org", "ALICEDEVICE", 1_000)
        val bobMembership = legacyMembership("\$bob", "@bob:example.org", "BOBDEVICE", 2_000)
        val charlieMembership = legacyMembership("\$charlie", "@charlie:example.org", "CHARLIEDEVICE", 3_000)
        val keyBox = MediaKeyEventBox()
        val manager = mediaKeyManager(
            client = client,
            ownMembership = ownMembership,
            memberships = listOf(ownMembership, bobMembership),
            keyGenerator = SequenceMediaKeyGenerator(listOf("own-key-0", "own-key-1")),
            clock = clock,
            keyBox = keyBox
        )

        manager.ensureKeyDistribution()
        clock.now = 10_500
        val result = manager.ensureKeyDistribution(
            listOf(ownMembership, bobMembership, charlieMembership)
        )

        assertEquals(
            listOf(MatrixRtcToDeviceTarget("@charlie:example.org", "CHARLIEDEVICE")),
            result.sharedWith
        )
        assertEquals(2, client.sendCount)
        assertEquals(
            listOf(MatrixRtcToDeviceTarget("@charlie:example.org", "CHARLIEDEVICE")),
            client.sentTargetsHistory.last()
        )
        val content = MatrixRtcCallEncryptionKeysContent.fromJson(client.sentContentJsonHistory.last())
        assertEquals(MatrixRtcCallEncryptionKeysContent.Keys(index = 0, key = "own-key-0"), content.keys)
        assertEquals(listOf(0), keyBox.events.map { it.key.keyIndex })
    }

    @Test
    fun rotatesOutboundMediaKeyForJoinerOutsideGracePeriod() = runBlocking {
        val client = MediaFakeCustomToDeviceClient()
        val clock = MediaKeyTestClock(now = 10_000)
        val ownMembership = legacyMembership("\$own", "@alice:example.org", "ALICEDEVICE", 1_000)
        val bobMembership = legacyMembership("\$bob", "@bob:example.org", "BOBDEVICE", 2_000)
        val charlieMembership = legacyMembership("\$charlie", "@charlie:example.org", "CHARLIEDEVICE", 3_000)
        val keyBox = MediaKeyEventBox()
        val manager = mediaKeyManager(
            client = client,
            ownMembership = ownMembership,
            memberships = listOf(ownMembership, bobMembership),
            keyGenerator = SequenceMediaKeyGenerator(listOf("own-key-0", "own-key-1")),
            clock = clock,
            keyBox = keyBox
        )

        manager.ensureKeyDistribution()
        clock.now = 21_000
        val result = manager.ensureKeyDistribution(
            listOf(ownMembership, bobMembership, charlieMembership)
        )

        assertEquals(
            listOf(
                MatrixRtcToDeviceTarget("@bob:example.org", "BOBDEVICE"),
                MatrixRtcToDeviceTarget("@charlie:example.org", "CHARLIEDEVICE")
            ),
            result.sharedWith
        )
        val content = MatrixRtcCallEncryptionKeysContent.fromJson(client.sentContentJsonHistory.last())
        assertEquals(MatrixRtcCallEncryptionKeysContent.Keys(index = 1, key = "own-key-1"), content.keys)
        assertEquals(listOf(0, 1), keyBox.events.map { it.key.keyIndex })
    }

    @Test
    fun rotatesOutboundMediaKeyWhenParticipantLeaves() = runBlocking {
        val client = MediaFakeCustomToDeviceClient()
        val clock = MediaKeyTestClock(now = 10_000)
        val ownMembership = legacyMembership("\$own", "@alice:example.org", "ALICEDEVICE", 1_000)
        val bobMembership = legacyMembership("\$bob", "@bob:example.org", "BOBDEVICE", 2_000)
        val charlieMembership = legacyMembership("\$charlie", "@charlie:example.org", "CHARLIEDEVICE", 3_000)
        val keyBox = MediaKeyEventBox()
        val manager = mediaKeyManager(
            client = client,
            ownMembership = ownMembership,
            memberships = listOf(ownMembership, bobMembership, charlieMembership),
            keyGenerator = SequenceMediaKeyGenerator(listOf("own-key-0", "own-key-1")),
            clock = clock,
            keyBox = keyBox
        )

        manager.ensureKeyDistribution()
        clock.now = 10_500
        val result = manager.ensureKeyDistribution(listOf(ownMembership, bobMembership))

        assertEquals(
            listOf(MatrixRtcToDeviceTarget("@bob:example.org", "BOBDEVICE")),
            result.sharedWith
        )
        val content = MatrixRtcCallEncryptionKeysContent.fromJson(client.sentContentJsonHistory.last())
        assertEquals(MatrixRtcCallEncryptionKeysContent.Keys(index = 1, key = "own-key-1"), content.keys)
        assertEquals(listOf(0, 1), keyBox.events.map { it.key.keyIndex })
    }

    @Test
    fun queuesInboundMediaKeyUntilMatchingMembershipArrives() {
        val client = MediaFakeCustomToDeviceClient()
        val ownMembership = legacyMembership("\$own", "@alice:example.org", "ALICEDEVICE", 1_000)
        val bobMembership = legacyMembership("\$bob", "@bob:example.org", "BOBDEVICE", 2_000)
        val keyBox = MediaKeyEventBox()
        val manager = mediaKeyManager(
            client = client,
            ownMembership = ownMembership,
            memberships = listOf(ownMembership),
            keyBox = keyBox
        )

        manager.handleReceivedKey(
            MatrixRtcReceivedCallEncryptionKey(
                sender = "@bob:example.org",
                membership = bobMembership.identity,
                keyBase64Encoded = "bob-key",
                keyIndex = 3,
                sentTimestamp = null,
                encryptionInfo = null
            )
        )
        assertTrue(keyBox.events.isEmpty())

        manager.updateMemberships(listOf(ownMembership, bobMembership))

        val changedKey = keyBox.events.first().key
        assertEquals("bob-key", changedKey.keyBase64Encoded)
        assertEquals("@bob:example.org:BOBDEVICE", changedKey.rtcBackendIdentity)
    }

    @Test
    fun handlesInboundMediaKeysReceivedThroughTransport() {
        val client = MediaFakeCustomToDeviceClient()
        val ownMembership = legacyMembership("\$own", "@alice:example.org", "ALICEDEVICE", 1_000)
        val bobMembership = legacyMembership("\$bob", "@bob:example.org", "BOBDEVICE", 2_000)
        val keyBox = MediaKeyEventBox()
        val manager = mediaKeyManager(
            client = client,
            ownMembership = ownMembership,
            memberships = listOf(ownMembership, bobMembership),
            keyBox = keyBox
        )
        manager.start()

        val content = MatrixRtcCallEncryptionKeysContent(
            keys = MatrixRtcCallEncryptionKeysContent.Keys(index = 5, key = "bob-key"),
            member = MatrixRtcCallEncryptionKeysContent.Member(
                id = bobMembership.memberId,
                claimedDeviceId = "BOBDEVICE"
            ),
            roomId = "!room:example.org",
            sentTimestamp = 123_456
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

        assertEquals(MatrixRtcCallEncryptionKeysContent.EVENT_TYPE, client.listenerEventType)
        assertEquals(true, client.listenerEncryptedOnly)
        val changedKey = keyBox.events.first().key
        assertEquals("bob-key", changedKey.keyBase64Encoded)
        assertEquals(5, changedKey.keyIndex)
        assertEquals(bobMembership.identity, changedKey.membership)
        assertEquals("@bob:example.org:BOBDEVICE", changedKey.rtcBackendIdentity)
    }

    private fun mediaKeyManager(
        client: MediaFakeCustomToDeviceClient,
        ownMembership: MatrixRtcCallMembership,
        memberships: List<MatrixRtcCallMembership>,
        keyGenerator: MatrixRtcMediaKeyGenerating = StaticMediaKeyGenerator("own-key"),
        clock: MediaKeyTestClock = MediaKeyTestClock(now = 10_000),
        keyBox: MediaKeyEventBox = MediaKeyEventBox()
    ): MatrixRtcMediaKeyManager {
        return MatrixRtcMediaKeyManager(
            ownMembership = ownMembership,
            memberships = memberships,
            transport = MatrixRtcToDeviceKeyTransport(
                roomId = "!room:example.org",
                ownIdentity = ownMembership.identity,
                client = client
            ),
            keyGenerator = keyGenerator,
            rotationConfiguration = MatrixRtcMediaKeyRotationConfiguration(
                useKeyDelayMillis = 0,
                keyRotationGracePeriodMillis = 10_000
            ),
            timestampProvider = { clock.now },
            onKeyChanged = { event -> keyBox.events += event }
        )
    }

    private fun legacyMembership(
        eventId: String,
        sender: String,
        deviceId: String,
        createdTimestamp: Long
    ): MatrixRtcCallMembership {
        return MatrixRtcCallMembershipParser.parse(
            MatrixRtcRawMembershipEvent(
                eventId = eventId,
                eventType = MatrixRtcRawMembershipEvent.LEGACY_CALL_MEMBER_EVENT_TYPE,
                stateKey = "_${sender}_${deviceId}_m.call",
                sender = sender,
                originServerTimestamp = createdTimestamp,
                contentJson = """
                    {
                      "application": "m.call",
                      "call_id": "",
                      "device_id": "$deviceId",
                      "focus_active": {
                        "type": "livekit",
                        "focus_selection": "oldest_membership"
                      },
                      "foci_preferred": [],
                      "created_ts": $createdTimestamp,
                      "expires": 50000
                    }
                """.trimIndent()
            )
        )
    }
}

private data class StaticMediaKeyGenerator(
    val key: String
) : MatrixRtcMediaKeyGenerating {
    override fun generateMediaKeyBase64Encoded(): String = key
}

private class SequenceMediaKeyGenerator(
    private val keys: List<String>
) : MatrixRtcMediaKeyGenerating {
    private var index = 0

    override fun generateMediaKeyBase64Encoded(): String {
        return keys[index].also {
            index += 1
        }
    }
}

private class MediaKeyTestClock(
    var now: Long
)

private class MediaKeyEventBox {
    var events: List<MatrixRtcMediaKeyChangedEvent> = emptyList()
}

private class MediaFakeCustomToDeviceClient : MatrixRtcCustomToDeviceEncrypting {
    var sentEventType: String? = null
    var sentTargets: List<MatrixRtcToDeviceTarget>? = null
    var sentContentJson: String? = null
    var sentTargetsHistory: List<List<MatrixRtcToDeviceTarget>> = emptyList()
    var sentContentJsonHistory: List<String> = emptyList()
    var sendCount = 0
    var sendFailures: List<MatrixRtcCustomToDeviceSendFailure> = emptyList()

    var listenerEventType: String? = null
    var listenerEncryptedOnly: Boolean? = null
    private var listener: ((MatrixRtcCustomToDeviceEvent) -> Unit)? = null

    override suspend fun encryptAndSendRawToDevice(
        eventType: String,
        targets: List<MatrixRtcToDeviceTarget>,
        contentJson: String
    ): List<MatrixRtcCustomToDeviceSendFailure> {
        sendCount += 1
        sentEventType = eventType
        sentTargets = targets
        sentContentJson = contentJson
        sentTargetsHistory = sentTargetsHistory + listOf(targets)
        sentContentJsonHistory = sentContentJsonHistory + contentJson
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

