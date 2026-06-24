package com.zyna.app.data.calls.matrixrtc

data class NativeMatrixRtcCallTrackState(
    val sid: String,
    val name: String,
    val kind: String,
    val source: String,
    val isMuted: Boolean,
    val isSubscribed: Boolean,
    val e2eeState: String? = null,
    val streamState: String? = null,
    val subscriptionError: String? = null,
    val localVideoTrack: MatrixRtcLiveKitLocalVideoTrack? = null,
    val remoteVideoTrack: MatrixRtcLiveKitRemoteVideoTrack? = null
) {
    val isAudio: Boolean
        get() = source.equals("MICROPHONE", ignoreCase = true) ||
            kind.equals("AUDIO", ignoreCase = true)

    val isVideo: Boolean
        get() = source.equals("CAMERA", ignoreCase = true) ||
            source.equals("SCREEN_SHARE", ignoreCase = true) ||
            kind.equals("VIDEO", ignoreCase = true)

    fun updateFrom(publication: MatrixRtcLiveKitTrackPublicationInfo): NativeMatrixRtcCallTrackState {
        return copy(
            name = publication.name,
            kind = publication.kind,
            source = publication.source,
            isMuted = publication.isMuted,
            isSubscribed = publication.isSubscribed
        )
    }
}

data class NativeMatrixRtcCallSpeakingState(
    val isSpeaking: Boolean = false,
    val lastSpokeAtMillis: Long? = null
) {
    fun updateFrom(participant: MatrixRtcLiveKitSpeakingParticipantInfo): NativeMatrixRtcCallSpeakingState {
        return copy(
            isSpeaking = participant.isSpeaking,
            lastSpokeAtMillis = participant.lastSpokeAtMillis ?: lastSpokeAtMillis
        )
    }

    fun stopSpeaking(): NativeMatrixRtcCallSpeakingState = copy(isSpeaking = false)
}

data class NativeMatrixRtcCallParticipantState(
    val id: String,
    val identity: String? = null,
    val sid: String? = null,
    val tracks: Map<String, NativeMatrixRtcCallTrackState> = emptyMap(),
    val speaking: NativeMatrixRtcCallSpeakingState = NativeMatrixRtcCallSpeakingState()
) {
    val sortedTracks: List<NativeMatrixRtcCallTrackState>
        get() = tracks.values.sortedWith(
            compareBy<NativeMatrixRtcCallTrackState> { it.source }
                .thenBy { it.sid }
        )

    val hasSubscribedAudio: Boolean
        get() = tracks.values.any { it.isAudio && it.isSubscribed && !it.isMuted }

    val hasSubscribedVideo: Boolean
        get() = tracks.values.any { it.isVideo && it.isSubscribed && !it.isMuted }
}

