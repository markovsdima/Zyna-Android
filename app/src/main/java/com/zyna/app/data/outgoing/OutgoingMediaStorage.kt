package com.zyna.app.data.outgoing

import java.io.File
import java.util.UUID

object OutgoingMediaStorage {
    const val DIRECTORY_NAME = "outgoing_media"

    fun directory(filesDir: File): File {
        return File(filesDir, DIRECTORY_NAME).apply { mkdirs() }
    }

    fun newVoiceFile(filesDir: File): File {
        return File(directory(filesDir), "voice-${System.currentTimeMillis()}-${UUID.randomUUID()}.m4a")
    }
}
