package com.zyna.app.data.matrix

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomList
import org.matrix.rustcomponents.sdk.RoomListDynamicEntriesController
import org.matrix.rustcomponents.sdk.RoomListEntriesDynamicFilterKind
import org.matrix.rustcomponents.sdk.RoomListEntriesListener
import org.matrix.rustcomponents.sdk.RoomListEntriesUpdate
import org.matrix.rustcomponents.sdk.RoomListEntriesWithDynamicAdaptersResult
import org.matrix.rustcomponents.sdk.RoomListLoadingState
import org.matrix.rustcomponents.sdk.RoomListLoadingStateListener
import org.matrix.rustcomponents.sdk.RoomListLoadingStateResult
import org.matrix.rustcomponents.sdk.RoomListService

internal data class MatrixRoomListSnapshot(
    val rooms: List<MatrixRoomSummary> = emptyList(),
    val excludedRoomIds: Set<String> = emptySet(),
    val updatedRoomIds: Set<String> = emptySet(),
    val isKnown: Boolean = false,
    val maximumNumberOfRooms: Int? = null,
    val loadedEntryCount: Int = rooms.size,
    val revision: Long = 0
) {
    // These projections are built on the session's IO processor. Consumers can therefore answer
    // viewport and initial-fill questions without rebuilding O(N) structures on the main thread.
    val roomIndexById: Map<String, Int> = rooms
        .mapIndexed { index, room -> room.id to index }
        .toMap()
    val renderableChatCount: Int = rooms.count { room -> !room.isSpace }

    // The SDK maximum is for the unfiltered list, so completion must use positional entries,
    // including left-room placeholders, rather than the number of renderable rooms.
    val endReached: Boolean
        get() = isKnown && maximumNumberOfRooms?.let { loadedEntryCount >= it } == true
}

internal data class MatrixRoomListEntry(
    val id: String,
    val room: MatrixRoomSummary?
)

internal sealed interface MatrixRoomListUpdate {
    data class Append(val values: List<MatrixRoomListEntry>) : MatrixRoomListUpdate
    data object Clear : MatrixRoomListUpdate
    data class PushFront(val value: MatrixRoomListEntry) : MatrixRoomListUpdate
    data class PushBack(val value: MatrixRoomListEntry) : MatrixRoomListUpdate
    data object PopFront : MatrixRoomListUpdate
    data object PopBack : MatrixRoomListUpdate
    data class Insert(val index: Int, val value: MatrixRoomListEntry) : MatrixRoomListUpdate
    data class Set(val index: Int, val value: MatrixRoomListEntry) : MatrixRoomListUpdate
    data class Remove(val index: Int) : MatrixRoomListUpdate
    data class Truncate(val length: Int) : MatrixRoomListUpdate
    data class Reset(val values: List<MatrixRoomListEntry>) : MatrixRoomListUpdate
}

internal fun applyMatrixRoomListUpdates(
    current: List<MatrixRoomListEntry>,
    updates: List<MatrixRoomListUpdate>
): List<MatrixRoomListEntry> {
    val result = current.toMutableList()
    updates.forEach { update ->
        when (update) {
            is MatrixRoomListUpdate.Append -> result.addAll(update.values)
            MatrixRoomListUpdate.Clear -> result.clear()
            is MatrixRoomListUpdate.PushFront -> result.add(0, update.value)
            is MatrixRoomListUpdate.PushBack -> result.add(update.value)
            MatrixRoomListUpdate.PopFront -> {
                check(result.isNotEmpty()) { "Cannot pop the front of an empty room list" }
                result.removeAt(0)
            }
            MatrixRoomListUpdate.PopBack -> {
                check(result.isNotEmpty()) { "Cannot pop the back of an empty room list" }
                result.removeAt(result.lastIndex)
            }
            is MatrixRoomListUpdate.Insert -> {
                require(update.index in 0..result.size) {
                    "Room-list insert index ${update.index} is outside 0..${result.size}"
                }
                result.add(update.index, update.value)
            }
            is MatrixRoomListUpdate.Set -> {
                require(update.index in result.indices) {
                    "Room-list set index ${update.index} is outside ${result.indices}"
                }
                result[update.index] = update.value
            }
            is MatrixRoomListUpdate.Remove -> {
                require(update.index in result.indices) {
                    "Room-list remove index ${update.index} is outside ${result.indices}"
                }
                result.removeAt(update.index)
            }
            is MatrixRoomListUpdate.Truncate -> {
                require(update.length in 0..result.size) {
                    "Room-list truncate length ${update.length} is outside 0..${result.size}"
                }
                result.subList(update.length, result.size).clear()
            }
            is MatrixRoomListUpdate.Reset -> {
                result.clear()
                result.addAll(update.values)
            }
        }
    }
    val uniqueIds = HashSet<String>(result.size)
    check(result.all { entry -> uniqueIds.add(entry.id) }) {
        "Matrix room-list diff produced duplicate stable IDs"
    }
    return result
}

