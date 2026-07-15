package com.zyna.app.ui.chat

import com.zyna.app.data.calls.matrixrtc.NativeMatrixRtcCallService
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixIncomingRtcCallNotification
import com.zyna.app.data.matrix.MatrixRoomCallInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class ChatCallInfoObserverDriver(
    val roomCallInfoUpdates: (roomId: String) -> Flow<MatrixRoomCallInfo>,
    val nativeCallStateUpdates: Flow<Unit>,
    val incomingCallNotifications: Flow<MatrixIncomingRtcCallNotification>,
    val loadRoomCallInfo: suspend (roomId: String) -> MatrixRoomCallInfo,
    val hasActiveMembership: suspend (roomId: String, senderId: String) -> Boolean,
    val nowMillis: () -> Long = System::currentTimeMillis,
    val delayMillis: suspend (Long) -> Unit = { delay(it) }
)

/**
 * Observes and reconciles the MatrixRTC state associated with one chat room.
 *
 * The mutable session state is confined to the coroutine running [observe].
 * Cancelling that coroutine also cancels fallback refreshes, ring validation,
 * and ring expiry work belonging to the room.
 */
internal class ChatCallInfoObserverSession(
    private val driver: ChatCallInfoObserverDriver,
    private val onCallInfo: (ChatCallInfoTarget, MatrixRoomCallInfo) -> Unit,
    private val onObservationError: (ChatCallInfoTarget, Throwable) -> Unit,
    private val onWarning: (String, Throwable) -> Unit = { _, _ -> },
    private val onLog: (String) -> Unit = {}
) {
    suspend fun observe(target: ChatCallInfoTarget) {
        val roomId = target.roomId
        coroutineScope {
            val ringOverride = MutableStateFlow<ChatMatrixRtcRingOverride?>(null)
            val membershipFallback = MutableStateFlow<MatrixRoomCallInfo?>(null)
            var lastObservedCallInfo: MatrixRoomCallInfo? = null
            var lastRoomInfoHasCall = false
            var hasObservedRoomInfoActiveCall = false
            var membershipFallbackRefreshJob: Job? = null
            var membershipFallbackValidationJob: Job? = null
            var ringExpiryJob: Job? = null
            var ringValidationJob: Job? = null
            var ringValidationEventId: String? = null
            var refreshMembershipFallback: ((String) -> Unit)? = null

            fun scheduleMembershipFallbackValidation() {
                if (membershipFallbackValidationJob?.isActive == true) {
                    return
                }
                membershipFallbackValidationJob = launch {
                    driver.delayMillis(MEMBERSHIP_FALLBACK_VALIDATION_DELAY_MS)
                    membershipFallbackValidationJob = null
                    refreshMembershipFallback?.invoke("activeFallbackValidation")
                }
            }

            fun applyMembershipFallbackSnapshot(
                fallback: MatrixRoomCallInfo?,
                reason: String
            ) {
                if (lastRoomInfoHasCall || hasObservedRoomInfoActiveCall) {
                    membershipFallback.value = null
                    return
                }

                membershipFallback.value = fallback
                if (fallback?.hasRoomCall == true && fallback.isAudioCall) {
                    onLog(
                        "chatCallMembershipFallback active roomId=$roomId " +
                            "participants=${fallback.activeParticipantCount} reason=$reason"
                    )
                    scheduleMembershipFallbackValidation()
                }
            }

            refreshMembershipFallback = refresh@ { reason ->
                if (membershipFallbackRefreshJob?.isActive == true) {
                    return@refresh
                }
                membershipFallbackRefreshJob = launch {
                    val fallback = try {
                        driver.loadRoomCallInfo(roomId)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        onWarning(
                            "Failed loading MatrixRTC membership fallback " +
                                "roomId=$roomId reason=$reason",
                            error
                        )
                        null
                    }

                    membershipFallbackRefreshJob = null
                    applyMembershipFallbackSnapshot(fallback, reason)
                }
            }

            fun handleRoomInfoForMembershipFallback(callInfo: MatrixRoomCallInfo) {
                lastRoomInfoHasCall = callInfo.hasRoomCall
                if (callInfo.hasRoomCall) {
                    hasObservedRoomInfoActiveCall = true
                    membershipFallback.value = null
                    membershipFallbackRefreshJob?.cancel()
                    membershipFallbackRefreshJob = null
                    membershipFallbackValidationJob?.cancel()
                    membershipFallbackValidationJob = null
                    return
                }

                if (hasObservedRoomInfoActiveCall) {
                    membershipFallback.value = null
                    membershipFallbackRefreshJob?.cancel()
                    membershipFallbackRefreshJob = null
                    membershipFallbackValidationJob?.cancel()
                    membershipFallbackValidationJob = null
                    return
                }

                if (membershipFallback.value == null) {
                    refreshMembershipFallback?.invoke("roomInfoInactive")
                }
            }

            fun clearRingOverride(reason: String, eventId: String) {
                if (ringOverride.value?.eventId != eventId) {
                    return
                }
                ringExpiryJob?.cancel()
                ringExpiryJob = null
                ringValidationJob?.cancel()
                ringValidationJob = null
                ringValidationEventId = null
                ringOverride.value = null
                onLog(
                    "chatCallRingOverride cleared roomId=$roomId " +
                        "eventId=$eventId reason=$reason"
                )
            }

            fun scheduleRingOverrideExpiry(override: ChatMatrixRtcRingOverride) {
                ringExpiryJob?.cancel()
                ringExpiryJob = launch {
                    driver.delayMillis(
                        maxOf(0L, override.expiresAtMillis - driver.nowMillis())
                    )
                    clearRingOverride(reason = "expired", eventId = override.eventId)
                }
            }

            fun scheduleRingOverrideValidation(
                eventId: String,
                senderId: String,
                reason: String,
                delayMillis: Long
            ) {
                if (ringValidationJob?.isActive == true && ringValidationEventId == eventId) {
                    return
                }
                ringValidationJob?.cancel()
                ringValidationEventId = eventId
                ringValidationJob = launch {
                    driver.delayMillis(delayMillis)
                    val hasActiveMembership = try {
                        driver.hasActiveMembership(roomId, senderId)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        onWarning(
                            "Failed validating MatrixRTC ring membership roomId=$roomId " +
                                "eventId=$eventId reason=$reason",
                            error
                        )
                        null
                    }

                    if (ringOverride.value?.eventId != eventId) {
                        if (ringValidationEventId == eventId) {
                            ringValidationJob = null
                            ringValidationEventId = null
                        }
                        return@launch
                    }

                    ringValidationJob = null
                    ringValidationEventId = null
                    when (hasActiveMembership) {
                        true -> {
                            ringOverride.update { current ->
                                if (current?.eventId == eventId) {
                                    current.copy(hasObservedActiveCall = true)
                                } else {
                                    current
                                }
                            }
                            onLog(
                                "chatCallRingOverride validated roomId=$roomId " +
                                    "eventId=$eventId reason=$reason"
                            )
                        }
                        false -> clearRingOverride(reason = reason, eventId = eventId)
                        null -> Unit
                    }
                }
            }

            fun handleRingOverrideSideEffects(
                observed: MatrixRoomCallInfo,
                override: ChatMatrixRtcRingOverride?
            ) {
                lastObservedCallInfo = observed
                val currentOverride = override ?: return
                if (currentOverride.expiresAtMillis <= driver.nowMillis()) {
                    clearRingOverride(reason = "expired", eventId = currentOverride.eventId)
                    return
                }

                if (observed.hasRoomCall) {
                    if (!currentOverride.hasObservedActiveCall) {
                        ringValidationJob?.cancel()
                        ringValidationJob = null
                        ringValidationEventId = null
                        ringOverride.value = currentOverride.copy(
                            hasObservedActiveCall = true
                        )
                        onLog(
                            "chatCallRingOverride observedActive roomId=$roomId " +
                                "eventId=${currentOverride.eventId}"
                        )
                    }
                    return
                }

                if (currentOverride.hasObservedActiveCall) {
                    clearRingOverride(
                        reason = "roomCallEnded",
                        eventId = currentOverride.eventId
                    )
                }
            }

            fun handleIncomingNotification(notification: MatrixIncomingRtcCallNotification) {
                val override = ChatCallBannerPolicy.ringOverrideForNotification(
                    notification = notification,
                    roomId = roomId,
                    hasObservedActiveCall = lastObservedCallInfo?.hasRoomCall == true,
                    nowMillis = driver.nowMillis()
                ) ?: return
                ringOverride.value = override
                scheduleRingOverrideExpiry(override)
                if (!override.hasObservedActiveCall) {
                    scheduleRingOverrideValidation(
                        eventId = notification.eventId,
                        senderId = notification.senderId,
                        reason = "membershipNotObserved",
                        delayMillis = RING_OVERRIDE_MEMBERSHIP_CONFIRMATION_DELAY_MS
                    )
                }
                onLog(
                    "chatCallRingOverride received roomId=$roomId " +
                        "eventId=${notification.eventId} sender=${notification.senderId} " +
                        "kind=${notification.kind} observed=${override.hasObservedActiveCall}"
                )
            }

            val notificationJob = launch {
                driver.incomingCallNotifications.collect(::handleIncomingNotification)
            }

            try {
                combine(
                    driver.roomCallInfoUpdates(roomId)
                        .onEach(::handleRoomInfoForMembershipFallback),
                    driver.nativeCallStateUpdates,
                    ringOverride,
                    membershipFallback
                ) { callInfo, _, override, fallback ->
                    ChatCallInfoSnapshot(
                        roomInfo = callInfo,
                        observed = ChatCallBannerPolicy.mergeMembershipFallback(
                            callInfo,
                            fallback
                        ),
                        ringOverride = override
                    )
                }
                    .collect { snapshot ->
                        handleRingOverrideSideEffects(
                            observed = snapshot.roomInfo,
                            override = snapshot.ringOverride
                        )
                        onCallInfo(
                            target,
                            ChatCallBannerPolicy.applyRingOverride(
                                observed = snapshot.observed,
                                override = snapshot.ringOverride,
                                nowMillis = driver.nowMillis()
                            )
                        )
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                onObservationError(target, error)
            } finally {
                notificationJob.cancel()
                membershipFallbackRefreshJob?.cancel()
                membershipFallbackValidationJob?.cancel()
                ringExpiryJob?.cancel()
                ringValidationJob?.cancel()
                ringValidationEventId = null
            }
        }
    }
}

private data class ChatCallInfoSnapshot(
    val roomInfo: MatrixRoomCallInfo,
    val observed: MatrixRoomCallInfo,
    val ringOverride: ChatMatrixRtcRingOverride?
)

internal fun createChatCallInfoCoordinator(
    scope: CoroutineScope,
    matrixClientService: MatrixClientService,
    nativeMatrixRtcCallService: NativeMatrixRtcCallService,
    onCallInfo: (ChatCallInfoTarget, MatrixRoomCallInfo) -> Unit,
    onObservationError: (ChatCallInfoTarget, Throwable) -> Unit,
    onWarning: (String, Throwable) -> Unit,
    onLog: (String) -> Unit
): ChatCallInfoCoordinator {
    val observerSession = ChatCallInfoObserverSession(
        driver = ChatCallInfoObserverDriver(
            roomCallInfoUpdates = matrixClientService::roomCallInfoUpdates,
            nativeCallStateUpdates = nativeMatrixRtcCallService.state.map { Unit },
            incomingCallNotifications = matrixClientService.incomingMatrixRtcCallNotifications,
            loadRoomCallInfo = matrixClientService::loadRoomCallInfo,
            hasActiveMembership = matrixClientService::hasActiveMatrixRtcMembership
        ),
        onCallInfo = onCallInfo,
        onObservationError = onObservationError,
        onWarning = onWarning,
        onLog = onLog
    )
    return ChatCallInfoCoordinator(
        scope = scope,
        observeTarget = observerSession::observe,
        onLog = onLog
    )
}

internal const val MEMBERSHIP_FALLBACK_VALIDATION_DELAY_MS = 10_000L
internal const val RING_OVERRIDE_MEMBERSHIP_CONFIRMATION_DELAY_MS = 2_500L
