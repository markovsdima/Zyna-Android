package com.zyna.app.data.outgoing

data class OutgoingVoiceDraft(
    val localPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMillis: Long,
    val waveform: List<Float>
)
