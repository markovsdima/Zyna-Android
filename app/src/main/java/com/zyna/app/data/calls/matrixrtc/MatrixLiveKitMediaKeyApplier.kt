package com.zyna.app.data.calls.matrixrtc

import io.livekit.android.e2ee.KeyProvider
import java.util.Base64

interface MatrixRtcMediaKeyApplier {
    fun applyMediaKey(key: MatrixRtcMediaKey)
}

class MatrixLiveKitMediaKeyApplier(
    private val keyProvider: KeyProvider
) : MatrixRtcMediaKeyApplier {
    init {
        require(!keyProvider.enableSharedKey) {
            "MatrixRTC LiveKit media keys require per-participant key mode"
        }
    }

    override fun applyMediaKey(key: MatrixRtcMediaKey) {
        require(key.rtcBackendIdentity.isNotEmpty()) {
            "MatrixRTC media key is missing a LiveKit participant identity"
        }

        val rawKey = Base64.getDecoder().decode(key.keyBase64Encoded)
        MatrixRtcCallDebugLog.d(
            "applyMediaKey participantId=${key.rtcBackendIdentity} " +
                "keyIndex=${key.keyIndex} userId=${key.membership.userId} " +
                "deviceId=${key.membership.deviceId} memberId=${key.membership.memberId}"
        )
        keyProvider.setKey(
            key = rawKey,
            participantId = key.rtcBackendIdentity,
            keyIndex = key.keyIndex
        )
    }
}

