package com.zyna.app.data.calls.matrixrtc

interface MatrixRtcCancellable {
    fun cancel()
}

object MatrixRtcNoopCancellable : MatrixRtcCancellable {
    override fun cancel() = Unit
}

enum class MatrixRtcCustomToDeviceSendFailureReason {
    MISSING_DEVICE,
    WITHHELD,
    SEND_FAILED
}

data class MatrixRtcCustomToDeviceSendFailure(
    val userId: String,
    val deviceId: String,
    val reason: MatrixRtcCustomToDeviceSendFailureReason
)

data class MatrixRtcCustomToDeviceEncryptionInfo(
    val sender: String,
    val senderDevice: String?,
    val senderCurve25519KeyBase64: String?,
    val senderVerified: Boolean
)

data class MatrixRtcCustomToDeviceEvent(
    val eventType: String,
    val sender: String,
    val contentJson: String,
    val rawJson: String,
    val encryptionInfo: MatrixRtcCustomToDeviceEncryptionInfo?
)

interface MatrixRtcCustomToDeviceEncrypting {
    suspend fun encryptAndSendRawToDevice(
        eventType: String,
        targets: List<MatrixRtcToDeviceTarget>,
        contentJson: String
    ): List<MatrixRtcCustomToDeviceSendFailure>

    fun addCustomToDeviceEventListener(
        eventType: String,
        encryptedOnly: Boolean,
        listener: (MatrixRtcCustomToDeviceEvent) -> Unit
    ): MatrixRtcCancellable
}

data class MatrixRtcReceivedCallEncryptionKey(
    val sender: String,
    val membership: MatrixRtcMembershipIdentity,
    val keyBase64Encoded: String,
    val keyIndex: Int,
    val sentTimestamp: Long?,
    val encryptionInfo: MatrixRtcCustomToDeviceEncryptionInfo?
)

class MatrixRtcToDeviceKeyTransport(
    private val roomId: String,
    private val ownIdentity: MatrixRtcMembershipIdentity,
    private val client: MatrixRtcCustomToDeviceEncrypting,
    private val sentTimestampProvider: () -> Long = { System.currentTimeMillis() },
    onReceivedKey: ReceivedKeyHandler = ReceivedKeyHandler {}
) {
    fun interface ReceivedKeyHandler {
        fun onReceivedKey(result: Result<MatrixRtcReceivedCallEncryptionKey>)
    }

    private val lock = Any()
    private var onReceivedKey: ReceivedKeyHandler = onReceivedKey
    private var listenerToken: MatrixRtcCancellable? = null

    fun setReceivedKeyHandler(onReceivedKey: ReceivedKeyHandler) {
        synchronized(lock) {
            this.onReceivedKey = onReceivedKey
        }
    }

    fun start() {
        MatrixRtcCallDebugLog.d(
            "mediaKeyTransportStart roomId=$roomId ownUserId=${ownIdentity.userId} " +
                "ownDeviceId=${ownIdentity.deviceId} ownMemberId=${ownIdentity.memberId}"
        )
        replaceListenerToken(null)?.cancel()
        val token = client.addCustomToDeviceEventListener(
            eventType = MatrixRtcCallEncryptionKeysContent.EVENT_TYPE,
            encryptedOnly = true
        ) { event ->
            handle(event)
        }
        replaceListenerToken(token)?.cancel()
    }

    fun stop() {
        MatrixRtcCallDebugLog.d("mediaKeyTransportStop roomId=$roomId")
        replaceListenerToken(null)?.cancel()
    }

    suspend fun sendKey(
        keyBase64Encoded: String,
        index: Int,
        targets: List<MatrixRtcToDeviceTarget>
    ): List<MatrixRtcCustomToDeviceSendFailure> {
        MatrixRtcCallDebugLog.d(
            "mediaKeySend start keyIndex=$index targets=${targets.joinToString { it.debugSummary() }}"
        )
        val content = MatrixRtcCallEncryptionKeysContent(
            keys = MatrixRtcCallEncryptionKeysContent.Keys(index = index, key = keyBase64Encoded),
            member = MatrixRtcCallEncryptionKeysContent.Member(
                id = ownIdentity.memberId,
                claimedDeviceId = ownIdentity.deviceId
            ),
            roomId = roomId,
            sentTimestamp = sentTimestampProvider()
        )
        return client.encryptAndSendRawToDevice(
            eventType = MatrixRtcCallEncryptionKeysContent.EVENT_TYPE,
            targets = targets,
            contentJson = content.jsonString()
        ).also { failures ->
            MatrixRtcCallDebugLog.d(
                "mediaKeySend done keyIndex=$index targetCount=${targets.size} " +
                    "failureCount=${failures.size} failures=${failures.joinToString { it.debugSummary() }}"
            )
        }
    }

    private fun handle(event: MatrixRtcCustomToDeviceEvent) {
        MatrixRtcToDeviceKeyEventHandler(
            roomId = roomId,
            onReceivedKey = receivedKeyHandler()
        ).handle(event)
    }

    private fun receivedKeyHandler(): ReceivedKeyHandler {
        return synchronized(lock) { onReceivedKey }
    }

    private fun replaceListenerToken(token: MatrixRtcCancellable?): MatrixRtcCancellable? {
        return synchronized(lock) {
            val previous = listenerToken
            listenerToken = token
            previous
        }
    }
}

private class MatrixRtcToDeviceKeyEventHandler(
    private val roomId: String,
    private val onReceivedKey: MatrixRtcToDeviceKeyTransport.ReceivedKeyHandler
) {
    fun handle(event: MatrixRtcCustomToDeviceEvent) {
        runCatching {
            if (event.eventType != MatrixRtcCallEncryptionKeysContent.EVENT_TYPE) {
                return
            }

            val content = MatrixRtcCallEncryptionKeysContent.fromJson(event.contentJson)
            if (content.roomId != roomId) {
                return
            }

            val encryptionInfo = event.encryptionInfo
                ?: throw IllegalArgumentException("MatrixRTC encryption key event is not encrypted")
            val sender = encryptionInfo.sender
            require(sender.isNotEmpty()) { "MatrixRTC encryption key sender is empty" }

            val memberId = content.member.id
                ?: MatrixRtcMembershipIdentity.legacyRtcBackendIdentity(
                    userId = sender,
                    deviceId = content.member.claimedDeviceId
                )
            MatrixRtcReceivedCallEncryptionKey(
                sender = sender,
                membership = MatrixRtcMembershipIdentity(
                    userId = sender,
                    deviceId = content.member.claimedDeviceId,
                    memberId = memberId
                ),
                keyBase64Encoded = content.keys.key,
                keyIndex = content.keys.index,
                sentTimestamp = content.sentTimestamp,
                encryptionInfo = encryptionInfo
            )
        }.onSuccess { received ->
            MatrixRtcCallDebugLog.d(
                "mediaKeyReceive sender=${received.sender} userId=${received.membership.userId} " +
                    "deviceId=${received.membership.deviceId} memberId=${received.membership.memberId} " +
                    "keyIndex=${received.keyIndex} encrypted=${received.encryptionInfo != null}"
            )
            onReceivedKey.onReceivedKey(Result.success(received))
        }.onFailure { error ->
            MatrixRtcCallDebugLog.d("mediaKeyReceive failed", error)
            onReceivedKey.onReceivedKey(Result.failure(error))
        }
    }
}

private fun MatrixRtcToDeviceTarget.debugSummary(): String {
    return "${userId}:${deviceId}"
}

private fun MatrixRtcCustomToDeviceSendFailure.debugSummary(): String {
    return "${userId}:${deviceId}:$reason"
}