data class NativeMatrixRtcCallParticipantsSnapshot(
    val roomId: String? = null,
    val localIdentity: String? = null,
    val localTracks: Map<String, NativeMatrixRtcCallTrackState> = emptyMap(),
    val localSpeaking: NativeMatrixRtcCallSpeakingState = NativeMatrixRtcCallSpeakingState(),
    val remoteParticipantsById: Map<String, NativeMatrixRtcCallParticipantState> = emptyMap()
) {
    val remoteParticipants: List<NativeMatrixRtcCallParticipantState>
        get() = remoteParticipantsById.values.sortedWith { lhs, rhs ->
            participantComparator(lhs, rhs)
        }

    val remoteParticipantCount: Int
        get() = remoteParticipantsById.size

    val totalParticipantCount: Int
        get() = if (roomId == null) 0 else remoteParticipantCount + 1

    val localVideoTrack: MatrixRtcLiveKitLocalVideoTrack?
        get() = sortedLocalTracks.firstNotNullOfOrNull { track ->
            track.localVideoTrack.takeIf {
                track.isVideo && track.isSubscribed && !track.isMuted
            }
        }

    val remoteVideoTracks: List<MatrixRtcLiveKitRemoteVideoTrack>
        get() = remoteParticipants.flatMap { participant ->
            participant.sortedTracks.mapNotNull { track ->
                track.remoteVideoTrack.takeIf {
                    track.isVideo && track.isSubscribed && !track.isMuted
                }
            }
        }

    val primaryRemoteVideoTrack: MatrixRtcLiveKitRemoteVideoTrack?
        get() = remoteVideoTracks.firstOrNull()

    private val sortedLocalTracks: List<NativeMatrixRtcCallTrackState>
        get() = localTracks.values.sortedWith(
            compareBy<NativeMatrixRtcCallTrackState> { it.source }
                .thenBy { it.sid }
        )

    companion object {
        fun empty(): NativeMatrixRtcCallParticipantsSnapshot = NativeMatrixRtcCallParticipantsSnapshot()

        private fun participantComparator(
            lhs: NativeMatrixRtcCallParticipantState,
            rhs: NativeMatrixRtcCallParticipantState
        ): Int {
            if (lhs.speaking.isSpeaking != rhs.speaking.isSpeaking) {
                return if (lhs.speaking.isSpeaking) -1 else 1
            }

            val lhsLastSpoke = lhs.speaking.lastSpokeAtMillis
            val rhsLastSpoke = rhs.speaking.lastSpokeAtMillis
            if (lhsLastSpoke != rhsLastSpoke) {
                return when {
                    lhsLastSpoke == null -> 1
                    rhsLastSpoke == null -> -1
                    lhsLastSpoke > rhsLastSpoke -> -1
                    else -> 1
                }
            }

            val lhsName = lhs.identity ?: lhs.sid ?: lhs.id
            val rhsName = rhs.identity ?: rhs.sid ?: rhs.id
            return lhsName.compareTo(rhsName).takeIf { it != 0 }
                ?: lhs.id.compareTo(rhs.id)
        }
    }
}

class NativeMatrixRtcCallParticipantStore(roomId: String? = null) {
    var snapshot: NativeMatrixRtcCallParticipantsSnapshot =
        NativeMatrixRtcCallParticipantsSnapshot(roomId = roomId)
        private set

    fun reset(roomId: String?): NativeMatrixRtcCallParticipantsSnapshot {
        snapshot = NativeMatrixRtcCallParticipantsSnapshot(roomId = roomId)
        return snapshot
    }

    fun setLocalIdentity(identity: String?): NativeMatrixRtcCallParticipantsSnapshot {
        snapshot = snapshot.copy(localIdentity = identity)
        return snapshot
    }

