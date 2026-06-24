package com.zyna.app.data.calls.matrixrtc

import android.content.Context
import com.zyna.app.data.matrix.MatrixClientService

class MatrixClientNativeMatrixRtcCallEnvironment(
    private val matrixClientService: MatrixClientService,
    context: Context
) : NativeMatrixRtcCallEnvironment {
    private val liveKitRoomSessionFactory = AndroidMatrixRtcLiveKitRoomSessionFactory(context)

    override fun ownDevice(): MatrixRtcOwnDevice {
        return matrixClientService.matrixRtcOwnDevice()
    }

    override suspend fun isRoomEncrypted(roomId: String): Boolean {
        return matrixClientService.matrixRtcMediaEncryptionEnabled(roomId)
    }

    override fun liveKitFocusClient(): MatrixRtcLiveKitFocusClient {
        return matrixClientService.matrixRtcLiveKitFocusClient()
    }

    override fun liveKitRoomSession(
        mediaEncryptionMode: MatrixRtcLiveKitMediaEncryptionMode,
        onEvent: (MatrixRtcLiveKitRoomSessionEvent) -> Unit
    ): MatrixRtcLiveKitRoomSession {
        return liveKitRoomSessionFactory.create(
            mediaEncryptionMode = mediaEncryptionMode,
            onEvent = onEvent
        )
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

    override fun subscribeToCallDeclineEvents(
        roomId: String,
        notificationEventId: String,
        onDecline: (declinerUserId: String) -> Unit
    ): MatrixRtcCancellable {
        return matrixClientService.subscribeToMatrixRtcCallDeclineEvents(
            roomId = roomId,
            notificationEventId = notificationEventId,
            onDecline = onDecline
        )
    }
}
