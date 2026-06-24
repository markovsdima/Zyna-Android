package com.zyna.app.data.calls.matrixrtc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeMatrixRtcCallServiceTest {
    @Test
    fun startAudioCallBootstrapsSessionAndSendsNotificationForEmptyCall() = runBlocking {
        val environment = FakeNativeMatrixRtcCallEnvironment()
        val membershipClient = environment.membershipClientFor(ROOM_ID)
        membershipClient.publishResult = nativeMembership(
            eventId = "\$own",
            sender = "@alice:example.org",
            deviceId = "ALICEDEVICE",
            createdTimestamp = 10_000
        )
        membershipClient.activeMembershipResponses = mutableListOf(emptyList())
        val service = nativeService(environment)

        val result = service.startAudioCall(
            roomId = ROOM_ID,
            fallbackLiveKitServiceUrl = "https://fallback.livekit.example.org",
            waitForPickup = false
        )

        assertEquals(NativeMatrixRtcCallServiceState.CONNECTED, service.state.value)
        assertEquals(ROOM_ID, service.currentRoomId())
        assertEquals(MatrixRtcLiveKitTransportDiscoverySource.BACKEND, result.transportSource)
        assertEquals("lk-room", result.liveKitAlias)
        assertEquals("lk-identity", result.liveKitIdentity)
        assertEquals(true, result.mediaEncryptionEnabled)
        assertEquals(listOf(membershipClient.publishResult!!.identity), result.memberships.map { it.identity })
        assertTrue(result.keyShareResult.sharedWith.isEmpty())

        assertEquals(listOf("https://fallback.livekit.example.org"), environment.focusClient.discoverFallbacks)
        assertEquals(
            listOf(
                FakeSfuRequest(
                    membership = membershipClient.publishResult!!.identity,
                    transport = MatrixRtcTransport.liveKit("https://livekit.example.org"),
                    roomId = ROOM_ID,
                    endpointVersion = MatrixRtcLiveKitJwtEndpointVersion.LEGACY
                )
            ),
            environment.focusClient.sfuRequests
        )
        assertEquals(listOf(MatrixRtcTransport.liveKit("https://livekit.example.org")), membershipClient.publishedFociPreferred)
        assertEquals("audio", membershipClient.publishedCallIntent)
        assertEquals(MatrixRtcCallEncryptionKeysContent.EVENT_TYPE, environment.toDeviceClient.listenerEventType)
        assertEquals(MatrixRtcLiveKitMediaEncryptionMode.PER_PARTICIPANT_KEYS, environment.liveKitSessions.single().mediaEncryptionMode)
        assertEquals(listOf("wss://livekit.example.org"), environment.liveKitSessions.single().controller.connectedUrls)
        assertEquals(listOf("jwt"), environment.liveKitSessions.single().controller.connectedTokens)
        assertEquals(listOf(true), environment.liveKitSessions.single().controller.microphoneHistory)
        assertEquals(listOf("own-key"), environment.liveKitSessions.single().keyApplier.appliedKeys.map { it.keyBase64Encoded })

        val notificationClient = environment.notificationClientFor(ROOM_ID)
        assertEquals(1, notificationClient.requests.size)
        assertEquals("\$own", notificationClient.requests.single().parentEventId)
        assertEquals(MatrixRtcCallNotificationType.NOTIFICATION, notificationClient.requests.single().notificationType)
        assertEquals("audio", notificationClient.requests.single().callIntent)
        assertEquals(notificationClient.result, result.callNotification)
        assertEquals(1, notificationClient.closeCount)
        assertEquals(0, membershipClient.closeCount)

        assertEquals(true, service.leaveActiveCall())
        assertEquals(1, environment.liveKitSessions.single().controller.closeCount)
        assertEquals(1, membershipClient.closeCount)
    }

    @Test
    fun startAudioCallSkipsNotificationWhenRemoteMembershipAlreadyExists() = runBlocking {
        val environment = FakeNativeMatrixRtcCallEnvironment()
        val membershipClient = environment.membershipClientFor(ROOM_ID)
        val ownMembership = nativeMembership(
            eventId = "\$own",
            sender = "@alice:example.org",
            deviceId = "ALICEDEVICE",
            createdTimestamp = 10_000
        )
        val bobMembership = nativeMembership(
            eventId = "\$bob",
            sender = "@bob:example.org",
            deviceId = "BOBDEVICE",
            createdTimestamp = 20_000
        )
        membershipClient.publishResult = ownMembership
        membershipClient.activeMembershipResponses = mutableListOf(listOf(bobMembership))
        val service = nativeService(environment)

        val result = service.startAudioCall(roomId = ROOM_ID)

        assertNull(result.callNotification)
        assertEquals(emptyList<FakeCallNotificationRequest>(), environment.notificationClientFor(ROOM_ID).requests)
        assertEquals(
            listOf(ownMembership.identity, bobMembership.identity),
            result.memberships.map { it.identity }
        )
        assertEquals(listOf(MatrixRtcToDeviceTarget("@bob:example.org", "BOBDEVICE")), result.keyShareResult.sharedWith)
        assertEquals(listOf(MatrixRtcToDeviceTarget("@bob:example.org", "BOBDEVICE")), environment.toDeviceClient.sentTargets)
        assertEquals(listOf("own-key"), environment.liveKitSessions.single().keyApplier.appliedKeys.map { it.keyBase64Encoded })

        assertEquals(true, service.leaveActiveCall())
        assertEquals(1, membershipClient.closeCount)
    }

    @Test
    fun startAudioCallAsyncBootstrapsSession() = runBlocking {
        val environment = FakeNativeMatrixRtcCallEnvironment()
        val membershipClient = environment.membershipClientFor(ROOM_ID)
        membershipClient.publishResult = nativeMembership(
            eventId = "\$own",
            sender = "@alice:example.org",
            deviceId = "ALICEDEVICE",
            createdTimestamp = 10_000
        )
        membershipClient.activeMembershipResponses = mutableListOf(emptyList())
        val service = nativeService(environment)

        service.startAudioCallAsync(roomId = ROOM_ID).join()

        assertEquals(NativeMatrixRtcCallServiceState.CONNECTED, service.state.value)
        assertEquals(ROOM_ID, service.currentRoomId())
        assertEquals(true, service.currentMicrophoneEnabled())
        assertEquals(listOf(true), environment.liveKitSessions.single().controller.microphoneHistory)

        assertEquals(true, service.leaveActiveCall())
        assertEquals(1, membershipClient.closeCount)
    }

    @Test
    fun startAudioCallAsyncStoresFailureAndReturnsIdle() = runBlocking {
        val environment = FakeNativeMatrixRtcCallEnvironment()
        environment.focusClient.discoveredTransport = null
        val service = nativeService(environment)
        var reportedFailure: Throwable? = null

        service.startAudioCallAsync(
            roomId = ROOM_ID,
            onFailure = { error -> reportedFailure = error }
        ).join()

        assertEquals(NativeMatrixRtcCallServiceException.MissingLiveKitTransport, reportedFailure)
        assertEquals(NativeMatrixRtcCallServiceException.MissingLiveKitTransport, service.currentFailure())
        assertEquals(NativeMatrixRtcCallServiceState.IDLE, service.state.value)
        assertNull(service.currentRoomId())
    }

    @Test
    fun loadRoomCallStatusReportsJoinableRemoteAudioMemberships() = runBlocking {
        val environment = FakeNativeMatrixRtcCallEnvironment()
        val membershipClient = environment.membershipClientFor(ROOM_ID)
        val ownMembership = nativeMembership(
            eventId = "\$own",
            sender = "@alice:example.org",
            deviceId = "ALICEDEVICE",
            createdTimestamp = 10_000,
            callIntent = "audio"
        )
        val bobMembership = nativeMembership(
            eventId = "\$bob",
            sender = "@bob:example.org",
            deviceId = "BOBDEVICE",
            createdTimestamp = 20_000,
            callIntent = "m.audio"
        )
        val carolVideoMembership = nativeMembership(
            eventId = "\$carol",
            sender = "@carol:example.org",
            deviceId = "CAROLDEVICE",
            createdTimestamp = 30_000,
            callIntent = "m.video"
        )
        membershipClient.activeMembershipResponses = mutableListOf(
            listOf(ownMembership, bobMembership, carolVideoMembership)
        )
        val service = nativeService(
            environment = environment,
            timestampProvider = { 40_000 }
        )

        val status = service.loadRoomCallStatus(ROOM_ID)

        assertEquals(ROOM_ID, status.roomId)
        assertEquals(true, status.hasJoinableCall)
        assertEquals(1, status.remoteMembershipCount)
        assertEquals(40_000, status.checkedAtMillis)
        assertEquals(1, membershipClient.loadCount)
        assertEquals(1, membershipClient.closeCount)
    }

    @Test
    fun loadRoomCallStatusIgnoresOwnAndNonAudioMemberships() = runBlocking {
        val environment = FakeNativeMatrixRtcCallEnvironment()
        val membershipClient = environment.membershipClientFor(ROOM_ID)
        val ownMembership = nativeMembership(
            eventId = "\$own",
            sender = "@alice:example.org",
            deviceId = "ALICEDEVICE",
            createdTimestamp = 10_000,
            memberId = "rtc-own-member-id"
        )
        val bobVideoMembership = nativeMembership(
            eventId = "\$bob",
            sender = "@bob:example.org",
            deviceId = "BOBDEVICE",
            createdTimestamp = 20_000,
            callIntent = "m.video"
        )
        membershipClient.activeMembershipResponses = mutableListOf(
            listOf(ownMembership, bobVideoMembership)
        )
        val service = nativeService(environment)

        val status = service.loadRoomCallStatus(ROOM_ID)

        assertEquals(false, status.hasJoinableCall)
        assertEquals(0, status.remoteMembershipCount)
        assertEquals(1, membershipClient.closeCount)
    }

    @Test
    fun refreshActiveMembershipsSharesCurrentKeyWithLateJoiner() = runBlocking {
        val environment = FakeNativeMatrixRtcCallEnvironment()
        val membershipClient = environment.membershipClientFor(ROOM_ID)
        val ownMembership = nativeMembership(
            eventId = "\$own",
            sender = "@alice:example.org",
            deviceId = "ALICEDEVICE",
            createdTimestamp = 10_000
        )
        val bobMembership = nativeMembership(
            eventId = "\$bob",
            sender = "@bob:example.org",
            deviceId = "BOBDEVICE",
            createdTimestamp = 40_000
        )
        var now = 10_000L
        membershipClient.publishResult = ownMembership
        membershipClient.activeMembershipResponses = mutableListOf(emptyList())
        val service = nativeService(
            environment = environment,
            keyGenerator = SequenceNativeMediaKeyGenerator(listOf("own-key-0", "own-key-1")),
            timestampProvider = { now }
        )

        service.startAudioCall(roomId = ROOM_ID)

        now = 40_000L
        membershipClient.activeMembershipResponses = mutableListOf(listOf(bobMembership))
        val refreshResult = service.refreshActiveMemberships()

        assertEquals(
            listOf(MatrixRtcToDeviceTarget("@bob:example.org", "BOBDEVICE")),
            refreshResult.keyShareResult.sharedWith
        )
        val sentContent = MatrixRtcCallEncryptionKeysContent.fromJson(
            environment.toDeviceClient.sentContents.single()
        )
        assertEquals(0, sentContent.keys.index)
        assertEquals("own-key-0", sentContent.keys.key)
        assertEquals(
            listOf(0),
            environment.liveKitSessions.single().keyApplier.appliedKeys.map { it.keyIndex }
        )
        assertEquals(
            listOf("own-key-0"),
            environment.liveKitSessions.single().keyApplier.appliedKeys.map { it.keyBase64Encoded }
        )

        assertEquals(true, service.leaveActiveCall())
    }

    @Test
    fun startAudioCallUsesUnencryptedSessionWhenRoomIsNotEncrypted() = runBlocking {
        val environment = FakeNativeMatrixRtcCallEnvironment()
        environment.encrypted = false
        val membershipClient = environment.membershipClientFor(ROOM_ID)
        val ownMembership = nativeMembership(
            eventId = "\$own",
            sender = "@alice:example.org",
            deviceId = "ALICEDEVICE",
            createdTimestamp = 10_000
        )
        val bobMembership = nativeMembership(
            eventId = "\$bob",
            sender = "@bob:example.org",
            deviceId = "BOBDEVICE",
            createdTimestamp = 20_000
        )
        membershipClient.publishResult = ownMembership
        membershipClient.activeMembershipResponses = mutableListOf(listOf(bobMembership))
        val service = nativeService(environment)

        val result = service.startAudioCall(roomId = ROOM_ID)

        assertEquals(false, result.mediaEncryptionEnabled)
        assertTrue(result.keyShareResult.sharedWith.isEmpty())
        assertNull(environment.toDeviceClient.listenerEventType)
        assertEquals(0, environment.toDeviceClient.sendCount)
        assertEquals(MatrixRtcLiveKitMediaEncryptionMode.UNENCRYPTED, environment.liveKitSessions.single().mediaEncryptionMode)
        assertTrue(environment.liveKitSessions.single().keyApplier.appliedKeys.isEmpty())

        assertEquals(true, service.leaveActiveCall())
    }

    @Test
    fun startAudioCallFailsAndReturnsIdleWhenLiveKitTransportIsMissing() = runBlocking {
        val environment = FakeNativeMatrixRtcCallEnvironment()
        environment.focusClient.discoveredTransport = null
        val service = nativeService(environment)

        val failure = runCatching {
            service.startAudioCall(roomId = ROOM_ID)
        }.exceptionOrNull()

        assertEquals(NativeMatrixRtcCallServiceException.MissingLiveKitTransport, failure)
        assertEquals(NativeMatrixRtcCallServiceState.IDLE, service.state.value)
        assertNull(service.currentRoomId())
        assertEquals(0, environment.membershipClientFor(ROOM_ID).publishCount)
        assertEquals(emptyList<FakeCallNotificationRequest>(), environment.notificationClientFor(ROOM_ID).requests)
    }

    @Test
    fun leaveActiveCallLeavesSessionAndReturnsIdle() = runBlocking {
        val environment = FakeNativeMatrixRtcCallEnvironment()
        val membershipClient = environment.membershipClientFor(ROOM_ID)
        membershipClient.publishResult = nativeMembership(
            eventId = "\$own",
            sender = "@alice:example.org",
            deviceId = "ALICEDEVICE",
            createdTimestamp = 10_000
        )
        membershipClient.activeMembershipResponses = mutableListOf(emptyList())
        val service = nativeService(environment)

        service.startAudioCall(roomId = ROOM_ID)
        service.setMicrophoneEnabledAsync(false).join()
        assertEquals(false, service.currentMicrophoneEnabled())
        val leftJob = service.leaveActiveCallAsync()
        leftJob.join()

        assertEquals(1, membershipClient.leaveCount)
        assertEquals(listOf(true, false), environment.liveKitSessions.single().controller.microphoneHistory)
        assertEquals(1, environment.liveKitSessions.single().controller.closeCount)
        assertEquals(NativeMatrixRtcCallServiceState.IDLE, service.state.value)
        assertNull(service.currentRoomId())
        assertEquals(true, service.currentMicrophoneEnabled())
        assertEquals(false, service.leaveActiveCall())
    }

    @Test
    fun leaveActiveCallCancelsJoiningAttemptAndReturnsIdle() = runBlocking {
        val environment = FakeNativeMatrixRtcCallEnvironment()
        val membershipClient = environment.membershipClientFor(ROOM_ID)
        membershipClient.publishResult = nativeMembership(
            eventId = "\$own",
            sender = "@alice:example.org",
            deviceId = "ALICEDEVICE",
            createdTimestamp = 10_000
        )
        membershipClient.activeMembershipResponses = mutableListOf(emptyList())
        val sfuConfigStarted = CompletableDeferred<Unit>()
        val continueSfuConfig = CompletableDeferred<Unit>()
        environment.focusClient.sfuConfigStarted = sfuConfigStarted
        environment.focusClient.continueSfuConfig = continueSfuConfig
        val service = nativeService(environment)

        var startFailure: Throwable? = null
        val startJob = launch {
            startFailure = runCatching {
                service.startAudioCall(roomId = ROOM_ID)
            }.exceptionOrNull()
        }
        sfuConfigStarted.await()

        assertEquals(NativeMatrixRtcCallServiceState.JOINING, service.state.value)
        assertEquals(ROOM_ID, service.currentRoomId())
        assertEquals(true, service.leaveActiveCall())
        assertEquals(NativeMatrixRtcCallServiceState.IDLE, service.state.value)
        assertNull(service.currentRoomId())
        assertEquals(1, environment.liveKitSessions.single().controller.closeCount)

        continueSfuConfig.complete(Unit)
        startJob.join()

        assertTrue(startFailure is CancellationException)
        assertEquals(NativeMatrixRtcCallServiceState.IDLE, service.state.value)
        assertNull(service.currentRoomId())
        assertEquals(0, membershipClient.publishCount)
        assertEquals(emptyList<FakeCallNotificationRequest>(), environment.notificationClientFor(ROOM_ID).requests)
        assertEquals(false, service.leaveActiveCall())
    }

    @Test
    fun startAudioCallRejectsSecondActiveCall() = runBlocking {
        val environment = FakeNativeMatrixRtcCallEnvironment()
        val membershipClient = environment.membershipClientFor(ROOM_ID)
        membershipClient.publishResult = nativeMembership(
            eventId = "\$own",
            sender = "@alice:example.org",
            deviceId = "ALICEDEVICE",
            createdTimestamp = 10_000
        )
        membershipClient.activeMembershipResponses = mutableListOf(emptyList())
        val service = nativeService(environment)
        service.startAudioCall(roomId = ROOM_ID)

        val failure = runCatching {
            service.startAudioCall(roomId = "!other:example.org")
        }.exceptionOrNull()

        assertTrue(failure is NativeMatrixRtcCallServiceException.AlreadyActive)
        assertEquals(ROOM_ID, (failure as NativeMatrixRtcCallServiceException.AlreadyActive).roomId)

        assertEquals(true, service.leaveActiveCall())
    }

    private fun nativeService(
        environment: FakeNativeMatrixRtcCallEnvironment,
        keyGenerator: MatrixRtcMediaKeyGenerating = StaticNativeMediaKeyGenerator("own-key"),
        timestampProvider: () -> Long = { 10_000 }
    ): NativeMatrixRtcCallService {
        return NativeMatrixRtcCallService(
            environment = environment,
            keyGenerator = keyGenerator,
            timestampProvider = timestampProvider
        )
    }

    private fun nativeMembership(
        eventId: String,
        sender: String,
        deviceId: String,
        createdTimestamp: Long,
        expires: Long = MatrixRtcCallMembership.DEFAULT_EXPIRE_DURATION_MILLIS,
        memberId: String = "$sender:$deviceId",
        callIntent: String? = null
    ): MatrixRtcCallMembership {
        val identity = MatrixRtcMembershipIdentity(
            userId = sender,
            deviceId = deviceId,
            memberId = memberId
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
            callIntent = callIntent
        )
    }

    private companion object {
        const val ROOM_ID = "!room:example.org"
    }
}

