package com.zyna.app.data.presence

enum class PresenceAvailability {
    ONLINE,
    UNAVAILABLE,
    OFFLINE,
    UNKNOWN
}

enum class PresenceSource {
    ZYNA_WS,
    MATRIX,
    NONE
}

data class UserPresenceStatus(
    val userId: String,
    val availability: PresenceAvailability,
    val lastSeenAtMillis: Long?,
    val currentlyActive: Boolean?,
    val source: PresenceSource,
    val receivedAtElapsedMillis: Long
) {
    val isOnline: Boolean
        get() = availability == PresenceAvailability.ONLINE
}

data class PresenceSession(
    val homeserverUrl: String,
    val accessToken: String,
    val userId: String
)

enum class PresenceProviderMode(
    val id: String
) {
    ZYNA_REALTIME("zyna_realtime"),
    MATRIX_STANDARD("matrix_standard"),
    OFF("off");

    companion object {
        val default: PresenceProviderMode = ZYNA_REALTIME

        fun fromId(id: String?): PresenceProviderMode {
            return entries.firstOrNull { it.id == id } ?: default
        }
    }
}

enum class PresenceConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

sealed interface PresenceBackendEvent {
    data class Snapshot(
        val statuses: Map<String, UserPresenceStatus>
    ) : PresenceBackendEvent

    data class Change(
        val status: UserPresenceStatus
    ) : PresenceBackendEvent

    data object Disconnected : PresenceBackendEvent
}
