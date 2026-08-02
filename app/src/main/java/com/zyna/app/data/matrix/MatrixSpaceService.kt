package com.zyna.app.data.matrix

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.AllowRule
import org.matrix.rustcomponents.sdk.JoinRule
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.RoomType
import org.matrix.rustcomponents.sdk.SpaceListUpdate
import org.matrix.rustcomponents.sdk.SpaceRoom
import org.matrix.rustcomponents.sdk.SpaceRoomList
import org.matrix.rustcomponents.sdk.SpaceRoomListEntriesListener
import org.matrix.rustcomponents.sdk.SpaceRoomListPaginationStateListener
import org.matrix.rustcomponents.sdk.SpaceRoomListSpaceListener
import org.matrix.rustcomponents.sdk.SpaceService
import org.matrix.rustcomponents.sdk.SpaceServiceJoinedSpacesListener
import org.matrix.rustcomponents.sdk.TaskHandle
import uniffi.matrix_sdk_ui.SpaceRoomListPaginationState

data class MatrixSpaceRemoteSnapshot(
    val space: MatrixSpaceRoom? = null,
    val rooms: List<MatrixSpaceRoom> = emptyList(),
    val isKnown: Boolean = false,
    val isPaginating: Boolean = false,
    val endReached: Boolean = false
)

interface MatrixSpaceRoomListSession : AutoCloseable {
    val snapshots: StateFlow<MatrixSpaceRemoteSnapshot>

    suspend fun paginate()
}

/**
 * Session-scoped owner of the Matrix SDK Spaces service and child-list FFI handles.
 */
class MatrixSpaceService(
    private val matrixClientService: MatrixClientService
) {
    private data class ActiveSession(
        val userId: String,
        val service: SpaceService
    )

    private val mutex = Mutex()
    private var activeSession: ActiveSession? = null
    private val childSessions = mutableSetOf<SdkSpaceRoomListSession>()

    suspend fun activate(userId: String) {
        val normalizedUserId = userId.trim().takeIf(String::isNotEmpty) ?: return
        mutex.withLock {
            if (activeSession?.userId == normalizedUserId) {
                return
            }
            closeLocked()
            activeSession = ActiveSession(
                userId = normalizedUserId,
                service = matrixClientService.openSpaceService(normalizedUserId)
            )
        }
    }

    suspend fun deactivate() {
        mutex.withLock {
            closeLocked()
        }
    }

    fun topLevelSpaceReadiness(): Flow<Boolean> {
        return matrixClientService.roomListLoadedStates()
    }

    suspend fun currentTopLevelSpaces(userId: String): List<MatrixSpaceRoom> {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                activeSession
                    ?.takeIf { it.userId == userId }
                    ?.service
                    ?.topLevelJoinedSpaces()
                    ?.map(SpaceRoom::toMatrixSpaceRoom)
                    ?: error("Spaces are not active for this Matrix session")
            }
        }
    }

    fun topLevelSpaceUpdates(userId: String): Flow<List<MatrixSpaceListUpdate>> {
        return callbackFlow {
            val service = requireActiveService(userId)
            val listener = object : SpaceServiceJoinedSpacesListener {
                override fun onUpdate(roomUpdates: List<SpaceListUpdate>) {
                    trySendBlocking(roomUpdates.mapSdkUpdates())
                }
            }
            val handle = service.subscribeToTopLevelJoinedSpaces(listener)
            try {
                val current = service.topLevelJoinedSpaces().map(SpaceRoom::toMatrixSpaceRoom)
                trySend(listOf(MatrixSpaceListUpdate.Reset(current)))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // The subscription remains authoritative and normally emits its own Reset.
            }
            awaitClose {
                handle.cancelAndDestroy()
            }
        }
            .buffer(Channel.UNLIMITED)
            .flowOn(Dispatchers.IO)
    }

    suspend fun openRoomList(
        userId: String,
        spaceId: String
    ): MatrixSpaceRoomListSession {
        val normalizedSpaceId = spaceId.trim().takeIf(String::isNotEmpty)
            ?: error("Space id is empty")
        return withFfiResourceHandoff<SdkSpaceRoomListSession>(
            release = SdkSpaceRoomListSession::close
        ) { own ->
            mutex.withLock {
                val service = activeSession
                    ?.takeIf { it.userId == userId }
                    ?.service
                    ?: error("Spaces are not active for this Matrix session")
                val list = service.spaceRoomList(normalizedSpaceId)
                val session = try {
                    SdkSpaceRoomListSession(
                        list = list,
                        onClosed = { closed ->
                            synchronized(childSessions) {
                                childSessions.remove(closed)
                            }
                        }
                    )
                } catch (error: Throwable) {
                    runCatching { list.destroy() }
                    throw error
                }
                own(session)
                session.start()
                synchronized(childSessions) {
                    childSessions += session
                }
                session
            }
        }
    }

    /** Loads fresh metadata for one preview without expanding hierarchy list mapping work. */
    suspend fun loadJoinContext(
        userId: String,
        roomId: String,
        fallbackRoom: MatrixSpaceRoom
    ): MatrixSpaceJoinContext = withContext(Dispatchers.IO) {
        val normalizedRoomId = roomId.trim().takeIf(String::isNotEmpty)
            ?: error("Room id is empty")
        require(fallbackRoom.roomId == normalizedRoomId) {
            "Fallback room does not match the requested room"
        }

        // Pending invitations are part of the client's local room list, but Spaces hierarchy APIs
        // are allowed to omit them (notably for an invited top-level Space). Membership from the
        // local Room is authoritative for accepting an invite and also avoids making preview
        // availability depend on hierarchy pagination. Keep SpaceService below for non-joined
        // children because it carries the exact restricted-room allow rules.
        matrixClientService.loadLocalSpaceJoinContext(
            userId = userId,
            roomId = normalizedRoomId,
            fallbackRoom = fallbackRoom
        )?.let { return@withContext it }

        val sdkRoom = mutex.withLock {
            val service = activeSession
                ?.takeIf { it.userId == userId }
                ?.service
                ?: error("Spaces are not active for this Matrix session")
            service.getSpaceRoom(normalizedRoomId)
                ?: error("Space room is not available")
        }
        val restrictedRules = when (val rule = sdkRoom.joinRule) {
            is JoinRule.Restricted -> rule.rules
            is JoinRule.KnockRestricted -> rule.rules
            else -> null
        }
        MatrixSpaceJoinContext(
            room = sdkRoom.toMatrixSpaceRoom(),
            canJoinRestrictedDirectly = restrictedRules?.restrictedRoomIds()?.let {
                matrixClientService.isJoinedToAnyRoom(userId, it)
            },
            hasUnsupportedRestrictedAllowRules = restrictedRules
                ?.any { it !is AllowRule.RoomMembership } == true
        )
    }

    private suspend fun requireActiveService(userId: String): SpaceService {
        return mutex.withLock {
            activeSession
                ?.takeIf { it.userId == userId }
                ?.service
                ?: error("Spaces are not active for this Matrix session")
        }
    }

    private fun closeLocked() {
        val sessions = synchronized(childSessions) {
            childSessions.toList().also { childSessions.clear() }
        }
        sessions.forEach(SdkSpaceRoomListSession::close)
        activeSession?.service?.destroy()
        activeSession = null
    }
}