private class FakeNativeMatrixRtcCallEnvironment : NativeMatrixRtcCallEnvironment {
    var device = MatrixRtcOwnDevice(
        userId = "@alice:example.org",
        deviceId = "ALICEDEVICE"
    )
    var encrypted = true
    val focusClient = FakeMatrixRtcLiveKitFocusClient()
    val toDeviceClient = FakeNativeToDeviceClient()
    val liveKitSessions = mutableListOf<FakeNativeLiveKitSessionRecord>()

    private val membershipClients = mutableMapOf<String, FakeNativeSessionMembershipClient>()
    private val notificationClients = mutableMapOf<String, FakeNativeCallNotificationClient>()

    override fun ownDevice(): MatrixRtcOwnDevice = device

    override suspend fun isRoomEncrypted(roomId: String): Boolean = encrypted

    override fun liveKitFocusClient(): MatrixRtcLiveKitFocusClient = focusClient

    override fun liveKitRoomSession(
        mediaEncryptionMode: MatrixRtcLiveKitMediaEncryptionMode,
        onEvent: (MatrixRtcLiveKitRoomSessionEvent) -> Unit
    ): MatrixRtcLiveKitRoomSession {
        val controller = FakeNativeLiveKitRoomController()
        val keyApplier = FakeNativeLiveKitKeyApplier()
        liveKitSessions += FakeNativeLiveKitSessionRecord(
            mediaEncryptionMode = mediaEncryptionMode,
            controller = controller,
            keyApplier = keyApplier
        )
        return MatrixRtcLiveKitRoomSession(
            controller = controller,
            keyApplier = when (mediaEncryptionMode) {
                MatrixRtcLiveKitMediaEncryptionMode.PER_PARTICIPANT_KEYS -> keyApplier
                MatrixRtcLiveKitMediaEncryptionMode.UNENCRYPTED -> null
            },
            onEvent = onEvent
        )
    }

