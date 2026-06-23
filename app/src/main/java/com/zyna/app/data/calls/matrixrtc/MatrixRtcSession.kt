package com.zyna.app.data.calls.matrixrtc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface MatrixRtcSessionMembershipClient {
    suspend fun publishOwnLegacyMembership(
        slot: MatrixRtcSlotDescription,
        roomVersion: String?,
        focusSelection: MatrixRtcLegacyCallMembershipFocusSelection,
        fociPreferred: List<MatrixRtcTransport>,
        createdTimestamp: Long?,
        expires: Long,
        callIntent: String?
    ): MatrixRtcCallMembership

    suspend fun loadActiveMemberships(
        slot: MatrixRtcSlotDescription,
        joinedUserIds: Set<String>?,
        now: Long
    ): List<MatrixRtcCallMembership>

    suspend fun leaveOwnLegacyMembership(
        slot: MatrixRtcSlotDescription,
        roomVersion: String?
    ): String

    suspend fun scheduleDelayedLeaveOwnLegacyMembership(
        slot: MatrixRtcSlotDescription,
        roomVersion: String?,
        delayMillis: ULong
    ): String {
        throw MatrixRtcSessionDelayedEventException.Unsupported
    }

    suspend fun restartDelayedEvent(delayId: String) {
        throw MatrixRtcSessionDelayedEventException.Unsupported
    }

    suspend fun sendDelayedEvent(delayId: String) {
        throw MatrixRtcSessionDelayedEventException.Unsupported
    }

    suspend fun cancelDelayedEvent(delayId: String) {
        throw MatrixRtcSessionDelayedEventException.Unsupported
    }
}

enum class MatrixRtcSessionState {
    IDLE,
    JOINING,
    JOINED,
    LEFT
}

sealed class MatrixRtcSessionException(message: String) : Exception(message) {
    data object AlreadyJoined : MatrixRtcSessionException("MatrixRTC session is already joined")
    data object NotJoined : MatrixRtcSessionException("MatrixRTC session is not joined")
    data class OwnMembershipIdentityMismatch(
        val expected: MatrixRtcMembershipIdentity,
        val actual: MatrixRtcMembershipIdentity
    ) : MatrixRtcSessionException("MatrixRTC own membership identity mismatch")
}

enum class MatrixRtcSessionMediaEncryptionMode {
    PER_PARTICIPANT_KEYS,
    UNENCRYPTED
}

data class MatrixRtcSessionConfiguration(
    val slot: MatrixRtcSlotDescription = MatrixRtcSlotDescription.MATRIX_CALL_ROOM,
    val roomVersion: String? = null,
    val ownMembershipIdentity: MatrixRtcMembershipIdentity? = null,
    val focusSelection: MatrixRtcLegacyCallMembershipFocusSelection =
        MatrixRtcLegacyCallMembershipFocusSelection.OLDEST_MEMBERSHIP,
    val fociPreferred: List<MatrixRtcTransport>,
    val expires: Long = MatrixRtcCallMembership.DEFAULT_EXPIRE_DURATION_MILLIS,
    val membershipEventExpiryHeadroomMillis: Long = 5_000,
    val membershipEventExpiryRefreshRetryDelayMillis: ULong = 5_000UL,
    val delayedLeaveEventDelayMillis: ULong? = 18_000UL,
    val delayedLeaveEventRestartMillis: ULong = 4_000UL,
    val callIntent: String? = null,
    val joinedUserIds: Set<String>? = null,
    val mediaEncryptionMode: MatrixRtcSessionMediaEncryptionMode =
        MatrixRtcSessionMediaEncryptionMode.PER_PARTICIPANT_KEYS,
    val mediaKeyRotationConfiguration: MatrixRtcMediaKeyRotationConfiguration =
        MatrixRtcMediaKeyRotationConfiguration()
)

data class MatrixRtcSessionJoinResult(
    val ownMembership: MatrixRtcCallMembership,
    val memberships: List<MatrixRtcCallMembership>,
    val keyShareResult: MatrixRtcMediaKeyShareResult
)

data class MatrixRtcSessionMembershipRefreshResult(
    val memberships: List<MatrixRtcCallMembership>,
    val keyShareResult: MatrixRtcMediaKeyShareResult
)

