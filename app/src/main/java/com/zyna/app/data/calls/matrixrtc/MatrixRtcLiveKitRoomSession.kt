package com.zyna.app.data.calls.matrixrtc

import android.content.Context
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.e2ee.BaseKeyProvider
import io.livekit.android.e2ee.E2EEOptions
import io.livekit.android.e2ee.KeyProvider
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.TrackPublication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

enum class MatrixRtcLiveKitRoomSessionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    DISCONNECTED
}

enum class MatrixRtcLiveKitMediaEncryptionMode {
    PER_PARTICIPANT_KEYS,
    UNENCRYPTED
}

sealed class MatrixRtcLiveKitRoomSessionException(message: String) : Exception(message) {
    data object AlreadyConnecting :
        MatrixRtcLiveKitRoomSessionException("LiveKit room session is already connecting")

    data object AlreadyConnected :
        MatrixRtcLiveKitRoomSessionException("LiveKit room session is already connected")

    data object NotConnected :
        MatrixRtcLiveKitRoomSessionException("LiveKit room session is not connected")
}

data class MatrixRtcLiveKitParticipantInfo(
    val identity: String?,
    val sid: String?
)

data class MatrixRtcLiveKitSpeakingParticipantInfo(
    val identity: String?,
    val sid: String?,
    val isSpeaking: Boolean,
    val audioLevel: Float,
    val lastSpokeAtMillis: Long?
)

data class MatrixRtcLiveKitTrackPublicationInfo(
    val sid: String,
    val name: String,
    val kind: String,
    val source: String,
    val isMuted: Boolean,
    val isSubscribed: Boolean
)

sealed interface MatrixRtcLiveKitRoomSessionEvent {
    data object Connected : MatrixRtcLiveKitRoomSessionEvent
    data class Disconnected(val error: String?, val reason: String?) : MatrixRtcLiveKitRoomSessionEvent
    data class FailedToConnect(val error: String?) : MatrixRtcLiveKitRoomSessionEvent
    data object Reconnecting : MatrixRtcLiveKitRoomSessionEvent
    data object Reconnected : MatrixRtcLiveKitRoomSessionEvent
    data class LocalTrackSubscribedByRemote(
        val publication: MatrixRtcLiveKitTrackPublicationInfo
    ) : MatrixRtcLiveKitRoomSessionEvent
    data class RemoteParticipantJoined(
        val participant: MatrixRtcLiveKitParticipantInfo
    ) : MatrixRtcLiveKitRoomSessionEvent
    data class RemoteParticipantLeft(
        val participant: MatrixRtcLiveKitParticipantInfo
    ) : MatrixRtcLiveKitRoomSessionEvent
    data class RemoteTrackPublished(
        val participant: MatrixRtcLiveKitParticipantInfo,
        val publication: MatrixRtcLiveKitTrackPublicationInfo
    ) : MatrixRtcLiveKitRoomSessionEvent
    data class RemoteTrackUnpublished(
        val participant: MatrixRtcLiveKitParticipantInfo,
        val publication: MatrixRtcLiveKitTrackPublicationInfo
    ) : MatrixRtcLiveKitRoomSessionEvent
    data class RemoteTrackSubscribed(
        val participant: MatrixRtcLiveKitParticipantInfo,
        val publication: MatrixRtcLiveKitTrackPublicationInfo
    ) : MatrixRtcLiveKitRoomSessionEvent
    data class RemoteTrackUnsubscribed(
        val participant: MatrixRtcLiveKitParticipantInfo,
        val publication: MatrixRtcLiveKitTrackPublicationInfo
    ) : MatrixRtcLiveKitRoomSessionEvent
    data class RemoteTrackSubscriptionFailed(
        val participant: MatrixRtcLiveKitParticipantInfo,
        val trackSid: String,
        val error: String
    ) : MatrixRtcLiveKitRoomSessionEvent
    data class TrackMutedChanged(
        val participant: MatrixRtcLiveKitParticipantInfo,
        val publication: MatrixRtcLiveKitTrackPublicationInfo,
        val isMuted: Boolean
    ) : MatrixRtcLiveKitRoomSessionEvent
    data class RemoteTrackStreamStateChanged(
        val publication: MatrixRtcLiveKitTrackPublicationInfo,
        val state: String
    ) : MatrixRtcLiveKitRoomSessionEvent
    data class SpeakingParticipantsChanged(
        val participants: List<MatrixRtcLiveKitSpeakingParticipantInfo>
    ) : MatrixRtcLiveKitRoomSessionEvent
    data class TrackE2EEStateChanged(
        val participant: MatrixRtcLiveKitParticipantInfo,
        val publication: MatrixRtcLiveKitTrackPublicationInfo,
        val state: String
    ) : MatrixRtcLiveKitRoomSessionEvent
    data class MediaKeyApplied(
        val keyIndex: Int,
        val participantId: String
    ) : MatrixRtcLiveKitRoomSessionEvent
}

