package com.zyna.app.data.calls.matrixrtc

import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class MatrixRtcMediaKey(
    val keyBase64Encoded: String,
    val keyIndex: Int,
    val membership: MatrixRtcMembershipIdentity,
    val rtcBackendIdentity: String
)

data class MatrixRtcMediaKeyChangedEvent(
    val key: MatrixRtcMediaKey
)

data class MatrixRtcMediaKeyShareResult(
    val failures: List<MatrixRtcCustomToDeviceSendFailure>,
    val sharedWith: List<MatrixRtcToDeviceTarget>
)

data class MatrixRtcMediaKeyRotationConfiguration(
    val useKeyDelayMillis: Long = 1_000,
    val keyRotationGracePeriodMillis: Long = 10_000,
    val rotateKeyOnLateJoin: Boolean = true
)

data class MatrixRtcMediaKeyMapKey(
    val userId: String,
    val deviceId: String,
    val memberId: String
) {
    constructor(membership: MatrixRtcMembershipIdentity) : this(
        userId = membership.userId,
        deviceId = membership.deviceId,
        memberId = membership.memberId
    )
}

interface MatrixRtcMediaKeyGenerating {
    fun generateMediaKeyBase64Encoded(): String
}

class MatrixRtcRandomMediaKeyGenerator(
    private val byteCount: Int = 16,
    private val secureRandom: SecureRandom = SecureRandom()
) : MatrixRtcMediaKeyGenerating {
    override fun generateMediaKeyBase64Encoded(): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }
}