    fun apply(
        event: MatrixRtcLiveKitRoomSessionEvent
    ): NativeMatrixRtcCallParticipantsSnapshot? {
        when (event) {
            MatrixRtcLiveKitRoomSessionEvent.Connected,
            is MatrixRtcLiveKitRoomSessionEvent.Disconnected,
            is MatrixRtcLiveKitRoomSessionEvent.FailedToConnect,
            MatrixRtcLiveKitRoomSessionEvent.Reconnecting,
            MatrixRtcLiveKitRoomSessionEvent.Reconnected -> return null

            is MatrixRtcLiveKitRoomSessionEvent.LocalTrackPublished ->
                upsertLocalTrack(event.publication)

            is MatrixRtcLiveKitRoomSessionEvent.LocalTrackUnpublished ->
                removeLocalTrack(event.publication)

            is MatrixRtcLiveKitRoomSessionEvent.LocalVideoTrackPublished ->
                upsertLocalVideoTrack(event.publication, event.videoTrack)

            is MatrixRtcLiveKitRoomSessionEvent.LocalVideoTrackUnpublished ->
                clearLocalVideoTrack(event.publication)

            is MatrixRtcLiveKitRoomSessionEvent.LocalTrackSubscribedByRemote ->
                upsertLocalTrack(event.publication)

            is MatrixRtcLiveKitRoomSessionEvent.RemoteParticipantJoined ->
                upsertRemoteParticipant(event.participant)

            is MatrixRtcLiveKitRoomSessionEvent.RemoteParticipantLeft ->
                removeRemoteParticipant(event.participant)

            is MatrixRtcLiveKitRoomSessionEvent.RemoteTrackPublished ->
                upsertRemoteTrack(event.participant, event.publication)

            is MatrixRtcLiveKitRoomSessionEvent.RemoteTrackUnpublished ->
                removeRemoteTrack(event.participant, event.publication)

            is MatrixRtcLiveKitRoomSessionEvent.RemoteTrackSubscribed ->
                upsertRemoteTrack(event.participant, event.publication)

            is MatrixRtcLiveKitRoomSessionEvent.RemoteVideoTrackSubscribed ->
                upsertRemoteVideoTrack(event.participant, event.publication, event.videoTrack)

            is MatrixRtcLiveKitRoomSessionEvent.RemoteTrackUnsubscribed -> {
                upsertRemoteTrack(event.participant, event.publication)
                clearRemoteVideoTrack(event.participant, event.publication)
            }

            is MatrixRtcLiveKitRoomSessionEvent.RemoteVideoTrackUnsubscribed ->
                clearRemoteVideoTrack(event.participant, event.publication)

            is MatrixRtcLiveKitRoomSessionEvent.RemoteTrackSubscriptionFailed ->
                markRemoteTrackSubscriptionFailed(event.participant, event.trackSid, event.error)

            is MatrixRtcLiveKitRoomSessionEvent.TrackMutedChanged ->
                updateMutedState(event.participant, event.publication, event.isMuted)

            is MatrixRtcLiveKitRoomSessionEvent.RemoteTrackStreamStateChanged ->
                updateRemoteTrackStreamState(event.publication, event.state)

            is MatrixRtcLiveKitRoomSessionEvent.SpeakingParticipantsChanged ->
                updateSpeakingParticipants(event.participants)

            is MatrixRtcLiveKitRoomSessionEvent.TrackE2EEStateChanged ->
                updateTrackE2EEState(event.publication, event.state)

            is MatrixRtcLiveKitRoomSessionEvent.MediaKeyApplied,
            is MatrixRtcLiveKitRoomSessionEvent.AudioOutputChanged -> return null
        }

        return snapshot
    }

    private fun upsertLocalTrack(publication: MatrixRtcLiveKitTrackPublicationInfo) {
        val track = snapshot.localTracks[publication.sid]
            ?.updateFrom(publication)
            ?: trackState(publication)
        snapshot = snapshot.copy(
            localTracks = snapshot.localTracks + (publication.sid to track)
        )
    }

    private fun removeLocalTrack(publication: MatrixRtcLiveKitTrackPublicationInfo) {
        snapshot = snapshot.copy(
            localTracks = snapshot.localTracks - publication.sid
        )
    }

    private fun upsertLocalVideoTrack(
        publication: MatrixRtcLiveKitTrackPublicationInfo,
        videoTrack: MatrixRtcLiveKitLocalVideoTrack
    ) {
        val track = (
            snapshot.localTracks[publication.sid]
                ?.updateFrom(publication)
                ?: trackState(publication)
            ).copy(localVideoTrack = videoTrack)
        snapshot = snapshot.copy(
            localTracks = snapshot.localTracks + (publication.sid to track)
        )
    }

    private fun clearLocalVideoTrack(publication: MatrixRtcLiveKitTrackPublicationInfo) {
        val track = snapshot.localTracks[publication.sid] ?: return
        snapshot = snapshot.copy(
            localTracks = snapshot.localTracks + (
                publication.sid to track.updateFrom(publication).copy(localVideoTrack = null)
                )
        )
    }

    private fun upsertRemoteParticipant(participant: MatrixRtcLiveKitParticipantInfo) {
        val id = participant.participantStateId
        val current = snapshot.remoteParticipantsById[id]
        val state = (
            current ?: NativeMatrixRtcCallParticipantState(id = id)
            ).copy(
            identity = participant.identity,
            sid = participant.sid
        )
        snapshot = snapshot.copy(
            remoteParticipantsById = snapshot.remoteParticipantsById + (id to state)
        )
    }

