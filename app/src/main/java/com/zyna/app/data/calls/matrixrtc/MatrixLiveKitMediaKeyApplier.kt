package com.zyna.app.data.calls.matrixrtc

import io.livekit.android.e2ee.KeyProvider
import java.util.Base64

interface MatrixRtcMediaKeyApplier {
    fun applyMediaKey(key: MatrixRtcMediaKey)
}

class MatrixLiveKitMediaKeyApplier(
    private val keyProvider: KeyProvider
) : MatrixRtcMediaKeyApplier {
    override fun applyMediaKey(key: MatrixRtcMediaKey) {
        require(key.rtcBackendIdentity.isNotEmpty()) {
            "MatrixRTC media key is missing a LiveKit participant identity"
        }

        val rawKey = Base64.getDecoder().decode(key.keyBase64Encoded)
        keyProvider.setKey(
            key = rawKey,
            participantId = key.rtcBackendIdentity,
            keyIndex = key.keyIndex
        )
    }
}

