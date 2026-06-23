package com.zyna.app.data.calls.matrixrtc

import com.zyna.app.data.matrix.MatrixClientService

class MatrixClientNativeMatrixRtcCallEnvironment(
    private val matrixClientService: MatrixClientService
) : NativeMatrixRtcCallEnvironment {
    override fun ownDevice(): MatrixRtcOwnDevice {
        return matrixClientService.matrixRtcOwnDevice()
    }

    override suspend fun isRoomEncrypted(roomId: String): Boolean {
        return matrixClientService.matrixRtcMediaEncryptionEnabled(roomId)
    }

    override fun liveKitFocusClient(): MatrixRtcLiveKitFocusClient {
        return matrixClientService.matrixRtcLiveKitFocusClient()
    }

    override fun sessionMembershipClient(roomId: String): MatrixRtcSessionMembershipClient {
        return matrixClientService.matrixRtcSessionMembershipClient(roomId)
    }

    override fun toDeviceClient(): MatrixRtcCustomToDeviceEncrypting {
        return matrixClientService.matrixRtcToDeviceClient()
    }

    override fun callNotificationClient(roomId: String): MatrixRtcCallNotificationClient {
        return matrixClientService.matrixRtcCallNotificationClient(roomId)
    }
}

