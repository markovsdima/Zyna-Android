package com.zyna.app.data.local

import androidx.room.withTransaction
import com.zyna.app.data.matrix.MatrixSpaceJoinRule
import com.zyna.app.data.matrix.MatrixSpaceListSnapshot
import com.zyna.app.data.matrix.MatrixSpaceMembership
import com.zyna.app.data.matrix.MatrixSpaceRoom
import com.zyna.app.data.matrix.MatrixSpaceRoomKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.json.JSONArray

class SpaceCacheRepository(
    private val database: ZynaDatabase
) {
    private val dao = database.cachedSpaceDao()
    private val memoryCache = SpaceSnapshotMemoryCache(MAX_MEMORY_SNAPSHOTS)

    /**
     * Warms the bounded session cache before a Space route can be opened.
     * Room remains the durable source; this mirror only removes the asynchronous
     * database gap from a route's first render.
     */
    suspend fun warmSession(userId: String) {
        val warmedSnapshots = database.withTransaction {
            val snapshots = dao.recentSnapshots(userId, MAX_MEMORY_SNAPSHOTS)
            if (snapshots.isEmpty()) return@withTransaction emptyList()
            val entriesByList = dao.entriesForLists(
                userId = userId,
                listIds = snapshots.map(CachedSpaceListSnapshotEntity::listId)
            ).groupBy(CachedSpaceListEntryEntity::listId)
            snapshots.map { snapshot ->
                snapshot.listId to snapshot.toSnapshot(
                    entriesByList[snapshot.listId].orEmpty()
                )
            }
        }
        warmedSnapshots.asReversed().forEach { (listId, snapshot) ->
            memoryCache.put(
                SpaceSnapshotKey(userId = userId, listId = listId),
                snapshot
            )
        }
    }

    fun peekTopLevelSpaces(userId: String): MatrixSpaceListSnapshot? {
        return memoryCache.get(SpaceSnapshotKey(userId, TOP_LEVEL_LIST_ID))
    }

    fun peekSpaceChildren(
        userId: String,
        spaceId: String
    ): MatrixSpaceListSnapshot? {
        return memoryCache.get(SpaceSnapshotKey(userId, childListId(spaceId)))
    }

    fun observeTopLevelSpaces(userId: String): Flow<MatrixSpaceListSnapshot> {
        return observeList(userId = userId, listId = TOP_LEVEL_LIST_ID)
    }

    fun observeSpaceChildren(
        userId: String,
        spaceId: String
    ): Flow<MatrixSpaceListSnapshot> {
        return observeList(userId = userId, listId = childListId(spaceId))
    }

    suspend fun cacheTopLevelSpaces(
        userId: String,
        snapshot: MatrixSpaceListSnapshot
    ) {
        cacheList(userId = userId, listId = TOP_LEVEL_LIST_ID, snapshot = snapshot)
    }

    suspend fun cacheSpaceChildren(
        userId: String,
        spaceId: String,
        snapshot: MatrixSpaceListSnapshot
    ) {
        cacheList(userId = userId, listId = childListId(spaceId), snapshot = snapshot)
    }

    suspend fun clearAll() {
        database.withTransaction {
            dao.clearAllEntries()
            dao.clearAllSnapshots()
        }
        // Room may still have a pre-transaction value buffered for an active
        // observer. Clear last so that value cannot revive deleted memory.
        memoryCache.clear()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeList(
        userId: String,
        listId: String
    ): Flow<MatrixSpaceListSnapshot> {
        val key = SpaceSnapshotKey(userId = userId, listId = listId)
        val disk = dao.observeList(userId, listId).map { rows ->
            val snapshot = rows.firstOrNull()?.snapshot
            if (snapshot == null) {
                MatrixSpaceListSnapshot()
            } else {
                snapshot.toSnapshot(
                    rows.mapNotNull(CachedSpaceListRow::entry)
                )
            }
        }
        return memoryCache.observe(key)
            .flatMapLatest { memorySnapshot ->
                if (memorySnapshot != null) {
                    flowOf(memorySnapshot)
                } else {
                    disk.map { diskSnapshot ->
                        memoryCache.putIfAbsent(key, diskSnapshot)
                        memoryCache.get(key) ?: diskSnapshot
                    }
                }
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)
    }

    private suspend fun cacheList(
        userId: String,
        listId: String,
        snapshot: MatrixSpaceListSnapshot
    ) {
        val updatedAtMillis = snapshot.updatedAtMillis ?: System.currentTimeMillis()
        val storedSnapshot = snapshot.copy(updatedAtMillis = updatedAtMillis)
        database.withTransaction {
            dao.upsertSnapshot(
                storedSnapshot.toEntity(
                    userId = userId,
                    listId = listId,
                    updatedAtMillis = updatedAtMillis
                )
            )
            dao.deleteEntries(userId = userId, listId = listId)
            val entries = storedSnapshot.rooms.mapIndexed { index, room ->
                room.toEntryEntity(
                    userId = userId,
                    listId = listId,
                    position = index
                )
            }
            if (entries.isNotEmpty()) {
                dao.upsertEntries(entries)
            }
        }
        memoryCache.put(
            SpaceSnapshotKey(userId = userId, listId = listId),
            storedSnapshot
        )
    }

    private fun childListId(spaceId: String): String = "space:$spaceId"

    private companion object {
        const val TOP_LEVEL_LIST_ID = "zyna:top-level-spaces"
        const val MAX_MEMORY_SNAPSHOTS = 32
    }
}

internal data class SpaceSnapshotKey(
    val userId: String,
    val listId: String
)

internal class SpaceSnapshotMemoryCache(
    private val maximumSize: Int
) {
    init {
        require(maximumSize > 0)
    }

    private val snapshots = LinkedHashMap<SpaceSnapshotKey, MatrixSpaceListSnapshot>(
        maximumSize,
        0.75f,
        true
    )
    private val values = MutableStateFlow<Map<SpaceSnapshotKey, MatrixSpaceListSnapshot>>(
        emptyMap()
    )
    private val blockedDiskSeeds = mutableSetOf<SpaceSnapshotKey>()

    @Synchronized
    fun get(key: SpaceSnapshotKey): MatrixSpaceListSnapshot? = snapshots[key]

    fun observe(key: SpaceSnapshotKey): Flow<MatrixSpaceListSnapshot?> {
        return values.map { snapshots -> snapshots[key] }.distinctUntilChanged()
    }

    @Synchronized
    fun put(key: SpaceSnapshotKey, snapshot: MatrixSpaceListSnapshot) {
        if (!snapshot.isKnown) return
        blockedDiskSeeds.remove(key)
        snapshots[key] = snapshot
        while (snapshots.size > maximumSize) {
            val eldestKey = snapshots.entries.firstOrNull()?.key ?: break
            snapshots.remove(eldestKey)
        }
        values.value = snapshots.toMap()
    }

    @Synchronized
    fun putIfAbsent(key: SpaceSnapshotKey, snapshot: MatrixSpaceListSnapshot) {
        if (!snapshot.isKnown || key in snapshots || key in blockedDiskSeeds) return
        put(key, snapshot)
    }

    @Synchronized
    fun clear() {
        blockedDiskSeeds += snapshots.keys
        snapshots.clear()
        values.value = emptyMap()
    }
}

private fun CachedSpaceListSnapshotEntity.toSnapshot(
    entries: List<CachedSpaceListEntryEntity>
): MatrixSpaceListSnapshot {
    return MatrixSpaceListSnapshot(
        space = toSpaceRoomOrNull(),
        rooms = entries.map(CachedSpaceListEntryEntity::toSpaceRoom),
        isKnown = isKnown,
        endReached = endReached,
        updatedAtMillis = updatedAtMillis
    )
}

private fun MatrixSpaceListSnapshot.toEntity(
    userId: String,
    listId: String,
    updatedAtMillis: Long
): CachedSpaceListSnapshotEntity {
    val header = space
    return CachedSpaceListSnapshotEntity(
        userId = userId,
        listId = listId,
        spaceRoomId = header?.roomId,
        spaceDisplayName = header?.displayName,
        spaceAvatarUrl = header?.avatarUrl,
        spaceTopic = header?.topic,
        spaceMembership = header?.membership?.name,
        spaceJoinedMemberCount = header?.joinedMemberCount,
        spaceChildrenCount = header?.childrenCount,
        spaceCanonicalAlias = header?.canonicalAlias,
        spaceJoinRule = header?.joinRule?.name,
        spaceWorldReadable = header?.worldReadable,
        spaceGuestCanJoin = header?.guestCanJoin,
        spaceIsDirect = header?.isDirect,
        spaceIsDm = header?.isDm,
        spaceViaJson = header?.via?.toJsonArray(),
        isKnown = isKnown,
        endReached = endReached,
        updatedAtMillis = updatedAtMillis
    )
}

private fun MatrixSpaceRoom.toEntryEntity(
    userId: String,
    listId: String,
    position: Int
): CachedSpaceListEntryEntity {
    return CachedSpaceListEntryEntity(
        userId = userId,
        listId = listId,
        roomId = roomId,
        position = position,
        displayName = displayName,
        avatarUrl = avatarUrl,
        topic = topic,
        kind = kind.name,
        membership = membership.name,
        joinedMemberCount = joinedMemberCount,
        childrenCount = childrenCount,
        canonicalAlias = canonicalAlias,
        joinRule = joinRule.name,
        worldReadable = worldReadable,
        guestCanJoin = guestCanJoin,
        isDirect = isDirect,
        isDm = isDm,
        viaJson = via.toJsonArray()
    )
}

private fun CachedSpaceListSnapshotEntity.toSpaceRoomOrNull(): MatrixSpaceRoom? {
    val roomId = spaceRoomId ?: return null
    return MatrixSpaceRoom(
        roomId = roomId,
        displayName = spaceDisplayName.orEmpty().ifBlank { roomId },
        avatarUrl = spaceAvatarUrl,
        topic = spaceTopic,
        kind = MatrixSpaceRoomKind.SPACE,
        membership = spaceMembership.toEnumOrDefault(MatrixSpaceMembership.UNKNOWN),
        joinedMemberCount = spaceJoinedMemberCount ?: 0L,
        childrenCount = spaceChildrenCount ?: 0L,
        canonicalAlias = spaceCanonicalAlias,
        joinRule = spaceJoinRule.toEnumOrDefault(MatrixSpaceJoinRule.UNKNOWN),
        worldReadable = spaceWorldReadable,
        guestCanJoin = spaceGuestCanJoin ?: false,
        isDirect = spaceIsDirect,
        isDm = spaceIsDm,
        via = spaceViaJson.toStringList()
    )
}

private fun CachedSpaceListEntryEntity.toSpaceRoom(): MatrixSpaceRoom {
    return MatrixSpaceRoom(
        roomId = roomId,
        displayName = displayName,
        avatarUrl = avatarUrl,
        topic = topic,
        kind = kind.toEnumOrDefault(MatrixSpaceRoomKind.ROOM),
        membership = membership.toEnumOrDefault(MatrixSpaceMembership.UNKNOWN),
        joinedMemberCount = joinedMemberCount,
        childrenCount = childrenCount,
        canonicalAlias = canonicalAlias,
        joinRule = joinRule.toEnumOrDefault(MatrixSpaceJoinRule.UNKNOWN),
        worldReadable = worldReadable,
        guestCanJoin = guestCanJoin,
        isDirect = isDirect,
        isDm = isDm,
        via = viaJson.toStringList()
    )
}

private inline fun <reified T : Enum<T>> String?.toEnumOrDefault(default: T): T {
    return this?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: default
}

private fun List<String>.toJsonArray(): String {
    return JSONArray().also { array -> forEach(array::put) }.toString()
}

private fun String?.toStringList(): List<String> {
    if (this.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(this)
        List(array.length()) { index -> array.optString(index) }
            .filter(String::isNotBlank)
    }.getOrDefault(emptyList())
}
