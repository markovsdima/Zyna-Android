package com.zyna.app.ui.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalRouteTest {
    @Test
    fun parser_requiresExplicitOpenRoomAction() {
        val command = parseExternalRouteCommand(
            action = IntentAction.Main,
            commandId = "notification:event",
            roomId = ROOM_ID,
            eventId = EVENT_ID
        )

        assertNull(command)
    }

    @Test
    fun parser_createsOpenRoomCommandAndNormalizesBlankEvent() {
        val withEvent = parseExternalRouteCommand(
            action = ExternalRouteIntents.ACTION_OPEN_ROOM,
            commandId = "notification:event",
            roomId = ROOM_ID,
            eventId = EVENT_ID
        )
        val withoutEvent = parseExternalRouteCommand(
            action = ExternalRouteIntents.ACTION_OPEN_ROOM,
            commandId = "notification:room",
            roomId = ROOM_ID,
            eventId = ""
        )

        assertEquals(
            ExternalRouteCommand(
                id = "notification:event",
                route = ExternalRoute.OpenRoom(ROOM_ID, EVENT_ID)
            ),
            withEvent
        )
        assertEquals(
            ExternalRouteCommand(
                id = "notification:room",
                route = ExternalRoute.OpenRoom(ROOM_ID, null)
            ),
            withoutEvent
        )
    }

    @Test
    fun parser_rejectsMalformedCommands() {
        assertNull(
            parseExternalRouteCommand(
                action = ExternalRouteIntents.ACTION_OPEN_ROOM,
                commandId = "",
                roomId = ROOM_ID,
                eventId = EVENT_ID
            )
        )
        assertNull(
            parseExternalRouteCommand(
                action = ExternalRouteIntents.ACTION_OPEN_ROOM,
                commandId = "notification:event",
                roomId = "",
                eventId = EVENT_ID
            )
        )
    }

    @Test
    fun coordinator_doesNothingWithoutExplicitCommand() {
        val coordinator = ExternalRouteCoordinator()

        assertNull(
            coordinator.takeIfReady(
                canOpenRooms = true,
                availableRoomIds = setOf(ROOM_ID)
            )
        )
    }

    @Test
    fun coordinator_waitsForMainSessionAndRoom() {
        val coordinator = ExternalRouteCoordinator()
        val command = command(id = "notification:event")

        assertTrue(coordinator.accept(command))
        assertNull(
            coordinator.takeIfReady(
                canOpenRooms = false,
                availableRoomIds = setOf(ROOM_ID)
            )
        )
        assertNull(
            coordinator.takeIfReady(
                canOpenRooms = true,
                availableRoomIds = emptySet()
            )
        )
        assertEquals(
            command,
            coordinator.takeIfReady(
                canOpenRooms = true,
                availableRoomIds = setOf(ROOM_ID)
            )
        )
    }

    @Test
    fun coordinator_consumesCommandOnlyOnce() {
        val coordinator = ExternalRouteCoordinator()
        val command = command(id = "notification:event")

        assertTrue(coordinator.accept(command))
        assertEquals(
            command,
            coordinator.takeIfReady(
                canOpenRooms = true,
                availableRoomIds = setOf(ROOM_ID)
            )
        )
        assertFalse(coordinator.accept(command))
        assertNull(
            coordinator.takeIfReady(
                canOpenRooms = true,
                availableRoomIds = setOf(ROOM_ID)
            )
        )
    }

    @Test
    fun coordinator_keepsLatestPendingExternalCommand() {
        val coordinator = ExternalRouteCoordinator()
        val first = command(id = "notification:first", eventId = "\$first")
        val latest = command(id = "notification:latest", eventId = "\$latest")

        assertTrue(coordinator.accept(first))
        assertTrue(coordinator.accept(latest))
        assertEquals(
            latest,
            coordinator.takeIfReady(
                canOpenRooms = true,
                availableRoomIds = setOf(ROOM_ID)
            )
        )
    }

    @Test
    fun deliveryTracker_rejectsCommandAfterActivityStateRestoration() {
        val original = ExternalRouteDeliveryTracker()

        assertTrue(original.markForDelivery("notification:event"))

        val restored = ExternalRouteDeliveryTracker(original.snapshot())

        assertFalse(restored.markForDelivery("notification:event"))
        assertTrue(restored.markForDelivery("notification:other"))
    }

    private fun command(
        id: String,
        eventId: String = EVENT_ID
    ): ExternalRouteCommand {
        return ExternalRouteCommand(
            id = id,
            route = ExternalRoute.OpenRoom(
                roomId = ROOM_ID,
                eventId = eventId
            )
        )
    }

    private object IntentAction {
        const val Main = "android.intent.action.MAIN"
    }

    private companion object {
        const val ROOM_ID = "!room:example.org"
        const val EVENT_ID = "\$event"
    }
}