private fun List<AllowRule>.restrictedRoomIds(): List<String> {
    return mapNotNull { rule ->
        (rule as? AllowRule.RoomMembership)?.roomId?.takeIf(String::isNotBlank)
    }.distinct()
}

private class SdkSpaceRoomListSession(
    private val list: SpaceRoomList,
    private val onClosed: (SdkSpaceRoomListSession) -> Unit
) : MatrixSpaceRoomListSession {
    private val closed = AtomicBoolean(false)
    private val updateLock = Any()
    private val initialPaginationState = list.paginationState()
    private val _snapshots = MutableStateFlow(
        MatrixSpaceRemoteSnapshot(
            space = list.space()?.toMatrixSpaceRoom(),
            isPaginating = initialPaginationState is SpaceRoomListPaginationState.Loading,
            endReached = (initialPaginationState as? SpaceRoomListPaginationState.Idle)
                ?.endReached == true
        )
    )
    override val snapshots: StateFlow<MatrixSpaceRemoteSnapshot> = _snapshots.asStateFlow()

    private lateinit var roomHandle: TaskHandle
    private lateinit var paginationHandle: TaskHandle
    private lateinit var spaceHandle: TaskHandle

    suspend fun start() {
        roomHandle = list.subscribeToRoomUpdate(
            object : SpaceRoomListEntriesListener {
                override fun onUpdate(rooms: List<SpaceListUpdate>) {
                    synchronized(updateLock) {
                        val current = _snapshots.value
                        _snapshots.value = current.copy(
                            rooms = applyMatrixSpaceListUpdates(
                                current = current.rooms,
                                updates = rooms.mapSdkUpdates()
                            ),
                            isKnown = true
                        )
                    }
                }
            }
        )
        paginationHandle = list.subscribeToPaginationStateUpdates(
            object : SpaceRoomListPaginationStateListener {
                override fun onUpdate(paginationState: SpaceRoomListPaginationState) {
                    synchronized(updateLock) {
                        val current = _snapshots.value
                        _snapshots.value = when (paginationState) {
                            is SpaceRoomListPaginationState.Idle -> current.copy(
                                isPaginating = false,
                                endReached = paginationState.endReached
                            )
                            SpaceRoomListPaginationState.Loading -> current.copy(
                                isPaginating = true
                            )
                        }
                    }
                }
            }
        )
        spaceHandle = list.subscribeToSpaceUpdates(
            object : SpaceRoomListSpaceListener {
                override fun onUpdate(space: SpaceRoom?) {
                    synchronized(updateLock) {
                        _snapshots.value = _snapshots.value.copy(
                            space = space?.toMatrixSpaceRoom()
                        )
                    }
                }
            }
        )
    }

    override suspend fun paginate() {
        if (!closed.get()) {
            withContext(Dispatchers.IO) {
                list.paginate()
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (::roomHandle.isInitialized) roomHandle.cancelAndDestroy()
        if (::paginationHandle.isInitialized) paginationHandle.cancelAndDestroy()
        if (::spaceHandle.isInitialized) spaceHandle.cancelAndDestroy()
        list.destroy()
        onClosed(this)
    }
}

private fun TaskHandle.cancelAndDestroy() {
    runCatching { cancel() }
    runCatching { destroy() }
}

private fun List<SpaceListUpdate>.mapSdkUpdates(): List<MatrixSpaceListUpdate> {
    return map { update ->
        when (update) {
            is SpaceListUpdate.Append -> MatrixSpaceListUpdate.Append(
                update.values.map(SpaceRoom::toMatrixSpaceRoom)
            )
            SpaceListUpdate.Clear -> MatrixSpaceListUpdate.Clear
            is SpaceListUpdate.PushFront ->
                MatrixSpaceListUpdate.PushFront(update.value.toMatrixSpaceRoom())
            is SpaceListUpdate.PushBack ->
                MatrixSpaceListUpdate.PushBack(update.value.toMatrixSpaceRoom())
            SpaceListUpdate.PopFront -> MatrixSpaceListUpdate.PopFront
            SpaceListUpdate.PopBack -> MatrixSpaceListUpdate.PopBack
            is SpaceListUpdate.Insert -> MatrixSpaceListUpdate.Insert(
                index = update.index.toInt(),
                value = update.value.toMatrixSpaceRoom()
            )
            is SpaceListUpdate.Set -> MatrixSpaceListUpdate.Set(
                index = update.index.toInt(),
                value = update.value.toMatrixSpaceRoom()
            )
            is SpaceListUpdate.Remove -> MatrixSpaceListUpdate.Remove(update.index.toInt())
            is SpaceListUpdate.Truncate ->
                MatrixSpaceListUpdate.Truncate(update.length.toInt())
            is SpaceListUpdate.Reset -> MatrixSpaceListUpdate.Reset(
                update.values.map(SpaceRoom::toMatrixSpaceRoom)
            )
        }
    }
}

internal fun SpaceRoom.toMatrixSpaceRoom(): MatrixSpaceRoom {
    return MatrixSpaceRoom(
        roomId = roomId,
        displayName = displayName.ifBlank { canonicalAlias ?: roomId },
        avatarUrl = avatarUrl,
        topic = topic,
        kind = when (roomType) {
            RoomType.Space -> MatrixSpaceRoomKind.SPACE
            RoomType.Room -> MatrixSpaceRoomKind.ROOM
            is RoomType.Custom -> MatrixSpaceRoomKind.ROOM
        },
        membership = when (state) {
            Membership.INVITED -> MatrixSpaceMembership.INVITED
            Membership.JOINED -> MatrixSpaceMembership.JOINED
            Membership.LEFT -> MatrixSpaceMembership.LEFT
            Membership.KNOCKED -> MatrixSpaceMembership.KNOCKED
            Membership.BANNED -> MatrixSpaceMembership.BANNED
            null -> MatrixSpaceMembership.UNKNOWN
        },
        joinedMemberCount = numJoinedMembers
            .coerceAtMost(Long.MAX_VALUE.toULong())
            .toLong(),
        childrenCount = childrenCount
            .coerceAtMost(Long.MAX_VALUE.toULong())
            .toLong(),
        canonicalAlias = canonicalAlias,
        joinRule = when (joinRule) {
            JoinRule.Public -> MatrixSpaceJoinRule.PUBLIC
            JoinRule.Invite -> MatrixSpaceJoinRule.INVITE
            JoinRule.Knock -> MatrixSpaceJoinRule.KNOCK
            is JoinRule.Restricted -> MatrixSpaceJoinRule.RESTRICTED
            is JoinRule.KnockRestricted -> MatrixSpaceJoinRule.KNOCK_RESTRICTED
            JoinRule.Private -> MatrixSpaceJoinRule.PRIVATE
            is JoinRule.Custom -> MatrixSpaceJoinRule.CUSTOM
            null -> MatrixSpaceJoinRule.UNKNOWN
        },
        worldReadable = worldReadable,
        guestCanJoin = guestCanJoin,
        isDirect = isDirect,
        isDm = isDm,
        via = via
    )
}