    private fun removeRemoteParticipant(participant: MatrixRtcLiveKitParticipantInfo) {
        snapshot = snapshot.copy(
            remoteParticipantsById = snapshot.remoteParticipantsById - participant.participantStateId
        )
    }

    private fun upsertRemoteTrack(
        participant: MatrixRtcLiveKitParticipantInfo,
        publication: MatrixRtcLiveKitTrackPublicationInfo
    ) {
        upsertRemoteParticipant(participant)
        val participantId = participant.participantStateId
        val participantState = requireNotNull(snapshot.remoteParticipantsById[participantId])
        val track = participantState.tracks[publication.sid]
            ?.updateFrom(publication)
            ?.copy(subscriptionError = null)
            ?: trackState(publication)
        snapshot = snapshot.copy(
            remoteParticipantsById = snapshot.remoteParticipantsById + (
                participantId to participantState.copy(
                    tracks = participantState.tracks + (publication.sid to track)
                )
                )
        )
    }

    private fun removeRemoteTrack(
        participant: MatrixRtcLiveKitParticipantInfo,
        publication: MatrixRtcLiveKitTrackPublicationInfo
    ) {
        val participantId = participant.participantStateId
        val participantState = snapshot.remoteParticipantsById[participantId] ?: return
        snapshot = snapshot.copy(
            remoteParticipantsById = snapshot.remoteParticipantsById + (
                participantId to participantState.copy(
                    tracks = participantState.tracks - publication.sid
                )
                )
        )
    }

    private fun upsertRemoteVideoTrack(
        participant: MatrixRtcLiveKitParticipantInfo,
        publication: MatrixRtcLiveKitTrackPublicationInfo,
        videoTrack: MatrixRtcLiveKitRemoteVideoTrack
    ) {
        upsertRemoteTrack(participant, publication)
        val participantId = participant.participantStateId
        val participantState = snapshot.remoteParticipantsById[participantId] ?: return
        val track = participantState.tracks[publication.sid] ?: return
        snapshot = snapshot.copy(
            remoteParticipantsById = snapshot.remoteParticipantsById + (
                participantId to participantState.copy(
                    tracks = participantState.tracks + (
                        publication.sid to track.copy(remoteVideoTrack = videoTrack)
                        )
                )
                )
        )
    }

    private fun clearRemoteVideoTrack(
        participant: MatrixRtcLiveKitParticipantInfo,
        publication: MatrixRtcLiveKitTrackPublicationInfo
    ) {
        val participantId = participant.participantStateId
        val participantState = snapshot.remoteParticipantsById[participantId] ?: return
        val track = participantState.tracks[publication.sid] ?: return
        snapshot = snapshot.copy(
            remoteParticipantsById = snapshot.remoteParticipantsById + (
                participantId to participantState.copy(
                    tracks = participantState.tracks + (
                        publication.sid to track.updateFrom(publication).copy(remoteVideoTrack = null)
                        )
                )
                )
        )
    }

    private fun markRemoteTrackSubscriptionFailed(
        participant: MatrixRtcLiveKitParticipantInfo,
        trackSid: String,
        error: String
    ) {
        upsertRemoteParticipant(participant)
        val participantId = participant.participantStateId
        val participantState = requireNotNull(snapshot.remoteParticipantsById[participantId])
        val track = participantState.tracks[trackSid]
            ?: NativeMatrixRtcCallTrackState(
                sid = trackSid,
                name = "",
                kind = "",
                source = "",
                isMuted = false,
                isSubscribed = false
            )
        snapshot = snapshot.copy(
            remoteParticipantsById = snapshot.remoteParticipantsById + (
                participantId to participantState.copy(
                    tracks = participantState.tracks + (trackSid to track.copy(subscriptionError = error))
                )
                )
        )
    }

