package com.zyna.app.data.local

import com.zyna.app.data.matrix.MatrixSpaceListSnapshot
import com.zyna.app.data.matrix.spaceRoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpaceSnapshotMemoryCacheTest {
    @Test
    fun successfulReplacementDoesNotDependOnWallClockDirection() {
        val cache = SpaceSnapshotMemoryCache(maximumSize = 2)
        val key = SpaceSnapshotKey("@alice:example.org", "space:a")
        val first = snapshot("first", updatedAtMillis = 20L)
        val replacementAfterClockRollback = snapshot("replacement", updatedAtMillis = 10L)

        cache.put(key, first)
        cache.put(key, replacementAfterClockRollback)

        assertEquals(replacementAfterClockRollback, cache.get(key))
    }

    @Test
    fun leastRecentlyUsedSnapshotIsEvicted() {
        val cache = SpaceSnapshotMemoryCache(maximumSize = 2)
        val first = SpaceSnapshotKey("@alice:example.org", "space:first")
        val second = SpaceSnapshotKey("@alice:example.org", "space:second")
        val third = SpaceSnapshotKey("@alice:example.org", "space:third")

        cache.put(first, snapshot("first", 1L))
        cache.put(second, snapshot("second", 2L))
        cache.get(first)
        cache.put(third, snapshot("third", 3L))

        assertEquals("first", cache.get(first)?.rooms?.single()?.roomId)
        assertNull(cache.get(second))
        assertEquals("third", cache.get(third)?.rooms?.single()?.roomId)
    }

    @Test
    fun unknownSnapshotCannotReplaceKnownMemory() {
        val cache = SpaceSnapshotMemoryCache(maximumSize = 1)
        val key = SpaceSnapshotKey("@alice:example.org", "space:a")
        val known = snapshot("known", 1L)

        cache.put(key, known)
        cache.put(key, MatrixSpaceListSnapshot())

        assertEquals(known, cache.get(key))
    }

    @Test
    fun clearCannotBeUndoneByABufferedDiskSeed() {
        val cache = SpaceSnapshotMemoryCache(maximumSize = 1)
        val key = SpaceSnapshotKey("@alice:example.org", "space:a")
        val deleted = snapshot("deleted", 1L)
        val replacement = snapshot("replacement", 2L)
        cache.put(key, deleted)

        cache.clear()
        cache.putIfAbsent(key, deleted)

        assertNull(cache.get(key))

        cache.put(key, replacement)

        assertEquals(replacement, cache.get(key))
    }

    private fun snapshot(roomId: String, updatedAtMillis: Long): MatrixSpaceListSnapshot {
        return MatrixSpaceListSnapshot(
            rooms = listOf(spaceRoom(roomId)),
            isKnown = true,
            endReached = true,
            updatedAtMillis = updatedAtMillis
        )
    }
}
