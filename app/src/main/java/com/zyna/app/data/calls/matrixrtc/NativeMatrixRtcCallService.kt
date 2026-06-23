package com.zyna.app.data.calls.matrixrtc

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class NativeMatrixRtcCallServiceState {
    IDLE,
    JOINING,
    CONNECTED,
    LEAVING
}

sealed class NativeMatrixRtcCallServiceException(message: String) : Exception(message) {
    data class AlreadyActive(val roomId: String) :
        NativeMatrixRtcCallServiceException("Native MatrixRTC call is already active in $roomId")

    data object MissingLiveKitTransport :
        NativeMatrixRtcCallServiceException("MatrixRTC LiveKit transport is missing")

    data object NoActiveCall :
        NativeMatrixRtcCallServiceException("No active native MatrixRTC call")
}

data class NativeMatrixRtcCallStartResult(
    val roomId: String,
    val ownMembership: MatrixRtcCallMembership,
    val memberships: List<MatrixRtcCallMembership>,
    val keyShareResult: MatrixRtcMediaKeyShareResult,
    val transport: MatrixRtcTransport,
    val transportSource: MatrixRtcLiveKitTransportDiscoverySource,
    val liveKitAlias: String,
    val liveKitIdentity: String,
    val mediaEncryptionEnabled: Boolean,
    val callNotification: MatrixRtcCallNotificationSendResult?
)

interface NativeMatrixRtcCallEnvironment {
    fun ownDevice(): MatrixRtcOwnDevice
    suspend fun isRoomEncrypted(roomId: String): Boolean
    fun liveKitFocusClient(): MatrixRtcLiveKitFocusClient
    fun liveKitRoomSession(
        mediaEncryptionMode: MatrixRtcLiveKitMediaEncryptionMode,
        onEvent: (MatrixRtcLiveKitRoomSessionEvent) -> Unit
    ): MatrixRtcLiveKitRoomSession
    fun sessionMembershipClient(roomId: String): MatrixRtcSessionMembershipClient
    fun toDeviceClient(): MatrixRtcCustomToDeviceEncrypting
    fun callNotificationClient(roomId: String): MatrixRtcCallNotificationClient
}

