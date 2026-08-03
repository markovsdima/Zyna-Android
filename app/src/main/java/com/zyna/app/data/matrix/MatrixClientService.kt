package com.zyna.app.data.matrix

import android.content.Context
import android.util.Log
import com.zyna.app.BuildConfig
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCancellable
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallMembershipParser
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallNotificationContent
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallNotificationType
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallTimelineMembership
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallTimelineNotification
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCustomToDeviceEncrypting
import com.zyna.app.data.calls.matrixrtc.MatrixRtcIncomingCall
import com.zyna.app.data.calls.matrixrtc.MatrixRtcLegacyCallNotifyContent
import com.zyna.app.data.calls.matrixrtc.MatrixRtcOwnDevice
import com.zyna.app.data.calls.matrixrtc.MatrixRtcRawMembershipEvent
import com.zyna.app.data.calls.matrixrtc.MatrixRustSdkRtcToDeviceClient
import com.zyna.app.data.calls.matrixrtc.MatrixRustSdkRtcCallNotificationClient
import com.zyna.app.data.calls.matrixrtc.MatrixRustSdkRtcLiveKitFocusClient
import com.zyna.app.data.calls.matrixrtc.MatrixRustSdkRtcMembershipClient
import com.zyna.app.data.calls.matrixrtc.MatrixRustSdkRtcSessionMembershipClient
import com.zyna.app.data.media.BlurHashCodec
import com.zyna.app.data.messaging.CaptionPlacement
import com.zyna.app.data.messaging.MediaGroupLayoutOverride
import com.zyna.app.data.local.TimelineFlushSummary
import com.zyna.app.data.messaging.ZynaHtmlCodec
import com.zyna.app.data.messaging.ZynaMessageAttributes
import com.zyna.app.data.messaging.normalizedMessageCaption
import com.zyna.app.data.push.MatrixPushRegistrar
import com.zyna.app.data.presence.PresenceSession
import com.zyna.app.data.push.ZynaPushNotificationContent
import com.zyna.app.data.push.ZynaPushNotificationResolution
import com.zyna.app.data.security.MatrixSessionSecurityAction
import com.zyna.app.data.security.MatrixLogoutWarning
import com.zyna.app.data.security.MatrixSessionSecurityService
import com.zyna.app.data.security.MatrixSessionSecurityState
import com.zyna.app.data.session.MatrixSessionStore
import com.zyna.app.data.session.MatrixStorePassphraseStore
import java.io.File
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.matrix.rustcomponents.sdk.CallDeclineListener
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.matrix.rustcomponents.sdk.DateDividerMode
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.ClientSessionDelegate
import org.matrix.rustcomponents.sdk.EmbeddedEventDetails
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.EventTimelineItem
import org.matrix.rustcomponents.sdk.FormattedBody
import org.matrix.rustcomponents.sdk.ImageInfo
import org.matrix.rustcomponents.sdk.ImageMessageContent
import org.matrix.rustcomponents.sdk.JoinRule
import org.matrix.rustcomponents.sdk.LatestEventValue
import org.matrix.rustcomponents.sdk.MediaFileHandle
import org.matrix.rustcomponents.sdk.MediaSource
import org.matrix.rustcomponents.sdk.MessageFormat
import org.matrix.rustcomponents.sdk.MessageContent
import org.matrix.rustcomponents.sdk.MessageLikeEventContent
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.MembershipState
import org.matrix.rustcomponents.sdk.MsgLikeContent
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.NotificationEvent
import org.matrix.rustcomponents.sdk.NotificationItem
import org.matrix.rustcomponents.sdk.NotificationProcessSetup
import org.matrix.rustcomponents.sdk.NotificationStatus
import org.matrix.rustcomponents.sdk.ProfileDetails
import org.matrix.rustcomponents.sdk.PowerLevel
import org.matrix.rustcomponents.sdk.RawRoomRelationsDirection
import org.matrix.rustcomponents.sdk.RawRoomRelationsOptions
import org.matrix.rustcomponents.sdk.ReceiptType
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomInfo
import org.matrix.rustcomponents.sdk.RoomInfoListener
import org.matrix.rustcomponents.sdk.RoomHistoryVisibility
import org.matrix.rustcomponents.sdk.RoomListLoadingState
import org.matrix.rustcomponents.sdk.RoomListLoadingStateListener
import org.matrix.rustcomponents.sdk.RoomListService
import org.matrix.rustcomponents.sdk.RoomPreset
import org.matrix.rustcomponents.sdk.RoomVisibility
import org.matrix.rustcomponents.sdk.RtcCallIntent
import org.matrix.rustcomponents.sdk.RtcCallIntentConsensus
import org.matrix.rustcomponents.sdk.RtcNotificationType
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SpaceService
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.StateEventType
import org.matrix.rustcomponents.sdk.SyncNotificationListener
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineConfiguration
import org.matrix.rustcomponents.sdk.isRoomAliasFormatValid
import org.matrix.rustcomponents.sdk.roomAliasNameFromRoomDisplayName
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineEventContent
import org.matrix.rustcomponents.sdk.TimelineFilter
import org.matrix.rustcomponents.sdk.TimelineFocus
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener
import org.matrix.rustcomponents.sdk.UserProfile
import org.matrix.rustcomponents.sdk.UserPowerLevelUpdate
import org.matrix.rustcomponents.sdk.genTransactionId
import org.matrix.rustcomponents.sdk.use
import org.matrix.rustcomponents.sdk.RoomMember as RustRoomMember
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext
import uniffi.matrix_sdk_base.EncryptionState
import uniffi.matrix_sdk.BackupDownloadStrategy
import uniffi.matrix_sdk.RoomMemberRole
import uniffi.matrix_sdk_ui.LatestEventValueLocalState
import uniffi.matrix_sdk_ui.TimelineReadReceiptTracking

sealed interface MatrixClientState {
    data object LoggedOut : MatrixClientState
    data class RestoringSession(val userId: String) : MatrixClientState
    data object LoggingIn : MatrixClientState
    data class LoggedIn(val userId: String) : MatrixClientState
    data class Syncing(val userId: String) : MatrixClientState
    data class Error(val message: String) : MatrixClientState
}

data class MatrixOwnProfile(
    val userId: String,
    val displayName: String?,
    val avatarUrl: String?
)

data class MatrixUserProfile(
    val userId: String,
    val displayName: String?,
    val avatarUrl: String?
) {
    val effectiveDisplayName: String
        get() = displayName?.takeIf { it.isNotBlank() } ?: userId
}

data class MatrixContact(
    val userId: String,
    val displayName: String,
    val avatarUrl: String?,
    val roomId: String?
)

enum class MatrixRoomKind {
    DIRECT,
    GROUP,
    SPACE
}

data class MatrixRoomSummary(
    val id: String,
    val displayName: String,
    val avatarUrl: String?,
    val directUserId: String? = null,
    val isSpace: Boolean = false,
    val membership: MatrixSpaceMembership = MatrixSpaceMembership.UNKNOWN,
    val lastMessageText: String? = null,
    val lastMessageSenderName: String? = null,
    val lastMessageAtMillis: Long? = null,
    val lastOwnMessageStatus: MatrixLastOwnMessageStatus? = null,
    val unreadCount: Long = 0,
    val unreadMentionCount: Long = 0,
    val isMarkedUnread: Boolean = false,
    val roomDetails: MatrixRoomDetails? = null
) {
    val kind: MatrixRoomKind
        get() = when {
            isSpace -> MatrixRoomKind.SPACE
            !directUserId.isNullOrBlank() -> MatrixRoomKind.DIRECT
            else -> MatrixRoomKind.GROUP
        }

    val isJoined: Boolean
        get() = membership == MatrixSpaceMembership.JOINED
}

internal data class MatrixRoomPreview(
    val body: String? = null,
    val senderName: String? = null,
    val timestampMillis: Long? = null,
    val localOwnMessageStatus: MatrixLastOwnMessageStatus? = null,
    val needsReadReceiptSummary: Boolean = false
)

internal fun matrixRoomPreviewForTimelineEvent(
    body: String?,
    senderName: String?,
    timestampMillis: Long,
    localOwnMessageStatus: MatrixLastOwnMessageStatus?,
    needsReadReceiptSummary: Boolean = false
): MatrixRoomPreview {
    if (body == null) {
        return MatrixRoomPreview()
    }
    return MatrixRoomPreview(
        body = body,
        senderName = senderName,
        timestampMillis = timestampMillis,
        localOwnMessageStatus = localOwnMessageStatus,
        needsReadReceiptSummary = needsReadReceiptSummary
    )
}

internal fun matrixRoomPreviewForInvite(timestampMillis: Long): MatrixRoomPreview {
    return MatrixRoomPreview(timestampMillis = timestampMillis)
}

data class MatrixRoomCallInfo(
    val roomId: String,
    val hasRoomCall: Boolean,
    val activeParticipantUserIds: List<String>,
    val isAudioCall: Boolean
) {
    val activeParticipantCount: Int
        get() = activeParticipantUserIds.size
}

enum class MatrixIncomingRtcCallNotificationKind {
    RING,
    NOTIFICATION
}

data class MatrixIncomingRtcCallNotification(
    val eventId: String,
    val roomId: String,
    val senderId: String,
    val kind: MatrixIncomingRtcCallNotificationKind,
    val isAudioCall: Boolean,
    val expiresAtMillis: Long
)

enum class MatrixLastOwnMessageStatus {
    PENDING,
    SENT,
    READ,
    FAILED
}

enum class MatrixMessageDeliveryState {
    SENT,
    SENDING,
    FAILED
}

enum class MatrixMessageContentType {
    TEXT,
    NOTICE,
    EMOTE,
    IMAGE,
    AUDIO,
    VIDEO,
    FILE,
    GALLERY,
    LOCATION,
    UNABLE_TO_DECRYPT,
    REDACTED,
    SYSTEM_EVENT,
    MATRIX_RTC_CALL,
    UNSUPPORTED
}

data class MatrixReplyInfo(
    val eventId: String,
    val senderId: String,
    val senderDisplayName: String?,
    val body: String
)

data class MatrixEditTarget(
    val messageId: String,
    val eventId: String,
    val body: String
)

data class MatrixForwardTarget(
    val body: String,
    val forwardedFrom: String?,
    val caption: String? = null,
    val imageItems: List<MatrixForwardImageItem> = emptyList(),
    val captionPlacement: CaptionPlacement = CaptionPlacement.BOTTOM,
    val layoutOverride: MediaGroupLayoutOverride? = null
)

data class MatrixForwardImageItem(
    val sourceJson: String,
    val thumbnailSourceJson: String?,
    val width: Int?,
    val height: Int?,
    val caption: String?,
    val mimeType: String?,
    val blurhash: String?
)

data class MatrixImageInfo(
    val sourceJson: String,
    val thumbnailSourceJson: String?,
    val width: Int?,
    val height: Int?,
    val caption: String?,
    val mimeType: String?,
    val blurhash: String?,
    val localPath: String? = null
)

data class MatrixAudioInfo(
    val sourceJson: String,
    val filename: String?,
    val caption: String?,
    val mimeType: String?,
    val sizeBytes: Long?,
    val durationMillis: Long?,
    val waveform: List<Float> = emptyList(),
    val isVoice: Boolean = false,
    val localPath: String? = null
)

data class MatrixMediaGroupItem(
    val messageId: String,
    val eventId: String?,
    val transactionId: String?,
    val imageInfo: MatrixImageInfo,
    val deliveryState: MatrixMessageDeliveryState
)

data class MatrixMediaGroupPresentation(
    val id: String,
    val totalHint: Int,
    val caption: String?,
    val captionPlacement: com.zyna.app.data.messaging.CaptionPlacement,
    val layoutOverride: com.zyna.app.data.messaging.MediaGroupLayoutOverride?,
    val suppressIndividualCaption: Boolean,
    val items: List<MatrixMediaGroupItem>,
    val rendersCompositeBubble: Boolean,
    val hidesStandaloneBubble: Boolean
)

data class MatrixReactionSender(
    val userId: String,
    val timestampMillis: Long
)

data class MatrixMessageReaction(
    val key: String,
    val senders: List<MatrixReactionSender>,
    val isOwn: Boolean,
    val isPendingRemoval: Boolean = false,
    val legacyCount: Int? = null
) {
    val count: Int
        get() = maxOf(senders.size, legacyCount ?: 0)
}

data class MatrixChatMessage(
    /** Stable UI/cache identity: eventId, transactionId, or local outbox id. */
    val id: String,
    val eventId: String? = null,
    val transactionId: String? = null,
    val sender: String,
    val senderDisplayName: String? = null,
    val body: String,
    val timestampMillis: Long,
    val isOwn: Boolean,
    val contentType: MatrixMessageContentType = MatrixMessageContentType.TEXT,
    val imageInfo: MatrixImageInfo? = null,
    val audioInfo: MatrixAudioInfo? = null,
    val deliveryState: MatrixMessageDeliveryState = MatrixMessageDeliveryState.SENT,
    val replyInfo: MatrixReplyInfo? = null,
    val forwardedFrom: String? = null,
    val zynaAttributes: ZynaMessageAttributes = ZynaMessageAttributes(),
    val isEdited: Boolean = false,
    val isEditPending: Boolean = false,
    val isEditFailed: Boolean = false,
    val latestEditEventId: String? = null,
    val editTransactionId: String? = null,
    val pendingEditBody: String? = null,
    val outgoingEnvelopeId: String? = null,
    val canRetryOutgoingEnvelope: Boolean = false,
    val canDiscardOutgoingEnvelope: Boolean = false,
    val reactions: List<MatrixMessageReaction> = emptyList(),
    val mediaGroupPresentation: MatrixMediaGroupPresentation? = null,
    val systemEventDetails: MatrixSystemEventDetails? = null,
    val matrixRtcCallDetails: MatrixRtcCallEventDetails? = null
) {
    val isRemote: Boolean
        get() = eventId != null
}