    override fun sessionMembershipClient(roomId: String): MatrixRtcSessionMembershipClient {
        return membershipClientFor(roomId)
    }

    override fun toDeviceClient(): MatrixRtcCustomToDeviceEncrypting = toDeviceClient

    override fun callNotificationClient(roomId: String): MatrixRtcCallNotificationClient {
        return notificationClientFor(roomId)
    }

    fun membershipClientFor(roomId: String): FakeNativeSessionMembershipClient {
        return membershipClients.getOrPut(roomId) { FakeNativeSessionMembershipClient() }
    }

    fun notificationClientFor(roomId: String): FakeNativeCallNotificationClient {
        return notificationClients.getOrPut(roomId) { FakeNativeCallNotificationClient() }
    }
}

private class FakeMatrixRtcLiveKitFocusClient : MatrixRtcLiveKitFocusClient {
    var discoveredTransport: MatrixRtcLiveKitDiscoveredTransport? = MatrixRtcLiveKitDiscoveredTransport(
        transport = MatrixRtcTransport.liveKit("https://livekit.example.org"),
        source = MatrixRtcLiveKitTransportDiscoverySource.BACKEND
    )
    var sfuConfigResult = MatrixRtcLiveKitSfuConfig(
        url = "wss://livekit.example.org",
        jwt = "jwt",
        liveKitAlias = "lk-room",
        liveKitIdentity = "lk-identity"
    )
    var sfuConfigStarted: CompletableDeferred<Unit>? = null
    var continueSfuConfig: CompletableDeferred<Unit>? = null
    var discoverFallbacks: List<String?> = emptyList()
    var sfuRequests: List<FakeSfuRequest> = emptyList()