internal interface MatrixRoomListSession {
    val snapshots: StateFlow<MatrixRoomListSnapshot>
    val failures: SharedFlow<Throwable>

    suspend fun loadMore()

    suspend fun subscribeToRooms(roomIds: List<String>)

    suspend fun acknowledgeCached(revision: Long)

    suspend fun close()
}

/**
 * Owns one ordered SDK room list and converts its FFI diff stream into immutable app models.
 * Room handles stay in an unlimited owned channel until the serial processor consumes them;
 * cancellation destroys every undelivered update instead of leaking its nested handles.
 */
internal class SdkMatrixRoomListSession(
    private val roomList: RoomList,
    private val roomListService: RoomListService,
    private val roomEntryMapper: suspend (Room) -> MatrixRoomListEntry,
    private val onClosed: (SdkMatrixRoomListSession) -> Unit
) : MatrixRoomListSession {
    private sealed interface Input {
        data class Updates(val values: List<RoomListEntriesUpdate>) : Input
        data class Loading(val value: RoomListLoadingState) : Input

        fun destroy() {
            if (this is Updates) values.forEach { update -> runCatching { update.destroy() } }
        }
    }

    private val closed = AtomicBoolean(false)
    private val desynchronized = AtomicBoolean(false)
    private val operationMutex = Mutex()
    private val projectionMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inputs = Channel<Input>(
        capacity = Channel.UNLIMITED,
        onUndeliveredElement = Input::destroy
    )
    private val _snapshots = MutableStateFlow(MatrixRoomListSnapshot())
    override val snapshots: StateFlow<MatrixRoomListSnapshot> = _snapshots.asStateFlow()
    private val _failures = MutableSharedFlow<Throwable>(replay = 1, extraBufferCapacity = 7)
    override val failures: SharedFlow<Throwable> = _failures.asSharedFlow()

    private var processorJob: Job? = null
    private var entriesResult: RoomListEntriesWithDynamicAdaptersResult? = null
    private var entriesController: RoomListDynamicEntriesController? = null
    private var loadingResult: RoomListLoadingStateResult? = null
    private var currentEntries: List<MatrixRoomListEntry> = emptyList()
    private var contentRevision = 0L
    private val changedAtRevisionByRoomId = mutableMapOf<String, Long>()

    suspend fun start() {
        check(processorJob == null) { "Room-list session is already started" }
        processorJob = scope.launch {
            for (input in inputs) {
                when (input) {
                    is Input.Loading -> publishLoadingState(input.value)
                    is Input.Updates -> processUpdates(input.values)
                }
            }
        }

        try {
            val openedEntries = roomList.entriesWithDynamicAdapters(
                pageSize = ROOM_LIST_PAGE_SIZE.toUInt(),
                listener = object : RoomListEntriesListener {
                    override fun onUpdate(roomEntriesUpdate: List<RoomListEntriesUpdate>) {
                        val input = Input.Updates(roomEntriesUpdate)
                        if (inputs.trySend(input).isFailure) input.destroy()
                    }
                }
            )
            entriesResult = openedEntries
            entriesController = openedEntries.controller().also { controller ->
                // Keep the dynamic head unfiltered so its size can be compared with the SDK's
                // unfiltered maximum. Left/banned entries remain as positional placeholders and
                // are removed from the immutable public projection by roomEntryMapper.
                controller.setFilter(RoomListEntriesDynamicFilterKind.All(emptyList()))
            }

            val openedLoading = roomList.loadingState(
                object : RoomListLoadingStateListener {
                    override fun onUpdate(state: RoomListLoadingState) {
                        inputs.trySend(Input.Loading(state))
                    }
                }
            )
            loadingResult = openedLoading
            inputs.trySend(Input.Loading(openedLoading.state))
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    override suspend fun loadMore() {
        operationMutex.withLock {
            if (closed.get()) return
            withContext(Dispatchers.IO) {
                repeat(ROOM_LIST_PAGES_PER_REQUEST) {
                    entriesController?.addOnePage()
                }
            }
        }
    }

    override suspend fun subscribeToRooms(roomIds: List<String>) {
        val distinctRoomIds = roomIds.distinct()
        operationMutex.withLock {
            if (closed.get()) return
            withContext(Dispatchers.IO) {
                roomListService.subscribeToRooms(distinctRoomIds)
            }
        }
    }

    override suspend fun acknowledgeCached(revision: Long) {
        projectionMutex.withLock {
            if (closed.get()) return
            changedAtRevisionByRoomId.entries.removeAll { (_, changedAtRevision) ->
                changedAtRevision <= revision
            }
            _snapshots.value = _snapshots.value.copy(
                updatedRoomIds = changedAtRevisionByRoomId.keys.toSet()
            )
        }
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        withContext(NonCancellable + Dispatchers.IO) {
            operationMutex.withLock {
                loadingResult?.let { result ->
                    runCatching { result.stateStream }.getOrNull()?.let { stream ->
                        runCatching { stream.cancel() }
                        runCatching { stream.destroy() }
                    }
                    runCatching { result.destroy() }
                }
                loadingResult = null
                entriesResult?.let { result ->
                    runCatching { result.entriesStream() }.getOrNull()?.let { stream ->
                        runCatching { stream.cancel() }
                        runCatching { stream.destroy() }
                    }
                }
                runCatching { entriesController?.destroy() }
                entriesController = null
                runCatching { entriesResult?.destroy() }
                entriesResult = null
                inputs.cancel()
                scope.cancel()
                runCatching { roomList.destroy() }
            }
        }
        onClosed(this)
    }

    private suspend fun publishLoadingState(state: RoomListLoadingState) {
        projectionMutex.withLock {
            if (closed.get()) return
            _snapshots.value = when (state) {
                RoomListLoadingState.NotLoaded -> _snapshots.value.copy(isKnown = false)
                is RoomListLoadingState.Loaded -> _snapshots.value.copy(
                    isKnown = true,
                    maximumNumberOfRooms = state.maximumNumberOfRooms
                        ?.coerceAtMost(Int.MAX_VALUE.toUInt())
                        ?.toInt()
                )
            }
        }
    }

    private suspend fun processUpdates(updates: List<RoomListEntriesUpdate>) {
        if (desynchronized.get()) {
            updates.forEach { update -> runCatching { update.destroy() } }
            return
        }
        try {
            val mappedUpdates = operationMutex.withLock {
                if (closed.get()) null else updates.map { update -> update.toMatrixUpdate() }
            } ?: return
            projectionMutex.withLock {
                if (closed.get()) return
                currentEntries = applyMatrixRoomListUpdates(
                    current = currentEntries,
                    updates = mappedUpdates
                )
                contentRevision += 1
                if (mappedUpdates.any { update ->
                        update is MatrixRoomListUpdate.Reset || update == MatrixRoomListUpdate.Clear
                    }
                ) {
                    changedAtRevisionByRoomId.clear()
                }
                mappedUpdates.changedRoomIds().forEach { roomId ->
                    changedAtRevisionByRoomId[roomId] = contentRevision
                }
                _snapshots.value = _snapshots.value.copy(
                    rooms = currentEntries.mapNotNull(MatrixRoomListEntry::room),
                    excludedRoomIds = currentEntries.asSequence()
                        .filter { entry -> entry.room == null }
                        .map(MatrixRoomListEntry::id)
                        .toSet(),
                    updatedRoomIds = changedAtRevisionByRoomId.keys.toSet(),
                    loadedEntryCount = currentEntries.size,
                    revision = contentRevision
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // Positional updates cannot be applied safely after a missed batch. Freeze this
            // projection immediately; RoomListStore will close it and open a fresh SDK adapter.
            desynchronized.set(true)
            projectionMutex.withLock {
                _snapshots.value = _snapshots.value.copy(isKnown = false)
            }
            _failures.tryEmit(error)
        } finally {
            updates.forEach { update -> runCatching { update.destroy() } }
        }
    }

    private suspend fun RoomListEntriesUpdate.toMatrixUpdate(): MatrixRoomListUpdate {
        return when (this) {
            is RoomListEntriesUpdate.Append -> MatrixRoomListUpdate.Append(
                values.map { room -> roomEntryMapper(room) }
            )
            RoomListEntriesUpdate.Clear -> MatrixRoomListUpdate.Clear
            is RoomListEntriesUpdate.PushFront ->
                MatrixRoomListUpdate.PushFront(roomEntryMapper(value))
            is RoomListEntriesUpdate.PushBack ->
                MatrixRoomListUpdate.PushBack(roomEntryMapper(value))
            RoomListEntriesUpdate.PopFront -> MatrixRoomListUpdate.PopFront
            RoomListEntriesUpdate.PopBack -> MatrixRoomListUpdate.PopBack
            is RoomListEntriesUpdate.Insert -> MatrixRoomListUpdate.Insert(
                index = index.toInt(),
                value = roomEntryMapper(value)
            )
            is RoomListEntriesUpdate.Set -> MatrixRoomListUpdate.Set(
                index = index.toInt(),
                value = roomEntryMapper(value)
            )
            is RoomListEntriesUpdate.Remove -> MatrixRoomListUpdate.Remove(index.toInt())
            is RoomListEntriesUpdate.Truncate -> MatrixRoomListUpdate.Truncate(length.toInt())
            is RoomListEntriesUpdate.Reset -> MatrixRoomListUpdate.Reset(
                values.map { room -> roomEntryMapper(room) }
            )
        }
    }

    private fun List<MatrixRoomListUpdate>.changedRoomIds(): Set<String> {
        return flatMapTo(mutableSetOf()) { update ->
            when (update) {
                is MatrixRoomListUpdate.Append -> update.values.map(MatrixRoomListEntry::id)
                is MatrixRoomListUpdate.PushFront -> listOf(update.value.id)
                is MatrixRoomListUpdate.PushBack -> listOf(update.value.id)
                is MatrixRoomListUpdate.Insert -> listOf(update.value.id)
                is MatrixRoomListUpdate.Set -> listOf(update.value.id)
                is MatrixRoomListUpdate.Reset -> update.values.map(MatrixRoomListEntry::id)
                MatrixRoomListUpdate.Clear,
                MatrixRoomListUpdate.PopFront,
                MatrixRoomListUpdate.PopBack,
                is MatrixRoomListUpdate.Remove,
                is MatrixRoomListUpdate.Truncate -> emptyList()
            }
        }
    }

    private companion object {
        const val ROOM_LIST_PAGE_SIZE = 40
        const val ROOM_LIST_PAGES_PER_REQUEST = 2
    }
}