class NativeMatrixRtcCallService(
    private val environment: NativeMatrixRtcCallEnvironment,
    private val keyGenerator: MatrixRtcMediaKeyGenerating = MatrixRtcRandomMediaKeyGenerator(),
    private val timestampProvider: () -> Long = { System.currentTimeMillis() },
    private val onMediaKeyChanged: (MatrixRtcMediaKeyChangedEvent) -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val lock = Mutex()
    private val _state = MutableStateFlow(NativeMatrixRtcCallServiceState.IDLE)
    val state: StateFlow<NativeMatrixRtcCallServiceState> = _state.asStateFlow()

    private var activeCall: ActiveCall? = null
    private var activeAttemptId: String? = null
    private var activeRoomId: String? = null

    suspend fun startAudioCall(
        roomId: String,
        fallbackLiveKitServiceUrl: String? = null,
        waitForPickup: Boolean = false
    ): NativeMatrixRtcCallStartResult {
        val attemptId = beginJoining(roomId)
        var matrixRtcSession: MatrixRtcSession? = null
        var liveKitSession: MatrixRtcLiveKitRoomSession? = null
        try {
            val ownDevice = environment.ownDevice()
            val ownIdentity = ownDevice.legacyMembershipIdentity
            val focusClient = environment.liveKitFocusClient()
            val discoveredTransport = focusClient.discoverPreferredTransport(
                fallbackServiceUrl = fallbackLiveKitServiceUrl
            ) ?: throw NativeMatrixRtcCallServiceException.MissingLiveKitTransport
            val mediaEncryptionEnabled = environment.isRoomEncrypted(roomId)
            val liveKitMediaEncryptionMode = if (mediaEncryptionEnabled) {
                MatrixRtcLiveKitMediaEncryptionMode.PER_PARTICIPANT_KEYS
            } else {
                MatrixRtcLiveKitMediaEncryptionMode.UNENCRYPTED
            }
            val liveKit = environment.liveKitRoomSession(
                mediaEncryptionMode = liveKitMediaEncryptionMode,
                onEvent = { event -> handleLiveKitEvent(event, attemptId) }
            )
            liveKitSession = liveKit
            val sfuConfig = focusClient.sfuConfig(
                membership = ownIdentity,
                transport = discoveredTransport.transport,
                roomId = roomId,
                endpointVersion = MatrixRtcLiveKitJwtEndpointVersion.LEGACY
            )

            val toDeviceClient = environment.toDeviceClient()
            val session = MatrixRtcSession(
                configuration = MatrixRtcSessionConfiguration(
                    ownMembershipIdentity = ownIdentity,
                    focusSelection = MatrixRtcLegacyCallMembershipFocusSelection.OLDEST_MEMBERSHIP,
                    fociPreferred = listOf(discoveredTransport.transport),
                    callIntent = AUDIO_CALL_INTENT,
                    mediaEncryptionMode = if (mediaEncryptionEnabled) {
                        MatrixRtcSessionMediaEncryptionMode.PER_PARTICIPANT_KEYS
                    } else {
                        MatrixRtcSessionMediaEncryptionMode.UNENCRYPTED
                    }
                ),
                membershipClient = environment.sessionMembershipClient(roomId),
                keyTransportFactory = { identity ->
                    MatrixRtcToDeviceKeyTransport(
                        roomId = roomId,
                        ownIdentity = identity,
                        client = toDeviceClient
                    )
                },
                keyGenerator = keyGenerator,
                timestampProvider = timestampProvider,
                onKeyChanged = { event ->
                    if (mediaEncryptionEnabled) {
                        liveKit.keyChangedHandler(onError)(event)
                    }
                    onMediaKeyChanged(event)
                },
                onError = onError
            )
            matrixRtcSession = session

            val joinResult = session.join()
            val callNotification = sendCallNotificationIfNeeded(
                roomId = roomId,
                joinResult = joinResult,
                waitForPickup = waitForPickup
            )
            liveKit.connect(
                sfuConfig = sfuConfig,
                publishAudio = true
            )

            val call = ActiveCall(
                attemptId = attemptId,
                roomId = roomId,
                ownUserId = ownIdentity.userId,
                matrixRtcSession = session,
                liveKitSession = liveKit,
                discoveredTransport = discoveredTransport,
                sfuConfig = sfuConfig,
                mediaEncryptionEnabled = mediaEncryptionEnabled
            )
            if (!finishJoined(call)) {
                liveKit.close()
                session.leave()
                throw CancellationException("Stale MatrixRTC join attempt")
            }

            return NativeMatrixRtcCallStartResult(
                roomId = roomId,
                ownMembership = joinResult.ownMembership,
                memberships = joinResult.memberships,
                keyShareResult = joinResult.keyShareResult,
                transport = discoveredTransport.transport,
                transportSource = discoveredTransport.source,
                liveKitAlias = sfuConfig.liveKitAlias,
                liveKitIdentity = sfuConfig.liveKitIdentity,
                mediaEncryptionEnabled = mediaEncryptionEnabled,
                callNotification = callNotification
            )
        } catch (error: Throwable) {
            liveKitSession?.close()
            matrixRtcSession?.runCatchingLeave()
            finishFailed(attemptId)
            throw error
        }
    }

    suspend fun refreshActiveMemberships(): MatrixRtcSessionMembershipRefreshResult {
        return currentActiveCall()
            ?.matrixRtcSession
            ?.refreshMemberships()
            ?: throw NativeMatrixRtcCallServiceException.NoActiveCall
    }

    suspend fun reshareActiveMediaKey(): MatrixRtcSessionMembershipRefreshResult {
        return currentActiveCall()
            ?.matrixRtcSession
            ?.reshareCurrentMediaKey()
            ?: throw NativeMatrixRtcCallServiceException.NoActiveCall
    }

    suspend fun setMicrophoneEnabled(enabled: Boolean) {
        currentActiveCall()
            ?.liveKitSession
            ?.setMicrophoneEnabled(enabled)
            ?: throw NativeMatrixRtcCallServiceException.NoActiveCall
    }

    suspend fun leaveActiveCall(): Boolean {
        val call = beginLeaving() ?: return false
        try {
            call.liveKitSession.close()
            call.matrixRtcSession.leave()
            return true
        } finally {
            finishLeft(call.attemptId)
        }
    }

    fun currentRoomId(): String? = activeRoomId

    private suspend fun sendCallNotificationIfNeeded(
        roomId: String,
        joinResult: MatrixRtcSessionJoinResult,
        waitForPickup: Boolean
    ): MatrixRtcCallNotificationSendResult? {
        if (!shouldSendCallNotification(joinResult.ownMembership, joinResult.memberships)) {
            return null
        }

        return runCatching {
            environment.callNotificationClient(roomId).sendCallNotification(
                parentEventId = joinResult.ownMembership.eventId,
                slot = joinResult.ownMembership.slot,
                notificationType = if (waitForPickup) {
                    MatrixRtcCallNotificationType.RING
                } else {
                    MatrixRtcCallNotificationType.NOTIFICATION
                },
                callIntent = AUDIO_CALL_INTENT
            )
        }.onFailure(onError).getOrNull()
    }

    private fun handleLiveKitEvent(
        event: MatrixRtcLiveKitRoomSessionEvent,
        attemptId: String
    ) {
        when (event) {
            is MatrixRtcLiveKitRoomSessionEvent.Disconnected ->
                scheduleEndActiveCall(attemptId)
            is MatrixRtcLiveKitRoomSessionEvent.FailedToConnect ->
                scheduleEndActiveCall(attemptId)
            else -> Unit
        }

        if (mediaKeyReshareReason(event) != null) {
            scheduleActiveMediaKeyReshare(attemptId)
        }
        if (membershipRefreshReason(event) != null) {
            scheduleActiveMembershipRefresh(attemptId)
        }
    }

    private fun scheduleEndActiveCall(attemptId: String) {
        coroutineScope.launch {
            endActiveCall(attemptId)
        }
    }

    private suspend fun endActiveCall(attemptId: String): Boolean {
        val call = beginLeaving(attemptId = attemptId) ?: return false
        try {
            call.liveKitSession.close()
            call.matrixRtcSession.leave()
            return true
        } catch (error: Throwable) {
            onError(error)
            return false
        } finally {
            finishLeft(call.attemptId)
        }
    }

    private fun scheduleActiveMembershipRefresh(attemptId: String) {
        coroutineScope.launch {
            val call = currentActiveCallByAttempt(attemptId) ?: return@launch
            runCatching {
                call.matrixRtcSession.refreshMemberships()
            }.onFailure(onError)
        }
    }

    private fun scheduleActiveMediaKeyReshare(attemptId: String) {
        coroutineScope.launch {
            val call = currentActiveCallByAttempt(attemptId) ?: return@launch
            if (!call.mediaEncryptionEnabled) {
                return@launch
            }
            runCatching {
                call.matrixRtcSession.reshareCurrentMediaKey()
            }.onFailure(onError)
        }
    }

    private suspend fun beginJoining(roomId: String): String {
        return lock.withLock {
            activeCall?.let { call ->
                throw NativeMatrixRtcCallServiceException.AlreadyActive(call.roomId)
            }
            activeAttemptId?.let {
                throw NativeMatrixRtcCallServiceException.AlreadyActive(activeRoomId ?: roomId)
            }

            val attemptId = UUID.randomUUID().toString()
            activeAttemptId = attemptId
            activeRoomId = roomId
            _state.value = NativeMatrixRtcCallServiceState.JOINING
            attemptId
        }
    }

    private suspend fun finishJoined(call: ActiveCall): Boolean {
        return lock.withLock {
            if (
                activeAttemptId != call.attemptId ||
                _state.value != NativeMatrixRtcCallServiceState.JOINING
            ) {
                return@withLock false
            }
            activeCall = call
            activeRoomId = call.roomId
            _state.value = NativeMatrixRtcCallServiceState.CONNECTED
            true
        }
    }

    private suspend fun finishFailed(attemptId: String) {
        lock.withLock {
            if (activeAttemptId != attemptId) {
                return@withLock
            }
            activeAttemptId = null
            activeRoomId = null
            activeCall = null
            _state.value = NativeMatrixRtcCallServiceState.IDLE
        }
    }

    private suspend fun beginLeaving(): ActiveCall? {
        return beginLeaving(attemptId = null)
    }

    private suspend fun beginLeaving(attemptId: String?): ActiveCall? {
        return lock.withLock {
            if (attemptId != null && activeAttemptId != attemptId) {
                return@withLock null
            }
            val call = activeCall ?: run {
                if (activeAttemptId == null) {
                    _state.value = NativeMatrixRtcCallServiceState.IDLE
                }
                return@withLock null
            }
            _state.value = NativeMatrixRtcCallServiceState.LEAVING
            call
        }
    }

    private suspend fun finishLeft(attemptId: String) {
        lock.withLock {
            if (activeAttemptId != attemptId) {
                return@withLock
            }
            activeAttemptId = null
            activeRoomId = null
            activeCall = null
            _state.value = NativeMatrixRtcCallServiceState.IDLE
        }
    }

    private suspend fun currentActiveCall(): ActiveCall? {
        return lock.withLock { activeCall }
    }

    private suspend fun currentActiveCallByAttempt(attemptId: String): ActiveCall? {
        return lock.withLock {
            activeCall?.takeIf { call -> call.attemptId == attemptId }
        }
    }

    private suspend fun MatrixRtcSession.runCatchingLeave() {
        runCatching { leave() }
    }

    private data class ActiveCall(
        val attemptId: String,
        val roomId: String,
        val ownUserId: String,
        val matrixRtcSession: MatrixRtcSession,
        val liveKitSession: MatrixRtcLiveKitRoomSession,
        val discoveredTransport: MatrixRtcLiveKitDiscoveredTransport,
        val sfuConfig: MatrixRtcLiveKitSfuConfig,
        val mediaEncryptionEnabled: Boolean
    )

    companion object {
        const val AUDIO_CALL_INTENT = "audio"

        fun shouldSendCallNotification(
            ownMembership: MatrixRtcCallMembership,
            memberships: List<MatrixRtcCallMembership>
        ): Boolean {
            return memberships.none { membership ->
                membership.userId != ownMembership.userId ||
                    membership.deviceId != ownMembership.deviceId ||
                    membership.memberId != ownMembership.memberId
            }
        }

        fun membershipRefreshReason(event: MatrixRtcLiveKitRoomSessionEvent): String? {
            return when (event) {
                is MatrixRtcLiveKitRoomSessionEvent.RemoteParticipantJoined ->
                    "remoteParticipantJoined"
                is MatrixRtcLiveKitRoomSessionEvent.RemoteTrackPublished ->
                    "remoteTrackPublished"
                is MatrixRtcLiveKitRoomSessionEvent.RemoteTrackSubscribed ->
                    "remoteTrackSubscribed"
                else -> null
            }
        }

        fun mediaKeyReshareReason(event: MatrixRtcLiveKitRoomSessionEvent): String? {
            return when (event) {
                is MatrixRtcLiveKitRoomSessionEvent.LocalTrackSubscribedByRemote ->
                    "localTrackSubscribedByRemote"
                else -> null
            }
        }
    }
}
