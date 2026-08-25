package com.zyna.app.ui.app

import android.content.Intent

object ExternalRouteIntents {
    internal const val ACTION_OPEN_ROOM = "com.zyna.app.action.OPEN_ROOM"
    private const val EXTRA_COMMAND_ID = "com.zyna.app.extra.EXTERNAL_COMMAND_ID"
    private const val EXTRA_ROOM_ID = "com.zyna.app.extra.EXTERNAL_ROOM_ID"
    private const val EXTRA_EVENT_ID = "com.zyna.app.extra.EXTERNAL_EVENT_ID"

    fun putOpenRoom(
        intent: Intent,
        commandId: String,
        roomId: String,
        eventId: String?
    ): Intent {
        return intent
            .setAction(ACTION_OPEN_ROOM)
            .putExtra(EXTRA_COMMAND_ID, commandId)
            .putExtra(EXTRA_ROOM_ID, roomId)
            .putExtra(EXTRA_EVENT_ID, eventId)
    }

    fun consume(intent: Intent?): ExternalRouteCommand? {
        if (intent?.action != ACTION_OPEN_ROOM) {
            return null
        }

        val command = parseExternalRouteCommand(
            action = intent.action,
            commandId = intent.getStringExtra(EXTRA_COMMAND_ID),
            roomId = intent.getStringExtra(EXTRA_ROOM_ID),
            eventId = intent.getStringExtra(EXTRA_EVENT_ID)
        )

        // Activity recreation must not replay a command that was already delivered.
        intent.action = null
        intent.removeExtra(EXTRA_COMMAND_ID)
        intent.removeExtra(EXTRA_ROOM_ID)
        intent.removeExtra(EXTRA_EVENT_ID)
        return command
    }
}
