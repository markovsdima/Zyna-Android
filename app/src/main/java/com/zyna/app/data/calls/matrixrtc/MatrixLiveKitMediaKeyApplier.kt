package com.zyna.app.data.calls.matrixrtc

import io.livekit.android.e2ee.KeyProvider
import java.util.Base64

interface MatrixRtcMediaKeyApplier {
    fun applyMediaKey(key: MatrixRtcMediaKey)
}

class MatrixRtcLiveKitMediaKeyException(message: String) : IllegalArgumentException(message)

class MatrixLiveKitMediaKeyApplier(
    private val keyProvider: KeyProvider
) : MatrixRtcMediaKeyApplier {
    init {
        require(!keyProvider.enableSharedKey) {
            "MatrixRTC LiveKit media keys require per-participant key mode"
        }
    }

    override fun applyMediaKey(key: MatrixRtcMediaKey) {
        if (key.rtcBackendIdentity.isEmpty()) {
            throw MatrixRtcLiveKitMediaKeyException(
                "MatrixRTC media key is missing a LiveKit participant identity"
            )
        }
        if (key.keyIndex !in 0 until KEY_RING_SIZE) {
            throw MatrixRtcLiveKitMediaKeyException(
                "MatrixRTC media key index ${key.keyIndex} is outside LiveKit key ring"
            )
        }
        if (key.keyIndex == LIVEKIT_UNSUPPORTED_OUTBOUND_KEY_INDEX) {
            throw MatrixRtcLiveKitMediaKeyException(
                "MatrixRTC media key index ${key.keyIndex} is not supported by LiveKit WebRTC"
            )
        }

        val rawKey = rawKeyFromBase64(key.keyBase64Encoded)
        if (rawKey.size != KEY_BYTE_COUNT) {
            throw MatrixRtcLiveKitMediaKeyException(
                "MatrixRTC media key has ${rawKey.size} bytes, expected $KEY_BYTE_COUNT"
            )
        }
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

    private fun rawKeyFromBase64(value: String): ByteArray {
        val normalized = value
            .replace('-', '+')
            .replace('_', '/')
            .let { base64 ->
                val remainder = base64.length % 4
                if (remainder == 0) {
                    base64
                } else {
                    base64 + "=".repeat(4 - remainder)
                }
            }
        return runCatching {
            Base64.getDecoder().decode(normalized)
        }.getOrElse { error ->
            throw MatrixRtcLiveKitMediaKeyException(
                "MatrixRTC media key is not valid base64: ${error.message}"
            )
        }
    }

    private companion object {
        private const val KEY_BYTE_COUNT = 16
        private const val KEY_RING_SIZE = 256
        private const val LIVEKIT_UNSUPPORTED_OUTBOUND_KEY_INDEX = 255
    }
}