    override suspend fun discoverPreferredTransport(
        fallbackServiceUrl: String?
    ): MatrixRtcLiveKitDiscoveredTransport? {
        discoverFallbacks = discoverFallbacks + fallbackServiceUrl
        return discoveredTransport
    }

    override suspend fun requestOpenIdToken(): MatrixRtcLiveKitOpenIdToken {
        return MatrixRtcLiveKitOpenIdToken(
            accessToken = "openid-token",
            tokenType = "Bearer",
            matrixServerName = "example.org",
            expiresIn = 3_600UL
        )
    }

    override suspend fun sfuConfig(
        membership: MatrixRtcMembershipIdentity,
        transport: MatrixRtcTransport,
        roomId: String,
        endpointVersion: MatrixRtcLiveKitJwtEndpointVersion,
        delayDelegation: MatrixRtcLiveKitDelayDelegation?
    ): MatrixRtcLiveKitSfuConfig {
        sfuConfigStarted?.complete(Unit)
        continueSfuConfig?.await()
        sfuRequests = sfuRequests + FakeSfuRequest(
            membership = membership,
            transport = transport,
            roomId = roomId,
            endpointVersion = endpointVersion
        )
        return sfuConfigResult
    }

    override suspend fun discoverAndAuthenticate(
        membership: MatrixRtcMembershipIdentity,
        roomId: String,
        fallbackServiceUrl: String?,
        endpointVersion: MatrixRtcLiveKitJwtEndpointVersion,
        delayDelegation: MatrixRtcLiveKitDelayDelegation?
    ): MatrixRustSdkRtcLiveKitFocus? {
        val transport = discoverPreferredTransport(fallbackServiceUrl) ?: return null
        return MatrixRustSdkRtcLiveKitFocus(
            discoveredTransport = transport,
            sfuConfig = sfuConfig(
                membership = membership,
                transport = transport.transport,
                roomId = roomId,
                endpointVersion = endpointVersion,
                delayDelegation = delayDelegation
            )
        )
    }
}

