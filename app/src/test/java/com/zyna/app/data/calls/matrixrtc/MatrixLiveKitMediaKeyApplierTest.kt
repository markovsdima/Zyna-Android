package com.zyna.app.data.calls.matrixrtc

import io.livekit.android.e2ee.KeyProvider
import livekit.org.webrtc.FrameCryptorKeyProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MatrixLiveKitMediaKeyApplierTest {
    @Test
    fun appliesMediaKeyToLiveKitKeyProviderAsRawBytes() {
        val keyProvider = FakeLiveKitKeyProvider()
        val applier = MatrixLiveKitMediaKeyApplier(keyProvider)

        applier.applyMediaKey(
            MatrixRtcMediaKey(
                keyBase64Encoded = "AQIDBAUGBwgJCgsMDQ4PEA==",
                keyIndex = 7,
                membership = MatrixRtcMembershipIdentity(
                    userId = "@alice:example.org",
                    deviceId = "ALICEDEVICE",
                    memberId = "@alice:example.org:ALICEDEVICE"
                ),
                rtcBackendIdentity = "@alice:example.org:ALICEDEVICE"
            )
        )

        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16),
            keyProvider.byteKey
        )
        assertEquals("@alice:example.org:ALICEDEVICE", keyProvider.participantId)
        assertEquals(7, keyProvider.keyIndex)
    }

    @Test
    fun rejectsSharedKeyModeProvider() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            MatrixLiveKitMediaKeyApplier(
                FakeLiveKitKeyProvider(enableSharedKey = true)
            )
        }

        assertEquals(
            "MatrixRTC LiveKit media keys require per-participant key mode",
            error.message
        )
    }
}

private class FakeLiveKitKeyProvider(
    override var enableSharedKey: Boolean = false
) : KeyProvider {
    var byteKey: ByteArray? = null
    var participantId: String? = null
    var keyIndex: Int? = null

    override val rtcKeyProvider: FrameCryptorKeyProvider
        get() = error("Not used")

    override fun setSharedKey(key: String, keyIndex: Int?): Boolean = true

    override fun setSharedKey(key: ByteArray, keyIndex: Int?): Boolean = true

    override fun ratchetSharedKey(keyIndex: Int?): ByteArray = ByteArray(0)

    override fun exportSharedKey(keyIndex: Int?): ByteArray = ByteArray(0)

    override fun setKey(key: String, participantId: String?, keyIndex: Int?) = Unit

    override fun setKey(key: ByteArray, participantId: String?, keyIndex: Int?) {
        this.byteKey = key
        this.participantId = participantId
        this.keyIndex = keyIndex
    }

    override fun ratchetKey(participantId: String, keyIndex: Int?): ByteArray = ByteArray(0)

    override fun exportKey(participantId: String, keyIndex: Int?): ByteArray = ByteArray(0)

    override fun setSifTrailer(trailer: ByteArray) = Unit

    override fun getLatestKeyIndex(participantId: String): Int = 0
}

