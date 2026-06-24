package com.zyna.app.data.calls.matrixrtc

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatrixRtcSessionTest {
    @Test
    fun joinsSessionAndSharesInitialMediaKey() = runBlocking {
        val membershipClient = FakeSessionMembershipClient()
        val toDeviceClient = SessionFakeCustomToDeviceClient()
        val ownMembership = sessionLegacyMembership("\$own", "@alice:example.org", "ALICEDEVICE", 10_000)
        val bobMembership = sessionLegacyMembership("\$bob", "@bob:example.org", "BOBDEVICE", 20_000)
        membershipClient.publishResult = ownMembership
        membershipClient.activeMembershipResponses = mutableListOf(listOf(bobMembership))
        val keyBox = SessionMediaKeyEventBox()
        val session = matrixRtcSession(
            membershipClient = membershipClient,
            toDeviceClient = toDeviceClient,
            fociPreferred = listOf(MatrixRtcTransport.liveKit("https://livekit.example.org")),
            callIntent = "m.audio",
            keyBox = keyBox
        )

        val result = session.join()

        assertEquals(MatrixRtcSessionState.JOINED, session.state)
        assertEquals(ownMembership, result.ownMembership)
        assertEquals(listOf(ownMembership.identity, bobMembership.identity), result.memberships.map { it.identity })
        assertEquals(
            listOf(MatrixRtcToDeviceTarget("@bob:example.org", "BOBDEVICE")),
            result.keyShareResult.sharedWith
        )

        assertEquals(1, membershipClient.publishCount)
        assertEquals(listOf(MatrixRtcTransport.liveKit("https://livekit.example.org")), membershipClient.publishedFociPreferred)
        assertEquals(10_000L, membershipClient.publishedCreatedTimestamp)
        assertEquals("m.audio", membershipClient.publishedCallIntent)
        assertEquals(1, membershipClient.loadCount)

        assertEquals(MatrixRtcCallEncryptionKeysContent.EVENT_TYPE, toDeviceClient.listenerEventType)
        assertEquals(true, toDeviceClient.listenerEncryptedOnly)
        assertEquals(
            listOf(MatrixRtcToDeviceTarget("@bob:example.org", "BOBDEVICE")),
            toDeviceClient.sentTargets
        )

        val sentContent = MatrixRtcCallEncryptionKeysContent.fromJson(toDeviceClient.sentContentJson!!)
        assertEquals(MatrixRtcCallEncryptionKeysContent.Keys(index = 0, key = "own-key"), sentContent.keys)
        assertEquals(
            MatrixRtcCallEncryptionKeysContent.Member(
                id = "@alice:example.org:ALICEDEVICE",
                claimedDeviceId = "ALICEDEVICE"
            ),
            sentContent.member
        )
        assertEquals("!room:example.org", sentContent.roomId)

        val ownKey = keyBox.events.first().key
        assertEquals(ownMembership.identity, ownKey.membership)
        assertEquals("@alice:example.org:ALICEDEVICE", ownKey.rtcBackendIdentity)
    }

    @Test
    fun joinsUnencryptedSessionWithoutMediaKeyTransport() = runBlocking {
        val membershipClient = FakeSessionMembershipClient()
        val toDeviceClient = SessionFakeCustomToDeviceClient()
        val ownMembership = sessionLegacyMembership("\$own", "@alice:example.org", "ALICEDEVICE", 10_000)
        val bobMembership = sessionLegacyMembership("\$bob", "@bob:example.org", "BOBDEVICE", 20_000)
        membershipClient.publishResult = ownMembership
        membershipClient.activeMembershipResponses = mutableListOf(listOf(bobMembership))
        val keyTransportFactoryCalled = SessionFlagBox()
        val session = matrixRtcSession(
            membershipClient = membershipClient,
            toDeviceClient = toDeviceClient,
            mediaEncryptionMode = MatrixRtcSessionMediaEncryptionMode.UNENCRYPTED,
            keyTransportFactoryCalled = keyTransportFactoryCalled
        )

        val joinResult = session.join()
        val refreshResult = session.refreshMemberships()
        val reshareResult = session.reshareCurrentMediaKey()

        assertEquals(MatrixRtcSessionState.JOINED, session.state)
        assertEquals(listOf(ownMembership.identity, bobMembership.identity), joinResult.memberships.map { it.identity })
        assertTrue(joinResult.keyShareResult.sharedWith.isEmpty())
        assertTrue(refreshResult.keyShareResult.sharedWith.isEmpty())
        assertTrue(reshareResult.keyShareResult.sharedWith.isEmpty())
        assertEquals(false, keyTransportFactoryCalled.value)
        assertNull(toDeviceClient.listenerEventType)
        assertEquals(0, toDeviceClient.sendCount)
        assertTrue(session.encryptionKeys().isEmpty())
    }

    @Test
    fun refreshSharesCurrentMediaKeyWithNewMembersOnly() = runBlocking {
        val membershipClient = FakeSessionMembershipClient()
        val toDeviceClient = SessionFakeCustomToDeviceClient()
        val ownMembership = sessionLegacyMembership("\$own", "@alice:example.org", "ALICEDEVICE", 10_000)
        val bobMembership = sessionLegacyMembership("\$bob", "@bob:example.org", "BOBDEVICE", 20_000)
        membershipClient.publishResult = ownMembership
        membershipClient.activeMembershipResponses = mutableListOf(emptyList(), listOf(bobMembership), listOf(bobMembership))
        val session = matrixRtcSession(
            membershipClient = membershipClient,
            toDeviceClient = toDeviceClient
        )

        session.join()
        assertEquals(0, toDeviceClient.sendCount)

        val firstRefresh = session.refreshMemberships()
        assertEquals(
            listOf(MatrixRtcToDeviceTarget("@bob:example.org", "BOBDEVICE")),
            firstRefresh.keyShareResult.sharedWith
        )
        assertEquals(1, toDeviceClient.sendCount)

        val secondRefresh = session.refreshMemberships()
        assertTrue(secondRefresh.keyShareResult.sharedWith.isEmpty())
        assertEquals(1, toDeviceClient.sendCount)
    }

    @Test
    fun refreshMembershipsCanSkipKeyDistribution() = runBlocking {
        val membershipClient = FakeSessionMembershipClient()
        val toDeviceClient = SessionFakeCustomToDeviceClient()
        val ownMembership = sessionLegacyMembership("\$own", "@alice:example.org", "ALICEDEVICE", 10_000)
        val bobMembership = sessionLegacyMembership("\$bob", "@bob:example.org", "BOBDEVICE", 20_000)
        membershipClient.publishResult = ownMembership
        membershipClient.activeMembershipResponses = mutableListOf(emptyList(), listOf(bobMembership), listOf(bobMembership))
        val session = matrixRtcSession(
            membershipClient = membershipClient,
            toDeviceClient = toDeviceClient
        )

        session.join()
        val readOnlyRefresh = session.refreshMemberships(distributeKeys = false)
        assertEquals(listOf(ownMembership.identity, bobMembership.identity), readOnlyRefresh.memberships.map { it.identity })
        assertTrue(readOnlyRefresh.keyShareResult.sharedWith.isEmpty())
        assertEquals(0, toDeviceClient.sendCount)

        val distributingRefresh = session.refreshMemberships()
        assertEquals(
            listOf(MatrixRtcToDeviceTarget("@bob:example.org", "BOBDEVICE")),
            distributingRefresh.keyShareResult.sharedWith
        )
        assertEquals(1, toDeviceClient.sendCount)
    }

    @Test
    fun joinAppliesMediaKeysReceivedBeforeManagerIsReady() = runBlocking {
        val membershipClient = FakeSessionMembershipClient()
        val toDeviceClient = SessionFakeCustomToDeviceClient()
        val ownMembership = sessionLegacyMembership("\$own", "@alice:example.org", "ALICEDEVICE", 10_000)
        val bobMembership = sessionLegacyMembership("\$bob", "@bob:example.org", "BOBDEVICE", 20_000)
        membershipClient.publishResult = ownMembership
        membershipClient.activeMembershipResponses = mutableListOf(listOf(bobMembership))
        val keyBox = SessionMediaKeyEventBox()
        val emittedBeforePublishReturned = SessionFlagBox()
        membershipClient.publishHook = {
            emittedBeforePublishReturned.value = toDeviceClient.listener != null
            val content = MatrixRtcCallEncryptionKeysContent(
                keys = MatrixRtcCallEncryptionKeysContent.Keys(index = 4, key = "bob-key"),
                member = MatrixRtcCallEncryptionKeysContent.Member(
                    id = bobMembership.memberId,
                    claimedDeviceId = bobMembership.deviceId
                ),
                roomId = "!room:example.org",
                sentTimestamp = 11_000
            )
            toDeviceClient.emit(
                MatrixRtcCustomToDeviceEvent(
                    eventType = MatrixRtcCallEncryptionKeysContent.EVENT_TYPE,
                    sender = "@spoofed:example.org",
                    contentJson = content.jsonString(),
                    rawJson = "{}",
                    encryptionInfo = MatrixRtcCustomToDeviceEncryptionInfo(
                        sender = bobMembership.userId,
                        senderDevice = bobMembership.deviceId,
                        senderCurve25519KeyBase64 = "curve-key",
                        senderVerified = true
                    )
                )
            )
        }
        val session = matrixRtcSession(
            membershipClient = membershipClient,
            toDeviceClient = toDeviceClient,
            ownMembershipIdentity = ownMembership.identity,
            keyBox = keyBox
        )

        session.join()

        assertEquals(true, emittedBeforePublishReturned.value)
        assertEquals(MatrixRtcCallEncryptionKeysContent.EVENT_TYPE, toDeviceClient.listenerEventType)
        assertTrue(
            keyBox.events.any { event ->
                event.key.membership == bobMembership.identity &&
                    event.key.keyBase64Encoded == "bob-key" &&
                    event.key.keyIndex == 4 &&
                    event.key.rtcBackendIdentity == bobMembership.rtcBackendIdentity
            }
        )
    }

    @Test
    fun refreshOwnMembershipExpiryExtendsExpiresWithoutChangingCreatedTimestamp() = runBlocking {
        val membershipClient = FakeSessionMembershipClient()
        val toDeviceClient = SessionFakeCustomToDeviceClient()
        val ownMembership = sessionLegacyMembership(
            eventId = "\$own",
            sender = "@alice:example.org",
            deviceId = "ALICEDEVICE",
            createdTimestamp = 10_000,
            expires = 10_000
        )
        val refreshedMembership = sessionLegacyMembership(
            eventId = "\$own-refresh",
            sender = "@alice:example.org",
            deviceId = "ALICEDEVICE",
            createdTimestamp = 10_000,
            expires = 20_000
        )
        membershipClient.publishResults = mutableListOf(ownMembership, refreshedMembership)
        membershipClient.activeMembershipResponses = mutableListOf(emptyList())
        val session = matrixRtcSession(
            membershipClient = membershipClient,
            toDeviceClient = toDeviceClient,
            expires = 10_000
        )

        session.join()
        val refreshed = session.refreshOwnMembershipExpiry()

        assertEquals(refreshedMembership, refreshed)
        assertEquals(refreshedMembership, session.ownMembership)
        assertEquals(listOf(refreshedMembership.identity), session.memberships.map { it.identity })
        assertEquals(2, membershipClient.publishCount)
        assertEquals(listOf(10_000L, 10_000L), membershipClient.publishedCreatedTimestampHistory)
        assertEquals(listOf(10_000L, 20_000L), membershipClient.publishedExpiresHistory)
    }

    @Test
    fun leavesSessionAndClearsLocalState() = runBlocking {
        val membershipClient = FakeSessionMembershipClient()
        val toDeviceClient = SessionFakeCustomToDeviceClient()
        val ownMembership = sessionLegacyMembership("\$own", "@alice:example.org", "ALICEDEVICE", 10_000)
        membershipClient.publishResult = ownMembership
        membershipClient.activeMembershipResponses = mutableListOf(emptyList())
        val session = matrixRtcSession(
            membershipClient = membershipClient,
            toDeviceClient = toDeviceClient
        )

        session.join()
        val leaveEventId = session.leave()

        assertEquals("\$leave", leaveEventId)
        assertEquals(1, membershipClient.leaveCount)
        assertEquals(MatrixRtcSessionState.LEFT, session.state)
        assertNull(session.ownMembership)
        assertTrue(session.memberships.isEmpty())
        assertTrue(session.encryptionKeys().isEmpty())
    }

    @Test
    fun leaveSendsScheduledDelayedLeaveWhenAvailable() = runBlocking {
        val membershipClient = FakeSessionMembershipClient()
        val toDeviceClient = SessionFakeCustomToDeviceClient()
        val ownMembership = sessionLegacyMembership("\$own", "@alice:example.org", "ALICEDEVICE", 10_000)
        membershipClient.publishResult = ownMembership
        membershipClient.activeMembershipResponses = mutableListOf(emptyList())
        membershipClient.delayedLeaveEventId = "delay-1"
        val session = matrixRtcSession(
            membershipClient = membershipClient,
            toDeviceClient = toDeviceClient
        )

        session.join()
        val leaveEventId = session.leave()

        assertNull(leaveEventId)
        assertEquals(listOf("delay-1"), membershipClient.sentDelayedEventIds)
        assertEquals(0, membershipClient.leaveCount)
        assertEquals(MatrixRtcSessionState.LEFT, session.state)
    }

    @Test
    fun leaveFallsBackToImmediateLeaveWhenScheduledDelayedLeaveFails() = runBlocking {
        val membershipClient = FakeSessionMembershipClient()
        val toDeviceClient = SessionFakeCustomToDeviceClient()
        val ownMembership = sessionLegacyMembership("\$own", "@alice:example.org", "ALICEDEVICE", 10_000)
        membershipClient.publishResult = ownMembership
        membershipClient.activeMembershipResponses = mutableListOf(emptyList())
        membershipClient.delayedLeaveEventId = "delay-1"
        membershipClient.sendDelayedEventError = MatrixRtcSessionDelayedEventException.NotFound
        val session = matrixRtcSession(
            membershipClient = membershipClient,
            toDeviceClient = toDeviceClient
        )

        session.join()
        val leaveEventId = session.leave()

        assertEquals("\$leave", leaveEventId)
        assertEquals(listOf("delay-1"), membershipClient.sentDelayedEventIds)
        assertEquals(1, membershipClient.leaveCount)
    }

    @Test
    fun reemitsKnownSessionEncryptionKeys() = runBlocking {
        val membershipClient = FakeSessionMembershipClient()
        val toDeviceClient = SessionFakeCustomToDeviceClient()
        val ownMembership = sessionLegacyMembership("\$own", "@alice:example.org", "ALICEDEVICE", 10_000)
        membershipClient.publishResult = ownMembership
        membershipClient.activeMembershipResponses = mutableListOf(emptyList())
        val keyBox = SessionMediaKeyEventBox()
        val session = matrixRtcSession(
            membershipClient = membershipClient,
            toDeviceClient = toDeviceClient,
            keyBox = keyBox
        )

        session.join()
        session.reemitEncryptionKeys()

        assertEquals(listOf("own-key", "own-key"), keyBox.events.map { it.key.keyBase64Encoded })
    }

    private fun matrixRtcSession(
        membershipClient: FakeSessionMembershipClient,
        toDeviceClient: SessionFakeCustomToDeviceClient,
        fociPreferred: List<MatrixRtcTransport> = emptyList(),
        callIntent: String? = null,
        mediaEncryptionMode: MatrixRtcSessionMediaEncryptionMode =
            MatrixRtcSessionMediaEncryptionMode.PER_PARTICIPANT_KEYS,
        ownMembershipIdentity: MatrixRtcMembershipIdentity? = null,
        expires: Long = 0,
        keyBox: SessionMediaKeyEventBox = SessionMediaKeyEventBox(),
        keyTransportFactoryCalled: SessionFlagBox = SessionFlagBox()
    ): MatrixRtcSession {
        return MatrixRtcSession(
            configuration = MatrixRtcSessionConfiguration(
                ownMembershipIdentity = ownMembershipIdentity,
                fociPreferred = fociPreferred,
                expires = expires,
                callIntent = callIntent,
                mediaEncryptionMode = mediaEncryptionMode,
                mediaKeyRotationConfiguration = MatrixRtcMediaKeyRotationConfiguration(
                    useKeyDelayMillis = 0,
                    keyRotationGracePeriodMillis = 10_000
                )
            ),
            membershipClient = membershipClient,
            keyTransportFactory = { identity ->
                keyTransportFactoryCalled.value = true
                MatrixRtcToDeviceKeyTransport(
                    roomId = "!room:example.org",
                    ownIdentity = identity,
                    client = toDeviceClient
                )
            },
            keyGenerator = StaticSessionMediaKeyGenerator("own-key"),
            timestampProvider = { 10_000 },
            onKeyChanged = { event -> keyBox.events += event }
        )
    }

    private fun sessionLegacyMembership(
        eventId: String,
        sender: String,
        deviceId: String,
        createdTimestamp: Long,
        expires: Long = MatrixRtcCallMembership.DEFAULT_EXPIRE_DURATION_MILLIS
    ): MatrixRtcCallMembership {
        val identity = MatrixRtcMembershipIdentity(
            userId = sender,
            deviceId = deviceId,
            memberId = "$sender:$deviceId"
        )
        return MatrixRtcCallMembership(
            kind = MatrixRtcCallMembership.Kind.LEGACY_STATE,
            eventId = eventId,
            eventType = MatrixRtcRawMembershipEvent.LEGACY_CALL_MEMBER_EVENT_TYPE,
            stateKey = "_${sender}_${deviceId}_m.call",
            sender = sender,
            identity = identity,
            slot = MatrixRtcSlotDescription.MATRIX_CALL_ROOM,
            createdTimestamp = createdTimestamp,
            absoluteExpiryTimestamp = createdTimestamp + expires,
            rtcBackendIdentity = identity.legacyRtcBackendIdentity,
            transports = emptyList(),
            focusSelection = MatrixRtcLegacyCallMembershipFocusSelection.OLDEST_MEMBERSHIP.wireValue,
            callIntent = null
        )
    }
}