private data class FakeSfuRequest(
    val membership: MatrixRtcMembershipIdentity,
    val transport: MatrixRtcTransport,
    val roomId: String,
    val endpointVersion: MatrixRtcLiveKitJwtEndpointVersion
)

private class FakeNativeSessionMembershipClient : MatrixRtcSessionMembershipClient {
    var publishResult: MatrixRtcCallMembership? = null
    var activeMembershipResponses: MutableList<List<MatrixRtcCallMembership>> = mutableListOf()
    var publishCount = 0
    var loadCount = 0
    var leaveCount = 0
    var closeCount = 0
    var publishedFociPreferred: List<MatrixRtcTransport>? = null
    var publishedCallIntent: String? = null

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
        publishedCallIntent = callIntent
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
        return "\$leave"
    }

    override fun close() {
        closeCount += 1
    }
}

private class FakeNativeToDeviceClient : MatrixRtcCustomToDeviceEncrypting {
    var sentTargets: List<MatrixRtcToDeviceTarget>? = null
    var sentContents: List<String> = emptyList()
    var sendCount = 0
    var listenerEventType: String? = null
    var listenerEncryptedOnly: Boolean? = null

    override suspend fun encryptAndSendRawToDevice(
        eventType: String,
        targets: List<MatrixRtcToDeviceTarget>,
        contentJson: String
    ): List<MatrixRtcCustomToDeviceSendFailure> {
        sendCount += 1
        sentTargets = targets
        sentContents = sentContents + contentJson
        return emptyList()
    }