class MatrixClientService(
    private val context: Context,
    private val sessionStore: MatrixSessionStore,
    private val storePassphraseStore: MatrixStorePassphraseStore,
    private val pushRegistrar: MatrixPushRegistrar
) {
    private val _state = MutableStateFlow<MatrixClientState>(MatrixClientState.LoggedOut)
    val state: StateFlow<MatrixClientState> = _state.asStateFlow()

    private val sessionSecurityService = MatrixSessionSecurityService(sessionStore)
    val sessionSecurityState: StateFlow<MatrixSessionSecurityState> = sessionSecurityService.state

    private val sessionDelegate = AndroidMatrixSessionDelegate(sessionStore)

    private var client: Client? = null
    private var syncService: SyncService? = null
    private var roomListService: RoomListService? = null
    private val roomListSessionsLock = Any()
    private val activeRoomListSessions = mutableSetOf<SdkMatrixRoomListSession>()
    private var matrixRtcNotificationHandlerClient: Client? = null
    private val deliveredMatrixRtcNotificationIds = LinkedHashSet<String>()
    private val _incomingMatrixRtcCallNotifications =
        MutableSharedFlow<MatrixIncomingRtcCallNotification>(extraBufferCapacity = 64)
    val incomingMatrixRtcCallNotifications: SharedFlow<MatrixIncomingRtcCallNotification> =
        _incomingMatrixRtcCallNotifications.asSharedFlow()
    private val activeTimelineLock = Any()
    private val activeRoomTimelines = mutableMapOf<String, Timeline>()
    private val sessionRestoreMutex = Mutex()
    private val timelinePaginationMutex = Mutex()
    private val notificationResolutionMutex = Mutex()

    suspend fun restoreSessionIfAvailable() {
        sessionRestoreMutex.withLock {
            restoreSessionIfAvailableLocked()
        }
    }

    suspend fun ensureSessionRestored(): Boolean {
        sessionRestoreMutex.withLock {
            if (client != null) {
                startSync()
                return true
            }

            restoreSessionIfAvailableLocked()
            return client != null
        }
    }

    suspend fun currentPresenceSessionOrNull(): PresenceSession? = withContext(Dispatchers.IO) {
        val activeSession = client?.let { activeClient ->
            runCatching { activeClient.session() }.getOrNull()
        } ?: sessionStore.loadLastSession()

        activeSession?.let { session ->
            PresenceSession(
                homeserverUrl = session.homeserverUrl,
                accessToken = session.accessToken,
                userId = session.userId
            )
        }
    }

    /**
     * Opens the SDK Spaces boundary for the requested active account.
     *
     * MatrixSpaceService owns and disposes the returned FFI object. Keeping creation here avoids
     * exposing the session Client outside the data layer while preserving MatrixClientService as
     * the owner of client/session lifetime.
     */
    internal suspend fun openSpaceService(userId: String): SpaceService {
        return withFfiResourceHandoff(
            release = { service -> service.destroy() }
        ) { own ->
            val activeClient = client ?: error("Matrix client is not available")
            val activeUserId = when (val current = state.value) {
                is MatrixClientState.LoggedIn -> current.userId
                is MatrixClientState.Syncing -> current.userId
                else -> null
            }
            check(activeUserId == userId) { "Matrix session changed while opening Spaces" }
            val service = activeClient.spaceService().also(own)
            val stillCurrent = client === activeClient && when (val current = state.value) {
                is MatrixClientState.LoggedIn -> current.userId == userId
                is MatrixClientState.Syncing -> current.userId == userId
                else -> false
            }
            check(stillCurrent) { "Matrix session changed while opening Spaces" }
            service
        }
    }

    /** Opens an ordered, paginated room-list boundary owned by the caller. */
    internal suspend fun openRoomListSession(
        userId: String
    ): MatrixRoomListSession = withContext(Dispatchers.IO) {
        withFfiResourceHandoff<SdkMatrixRoomListSession>(
            release = SdkMatrixRoomListSession::close
        ) { own ->
            val service = synchronized(roomListSessionsLock) {
                roomListService
            } ?: error("Matrix room list service is not available")
            check(activeMatrixUserId() == userId) {
                "Matrix session changed while opening the room list"
            }
            val list = service.allRooms()
            val session = try {
                SdkMatrixRoomListSession(
                    roomList = list,
                    roomListService = service,
                    roomEntryMapper = { room -> room.toRoomListEntry() },
                    onClosed = { closed ->
                        synchronized(roomListSessionsLock) {
                            activeRoomListSessions.remove(closed)
                        }
                    }
                )
            } catch (error: Throwable) {
                runCatching { list.destroy() }
                throw error
            }
            own(session)
            session.start()
            val registered = synchronized(roomListSessionsLock) {
                if (roomListService === service && activeMatrixUserId() == userId) {
                    activeRoomListSessions += session
                    true
                } else {
                    false
                }
            }
            check(registered) { "Matrix session changed while opening the room list" }
            session
        }
    }

    private suspend fun restoreSessionIfAvailableLocked() {
        if (client != null) {
            startSync()
            return
        }

        val session = sessionStore.loadLastSession()
        if (session == null) {
            sessionSecurityService.detach()
            clearStoredMatrixState()
            _state.value = MatrixClientState.LoggedOut
            return
        }

        _state.value = MatrixClientState.RestoringSession(session.userId)
        var restoredClient: Client? = null
        try {
            restoredClient = buildClient(session.homeserverUrl)
            restoredClient.restoreSession(session)
            client = restoredClient
            restoredClient = null
            _state.value = MatrixClientState.LoggedIn(session.userId)
            startSync()
            client?.let { sessionSecurityService.attach(it) }
        } catch (error: Throwable) {
            sessionSecurityService.detach()
            restoredClient?.close()
            client = null
            matrixRtcNotificationHandlerClient = null
            closeRoomListResources()
            syncService = null
            _state.value = MatrixClientState.Error(error.displayMessage())
        }
    }

    suspend fun login(homeserver: String, username: String, password: String) {
        _state.value = MatrixClientState.LoggingIn
        var loginClient: Client? = null
        try {
            resetClientForFreshLogin()
            loginClient = buildClient(normalizeHomeserver(homeserver))
            loginClient.login(
                username = username.trim(),
                password = password,
                initialDeviceName = "Zyna Android",
                deviceId = null
            )

            val session = loginClient.session()
            sessionStore.save(session)
            client = loginClient
            loginClient = null
            _state.value = MatrixClientState.LoggedIn(session.userId)
            startSync()
            client?.let { sessionSecurityService.attach(it) }
        } catch (error: Throwable) {
            sessionSecurityService.detach()
            loginClient?.close()
            client = null
            matrixRtcNotificationHandlerClient = null
            closeRoomListResources()
            syncService = null
            clearStoredMatrixState()
            _state.value = MatrixClientState.Error(error.displayMessage())
        }
    }

    private suspend fun resetClientForFreshLogin() {
        sessionSecurityService.detach()
        closeRoomListResources()
        syncService?.stop()
        syncService?.close()
        syncService = null
        client?.let { activeClient ->
            unregisterPushPusher(activeClient)
        }
        client?.close()
        client = null
        matrixRtcNotificationHandlerClient = null
        clearStoredMatrixState()
    }

    suspend fun logout() {
        val activeClient = client
        runCatching { sessionSecurityService.detach() }
            .onFailure { Log.w(TAG, "Failed to detach session security during logout", it) }
        runCatching { closeRoomListResources() }
            .onFailure { Log.w(TAG, "Failed to close room list during logout", it) }
        runCatching { syncService?.stop() }
            .onFailure { Log.w(TAG, "Failed to stop sync during logout", it) }
        runCatching { syncService?.close() }
            .onFailure { Log.w(TAG, "Failed to close sync during logout", it) }
        syncService = null
        activeClient?.let { logoutClient ->
            unregisterPushPusher(logoutClient)
            runCatching { logoutClient.logout() }
                .onFailure { Log.w(TAG, "Server logout failed; continuing locally", it) }
        }
        runCatching { activeClient?.close() }
            .onFailure { Log.w(TAG, "Failed to close Matrix client during logout", it) }
        client = null
        matrixRtcNotificationHandlerClient = null
        runCatching { clearStoredMatrixState() }
            .onFailure { Log.w(TAG, "Failed to clear part of the local Matrix state", it) }
        _state.value = MatrixClientState.LoggedOut
    }

    suspend fun prepareForLogout(): MatrixLogoutWarning? {
        return sessionSecurityService.prepareForLogout()
    }

    fun handleSessionSecurityAction(action: MatrixSessionSecurityAction) {
        sessionSecurityService.handle(action)
    }

    fun isSessionSecurityReady(userId: String): Boolean {
        val securityState = sessionSecurityState.value
        return securityState.userId == userId &&
            securityState.readyForEncryptedTraffic
    }

    suspend fun loadOwnProfile(): MatrixOwnProfile = withContext(Dispatchers.IO) {
        val activeClient = client ?: error("Matrix client is not ready")
        MatrixOwnProfile(
            userId = activeClient.userId(),
            displayName = activeClient.displayName()?.takeIf { it.isNotBlank() },
            avatarUrl = activeClient.avatarUrl()?.takeIf { it.isNotBlank() }
        )
    }

    suspend fun loadUserProfile(userId: String): MatrixUserProfile = withContext(Dispatchers.IO) {
        val activeClient = client ?: error("Matrix client is not ready")
        val normalizedUserId = userId.trim()
        require(normalizedUserId.isNotEmpty()) { "User ID is required" }

        activeClient.getProfile(normalizedUserId).toMatrixUserProfile()
    }

    suspend fun searchUsers(searchTerm: String, limit: Int): List<MatrixUserProfile> =
        withContext(Dispatchers.IO) {
            val activeClient = client ?: error("Matrix client is not ready")
            val normalizedSearchTerm = searchTerm.trim()
            if (normalizedSearchTerm.isEmpty()) {
                return@withContext emptyList()
            }

            val ownUserId = runCatching { activeClient.userId() }.getOrNull()
            activeClient
                .searchUsers(
                    searchTerm = normalizedSearchTerm,
                    limit = limit.coerceAtLeast(1).toULong()
                )
                .results
                .asSequence()
                .map { it.toMatrixUserProfile() }
                .filter { it.userId.isNotBlank() && it.userId != ownUserId }
                .distinctBy { it.userId }
                .toList()
        }

    suspend fun resolveDirectRoom(
        userId: String,
        fallbackDisplayName: String?,
        fallbackAvatarUrl: String?
    ): MatrixRoomSummary = withContext(Dispatchers.IO) {
        val activeClient = client ?: error("Matrix client is not ready")
        val normalizedUserId = userId.trim()
        require(normalizedUserId.isNotEmpty()) { "User ID is required" }

        val existingDmRoom = activeClient.getDmRoom(normalizedUserId)
        if (existingDmRoom != null) {
            return@withContext existingDmRoom.use { room ->
                room.toRoomSummary().copy(directUserId = normalizedUserId)
            }
        }

        val roomId = activeClient.createRoom(
            request = CreateRoomParameters(
                name = null,
                topic = null,
                isEncrypted = true,
                isDirect = true,
                visibility = RoomVisibility.Private,
                preset = RoomPreset.TRUSTED_PRIVATE_CHAT,
                invite = listOf(normalizedUserId),
                avatar = null,
                powerLevelContentOverride = null,
                joinRuleOverride = null,
                historyVisibilityOverride = null,
                canonicalAlias = null,
                isSpace = false
            )
        )

        val createdRoom = activeClient.getRoom(roomId)
        if (createdRoom != null) {
            return@withContext createdRoom.use { room ->
                room.toRoomSummary().copy(directUserId = normalizedUserId)
            }
        }

        MatrixRoomSummary(
            id = roomId,
            displayName = fallbackDisplayName?.takeIf { it.isNotBlank() } ?: normalizedUserId,
            avatarUrl = fallbackAvatarUrl?.takeIf { it.isNotBlank() },
            directUserId = normalizedUserId
        )
    }

    suspend fun uploadMedia(
        localPath: String,
        mimeType: String
    ): String = withContext(Dispatchers.IO) {
        val activeClient = client ?: error("Matrix client is not ready")
        val mediaFile = File(localPath)
        require(mediaFile.isFile) { "Media file is not available" }
        activeClient.uploadMedia(
            mimeType = mimeType.ifBlank { "application/octet-stream" },
            data = mediaFile.readBytes(),
            progressWatcher = null
        )
    }

    fun suggestRoomAliasLocalPart(name: String): String {
        return roomAliasNameFromRoomDisplayName(name)
    }

    fun isRoomAliasValid(alias: String): Boolean {
        return isRoomAliasFormatValid(alias)
    }

    suspend fun isRoomAliasAvailable(alias: String): Boolean = withContext(Dispatchers.IO) {
        val activeClient = client ?: error("Matrix client is not ready")
        activeClient.isRoomAliasAvailable(alias)
    }

    suspend fun createRoom(
        request: MatrixRoomCreationRequest
    ): MatrixRoomSummary = withContext(Dispatchers.IO) {
        val activeClient = client ?: error("Matrix client is not ready")
        val parameters = request.toCreateRoomParameters()
        val normalizedName = requireNotNull(parameters.name)
        val normalizedAvatarUrl = parameters.avatar?.takeIf { it.isNotBlank() }
        val isSpace = request.kind == MatrixRoomCreationKind.SPACE
        val canonicalAlias = parameters.canonicalAlias?.let { localPart ->
            activeClient.userId()
                .substringAfter(':', missingDelimiterValue = "")
                .takeIf { it.isNotBlank() }
                ?.let { serverName -> "#$localPart:$serverName" }
        }

        // Resolve every fallible input before createRoom: after it returns, the remote mutation is
        // irreversible and must always be reported as success to the feature coordinator.
        val roomId = activeClient.createRoom(
            request = parameters
        )

        MatrixRoomSummary(
            id = roomId,
            displayName = normalizedName,
            avatarUrl = normalizedAvatarUrl,
            isSpace = isSpace,
            membership = MatrixSpaceMembership.JOINED,
            roomDetails = MatrixRoomDetails(
                roomId = roomId,
                displayName = normalizedName,
                avatarUrl = normalizedAvatarUrl,
                directUserId = null,
                kind = if (isSpace) MatrixRoomKind.SPACE else MatrixRoomKind.GROUP,
                topic = parameters.topic,
                joinedMemberCount = 1,
                encryption = if (parameters.isEncrypted) {
                    MatrixRoomEncryption.ENCRYPTED
                } else {
                    MatrixRoomEncryption.NOT_ENCRYPTED
                },
                access = when (request.access) {
                    MatrixRoomCreationAccess.Private -> MatrixRoomAccess.PRIVATE
                    MatrixRoomCreationAccess.Public -> MatrixRoomAccess.PUBLIC
                    is MatrixRoomCreationAccess.Restricted -> MatrixRoomAccess.RESTRICTED
                },
                historyVisibility = if (parameters.historyVisibilityOverride ==
                    RoomHistoryVisibility.Invited
                ) {
                    MatrixRoomHistoryVisibility.INVITED
                } else {
                    MatrixRoomHistoryVisibility.SHARED
                },
                pinnedEventCount = 0,
                canonicalAlias = canonicalAlias,
                capabilities = MatrixRoomCapabilities(
                    canInviteMembers = true,
                    canChangeName = true,
                    canChangeAvatar = true
                )
            )
        )
    }

    internal suspend fun isJoinedToAnyRoom(
        userId: String,
        roomIds: List<String>
    ): Boolean = withContext(Dispatchers.IO) {
        val activeClient = requireActiveClient(userId)
        roomIds.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .any { roomId ->
                knownRoomOrNull(userId, activeClient, roomId)?.use { room ->
                    room.membership() == Membership.JOINED
                } == true
            }
    }

    /**
     * Resolves membership for a Space already represented by the local Room API.
     *
     * Invited and joined Spaces are authoritative here even when SpaceService does not expose
     * them through its hierarchy lookup. The fallback keeps cache-first presentation stable if
     * optional room metadata is temporarily unreadable; only the fresh membership is required for
     * the command preflight.
     */
    internal suspend fun loadLocalSpaceJoinContext(
        userId: String,
        roomId: String,
        fallbackRoom: MatrixSpaceRoom
    ): MatrixSpaceJoinContext? = withContext(Dispatchers.IO) {
        val activeClient = requireActiveClient(userId)
        val normalizedRoomId = roomId.trim().takeIf(String::isNotEmpty)
            ?: error("Room id is empty")
        require(fallbackRoom.roomId == normalizedRoomId) {
            "Fallback room does not match the requested room"
        }
        val localRoom = knownRoomOrNull(userId, activeClient, normalizedRoomId)
            ?: return@withContext null
        localRoom.use { room ->
            val membership = when (room.membership()) {
                Membership.INVITED -> MatrixSpaceMembership.INVITED
                Membership.JOINED -> MatrixSpaceMembership.JOINED
                else -> return@withContext null
            }
            val refreshedRoom = runCatching { room.toRoomSummary() }
                .getOrNull()
                ?.takeIf { it.isSpace }
                ?.toSpaceRoom()
            MatrixSpaceJoinContext(
                room = (refreshedRoom ?: fallbackRoom).copy(membership = membership)
            )
        }
    }

    suspend fun joinRoomFromSpace(
        userId: String,
        roomId: String,
        serverNames: List<String>
    ): MatrixRoomSummary = withContext(Dispatchers.IO) {
        val activeClient = requireActiveClient(userId)
        val normalizedRoomId = roomId.trim().takeIf(String::isNotEmpty)
            ?: error("Room id is empty")
        val normalizedServerNames = serverNames
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        val joinedRoom = if (normalizedServerNames.isEmpty()) {
            // Invites are already known to the homeserver. This path does not depend on the
            // invited room being exposed by Client.getRoom(), which the SDK does not guarantee.
            activeClient.joinRoomById(normalizedRoomId)
        } else {
            activeClient.joinRoomByIdOrAlias(
                roomIdOrAlias = normalizedRoomId,
                serverNames = normalizedServerNames
            )
        }
        joinedRoom.use { room ->
            room.toRoomSummary()
        }
    }

    suspend fun knockRoomFromSpace(
        userId: String,
        roomId: String,
        serverNames: List<String>
    ) = withContext(Dispatchers.IO) {
        val activeClient = requireActiveClient(userId)
        val normalizedRoomId = roomId.trim().takeIf(String::isNotEmpty)
            ?: error("Room id is empty")
        activeClient.knock(
            roomIdOrAlias = normalizedRoomId,
            reason = null,
            serverNames = serverNames.map(String::trim).filter(String::isNotEmpty).distinct()
        ).use { }
    }

    suspend fun setOwnDisplayName(displayName: String) = withContext(Dispatchers.IO) {
        val activeClient = client ?: error("Matrix client is not ready")
        activeClient.setDisplayName(displayName)
    }

    private fun requireActiveClient(userId: String): Client {
        val activeClient = client ?: error("Matrix client is not ready")
        val activeUserId = when (val current = state.value) {
            is MatrixClientState.LoggedIn -> current.userId
            is MatrixClientState.Syncing -> current.userId
            else -> null
        }
        check(activeUserId == userId) { "Matrix session changed" }
        return activeClient
    }

    /**
     * Resolves rooms from the SDK room-list projection before falling back to Client.getRoom().
     *
     * Invited rooms are visible in the room list but are not guaranteed to be returned by
     * Client.getRoom(). The room-list service is therefore the authoritative lookup boundary for
     * membership commands, matching the ownership model used by Element's Rust room factory.
     */
    private fun knownRoomOrNull(userId: String, activeClient: Client, roomId: String): Room? {
        val roomListRoom = synchronized(roomListSessionsLock) {
            val service = roomListService
            if (service == null || activeMatrixUserId() != userId) {
                null
            } else {
                runCatching { service.room(roomId) }.getOrNull()
            }
        }
        return roomListRoom ?: activeClient.getRoom(roomId)
    }

    suspend fun uploadOwnAvatar(
        localPath: String,
        mimeType: String
    ) = withContext(Dispatchers.IO) {
        val activeClient = client ?: error("Matrix client is not ready")
        val avatarFile = File(localPath)
        require(avatarFile.isFile) { "Avatar file is not available" }
        activeClient.uploadAvatar(
            mimeType.ifBlank { "image/jpeg" },
            avatarFile.readBytes()
        )
    }

    suspend fun removeOwnAvatar() = withContext(Dispatchers.IO) {
        val activeClient = client ?: error("Matrix client is not ready")
        activeClient.removeAvatar()
    }

    suspend fun registerPushPusherIfAvailable() {
        val activeClient = client ?: return
        registerPushPusher(activeClient)
    }

    suspend fun resolvePushNotification(
        roomId: String,
        eventId: String,
        unreadCount: Int?
    ): ZynaPushNotificationResolution {
        if (roomId.isBlank() || eventId.isBlank()) {
            return ZynaPushNotificationResolution.Unavailable
        }

        return try {
            withTimeoutOrNull(PUSH_NOTIFICATION_RESOLVE_TIMEOUT_MS) {
                notificationResolutionMutex.withLock {
                    loadPushNotificationResolution(
                        roomId = roomId,
                        eventId = eventId,
                        unreadCount = unreadCount
                    )
                        ?: ZynaPushNotificationResolution.Unavailable
                }
            } ?: ZynaPushNotificationResolution.Unavailable
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to resolve push notification event=$eventId room=$roomId", error)
            ZynaPushNotificationResolution.Unavailable
        }
    }

    fun matrixRtcOwnDevice(): MatrixRtcOwnDevice {
        val activeClient = client ?: error("Matrix client is not ready")
        val session = activeClient.session()
        return MatrixRtcOwnDevice(
            userId = session.userId,
            deviceId = session.deviceId
        )
    }

    fun matrixRtcToDeviceClient(): MatrixRtcCustomToDeviceEncrypting {
        val activeClient = client ?: error("Matrix client is not ready")
        return MatrixRustSdkRtcToDeviceClient(activeClient)
    }

    fun matrixRtcMembershipClient(): MatrixRustSdkRtcMembershipClient {
        val activeClient = client ?: error("Matrix client is not ready")
        return MatrixRustSdkRtcMembershipClient(activeClient)
    }

    fun matrixRtcLiveKitFocusClient(): MatrixRustSdkRtcLiveKitFocusClient {
        val activeClient = client ?: error("Matrix client is not ready")
        return MatrixRustSdkRtcLiveKitFocusClient(activeClient)
    }

    fun matrixRtcCallNotificationClient(roomId: String): MatrixRustSdkRtcCallNotificationClient {
        val activeClient = client ?: error("Matrix client is not ready")
        val room = activeClient.getRoom(roomId) ?: error("Matrix room is not available")
        return MatrixRustSdkRtcCallNotificationClient(room)
    }

    suspend fun declineMatrixRtcCall(
        roomId: String,
        notificationEventId: String
    ) = withContext(Dispatchers.IO) {
        val activeClient = client
        if (activeClient != null) {
            activeClient.declineMatrixRtcCallWithClient(
                roomId = roomId,
                notificationEventId = notificationEventId
            )
            return@withContext
        }

        val session = sessionStore.loadLastSession() ?: error("Matrix session is not available")
        var temporaryClient: Client? = null
        try {
            temporaryClient = buildClient(session.homeserverUrl)
            temporaryClient.restoreSession(session)
            temporaryClient.declineMatrixRtcCallWithClient(
                roomId = roomId,
                notificationEventId = notificationEventId
            )
        } finally {
            temporaryClient?.close()
        }
    }

    private suspend fun Client.declineMatrixRtcCallWithClient(
        roomId: String,
        notificationEventId: String
    ) {
        val room = getRoom(roomId) ?: error("Matrix room is not available")
        try {
            room.declineCall(notificationEventId)
        } finally {
            room.destroy()
        }
    }

    fun subscribeToMatrixRtcCallDeclineEvents(
        roomId: String,
        notificationEventId: String,
        onDecline: (declinerUserId: String) -> Unit
    ): MatrixRtcCancellable {
        val activeClient = client ?: error("Matrix client is not ready")
        val room = activeClient.getRoom(roomId) ?: error("Matrix room is not available")
        var handle: TaskHandle? = null
        try {
            handle = room.subscribeToCallDeclineEvents(
                rtcNotificationEventId = notificationEventId,
                listener = object : CallDeclineListener {
                    override fun call(declinerUserId: String) {
                        onDecline(declinerUserId)
                    }
                }
            )
            return object : MatrixRtcCancellable {
                private val didCancel = AtomicBoolean(false)

                override fun cancel() {
                    if (didCancel.compareAndSet(false, true)) {
                        try {
                            handle?.cancelAndDestroy()
                        } finally {
                            room.destroy()
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            try {
                handle?.cancelAndDestroy()
            } finally {
                room.destroy()
            }
            throw error
        }
    }

    fun matrixRtcSessionMembershipClient(roomId: String): MatrixRustSdkRtcSessionMembershipClient {
        val activeClient = client ?: error("Matrix client is not ready")
        val room = activeClient.getRoom(roomId) ?: error("Matrix room is not available")
        return MatrixRustSdkRtcSessionMembershipClient(
            membershipClient = MatrixRustSdkRtcMembershipClient(activeClient),
            room = room
        )
    }

    suspend fun hasActiveMatrixRtcMembership(roomId: String, senderId: String): Boolean =
        withContext(Dispatchers.IO) {
            matrixRtcMembershipClient()
                .loadActiveMemberships(roomId = roomId, joinedUserIds = setOf(senderId))
                .any { membership ->
                    membership.userId == senderId && membership.callIntent.isAudioCompatible()
                }
        }

    suspend fun loadRoomCallInfo(roomId: String): MatrixRoomCallInfo =
        withContext(Dispatchers.IO) {
            val audioMemberships = matrixRtcMembershipClient()
                .loadActiveMemberships(roomId = roomId)
                .filter { membership ->
                    membership.callIntent.isAudioCompatible()
                }
            MatrixRoomCallInfo(
                roomId = roomId,
                hasRoomCall = audioMemberships.isNotEmpty(),
                activeParticipantUserIds = audioMemberships
                    .map { membership -> membership.userId }
                    .distinct(),
                isAudioCall = true
            )
        }

    suspend fun matrixRtcMediaEncryptionEnabled(roomId: String): Boolean = withContext(Dispatchers.IO) {
        val activeClient = client ?: error("Matrix client is not ready")
        val room = activeClient.getRoom(roomId) ?: error("Matrix room is not available")
        try {
            room.latestEncryptionState() == EncryptionState.ENCRYPTED
        } catch (_: Throwable) {
            room.encryptionState() == EncryptionState.ENCRYPTED
        } finally {
            room.destroy()
        }
    }

    suspend fun loadRoomMembers(
        roomId: String,
        useCachedSnapshot: Boolean
    ): List<MatrixRoomMember> = withContext(Dispatchers.IO) {
        val activeClient = client ?: error("Matrix client is not ready")
        activeClient.getRoom(roomId)?.use { room ->
            val iterator = if (useCachedSnapshot) {
                room.membersNoSync()
            } else {
                room.members()
            }
            iterator.use { members ->
                buildList(
                    capacity = members.len().coerceAtMost(Int.MAX_VALUE.toUInt()).toInt()
                ) {
                    while (true) {
                        coroutineContext.ensureActive()
                        val chunk = members.nextChunk(ROOM_MEMBERS_CHUNK_SIZE.toUInt())
                            ?: break
                        if (chunk.isEmpty()) {
                            break
                        }
                        chunk.mapNotNullTo(this) { member -> member.toMatrixRoomMemberOrNull() }
                    }
                }
            }
        } ?: error("Matrix room is not available")
    }

    /**
     * Loads the member ownership needed by the leave UI. The preview may use the local member
     * snapshot; the destructive preflight always requests a fresh SDK sync.
     */
    suspend fun loadRoomLeaveContext(
        userId: String,
        roomId: String,
        useCachedMembers: Boolean
    ): MatrixRoomLeaveContext = withContext(Dispatchers.IO) {
        val activeClient = requireActiveClient(userId)
        val normalizedRoomId = roomId.trim().takeIf(String::isNotEmpty)
            ?: error("Room id is empty")
        knownRoomOrNull(userId, activeClient, normalizedRoomId)?.use { room ->
            val iterator = if (useCachedMembers) {
                room.membersNoSync()
            } else {
                room.members()
            }
            iterator.use { members ->
                var ownRole: MatrixRoomMemberRole? = null
                var otherOwnerExists = false
                while (true) {
                    coroutineContext.ensureActive()
                    val chunk = members.nextChunk(ROOM_MEMBERS_CHUNK_SIZE.toUInt())
                        ?: break
                    if (chunk.isEmpty()) break
                    chunk.forEach { member ->
                        val mapped = member.toMatrixRoomMemberOrNull() ?: return@forEach
                        if (mapped.membership != MatrixRoomMemberMembership.JOINED) {
                            return@forEach
                        }
                        if (mapped.userId == userId) {
                            ownRole = mapped.role
                        } else if (mapped.role.isRoomOwnershipRole()) {
                            otherOwnerExists = true
                        }
                    }
                }
                val isLastOwner = ownRole?.isRoomOwnershipRole() == true && !otherOwnerExists
                val roomInfo = room.roomInfo()
                try {
                    MatrixRoomLeaveContext(
                        joinedMemberCount = roomInfo.joinedMembersCount
                            .coerceAtMost(Long.MAX_VALUE.toULong())
                            .toLong(),
                        isLastOwner = isLastOwner,
                        areCreatorsPrivileged = roomInfo.privilegedCreatorsRole
                    )
                } finally {
                    roomInfo.destroy()
                }
            }
        } ?: error("Matrix room is not available")
    }

    suspend fun leaveRoom(userId: String, roomId: String) = withContext(Dispatchers.IO) {
        val activeClient = requireActiveClient(userId)
        val normalizedRoomId = roomId.trim().takeIf(String::isNotEmpty)
            ?: error("Room id is empty")
        knownRoomOrNull(userId, activeClient, normalizedRoomId)?.use { room ->
            room.leave()
        } ?: error("Matrix room is not available")
    }

    suspend fun isRoomLeft(userId: String, roomId: String): Boolean =
        withContext(Dispatchers.IO) {
            val activeClient = requireActiveClient(userId)
            val normalizedRoomId = roomId.trim().takeIf(String::isNotEmpty)
                ?: error("Room id is empty")
            knownRoomOrNull(userId, activeClient, normalizedRoomId)?.use { room ->
                room.membership() == Membership.LEFT
            } == true
        }

    suspend fun loadRoomRoleChangeContext(
        roomId: String,
        targetUserId: String
    ): MatrixRoomRoleChangeContext = withContext(Dispatchers.IO) {
        val activeClient = client ?: error("Matrix client is not ready")
        val ownUserId = activeClient.userId()
        activeClient.getRoom(roomId)?.use { room ->
            val roomInfo = room.roomInfo()
            try {
                val ownMember = room.member(ownUserId)
                val member = room.member(targetUserId)
                val powerLevels = roomInfo.powerLevels
                if (powerLevels != null) {
                    powerLevels.toMatrixRoomRoleChangeContext(
                        roomId = roomId,
                        ownUserId = ownUserId,
                        ownMember = ownMember,
                        targetMember = member,
                    )
                } else {
                    room.getPowerLevels().use { loadedPowerLevels ->
                        loadedPowerLevels.toMatrixRoomRoleChangeContext(
                            roomId = roomId,
                            ownUserId = ownUserId,
                            ownMember = ownMember,
                            targetMember = member,
                        )
                    }
                }
            } finally {
                roomInfo.destroy()
            }
        } ?: error("Matrix room is not available")
    }

    suspend fun updateRoomMemberPowerLevel(
        roomId: String,
        userId: String,
        powerLevel: Long
    ) = withContext(Dispatchers.IO) {
        val activeClient = client ?: error("Matrix client is not ready")
        activeClient.getRoom(roomId)?.use { room ->
            room.updatePowerLevelsForUsers(
                listOf(
                    UserPowerLevelUpdate(
                        userId = userId,
                        powerLevel = powerLevel
                    )
                )
            )
        } ?: error("Matrix room is not available")
    }

    suspend fun loadRoomMemberModerationContext(
        roomId: String,
        targetUserId: String
    ): MatrixRoomMemberModerationContext = withContext(Dispatchers.IO) {
        val activeClient = client ?: error("Matrix client is not ready")
        val ownUserId = activeClient.userId()
        activeClient.getRoom(roomId)?.use { room ->
            val roomInfo = room.roomInfo()
            try {
                val ownMember = room.member(ownUserId)
                val targetMember = room.member(targetUserId)
                val powerLevels = roomInfo.powerLevels
                if (powerLevels != null) {
                    powerLevels.toMatrixRoomMemberModerationContext(
                        roomId = roomId,
                        ownUserId = ownUserId,
                        ownMember = ownMember,
                        targetMember = targetMember
                    )
                } else {
                    room.getPowerLevels().use { loadedPowerLevels ->
                        loadedPowerLevels.toMatrixRoomMemberModerationContext(
                            roomId = roomId,
                            ownUserId = ownUserId,
                            ownMember = ownMember,
                            targetMember = targetMember
                        )
                    }
                }
            } finally {
                roomInfo.destroy()
            }
        } ?: error("Matrix room is not available")
    }

    suspend fun moderateRoomMember(
        roomId: String,
        userId: String,
        action: MatrixRoomMemberModerationAction,
        reason: String?
    ) = withContext(Dispatchers.IO) {
        val activeClient = client ?: error("Matrix client is not ready")
        activeClient.getRoom(roomId)?.use { room ->
            when (action) {
                MatrixRoomMemberModerationAction.KICK -> room.kickUser(userId, reason)
                MatrixRoomMemberModerationAction.BAN -> room.banUser(userId, reason)
                MatrixRoomMemberModerationAction.UNBAN -> room.unbanUser(userId, reason)
            }
        } ?: error("Matrix room is not available")
    }

    suspend fun canInviteRoomMembers(roomId: String): Boolean = withContext(Dispatchers.IO) {
        val activeClient = client ?: error("Matrix client is not ready")
        activeClient.getRoom(roomId)?.use { room ->
            room.getPowerLevels().use { powerLevels ->
                powerLevels.canOwnUserInvite()
            }
        } ?: error("Matrix room is not available")
    }

    suspend fun loadRoomCapabilities(roomId: String): MatrixRoomCapabilities =
        withContext(Dispatchers.IO) {
            val activeClient = client ?: error("Matrix client is not ready")
            activeClient.getRoom(roomId)?.use { room ->
                room.getPowerLevels().use { powerLevels ->
                    MatrixRoomCapabilities(
                        canInviteMembers = powerLevels.canOwnUserInvite(),
                        canChangeName = powerLevels.canOwnUserSendState(StateEventType.RoomName),
                        canChangeAvatar = powerLevels.canOwnUserSendState(
                            StateEventType.RoomAvatar
                        )
                    )
                }
            } ?: error("Matrix room is not available")
        }

    suspend fun canManageSpaceChildren(roomId: String): Boolean =
        withContext(Dispatchers.IO) {
            val activeClient = client ?: error("Matrix client is not ready")
            activeClient.getRoom(roomId)?.use { room ->
                room.getPowerLevels().use { powerLevels ->
                    powerLevels.canOwnUserSendState(StateEventType.SpaceChild)
                }
            } ?: error("Matrix room is not available")
        }

    suspend fun loadRoomPermissions(roomId: String): MatrixRoomPermissions =
        withContext(Dispatchers.IO) {
            val activeClient = client ?: error("Matrix client is not ready")
            val ownUserId = activeClient.userId()
            activeClient.getRoom(roomId)?.use { room ->
                val roomInfo = room.roomInfo()
                try {
                    val powerLevels = roomInfo.powerLevels
                    if (powerLevels != null) {
                        powerLevels.toMatrixRoomPermissions(
                            roomId = roomId,
                            ownUserId = ownUserId,
                            privilegedCreatorsRole = roomInfo.privilegedCreatorsRole,
                            creators = roomInfo.creators
                        )
                    } else {
                        room.getPowerLevels().use { loadedPowerLevels ->
                            loadedPowerLevels.toMatrixRoomPermissions(
                                roomId = roomId,
                                ownUserId = ownUserId,
                                privilegedCreatorsRole = roomInfo.privilegedCreatorsRole,
                                creators = roomInfo.creators
                            )
                        }
                    }
                } finally {
                    roomInfo.destroy()
                }
            } ?: error("Matrix room is not available")
        }

    fun roomPermissionsUpdates(roomId: String): Flow<MatrixRoomPermissions> = callbackFlow {
        val activeClient = client
        if (activeClient == null) {
            close(IllegalStateException("Matrix client is not ready"))
            return@callbackFlow
        }
        val ownUserId = activeClient.userId()
        val room = activeClient.getRoom(roomId)
        if (room == null) {
            close(IllegalStateException("Matrix room is not available"))
            return@callbackFlow
        }

        var listenerHandle: TaskHandle? = null
        val hasCleanedUp = AtomicBoolean(false)

        fun emit(roomInfo: RoomInfo): Boolean {
            val permissions = try {
                roomInfo.powerLevels?.toMatrixRoomPermissions(
                    roomId = roomId,
                    ownUserId = ownUserId,
                    privilegedCreatorsRole = roomInfo.privilegedCreatorsRole,
                    creators = roomInfo.creators
                )
            } finally {
                roomInfo.destroy()
            }
            if (permissions != null) {
                trySendBlocking(permissions)
                return true
            }
            return false
        }

        fun cleanup() {
            if (hasCleanedUp.compareAndSet(false, true)) {
                listenerHandle?.cancelAndDestroy()
                room.destroy()
            }
        }

        try {
            val initialRoomInfo = room.roomInfo()
            val initialPrivilegedCreatorsRole = initialRoomInfo.privilegedCreatorsRole
            val initialCreators = initialRoomInfo.creators
            val didEmitInitial = emit(initialRoomInfo)
            listenerHandle = room.subscribeToRoomInfoUpdates(
                object : RoomInfoListener {
                    override fun call(roomInfo: RoomInfo) {
                        emit(roomInfo)
                    }
                }
            )
            if (!didEmitInitial) {
                room.getPowerLevels().use { powerLevels ->
                    trySendBlocking(
                        powerLevels.toMatrixRoomPermissions(
                            roomId = roomId,
                            ownUserId = ownUserId,
                            privilegedCreatorsRole = initialPrivilegedCreatorsRole,
                            creators = initialCreators
                        )
                    )
                }
            }
        } catch (error: Throwable) {
            cleanup()
            close(error)
            return@callbackFlow
        }

        awaitClose(::cleanup)
    }.buffer(Channel.CONFLATED)
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    suspend fun updateRoomPermission(
        roomId: String,
        permission: MatrixRoomPermission,
        level: Long
    ) = withContext(Dispatchers.IO) {
        val activeClient = client ?: error("Matrix client is not ready")
        activeClient.getRoom(roomId)?.use { room ->
            room.applyPowerLevelChanges(permission.toPowerLevelChanges(level))
        } ?: error("Matrix room is not available")
    }

    suspend fun setRoomName(roomId: String, name: String) = withContext(Dispatchers.IO) {
        val activeClient = client ?: error("Matrix client is not ready")
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty()) { "Room name is required" }
        activeClient.getRoom(roomId)?.use { room ->
            room.setName(normalizedName)
        } ?: error("Matrix room is not available")
    }

    suspend fun uploadRoomAvatar(
        roomId: String,
        localPath: String,
        mimeType: String
    ) = withContext(Dispatchers.IO) {
        val activeClient = client ?: error("Matrix client is not ready")
        val avatarFile = File(localPath)
        require(avatarFile.isFile) { "Avatar file is not available" }
        activeClient.getRoom(roomId)?.use { room ->
            room.uploadAvatar(
                mimeType.ifBlank { "image/jpeg" },
                avatarFile.readBytes(),
                null
            )
        } ?: error("Matrix room is not available")
    }

    suspend fun removeRoomAvatar(roomId: String) = withContext(Dispatchers.IO) {
        val activeClient = client ?: error("Matrix client is not ready")
        activeClient.getRoom(roomId)?.use { room ->
            room.removeAvatar()
        } ?: error("Matrix room is not available")
    }

    suspend fun inviteRoomMember(roomId: String, userId: String) = withContext(Dispatchers.IO) {
        val activeClient = client ?: error("Matrix client is not ready")
        val normalizedUserId = userId.trim()
        require(normalizedUserId.isNotEmpty()) { "User ID is required" }
        activeClient.getRoom(roomId)?.use { room ->
            room.inviteUserById(normalizedUserId)
        } ?: error("Matrix room is not available")
    }

    /**
     * Reports when the SDK room list has an authoritative loaded snapshot.
     * Space roots are derived from joined rooms and must not treat an early
     * empty list as an authoritative removal before this becomes true.
     */
    fun roomListLoadedStates(): Flow<Boolean> = callbackFlow {
        val service = roomListService
        if (service == null) {
            close(IllegalStateException("Matrix room list service is not ready"))
            return@callbackFlow
        }
        val roomList = service.allRooms()
        val listener = object : RoomListLoadingStateListener {
            override fun onUpdate(state: RoomListLoadingState) {
                trySendBlocking(state is RoomListLoadingState.Loaded)
            }
        }
        val result = roomList.loadingState(listener)
        trySend(result.state is RoomListLoadingState.Loaded)

        awaitClose {
            result.stateStream.cancelAndDestroy()
            runCatching { result.destroy() }
            runCatching { roomList.destroy() }
        }
    }
        .buffer(Channel.CONFLATED)
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    fun roomCallInfoUpdates(roomId: String): Flow<MatrixRoomCallInfo> = callbackFlow {
        val activeClient = client
        if (activeClient == null) {
            close(IllegalStateException("Matrix client is not ready"))
            return@callbackFlow
        }
        val room = activeClient.getRoom(roomId)
        if (room == null) {
            close(IllegalStateException("Matrix room is not available"))
            return@callbackFlow
        }

        var listenerHandle: TaskHandle? = null
        val hasCleanedUp = AtomicBoolean(false)

        fun emit(roomInfo: RoomInfo) {
            val consensusIntent = roomInfo.activeRoomCallConsensusIntent.debugSummary()
            val directHasActiveCall = runCatching { room.hasActiveRoomCall() }.getOrDefault(false)
            val directParticipantUserIds = runCatching { room.activeRoomCallParticipants() }.getOrDefault(emptyList())
            val callInfo = try {
                roomInfo.toRoomCallInfo(
                    directHasActiveCall = directHasActiveCall,
                    directParticipantUserIds = directParticipantUserIds
                )
            } finally {
                roomInfo.destroy()
            }
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "roomCallInfo roomId=$roomId hasRoomCall=${callInfo.hasRoomCall} " +
                        "participants=${callInfo.activeParticipantCount} " +
                        "isAudioCall=${callInfo.isAudioCall} consensus=$consensusIntent " +
                        "directHasActiveCall=$directHasActiveCall " +
                        "directParticipants=${directParticipantUserIds.size}"
                )
            }
            trySendBlocking(callInfo)
        }

        fun cleanup() {
            if (hasCleanedUp.compareAndSet(false, true)) {
                listenerHandle?.cancelAndDestroy()
                room.destroy()
            }
        }

        try {
            emit(room.roomInfo())
            listenerHandle = room.subscribeToRoomInfoUpdates(
                object : RoomInfoListener {
                    override fun call(roomInfo: RoomInfo) {
                        emit(roomInfo)
                    }
                }
            )
        } catch (error: Throwable) {
            cleanup()
            close(error)
            return@callbackFlow
        }

        awaitClose {
            cleanup()
        }
    }.buffer(Channel.CONFLATED)
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    fun roomDetailsUpdates(roomId: String): Flow<MatrixRoomDetails> = callbackFlow {
        val activeClient = client
        if (activeClient == null) {
            close(IllegalStateException("Matrix client is not ready"))
            return@callbackFlow
        }
        val room = activeClient.getRoom(roomId)
        if (room == null) {
            close(IllegalStateException("Matrix room is not available"))
            return@callbackFlow
        }

        var listenerHandle: TaskHandle? = null
        val hasCleanedUp = AtomicBoolean(false)

        fun emit(roomInfo: RoomInfo) {
            val details = try {
                roomInfo.toMatrixRoomDetails(resolveCapabilities = true)
            } finally {
                roomInfo.destroy()
            }
            trySendBlocking(details)
        }

        fun cleanup() {
            if (hasCleanedUp.compareAndSet(false, true)) {
                listenerHandle?.cancelAndDestroy()
                room.destroy()
            }
        }

        try {
            emit(room.roomInfo())
            listenerHandle = room.subscribeToRoomInfoUpdates(
                object : RoomInfoListener {
                    override fun call(roomInfo: RoomInfo) {
                        emit(roomInfo)
                    }
                }
            )
        } catch (error: Throwable) {
            cleanup()
            close(error)
            return@callbackFlow
        }

        awaitClose(::cleanup)
    }.buffer(Channel.CONFLATED)
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    fun roomTimelineMessageUpserts(roomId: String): Flow<MatrixTimelineUpdate> = callbackFlow {
        val activeClient = client ?: error("Matrix client is not ready")
        val room = activeClient.getRoom(roomId) ?: error("Matrix room is not available")
        var timeline: Timeline? = null
        var listenerHandle: TaskHandle? = null
        val hasEmittedInitialState = AtomicBoolean(false)
        val hasCleanedUp = AtomicBoolean(false)
        val diffBatcher = MatrixTimelineDiffBatcher(
            scope = this,
            debounceMillis = TIMELINE_EMIT_COALESCE_MS,
            mapTimelineItem = { item -> item.toTimelineMappedItem(roomId) },
            onFlush = { update ->
                hasEmittedInitialState.set(true)
                trySendBlocking(update)
            }
        )

        fun cleanup() {
            if (hasCleanedUp.compareAndSet(false, true)) {
                diffBatcher.cancel()
                listenerHandle?.cancelAndDestroy()
                synchronized(activeTimelineLock) {
                    if (activeRoomTimelines[roomId] === timeline) {
                        activeRoomTimelines.remove(roomId)
                    }
                }
                timeline?.destroy()
                room.destroy()
            }
        }

        try {
            val openedTimeline = room.timelineWithConfiguration(
                liveTimelineConfiguration(roomId)
            )
            timeline = openedTimeline
            synchronized(activeTimelineLock) {
                activeRoomTimelines[roomId] = openedTimeline
            }

            listenerHandle = openedTimeline.addListener(
                object : TimelineListener {
                    override fun onUpdate(diff: List<TimelineDiff>) {
                        diffBatcher.receive(diff)
                    }
                }
            )

            val initialLoadingFallbackJob = launch {
                delay(TIMELINE_UPDATE_TIMEOUT_MS)
                if (hasEmittedInitialState.compareAndSet(false, true)) {
                    trySend(
                        MatrixTimelineUpdate(
                            messages = emptyList(),
                            flushSummary = TimelineFlushSummary()
                        )
                    )
                }
            }

            val paginationJob = launch(Dispatchers.IO) {
                runCatching {
                    timelinePaginationMutex.withLock {
                        for (page in 0 until TIMELINE_INITIAL_BACKFILL_PAGES) {
                            val hasReachedStart = openedTimeline.paginateBackwards(
                                TIMELINE_PAGE_SIZE.toUShort()
                            )
                            if (hasReachedStart) {
                                break
                            }
                        }
                    }
                }.onFailure { error ->
                    close(error)
                }
            }

            awaitClose {
                initialLoadingFallbackJob.cancel()
                paginationJob.cancel()
                cleanup()
            }
        } catch (error: Throwable) {
            cleanup()
            throw error
        }
    }.distinctUntilChanged()
        .buffer(Channel.UNLIMITED)
        .flowOn(Dispatchers.IO)

    suspend fun paginateRoomTimelineBackwards(roomId: String): Boolean = withContext(Dispatchers.IO) {
        val activeTimeline = synchronized(activeTimelineLock) {
            activeRoomTimelines[roomId]
        } ?: error("Chat timeline is not ready")

        timelinePaginationMutex.withLock {
            for (page in 0 until TIMELINE_INTERACTIVE_BACKFILL_PAGES) {
                val hasReachedStart = activeTimeline.paginateBackwards(
                    TIMELINE_PAGE_SIZE.toUShort()
                )
                if (hasReachedStart) {
                    return@withLock true
                }
            }
            false
        }
    }

    suspend fun paginateRoomTimelineForwards(roomId: String): Boolean = withContext(Dispatchers.IO) {
        val activeTimeline = synchronized(activeTimelineLock) {
            activeRoomTimelines[roomId]
        } ?: error("Chat timeline is not ready")

        timelinePaginationMutex.withLock {
            for (page in 0 until TIMELINE_INTERACTIVE_BACKFILL_PAGES) {
                val hasReachedEnd = activeTimeline.paginateForwards(
                    TIMELINE_PAGE_SIZE.toUShort()
                )
                if (hasReachedEnd) {
                    return@withLock true
                }
            }
            false
        }
    }

    fun prepareTransactionId(): String {
        return genTransactionId()
    }

    suspend fun sendTextMessage(
        roomId: String,
        body: String,
        transactionId: String,
        replyInfo: MatrixReplyInfo? = null,
        forwardedFrom: String? = null
    ): String = withContext(Dispatchers.IO) {
        val text = body.trim()
        require(text.isNotEmpty()) { "Message is empty" }
        val activeClient = client ?: error("Matrix client is not ready")
        val room = activeClient.getRoom(roomId) ?: error("Matrix room is not available")
        val reply = replyInfo?.takeIf { it.eventId.isNotBlank() }
        val forwardedSender = forwardedFrom?.trim()?.takeIf { it.isNotBlank() }
        val content = JSONObject()
            .put("msgtype", "m.text")
            .put("body", if (reply == null) text else plainReplyBody(text, reply))
            .put(TRANSACTION_ID_CONTENT_KEY, transactionId)
        val formattedBody = formattedBody(
            roomId = roomId,
            body = text,
            replyInfo = reply,
            forwardedFrom = forwardedSender
        )
        if (reply != null) {
            content.put(
                "m.relates_to",
                JSONObject().put(
                    "m.in_reply_to",
                    JSONObject().put("event_id", reply.eventId)
                )
            )
        }
        if (formattedBody != null) {
            content.put("format", "org.matrix.custom.html")
            content.put("formatted_body", formattedBody)
        }
        val contentJson = content
            .toString()

        room.sendRawWithTransactionIdReturningEventId(
            eventType = "m.room.message",
            content = contentJson,
            transactionId = transactionId
        )
    }

    suspend fun sendImageMessage(
        roomId: String,
        localPath: String,
        mimeType: String,
        sizeBytes: Long,
        width: Int,
        height: Int,
        caption: String?,
        transactionId: String,
        zynaAttributesJson: String?,
        blurhash: String? = null,
        thumbnailLocalPath: String? = null,
        thumbnailMimeType: String? = null,
        thumbnailSizeBytes: Long? = null,
        thumbnailWidth: Int? = null,
        thumbnailHeight: Int? = null
    ): String = withContext(Dispatchers.IO) {
        val uploadedImageJson = uploadImageForEvent(
            roomId = roomId,
            localPath = localPath,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            width = width,
            height = height,
            blurhash = blurhash,
            thumbnailLocalPath = thumbnailLocalPath,
            thumbnailMimeType = thumbnailMimeType,
            thumbnailSizeBytes = thumbnailSizeBytes,
            thumbnailWidth = thumbnailWidth,
            thumbnailHeight = thumbnailHeight
        )
        sendUploadedImageMessage(
            roomId = roomId,
            uploadedImageJson = uploadedImageJson,
            caption = caption,
            transactionId = transactionId,
            zynaAttributesJson = zynaAttributesJson
        )
    }

    suspend fun uploadImageForEvent(
        roomId: String,
        localPath: String,
        mimeType: String,
        sizeBytes: Long,
        width: Int,
        height: Int,
        blurhash: String? = null,
        thumbnailLocalPath: String? = null,
        thumbnailMimeType: String? = null,
        thumbnailSizeBytes: Long? = null,
        thumbnailWidth: Int? = null,
        thumbnailHeight: Int? = null
    ): String = withContext(Dispatchers.IO) {
        val imageFile = File(localPath)
        require(imageFile.isFile) { "Image file is not available" }
        val thumbnailFile = thumbnailLocalPath
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.isFile }
        val activeClient = client ?: error("Matrix client is not ready")
        val room = activeClient.getRoom(roomId) ?: error("Matrix room is not available")
        val mediaBlurhash = blurhash?.takeIf { it.isNotBlank() }
            ?: thumbnailFile?.absolutePath?.let { path -> BlurHashCodec.encodeFile(path) }
            ?: BlurHashCodec.encodeFile(imageFile.absolutePath)
        room.uploadImageForEvent(
            originalFilePath = imageFile.absolutePath,
            thumbnailFilePath = thumbnailFile?.absolutePath,
            originalMimetype = mimeType.ifBlank { "image/jpeg" },
            originalSize = sizeBytes.takeIf { it > 0L }
                ?.toULong()
                ?: imageFile.length().coerceAtLeast(1L).toULong(),
            originalWidth = width.coerceAtLeast(1).toULong(),
            originalHeight = height.coerceAtLeast(1).toULong(),
            thumbnailMimetype = thumbnailFile?.let {
                thumbnailMimeType?.takeIf { it.isNotBlank() } ?: "image/jpeg"
            },
            thumbnailSize = thumbnailFile?.let {
                (thumbnailSizeBytes?.takeIf { it > 0L } ?: thumbnailFile.length().coerceAtLeast(1L))
                    .toULong()
            },
            thumbnailWidth = thumbnailFile?.let {
                thumbnailWidth?.takeIf { it > 0 }?.toULong()
            },
            thumbnailHeight = thumbnailFile?.let {
                thumbnailHeight?.takeIf { it > 0 }?.toULong()
            },
            blurhash = mediaBlurhash
        )
    }

    suspend fun sendUploadedImageMessage(
        roomId: String,
        uploadedImageJson: String,
        caption: String?,
        transactionId: String,
        zynaAttributesJson: String?
    ): String = withContext(Dispatchers.IO) {
        val activeClient = client ?: error("Matrix client is not ready")
        val room = activeClient.getRoom(roomId) ?: error("Matrix room is not available")
        val normalizedCaption = caption.normalizedMessageCaption()
        val zynaAttributes = ZynaHtmlCodec.decodeAttributesJson(zynaAttributesJson)
        val plainCaption = normalizedCaption
            ?: ZERO_WIDTH_SPACE.takeUnless { zynaAttributes.isEmpty }
        val formattedCaption = formattedMediaCaption(
            caption = normalizedCaption,
            attributes = zynaAttributes
        )

        room.sendUploadedImageWithTransactionIdReturningEventId(
            uploadedImageJson = uploadedImageJson,
            transactionId = transactionId,
            caption = plainCaption,
            formattedCaption = formattedCaption,
            replyEventId = null
        )
    }

    suspend fun uploadVoiceForEvent(
        roomId: String,
        localPath: String,
        mimeType: String,
        sizeBytes: Long,
        durationMillis: Long,
        waveform: List<Float>
    ): String = withContext(Dispatchers.IO) {
        val voiceFile = File(localPath)
        require(voiceFile.isFile) { "Voice file is not available" }
        val activeClient = client ?: error("Matrix client is not ready")
        val room = activeClient.getRoom(roomId) ?: error("Matrix room is not available")
        room.uploadVoiceForEvent(
            filePath = voiceFile.absolutePath,
            mimetype = mimeType.ifBlank { "audio/mp4" },
            size = sizeBytes.takeIf { it > 0L }
                ?.toULong()
                ?: voiceFile.length().coerceAtLeast(1L).toULong(),
            duration = Duration.ofMillis(durationMillis.coerceAtLeast(1L)),
            waveform = waveform.map { it.coerceIn(0f, 1f) }
        )
    }

    suspend fun sendUploadedVoiceMessage(
        roomId: String,
        uploadedVoiceJson: String,
        transactionId: String,
        replyInfo: MatrixReplyInfo? = null
    ): String = withContext(Dispatchers.IO) {
        val activeClient = client ?: error("Matrix client is not ready")
        val room = activeClient.getRoom(roomId) ?: error("Matrix room is not available")
        room.sendUploadedVoiceWithTransactionIdReturningEventId(
            uploadedVoiceJson = uploadedVoiceJson,
            transactionId = transactionId,
            replyEventId = replyInfo?.eventId?.takeIf { it.isNotBlank() }
        )
    }

    suspend fun sendForwardedImageMessage(
        roomId: String,
        image: MatrixForwardImageItem,
        caption: String?,
        transactionId: String,
        zynaAttributesJson: String?
    ): String = withContext(Dispatchers.IO) {
        val activeClient = client ?: error("Matrix client is not ready")
        val room = activeClient.getRoom(roomId) ?: error("Matrix room is not available")
        val source = MediaSource.fromJson(image.sourceJson)
        val thumbnailSource = image.thumbnailSourceJson
            ?.takeIf { it.isNotBlank() }
            ?.let { MediaSource.fromJson(it) }
        val normalizedCaption = caption.normalizedMessageCaption()
        val zynaAttributes = ZynaHtmlCodec.decodeAttributesJson(zynaAttributesJson)
        val plainCaption = normalizedCaption
            ?: ZERO_WIDTH_SPACE.takeUnless { zynaAttributes.isEmpty }
        val formattedCaption = formattedMediaCaption(
            caption = normalizedCaption,
            attributes = zynaAttributes
        )
        val msgType = MessageType.Image(
            ImageMessageContent(
                filename = "image.jpg",
                caption = plainCaption,
                formattedCaption = formattedCaption?.let {
                    FormattedBody(format = MessageFormat.Html, body = it)
                },
                source = source,
                info = ImageInfo(
                    height = image.height?.takeIf { it > 0 }?.toULong(),
                    width = image.width?.takeIf { it > 0 }?.toULong(),
                    mimetype = image.mimeType?.takeIf { it.isNotBlank() } ?: "image/jpeg",
                    size = null,
                    thumbnailInfo = null,
                    thumbnailSource = thumbnailSource,
                    blurhash = image.blurhash?.takeIf { it.isNotBlank() },
                    isAnimated = null
                )
            )
        )
        msgType.use { messageType ->
            room.sendMessageTypeWithTransactionIdReturningEventId(
                msgType = messageType,
                transactionId = transactionId,
                replyEventId = null
            )
        }
    }

    suspend fun sendTextEdit(
        roomId: String,
        eventId: String,
        body: String,
        transactionId: String
    ): String = withContext(Dispatchers.IO) {
        val text = body.trim()
        require(eventId.isNotBlank()) { "Edited message event id is empty" }
        require(text.isNotEmpty()) { "Edited message is empty" }
        val activeClient = client ?: error("Matrix client is not ready")
        val room = activeClient.getRoom(roomId) ?: error("Matrix room is not available")
        val newContent = JSONObject()
            .put("msgtype", "m.text")
            .put("body", text)
        val content = JSONObject()
            .put("msgtype", "m.text")
            .put("body", "* $text")
            .put("m.new_content", newContent)
            .put(
                "m.relates_to",
                JSONObject()
                    .put("rel_type", "m.replace")
                    .put("event_id", eventId)
            )
            .put(TRANSACTION_ID_CONTENT_KEY, transactionId)

        room.sendRawWithTransactionIdReturningEventId(
            eventType = "m.room.message",
            content = content.toString(),
            transactionId = transactionId
        )
    }

    suspend fun sendReaction(
        roomId: String,
        targetEventId: String,
        reactionKey: String,
        transactionId: String
    ): String = withContext(Dispatchers.IO) {
        require(targetEventId.isNotBlank()) { "Reaction target event id is empty" }
        require(reactionKey.isNotBlank()) { "Reaction key is empty" }
        val activeClient = client ?: error("Matrix client is not ready")
        val room = activeClient.getRoom(roomId) ?: error("Matrix room is not available")
        val content = JSONObject()
            .put(
                "m.relates_to",
                JSONObject()
                    .put("rel_type", "m.annotation")
                    .put("event_id", targetEventId)
                    .put("key", reactionKey)
            )
            .put(TRANSACTION_ID_CONTENT_KEY, transactionId)

        room.sendRawWithTransactionIdReturningEventId(
            eventType = "m.reaction",
            content = content.toString(),
            transactionId = transactionId
        )
    }

    suspend fun redactMessage(
        roomId: String,
        eventId: String,
        transactionId: String,
        reason: String? = null
    ): String = withContext(Dispatchers.IO) {
        require(eventId.isNotBlank()) { "Message event id is empty" }
        val activeClient = client ?: error("Matrix client is not ready")
        val room = activeClient.getRoom(roomId) ?: error("Matrix room is not available")

        room.redactWithTransactionIdReturningEventId(
            eventId = eventId,
            reason = reason,
            transactionId = transactionId
        )
    }

    suspend fun findOwnReactionEventId(
        roomId: String,
        targetEventId: String,
        reactionKey: String,
        userId: String
    ): String? = withContext(Dispatchers.IO) {
        if (targetEventId.isBlank() || reactionKey.isBlank() || userId.isBlank()) {
            return@withContext null
        }
        val activeClient = client ?: return@withContext null
        val room = activeClient.getRoom(roomId) ?: return@withContext null
        var from: String? = null
        repeat(MAX_REACTION_RELATION_PAGES) {
            val relations = runCatching {
                room.getEventRelations(
                    eventId = targetEventId,
                    options = RawRoomRelationsOptions(
                        relationType = "m.annotation",
                        eventType = "m.reaction",
                        from = from,
                        limit = 100uL,
                        direction = RawRoomRelationsDirection.BACKWARD,
                        recurse = false
                    )
                )
            }.getOrElse { error ->
                Log.w(TAG, "findOwnReactionEventId failed target=$targetEventId", error)
                return@withContext null
            }

            relations.chunk
                .asSequence()
                .filter { event ->
                    event.eventType == "m.reaction" &&
                        event.sender == userId &&
                        event.eventId?.isNotBlank() == true
                }
                .firstOrNull { event ->
                    event.contentJson.isReactionFor(
                        targetEventId = targetEventId,
                        reactionKey = reactionKey
                    )
                }
                ?.eventId
                ?.let { return@withContext it }

            val nextFrom = relations.nextBatchToken?.takeIf { it.isNotBlank() }
            if (nextFrom == null || nextFrom == from || relations.chunk.isEmpty()) {
                return@withContext null
            }
            from = nextFrom
        }
        null
    }

    suspend fun sendReadReceipt(
        roomId: String,
        eventId: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (eventId.isBlank()) {
            return@withContext false
        }

        val activeTimeline = synchronized(activeTimelineLock) {
            activeRoomTimelines[roomId]
        } ?: return@withContext false

        try {
            activeTimeline.sendReadReceipt(
                receiptType = ReceiptType.READ,
                eventId = eventId
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "sendReadReceipt(.read) failed event=$eventId", error)
        }

        try {
            activeTimeline.sendReadReceipt(
                receiptType = ReceiptType.FULLY_READ,
                eventId = eventId
            )
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "sendReadReceipt(.fullyRead) failed event=$eventId", error)
            false
        }
    }

    suspend fun loadMediaContent(sourceJson: String): ByteArray = withContext(Dispatchers.IO) {
        val activeClient = client ?: error("Matrix client is not ready")
        val source = MediaSource.fromJson(sourceJson)
        source.use { mediaSource ->
            activeClient.getMediaContent(mediaSource)
        }
    }

    suspend fun loadMediaFile(
        sourceJson: String,
        filename: String?,
        mimeType: String?,
        tempDir: File?
    ): MediaFileHandle = withContext(Dispatchers.IO) {
        val activeClient = client ?: error("Matrix client is not ready")
        val source = MediaSource.fromJson(sourceJson)
        source.use { mediaSource ->
            activeClient.getMediaFile(
                mediaSource = mediaSource,
                filename = filename?.takeIf { it.isNotBlank() },
                mimeType = mimeType?.takeIf { it.isNotBlank() } ?: DEFAULT_AUDIO_MIME_TYPE,
                useCache = true,
                tempDir = tempDir?.absolutePath
            )
        }
    }

    suspend fun loadMediaContentFromUrl(url: String): ByteArray = withContext(Dispatchers.IO) {
        val activeClient = client ?: error("Matrix client is not ready")
        val source = MediaSource.fromUrl(url)
        source.use { mediaSource ->
            activeClient.getMediaContent(mediaSource)
        }
    }

    suspend fun loadMediaThumbnail(
        sourceJson: String,
        width: Int,
        height: Int
    ): ByteArray = withContext(Dispatchers.IO) {
        val activeClient = client ?: error("Matrix client is not ready")
        val source = MediaSource.fromJson(sourceJson)
        source.use { mediaSource ->
            activeClient.getMediaThumbnail(
                mediaSource = mediaSource,
                width = width.coerceAtLeast(1).toULong(),
                height = height.coerceAtLeast(1).toULong()
            )
        }
    }

    suspend fun loadMediaThumbnailFromUrl(
        url: String,
        width: Int,
        height: Int
    ): ByteArray = withContext(Dispatchers.IO) {
        val activeClient = client ?: error("Matrix client is not ready")
        val source = MediaSource.fromUrl(url)
        source.use { mediaSource ->
            activeClient.getMediaThumbnail(
                mediaSource = mediaSource,
                width = width.coerceAtLeast(1).toULong(),
                height = height.coerceAtLeast(1).toULong()
            )
        }
    }

    private fun TimelineItem.toTimelineMappedItem(roomId: String): MatrixTimelineMappedItem = use { item ->
        val event = item.asEvent() ?: return@use null
        event.toTimelineMappedItem(roomId)
    } ?: MatrixTimelineMappedItem()

    private fun TimelineItem.toChatMessageOrNull(): MatrixChatMessage? = use { item ->
        val event = item.asEvent() ?: return@use null
        event.toChatMessageOrNull()
    }

    private fun EventTimelineItem.toTimelineMappedItem(roomId: String): MatrixTimelineMappedItem {
        val message = toChatMessageOrNull()
        if (message != null) {
            return MatrixTimelineMappedItem(message = message)
        }
        val raw = rawJsonObjectOrNull() ?: return MatrixTimelineMappedItem()
        val eventType = raw.optStringOrNull("type") ?: return MatrixTimelineMappedItem()
        return MatrixTimelineMappedItem(
            callNotification = if (
                eventType == MatrixRtcCallNotificationContent.EVENT_TYPE ||
                eventType == MatrixRtcLegacyCallNotifyContent.EVENT_TYPE
            ) {
                toMatrixRtcCallTimelineNotificationOrNull(
                    roomId = roomId,
                    raw = raw,
                    eventType = eventType
                )
            } else {
                null
            },
            callMembership = if (
                eventType == MatrixRtcRawMembershipEvent.LEGACY_CALL_MEMBER_EVENT_TYPE ||
                eventType == MatrixRtcRawMembershipEvent.RTC_MEMBER_EVENT_TYPE
            ) {
                toMatrixRtcCallTimelineMembershipOrNull(
                    roomId = roomId,
                    raw = raw,
                    eventType = eventType
                )
            } else {
                null
            }
        )
    }

    private fun EventTimelineItem.toChatMessageOrNull(): MatrixChatMessage? {
        return when (val timelineContent = content) {
            is TimelineItemContent.MsgLike -> toMessageLikeChatMessageOrNull(timelineContent.content)
            is TimelineItemContent.RoomMembership -> matrixMembershipEventDetailsOrNull(
                userId = timelineContent.userId,
                userDisplayName = timelineContent.userDisplayName,
                change = timelineContent.change,
                reason = timelineContent.reason
            )?.let { details -> toSystemEventChatMessage(details) }
            is TimelineItemContent.ProfileChange -> matrixProfileChangeEventDetailsOrNull(
                displayName = timelineContent.displayName,
                previousDisplayName = timelineContent.prevDisplayName
            )?.let { details -> toSystemEventChatMessage(details) }
            is TimelineItemContent.State -> matrixRoomStateEventDetailsOrNull(
                stateKey = timelineContent.stateKey,
                state = timelineContent.content
            )?.let { details -> toSystemEventChatMessage(details) }
            else -> null
        }
    }

    private fun EventTimelineItem.toMessageLikeChatMessageOrNull(
        msgLike: MsgLikeContent
    ): MatrixChatMessage? {
        val replyInfo = msgLike.replyInfoOrNull()
        val messageBody = when (val kind = msgLike.kind) {
            is MsgLikeKind.Message -> MatrixMessageBody(
                body = kind.content.displayBody(),
                contentType = kind.content.contentType(),
                imageInfo = kind.content.imageInfoOrNull(),
                audioInfo = kind.content.audioInfoOrNull()
            )
            MsgLikeKind.Redacted -> MatrixMessageBody(
                body = "Deleted message",
                contentType = MatrixMessageContentType.REDACTED,
                imageInfo = null,
                audioInfo = null
            )
            is MsgLikeKind.UnableToDecrypt -> MatrixMessageBody(
                body = "Unable to decrypt message",
                contentType = MatrixMessageContentType.UNABLE_TO_DECRYPT,
                imageInfo = null,
                audioInfo = null
            )
            else -> return null
        }
        val body = if (replyInfo == null) {
            messageBody.body
        } else {
            messageBody.body.stripMatrixReplyFallback()
        }
        val eventId = eventOrTransactionId.eventIdOrNull()
        val transactionId = eventOrTransactionId.transactionIdOrNull()
        val messageContent = (msgLike.kind as? MsgLikeKind.Message)?.content
        val isEdited = messageContent?.isEdited ?: false
        val reactions = msgLike.buildReactions()
        val zynaAttributes = lazyProvider.latestJson()
            ?.zynaAttributesFromRawEvent()
            ?: messageContent?.zynaAttributes()
            ?: ZynaMessageAttributes()

        return MatrixChatMessage(
            id = eventOrTransactionId.stableId(),
            eventId = eventId,
            transactionId = transactionId,
            sender = sender,
            senderDisplayName = senderProfile.displayNameOrNull(),
            body = body,
            timestampMillis = timestamp.toLong(),
            isOwn = isOwn,
            contentType = messageBody.contentType,
            imageInfo = messageBody.imageInfo,
            audioInfo = messageBody.audioInfo,
            replyInfo = replyInfo,
            forwardedFrom = zynaAttributes.forwardedFrom,
            zynaAttributes = zynaAttributes,
            isEdited = isEdited,
            reactions = reactions
        )
    }

    private fun EventTimelineItem.toSystemEventChatMessage(
        details: MatrixSystemEventDetails
    ): MatrixChatMessage {
        return MatrixChatMessage(
            id = eventOrTransactionId.stableId(),
            eventId = eventOrTransactionId.eventIdOrNull(),
            transactionId = eventOrTransactionId.transactionIdOrNull(),
            sender = sender,
            senderDisplayName = senderProfile.displayNameOrNull(),
            body = "",
            timestampMillis = timestamp.toLong(),
            isOwn = isOwn,
            contentType = MatrixMessageContentType.SYSTEM_EVENT,
            systemEventDetails = details
        )
    }

    private fun EventTimelineItem.toMatrixRtcCallTimelineNotificationOrNull(
        roomId: String,
        raw: JSONObject,
        eventType: String
    ): MatrixRtcCallTimelineNotification? {
        val eventId = raw.optStringOrNull("event_id")
            ?: eventOrTransactionId.eventIdOrNull()
            ?: return null
        val contentJson = raw.optJSONObject("content") ?: return null
        val timestampMillis = raw.optLongOrNull("origin_server_ts") ?: timestamp.toLong()
        return when (eventType) {
            MatrixRtcCallNotificationContent.EVENT_TYPE -> {
                val content = runCatching {
                    MatrixRtcCallNotificationContent.fromJson(contentJson.toString())
                }.getOrNull() ?: return null
                MatrixRtcCallTimelineNotification(
                    eventId = eventId,
                    roomId = roomId,
                    parentEventId = content.relation.eventId,
                    senderId = sender,
                    senderDisplayName = senderProfile.displayNameOrNull(),
                    isOutgoing = isOwn,
                    timestampMillis = timestampMillis,
                    notificationType = content.notificationType,
                    callIntent = content.callIntent,
                    expiresAtMillis = content.senderTimestamp + content.lifetime,
                    declinedBy = contentJson.declinedByUserIds()
                )
            }
            MatrixRtcLegacyCallNotifyContent.EVENT_TYPE -> {
                val content = runCatching {
                    MatrixRtcLegacyCallNotifyContent.fromJson(contentJson.toString())
                }.getOrNull() ?: return null
                MatrixRtcCallTimelineNotification(
                    eventId = eventId,
                    roomId = roomId,
                    parentEventId = content.callId.takeIf { it.isNotBlank() },
                    senderId = sender,
                    senderDisplayName = senderProfile.displayNameOrNull(),
                    isOutgoing = isOwn,
                    timestampMillis = timestampMillis,
                    notificationType = when (content.notifyType) {
                        MatrixRtcCallNotificationType.RING.wireValue ->
                            MatrixRtcCallNotificationType.RING
                        else -> MatrixRtcCallNotificationType.NOTIFICATION
                    },
                    callIntent = null,
                    expiresAtMillis = timestampMillis + DEFAULT_LEGACY_CALL_NOTIFICATION_LIFETIME_MS,
                    declinedBy = contentJson.declinedByUserIds()
                )
            }
            else -> null
        }
    }

    private fun EventTimelineItem.toMatrixRtcCallTimelineMembershipOrNull(
        roomId: String,
        raw: JSONObject,
        eventType: String
    ): MatrixRtcCallTimelineMembership? {
        val eventId = raw.optStringOrNull("event_id") ?: return null
        val senderId = raw.optStringOrNull("sender") ?: sender
        val timestampMillis = raw.optLongOrNull("origin_server_ts") ?: timestamp.toLong()
        val contentJson = raw.optJSONObject("content") ?: return null
        val stateKey = raw.optStringOrNull("state_key")
        val isLeave = contentJson.length() == 0
        if (isLeave) {
            return MatrixRtcCallTimelineMembership(
                eventId = eventId,
                roomId = roomId,
                eventType = eventType,
                stateKey = stateKey,
                senderId = senderId,
                timestampMillis = timestampMillis,
                isLeave = true,
                memberUserId = senderId,
                deviceId = null,
                memberId = null,
                callIntent = null,
                expiresAtMillis = null
            )
        }

        val membership = runCatching {
            MatrixRtcCallMembershipParser.parse(
                MatrixRtcRawMembershipEvent(
                    eventId = eventId,
                    eventType = eventType,
                    stateKey = stateKey,
                    sender = senderId,
                    originServerTimestamp = timestampMillis,
                    contentJson = contentJson.toString()
                )
            )
        }.getOrNull() ?: return null

        return MatrixRtcCallTimelineMembership(
            eventId = eventId,
            roomId = roomId,
            eventType = eventType,
            stateKey = stateKey,
            senderId = senderId,
            timestampMillis = timestampMillis,
            isLeave = false,
            memberUserId = membership.userId,
            deviceId = membership.deviceId,
            memberId = membership.memberId,
            callIntent = membership.callIntent,
            expiresAtMillis = membership.absoluteExpiryTimestamp
        )
    }

    private fun EventTimelineItem.rawJsonObjectOrNull(): JSONObject? {
        return runCatching {
            lazyProvider.latestJson()?.let(::JSONObject)
        }.getOrNull()
    }

    private fun MsgLikeContent.buildReactions(): List<MatrixMessageReaction> {
        val currentUserId = (state.value as? MatrixClientState.LoggedIn)?.userId
            ?: (state.value as? MatrixClientState.Syncing)?.userId
            ?: runCatching { client?.userId() }.getOrNull()
            ?: ""
        return reactions
            .map { reaction ->
                val senders = reaction.senders
                    .map { sender ->
                        MatrixReactionSender(
                            userId = sender.senderId,
                            timestampMillis = sender.timestamp.toLong()
                        )
                    }
                    .sortedByDescending { it.timestampMillis }
                MatrixMessageReaction(
                    key = reaction.key,
                    senders = senders,
                    isOwn = reaction.senders.any { it.senderId == currentUserId }
                )
            }
            .filter { it.count > 0 && it.key.isNotBlank() }
            .sortedWith(
                compareByDescending<MatrixMessageReaction> { it.count }
                    .thenByDescending { it.senders.firstOrNull()?.timestampMillis ?: 0L }
                    .thenBy { it.key }
            )
    }

    private fun MsgLikeContent.replyInfoOrNull(): MatrixReplyInfo? {
        val replyDetails = inReplyTo ?: return null
        val eventId = runCatching { replyDetails.eventId() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val embeddedEvent = runCatching { replyDetails.event() }.getOrNull()
        return try {
            val readyEvent = embeddedEvent as? EmbeddedEventDetails.Ready
            val embeddedContent = readyEvent?.content as? TimelineItemContent.MsgLike
            val embeddedMessageBody = embeddedContent?.content?.replyPreviewBodyOrNull()

            MatrixReplyInfo(
                eventId = eventId,
                senderId = readyEvent?.sender.orEmpty(),
                senderDisplayName = (readyEvent?.senderProfile as? ProfileDetails.Ready)
                    ?.displayName
                    ?.takeIf { it.isNotBlank() },
                body = embeddedMessageBody?.stripMatrixReplyFallback().orEmpty()
            )
        } finally {
            embeddedEvent?.destroy()
        }
    }

    private fun MsgLikeContent.replyPreviewBodyOrNull(): String? {
        return when (val kind = kind) {
            is MsgLikeKind.Message -> kind.content.displayBody()
            MsgLikeKind.Redacted -> "Deleted message"
            is MsgLikeKind.UnableToDecrypt -> "Unable to decrypt message"
            else -> null
        }
    }

    private fun EventOrTransactionId.stableId(): String {
        return when (this) {
            is EventOrTransactionId.EventId -> eventId
            is EventOrTransactionId.TransactionId -> transactionId
        }
    }

    private fun EventOrTransactionId.eventIdOrNull(): String? {
        return (this as? EventOrTransactionId.EventId)?.eventId
    }

    private fun EventOrTransactionId.transactionIdOrNull(): String? {
        return (this as? EventOrTransactionId.TransactionId)?.transactionId
    }

    private fun MessageContent.displayBody(): String {
        return msgType.displayBody(fallbackBody = body)
    }

    private fun MessageContent.contentType(): MatrixMessageContentType {
        return when (msgType) {
            is MessageType.Text -> MatrixMessageContentType.TEXT
            is MessageType.Notice -> MatrixMessageContentType.NOTICE
            is MessageType.Emote -> MatrixMessageContentType.EMOTE
            is MessageType.Image -> MatrixMessageContentType.IMAGE
            is MessageType.Audio -> MatrixMessageContentType.AUDIO
            is MessageType.Video -> MatrixMessageContentType.VIDEO
            is MessageType.File -> MatrixMessageContentType.FILE
            is MessageType.Gallery -> MatrixMessageContentType.GALLERY
            is MessageType.Location -> MatrixMessageContentType.LOCATION
            is MessageType.Other -> MatrixMessageContentType.UNSUPPORTED
        }
    }

    private fun MessageContent.imageInfoOrNull(): MatrixImageInfo? {
        val image = (msgType as? MessageType.Image)?.content ?: return null
        val sourceJson = runCatching { image.source.toJson() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val info = image.info
        return MatrixImageInfo(
            sourceJson = sourceJson,
            thumbnailSourceJson = info?.thumbnailSource
                ?.let { source -> runCatching { source.toJson() }.getOrNull() }
                ?.takeIf { it.isNotBlank() },
            width = info?.width?.toIntOrNull(),
            height = info?.height?.toIntOrNull(),
            caption = image.caption.normalizedMessageCaption(),
            mimeType = info?.mimetype?.takeIf { it.isNotBlank() },
            blurhash = info?.blurhash?.takeIf { it.isNotBlank() }
        )
    }

    private fun MessageContent.audioInfoOrNull(): MatrixAudioInfo? {
        val audio = (msgType as? MessageType.Audio)?.content ?: return null
        val sourceJson = runCatching { audio.source.toJson() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val info = audio.info
        val details = audio.audio
        return MatrixAudioInfo(
            sourceJson = sourceJson,
            filename = audio.filename.takeIf { it.isNotBlank() },
            caption = audio.caption.normalizedMessageCaption(),
            mimeType = info?.mimetype?.takeIf { it.isNotBlank() },
            sizeBytes = info?.size?.toLongOrNull(),
            durationMillis = info?.duration
                ?.toMillis()
                ?.takeIf { it > 0L }
                ?: details?.duration?.toMillis()?.takeIf { it > 0L },
            waveform = details?.waveform.normalizedWaveform(),
            isVoice = audio.voice != null
        )
    }

    private fun plainReplyBody(body: String, replyInfo: MatrixReplyInfo): String {
        val quotedLines = replyInfo.body
            .lineSequence()
            .mapIndexed { index, line ->
                if (index == 0) {
                    "> <${replyInfo.senderId}> $line"
                } else {
                    "> $line"
                }
            }
            .toList()
            .ifEmpty { listOf("> <${replyInfo.senderId}>") }
        return quotedLines.joinToString(separator = "\n") + "\n\n" + body
    }

    private fun formattedBody(
        roomId: String,
        body: String,
        replyInfo: MatrixReplyInfo?,
        forwardedFrom: String?
    ): String? {
        if (replyInfo == null && forwardedFrom.isNullOrBlank()) {
            return null
        }
        var html = ZynaHtmlCodec.escapeForHtmlAttribute(body).htmlLineBreaks()
        if (replyInfo != null) {
            html = htmlReplyFallback(roomId, replyInfo) + html
        }
        return ZynaHtmlCodec.encode(
            userHtml = html,
            attributes = ZynaMessageAttributes(forwardedFrom = forwardedFrom)
        )
    }

    private fun formattedMediaCaption(
        caption: String?,
        attributes: ZynaMessageAttributes
    ): String? {
        if (caption == null && attributes.isEmpty) {
            return null
        }

        val userHtml = caption
            ?.let { ZynaHtmlCodec.escapeForHtmlAttribute(it).htmlLineBreaks() }
            ?: ZERO_WIDTH_SPACE
        return ZynaHtmlCodec.encode(
            userHtml = userHtml,
            attributes = attributes
        )
    }

    private fun htmlReplyFallback(roomId: String, replyInfo: MatrixReplyInfo): String {
        val roomEventLink = ZynaHtmlCodec.escapeForHtmlAttribute(
            "https://matrix.to/#/$roomId/${replyInfo.eventId}"
        )
        val senderLink = ZynaHtmlCodec.escapeForHtmlAttribute(
            "https://matrix.to/#/${replyInfo.senderId}"
        )
        val senderName = ZynaHtmlCodec.escapeForHtmlAttribute(
            replyInfo.senderDisplayName ?: replyInfo.senderId
        )
        val quotedBody = ZynaHtmlCodec.escapeForHtmlAttribute(replyInfo.body).htmlLineBreaks()

        return "<mx-reply><blockquote><a href=\"$roomEventLink\">In reply to</a> " +
            "<a href=\"$senderLink\">$senderName</a><br>$quotedBody</blockquote></mx-reply>"
    }

    private fun MessageContent.zynaAttributes(): ZynaMessageAttributes? {
        val formatted = formattedHtmlBodyOrNull() ?: return null
        return ZynaHtmlCodec.decode(formatted)
    }

    private fun String.zynaAttributesFromRawEvent(): ZynaMessageAttributes? {
        val content = runCatching { JSONObject(this).optJSONObject("content") }
            .getOrNull()
            ?: return null
        return content.zynaAttributesFromContent()
    }

    private fun JSONObject.zynaAttributesFromContent(): ZynaMessageAttributes? {
        val editedAttributes = optJSONObject("m.new_content")
            ?.zynaAttributesFromContent()
        if (editedAttributes != null && !editedAttributes.isEmpty) {
            return editedAttributes
        }

        val formatted = optStringOrNull("formatted_body") ?: return null
        return ZynaHtmlCodec.decode(formatted)
    }

    private fun MessageContent.formattedHtmlBodyOrNull(): String? {
        return when (val type = msgType) {
            is MessageType.Text -> type.content.formatted?.body
            is MessageType.Notice -> type.content.formatted?.body
            is MessageType.Emote -> type.content.formatted?.body
            else -> null
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        return opt(key) as? String
    }

    private fun JSONObject.optLongOrNull(key: String): Long? {
        return when (val value = opt(key)) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }

    private fun JSONObject.declinedByUserIds(): List<String> {
        val keys = listOf(
            "declined_by",
            "declinedBy",
            "m.call.declined_by",
            "m.call.declinedBy"
        )
        return keys.asSequence()
            .mapNotNull { key -> optJSONArray(key) }
            .firstOrNull()
            ?.toStringList()
            ?: emptyList()
    }

    private fun JSONArray.toStringList(): List<String> {
        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun String.stripMatrixReplyFallback(): String {
        val normalized = replace("\r\n", "\n").replace('\r', '\n')
        val separatorIndex = normalized.indexOf("\n\n")
        if (separatorIndex <= 0) {
            return this
        }

        val quotedPart = normalized.substring(0, separatorIndex)
        val isReplyFallback = quotedPart
            .lineSequence()
            .filter { it.isNotBlank() }
            .all { it.startsWith(">") }
        return if (isReplyFallback) normalized.substring(separatorIndex + 2) else this
    }

    private fun String.htmlLineBreaks(): String {
        return replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\n", "<br>")
    }

    private suspend fun Room.toRoomSummary(): MatrixRoomSummary {
        val roomId = id()
        val roomInfo = runCatching { roomInfo() }.getOrNull()
        try {
            return toRoomSummary(roomId = roomId, roomInfo = roomInfo)
        } finally {
            roomInfo?.destroy()
        }
    }

    private suspend fun Room.toRoomListEntry(): MatrixRoomListEntry {
        val roomId = id()
        val roomInfo = runCatching { roomInfo() }.getOrNull()
        try {
            val isVisible = roomInfo?.membership != Membership.LEFT &&
                roomInfo?.membership != Membership.BANNED
            return MatrixRoomListEntry(
                id = roomId,
                room = if (isVisible) {
                    toRoomSummary(roomId = roomId, roomInfo = roomInfo)
                } else {
                    null
                }
            )
        } finally {
            roomInfo?.destroy()
        }
    }

    private suspend fun Room.toRoomSummary(
        roomId: String,
        roomInfo: RoomInfo?
    ): MatrixRoomSummary {
        // A single room with temporarily unreadable presentation data must not invalidate the
        // positional SDK list. Preserve the entry and let the cache retain richer older fields.
        val latestPreview = readRoomListField(roomId, "latest event") {
            latestEvent().toRoomPreview()
        } ?: MatrixRoomPreview()
        val sdkDisplayName = readRoomListField(roomId, "display name") { displayName() }
        val sdkAvatarUrl = readRoomListField(roomId, "avatar") { avatarUrl() }
        val details = roomInfo?.let { info ->
            readRoomListField(roomId, "room info") {
                info.toMatrixRoomDetails(resolveCapabilities = false)
            }
        }
        return MatrixRoomSummary(
            id = roomId,
            displayName = sdkDisplayName
                ?.takeIf { it.isNotBlank() }
                ?: roomInfo?.displayName?.takeIf { it.isNotBlank() }
                ?: roomId,
            avatarUrl = sdkAvatarUrl ?: roomInfo?.avatarUrl,
            directUserId = roomInfo?.directUserId(),
            isSpace = roomInfo?.isSpace == true,
            membership = when (roomInfo?.membership) {
                Membership.INVITED -> MatrixSpaceMembership.INVITED
                Membership.JOINED -> MatrixSpaceMembership.JOINED
                Membership.LEFT -> MatrixSpaceMembership.LEFT
                Membership.KNOCKED -> MatrixSpaceMembership.KNOCKED
                Membership.BANNED -> MatrixSpaceMembership.BANNED
                null -> MatrixSpaceMembership.UNKNOWN
            },
            lastMessageText = latestPreview.body,
            lastMessageSenderName = latestPreview.senderName,
            lastMessageAtMillis = latestPreview.timestampMillis,
            lastOwnMessageStatus = resolveLastOwnMessageStatus(latestPreview),
            unreadCount = roomInfo?.numUnreadMessages?.toLong() ?: 0,
            unreadMentionCount = roomInfo?.numUnreadMentions?.toLong() ?: 0,
            isMarkedUnread = roomInfo?.isMarkedUnread ?: false,
            roomDetails = details
        )
    }

    private suspend fun <T> readRoomListField(
        roomId: String,
        field: String,
        read: suspend () -> T
    ): T? {
        return try {
            read()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to read room-list $field for $roomId", error)
            null
        }
    }

    private fun RoomInfo.directUserId(): String? {
        if (!isDirect && !isDm) {
            return null
        }
        return heroes.firstOrNull()
            ?.userId
            ?.takeIf { it.isNotBlank() }
    }

    private fun RoomInfo.toMatrixRoomDetails(
        resolveCapabilities: Boolean
    ): MatrixRoomDetails {
        val directUserId = directUserId()
        val kind = when {
            isSpace -> MatrixRoomKind.SPACE
            directUserId != null -> MatrixRoomKind.DIRECT
            else -> MatrixRoomKind.GROUP
        }
        return MatrixRoomDetails(
            roomId = id,
            displayName = displayName
                ?.takeIf { it.isNotBlank() }
                ?: rawName?.takeIf { it.isNotBlank() }
                ?: id,
            avatarUrl = avatarUrl?.takeIf { it.isNotBlank() },
            directUserId = directUserId,
            kind = kind,
            topic = topic?.takeIf { it.isNotBlank() },
            joinedMemberCount = joinedMembersCount
                .coerceAtMost(Long.MAX_VALUE.toULong())
                .toLong(),
            encryption = when (encryptionState) {
                EncryptionState.ENCRYPTED -> MatrixRoomEncryption.ENCRYPTED
                EncryptionState.NOT_ENCRYPTED -> MatrixRoomEncryption.NOT_ENCRYPTED
                EncryptionState.UNKNOWN -> MatrixRoomEncryption.UNKNOWN
            },
            access = when (joinRule) {
                JoinRule.Public -> MatrixRoomAccess.PUBLIC
                JoinRule.Invite, JoinRule.Private -> MatrixRoomAccess.PRIVATE
                JoinRule.Knock, is JoinRule.KnockRestricted -> MatrixRoomAccess.ASK_TO_JOIN
                is JoinRule.Restricted -> MatrixRoomAccess.RESTRICTED
                is JoinRule.Custom -> MatrixRoomAccess.CUSTOM
                null -> MatrixRoomAccess.UNKNOWN
            },
            historyVisibility = when (historyVisibility) {
                RoomHistoryVisibility.Shared -> MatrixRoomHistoryVisibility.SHARED
                RoomHistoryVisibility.Invited -> MatrixRoomHistoryVisibility.INVITED
                RoomHistoryVisibility.Joined -> MatrixRoomHistoryVisibility.JOINED
                RoomHistoryVisibility.WorldReadable -> MatrixRoomHistoryVisibility.WORLD_READABLE
                is RoomHistoryVisibility.Custom -> MatrixRoomHistoryVisibility.CUSTOM
            },
            pinnedEventCount = pinnedEventIds.size,
            canonicalAlias = canonicalAlias?.takeIf { it.isNotBlank() },
            roomVersion = roomVersion?.takeIf { it.isNotBlank() },
            creatorSemantics = matrixRoomCreatorSemantics(
                roomVersion = roomVersion,
                privilegedCreatorsRole = privilegedCreatorsRole
            ),
            capabilities = MatrixRoomCapabilities(
                canInviteMembers = if (resolveCapabilities && kind != MatrixRoomKind.DIRECT) {
                    powerLevels?.canOwnUserInvite()
                } else {
                    null
                },
                canChangeName = if (resolveCapabilities && kind != MatrixRoomKind.DIRECT) {
                    powerLevels?.canOwnUserSendState(StateEventType.RoomName)
                } else {
                    null
                },
                canChangeAvatar = if (resolveCapabilities && kind != MatrixRoomKind.DIRECT) {
                    powerLevels?.canOwnUserSendState(StateEventType.RoomAvatar)
                } else {
                    null
                }
            )
        )
    }

    private fun RustRoomMember.toMatrixRoomMemberOrNull(
        includeLeft: Boolean = false
    ): MatrixRoomMember? {
        val mappedMembership = when (membership) {
            MembershipState.Invite -> MatrixRoomMemberMembership.INVITED
            MembershipState.Join -> MatrixRoomMemberMembership.JOINED
            MembershipState.Ban -> MatrixRoomMemberMembership.BANNED
            MembershipState.Leave -> {
                if (includeLeft) MatrixRoomMemberMembership.LEFT else return null
            }
            MembershipState.Knock,
            is MembershipState.Custom -> return null
        }
        val mappedPowerLevel = when (val level = powerLevel) {
            PowerLevel.Infinite -> Long.MAX_VALUE
            is PowerLevel.Value -> level.value
        }
        val mappedRole = suggestedRoleForPowerLevel.toMatrixRoomMemberRole(mappedPowerLevel)
        return MatrixRoomMember(
            userId = userId,
            displayName = displayName?.takeIf { it.isNotBlank() },
            avatarUrl = avatarUrl?.takeIf { it.isNotBlank() },
            membership = mappedMembership,
            role = mappedRole,
            powerLevel = mappedPowerLevel,
            isNameAmbiguous = isNameAmbiguous
        )
    }

    private fun org.matrix.rustcomponents.sdk.RoomPowerLevels
        .toMatrixRoomRoleChangeContext(
            roomId: String,
            ownUserId: String,
            ownMember: RustRoomMember,
            targetMember: RustRoomMember
        ): MatrixRoomRoleChangeContext {
        val mappedOwnMember = ownMember.toMatrixRoomMemberOrNull()
            ?: error("The current user is not an active room member")
        val mappedTarget = targetMember.toMatrixRoomMemberOrNull()
            ?: error("The selected user is not an active room member")
        return MatrixRoomRoleChangeContext(
            roomId = roomId,
            ownUserId = ownUserId,
            ownPowerLevel = mappedOwnMember.powerLevel,
            canEditPowerLevels = canOwnUserSendState(StateEventType.RoomPowerLevels),
            targetUserId = mappedTarget.userId,
            targetPowerLevel = mappedTarget.powerLevel,
            targetMembership = mappedTarget.membership,
            targetRole = mappedTarget.role
        )
    }

    private fun org.matrix.rustcomponents.sdk.RoomPowerLevels
        .toMatrixRoomMemberModerationContext(
            roomId: String,
            ownUserId: String,
            ownMember: RustRoomMember,
            targetMember: RustRoomMember
        ): MatrixRoomMemberModerationContext {
        val mappedOwnMember = ownMember.toMatrixRoomMemberOrNull()
            ?: error("The current user is not an active room member")
        val mappedTarget = targetMember.toMatrixRoomMemberOrNull(includeLeft = true)
            ?: error("The selected room membership is not supported")
        return MatrixRoomMemberModerationContext(
            roomId = roomId,
            ownUserId = ownUserId,
            ownPowerLevel = mappedOwnMember.powerLevel,
            canKickMembers = canOwnUserKick(),
            canBanMembers = canOwnUserBan(),
            member = mappedTarget
        )
    }

    private fun RoomInfo.toRoomCallInfo(
        directHasActiveCall: Boolean,
        directParticipantUserIds: List<String>
    ): MatrixRoomCallInfo {
        val participantUserIds = activeRoomCallParticipants
            .takeIf { it.isNotEmpty() }
            ?: directParticipantUserIds

        return MatrixRoomCallInfo(
            roomId = id,
            hasRoomCall = hasRoomCall || directHasActiveCall,
            activeParticipantUserIds = participantUserIds.toList(),
            isAudioCall = activeRoomCallConsensusIntent.isAudioCompatible()
        )
    }

    private suspend fun loadPushNotificationResolution(
        roomId: String,
        eventId: String,
        unreadCount: Int?
    ): ZynaPushNotificationResolution? = withContext(Dispatchers.IO) {
        val activeClient = client
        if (activeClient != null) {
            return@withContext activeClient.resolvePushNotificationWithClient(
                roomId = roomId,
                eventId = eventId,
                unreadCount = unreadCount,
                processSetup = syncService?.let { activeSyncService ->
                    NotificationProcessSetup.SingleProcess(activeSyncService)
                }
                    ?: NotificationProcessSetup.MultipleProcesses
            )
        }

        val session = sessionStore.loadLastSession() ?: return@withContext null
        var temporaryClient: Client? = null
        try {
            temporaryClient = buildClient(session.homeserverUrl)
            temporaryClient.restoreSession(session)
            temporaryClient.resolvePushNotificationWithClient(
                roomId = roomId,
                eventId = eventId,
                unreadCount = unreadCount,
                processSetup = NotificationProcessSetup.MultipleProcesses
            )
        } finally {
            temporaryClient?.close()
        }
    }

    private suspend fun Client.resolvePushNotificationWithClient(
        roomId: String,
        eventId: String,
        unreadCount: Int?,
        processSetup: NotificationProcessSetup
    ): ZynaPushNotificationResolution {
        val notificationClient = notificationClient(processSetup)
        return try {
            val status = notificationClient.getNotification(roomId = roomId, eventId = eventId)
            try {
                status.toPushNotificationResolution(
                    unreadCount = unreadCount,
                    ownUserId = userId(),
                    roomId = roomId
                )
            } finally {
                status.destroy()
            }
        } finally {
            notificationClient.destroy()
        }
    }

    private fun NotificationStatus.toPushNotificationResolution(
        unreadCount: Int?,
        ownUserId: String,
        roomId: String
    ): ZynaPushNotificationResolution {
        return when (this) {
            is NotificationStatus.Event -> item.toPushNotificationResolution(
                unreadCount = unreadCount,
                ownUserId = ownUserId,
                roomId = roomId
            )
            NotificationStatus.EventFilteredOut,
            NotificationStatus.EventRedacted -> ZynaPushNotificationResolution.Suppressed
            NotificationStatus.EventNotFound -> ZynaPushNotificationResolution.Unavailable
        }
    }

    private fun NotificationItem.toPushNotificationResolution(
        unreadCount: Int?,
        ownUserId: String,
        roomId: String
    ): ZynaPushNotificationResolution {
        val isCallNotificationEvent = rawEvent.isMatrixRtcCallNotificationEvent()
        val incomingCall = try {
            pushIncomingCallOrNull(ownUserId = ownUserId, roomId = roomId)
        } catch (error: Throwable) {
            if (isCallNotificationEvent) {
                Log.w(TAG, "MatrixRTC call push ignored: failed to parse notification", error)
                null
            } else {
                throw error
            }
        }
        incomingCall?.let { call ->
            return ZynaPushNotificationResolution.IncomingCall(call)
        }
        if (isCallNotificationEvent) {
            return ZynaPushNotificationResolution.Suppressed
        }

        val body = pushNotificationBodyOrNull()
            ?: return ZynaPushNotificationResolution.Unavailable
        return ZynaPushNotificationResolution.Resolved(
            ZynaPushNotificationContent(
                title = pushNotificationTitle(),
                body = formattedPushNotificationBody(body),
                isNoisy = isNoisy == true,
                unreadCount = unreadCount
            )
        )
    }

    private fun NotificationItem.pushIncomingCallOrNull(
        ownUserId: String,
        roomId: String
    ): MatrixRtcIncomingCall? {
        val timelineEvent = (event as? NotificationEvent.Timeline)?.event
            ?: return null.also {
                Log.d(TAG, "MatrixRTC call push ignored: notification event is not timeline")
            }
        val eventId = timelineEvent.eventId()
        val eventContent = timelineEvent.content()
        return try {
            val messageLike = eventContent as? TimelineEventContent.MessageLike
                ?: return null.also {
                    Log.d(TAG, "MatrixRTC call push ignored: event_id=$eventId content is not message-like")
                }
            val rtcNotification =
                messageLike.content as? MessageLikeEventContent.RtcNotification
                    ?: return null.also {
                        Log.d(TAG, "MatrixRTC call push ignored: event_id=$eventId content is not rtc notification")
                    }
            if (rtcNotification.notificationType != RtcNotificationType.RING) {
                Log.d(
                    TAG,
                    "MatrixRTC call push ignored: event_id=$eventId type=${rtcNotification.notificationType}"
                )
                return null
            }

            val senderId = timelineEvent.senderId().takeIf { it.isNotBlank() }
                ?: return null.also {
                    Log.d(TAG, "MatrixRTC call push ignored: event_id=$eventId missing sender")
                }
            if (senderId == ownUserId) {
                Log.d(TAG, "MatrixRTC call push ignored: event_id=$eventId from own user")
                return null
            }
            val expiresAtMillis = rtcNotification.expirationTs.toLong()
            val nowMillis = System.currentTimeMillis()
            if (expiresAtMillis <= nowMillis) {
                Log.d(
                    TAG,
                    "MatrixRTC call push ignored: event_id=$eventId expired " +
                        "expires_at=$expiresAtMillis now=$nowMillis " +
                        "expired_by_ms=${nowMillis - expiresAtMillis}"
                )
                return null
            }
            val isAudioCall = rtcNotification.callIntent.isAudioCompatible()
            if (!isAudioCall) {
                Log.d(
                    TAG,
                    "MatrixRTC call push ignored: event_id=$eventId " +
                        "unsupported_call_intent=${rtcNotification.callIntent}"
                )
                return null
            }

            MatrixRtcIncomingCall(
                eventId = eventId,
                roomId = roomId,
                senderId = senderId,
                senderName = senderDisplayNameOrNull() ?: senderId,
                roomName = roomInfo.displayName.takeIf { it.isNotBlank() },
                isAudioCall = true,
                expiresAtMillis = expiresAtMillis
            )
        } finally {
            eventContent.destroy()
        }
    }

    private fun NotificationItem.pushNotificationTitle(): String {
        val sender = senderDisplayNameOrNull()
        val room = roomInfo.displayName.takeIf { it.isNotBlank() }
        return if (roomInfo.isDirect || roomInfo.isDm) {
            sender ?: room ?: DEFAULT_PUSH_NOTIFICATION_TITLE
        } else {
            room ?: sender ?: DEFAULT_PUSH_NOTIFICATION_TITLE
        }
    }

    private fun NotificationItem.formattedPushNotificationBody(body: String): String {
        if (roomInfo.isDirect || roomInfo.isDm || event is NotificationEvent.Invite) {
            return body
        }
        val sender = senderDisplayNameOrNull() ?: return body
        return "$sender: $body"
    }

    private fun NotificationItem.senderDisplayNameOrNull(): String? {
        return senderInfo.displayName?.takeIf { it.isNotBlank() }
            ?: when (val notificationEvent = event) {
                is NotificationEvent.Timeline -> notificationEvent.event.senderId()
                    .takeIf { it.isNotBlank() }
                is NotificationEvent.Invite -> notificationEvent.sender.takeIf { it.isNotBlank() }
            }
    }

    private fun NotificationItem.pushNotificationBodyOrNull(): String? {
        val rawEventBody = rawEvent.rawEventBodyOrNull()
        return when (val notificationEvent = event) {
            is NotificationEvent.Timeline ->
                notificationEvent.event.pushNotificationBodyOrNull(rawEventBody = rawEventBody)
            is NotificationEvent.Invite -> {
                val sender = senderDisplayNameOrNull()
                if (sender == null) "Room invitation" else "$sender invited you"
            }
        }
    }

    private fun org.matrix.rustcomponents.sdk.TimelineEvent.pushNotificationBodyOrNull(
        rawEventBody: String?
    ): String? {
        val eventContent = content()
        return try {
            (eventContent as? TimelineEventContent.MessageLike)
                ?.content
                ?.pushNotificationBodyOrNull(rawEventBody = rawEventBody)
        } finally {
            eventContent.destroy()
        }
    }

    private fun MessageLikeEventContent.pushNotificationBodyOrNull(rawEventBody: String?): String? {
        return when (this) {
            MessageLikeEventContent.CallAnswer -> "Call answered"
            MessageLikeEventContent.CallInvite -> "Incoming call"
            MessageLikeEventContent.CallHangup -> "Call ended"
            MessageLikeEventContent.CallCandidates -> "Call update"
            MessageLikeEventContent.KeyVerificationReady,
            MessageLikeEventContent.KeyVerificationStart -> "Verification request"
            MessageLikeEventContent.KeyVerificationCancel -> "Verification cancelled"
            MessageLikeEventContent.KeyVerificationAccept,
            MessageLikeEventContent.KeyVerificationKey,
            MessageLikeEventContent.KeyVerificationMac,
            MessageLikeEventContent.KeyVerificationDone -> "Verification update"
            is MessageLikeEventContent.Poll -> "Poll: $question"
            is MessageLikeEventContent.ReactionContent -> "Reaction"
            MessageLikeEventContent.RoomEncrypted -> "Unable to decrypt message"
            is MessageLikeEventContent.RoomMessage -> messageType
                .displayBody(fallbackBody = rawEventBody)
                .stripMatrixReplyFallback()
            is MessageLikeEventContent.RoomRedaction -> "Deleted message"
            MessageLikeEventContent.Sticker -> "Sticker"
            is MessageLikeEventContent.RtcNotification -> "Incoming call"
        }?.normalizedPushBody()
    }

    private fun MessageType.displayBody(fallbackBody: String? = null): String {
        return when (val type = this) {
            is MessageType.Text -> type.content.body
            is MessageType.Notice -> type.content.body
            is MessageType.Emote -> type.content.body
            is MessageType.Image -> type.content.caption.normalizedMessageCaption() ?: "Photo"
            is MessageType.Audio ->
                type.content.caption.normalizedMessageCaption()
                    ?: fallbackBody?.ifBlank { null }
                    ?: type.content.filename.ifBlank { "Audio" }
            is MessageType.Video ->
                type.content.caption.normalizedMessageCaption()
                    ?: fallbackBody?.ifBlank { null }
                    ?: type.content.filename.ifBlank { "Video" }
            is MessageType.File ->
                type.content.caption.normalizedMessageCaption()
                    ?: fallbackBody?.ifBlank { null }
                    ?: type.content.filename.ifBlank { "File" }
            is MessageType.Gallery -> type.content.body
            is MessageType.Location -> type.content.body
            is MessageType.Other -> type.body
        }
    }

    private fun String.rawEventBodyOrNull(): String? {
        return runCatching {
            JSONObject(this)
                .optJSONObject("content")
                ?.optStringOrNull("body")
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun String.isReactionFor(targetEventId: String, reactionKey: String): Boolean {
        return runCatching {
            val relatesTo = JSONObject(this)
                .optJSONObject("m.relates_to")
                ?: return@runCatching false
            relatesTo.optStringOrNull("rel_type") == "m.annotation" &&
                relatesTo.optStringOrNull("event_id") == targetEventId &&
                relatesTo.optStringOrNull("key") == reactionKey
        }.getOrDefault(false)
    }

    private fun String.isMatrixRtcCallNotificationEvent(): Boolean {
        return runCatching {
            when (JSONObject(this).optStringOrNull("type")) {
                MatrixRtcCallNotificationContent.EVENT_TYPE,
                MatrixRtcLegacyCallNotifyContent.EVENT_TYPE -> true
                else -> false
            }
        }.getOrDefault(false)
    }

    private fun String.normalizedPushBody(): String? {
        return replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(separator = " ")
            .takeIf { it.isNotBlank() }
    }

    private fun parseIncomingMatrixRtcCallNotification(
        notification: NotificationItem,
        roomId: String,
        ownUserId: String
    ): MatrixIncomingRtcCallNotification? {
        val timelineEvent = (notification.event as? NotificationEvent.Timeline)?.event ?: return null
        val eventContent = runCatching { timelineEvent.content() }
            .getOrElse { error ->
                if (notification.rawEvent.isMatrixRtcCallNotificationEvent()) {
                    return null
                }
                throw error
            }
        try {
            val messageLike = eventContent as? TimelineEventContent.MessageLike ?: return null
            val rtcNotification = messageLike.content as? MessageLikeEventContent.RtcNotification ?: return null
            val senderId = timelineEvent.senderId()
            if (senderId == ownUserId) {
                return null
            }

            val expiresAtMillis = rtcNotification.expirationTs.toLong()
            if (expiresAtMillis <= System.currentTimeMillis()) {
                return null
            }

            return MatrixIncomingRtcCallNotification(
                eventId = timelineEvent.eventId(),
                roomId = roomId,
                senderId = senderId,
                kind = when (rtcNotification.notificationType) {
                    RtcNotificationType.RING -> MatrixIncomingRtcCallNotificationKind.RING
                    RtcNotificationType.NOTIFICATION -> MatrixIncomingRtcCallNotificationKind.NOTIFICATION
                },
                isAudioCall = rtcNotification.callIntent.isAudioCompatible(),
                expiresAtMillis = expiresAtMillis
            )
        } finally {
            eventContent.destroy()
        }
    }

    private fun markMatrixRtcNotificationDelivered(eventId: String): Boolean {
        synchronized(deliveredMatrixRtcNotificationIds) {
            if (!deliveredMatrixRtcNotificationIds.add(eventId)) {
                return false
            }
            if (deliveredMatrixRtcNotificationIds.size > 200) {
                deliveredMatrixRtcNotificationIds.firstOrNull()?.let { oldest ->
                    deliveredMatrixRtcNotificationIds.remove(oldest)
                }
            }
            return true
        }
    }

    private fun String?.isAudioCompatible(): Boolean {
        return this == null || this == "audio" || this == "m.audio"
    }

    private fun RtcCallIntent?.isAudioCompatible(): Boolean {
        return this == null || this == RtcCallIntent.AUDIO
    }

    private fun RtcCallIntentConsensus.isAudioCompatible(): Boolean {
        return when (this) {
            is RtcCallIntentConsensus.Full -> v1 == RtcCallIntent.AUDIO
            is RtcCallIntentConsensus.Partial -> intent == RtcCallIntent.AUDIO
            RtcCallIntentConsensus.None -> true
        }
    }

    private fun RtcCallIntentConsensus.debugSummary(): String {
        return when (this) {
            is RtcCallIntentConsensus.Full -> "Full($v1)"
            is RtcCallIntentConsensus.Partial -> "Partial($intent,$agreeingCount/$totalCount)"
            RtcCallIntentConsensus.None -> "None"
        }
    }

    private suspend fun Room.resolveLastOwnMessageStatus(
        preview: MatrixRoomPreview
    ): MatrixLastOwnMessageStatus? {
        if (!preview.needsReadReceiptSummary) {
            return preview.localOwnMessageStatus
        }

        val readReceiptSummary = runCatching {
            latestOwnMainTimelineReadReceiptSummary()
        }.getOrNull()
        return if (readReceiptSummary?.hasReadReceiptFromOtherUser == true) {
            MatrixLastOwnMessageStatus.READ
        } else {
            preview.localOwnMessageStatus
        }
    }

    private fun LatestEventValue.toRoomPreview(): MatrixRoomPreview = use { latestEvent ->
        when (latestEvent) {
            LatestEventValue.None -> MatrixRoomPreview()
            is LatestEventValue.Remote -> matrixRoomPreviewForTimelineEvent(
                body = latestEvent.content.roomPreviewBody(),
                senderName = latestEvent.sender.previewSenderName(
                    isOwn = latestEvent.isOwn,
                    profile = latestEvent.profile
                ),
                timestampMillis = latestEvent.timestamp.toLong(),
                localOwnMessageStatus = if (latestEvent.isOwn) {
                    MatrixLastOwnMessageStatus.SENT
                } else {
                    null
                },
                needsReadReceiptSummary = latestEvent.isOwn
            )
            is LatestEventValue.Local -> matrixRoomPreviewForTimelineEvent(
                body = latestEvent.content.roomPreviewBody(),
                senderName = latestEvent.sender.previewSenderName(
                    isOwn = true,
                    profile = latestEvent.profile
                ),
                timestampMillis = latestEvent.timestamp.toLong(),
                localOwnMessageStatus = latestEvent.state.toLastOwnMessageStatus()
            )
            is LatestEventValue.RemoteInvite ->
                matrixRoomPreviewForInvite(latestEvent.timestamp.toLong())
        }
    }

    private fun TimelineItemContent.roomPreviewBody(): String? {
        val messageContent = (this as? TimelineItemContent.MsgLike)?.content
            ?: return null
        return when (val kind = messageContent.kind) {
            is MsgLikeKind.Message -> kind.content.roomPreviewBody()
            is MsgLikeKind.Sticker -> "Sticker"
            is MsgLikeKind.Poll -> "Poll: ${kind.question}"
            MsgLikeKind.Redacted -> "Deleted message"
            is MsgLikeKind.UnableToDecrypt -> "Unable to decrypt message"
            is MsgLikeKind.LiveLocation -> "Live location"
            is MsgLikeKind.Other -> null
        }
    }

    private fun MessageContent.roomPreviewBody(): String {
        return when (val type = msgType) {
            is MessageType.Text -> type.content.body
            is MessageType.Notice -> type.content.body
            is MessageType.Emote -> type.content.body
            is MessageType.Image -> "Photo"
            is MessageType.Audio -> "Audio"
            is MessageType.Video -> "Video"
            is MessageType.File -> "File"
            is MessageType.Location -> "Location"
            is MessageType.Gallery,
            is MessageType.Other -> "Message"
        }
    }

    private fun String.previewSenderName(isOwn: Boolean, profile: ProfileDetails): String {
        if (isOwn) return OWN_MESSAGE_PREVIEW_SENDER

        val displayName = (profile as? ProfileDetails.Ready)?.displayName
        return displayName?.takeIf { it.isNotBlank() } ?: this
    }

    private fun ProfileDetails.displayNameOrNull(): String? {
        return (this as? ProfileDetails.Ready)
            ?.displayName
            ?.takeIf { it.isNotBlank() }
    }

    private fun UserProfile.toMatrixUserProfile(): MatrixUserProfile {
        return MatrixUserProfile(
            userId = userId,
            displayName = displayName?.takeIf { it.isNotBlank() },
            avatarUrl = avatarUrl?.takeIf { it.isNotBlank() }
        )
    }

    private fun ULong.toIntOrNull(): Int? {
        return takeIf { it in 1UL..Int.MAX_VALUE.toULong() }?.toInt()
    }

    private fun ULong.toLongOrNull(): Long? {
        return takeIf { it in 1UL..Long.MAX_VALUE.toULong() }?.toLong()
    }

    private fun List<UShort>?.normalizedWaveform(): List<Float> {
        if (isNullOrEmpty()) {
            return emptyList()
        }
        val maxValue = maxOf { it.toInt() }.coerceAtLeast(MATRIX_WAVEFORM_DEFAULT_PEAK)
        return map { sample ->
            (sample.toInt().toFloat() / maxValue.toFloat()).coerceIn(0f, 1f)
        }
    }

    private fun LatestEventValueLocalState.toLastOwnMessageStatus(): MatrixLastOwnMessageStatus {
        return when (this) {
            LatestEventValueLocalState.IS_SENDING -> MatrixLastOwnMessageStatus.PENDING
            LatestEventValueLocalState.HAS_BEEN_SENT -> MatrixLastOwnMessageStatus.SENT
            LatestEventValueLocalState.CANNOT_BE_SENT -> MatrixLastOwnMessageStatus.FAILED
        }
    }

    private fun TaskHandle.cancelAndDestroy() {
        cancel()
        destroy()
    }

    private fun liveTimelineConfiguration(roomId: String): TimelineConfiguration {
        return TimelineConfiguration(
            focus = TimelineFocus.Live(hideThreadedEvents = false),
            filter = TimelineFilter.All,
            internalIdPrefix = "room_$roomId",
            dateDividerMode = DateDividerMode.DAILY,
            trackReadReceipts = TimelineReadReceiptTracking.ALL_EVENTS,
            reportUtds = false
        )
    }

    private suspend fun buildClient(homeserver: String): Client {
        val storePassphrase = prepareStorePassphrase()

        return withContext(Dispatchers.IO) {
            val paths = matrixStorePaths()
            ClientBuilder()
                .serverNameOrHomeserverUrl(homeserver)
                .sqliteStore(
                    SqliteStoreBuilder(paths.dataPath, paths.cachePath)
                        .passphrase(storePassphrase)
                )
                .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.DISCOVER_NATIVE)
                .setSessionDelegate(sessionDelegate)
                .autoEnableCrossSigning(true)
                .autoEnableBackups(true)
                .backupDownloadStrategy(BackupDownloadStrategy.AFTER_DECRYPTION_FAILURE)
                .userAgent("Zyna Android")
                .build()
        }
    }

    private suspend fun prepareStorePassphrase(): String {
        val existing = withContext(Dispatchers.IO) {
            storePassphraseStore.loadPassphraseOrNull()
        }
        if (existing != null) {
            return existing
        }

        clearMatrixStoreDirectories()
        return withContext(Dispatchers.IO) {
            storePassphraseStore.createPassphrase()
        }
    }

    private suspend fun clearStoredMatrixState() {
        sessionStore.clear()
        storePassphraseStore.clear()
        pushRegistrar.clearLocalState()
        clearMatrixStoreDirectories()
    }

    private suspend fun startSync() {
        val activeClient = client ?: return
        if (syncService != null) {
            _state.value = MatrixClientState.Syncing(activeClient.userId())
            registerPushPusher(activeClient)
            return
        }

        val service = withContext(Dispatchers.IO) {
            activeClient.syncService()
                .withOfflineMode()
                .finish()
        }
        val roomList = service.roomListService()
        syncService = service
        synchronized(roomListSessionsLock) {
            roomListService = roomList
        }
        registerMatrixRtcNotificationHandler(activeClient)
        service.start()
        _state.value = MatrixClientState.Syncing(activeClient.userId())
        registerPushPusher(activeClient)
    }

    private fun activeMatrixUserId(): String? {
        return when (val current = state.value) {
            is MatrixClientState.LoggedIn -> current.userId
            is MatrixClientState.Syncing -> current.userId
            else -> null
        }
    }

    private suspend fun closeRoomListResources() {
        val resources = synchronized(roomListSessionsLock) {
            val sessions = activeRoomListSessions.toList()
            activeRoomListSessions.clear()
            val service = roomListService
            roomListService = null
            sessions to service
        }
        withContext(NonCancellable + Dispatchers.IO) {
            resources.first.forEach { session -> runCatching { session.close() } }
            resources.second?.close()
        }
    }

    private suspend fun registerPushPusher(activeClient: Client) {
        try {
            pushRegistrar.register(activeClient)
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to register Android push pusher", error)
        }
    }

    private suspend fun unregisterPushPusher(activeClient: Client) {
        try {
            pushRegistrar.unregister(activeClient)
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to unregister Android push pusher", error)
        }
    }

    private suspend fun registerMatrixRtcNotificationHandler(activeClient: Client) {
        if (matrixRtcNotificationHandlerClient === activeClient) {
            return
        }

        val ownUserId = activeClient.userId()
        withContext(Dispatchers.IO) {
            activeClient.registerNotificationHandler(
                object : SyncNotificationListener {
                    override fun onNotification(notification: NotificationItem, roomId: String) {
                        val incoming = try {
                            parseIncomingMatrixRtcCallNotification(
                                notification = notification,
                                roomId = roomId,
                                ownUserId = ownUserId
                            )
                        } catch (error: Throwable) {
                            Log.w(TAG, "Failed to parse MatrixRTC notification", error)
                            null
                        } finally {
                            notification.destroy()
                        } ?: return

                        if (!markMatrixRtcNotificationDelivered(incoming.eventId)) {
                            return
                        }

                        if (BuildConfig.DEBUG) {
                            Log.d(
                                TAG,
                                "incomingMatrixRtcNotification roomId=$roomId " +
                                    "eventId=${incoming.eventId} sender=${incoming.senderId} " +
                                    "kind=${incoming.kind} audio=${incoming.isAudioCall} " +
                                    "expiresAt=${incoming.expiresAtMillis}"
                            )
                        }
                        _incomingMatrixRtcCallNotifications.tryEmit(incoming)
                    }
                }
            )
        }
        matrixRtcNotificationHandlerClient = activeClient
    }

    private fun matrixStorePaths(): MatrixStorePaths {
        val dataDir = File(context.filesDir, "matrix/data")
        val cacheDir = File(context.cacheDir, "matrix/cache")
        dataDir.mkdirs()
        cacheDir.mkdirs()
        return MatrixStorePaths(
            dataPath = dataDir.absolutePath,
            cachePath = cacheDir.absolutePath
        )
    }

    private suspend fun clearMatrixStoreDirectories() = withContext(Dispatchers.IO) {
        File(context.filesDir, "matrix").deleteRecursively()
        File(context.cacheDir, "matrix").deleteRecursively()
    }

    private fun normalizeHomeserver(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    private fun Throwable.displayMessage(): String {
        return message ?: javaClass.simpleName
    }

    private data class MatrixStorePaths(
        val dataPath: String,
        val cachePath: String
    )

    private data class MatrixMessageBody(
        val body: String,
        val contentType: MatrixMessageContentType,
        val imageInfo: MatrixImageInfo?,
        val audioInfo: MatrixAudioInfo?
    )

    private companion object {
        const val TAG = "MatrixClientService"
        const val TIMELINE_PAGE_SIZE = 100
        const val TIMELINE_INITIAL_BACKFILL_PAGES = 5
        const val TIMELINE_INTERACTIVE_BACKFILL_PAGES = 3
        const val TIMELINE_EMIT_COALESCE_MS = 50L
        const val TIMELINE_UPDATE_TIMEOUT_MS = 2_000L
        const val ROOM_MEMBERS_CHUNK_SIZE = 512
        const val MAX_REACTION_RELATION_PAGES = 20
        const val TRANSACTION_ID_CONTENT_KEY = "com.zyna.client_txn_id"
        const val OWN_MESSAGE_PREVIEW_SENDER = "You"
        const val DEFAULT_PUSH_NOTIFICATION_TITLE = "Zyna"
        const val PUSH_NOTIFICATION_RESOLVE_TIMEOUT_MS = 10_000L
        const val ZERO_WIDTH_SPACE = "\u200B"
        const val DEFAULT_AUDIO_MIME_TYPE = "audio/mpeg"
        const val MATRIX_WAVEFORM_DEFAULT_PEAK = 1024
        const val DEFAULT_LEGACY_CALL_NOTIFICATION_LIFETIME_MS = 30_000L
    }
}

internal fun RoomMemberRole.toMatrixRoomMemberRole(powerLevel: Long): MatrixRoomMemberRole {
    return when (this) {
        RoomMemberRole.CREATOR -> MatrixRoomMemberRole.CREATOR
        RoomMemberRole.ADMINISTRATOR -> {
            if (powerLevel >= MATRIX_ROOM_OWNER_POWER_LEVEL) {
                MatrixRoomMemberRole.OWNER
            } else {
                MatrixRoomMemberRole.ADMIN
            }
        }
        RoomMemberRole.MODERATOR -> MatrixRoomMemberRole.MODERATOR
        RoomMemberRole.USER -> MatrixRoomMemberRole.MEMBER
    }
}

private fun MatrixRoomMemberRole.isRoomOwnershipRole(): Boolean {
    return this == MatrixRoomMemberRole.CREATOR ||
        this == MatrixRoomMemberRole.OWNER ||
        this == MatrixRoomMemberRole.ADMIN
}

private const val MATRIX_ROOM_OWNER_POWER_LEVEL = 150L

private class AndroidMatrixSessionDelegate(
    private val sessionStore: MatrixSessionStore
) : ClientSessionDelegate {
    override fun retrieveSessionFromKeychain(userId: String): Session {
        return sessionStore.loadSession(userId)
            ?: error("No Matrix session stored for $userId")
    }

    override fun saveSessionInKeychain(session: Session) {
        sessionStore.save(session)
    }
}