private class FakeSessionMembershipClient : MatrixRtcSessionMembershipClient {
    var publishResult: MatrixRtcCallMembership? = null
    var publishResults: MutableList<MatrixRtcCallMembership> = mutableListOf()
    var activeMembershipResponses: MutableList<List<MatrixRtcCallMembership>> = mutableListOf()
    var leaveEventId = "\$leave"
    var delayedLeaveEventId: String? = null
    var sendDelayedEventError: Throwable? = null
    var publishHook: (() -> Unit)? = null

    var publishCount = 0
    var loadCount = 0
    var leaveCount = 0

    var publishedFociPreferred: List<MatrixRtcTransport>? = null
    var publishedCreatedTimestamp: Long? = null
    var publishedCreatedTimestampHistory: List<Long?> = emptyList()
    var publishedExpiresHistory: List<Long> = emptyList()
    var publishedCallIntent: String? = null
    var scheduledDelayedLeaveSlots: List<MatrixRtcSlotDescription> = emptyList()
    var scheduledDelayedLeaveRoomVersions: List<String?> = emptyList()
    var scheduledDelayedLeaveDelays: List<ULong> = emptyList()
    var restartedDelayedEventIds: List<String> = emptyList()
    var sentDelayedEventIds: List<String> = emptyList()
    var canceledDelayedEventIds: List<String> = emptyList()