    override fun addCustomToDeviceEventListener(
        eventType: String,
        encryptedOnly: Boolean,
        listener: (MatrixRtcCustomToDeviceEvent) -> Unit
    ): MatrixRtcCancellable {
        listenerEventType = eventType
        listenerEncryptedOnly = encryptedOnly
        return MatrixRtcNoopCancellable
    }
}

private data class FakeNativeLiveKitSessionRecord(
    val mediaEncryptionMode: MatrixRtcLiveKitMediaEncryptionMode,
    val controller: FakeNativeLiveKitRoomController,
    val keyApplier: FakeNativeLiveKitKeyApplier
)

private class FakeNativeLiveKitRoomController : MatrixRtcLiveKitRoomController {
    var connectedUrls: List<String> = emptyList()
    var connectedTokens: List<String> = emptyList()
    var microphoneHistory: List<Boolean> = emptyList()
    var cameraHistory: List<Boolean> = emptyList()
    var disconnectCount = 0
    var closeCount = 0
    private var eventHandler: ((MatrixRtcLiveKitRoomSessionEvent) -> Unit)? = null

    override fun startEventCollection(
        scope: kotlinx.coroutines.CoroutineScope,
        onEvent: (MatrixRtcLiveKitRoomSessionEvent) -> Unit
    ): MatrixRtcCancellable {
        eventHandler = onEvent
        return object : MatrixRtcCancellable {
            override fun cancel() {
                eventHandler = null
            }
        }
    }

