package com.zyna.app.ui.app

sealed interface ExternalRoute {
    data class OpenRoom(
        val roomId: String,
        val eventId: String?
    ) : ExternalRoute
}

data class ExternalRouteCommand(
    val id: String,
    val route: ExternalRoute
)

internal class ExternalRouteDeliveryTracker(
    restoredCommandIds: Collection<String> = emptyList()
) {
    private val deliveredCommandIds = LinkedHashSet<String>()

    init {
        restoredCommandIds
            .filter { it.isNotBlank() }
            .takeLast(MAX_REMEMBERED_COMMANDS)
            .forEach(deliveredCommandIds::add)
    }

    @Synchronized
    fun markForDelivery(commandId: String): Boolean {
        if (commandId.isBlank() || commandId in deliveredCommandIds) {
            return false
        }
        remember(commandId)
        return true
    }

    @Synchronized
    fun snapshot(): ArrayList<String> = ArrayList(deliveredCommandIds)

    private fun remember(commandId: String) {
        deliveredCommandIds += commandId
        while (deliveredCommandIds.size > MAX_REMEMBERED_COMMANDS) {
            deliveredCommandIds.remove(deliveredCommandIds.first())
        }
    }

    private companion object {
        const val MAX_REMEMBERED_COMMANDS = 64
    }
}

internal class ExternalRouteCoordinator {
    private var pendingCommand: ExternalRouteCommand? = null
    private val consumedCommandIds = LinkedHashSet<String>()

    @Synchronized
    fun hasPendingCommand(): Boolean = pendingCommand != null

    @Synchronized
    fun accept(command: ExternalRouteCommand): Boolean {
        if (command.id.isBlank() || command.id in consumedCommandIds) {
            return false
        }
        val route = command.route
        if (route is ExternalRoute.OpenRoom && route.roomId.isBlank()) {
            return false
        }
        if (pendingCommand?.id == command.id) {
            return false
        }

        pendingCommand = command
        return true
    }

    @Synchronized
    fun takeIfReady(
        canOpenRooms: Boolean,
        availableRoomIds: Set<String>
    ): ExternalRouteCommand? {
        val command = pendingCommand ?: return null
        if (!canOpenRooms) {
            return null
        }
        val isAvailable = when (val route = command.route) {
            is ExternalRoute.OpenRoom -> route.roomId in availableRoomIds
        }
        if (!isAvailable) {
            return null
        }

        pendingCommand = null
        rememberConsumed(command.id)
        return command
    }

    private fun rememberConsumed(commandId: String) {
        consumedCommandIds += commandId
        while (consumedCommandIds.size > MAX_REMEMBERED_COMMANDS) {
            consumedCommandIds.remove(consumedCommandIds.first())
        }
    }

    private companion object {
        const val MAX_REMEMBERED_COMMANDS = 64
    }
}

internal fun parseExternalRouteCommand(
    action: String?,
    commandId: String?,
    roomId: String?,
    eventId: String?
): ExternalRouteCommand? {
    if (action != ExternalRouteIntents.ACTION_OPEN_ROOM) {
        return null
    }
    val normalizedCommandId = commandId?.takeIf { it.isNotBlank() } ?: return null
    val normalizedRoomId = roomId?.takeIf { it.isNotBlank() } ?: return null
    return ExternalRouteCommand(
        id = normalizedCommandId,
        route = ExternalRoute.OpenRoom(
            roomId = normalizedRoomId,
            eventId = eventId?.takeIf { it.isNotBlank() }
        )
    )
}