    override suspend fun publishOwnLegacyMembership(
        slot: MatrixRtcSlotDescription,
        roomVersion: String?,
        focusSelection: MatrixRtcLegacyCallMembershipFocusSelection,
        fociPreferred: List<MatrixRtcTransport>,
        createdTimestamp: Long?,
        expires: Long,
        callIntent: String?
    ): MatrixRtcCallMembership {
        publishCount += 1
        publishedFociPreferred = fociPreferred
        publishedCreatedTimestamp = createdTimestamp
        publishedCreatedTimestampHistory = publishedCreatedTimestampHistory + createdTimestamp
        publishedExpiresHistory = publishedExpiresHistory + expires
        publishedCallIntent = callIntent
        publishHook?.invoke()
        if (publishResults.isNotEmpty()) {
            return publishResults.removeFirst()
        }
        return requireNotNull(publishResult)
    }

    override suspend fun loadActiveMemberships(
        slot: MatrixRtcSlotDescription,
        joinedUserIds: Set<String>?,
        now: Long
    ): List<MatrixRtcCallMembership> {
        loadCount += 1
        if (activeMembershipResponses.isEmpty()) {
            return emptyList()
        }
        if (activeMembershipResponses.size == 1) {
            return activeMembershipResponses.first()
        }
        return activeMembershipResponses.removeFirst()
    }