    override suspend fun connect(url: String, token: String) {
        connectedUrls = connectedUrls + url
        connectedTokens = connectedTokens + token
    }

    override fun disconnect() {
        disconnectCount += 1
    }

    override suspend fun setMicrophoneEnabled(enabled: Boolean) {
        microphoneHistory = microphoneHistory + enabled
    }

    override suspend fun setCameraEnabled(enabled: Boolean) {
        cameraHistory = cameraHistory + enabled
    }

    override fun close() {
        closeCount += 1
    }

    fun emit(event: MatrixRtcLiveKitRoomSessionEvent) {
        eventHandler?.invoke(event)
    }
}

private class FakeNativeLiveKitKeyApplier : MatrixRtcMediaKeyApplier {
    var appliedKeys: List<MatrixRtcMediaKey> = emptyList()

    override fun applyMediaKey(key: MatrixRtcMediaKey) {
        appliedKeys = appliedKeys + key
    }
}

private class FakeNativeCallNotificationClient : MatrixRtcCallNotificationClient {
    val result = MatrixRtcCallNotificationSendResult(
        sentNotification = true,
        notificationEventId = "\$notification",
        sentLegacyFallback = true,
        legacyFallbackEventId = "\$legacy-notify",
        notificationType = MatrixRtcCallNotificationType.NOTIFICATION,
        senderTimestamp = 10_000,
        lifetimeMillis = 60_000
    )
    var requests: List<FakeCallNotificationRequest> = emptyList()
    var closeCount = 0

    override suspend fun sendCallNotification(
        parentEventId: String,
        slot: MatrixRtcSlotDescription,
        notificationType: MatrixRtcCallNotificationType,
        callIntent: String?
    ): MatrixRtcCallNotificationSendResult {
        requests = requests + FakeCallNotificationRequest(
            parentEventId = parentEventId,
            slot = slot,
            notificationType = notificationType,
            callIntent = callIntent
        )
        return result.copy(notificationType = notificationType)
    }

    override fun close() {
        closeCount += 1
    }
}

private data class FakeCallNotificationRequest(
    val parentEventId: String,
    val slot: MatrixRtcSlotDescription,
    val notificationType: MatrixRtcCallNotificationType,
    val callIntent: String?
)

private data class StaticNativeMediaKeyGenerator(
    val key: String
) : MatrixRtcMediaKeyGenerating {
    override fun generateMediaKeyBase64Encoded(): String = key
}

private class SequenceNativeMediaKeyGenerator(
    private val keys: List<String>
) : MatrixRtcMediaKeyGenerating {
    private var index = 0

    override fun generateMediaKeyBase64Encoded(): String {
        return keys[index].also {
            index += 1
        }
    }
}