    private fun updateMutedState(
        participant: MatrixRtcLiveKitParticipantInfo,
        publication: MatrixRtcLiveKitTrackPublicationInfo,
        isMuted: Boolean
    ) {
        val localTrack = snapshot.localTracks[publication.sid]
        if (localTrack != null) {
            snapshot = snapshot.copy(
                localTracks = snapshot.localTracks + (
                    publication.sid to localTrack.updateFrom(publication).copy(isMuted = isMuted)
                    )
            )
            return
        }

        upsertRemoteTrack(participant, publication)
        val participantId = participant.participantStateId
        val participantState = snapshot.remoteParticipantsById[participantId] ?: return
        val track = participantState.tracks[publication.sid] ?: return
        snapshot = snapshot.copy(
            remoteParticipantsById = snapshot.remoteParticipantsById + (
                participantId to participantState.copy(
                    tracks = participantState.tracks + (
                        publication.sid to track.updateFrom(publication).copy(isMuted = isMuted)
                        )
                )
                )
        )
    }

    private fun updateRemoteTrackStreamState(
        publication: MatrixRtcLiveKitTrackPublicationInfo,
        streamState: String
    ) {
        val remoteParticipants = snapshot.remoteParticipantsById.mapValues { (_, participant) ->
            val track = participant.tracks[publication.sid] ?: return@mapValues participant
            participant.copy(
                tracks = participant.tracks + (
                    publication.sid to track.updateFrom(publication).copy(streamState = streamState)
                    )
            )
        }
        snapshot = snapshot.copy(remoteParticipantsById = remoteParticipants)
    }

    private fun updateTrackE2EEState(
        publication: MatrixRtcLiveKitTrackPublicationInfo,
        e2eeState: String
    ) {
        val localTracks = snapshot.localTracks.toMutableMap()
        localTracks[publication.sid]?.let { track ->
            localTracks[publication.sid] = track.updateFrom(publication).copy(e2eeState = e2eeState)
        }

        val remoteParticipants = snapshot.remoteParticipantsById.mapValues { (_, participant) ->
            val track = participant.tracks[publication.sid] ?: return@mapValues participant
            participant.copy(
                tracks = participant.tracks + (
                    publication.sid to track.updateFrom(publication).copy(e2eeState = e2eeState)
                    )
            )
        }
        snapshot = snapshot.copy(
            localTracks = localTracks,
            remoteParticipantsById = remoteParticipants
        )
    }

    private fun updateSpeakingParticipants(
        participants: List<MatrixRtcLiveKitSpeakingParticipantInfo>
    ) {
        val participantsById = participants.associateBy { it.participantStateId }
        val localIdentity = snapshot.localIdentity
        val localSpeaking = if (localIdentity != null && participantsById[localIdentity] != null) {
            snapshot.localSpeaking.updateFrom(requireNotNull(participantsById[localIdentity]))
        } else {
            snapshot.localSpeaking.stopSpeaking()
        }

        val remoteParticipants = snapshot.remoteParticipantsById.mapValues { (id, participant) ->
            val speakingParticipant = participantsById[id]
            if (speakingParticipant != null) {
                participant.copy(speaking = participant.speaking.updateFrom(speakingParticipant))
            } else {
                participant.copy(speaking = participant.speaking.stopSpeaking())
            }
        }
        snapshot = snapshot.copy(
            localSpeaking = localSpeaking,
            remoteParticipantsById = remoteParticipants
        )
    }

    private fun trackState(
        publication: MatrixRtcLiveKitTrackPublicationInfo
    ): NativeMatrixRtcCallTrackState {
        return NativeMatrixRtcCallTrackState(
            sid = publication.sid,
            name = publication.name,
            kind = publication.kind,
            source = publication.source,
            isMuted = publication.isMuted,
            isSubscribed = publication.isSubscribed
        )
    }
}

private val MatrixRtcLiveKitParticipantInfo.participantStateId: String
    get() = identity ?: sid ?: "unknown"

private val MatrixRtcLiveKitSpeakingParticipantInfo.participantStateId: String
    get() = identity ?: sid ?: "unknown"