class MatrixRtcMediaKeyManager(
    private val ownMembership: MatrixRtcCallMembership,
    memberships: List<MatrixRtcCallMembership> = emptyList(),
    private val transport: MatrixRtcToDeviceKeyTransport,
    private val keyGenerator: MatrixRtcMediaKeyGenerating = MatrixRtcRandomMediaKeyGenerator(),
    private val rotationConfiguration: MatrixRtcMediaKeyRotationConfiguration =
        MatrixRtcMediaKeyRotationConfiguration(),
    private val timestampProvider: () -> Long = { System.currentTimeMillis() },
    private val onKeyChanged: (MatrixRtcMediaKeyChangedEvent) -> Unit,
    private val onError: ((Throwable) -> Unit)? = null
) {
    private val lock = Any()
    private val distributionMutex = Mutex()

    private var memberships: List<MatrixRtcCallMembership> = memberships
    private val inboundKeys =
        mutableMapOf<MatrixRtcMediaKeyMapKey, List<MatrixRtcMediaKey>>()
    private val pendingInboundKeys = mutableListOf<MatrixRtcReceivedCallEncryptionKey>()
    private val inboundKeyTimestamps = mutableMapOf<InboundMediaKeyTimestampKey, Long>()
    private var outboundSession: OutboundMediaKeySession? = null

    init {
        transport.setReceivedKeyHandler { result ->
            result
                .onSuccess(::handleReceivedKey)
                .onFailure { error -> onError?.invoke(error) }
        }
    }

    fun start() {
        transport.start()
    }

    fun stop() {
        transport.stop()
    }

    fun updateMemberships(memberships: List<MatrixRtcCallMembership>) {
        val changedKeys = synchronized(lock) {
            MatrixRtcCallDebugLog.d(
                "mediaKeyUpdateMemberships count=${memberships.size} " +
                    "memberships=${memberships.joinToString { it.debugSummary() }}"
            )
            this.memberships = memberships
            flushPendingInboundKeysLocked()
        }
        changedKeys.forEach { key -> onKeyChanged(MatrixRtcMediaKeyChangedEvent(key)) }
    }

    fun encryptionKeys(): Map<MatrixRtcMediaKeyMapKey, List<MatrixRtcMediaKey>> {
        return synchronized(lock) { inboundKeys.toMap() }
    }

    fun reemitEncryptionKeys() {
        val keys = synchronized(lock) {
            inboundKeys.values
                .flatten()
                .sortedWith(
                    compareBy<MatrixRtcMediaKey> { it.membership.userId }
                        .thenBy { it.membership.deviceId }
                        .thenBy { it.membership.memberId }
                        .thenBy { it.keyIndex }
                )
        }
        keys.forEach { key -> onKeyChanged(MatrixRtcMediaKeyChangedEvent(key)) }
    }

    fun ensureOutboundSession(): MatrixRtcMediaKey {
        val result = synchronized(lock) {
            outboundSession?.let { session ->
                return@synchronized OutboundEnsureResult(mediaKey = session.mediaKey, isNew = false)
            }

            val session = OutboundMediaKeySession(
                mediaKey = MatrixRtcMediaKey(
                    keyBase64Encoded = keyGenerator.generateMediaKeyBase64Encoded(),
                    keyIndex = 0,
                    membership = ownMembership.identity,
                    rtcBackendIdentity = ownMembership.rtcBackendIdentity
                ),
                createdTimestamp = timestampProvider()
            )
            outboundSession = session
            addKeyLocked(session.mediaKey)
            MatrixRtcCallDebugLog.d(
                "mediaKeyOutboundCreated participantId=${session.mediaKey.rtcBackendIdentity} " +
                    "keyIndex=${session.mediaKey.keyIndex}"
            )
            OutboundEnsureResult(mediaKey = session.mediaKey, isNew = true)
        }

        if (result.isNew) {
            onKeyChanged(MatrixRtcMediaKeyChangedEvent(result.mediaKey))
        }
        return result.mediaKey
    }

    suspend fun ensureKeyDistribution(
        memberships: List<MatrixRtcCallMembership>? = null
    ): MatrixRtcMediaKeyShareResult {
        if (memberships != null) {
            updateMemberships(memberships)
        }

        return distributionMutex.withLock {
            rolloutOutboundKey()
        }
    }

    suspend fun shareCurrentKey(
        memberships: List<MatrixRtcCallMembership>? = null
    ): MatrixRtcMediaKeyShareResult {
        val outboundKey = ensureOutboundSession()
        val targetMemberships = memberships ?: synchronized(lock) { this.memberships }
        val shareTargets = synchronized(lock) {
            shareTargets(targetMemberships)
        }
        return shareKey(outboundKey, shareTargets)
    }

    suspend fun reshareCurrentKey(
        memberships: List<MatrixRtcCallMembership>? = null
    ): MatrixRtcMediaKeyShareResult {
        if (memberships != null) {
            updateMemberships(memberships)
        }

        return distributionMutex.withLock {
            forceShareCurrentKey(memberships ?: synchronized(lock) { this.memberships })
        }
    }

    fun handleReceivedKey(receivedKey: MatrixRtcReceivedCallEncryptionKey) {
        val changedKey = synchronized(lock) {
            addOrQueueInboundKeyLocked(receivedKey)
        }
        changedKey?.let { key -> onKeyChanged(MatrixRtcMediaKeyChangedEvent(key)) }
    }

    private suspend fun rolloutOutboundKey(): MatrixRtcMediaKeyShareResult {
        ensureOutboundSession()
        val rollout = synchronized(lock) {
            makeOutboundRolloutLocked(now = timestampProvider())
        } ?: return MatrixRtcMediaKeyShareResult(failures = emptyList(), sharedWith = emptyList())

        val result = shareKey(rollout.mediaKey, rollout.shareTargets)
        if (rollout.shouldApplyLocallyAfterDelay) {
            if (rotationConfiguration.useKeyDelayMillis > 0) {
                delay(rotationConfiguration.useKeyDelayMillis)
            }
            synchronized(lock) {
                addKeyLocked(rollout.mediaKey)
            }
            onKeyChanged(MatrixRtcMediaKeyChangedEvent(rollout.mediaKey))
        }
        return result
    }

    private suspend fun forceShareCurrentKey(
        memberships: List<MatrixRtcCallMembership>
    ): MatrixRtcMediaKeyShareResult {
        val outboundKey = ensureOutboundSession()
        val shareTargets = synchronized(lock) {
            allShareTargets(memberships)
        }
        return shareKey(outboundKey, shareTargets)
    }

    private suspend fun shareKey(
        mediaKey: MatrixRtcMediaKey,
        shareTargets: List<MediaKeyShareTarget>
    ): MatrixRtcMediaKeyShareResult {
        if (shareTargets.isEmpty()) {
            MatrixRtcCallDebugLog.d("mediaKeyShare skipped keyIndex=${mediaKey.keyIndex} targets=0")
            return MatrixRtcMediaKeyShareResult(failures = emptyList(), sharedWith = emptyList())
        }

        MatrixRtcCallDebugLog.d(
            "mediaKeyShare keyIndex=${mediaKey.keyIndex} " +
                "targets=${shareTargets.joinToString { it.target.debugSummary() }}"
        )
        val failures = transport.sendKey(
            keyBase64Encoded = mediaKey.keyBase64Encoded,
            index = mediaKey.keyIndex,
            targets = shareTargets.map { it.target }
        )
        val successfulShareTargets = successfulShareTargets(shareTargets, failures)
        if (successfulShareTargets.isNotEmpty()) {
            synchronized(lock) {
                outboundSession?.sharedWith?.addAll(
                    successfulShareTargets.map { it.participant }
                )
            }
        }

        return MatrixRtcMediaKeyShareResult(
            failures = failures,
            sharedWith = successfulShareTargets.map { it.target }
        ).also { result ->
            MatrixRtcCallDebugLog.d(
                "mediaKeyShare result keyIndex=${mediaKey.keyIndex} " +
                    "sharedWith=${result.sharedWith.joinToString { it.debugSummary() }} " +
                    "failures=${result.failures.joinToString { it.debugSummary() }}"
            )
        }
    }

    private fun successfulShareTargets(
        shareTargets: List<MediaKeyShareTarget>,
        failures: List<MatrixRtcCustomToDeviceSendFailure>
    ): List<MediaKeyShareTarget> {
        val failedTargets = failures.mapTo(LinkedHashSet()) { failure ->
            MatrixRtcToDeviceTarget(userId = failure.userId, deviceId = failure.deviceId)
        }
        return shareTargets.filter { it.target !in failedTargets }
    }

    private fun makeOutboundRolloutLocked(now: Long): OutboundMediaKeyRollout? {
        val session = outboundSession ?: return null
        val shareTargets = allShareTargets(memberships)
        val currentParticipants = shareTargets.mapTo(LinkedHashSet()) { it.participant }
        val resetSharedWith = session.sharedWith.filterTo(LinkedHashSet()) { sharedParticipant ->
            currentParticipants.none { currentParticipant ->
                currentParticipant.userId == sharedParticipant.userId &&
                    currentParticipant.deviceId == sharedParticipant.deviceId &&
                    currentParticipant.createdTimestamp != sharedParticipant.createdTimestamp
            }
        }
        session.sharedWith.clear()
        session.sharedWith.addAll(resetSharedWith)

        val leftParticipants = resetSharedWith.filter { it !in currentParticipants }
        val joinedShareTargets = shareTargets.filter { it.participant !in resetSharedWith }

        if (leftParticipants.isNotEmpty()) {
            val newSession = createNewOutboundSessionLocked(now)
            return OutboundMediaKeyRollout(
                mediaKey = newSession.mediaKey,
                shareTargets = shareTargets,
                shouldApplyLocallyAfterDelay = true
            )
        }

        if (joinedShareTargets.isEmpty()) {
            return null
        }

        val keyAge = now - session.createdTimestamp
        if (
            keyAge < rotationConfiguration.keyRotationGracePeriodMillis ||
            !rotationConfiguration.rotateKeyOnLateJoin
        ) {
            return OutboundMediaKeyRollout(
                mediaKey = session.mediaKey,
                shareTargets = joinedShareTargets,
                shouldApplyLocallyAfterDelay = false
            )
        }

        val newSession = createNewOutboundSessionLocked(now)
        return OutboundMediaKeyRollout(
            mediaKey = newSession.mediaKey,
            shareTargets = shareTargets,
            shouldApplyLocallyAfterDelay = true
        )
    }

    private fun createNewOutboundSessionLocked(now: Long): OutboundMediaKeySession {
        val session = OutboundMediaKeySession(
            mediaKey = MatrixRtcMediaKey(
                keyBase64Encoded = keyGenerator.generateMediaKeyBase64Encoded(),
                keyIndex = nextOutboundKeyIndexLocked(),
                membership = ownMembership.identity,
                rtcBackendIdentity = ownMembership.rtcBackendIdentity
            ),
            createdTimestamp = now
        )
        outboundSession = session
        return session
    }

    private fun nextOutboundKeyIndexLocked(): Int {
        val currentIndex = outboundSession?.mediaKey?.keyIndex ?: return 0
        var nextIndex = (currentIndex + 1) % 256
        if (nextIndex == LIVEKIT_UNSUPPORTED_OUTBOUND_KEY_INDEX) {
            nextIndex = 0
        }
        return nextIndex
    }

    private fun shareTargets(
        memberships: List<MatrixRtcCallMembership>
    ): List<MediaKeyShareTarget> {
        val sharedWith = outboundSession?.sharedWith ?: emptySet()
        return allShareTargets(memberships)
            .filter { shareTarget -> shareTarget.participant !in sharedWith }
    }

    private fun allShareTargets(
        memberships: List<MatrixRtcCallMembership>
    ): List<MediaKeyShareTarget> {
        val seen = LinkedHashSet<MatrixRtcToDeviceTarget>()
        return memberships.mapNotNull { membership ->
            if (
                membership.userId == ownMembership.userId &&
                membership.deviceId == ownMembership.deviceId
            ) {
                return@mapNotNull null
            }

            val participant = ParticipantDevice(
                userId = membership.userId,
                deviceId = membership.deviceId,
                createdTimestamp = membership.createdTimestamp
            )
            val target = membership.toDeviceTarget
            if (!seen.add(target)) {
                return@mapNotNull null
            }
            MediaKeyShareTarget(participant = participant, target = target)
        }
    }

    private fun mediaKey(receivedKey: MatrixRtcReceivedCallEncryptionKey): MatrixRtcMediaKey {
        val membership = memberships.firstOrNull {
            it.userId == receivedKey.membership.userId &&
                it.deviceId == receivedKey.membership.deviceId
        }
        return if (membership == null) {
            MatrixRtcMediaKey(
                keyBase64Encoded = receivedKey.keyBase64Encoded,
                keyIndex = receivedKey.keyIndex,
                membership = receivedKey.membership,
                rtcBackendIdentity = ""
            )
        } else {
            MatrixRtcMediaKey(
                keyBase64Encoded = receivedKey.keyBase64Encoded,
                keyIndex = receivedKey.keyIndex,
                membership = membership.identity,
                rtcBackendIdentity = membership.rtcBackendIdentity
            )
        }
    }

    private fun addOrQueueInboundKeyLocked(
        receivedKey: MatrixRtcReceivedCallEncryptionKey
    ): MatrixRtcMediaKey? {
        val mediaKey = mediaKey(receivedKey)
        if (mediaKey.rtcBackendIdentity.isEmpty()) {
            pendingInboundKeys += receivedKey
            return null
        }
        if (isOutdatedInboundKeyLocked(mediaKey, receivedKey.sentTimestamp)) {
            return null
        }
        addKeyLocked(mediaKey)
        return mediaKey
    }

    private fun flushPendingInboundKeysLocked(): List<MatrixRtcMediaKey> {
        if (pendingInboundKeys.isEmpty()) {
            return emptyList()
        }

        val pendingKeys = pendingInboundKeys.toList()
        pendingInboundKeys.clear()
        return pendingKeys.mapNotNull { receivedKey ->
            addOrQueueInboundKeyLocked(receivedKey)
        }
    }

    private fun isOutdatedInboundKeyLocked(
        mediaKey: MatrixRtcMediaKey,
        sentTimestamp: Long?
    ): Boolean {
        if (sentTimestamp == null) {
            return false
        }

        val timestampKey = InboundMediaKeyTimestampKey(
            mapKey = MatrixRtcMediaKeyMapKey(mediaKey.membership),
            keyIndex = mediaKey.keyIndex
        )
        val latestTimestamp = inboundKeyTimestamps[timestampKey]
        if (latestTimestamp != null && latestTimestamp > sentTimestamp) {
            return true
        }

        inboundKeyTimestamps[timestampKey] = sentTimestamp
        return false
    }

    private fun addKeyLocked(mediaKey: MatrixRtcMediaKey) {
        val mapKey = MatrixRtcMediaKeyMapKey(mediaKey.membership)
        val keys = inboundKeys[mapKey]
            .orEmpty()
            .filterNot { it.keyIndex == mediaKey.keyIndex }
            .plus(mediaKey)
            .sortedBy { it.keyIndex }
        inboundKeys[mapKey] = keys
        MatrixRtcCallDebugLog.d(
            "mediaKeyStore participantId=${mediaKey.rtcBackendIdentity} " +
                "keyIndex=${mediaKey.keyIndex} userId=${mediaKey.membership.userId} " +
                "deviceId=${mediaKey.membership.deviceId} memberId=${mediaKey.membership.memberId}"
        )
    }

    private data class OutboundEnsureResult(
        val mediaKey: MatrixRtcMediaKey,
        val isNew: Boolean
    )

    private class OutboundMediaKeySession(
        val mediaKey: MatrixRtcMediaKey,
        val createdTimestamp: Long,
        val sharedWith: MutableSet<ParticipantDevice> = LinkedHashSet()
    )

    private data class OutboundMediaKeyRollout(
        val mediaKey: MatrixRtcMediaKey,
        val shareTargets: List<MediaKeyShareTarget>,
        val shouldApplyLocallyAfterDelay: Boolean
    )

    private data class ParticipantDevice(
        val userId: String,
        val deviceId: String,
        val createdTimestamp: Long
    )

    private data class InboundMediaKeyTimestampKey(
        val mapKey: MatrixRtcMediaKeyMapKey,
        val keyIndex: Int
    )

    private data class MediaKeyShareTarget(
        val participant: ParticipantDevice,
        val target: MatrixRtcToDeviceTarget
    )

    companion object {
        private const val LIVEKIT_UNSUPPORTED_OUTBOUND_KEY_INDEX = 255
    }
}

private fun MatrixRtcCallMembership.debugSummary(): String {
    return "userId=$userId deviceId=$deviceId memberId=$memberId " +
        "rtcBackendIdentity=$rtcBackendIdentity kind=$kind"
}

private fun MatrixRtcToDeviceTarget.debugSummary(): String {
    return "${userId}:${deviceId}"
}

private fun MatrixRtcCustomToDeviceSendFailure.debugSummary(): String {
    return "${userId}:${deviceId}:$reason"
}
