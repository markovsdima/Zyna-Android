package com.zyna.app.data.calls.matrixrtc

data class MatrixRtcIncomingCall(
    val eventId: String,
    val roomId: String,
    val senderId: String,
    val senderName: String,
    val roomName: String?,
    val isAudioCall: Boolean,
    val expiresAtMillis: Long
) {
    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return expiresAtMillis <= nowMillis
    }
}
