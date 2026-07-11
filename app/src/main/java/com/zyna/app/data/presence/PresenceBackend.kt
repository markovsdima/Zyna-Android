package com.zyna.app.data.presence

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

interface PresenceBackend {
    val events: SharedFlow<PresenceBackendEvent>
    val connectionStatus: StateFlow<PresenceConnectionStatus>

    suspend fun connect(session: PresenceSession)
    suspend fun subscribe(userIds: Set<String>)
    fun disconnect()
}

class MatrixPresenceBackendStub : PresenceBackend {
    private val _events = MutableSharedFlow<PresenceBackendEvent>(extraBufferCapacity = 1)
    private val _connectionStatus = MutableStateFlow(PresenceConnectionStatus.DISCONNECTED)

    override val events: SharedFlow<PresenceBackendEvent>
        get() = _events.asSharedFlow()
    override val connectionStatus: StateFlow<PresenceConnectionStatus>
        get() = _connectionStatus.asStateFlow()

    override suspend fun connect(session: PresenceSession) = Unit
    override suspend fun subscribe(userIds: Set<String>) = Unit
    override fun disconnect() = Unit
}
