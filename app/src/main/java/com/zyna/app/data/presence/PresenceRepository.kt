package com.zyna.app.data.presence

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

class PresenceRepository(
    private val settingsStore: PresenceSettingsStore,
    private val zynaBackend: PresenceBackend,
    private val matrixBackend: PresenceBackend,
    private val sessionProvider: suspend () -> PresenceSession?
) {
    private val _statuses = MutableStateFlow<Map<String, UserPresenceStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, UserPresenceStatus>> = _statuses.asStateFlow()

    private val foreground = MutableStateFlow(false)
    private val sessionGate = MutableStateFlow(PresenceSessionGate())
    private val registrations = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    private var startJob: Job? = null
    private var connectionJob: Job? = null
    private var activeBackend: PresenceBackend? = null

    fun start(scope: CoroutineScope) {
        if (startJob?.isActive == true) {
            return
        }
        startJob = scope.launch {
            launch {
                combine(
                    settingsStore.selectedProvider,
                    foreground,
                    sessionGate
                ) { provider, isForeground, gate ->
                    ConnectionIntent(
                        provider = provider,
                        sessionUserId = gate.userId,
                        sessionAllowed = gate.allowed,
                        shouldConnect = isForeground &&
                            gate.allowed &&
                            provider != PresenceProviderMode.OFF
                    )
                }
                    .distinctUntilChanged()
                    .collect { intent ->
                        applyConnectionIntent(scope, intent)
                    }
            }

            launch {
                registrations
                    .collect { registrationMap ->
                        val subscribedUserIds = registrationMap.unionUserIds()
                        _statuses.update { current ->
                            if (subscribedUserIds.isEmpty()) {
                                emptyMap()
                            } else {
                                current.filterKeys(subscribedUserIds::contains)
                            }
                        }
                        runCatching {
                            activeBackend?.subscribe(subscribedUserIds)
                        }.onFailure { error ->
                            Log.w(TAG, "Failed to update presence subscription", error)
                        }
                    }
            }
        }
    }

    fun setForeground(isForeground: Boolean) {
        foreground.value = isForeground
    }

    fun setSessionContext(userId: String?, allowed: Boolean) {
        val normalizedUserId = userId?.takeIf { it.isNotBlank() }
        val nextGate = PresenceSessionGate(
            userId = normalizedUserId,
            allowed = allowed && normalizedUserId != null
        )
        val previousGate = sessionGate.value
        val switchedUsers = previousGate.userId != null &&
            nextGate.userId != null &&
            previousGate.userId != nextGate.userId

        sessionGate.value = nextGate
        if (!nextGate.allowed || switchedUsers) {
            registrations.value = emptyMap()
        }
        if (!nextGate.allowed || previousGate.userId != nextGate.userId) {
            _statuses.value = emptyMap()
        }
    }

    fun register(tag: String, userIds: Set<String>) {
        registrations.update { current ->
            if (userIds.isEmpty()) {
                current - tag
            } else {
                current + (tag to userIds)
            }
        }
    }

    fun unregister(tag: String) {
        registrations.update { it - tag }
    }

    private fun applyConnectionIntent(scope: CoroutineScope, intent: ConnectionIntent) {
        connectionJob?.cancel()
        connectionJob = null
        activeBackend?.disconnect()
        activeBackend = null

        if (!intent.shouldConnect) {
            if (intent.provider == PresenceProviderMode.OFF || !intent.sessionAllowed) {
                _statuses.value = emptyMap()
            } else {
                markOnlineStatusesUnknown()
            }
            return
        }

        val backend = backendFor(intent.provider)
        activeBackend = backend
        val expectedUserId = intent.sessionUserId ?: return
        connectionJob = scope.launch {
            runConnectionLoop(backend, expectedUserId)
        }
    }

    private suspend fun runConnectionLoop(backend: PresenceBackend, expectedUserId: String) {
        var attempt = 0
        while (coroutineContext.isActive) {
            val session = sessionProvider()
            if (session == null || session.userId != expectedUserId) {
                markOnlineStatusesUnknown()
                delay(RECONNECT_MISSING_SESSION_DELAY_MS)
                continue
            }

            coroutineScope {
                val disconnected = CompletableDeferred<Unit>()
                val eventJob = launch {
                    backend.events.collect { event ->
                        when (event) {
                            is PresenceBackendEvent.Snapshot -> applySnapshot(event.statuses)
                            is PresenceBackendEvent.Change -> applyChange(event.status)
                            PresenceBackendEvent.Disconnected -> {
                                if (!disconnected.isCompleted) {
                                    disconnected.complete(Unit)
                                }
                            }
                        }
                    }
                }

                try {
                    backend.connect(session)
                    backend.subscribe(registrations.value.unionUserIds())
                    attempt = 0
                    disconnected.await()
                    markOnlineStatusesUnknown()
                } catch (error: CancellationException) {
                    eventJob.cancel()
                    backend.disconnect()
                    throw error
                } catch (error: Throwable) {
                    Log.w(TAG, "Presence connection failed", error)
                    markOnlineStatusesUnknown()
                } finally {
                    eventJob.cancel()
                    backend.disconnect()
                }
            }

            attempt += 1
            delay(reconnectDelayMillis(attempt))
        }
    }

    private fun applySnapshot(statuses: Map<String, UserPresenceStatus>) {
        if (statuses.isEmpty()) {
            return
        }
        _statuses.update { current -> current + statuses }
    }

    private fun applyChange(status: UserPresenceStatus) {
        _statuses.update { current -> current + (status.userId to status) }
    }

    private fun markOnlineStatusesUnknown() {
        val nowElapsed = SystemClock.elapsedRealtime()
        _statuses.update { current ->
            current.mapValues { (_, status) ->
                if (!status.isOnline) {
                    status
                } else {
                    status.copy(
                        availability = PresenceAvailability.UNKNOWN,
                        source = PresenceSource.NONE,
                        receivedAtElapsedMillis = nowElapsed
                    )
                }
            }
        }
    }

    private fun backendFor(provider: PresenceProviderMode): PresenceBackend {
        return when (provider) {
            PresenceProviderMode.ZYNA_REALTIME -> zynaBackend
            PresenceProviderMode.MATRIX_STANDARD -> matrixBackend
            PresenceProviderMode.OFF -> matrixBackend
        }
    }

    private fun Map<String, Set<String>>.unionUserIds(): Set<String> {
        return values.fold(mutableSetOf()) { acc, ids ->
            acc.apply { addAll(ids) }
        }
    }

    private fun reconnectDelayMillis(attempt: Int): Long {
        val exponentialSeconds = (1 shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(30)
        val jitterMillis = (0..500).random().toLong()
        return exponentialSeconds * 1_000L + jitterMillis
    }

    private data class ConnectionIntent(
        val provider: PresenceProviderMode,
        val sessionUserId: String?,
        val sessionAllowed: Boolean,
        val shouldConnect: Boolean
    )

    private data class PresenceSessionGate(
        val userId: String? = null,
        val allowed: Boolean = false
    )

    private companion object {
        const val TAG = "ZynaPresence"
        const val RECONNECT_MISSING_SESSION_DELAY_MS = 2_000L
    }
}
