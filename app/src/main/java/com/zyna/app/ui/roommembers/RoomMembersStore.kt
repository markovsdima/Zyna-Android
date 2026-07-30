package com.zyna.app.ui.roommembers

import androidx.annotation.MainThread
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixRoomMember
import com.zyna.app.data.matrix.MatrixRoomMemberMembership
import com.zyna.app.data.matrix.MatrixRoomMemberModerationAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RoomMembersTarget(
    val userId: String,
    val roomId: String
)

data class RoomMembersState(
    val target: RoomMembersTarget? = null,
    val searchQuery: String = "",
    val invitedMembers: List<MatrixRoomMember> = emptyList(),
    val joinedMembers: List<MatrixRoomMember> = emptyList(),
    val bannedMembers: List<MatrixRoomMember> = emptyList(),
    val totalMemberCount: Int = 0,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null
)

internal enum class RoomMembersSource {
    CACHE,
    SERVER
}

internal class RoomMembersDriver(
    val loadMembers: suspend (roomId: String, source: RoomMembersSource) -> List<MatrixRoomMember>
)

/**
 * Owns the active room-members route and its two-phase SDK load.
 *
 * The SDK cache paints the first trustworthy snapshot without duplicating members in our Room
 * database. A server-backed pass then replaces it. The session user is part of the target so a
 * late result from an old account cannot update an identically named room after session change.
 * Search text is published immediately; debounced filtering runs on the worker dispatcher and
 * uses both generation and member-snapshot identity to reject stale results.
 */
