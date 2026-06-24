package com.zyna.app.data.calls.matrixrtc

import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.CustomToDeviceEvent
import org.matrix.rustcomponents.sdk.CustomToDeviceEventEncryptionInfo
import org.matrix.rustcomponents.sdk.CustomToDeviceEventListener
import org.matrix.rustcomponents.sdk.CustomToDeviceEventSendFailure
import org.matrix.rustcomponents.sdk.CustomToDeviceEventSendFailureReason
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.ToDeviceTarget

class MatrixRustSdkRtcToDeviceClient(
    private val client: Client
) : MatrixRtcCustomToDeviceEncrypting {
    override suspend fun encryptAndSendRawToDevice(
        eventType: String,
        targets: List<MatrixRtcToDeviceTarget>,
        contentJson: String
    ): List<MatrixRtcCustomToDeviceSendFailure> {
        return client.encryption()
            .encryptAndSendRawToDevice(
                eventType = eventType,
                targets = targets.map { target ->
                    ToDeviceTarget(userId = target.userId, deviceId = target.deviceId)
                },
                contentJson = contentJson
            )
            .map { failure -> failure.toMatrixRtcFailure() }
    }

    override fun addCustomToDeviceEventListener(
        eventType: String,
        encryptedOnly: Boolean,
        listener: (MatrixRtcCustomToDeviceEvent) -> Unit
    ): MatrixRtcCancellable {
        val handle = client.addCustomToDeviceEventListener(
            eventType = eventType,
            encryptedOnly = encryptedOnly,
            listener = object : CustomToDeviceEventListener {
                override fun onEvent(event: CustomToDeviceEvent) {
                    listener(event.toMatrixRtcEvent())
                }
            }
        )
        return MatrixRustSdkTaskCancellable(handle)
    }
}

private class MatrixRustSdkTaskCancellable(
    private val handle: TaskHandle
) : MatrixRtcCancellable {
    override fun cancel() {
        handle.cancel()
        handle.destroy()
    }
}

private fun CustomToDeviceEvent.toMatrixRtcEvent(): MatrixRtcCustomToDeviceEvent {
    return MatrixRtcCustomToDeviceEvent(
        eventType = eventType,
        sender = sender,
        contentJson = contentJson,
        rawJson = rawJson,
        encryptionInfo = encryptionInfo?.toMatrixRtcEncryptionInfo()
    )
}

private fun CustomToDeviceEventEncryptionInfo.toMatrixRtcEncryptionInfo(): MatrixRtcCustomToDeviceEncryptionInfo {
    return MatrixRtcCustomToDeviceEncryptionInfo(
        sender = sender,
        senderDevice = senderDevice,
        senderCurve25519KeyBase64 = senderCurve25519KeyBase64,
        senderVerified = senderVerified
    )
}

private fun CustomToDeviceEventSendFailure.toMatrixRtcFailure(): MatrixRtcCustomToDeviceSendFailure {
    return MatrixRtcCustomToDeviceSendFailure(
        userId = userId,
        deviceId = deviceId,
        reason = when (reason) {
            CustomToDeviceEventSendFailureReason.MISSING_DEVICE ->
                MatrixRtcCustomToDeviceSendFailureReason.MISSING_DEVICE
            CustomToDeviceEventSendFailureReason.WITHHELD ->
                MatrixRtcCustomToDeviceSendFailureReason.WITHHELD
            CustomToDeviceEventSendFailureReason.SEND_FAILED ->
                MatrixRtcCustomToDeviceSendFailureReason.SEND_FAILED
        }
    )
}