fun interface MatrixRtcLiveKitRoomSessionFactory {
    fun create(
        mediaEncryptionMode: MatrixRtcLiveKitMediaEncryptionMode,
        onEvent: (MatrixRtcLiveKitRoomSessionEvent) -> Unit
    ): MatrixRtcLiveKitRoomSession
}

interface MatrixRtcLiveKitRoomController : AutoCloseable {
    fun startEventCollection(
        scope: CoroutineScope,
        onEvent: (MatrixRtcLiveKitRoomSessionEvent) -> Unit
    ): MatrixRtcCancellable

    suspend fun connect(url: String, token: String)
    fun disconnect()
    suspend fun setMicrophoneEnabled(enabled: Boolean)
    suspend fun setCameraEnabled(enabled: Boolean)
}

class MatrixRtcLiveKitRoomSession(
    private val controller: MatrixRtcLiveKitRoomController,
    private val keyApplier: MatrixRtcMediaKeyApplier?,
    private val onEvent: (MatrixRtcLiveKitRoomSessionEvent) -> Unit = {},
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : AutoCloseable {
    private val lock = Any()
    private val eventCollection = controller.startEventCollection(coroutineScope, onEvent)

    private var mutableState = MatrixRtcLiveKitRoomSessionState.IDLE
    private var microphoneEnabled = false
    private var cameraEnabled = false

    val state: MatrixRtcLiveKitRoomSessionState
        get() = synchronized(lock) { mutableState }

    val isMicrophoneEnabled: Boolean
        get() = synchronized(lock) { microphoneEnabled }

    val isCameraEnabled: Boolean
        get() = synchronized(lock) { cameraEnabled }

    suspend fun connect(
        sfuConfig: MatrixRtcLiveKitSfuConfig,
        publishAudio: Boolean = false
    ) {
        markConnecting()
        try {
            controller.connect(
                url = sfuConfig.url,
                token = sfuConfig.jwt
            )
            setState(MatrixRtcLiveKitRoomSessionState.CONNECTED)

            if (publishAudio) {
                try {
                    setMicrophoneEnabled(true)
                } catch (error: Throwable) {
                    disconnect()
                    throw error
                }
            }
        } catch (error: Throwable) {
            setState(MatrixRtcLiveKitRoomSessionState.DISCONNECTED)
            throw error
        }
    }

    fun disconnect() {
        controller.disconnect()
        synchronized(lock) {
            microphoneEnabled = false
            cameraEnabled = false
            mutableState = MatrixRtcLiveKitRoomSessionState.DISCONNECTED
        }
    }

    suspend fun setMicrophoneEnabled(enabled: Boolean) {
        ensureConnected()
        controller.setMicrophoneEnabled(enabled)
        synchronized(lock) {
            microphoneEnabled = enabled
        }
    }

    suspend fun setCameraEnabled(enabled: Boolean) {
        ensureConnected()
        controller.setCameraEnabled(enabled)
        synchronized(lock) {
            cameraEnabled = enabled
        }
    }

    fun handleMediaKeyChanged(event: MatrixRtcMediaKeyChangedEvent): MatrixRtcMediaKey {
        val key = event.key
        val applier = keyApplier ?: return key
        applier.applyMediaKey(key)
        onEvent(
            MatrixRtcLiveKitRoomSessionEvent.MediaKeyApplied(
                keyIndex = key.keyIndex,
                participantId = key.rtcBackendIdentity
            )
        )
        return key
    }

    fun keyChangedHandler(
        onError: (Throwable) -> Unit = {}
    ): (MatrixRtcMediaKeyChangedEvent) -> Unit {
        return { event ->
            runCatching {
                handleMediaKeyChanged(event)
            }.onFailure(onError)
        }
    }

    override fun close() {
        eventCollection.cancel()
        runCatching { controller.disconnect() }
        controller.close()
        coroutineScope.cancel()
        synchronized(lock) {
            microphoneEnabled = false
            cameraEnabled = false
            mutableState = MatrixRtcLiveKitRoomSessionState.DISCONNECTED
        }
    }

    private fun markConnecting() {
        synchronized(lock) {
            when (mutableState) {
                MatrixRtcLiveKitRoomSessionState.CONNECTING ->
                    throw MatrixRtcLiveKitRoomSessionException.AlreadyConnecting
                MatrixRtcLiveKitRoomSessionState.CONNECTED ->
                    throw MatrixRtcLiveKitRoomSessionException.AlreadyConnected
                MatrixRtcLiveKitRoomSessionState.IDLE,
                MatrixRtcLiveKitRoomSessionState.DISCONNECTED -> {
                    mutableState = MatrixRtcLiveKitRoomSessionState.CONNECTING
                }
            }
        }
    }

    private fun ensureConnected() {
        if (state != MatrixRtcLiveKitRoomSessionState.CONNECTED) {
            throw MatrixRtcLiveKitRoomSessionException.NotConnected
        }
    }

    private fun setState(state: MatrixRtcLiveKitRoomSessionState) {
        synchronized(lock) {
            mutableState = state
        }
    }
}

class AndroidMatrixRtcLiveKitRoomSessionFactory(
    context: Context
) : MatrixRtcLiveKitRoomSessionFactory {
    private val appContext = context.applicationContext

    override fun create(
        mediaEncryptionMode: MatrixRtcLiveKitMediaEncryptionMode,
        onEvent: (MatrixRtcLiveKitRoomSessionEvent) -> Unit
    ): MatrixRtcLiveKitRoomSession {
        val keyProvider = BaseKeyProvider()
        val keyApplier = when (mediaEncryptionMode) {
            MatrixRtcLiveKitMediaEncryptionMode.PER_PARTICIPANT_KEYS ->
                MatrixLiveKitMediaKeyApplier(keyProvider)
            MatrixRtcLiveKitMediaEncryptionMode.UNENCRYPTED -> null
        }
        val controller = AndroidMatrixRtcLiveKitRoomController(
            context = appContext,
            roomOptions = RoomOptions(
                e2eeOptions = when (mediaEncryptionMode) {
                    MatrixRtcLiveKitMediaEncryptionMode.PER_PARTICIPANT_KEYS ->
                        E2EEOptions(keyProvider)
                    MatrixRtcLiveKitMediaEncryptionMode.UNENCRYPTED -> null
                }
            )
        )
        return MatrixRtcLiveKitRoomSession(
            controller = controller,
            keyApplier = keyApplier,
            onEvent = onEvent
        )
    }
}

class AndroidMatrixRtcLiveKitRoomController(
    context: Context,
    roomOptions: RoomOptions,
    private val connectOptions: ConnectOptions = ConnectOptions()
) : MatrixRtcLiveKitRoomController {
    private val appContext = context.applicationContext
    private val room = run {
        LiveKit.init(appContext)
        LiveKit.create(appContext, roomOptions)
    }

    override fun startEventCollection(
        scope: CoroutineScope,
        onEvent: (MatrixRtcLiveKitRoomSessionEvent) -> Unit
    ): MatrixRtcCancellable {
        val job = scope.launch {
            room.events.events.collect { event ->
                event.toMatrixRtcLiveKitEvent()?.let(onEvent)
            }
        }
        return object : MatrixRtcCancellable {
            override fun cancel() {
                job.cancel()
            }
        }
    }

    override suspend fun connect(url: String, token: String) {
        room.connect(url, token, connectOptions)
    }

    override fun disconnect() {
        room.disconnect()
    }

    override suspend fun setMicrophoneEnabled(enabled: Boolean) {
        room.localParticipant.setMicrophoneEnabled(enabled)
    }

    override suspend fun setCameraEnabled(enabled: Boolean) {
        room.localParticipant.setCameraEnabled(enabled)
    }

    override fun close() {
        room.release()
    }
}

private fun RoomEvent.toMatrixRtcLiveKitEvent(): MatrixRtcLiveKitRoomSessionEvent? {
    return when (this) {
        is RoomEvent.Connected -> MatrixRtcLiveKitRoomSessionEvent.Connected
        is RoomEvent.Disconnected -> MatrixRtcLiveKitRoomSessionEvent.Disconnected(
            error = error?.message,
            reason = reason?.name
        )
        is RoomEvent.FailedToConnect -> MatrixRtcLiveKitRoomSessionEvent.FailedToConnect(
            error = error.message
        )
        is RoomEvent.Reconnecting -> MatrixRtcLiveKitRoomSessionEvent.Reconnecting
        is RoomEvent.Reconnected -> MatrixRtcLiveKitRoomSessionEvent.Reconnected
        is RoomEvent.LocalTrackSubscribed -> MatrixRtcLiveKitRoomSessionEvent.LocalTrackSubscribedByRemote(
            publication = publication.toMatrixRtcLiveKitInfo()
        )
        is RoomEvent.ParticipantConnected -> MatrixRtcLiveKitRoomSessionEvent.RemoteParticipantJoined(
            participant = participant.toMatrixRtcLiveKitInfo()
        )
        is RoomEvent.ParticipantDisconnected -> MatrixRtcLiveKitRoomSessionEvent.RemoteParticipantLeft(
            participant = participant.toMatrixRtcLiveKitInfo()
        )
        is RoomEvent.TrackPublished -> if (participant is RemoteParticipant) {
            MatrixRtcLiveKitRoomSessionEvent.RemoteTrackPublished(
                participant = participant.toMatrixRtcLiveKitInfo(),
                publication = publication.toMatrixRtcLiveKitInfo()
            )
        } else {
            null
        }
        is RoomEvent.TrackUnpublished -> if (participant is RemoteParticipant) {
            MatrixRtcLiveKitRoomSessionEvent.RemoteTrackUnpublished(
                participant = participant.toMatrixRtcLiveKitInfo(),
                publication = publication.toMatrixRtcLiveKitInfo()
            )
        } else {
            null
        }
        is RoomEvent.TrackSubscribed -> MatrixRtcLiveKitRoomSessionEvent.RemoteTrackSubscribed(
            participant = participant.toMatrixRtcLiveKitInfo(),
            publication = publication.toMatrixRtcLiveKitInfo()
        )
        is RoomEvent.TrackSubscriptionFailed -> MatrixRtcLiveKitRoomSessionEvent.RemoteTrackSubscriptionFailed(
            participant = participant.toMatrixRtcLiveKitInfo(),
            trackSid = sid,
            error = exception.message ?: exception.toString()
        )
        is RoomEvent.TrackMuted -> MatrixRtcLiveKitRoomSessionEvent.TrackMutedChanged(
            participant = participant.toMatrixRtcLiveKitInfo(),
            publication = publication.toMatrixRtcLiveKitInfo(),
            isMuted = true
        )
        is RoomEvent.TrackUnmuted -> MatrixRtcLiveKitRoomSessionEvent.TrackMutedChanged(
            participant = participant.toMatrixRtcLiveKitInfo(),
            publication = publication.toMatrixRtcLiveKitInfo(),
            isMuted = false
        )
        is RoomEvent.TrackStreamStateChanged -> MatrixRtcLiveKitRoomSessionEvent.RemoteTrackStreamStateChanged(
            publication = trackPublication.toMatrixRtcLiveKitInfo(),
            state = streamState.name
        )
        is RoomEvent.ActiveSpeakersChanged -> MatrixRtcLiveKitRoomSessionEvent.SpeakingParticipantsChanged(
            participants = speakers.map { participant ->
                MatrixRtcLiveKitSpeakingParticipantInfo(
                    identity = participant.identity?.value,
                    sid = participant.sid?.toString(),
                    isSpeaking = participant.isSpeaking,
                    audioLevel = participant.audioLevel,
                    lastSpokeAtMillis = participant.lastSpokeAt
                )
            }
        )
        is RoomEvent.TrackE2EEStateEvent -> MatrixRtcLiveKitRoomSessionEvent.TrackE2EEStateChanged(
            participant = participant.toMatrixRtcLiveKitInfo(),
            publication = publication.toMatrixRtcLiveKitInfo(),
            state = state.name
        )
        else -> null
    }
}

private fun Participant.toMatrixRtcLiveKitInfo(): MatrixRtcLiveKitParticipantInfo {
    return MatrixRtcLiveKitParticipantInfo(
        identity = identity?.value,
        sid = sid?.toString()
    )
}

private fun TrackPublication.toMatrixRtcLiveKitInfo(): MatrixRtcLiveKitTrackPublicationInfo {
    return MatrixRtcLiveKitTrackPublicationInfo(
        sid = sid,
        name = name,
        kind = kind.name,
        source = source.name,
        isMuted = muted,
        isSubscribed = subscribed
    )
}
