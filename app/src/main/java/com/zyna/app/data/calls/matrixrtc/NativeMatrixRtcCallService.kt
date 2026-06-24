package com.zyna.app.data.calls.matrixrtc

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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

sealed interface NativeMatrixRtcCallPickupState {
    data object Inactive : NativeMatrixRtcCallPickupState

    data class Ringing(
        val roomId: String,
        val notificationEventId: String,
        val expiresAtMillis: Long
    ) : NativeMatrixRtcCallPickupState

    data class Answered(val roomId: String) : NativeMatrixRtcCallPickupState

    data class TimedOut(val roomId: String) : NativeMatrixRtcCallPickupState
}

sealed class NativeMatrixRtcCallServiceException(message: String) : Exception(message) {
    data class AlreadyActive(val roomId: String) :
        NativeMatrixRtcCallServiceException("Native MatrixRTC call is already active in $roomId")

    data object MissingLiveKitTransport :
        NativeMatrixRtcCallServiceException("MatrixRTC LiveKit transport is missing")

    data object NoActiveCall :
        NativeMatrixRtcCallServiceException("No active native MatrixRTC call")

    data object MissingAudioOutputRoute :
        NativeMatrixRtcCallServiceException("Requested MatrixRTC audio output route is unavailable")
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
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val autoLeaveWhenOthersLeftDelaysMillis: List<Long> = listOf(800, 1_200, 2_000, 4_000, 8_000, 8_000)
) {
    private val lock = Mutex()
    private val _state = MutableStateFlow(NativeMatrixRtcCallServiceState.IDLE)
    val state: StateFlow<NativeMatrixRtcCallServiceState> = _state.asStateFlow()
    private val _microphoneEnabled = MutableStateFlow(true)
    val microphoneEnabled: StateFlow<Boolean> = _microphoneEnabled.asStateFlow()
    private val _audioOutputState = MutableStateFlow(MatrixRtcAudioOutputState())
    val audioOutputState: StateFlow<MatrixRtcAudioOutputState> = _audioOutputState.asStateFlow()
    private val _remoteParticipantCount = MutableStateFlow(0)
    val remoteParticipantCount: StateFlow<Int> = _remoteParticipantCount.asStateFlow()
    private val _pickupState = MutableStateFlow<NativeMatrixRtcCallPickupState>(
        NativeMatrixRtcCallPickupState.Inactive
    )
    val pickupState: StateFlow<NativeMatrixRtcCallPickupState> = _pickupState.asStateFlow()
    private val _lastFailure = MutableStateFlow<Throwable?>(null)

    private var activeCall: ActiveCall? = null
    private var joiningCall: JoiningCall? = null
    private var activeAttemptId: String? = null
    private var activeRoomId: String? = null
    private var autoLeaveWhenOthersLeftJob: Job? = null
    private var pickupTimeoutJob: Job? = null
    private var remoteParticipantIds: Set<String> = emptySet()
    private val remoteParticipantPresenceQueueLock = Any()
    private var remoteParticipantPresenceTail: Job = Job().apply { complete() }

    fun startAudioCallAsync(
        roomId: String,
        fallbackLiveKitServiceUrl: String? = null,
        waitForPickup: Boolean = false,
        autoLeaveWhenOthersLeft: Boolean = true,
        onFailure: (Throwable) -> Unit = {}
    ): Job {
        return coroutineScope.launch {
            try {
                startAudioCall(
                    roomId = roomId,
                    fallbackLiveKitServiceUrl = fallbackLiveKitServiceUrl,
                    waitForPickup = waitForPickup,
                    autoLeaveWhenOthersLeft = autoLeaveWhenOthersLeft
                )
            } catch (_: CancellationException) {
                // A newer call or explicit leave took ownership of cleanup.
            } catch (error: Throwable) {
                onError(error)
                onFailure(error)
            }
        }
    }

    suspend fun startAudioCall(
        roomId: String,
        fallbackLiveKitServiceUrl: String? = null,
        waitForPickup: Boolean = false,
        autoLeaveWhenOthersLeft: Boolean = true
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
            ensureJoiningAttemptCurrent(attemptId)
            val mediaEncryptionEnabled = environment.isRoomEncrypted(roomId)
            MatrixRtcCallDebugLog.d(
                "nativeCallStart roomId=$roomId attemptId=$attemptId " +
                    "transportSource=${discoveredTransport.source} " +
                    "mediaEncryptionEnabled=$mediaEncryptionEnabled ownUserId=${ownIdentity.userId} " +
                    "ownDeviceId=${ownIdentity.deviceId} ownMemberId=${ownIdentity.memberId}"
            )
            ensureJoiningAttemptCurrent(attemptId)
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
            if (!attachJoiningLiveKitSession(attemptId, liveKit)) {
                runCatching { liveKit.close() }
                liveKitSession = null
                throw CancellationException("Stale MatrixRTC join attempt")
            }
            val sfuConfig = focusClient.sfuConfig(
                membership = ownIdentity,
                transport = discoveredTransport.transport,
                roomId = roomId,
                endpointVersion = MatrixRtcLiveKitJwtEndpointVersion.LEGACY
            )
            MatrixRtcCallDebugLog.d(
                "nativeCallSfuConfig roomId=$roomId attemptId=$attemptId " +
                    "liveKitIdentity=${sfuConfig.liveKitIdentity} liveKitAlias=${sfuConfig.liveKitAlias} " +
                    "ownRtcBackendIdentity=${ownIdentity.legacyRtcBackendIdentity}"
            )
            ensureJoiningAttemptCurrent(attemptId)

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
                    },
                    mediaKeyRotationConfiguration = audioCallMediaKeyRotationConfiguration
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
            if (!attachJoiningMatrixRtcSession(attemptId, session)) {
                session.runCatchingLeaveAndClose()
                matrixRtcSession = null
                throw CancellationException("Stale MatrixRTC join attempt")
            }

            val joinResult = session.join()
            MatrixRtcCallDebugLog.d(
                "nativeCallMatrixJoined roomId=$roomId attemptId=$attemptId " +
                    "ownMembership=${joinResult.ownMembership.debugSummary()} " +
                    "memberships=${joinResult.memberships.joinToString { it.debugSummary() }} " +
                    "keyShareSharedWith=${joinResult.keyShareResult.sharedWith.joinToString { it.debugSummary() }} " +
                    "keyShareFailures=${joinResult.keyShareResult.failures.joinToString { it.debugSummary() }}"
            )
            ensureJoiningAttemptCurrent(attemptId)
            val callNotification = sendCallNotificationIfNeeded(
                roomId = roomId,
                joinResult = joinResult,
                waitForPickup = waitForPickup
            )
            ensureJoiningAttemptCurrent(attemptId)
            MatrixRtcCallDebugLog.d("nativeCallLiveKitConnect roomId=$roomId attemptId=$attemptId")
            liveKit.connect(
                sfuConfig = sfuConfig,
                publishAudio = true
            )
            _microphoneEnabled.value = true
            _audioOutputState.value = liveKit.audioOutputState
            MatrixRtcCallDebugLog.d("nativeCallLiveKitConnected roomId=$roomId attemptId=$attemptId")
            ensureJoiningAttemptCurrent(attemptId)

            val call = ActiveCall(
                attemptId = attemptId,
                roomId = roomId,
                ownUserId = ownIdentity.userId,
                matrixRtcSession = session,
                liveKitSession = liveKit,
                discoveredTransport = discoveredTransport,
                sfuConfig = sfuConfig,
                mediaEncryptionEnabled = mediaEncryptionEnabled,
                autoLeaveWhenOthersLeft = autoLeaveWhenOthersLeft,
                pickupAttempt = pickupAttemptFrom(
                    callNotification = callNotification,
                    waitForPickup = waitForPickup
                )
            )
            if (!finishJoined(call)) {
                runCatching { liveKit.close() }
                liveKitSession = null
                session.runCatchingLeaveAndClose()
                matrixRtcSession = null
                throw CancellationException("Stale MatrixRTC join attempt")
            }
            startCallPickupLifecycleIfNeeded(call)

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
            MatrixRtcCallDebugLog.d(
                "nativeCallStartFailed roomId=$roomId attemptId=$attemptId",
                error
            )
            if (shouldCleanupFailedJoin(attemptId)) {
                _lastFailure.value = error
                runCatching { liveKitSession?.close() }
                matrixRtcSession?.runCatchingLeaveAndClose()
                finishFailed(attemptId)
            }
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

    fun setMicrophoneEnabledAsync(
        enabled: Boolean,
        onFailure: (Throwable) -> Unit = {}
    ): Job {
        return coroutineScope.launch {
            try {
                setMicrophoneEnabled(enabled)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                onError(error)
                onFailure(error)
            }
        }
    }

    suspend fun setMicrophoneEnabled(enabled: Boolean) {
        currentActiveCall()
            ?.liveKitSession
            ?.setMicrophoneEnabled(enabled)
            ?: throw NativeMatrixRtcCallServiceException.NoActiveCall
        _microphoneEnabled.value = enabled
    }

    fun setSpeakerphoneEnabledAsync(
        enabled: Boolean,
        onFailure: (Throwable) -> Unit = {}
    ): Job {
        return coroutineScope.launch {
            try {
                setSpeakerphoneEnabled(enabled)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                onError(error)
                onFailure(error)
            }
        }
    }

    suspend fun setSpeakerphoneEnabled(enabled: Boolean) {
        val call = currentActiveCall()
            ?: throw NativeMatrixRtcCallServiceException.NoActiveCall
        val currentState = call.liveKitSession.audioOutputState
            .takeIf { it.availableDevices.isNotEmpty() }
            ?: _audioOutputState.value
        val targetKind = currentState.preferredKindForSpeakerphone(enabled)
            ?: throw NativeMatrixRtcCallServiceException.MissingAudioOutputRoute
        val selected = currentState.availableDevices.firstOrNull { device ->
            device.kind == targetKind
        }
        val didSelect = call.liveKitSession.selectAudioOutput(targetKind)
        if (!didSelect) {
            throw NativeMatrixRtcCallServiceException.MissingAudioOutputRoute
        }
        if (selected != null) {
            _audioOutputState.value = currentState.copy(selectedDevice = selected)
        }
    }

    fun leaveActiveCallAsync(
        onFailure: (Throwable) -> Unit = {}
    ): Job {
        return coroutineScope.launch {
            try {
                leaveActiveCall()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                onError(error)
                onFailure(error)
            }
        }
    }

    suspend fun leaveActiveCall(): Boolean {
        val call = beginLeaving() ?: return false
        try {
            when (call) {
                is LeavingCall.Active -> {
                    call.activeCall.liveKitSession.close()
                    call.activeCall.matrixRtcSession.leaveAndClose()
                }
                is LeavingCall.Joining -> {
                    call.joiningCall.liveKitSession?.close()
                    call.joiningCall.matrixRtcSession?.runCatchingLeaveAndClose()
                }
            }
            return true
        } finally {
            finishLeft(call.attemptId)
        }
    }

    fun currentRoomId(): String? = activeRoomId
    fun currentMicrophoneEnabled(): Boolean = _microphoneEnabled.value
    fun currentAudioOutputState(): MatrixRtcAudioOutputState = _audioOutputState.value
    fun currentRemoteParticipantCount(): Int = _remoteParticipantCount.value
    fun currentPickupState(): NativeMatrixRtcCallPickupState = _pickupState.value
    fun currentFailure(): Throwable? = _lastFailure.value

    private suspend fun sendCallNotificationIfNeeded(
        roomId: String,
        joinResult: MatrixRtcSessionJoinResult,
        waitForPickup: Boolean
    ): MatrixRtcCallNotificationSendResult? {
        if (!shouldSendCallNotification(joinResult.ownMembership, joinResult.memberships)) {
            return null
        }

        val notificationClient = environment.callNotificationClient(roomId)
        return runCatching {
            try {
                notificationClient.sendCallNotification(
                    parentEventId = joinResult.ownMembership.eventId,
                    slot = joinResult.ownMembership.slot,
                    notificationType = if (waitForPickup) {
                        MatrixRtcCallNotificationType.RING
                    } else {
                        MatrixRtcCallNotificationType.NOTIFICATION
                    },
                    callIntent = AUDIO_CALL_INTENT
                )
            } finally {
                notificationClient.close()
            }
        }.onFailure(onError).getOrNull()
    }

    private fun handleLiveKitEvent(
        event: MatrixRtcLiveKitRoomSessionEvent,
        attemptId: String
    ) {
        MatrixRtcCallDebugLog.d("nativeCallLiveKitEvent attemptId=$attemptId ${event.debugSummary()}")
        when (event) {
            is MatrixRtcLiveKitRoomSessionEvent.Disconnected ->
                scheduleEndActiveCall(attemptId)
            is MatrixRtcLiveKitRoomSessionEvent.FailedToConnect ->
                scheduleEndActiveCall(attemptId)
            is MatrixRtcLiveKitRoomSessionEvent.RemoteParticipantLeft ->
                markRemoteParticipantLeft(attemptId, event.participant)
            is MatrixRtcLiveKitRoomSessionEvent.RemoteParticipantJoined ->
                markRemoteParticipantPresent(attemptId, event.participant)
            is MatrixRtcLiveKitRoomSessionEvent.RemoteTrackPublished ->
                markRemoteParticipantPresent(attemptId, event.participant)
            is MatrixRtcLiveKitRoomSessionEvent.RemoteTrackSubscribed ->
                markRemoteParticipantPresent(attemptId, event.participant)
            is MatrixRtcLiveKitRoomSessionEvent.RemoteTrackSubscriptionFailed ->
                markRemoteParticipantPresent(attemptId, event.participant)
            is MatrixRtcLiveKitRoomSessionEvent.AudioOutputChanged ->
                updateAudioOutputState(attemptId, event.state)
            else -> Unit
        }

        val mediaKeyReshareReason = mediaKeyReshareReason(event)
        if (mediaKeyReshareReason != null) {
            MatrixRtcCallDebugLog.d(
                "nativeCallScheduleMediaKeyReshare attemptId=$attemptId reason=$mediaKeyReshareReason"
            )
            scheduleActiveMediaKeyReshare(attemptId)
        }
        val membershipRefreshReason = membershipRefreshReason(event)
        if (membershipRefreshReason != null) {
            MatrixRtcCallDebugLog.d(
                "nativeCallScheduleMembershipRefresh attemptId=$attemptId reason=$membershipRefreshReason"
            )
            scheduleActiveMembershipRefresh(attemptId)
        }
    }

    private fun scheduleEndActiveCall(attemptId: String) {
        coroutineScope.launch {
            endActiveCall(attemptId)
        }
    }

    private fun updateAudioOutputState(
        attemptId: String,
        state: MatrixRtcAudioOutputState
    ) {
        coroutineScope.launch {
            val isCurrentAttempt = lock.withLock {
                activeAttemptId == attemptId
            }
            if (isCurrentAttempt) {
                _audioOutputState.value = state
            }
        }
    }

    private fun markRemoteParticipantLeft(
        attemptId: String,
        participant: MatrixRtcLiveKitParticipantInfo
    ) {
        enqueueRemoteParticipantPresenceChange(
            RemoteParticipantPresenceChange(
                attemptId = attemptId,
                participantId = participant.identity ?: participant.sid,
                isPresent = false,
                autoLeaveAction = RemoteParticipantPresenceAutoLeaveAction.SCHEDULE
            )
        )
    }

    private fun markRemoteParticipantPresent(
        attemptId: String,
        participant: MatrixRtcLiveKitParticipantInfo
    ) {
        enqueueRemoteParticipantPresenceChange(
            RemoteParticipantPresenceChange(
                attemptId = attemptId,
                participantId = participant.identity ?: participant.sid,
                isPresent = true,
                autoLeaveAction = RemoteParticipantPresenceAutoLeaveAction.CANCEL
            )
        )
    }

    private fun enqueueRemoteParticipantPresenceChange(change: RemoteParticipantPresenceChange) {
        synchronized(remoteParticipantPresenceQueueLock) {
            val previous = remoteParticipantPresenceTail
            remoteParticipantPresenceTail = coroutineScope.launch {
                previous.join()
                try {
                    applyRemoteParticipantPresenceChange(change)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    onError(error)
                }
            }
        }
    }

    private suspend fun applyRemoteParticipantPresenceChange(
        change: RemoteParticipantPresenceChange
    ) {
        var pickupTimeoutJobToCancel: Job? = null
        val shouldApplyAutoLeaveAction = lock.withLock {
            if (activeAttemptId != change.attemptId) {
                false
            } else {
                val participantId = change.participantId
                if (participantId != null) {
                    remoteParticipantIds = if (change.isPresent) {
                        remoteParticipantIds + participantId
                    } else {
                        remoteParticipantIds - participantId
                    }
                }
                _remoteParticipantCount.value = remoteParticipantIds.size
                if (change.isPresent && remoteParticipantIds.isNotEmpty()) {
                    pickupTimeoutJobToCancel = markPickupAnsweredLocked(change.attemptId)
                }
                true
            }
        }
        pickupTimeoutJobToCancel?.cancel()
        if (!shouldApplyAutoLeaveAction) {
            return
        }

        when (change.autoLeaveAction) {
            RemoteParticipantPresenceAutoLeaveAction.SCHEDULE ->
                scheduleAutoLeaveWhenOthersLeftCheck(change.attemptId)
            RemoteParticipantPresenceAutoLeaveAction.CANCEL ->
                cancelAutoLeaveWhenOthersLeftCheck(change.attemptId)
        }
    }

    private data class RemoteParticipantPresenceChange(
        val attemptId: String,
        val participantId: String?,
        val isPresent: Boolean,
        val autoLeaveAction: RemoteParticipantPresenceAutoLeaveAction
    )

    private enum class RemoteParticipantPresenceAutoLeaveAction {
        SCHEDULE,
        CANCEL
    }

    private suspend fun startCallPickupLifecycleIfNeeded(call: ActiveCall) {
        val pickupAttempt = call.pickupAttempt ?: return
        val timeoutJob = coroutineScope.launch(start = CoroutineStart.LAZY) {
            val remainingMillis = pickupAttempt.expiresAtMillis - timestampProvider()
            if (remainingMillis > 0) {
                delay(remainingMillis)
            }
            handleCallPickupTimeout(
                attemptId = call.attemptId,
                notificationEventId = pickupAttempt.notificationEventId
            )
        }
        var previousJob: Job? = null
        val shouldStart = lock.withLock {
            if (activeCall?.attemptId == call.attemptId) {
                if (remoteParticipantIds.isNotEmpty()) {
                    _pickupState.value = NativeMatrixRtcCallPickupState.Answered(call.roomId)
                    false
                } else {
                    previousJob = pickupTimeoutJob
                    pickupTimeoutJob = timeoutJob
                    _pickupState.value = NativeMatrixRtcCallPickupState.Ringing(
                        roomId = call.roomId,
                        notificationEventId = pickupAttempt.notificationEventId,
                        expiresAtMillis = pickupAttempt.expiresAtMillis
                    )
                    true
                }
            } else {
                false
            }
        }
        previousJob?.cancel()
        if (shouldStart) {
            timeoutJob.start()
        } else {
            timeoutJob.cancel()
        }
    }

    private suspend fun handleCallPickupTimeout(
        attemptId: String,
        notificationEventId: String
    ) {
        val shouldEnd = lock.withLock {
            val call = activeCall
            if (
                call?.attemptId == attemptId &&
                call.pickupAttempt?.notificationEventId == notificationEventId &&
                _pickupState.value is NativeMatrixRtcCallPickupState.Ringing
            ) {
                pickupTimeoutJob = null
                _pickupState.value = NativeMatrixRtcCallPickupState.TimedOut(call.roomId)
                true
            } else {
                false
            }
        }
        if (shouldEnd) {
            endActiveCall(attemptId)
        }
    }

    private fun markPickupAnsweredLocked(attemptId: String): Job? {
        val call = activeCall ?: return null
        if (
            call.attemptId != attemptId ||
            call.pickupAttempt == null ||
            _pickupState.value !is NativeMatrixRtcCallPickupState.Ringing
        ) {
            return null
        }
        val timeoutJob = pickupTimeoutJob
        pickupTimeoutJob = null
        _pickupState.value = NativeMatrixRtcCallPickupState.Answered(call.roomId)
        return timeoutJob
    }

    private fun pickupAttemptFrom(
        callNotification: MatrixRtcCallNotificationSendResult?,
        waitForPickup: Boolean
    ): PickupAttempt? {
        if (
            !waitForPickup ||
            callNotification?.sentNotification != true ||
            callNotification.notificationType != MatrixRtcCallNotificationType.RING
        ) {
            return null
        }
        val notificationEventId = callNotification.notificationEventId ?: return null
        return PickupAttempt(
            notificationEventId = notificationEventId,
            expiresAtMillis = callNotification.senderTimestamp + callNotification.lifetimeMillis
        )
    }

    private suspend fun endActiveCall(attemptId: String): Boolean {
        val call = beginLeaving(attemptId = attemptId) ?: return false
        try {
            when (call) {
                is LeavingCall.Active -> {
                    call.activeCall.liveKitSession.close()
                    call.activeCall.matrixRtcSession.leaveAndClose()
                }
                is LeavingCall.Joining -> {
                    call.joiningCall.liveKitSession?.close()
                    call.joiningCall.matrixRtcSession?.runCatchingLeaveAndClose()
                }
            }
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
                    .also { result ->
                        MatrixRtcCallDebugLog.d(
                            "nativeCallMembershipRefreshed attemptId=$attemptId " +
                                "memberships=${result.memberships.joinToString { it.debugSummary() }} " +
                                "keyShareSharedWith=${result.keyShareResult.sharedWith.joinToString { it.debugSummary() }} " +
                                "keyShareFailures=${result.keyShareResult.failures.joinToString { it.debugSummary() }}"
                        )
                    }
            }.onFailure(onError)
        }
    }

    private suspend fun scheduleAutoLeaveWhenOthersLeftCheck(attemptId: String) {
        val newJob = coroutineScope.launch(start = CoroutineStart.LAZY) {
            autoLeaveWhenOthersLeftIfConfirmed(attemptId)
        }
        var shouldCheck = false
        val previousJob = lock.withLock {
            val call = activeCall
            if (
                call?.attemptId != attemptId ||
                !call.autoLeaveWhenOthersLeft ||
                _state.value != NativeMatrixRtcCallServiceState.CONNECTED
            ) {
                null
            } else {
                shouldCheck = true
                val previousJob = autoLeaveWhenOthersLeftJob
                autoLeaveWhenOthersLeftJob = newJob
                previousJob
            }
        }
        if (!shouldCheck) {
            newJob.cancel()
            return
        }
        previousJob?.cancel()
        newJob.start()
    }

    private suspend fun cancelAutoLeaveWhenOthersLeftCheck(attemptId: String) {
        val job = lock.withLock {
            if (activeCall?.attemptId != attemptId) {
                null
            } else {
                val job = autoLeaveWhenOthersLeftJob
                autoLeaveWhenOthersLeftJob = null
                job
            }
        }
        job?.cancel()
    }

    private suspend fun autoLeaveWhenOthersLeftIfConfirmed(attemptId: String) {
        for ((attemptIndex, delayMillis) in autoLeaveWhenOthersLeftDelaysMillis.withIndex()) {
            if (!isAutoLeaveWhenOthersLeftCheckNeeded(attemptId)) {
                return
            }
            delay(delayMillis)
            if (!isAutoLeaveWhenOthersLeftCheckNeeded(attemptId)) {
                return
            }

            val call = currentActiveCallByAttempt(attemptId) ?: return
            if (!call.autoLeaveWhenOthersLeft) {
                return
            }

            try {
                val result = call.matrixRtcSession.refreshMemberships(distributeKeys = false)
                if (!isAutoLeaveWhenOthersLeftCheckNeeded(attemptId)) {
                    return
                }
                if (!containsRemoteMembership(result.memberships, call.ownUserId)) {
                    MatrixRtcCallDebugLog.d(
                        "nativeCallAutoLeaveConfirmed attemptId=$attemptId " +
                            "memberships=${result.memberships.joinToString { it.debugSummary() }}"
                    )
                    endActiveCall(attemptId)
                    return
                }

                MatrixRtcCallDebugLog.d(
                    "nativeCallAutoLeaveRemoteMembershipStillActive attemptId=$attemptId " +
                        "attempt=${attemptIndex + 1} memberships=${result.memberships.size}"
                )
            } catch (_: CancellationException) {
                return
            } catch (error: Throwable) {
                MatrixRtcCallDebugLog.d(
                    "nativeCallAutoLeaveCheckFailed attemptId=$attemptId attempt=${attemptIndex + 1}",
                    error
                )
                onError(error)
            }
        }

        MatrixRtcCallDebugLog.d("nativeCallAutoLeaveSkipped attemptId=$attemptId")
    }

    private suspend fun isAutoLeaveWhenOthersLeftCheckNeeded(attemptId: String): Boolean {
        return lock.withLock {
            activeCall?.attemptId == attemptId &&
                activeCall?.autoLeaveWhenOthersLeft == true &&
                _state.value == NativeMatrixRtcCallServiceState.CONNECTED
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
                    .also { result ->
                        MatrixRtcCallDebugLog.d(
                            "nativeCallMediaKeyReshared attemptId=$attemptId " +
                                "memberships=${result.memberships.joinToString { it.debugSummary() }} " +
                                "keyShareSharedWith=${result.keyShareResult.sharedWith.joinToString { it.debugSummary() }} " +
                                "keyShareFailures=${result.keyShareResult.failures.joinToString { it.debugSummary() }}"
                        )
                    }
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
            _lastFailure.value = null
            _microphoneEnabled.value = true
            _audioOutputState.value = MatrixRtcAudioOutputState()
            remoteParticipantIds = emptySet()
            _remoteParticipantCount.value = 0
            pickupTimeoutJob?.cancel()
            pickupTimeoutJob = null
            _pickupState.value = NativeMatrixRtcCallPickupState.Inactive
            joiningCall = JoiningCall(
                attemptId = attemptId
            )
            _state.value = NativeMatrixRtcCallServiceState.JOINING
            attemptId
        }
    }

    private suspend fun ensureJoiningAttemptCurrent(attemptId: String) {
        val isCurrent = lock.withLock {
            activeAttemptId == attemptId &&
                _state.value == NativeMatrixRtcCallServiceState.JOINING
        }
        if (!isCurrent) {
            throw CancellationException("Stale MatrixRTC join attempt")
        }
    }

    private suspend fun attachJoiningLiveKitSession(
        attemptId: String,
        liveKitSession: MatrixRtcLiveKitRoomSession
    ): Boolean {
        return lock.withLock {
            val joining = joiningCall
            if (
                activeAttemptId != attemptId ||
                joining?.attemptId != attemptId ||
                _state.value != NativeMatrixRtcCallServiceState.JOINING
            ) {
                return@withLock false
            }
            joiningCall = joining.copy(liveKitSession = liveKitSession)
            true
        }
    }

    private suspend fun attachJoiningMatrixRtcSession(
        attemptId: String,
        matrixRtcSession: MatrixRtcSession
    ): Boolean {
        return lock.withLock {
            val joining = joiningCall
            if (
                activeAttemptId != attemptId ||
                joining?.attemptId != attemptId ||
                _state.value != NativeMatrixRtcCallServiceState.JOINING
            ) {
                return@withLock false
            }
            joiningCall = joining.copy(matrixRtcSession = matrixRtcSession)
            true
        }
    }

    private suspend fun shouldCleanupFailedJoin(attemptId: String): Boolean {
        return lock.withLock {
            activeAttemptId == attemptId &&
                _state.value == NativeMatrixRtcCallServiceState.JOINING
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
            joiningCall = null
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
            joiningCall = null
            autoLeaveWhenOthersLeftJob = null
            pickupTimeoutJob?.cancel()
            pickupTimeoutJob = null
            _microphoneEnabled.value = true
            _audioOutputState.value = MatrixRtcAudioOutputState()
            remoteParticipantIds = emptySet()
            _remoteParticipantCount.value = 0
            _pickupState.value = NativeMatrixRtcCallPickupState.Inactive
            _state.value = NativeMatrixRtcCallServiceState.IDLE
        }
    }

    private suspend fun beginLeaving(): LeavingCall? {
        return beginLeaving(attemptId = null)
    }

    private suspend fun beginLeaving(attemptId: String?): LeavingCall? {
        return lock.withLock {
            if (attemptId != null && activeAttemptId != attemptId) {
                return@withLock null
            }
            _lastFailure.value = null
            activeCall?.let { call ->
                _state.value = NativeMatrixRtcCallServiceState.LEAVING
                return@withLock LeavingCall.Active(call)
            }
            joiningCall?.let { call ->
                joiningCall = null
                _state.value = NativeMatrixRtcCallServiceState.LEAVING
                return@withLock LeavingCall.Joining(call)
            }
            if (activeAttemptId == null) {
                _state.value = NativeMatrixRtcCallServiceState.IDLE
            }
            null
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
            joiningCall = null
            autoLeaveWhenOthersLeftJob = null
            pickupTimeoutJob?.cancel()
            pickupTimeoutJob = null
            _microphoneEnabled.value = true
            _audioOutputState.value = MatrixRtcAudioOutputState()
            remoteParticipantIds = emptySet()
            _remoteParticipantCount.value = 0
            if (_pickupState.value !is NativeMatrixRtcCallPickupState.TimedOut) {
                _pickupState.value = NativeMatrixRtcCallPickupState.Inactive
            }
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

    private suspend fun MatrixRtcSession.leaveAndClose() {
        try {
            leave()
        } finally {
            close()
        }
    }

    private suspend fun MatrixRtcSession.runCatchingLeaveAndClose() {
        runCatching { leaveAndClose() }
    }

    private data class ActiveCall(
        val attemptId: String,
        val roomId: String,
        val ownUserId: String,
        val matrixRtcSession: MatrixRtcSession,
        val liveKitSession: MatrixRtcLiveKitRoomSession,
        val discoveredTransport: MatrixRtcLiveKitDiscoveredTransport,
        val sfuConfig: MatrixRtcLiveKitSfuConfig,
        val mediaEncryptionEnabled: Boolean,
        val autoLeaveWhenOthersLeft: Boolean,
        val pickupAttempt: PickupAttempt?
    )

    private data class JoiningCall(
        val attemptId: String,
        val matrixRtcSession: MatrixRtcSession? = null,
        val liveKitSession: MatrixRtcLiveKitRoomSession? = null
    )

    private data class PickupAttempt(
        val notificationEventId: String,
        val expiresAtMillis: Long
    )

    private sealed interface LeavingCall {
        val attemptId: String

        data class Active(
            val activeCall: ActiveCall
        ) : LeavingCall {
            override val attemptId: String = activeCall.attemptId
        }

        data class Joining(
            val joiningCall: JoiningCall
        ) : LeavingCall {
            override val attemptId: String = joiningCall.attemptId
        }
    }

    companion object {
        const val AUDIO_CALL_INTENT = "audio"

        // LiveKit Android applies a sender frame cryptor key index when the local track is
        // published. Until the SDK exposes switching that index for existing senders, late
        // joiners must receive the current key rather than a rotated key.
        private val audioCallMediaKeyRotationConfiguration = MatrixRtcMediaKeyRotationConfiguration(
            rotateKeyOnLateJoin = false
        )

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

        fun containsRemoteMembership(
            memberships: List<MatrixRtcCallMembership>,
            ownUserId: String
        ): Boolean {
            return memberships.any { membership -> membership.userId != ownUserId }
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

private fun MatrixRtcCallMembership.debugSummary(): String {
    return "userId=$userId deviceId=$deviceId memberId=$memberId " +
        "rtcBackendIdentity=$rtcBackendIdentity kind=$kind"
}

private fun MatrixRtcToDeviceTarget.debugSummary(): String {
    return "${userId}:${deviceId}"
}

private fun MatrixRtcCustomToDeviceSendFailure.debugSummary(): String {
    return "${userId}:${deviceId}:$reason"
}
