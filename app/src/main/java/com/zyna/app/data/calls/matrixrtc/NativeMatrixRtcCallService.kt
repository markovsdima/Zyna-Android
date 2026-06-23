package com.zyna.app.data.calls.matrixrtc

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    fun sessionMembershipClient(roomId: String): MatrixRtcSessionMembershipClient
    fun toDeviceClient(): MatrixRtcCustomToDeviceEncrypting
    fun callNotificationClient(roomId: String): MatrixRtcCallNotificationClient
}

class NativeMatrixRtcCallService(
    private val environment: NativeMatrixRtcCallEnvironment,
    private val keyGenerator: MatrixRtcMediaKeyGenerating = MatrixRtcRandomMediaKeyGenerator(),
    private val timestampProvider: () -> Long = { System.currentTimeMillis() },
    private val onMediaKeyChanged: (MatrixRtcMediaKeyChangedEvent) -> Unit = {},
    private val onError: (Throwable) -> Unit = {}
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
        try {
            val ownDevice = environment.ownDevice()
            val ownIdentity = ownDevice.legacyMembershipIdentity
            val focusClient = environment.liveKitFocusClient()
            val discoveredTransport = focusClient.discoverPreferredTransport(
                fallbackServiceUrl = fallbackLiveKitServiceUrl
            ) ?: throw NativeMatrixRtcCallServiceException.MissingLiveKitTransport
            val mediaEncryptionEnabled = environment.isRoomEncrypted(roomId)
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
                onKeyChanged = onMediaKeyChanged,
                onError = onError
            )
            matrixRtcSession = session

            val joinResult = session.join()
            val callNotification = sendCallNotificationIfNeeded(
                roomId = roomId,
                joinResult = joinResult,
                waitForPickup = waitForPickup
            )

            val call = ActiveCall(
                attemptId = attemptId,
                roomId = roomId,
                ownUserId = ownIdentity.userId,
                matrixRtcSession = session,
                discoveredTransport = discoveredTransport,
                sfuConfig = sfuConfig,
                mediaEncryptionEnabled = mediaEncryptionEnabled
            )
            if (!finishJoined(call)) {
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

    suspend fun leaveActiveCall(): Boolean {
        val call = beginLeaving() ?: return false
        try {
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
        return lock.withLock {
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

    private suspend fun MatrixRtcSession.runCatchingLeave() {
        runCatching { leave() }
    }

    private data class ActiveCall(
        val attemptId: String,
        val roomId: String,
        val ownUserId: String,
        val matrixRtcSession: MatrixRtcSession,
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
    }
}