class MatrixRtcSession(
    private val configuration: MatrixRtcSessionConfiguration,
    private val membershipClient: MatrixRtcSessionMembershipClient,
    private val keyTransportFactory: (MatrixRtcMembershipIdentity) -> MatrixRtcToDeviceKeyTransport,
    private val keyGenerator: MatrixRtcMediaKeyGenerating = MatrixRtcRandomMediaKeyGenerator(),
    private val timestampProvider: () -> Long = { System.currentTimeMillis() },
    private val onKeyChanged: (MatrixRtcMediaKeyChangedEvent) -> Unit,
    private val onError: ((Throwable) -> Unit)? = null,
    private val coroutineScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : AutoCloseable {
    private val sessionUpdateMutex = Mutex()
    private val stateLock = Any()
    private val mutableState = MutableState()

    val state: MatrixRtcSessionState
        get() = withStateLock { state }

    val ownMembership: MatrixRtcCallMembership?
        get() = withStateLock { ownMembership }

    val memberships: List<MatrixRtcCallMembership>
        get() = withStateLock { memberships }

    suspend fun join(): MatrixRtcSessionJoinResult {
        return sessionUpdateMutex.withLock {
            if (withStateLock { state == MatrixRtcSessionState.JOINING || state == MatrixRtcSessionState.JOINED }) {
                throw MatrixRtcSessionException.AlreadyJoined
            }

            withStateLock {
                state = MatrixRtcSessionState.JOINING
            }

            var publishedOwnMembership: MatrixRtcCallMembership? = null
            var startedMediaKeyManager: MatrixRtcMediaKeyManager? = null
            var prestartedKeyTransport: MatrixRtcToDeviceKeyTransport? = null
            var scheduledDelayedLeaveEventId: String? = null
            val earlyReceivedKeys = MatrixRtcEarlyReceivedKeyBuffer()

            try {
                if (
                    configuration.mediaEncryptionMode ==
                    MatrixRtcSessionMediaEncryptionMode.PER_PARTICIPANT_KEYS &&
                    configuration.ownMembershipIdentity != null
                ) {
                    val transport = keyTransportFactory(configuration.ownMembershipIdentity)
                    transport.setReceivedKeyHandler { result -> earlyReceivedKeys.append(result) }
                    transport.start()
                    prestartedKeyTransport = transport
                }

                val createdTimestamp = timestampProvider()
                val ownMembership = membershipClient.publishOwnLegacyMembership(
                    slot = configuration.slot,
                    roomVersion = configuration.roomVersion,
                    focusSelection = configuration.focusSelection,
                    fociPreferred = configuration.fociPreferred,
                    createdTimestamp = createdTimestamp,
                    expires = configuration.expires,
                    callIntent = configuration.callIntent
                )
                publishedOwnMembership = ownMembership
                configuration.ownMembershipIdentity?.let { expectedIdentity ->
                    if (expectedIdentity != ownMembership.identity) {
                        throw MatrixRtcSessionException.OwnMembershipIdentityMismatch(
                            expected = expectedIdentity,
                            actual = ownMembership.identity
                        )
                    }
                }

                scheduledDelayedLeaveEventId = scheduleDelayedLeaveIfPossible()
                val loadedMemberships = membershipClient.loadActiveMemberships(
                    slot = configuration.slot,
                    joinedUserIds = configuration.joinedUserIds,
                    now = timestampProvider()
                )
                val activeMemberships = membershipsIncluding(
                    memberships = loadedMemberships,
                    ownMembership = ownMembership
                )

                val manager: MatrixRtcMediaKeyManager?
                val keyShareResult: MatrixRtcMediaKeyShareResult
                when (configuration.mediaEncryptionMode) {
                    MatrixRtcSessionMediaEncryptionMode.PER_PARTICIPANT_KEYS -> {
                        val mediaKeyManager = MatrixRtcMediaKeyManager(
                            ownMembership = ownMembership,
                            memberships = activeMemberships,
                            transport = prestartedKeyTransport ?: keyTransportFactory(ownMembership.identity),
                            keyGenerator = keyGenerator,
                            rotationConfiguration = configuration.mediaKeyRotationConfiguration,
                            timestampProvider = timestampProvider,
                            onKeyChanged = onKeyChanged,
                            onError = onError
                        )
                        if (prestartedKeyTransport == null) {
                            mediaKeyManager.start()
                        }
                        startedMediaKeyManager = mediaKeyManager

                        val deliverReceivedKey: (Result<MatrixRtcReceivedCallEncryptionKey>) -> Unit = { result ->
                            result
                                .onSuccess(mediaKeyManager::handleReceivedKey)
                                .onFailure { error -> onError?.invoke(error) }
                        }
                        earlyReceivedKeys.drain(forwardingHandler = deliverReceivedKey)
                            .forEach(deliverReceivedKey)

                        manager = mediaKeyManager
                        keyShareResult = mediaKeyManager.ensureKeyDistribution(activeMemberships)
                    }
                    MatrixRtcSessionMediaEncryptionMode.UNENCRYPTED -> {
                        manager = null
                        keyShareResult = MatrixRtcMediaKeyShareResult(
                            failures = emptyList(),
                            sharedWith = emptyList()
                        )
                    }
                }

                withStateLock {
                    this.ownMembership = ownMembership
                    memberships = activeMemberships
                    mediaKeyManager = manager
                    delayedLeaveEventId = scheduledDelayedLeaveEventId
                    state = MatrixRtcSessionState.JOINED
                }
                startMembershipExpiryRefresh()
                startDelayedLeaveRefresh()

                MatrixRtcSessionJoinResult(
                    ownMembership = ownMembership,
                    memberships = activeMemberships,
                    keyShareResult = keyShareResult
                )
            } catch (error: Throwable) {
                startedMediaKeyManager?.stop()
                if (startedMediaKeyManager == null) {
                    prestartedKeyTransport?.stop()
                }
                if (publishedOwnMembership != null) {
                    runCatching { leaveOwnLegacyMembership(scheduledDelayedLeaveEventId) }
                }
                stopMembershipExpiryRefresh()
                stopDelayedLeaveRefresh()
                withStateLock {
                    ownMembership = null
                    memberships = emptyList()
                    mediaKeyManager = null
                    delayedLeaveEventId = null
                    state = MatrixRtcSessionState.IDLE
                }
                throw error
            }
        }
    }

    suspend fun refreshMemberships(
        joinedUserIds: Set<String>? = null,
        distributeKeys: Boolean = true
    ): MatrixRtcSessionMembershipRefreshResult {
        return sessionUpdateMutex.withLock {
            val snapshot = withStateLock {
                StateSnapshot(
                    state = state,
                    ownMembership = ownMembership,
                    mediaKeyManager = mediaKeyManager
                )
            }
            val ownMembership = snapshot.ownMembership
            if (snapshot.state != MatrixRtcSessionState.JOINED || ownMembership == null) {
                throw MatrixRtcSessionException.NotJoined
            }

            val loadedMemberships = membershipClient.loadActiveMemberships(
                slot = configuration.slot,
                joinedUserIds = joinedUserIds ?: configuration.joinedUserIds,
                now = timestampProvider()
            )
            val activeMemberships = membershipsIncluding(loadedMemberships, ownMembership)
            val keyShareResult = when {
                snapshot.mediaKeyManager != null && distributeKeys ->
                    snapshot.mediaKeyManager.ensureKeyDistribution(activeMemberships)
                snapshot.mediaKeyManager != null -> {
                    snapshot.mediaKeyManager.updateMemberships(activeMemberships)
                    MatrixRtcMediaKeyShareResult(failures = emptyList(), sharedWith = emptyList())
                }
                else -> MatrixRtcMediaKeyShareResult(failures = emptyList(), sharedWith = emptyList())
            }

            withStateLock {
                memberships = activeMemberships
            }
            MatrixRtcSessionMembershipRefreshResult(
                memberships = activeMemberships,
                keyShareResult = keyShareResult
            )
        }
    }

    suspend fun reshareCurrentMediaKey(
        joinedUserIds: Set<String>? = null
    ): MatrixRtcSessionMembershipRefreshResult {
        return sessionUpdateMutex.withLock {
            val snapshot = withStateLock {
                StateSnapshot(
                    state = state,
                    ownMembership = ownMembership,
                    mediaKeyManager = mediaKeyManager
                )
            }
            val ownMembership = snapshot.ownMembership
            if (snapshot.state != MatrixRtcSessionState.JOINED || ownMembership == null) {
                throw MatrixRtcSessionException.NotJoined
            }

            val loadedMemberships = membershipClient.loadActiveMemberships(
                slot = configuration.slot,
                joinedUserIds = joinedUserIds ?: configuration.joinedUserIds,
                now = timestampProvider()
            )
            val activeMemberships = membershipsIncluding(loadedMemberships, ownMembership)
            val keyShareResult = snapshot.mediaKeyManager?.reshareCurrentKey(activeMemberships)
                ?: MatrixRtcMediaKeyShareResult(failures = emptyList(), sharedWith = emptyList())

            withStateLock {
                memberships = activeMemberships
            }
            MatrixRtcSessionMembershipRefreshResult(
                memberships = activeMemberships,
                keyShareResult = keyShareResult
            )
        }
    }

    suspend fun refreshOwnMembershipExpiry(): MatrixRtcCallMembership {
        return sessionUpdateMutex.withLock {
            val snapshot = withStateLock {
                ExpiryRefreshSnapshot(
                    state = state,
                    ownMembership = ownMembership,
                    mediaKeyManager = mediaKeyManager,
                    membershipExpiryRefreshIteration = membershipExpiryRefreshIteration
                )
            }
            val ownMembership = snapshot.ownMembership
            if (snapshot.state != MatrixRtcSessionState.JOINED || ownMembership == null) {
                throw MatrixRtcSessionException.NotJoined
            }

            val nextIteration = snapshot.membershipExpiryRefreshIteration + 1
            val nextExpires = membershipEventExpires(nextIteration)
            val refreshedMembership = membershipClient.publishOwnLegacyMembership(
                slot = configuration.slot,
                roomVersion = configuration.roomVersion,
                focusSelection = configuration.focusSelection,
                fociPreferred = configuration.fociPreferred,
                createdTimestamp = ownMembership.createdTimestamp,
                expires = nextExpires,
                callIntent = configuration.callIntent
            )

            val stillCurrent = withStateLock {
                state == MatrixRtcSessionState.JOINED &&
                    this.ownMembership?.identity == ownMembership.identity
            }
            if (!stillCurrent) {
                throw CancellationException("MatrixRTC session changed while refreshing membership")
            }

            val refreshedMemberships = withStateLock {
                membershipExpiryRefreshIteration = nextIteration
                this.ownMembership = refreshedMembership
                val retainedMemberships = memberships.filter { it.identity != ownMembership.identity }
                memberships = membershipsIncluding(retainedMemberships, refreshedMembership)
                memberships
            }
            snapshot.mediaKeyManager?.updateMemberships(refreshedMemberships)
            refreshedMembership
        }
    }

    suspend fun leave(): String? {
        return sessionUpdateMutex.withLock {
            val shouldLeave = withStateLock {
                ownMembership != null ||
                    mediaKeyManager != null ||
                    state == MatrixRtcSessionState.JOINING ||
                    state == MatrixRtcSessionState.JOINED
            }
            if (!shouldLeave) {
                withStateLock {
                    state = MatrixRtcSessionState.LEFT
                }
                return@withLock null
            }

            stopMembershipExpiryRefresh()
            stopDelayedLeaveRefresh()

            val snapshot = withStateLock {
                LeaveSnapshot(
                    mediaKeyManager = mediaKeyManager,
                    delayedLeaveEventId = delayedLeaveEventId
                )
            }
            try {
                leaveOwnLegacyMembership(snapshot.delayedLeaveEventId)
            } finally {
                snapshot.mediaKeyManager?.stop()
                withStateLock {
                    mediaKeyManager = null
                    ownMembership = null
                    memberships = emptyList()
                    delayedLeaveEventId = null
                    state = MatrixRtcSessionState.LEFT
                }
            }
        }
    }

    fun encryptionKeys(): Map<MatrixRtcMediaKeyMapKey, List<MatrixRtcMediaKey>> {
        return withStateLock { mediaKeyManager }?.encryptionKeys() ?: emptyMap()
    }

    fun reemitEncryptionKeys() {
        withStateLock { mediaKeyManager }?.reemitEncryptionKeys()
    }

    override fun close() {
        stopMembershipExpiryRefresh()
        stopDelayedLeaveRefresh()
        withStateLock { mediaKeyManager }?.stop()
        coroutineScope.cancel()
    }

    private fun startMembershipExpiryRefresh() {
        stopMembershipExpiryRefresh()
        if (configuration.expires <= 0) {
            return
        }

        withStateLock {
            membershipExpiryRefreshIteration = 1
        }
        val job = coroutineScope.launch {
            while (true) {
                try {
                    delay(membershipExpiryRefreshDelayMillis())
                    refreshOwnMembershipExpiry()
                } catch (_: CancellationException) {
                    return@launch
                } catch (error: Throwable) {
                    onError?.invoke(error)
                    delay(configuration.membershipEventExpiryRefreshRetryDelayMillis.toLong())
                }
            }
        }
        withStateLock {
            membershipExpiryRefreshJob = job
        }
    }

    private fun stopMembershipExpiryRefresh() {
        val job = withStateLock {
            val job = membershipExpiryRefreshJob
            membershipExpiryRefreshJob = null
            job
        }
        job?.cancel()
    }

    private fun startDelayedLeaveRefresh() {
        stopDelayedLeaveRefresh()
        val shouldStart = withStateLock { delayedLeaveEventId != null }
        if (!shouldStart || configuration.delayedLeaveEventRestartMillis == 0UL) {
            return
        }

        val job = coroutineScope.launch {
            while (true) {
                try {
                    delay(configuration.delayedLeaveEventRestartMillis.toLong())
                    if (!restartDelayedLeaveEvent()) {
                        return@launch
                    }
                } catch (_: CancellationException) {
                    return@launch
                } catch (_: Throwable) {
                    return@launch
                }
            }
        }
        withStateLock {
            delayedLeaveRefreshJob = job
        }
    }

    private fun stopDelayedLeaveRefresh() {
        val job = withStateLock {
            val job = delayedLeaveRefreshJob
            delayedLeaveRefreshJob = null
            job
        }
        job?.cancel()
    }

    private suspend fun scheduleDelayedLeaveIfPossible(
        delayMillis: ULong? = null
    ): String? {
        val configuredDelay = configuration.delayedLeaveEventDelayMillis ?: return null
        val effectiveDelay = delayMillis ?: configuredDelay
        return try {
            membershipClient.scheduleDelayedLeaveOwnLegacyMembership(
                slot = configuration.slot,
                roomVersion = configuration.roomVersion,
                delayMillis = effectiveDelay
            )
        } catch (error: MatrixRtcSessionDelayedEventException.MaxDelayExceeded) {
            val maxDelayMillis = error.maxDelayMillis
            if (maxDelayMillis != null && maxDelayMillis > 0UL && maxDelayMillis < effectiveDelay) {
                scheduleDelayedLeaveIfPossible(maxDelayMillis)
            } else {
                reportDelayedLeaveError(error)
                null
            }
        } catch (error: Throwable) {
            reportDelayedLeaveError(error)
            null
        }
    }

    private suspend fun restartDelayedLeaveEvent(): Boolean {
        val snapshot = withStateLock {
            DelayedLeaveSnapshot(
                state = state,
                delayedLeaveEventId = delayedLeaveEventId
            )
        }
        if (snapshot.state != MatrixRtcSessionState.JOINED) {
            return false
        }
        val delayedLeaveEventId = snapshot.delayedLeaveEventId ?: return false

        try {
            membershipClient.restartDelayedEvent(delayedLeaveEventId)
            return true
        } catch (_: MatrixRtcSessionDelayedEventException.NotFound) {
            val rescheduledId = scheduleDelayedLeaveIfPossible() ?: run {
                withStateLock {
                    if (this.delayedLeaveEventId == delayedLeaveEventId) {
                        this.delayedLeaveEventId = null
                    }
                }
                return false
            }

            return withStateLock {
                if (
                    state == MatrixRtcSessionState.JOINED &&
                    this.delayedLeaveEventId == delayedLeaveEventId
                ) {
                    this.delayedLeaveEventId = rescheduledId
                    true
                } else {
                    false
                }
            }
        } catch (error: Throwable) {
            if (isDelayedEventsUnsupported(error)) {
                withStateLock {
                    if (this.delayedLeaveEventId == delayedLeaveEventId) {
                        this.delayedLeaveEventId = null
                    }
                }
                return false
            }
            reportDelayedLeaveError(error)
            return true
        }
    }

    private suspend fun leaveOwnLegacyMembership(delayedLeaveEventId: String?): String? {
        if (delayedLeaveEventId == null) {
            return membershipClient.leaveOwnLegacyMembership(
                slot = configuration.slot,
                roomVersion = configuration.roomVersion
            )
        }

        return try {
            membershipClient.sendDelayedEvent(delayedLeaveEventId)
            null
        } catch (error: Throwable) {
            reportDelayedLeaveError(error)
            membershipClient.leaveOwnLegacyMembership(
                slot = configuration.slot,
                roomVersion = configuration.roomVersion
            )
        }
    }

    private fun reportDelayedLeaveError(error: Throwable) {
        if (!isDelayedEventsUnsupported(error)) {
            onError?.invoke(error)
        }
    }

    private fun isDelayedEventsUnsupported(error: Throwable): Boolean {
        return error is MatrixRtcSessionDelayedEventException.Unsupported
    }

    private fun membershipExpiryRefreshDelayMillis(): Long {
        val snapshot = withStateLock {
            ExpiryDelaySnapshot(
                ownMembership = ownMembership,
                membershipExpiryRefreshIteration = membershipExpiryRefreshIteration
            )
        }
        val ownMembership = snapshot.ownMembership ?: return 0
        val nextExpiryTimestamp = membershipExpiryTimestamp(
            createdTimestamp = ownMembership.createdTimestamp,
            iteration = snapshot.membershipExpiryRefreshIteration
        )
        val refreshTimestamp = nextExpiryTimestamp - effectiveMembershipEventExpiryHeadroomMillis()
        return maxOf(0, refreshTimestamp - timestampProvider())
    }

    private fun effectiveMembershipEventExpiryHeadroomMillis(): Long {
        return configuration.membershipEventExpiryHeadroomMillis
            .coerceAtLeast(0)
            .coerceAtMost((configuration.expires - 1).coerceAtLeast(0))
    }

    private fun membershipExpiryTimestamp(createdTimestamp: Long, iteration: Long): Long {
        val expires = membershipEventExpires(iteration)
        return if (Long.MAX_VALUE - createdTimestamp < expires) {
            Long.MAX_VALUE
        } else {
            createdTimestamp + expires
        }
    }

    private fun membershipEventExpires(iteration: Long): Long {
        if (configuration.expires <= 0 || iteration <= 0) {
            return 0
        }
        return if (Long.MAX_VALUE / configuration.expires < iteration) {
            Long.MAX_VALUE
        } else {
            configuration.expires * iteration
        }
    }

    private fun <T> withStateLock(operation: MutableState.() -> T): T {
        return synchronized(stateLock) {
            mutableState.operation()
        }
    }

    private data class MutableState(
        var state: MatrixRtcSessionState = MatrixRtcSessionState.IDLE,
        var ownMembership: MatrixRtcCallMembership? = null,
        var memberships: List<MatrixRtcCallMembership> = emptyList(),
        var mediaKeyManager: MatrixRtcMediaKeyManager? = null,
        var membershipExpiryRefreshJob: Job? = null,
        var membershipExpiryRefreshIteration: Long = 1,
        var delayedLeaveEventId: String? = null,
        var delayedLeaveRefreshJob: Job? = null
    )

    private data class StateSnapshot(
        val state: MatrixRtcSessionState,
        val ownMembership: MatrixRtcCallMembership?,
        val mediaKeyManager: MatrixRtcMediaKeyManager?
    )

    private data class ExpiryRefreshSnapshot(
        val state: MatrixRtcSessionState,
        val ownMembership: MatrixRtcCallMembership?,
        val mediaKeyManager: MatrixRtcMediaKeyManager?,
        val membershipExpiryRefreshIteration: Long
    )

    private data class LeaveSnapshot(
        val mediaKeyManager: MatrixRtcMediaKeyManager?,
        val delayedLeaveEventId: String?
    )

    private data class DelayedLeaveSnapshot(
        val state: MatrixRtcSessionState,
        val delayedLeaveEventId: String?
    )

    private data class ExpiryDelaySnapshot(
        val ownMembership: MatrixRtcCallMembership?,
        val membershipExpiryRefreshIteration: Long
    )

    companion object {
        fun membershipsIncluding(
            memberships: List<MatrixRtcCallMembership>,
            ownMembership: MatrixRtcCallMembership
        ): List<MatrixRtcCallMembership> {
            if (memberships.any { it.identity == ownMembership.identity }) {
                return memberships
            }
            return (memberships + ownMembership).sortedWith(
                compareBy<MatrixRtcCallMembership> { it.createdTimestamp }
                    .thenBy { it.eventId }
            )
        }
    }
}

private class MatrixRtcEarlyReceivedKeyBuffer {
    private val lock = Any()
    private val results = mutableListOf<Result<MatrixRtcReceivedCallEncryptionKey>>()
    private var forwardingHandler: ((Result<MatrixRtcReceivedCallEncryptionKey>) -> Unit)? = null

    fun append(result: Result<MatrixRtcReceivedCallEncryptionKey>) {
        val handler = synchronized(lock) {
            forwardingHandler ?: run {
                results += result
                return
            }
        }
        handler(result)
    }

    fun drain(
        forwardingHandler: (Result<MatrixRtcReceivedCallEncryptionKey>) -> Unit
    ): List<Result<MatrixRtcReceivedCallEncryptionKey>> {
        return synchronized(lock) {
            val drained = results.toList()
            results.clear()
            this.forwardingHandler = forwardingHandler
            drained
        }
    }
}