    override suspend fun leaveOwnLegacyMembership(
        slot: MatrixRtcSlotDescription,
        roomVersion: String?
    ): String {
        leaveCount += 1
        return leaveEventId
    }

    override suspend fun scheduleDelayedLeaveOwnLegacyMembership(
        slot: MatrixRtcSlotDescription,
        roomVersion: String?,
        delayMillis: ULong
    ): String {
        scheduledDelayedLeaveSlots = scheduledDelayedLeaveSlots + slot
        scheduledDelayedLeaveRoomVersions = scheduledDelayedLeaveRoomVersions + roomVersion
        scheduledDelayedLeaveDelays = scheduledDelayedLeaveDelays + delayMillis
        return delayedLeaveEventId ?: throw MatrixRtcSessionDelayedEventException.Unsupported
    }

    override suspend fun restartDelayedEvent(delayId: String) {
        restartedDelayedEventIds = restartedDelayedEventIds + delayId
    }

    override suspend fun sendDelayedEvent(delayId: String) {
        sentDelayedEventIds = sentDelayedEventIds + delayId
        sendDelayedEventError?.let { throw it }
    }

    override suspend fun cancelDelayedEvent(delayId: String) {
        canceledDelayedEventIds = canceledDelayedEventIds + delayId
    }
}

private data class StaticSessionMediaKeyGenerator(
    val key: String
) : MatrixRtcMediaKeyGenerating {
    override fun generateMediaKeyBase64Encoded(): String = key
}

private class SessionMediaKeyEventBox {
    var events: List<MatrixRtcMediaKeyChangedEvent> = emptyList()
}

private class SessionFlagBox {
    var value = false
}

private class SessionFakeCustomToDeviceClient : MatrixRtcCustomToDeviceEncrypting {
    var sentEventType: String? = null
    var sentTargets: List<MatrixRtcToDeviceTarget>? = null
    var sentContentJson: String? = null
    var sendCount = 0

    var listenerEventType: String? = null
    var listenerEncryptedOnly: Boolean? = null
    var listener: ((MatrixRtcCustomToDeviceEvent) -> Unit)? = null

    override suspend fun encryptAndSendRawToDevice(
        eventType: String,
        targets: List<MatrixRtcToDeviceTarget>,
        contentJson: String
    ): List<MatrixRtcCustomToDeviceSendFailure> {
        sendCount += 1
        sentEventType = eventType
        sentTargets = targets
        sentContentJson = contentJson
        return emptyList()
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