internal class RoomMembersStore(
    private val scope: CoroutineScope,
    private val driver: RoomMembersDriver,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    maxCachedRooms: Int = ROOM_MEMBERS_MAX_CACHED_ROOMS,
    maxCachedMemberCount: Int = ROOM_MEMBERS_MAX_CACHED_MEMBER_COUNT,
    private val searchDebounceMillis: Long = ROOM_MEMBERS_SEARCH_DEBOUNCE_MILLIS,
    private val onWarning: (String, Throwable) -> Unit = { _, _ -> }
) {
    private val _state = MutableStateFlow(RoomMembersState())
    val state: StateFlow<RoomMembersState> = _state.asStateFlow()

    private var generation = 0L
    private var expectedJoinedCount: Long? = null
    private var allMembers: List<MatrixRoomMember> = emptyList()
    private var allInvitedMembers: List<MatrixRoomMember> = emptyList()
    private var allJoinedMembers: List<MatrixRoomMember> = emptyList()
    private var allBannedMembers: List<MatrixRoomMember> = emptyList()
    private var hasPublishedSnapshot = false
    private var loadJob: Job? = null
    private var filterGeneration = 0L
    private var filterJob: Job? = null
    private val snapshotCache = RoomMembersSnapshotCache(
        maxRoomCount = maxCachedRooms,
        maxTotalMemberCount = maxCachedMemberCount
    )
    private var prefetchGeneration = 0L
    private var prefetchTarget: RoomMembersTarget? = null
    private var prefetchJob: Job? = null

    @MainThread
    fun activate(target: RoomMembersTarget, expectedJoinedCount: Long?) {
        val normalizedTarget = target.normalizedOrNull() ?: run {
            deactivate()
            return
        }
        if (_state.value.target == normalizedTarget) {
            this.expectedJoinedCount = expectedJoinedCount?.coerceAtLeast(0)
            return
        }
        cancelPrefetch()
        this.expectedJoinedCount = expectedJoinedCount?.coerceAtLeast(0)
        val retainedSnapshot = snapshotCache[normalizedTarget]
            ?.takeIf { snapshot ->
                snapshot.isAuthoritative || cachedSnapshotIsTrustworthy(
                    members = snapshot.prepared.members,
                    expectedJoinedCount = this.expectedJoinedCount
                )
            }
        setPreparedMembers(retainedSnapshot?.prepared ?: PreparedRoomMembers.Empty)
        hasPublishedSnapshot = retainedSnapshot != null
        startLoad(
            target = normalizedTarget,
            retainSnapshot = retainedSnapshot != null,
            skipSdkCache = retainedSnapshot != null
        )
    }

    @MainThread
    fun retry() {
        val target = _state.value.target ?: return
        startLoad(
            target = target,
            retainSnapshot = hasPublishedSnapshot,
            skipSdkCache = hasPublishedSnapshot
        )
    }

    /** Warms the session snapshot while room details are visible without owning UI state. */
    @MainThread
    fun prefetch(target: RoomMembersTarget, expectedJoinedCount: Long?) {
        val normalizedTarget = target.normalizedOrNull() ?: return
        val normalizedExpectedCount = expectedJoinedCount?.coerceAtLeast(0)
        val retainedSnapshot = snapshotCache[normalizedTarget]
        if (
            retainedSnapshot != null &&
            (
                retainedSnapshot.isAuthoritative ||
                    cachedSnapshotIsTrustworthy(
                        members = retainedSnapshot.prepared.members,
                        expectedJoinedCount = normalizedExpectedCount
                    )
                )
        ) {
            return
        }
        if (prefetchTarget == normalizedTarget && prefetchJob?.isActive == true) {
            return
        }

        cancelPrefetch()
        prefetchGeneration += 1
        val requestGeneration = prefetchGeneration
        prefetchTarget = normalizedTarget
        val nextJob = scope.launch {
            try {
                val prepared = loadPreparedMembers(
                    normalizedTarget.roomId,
                    RoomMembersSource.CACHE
                )
                if (
                    prefetchGeneration == requestGeneration &&
                    prefetchTarget == normalizedTarget &&
                    cachedSnapshotIsTrustworthy(prepared.members, normalizedExpectedCount)
                ) {
                    snapshotCache.put(
                        normalizedTarget,
                        RoomMembersSnapshot(prepared = prepared, isAuthoritative = false)
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (
                    prefetchGeneration == requestGeneration &&
                    prefetchTarget == normalizedTarget
                ) {
                    onWarning("Failed to prefetch cached room members", error)
                }
            }
        }
        prefetchJob = nextJob
        nextJob.invokeOnCompletion {
            if (prefetchJob === nextJob) {
                prefetchJob = null
            }
        }
    }

    @MainThread
    fun setSearchQuery(query: String) {
        if (_state.value.searchQuery == query) {
            return
        }
        _state.value = _state.value.copy(searchQuery = query)
        if (!hasPublishedSnapshot) {
            return
        }
        if (query.trim().isEmpty()) {
            cancelFilter()
            publishVisibleMembers(
                query = query,
                visibleMembers = VisibleRoomMembers(
                    invited = allInvitedMembers,
                    joined = allJoinedMembers,
                    banned = allBannedMembers
                )
            )
        } else {
            scheduleFilter(query = query, debounceMillis = searchDebounceMillis)
        }
    }

    /**
     * Applies a server-confirmed command immediately while the authoritative reload is in flight.
     *
     * This prevents a removed member from flashing in the previous section after navigation pops
     * back. The normal server pass still runs and remains the final reconciliation source.
     */
    @MainThread
    fun applyConfirmedModeration(
        roomId: String,
        userId: String,
        action: MatrixRoomMemberModerationAction
    ) {
        val target = _state.value.target
            ?.takeIf { it.roomId == roomId }
            ?: return
        val memberIndex = allMembers.indexOfFirst { member -> member.userId == userId }
        if (memberIndex < 0) return
        val updatedMembers = when (action) {
            MatrixRoomMemberModerationAction.BAN -> {
                allMembers.toMutableList().apply {
                    this[memberIndex] = this[memberIndex].copy(
                        membership = MatrixRoomMemberMembership.BANNED
                    )
                }
            }
            MatrixRoomMemberModerationAction.KICK,
            MatrixRoomMemberModerationAction.UNBAN -> {
                allMembers.toMutableList().apply { removeAt(memberIndex) }
            }
        }
        applyMembers(
            target = target,
            prepared = prepareMembers(updatedMembers),
            isAuthoritative = true,
            isRefreshing = _state.value.isRefreshing
        )
    }

    @MainThread
    fun deactivate() {
        if (_state.value.target == null && loadJob == null) {
            return
        }
        generation += 1
        loadJob?.cancel()
        loadJob = null
        cancelFilter()
        expectedJoinedCount = null
        setPreparedMembers(PreparedRoomMembers.Empty)
        hasPublishedSnapshot = false
        _state.value = RoomMembersState()
    }

    @MainThread
    fun clearSession() {
        deactivate()
        cancelPrefetch()
        snapshotCache.clear()
    }

    @MainThread
    fun stopPrefetch() {
        cancelPrefetch()
    }

    private fun startLoad(
        target: RoomMembersTarget,
        retainSnapshot: Boolean,
        skipSdkCache: Boolean
    ) {
        generation += 1
        val requestGeneration = generation
        loadJob?.cancel()
        cancelFilter()
        val previousState = _state.value
        if (!retainSnapshot) {
            setPreparedMembers(PreparedRoomMembers.Empty)
            hasPublishedSnapshot = false
        }
        val retainsCurrentTarget = retainSnapshot && previousState.target == target
        val retainedQuery = previousState.searchQuery.takeIf { retainsCurrentTarget }.orEmpty()
        _state.value = RoomMembersState(
            target = target,
            searchQuery = retainedQuery,
            invitedMembers = if (retainSnapshot) {
                previousState.invitedMembers.takeIf { retainsCurrentTarget }
                    ?: allInvitedMembers
            } else {
                emptyList()
            },
            joinedMembers = if (retainSnapshot) {
                previousState.joinedMembers.takeIf { retainsCurrentTarget }
                    ?: allJoinedMembers
            } else {
                emptyList()
            },
            bannedMembers = if (retainSnapshot) {
                previousState.bannedMembers.takeIf { retainsCurrentTarget }
                    ?: allBannedMembers
            } else {
                emptyList()
            },
            totalMemberCount = if (retainSnapshot) allMembers.size else 0,
            isLoading = !retainSnapshot,
            isRefreshing = retainSnapshot
        )

        val nextJob = scope.launch {
            if (!skipSdkCache) {
                try {
                    val cachedMembers = loadPreparedMembers(
                        target.roomId,
                        RoomMembersSource.CACHE
                    )
                    if (!isCurrent(target, requestGeneration)) {
                        return@launch
                    }
                    if (
                        cachedSnapshotIsTrustworthy(
                            cachedMembers.members,
                            expectedJoinedCount
                        )
                    ) {
                        applyMembers(
                            target = target,
                            prepared = cachedMembers,
                            isAuthoritative = false,
                            isRefreshing = true
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    if (isCurrent(target, requestGeneration)) {
                        onWarning("Failed to load cached room members", error)
                    }
                }
            }

            if (!isCurrent(target, requestGeneration)) {
                return@launch
            }

            try {
                val syncedMembers = loadPreparedMembers(
                    target.roomId,
                    RoomMembersSource.SERVER
                )
                if (!isCurrent(target, requestGeneration)) {
                    return@launch
                }
                applyMembers(
                    target = target,
                    prepared = syncedMembers,
                    isAuthoritative = true,
                    isRefreshing = false
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrent(target, requestGeneration)) {
                    return@launch
                }
                onWarning("Failed to load current room members", error)
                _state.value = _state.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    errorMessage = if (!hasPublishedSnapshot) {
                        error.message ?: error.javaClass.simpleName
                    } else {
                        null
                    }
                )
            }
        }
        loadJob = nextJob
        nextJob.invokeOnCompletion {
            if (loadJob === nextJob) {
                loadJob = null
            }
        }
    }

    private fun applyMembers(
        target: RoomMembersTarget,
        prepared: PreparedRoomMembers,
        isAuthoritative: Boolean,
        isRefreshing: Boolean
    ) {
        cancelFilter()
        setPreparedMembers(prepared)
        hasPublishedSnapshot = true
        snapshotCache.put(
            target,
            RoomMembersSnapshot(
                prepared = prepared,
                isAuthoritative = isAuthoritative
            )
        )
        val query = _state.value.searchQuery
        _state.value = _state.value.copy(
            totalMemberCount = prepared.members.size,
            isRefreshing = isRefreshing,
            errorMessage = null
        )
        if (query.trim().isEmpty()) {
            publishVisibleMembers(
                query = query,
                visibleMembers = VisibleRoomMembers(
                    invited = prepared.invited,
                    joined = prepared.joined,
                    banned = prepared.banned
                )
            )
        } else {
            scheduleFilter(query = query, debounceMillis = 0)
        }
    }

    private suspend fun loadPreparedMembers(
        roomId: String,
        source: RoomMembersSource
    ): PreparedRoomMembers {
        val members = driver.loadMembers(roomId, source)
        return withContext(workerDispatcher) {
            prepareMembers(members)
        }
    }

    private fun publishVisibleMembers(
        query: String,
        visibleMembers: VisibleRoomMembers
    ) {
        _state.value = _state.value.copy(
            searchQuery = query,
            invitedMembers = visibleMembers.invited,
            joinedMembers = visibleMembers.joined,
            bannedMembers = visibleMembers.banned,
            totalMemberCount = allMembers.size,
            isLoading = false
        )
    }

    private fun scheduleFilter(query: String, debounceMillis: Long) {
        cancelFilter()
        filterGeneration += 1
        val requestGeneration = filterGeneration
        val target = _state.value.target ?: return
        val membersSnapshot = allMembers
        val nextJob = scope.launch {
            if (debounceMillis > 0) {
                delay(debounceMillis)
            }
            val visibleMembers = withContext(workerDispatcher) {
                filterMembers(membersSnapshot, query)
            }
            if (
                filterGeneration == requestGeneration &&
                _state.value.target == target &&
                _state.value.searchQuery == query &&
                allMembers === membersSnapshot
            ) {
                publishVisibleMembers(query, visibleMembers)
            }
        }
        filterJob = nextJob
        nextJob.invokeOnCompletion {
            if (filterJob === nextJob) {
                filterJob = null
            }
        }
    }

    private fun setPreparedMembers(prepared: PreparedRoomMembers) {
        allMembers = prepared.members
        allInvitedMembers = prepared.invited
        allJoinedMembers = prepared.joined
        allBannedMembers = prepared.banned
    }

    private fun cachedSnapshotIsTrustworthy(
        members: List<MatrixRoomMember>,
        expectedJoinedCount: Long?
    ): Boolean {
        val expected = expectedJoinedCount ?: return members.any {
            it.membership == MatrixRoomMemberMembership.JOINED
        }
        if (expected == 0L) {
            return true
        }
        val minimumJoinedCount = expected / 2 + expected % 2
        val cachedJoinedCount = members.count {
            it.membership == MatrixRoomMemberMembership.JOINED
        }.toLong()
        return cachedJoinedCount >= minimumJoinedCount
    }

    private fun isCurrent(target: RoomMembersTarget, requestGeneration: Long): Boolean {
        return generation == requestGeneration && _state.value.target == target
    }

    private fun cancelPrefetch() {
        prefetchGeneration += 1
        prefetchTarget = null
        prefetchJob?.cancel()
        prefetchJob = null
    }

    private fun cancelFilter() {
        filterGeneration += 1
        filterJob?.cancel()
        filterJob = null
    }

    private fun RoomMembersTarget.normalizedOrNull(): RoomMembersTarget? {
        val normalizedUserId = userId.trim().takeIf { it.isNotEmpty() } ?: return null
        val normalizedRoomId = roomId.trim().takeIf { it.isNotEmpty() } ?: return null
        return RoomMembersTarget(
            userId = normalizedUserId,
            roomId = normalizedRoomId
        )
    }
}

private data class RoomMembersSnapshot(
    val prepared: PreparedRoomMembers,
    val isAuthoritative: Boolean
)

private data class PreparedRoomMembers(
    val members: List<MatrixRoomMember>,
    val invited: List<MatrixRoomMember>,
    val joined: List<MatrixRoomMember>,
    val banned: List<MatrixRoomMember>
) {
    companion object {
        val Empty = PreparedRoomMembers(
            members = emptyList(),
            invited = emptyList(),
            joined = emptyList(),
            banned = emptyList()
        )
    }
}

private data class VisibleRoomMembers(
    val invited: List<MatrixRoomMember>,
    val joined: List<MatrixRoomMember>,
    val banned: List<MatrixRoomMember>
)

private class RoomMembersSnapshotCache(
    private val maxRoomCount: Int,
    private val maxTotalMemberCount: Int
) {
    private val snapshots = LinkedHashMap<RoomMembersTarget, RoomMembersSnapshot>(
        maxRoomCount.coerceAtLeast(1),
        0.75f,
        true
    )
    private var totalMemberCount = 0

    operator fun get(target: RoomMembersTarget): RoomMembersSnapshot? = snapshots[target]

    fun put(target: RoomMembersTarget, snapshot: RoomMembersSnapshot) {
        snapshots.remove(target)?.let { previous ->
            totalMemberCount -= previous.prepared.members.size
        }
        if (
            maxRoomCount <= 0 ||
            maxTotalMemberCount <= 0 ||
            snapshot.prepared.members.size > maxTotalMemberCount
        ) {
            return
        }
        snapshots[target] = snapshot
        totalMemberCount += snapshot.prepared.members.size
        trimToBounds()
    }

    fun clear() {
        snapshots.clear()
        totalMemberCount = 0
    }

    private fun trimToBounds() {
        val iterator = snapshots.entries.iterator()
        while (
            iterator.hasNext() &&
            (snapshots.size > maxRoomCount || totalMemberCount > maxTotalMemberCount)
        ) {
            totalMemberCount -= iterator.next().value.prepared.members.size
            iterator.remove()
        }
    }
}

private object RoomMemberComparator : Comparator<MatrixRoomMember> {
    override fun compare(first: MatrixRoomMember, second: MatrixRoomMember): Int {
        val powerComparison = second.powerLevel.compareTo(first.powerLevel)
        if (powerComparison != 0) {
            return powerComparison
        }
        val nameComparison = first.displayNameOrUserId.compareTo(
            second.displayNameOrUserId,
            ignoreCase = true
        )
        return if (nameComparison != 0) {
            nameComparison
        } else {
            first.userId.compareTo(second.userId, ignoreCase = true)
        }
    }
}

private fun prepareMembers(members: List<MatrixRoomMember>): PreparedRoomMembers {
    val sorted = members.sortedWith(RoomMemberComparator)
    val invited = ArrayList<MatrixRoomMember>()
    val joined = ArrayList<MatrixRoomMember>()
    val banned = ArrayList<MatrixRoomMember>()
    sorted.forEach { member ->
        when (member.membership) {
            MatrixRoomMemberMembership.INVITED -> invited += member
            MatrixRoomMemberMembership.JOINED -> joined += member
            MatrixRoomMemberMembership.BANNED -> banned += member
            MatrixRoomMemberMembership.LEFT -> Unit
        }
    }
    return PreparedRoomMembers(
        members = sorted,
        invited = invited,
        joined = joined,
        banned = banned
    )
}

private suspend fun filterMembers(
    members: List<MatrixRoomMember>,
    query: String
): VisibleRoomMembers {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) {
        val prepared = prepareMembers(members)
        return VisibleRoomMembers(prepared.invited, prepared.joined, prepared.banned)
    }

    val coroutineContext = currentCoroutineContext()
    val invited = ArrayList<MatrixRoomMember>()
    val joined = ArrayList<MatrixRoomMember>()
    val banned = ArrayList<MatrixRoomMember>()
    members.forEachIndexed { index, member ->
        if (index and FILTER_CANCELLATION_CHECK_MASK == 0) {
            coroutineContext.ensureActive()
        }
        val matches = member.userId.contains(normalizedQuery, ignoreCase = true) ||
            member.displayName?.contains(normalizedQuery, ignoreCase = true) == true
        if (matches) {
            when (member.membership) {
                MatrixRoomMemberMembership.INVITED -> invited += member
                MatrixRoomMemberMembership.JOINED -> joined += member
                MatrixRoomMemberMembership.BANNED -> banned += member
                MatrixRoomMemberMembership.LEFT -> Unit
            }
        }
    }
    return VisibleRoomMembers(invited = invited, joined = joined, banned = banned)
}

internal fun createRoomMembersStore(
    scope: CoroutineScope,
    matrixClientService: MatrixClientService,
    onWarning: (String, Throwable) -> Unit
): RoomMembersStore {
    return RoomMembersStore(
        scope = scope,
        driver = RoomMembersDriver { roomId, source ->
            matrixClientService.loadRoomMembers(
                roomId = roomId,
                useCachedSnapshot = source == RoomMembersSource.CACHE
            )
        },
        onWarning = onWarning
    )
}

private const val ROOM_MEMBERS_MAX_CACHED_ROOMS = 8
private const val ROOM_MEMBERS_MAX_CACHED_MEMBER_COUNT = 20_000
private const val ROOM_MEMBERS_SEARCH_DEBOUNCE_MILLIS = 100L
private const val FILTER_CANCELLATION_CHECK_MASK = 127
