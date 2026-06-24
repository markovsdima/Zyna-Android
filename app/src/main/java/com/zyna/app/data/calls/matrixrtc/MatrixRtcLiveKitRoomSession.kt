package com.zyna.app.data.calls.matrixrtc

import android.content.Context
import com.twilio.audioswitch.AudioDevice
import com.twilio.audioswitch.AudioDeviceChangeListener
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
import livekit.org.webrtc.FrameCryptorKeyDerivationAlgorithm
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

enum class MatrixRtcAudioOutputDeviceKind {
    BLUETOOTH,
    WIRED_HEADSET,
    EARPIECE,
    SPEAKERPHONE
}

data class MatrixRtcAudioOutputDevice(
    val kind: MatrixRtcAudioOutputDeviceKind,
    val name: String
)

data class MatrixRtcAudioOutputState(
    val availableDevices: List<MatrixRtcAudioOutputDevice> = emptyList(),
    val selectedDevice: MatrixRtcAudioOutputDevice? = null
) {
    val isSpeakerphoneEnabled: Boolean
        get() = selectedDevice?.kind == MatrixRtcAudioOutputDeviceKind.SPEAKERPHONE

    val canToggleSpeakerphone: Boolean
        get() {
            val hasSpeakerphone = availableDevices.any {
                it.kind == MatrixRtcAudioOutputDeviceKind.SPEAKERPHONE
            }
            val hasNonSpeakerphone = availableDevices.any {
                it.kind != MatrixRtcAudioOutputDeviceKind.SPEAKERPHONE
            }
            return hasSpeakerphone && hasNonSpeakerphone
        }

    val selectedDeviceLabel: String?
        get() {
            val device = selectedDevice ?: return null
            return when (device.kind) {
                MatrixRtcAudioOutputDeviceKind.BLUETOOTH -> device.name
                MatrixRtcAudioOutputDeviceKind.WIRED_HEADSET -> "Headset"
                MatrixRtcAudioOutputDeviceKind.EARPIECE -> "Phone"
                MatrixRtcAudioOutputDeviceKind.SPEAKERPHONE -> "Speaker"
            }
        }

    fun preferredKindForSpeakerphone(enabled: Boolean): MatrixRtcAudioOutputDeviceKind? {
        if (enabled) {
            return availableDevices
                .firstOrNull { it.kind == MatrixRtcAudioOutputDeviceKind.SPEAKERPHONE }
                ?.kind
        }

        return listOf(
            MatrixRtcAudioOutputDeviceKind.BLUETOOTH,
            MatrixRtcAudioOutputDeviceKind.WIRED_HEADSET,
            MatrixRtcAudioOutputDeviceKind.EARPIECE
        ).firstNotNullOfOrNull { kind ->
            availableDevices.firstOrNull { it.kind == kind }?.kind
        }
    }
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
    data class AudioOutputChanged(
        val state: MatrixRtcAudioOutputState
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
    val audioOutputState: MatrixRtcAudioOutputState
    fun selectAudioOutput(kind: MatrixRtcAudioOutputDeviceKind): Boolean
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

    val audioOutputState: MatrixRtcAudioOutputState
        get() = controller.audioOutputState

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

    fun selectAudioOutput(kind: MatrixRtcAudioOutputDeviceKind): Boolean {
        ensureConnected()
        return controller.selectAudioOutput(kind)
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
        MatrixRtcCallDebugLog.d("createLiveKitRoomSession mediaEncryptionMode=$mediaEncryptionMode")
        LiveKit.init(appContext)
        val keyProvider = when (mediaEncryptionMode) {
            MatrixRtcLiveKitMediaEncryptionMode.PER_PARTICIPANT_KEYS ->
                BaseKeyProvider(
                    enableSharedKey = false,
                    ratchetWindowSize = 10,
                    keyRingSize = 256,
                    keyDerivationAlgorithm = FrameCryptorKeyDerivationAlgorithm.HKDF
                )
            MatrixRtcLiveKitMediaEncryptionMode.UNENCRYPTED -> null
        }
        val keyApplier = when (mediaEncryptionMode) {
            MatrixRtcLiveKitMediaEncryptionMode.PER_PARTICIPANT_KEYS ->
                MatrixLiveKitMediaKeyApplier(requireNotNull(keyProvider))
            MatrixRtcLiveKitMediaEncryptionMode.UNENCRYPTED -> null
        }
        val controller = AndroidMatrixRtcLiveKitRoomController(
            context = appContext,
            roomOptions = RoomOptions(
                e2eeOptions = when (mediaEncryptionMode) {
                    MatrixRtcLiveKitMediaEncryptionMode.PER_PARTICIPANT_KEYS -> {
                        val provider = requireNotNull(keyProvider)
                        E2EEOptions(provider).also {
                            MatrixRtcCallDebugLog.d(
                                "createLiveKitE2EEOptions enableSharedKey=${provider.enableSharedKey} " +
                                    "ratchetWindowSize=10 keyRingSize=256 keyDerivationAlgorithm=HKDF"
                            )
                        }
                    }
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

    init {
        room.audioSwitchHandler?.preferredDeviceList = callPreferredAudioDeviceList
    }

    override fun startEventCollection(
        scope: CoroutineScope,
        onEvent: (MatrixRtcLiveKitRoomSessionEvent) -> Unit
    ): MatrixRtcCancellable {
        val audioSwitchHandler = room.audioSwitchHandler
        val audioOutputListener: AudioDeviceChangeListener = { audioDevices, selectedAudioDevice ->
            val state = audioOutputStateFrom(
                audioDevices = audioDevices,
                selectedAudioDevice = selectedAudioDevice
            )
            MatrixRtcCallDebugLog.d("liveKitAudioOutputChanged ${state.debugSummary()}")
            onEvent(MatrixRtcLiveKitRoomSessionEvent.AudioOutputChanged(state))
        }
        audioSwitchHandler?.registerAudioDeviceChangeListener(audioOutputListener)
        val job = scope.launch {
            room.events.events.collect { event ->
                event.toMatrixRtcLiveKitEvent()?.let { mapped ->
                    MatrixRtcCallDebugLog.d("liveKitEvent ${mapped.debugSummary()}")
                    onEvent(mapped)
                }
            }
        }
        return object : MatrixRtcCancellable {
            override fun cancel() {
                audioSwitchHandler?.unregisterAudioDeviceChangeListener(audioOutputListener)
                job.cancel()
            }
        }
    }

    override suspend fun connect(url: String, token: String) {
        MatrixRtcCallDebugLog.d("liveKitConnect url=$url")
        room.connect(url, token, connectOptions)
        MatrixRtcCallDebugLog.d("liveKitConnect completed")
    }

    override fun disconnect() {
        MatrixRtcCallDebugLog.d("liveKitDisconnect")
        room.disconnect()
    }

    override suspend fun setMicrophoneEnabled(enabled: Boolean) {
        MatrixRtcCallDebugLog.d("setMicrophoneEnabled enabled=$enabled")
        room.localParticipant.setMicrophoneEnabled(enabled)
    }

    override suspend fun setCameraEnabled(enabled: Boolean) {
        MatrixRtcCallDebugLog.d("setCameraEnabled enabled=$enabled")
        room.localParticipant.setCameraEnabled(enabled)
    }

    override val audioOutputState: MatrixRtcAudioOutputState
        get() = audioOutputStateFrom(
            audioDevices = room.audioSwitchHandler?.availableAudioDevices.orEmpty(),
            selectedAudioDevice = room.audioSwitchHandler?.selectedAudioDevice
        )

    override fun selectAudioOutput(kind: MatrixRtcAudioOutputDeviceKind): Boolean {
        val audioSwitchHandler = room.audioSwitchHandler ?: return false
        val targetDevice = audioSwitchHandler.availableAudioDevices.firstOrNull { device ->
            device.toMatrixRtcAudioOutputDevice().kind == kind
        } ?: return false
        MatrixRtcCallDebugLog.d("selectAudioOutput kind=$kind device=${targetDevice.name}")
        audioSwitchHandler.selectDevice(targetDevice)
        return true
    }

    override fun close() {
        room.release()
    }

    private companion object {
        private val callPreferredAudioDeviceList = listOf(
            AudioDevice.BluetoothHeadset::class.java,
            AudioDevice.WiredHeadset::class.java,
            AudioDevice.Earpiece::class.java,
            AudioDevice.Speakerphone::class.java
        )
    }
}

internal fun MatrixRtcLiveKitRoomSessionEvent.debugSummary(): String {
    return when (this) {
        MatrixRtcLiveKitRoomSessionEvent.Connected -> "Connected"
        is MatrixRtcLiveKitRoomSessionEvent.Disconnected ->
            "Disconnected error=$error reason=$reason"
        is MatrixRtcLiveKitRoomSessionEvent.FailedToConnect ->
            "FailedToConnect error=$error"
        MatrixRtcLiveKitRoomSessionEvent.Reconnecting -> "Reconnecting"
        MatrixRtcLiveKitRoomSessionEvent.Reconnected -> "Reconnected"
        is MatrixRtcLiveKitRoomSessionEvent.LocalTrackSubscribedByRemote ->
            "LocalTrackSubscribedByRemote publication=${publication.debugSummary()}"
        is MatrixRtcLiveKitRoomSessionEvent.RemoteParticipantJoined ->
            "RemoteParticipantJoined participant=${participant.debugSummary()}"
        is MatrixRtcLiveKitRoomSessionEvent.RemoteParticipantLeft ->
            "RemoteParticipantLeft participant=${participant.debugSummary()}"
        is MatrixRtcLiveKitRoomSessionEvent.RemoteTrackPublished ->
            "RemoteTrackPublished participant=${participant.debugSummary()} " +
                "publication=${publication.debugSummary()}"
        is MatrixRtcLiveKitRoomSessionEvent.RemoteTrackUnpublished ->
            "RemoteTrackUnpublished participant=${participant.debugSummary()} " +
                "publication=${publication.debugSummary()}"
        is MatrixRtcLiveKitRoomSessionEvent.RemoteTrackSubscribed ->
            "RemoteTrackSubscribed participant=${participant.debugSummary()} " +
                "publication=${publication.debugSummary()}"
        is MatrixRtcLiveKitRoomSessionEvent.RemoteTrackUnsubscribed ->
            "RemoteTrackUnsubscribed participant=${participant.debugSummary()} " +
                "publication=${publication.debugSummary()}"
        is MatrixRtcLiveKitRoomSessionEvent.RemoteTrackSubscriptionFailed ->
            "RemoteTrackSubscriptionFailed participant=${participant.debugSummary()} " +
                "trackSid=$trackSid error=$error"
        is MatrixRtcLiveKitRoomSessionEvent.TrackMutedChanged ->
            "TrackMutedChanged participant=${participant.debugSummary()} " +
                "publication=${publication.debugSummary()} isMuted=$isMuted"
        is MatrixRtcLiveKitRoomSessionEvent.RemoteTrackStreamStateChanged ->
            "RemoteTrackStreamStateChanged publication=${publication.debugSummary()} state=$state"
        is MatrixRtcLiveKitRoomSessionEvent.SpeakingParticipantsChanged ->
            "SpeakingParticipantsChanged participants=${participants.joinToString { it.debugSummary() }}"
        is MatrixRtcLiveKitRoomSessionEvent.TrackE2EEStateChanged ->
            "TrackE2EEStateChanged participant=${participant.debugSummary()} " +
                "publication=${publication.debugSummary()} state=$state"
        is MatrixRtcLiveKitRoomSessionEvent.MediaKeyApplied ->
            "MediaKeyApplied participantId=$participantId keyIndex=$keyIndex"
        is MatrixRtcLiveKitRoomSessionEvent.AudioOutputChanged ->
            "AudioOutputChanged ${state.debugSummary()}"
    }
}

private fun MatrixRtcLiveKitParticipantInfo.debugSummary(): String {
    return "identity=$identity sid=$sid"
}

private fun MatrixRtcLiveKitTrackPublicationInfo.debugSummary(): String {
    return "sid=$sid name=$name kind=$kind source=$source " +
        "muted=$isMuted subscribed=$isSubscribed"
}

private fun MatrixRtcLiveKitSpeakingParticipantInfo.debugSummary(): String {
    return "identity=$identity sid=$sid speaking=$isSpeaking " +
        "level=$audioLevel lastSpokeAt=$lastSpokeAtMillis"
}

private fun audioOutputStateFrom(
    audioDevices: List<AudioDevice>,
    selectedAudioDevice: AudioDevice?
): MatrixRtcAudioOutputState {
    return MatrixRtcAudioOutputState(
        availableDevices = audioDevices.map { it.toMatrixRtcAudioOutputDevice() },
        selectedDevice = selectedAudioDevice?.toMatrixRtcAudioOutputDevice()
    )
}

private fun AudioDevice.toMatrixRtcAudioOutputDevice(): MatrixRtcAudioOutputDevice {
    return when (this) {
        is AudioDevice.BluetoothHeadset -> MatrixRtcAudioOutputDevice(
            kind = MatrixRtcAudioOutputDeviceKind.BLUETOOTH,
            name = name.ifBlank { "Bluetooth" }
        )
        is AudioDevice.WiredHeadset -> MatrixRtcAudioOutputDevice(
            kind = MatrixRtcAudioOutputDeviceKind.WIRED_HEADSET,
            name = name.ifBlank { "Headset" }
        )
        is AudioDevice.Earpiece -> MatrixRtcAudioOutputDevice(
            kind = MatrixRtcAudioOutputDeviceKind.EARPIECE,
            name = name.ifBlank { "Phone" }
        )
        is AudioDevice.Speakerphone -> MatrixRtcAudioOutputDevice(
            kind = MatrixRtcAudioOutputDeviceKind.SPEAKERPHONE,
            name = name.ifBlank { "Speaker" }
        )
    }
}

private fun MatrixRtcAudioOutputState.debugSummary(): String {
    return "selected=${selectedDevice?.kind}:${selectedDevice?.name} " +
        "available=${availableDevices.joinToString { "${it.kind}:${it.name}" }}"
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
