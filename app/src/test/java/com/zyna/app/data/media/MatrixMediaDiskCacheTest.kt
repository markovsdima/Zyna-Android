package com.zyna.app.data.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MatrixMediaDiskCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun read_returnsPreviouslyWrittenBytes() {
        val cache = MatrixMediaDiskCache(
            directory = temporaryFolder.newFolder(),
            maxSizeBytes = 128
        )
        val bytes = byteArrayOf(1, 2, 3)

        cache.write("mxc://example/media", bytes)

        assertArrayEquals(bytes, cache.read("mxc://example/media"))
    }

    @Test
    fun write_evictsLeastRecentlyUsedEntriesWhenSizeLimitIsExceeded() {
        var now = 1_000L
        val cache = MatrixMediaDiskCache(
            directory = temporaryFolder.newFolder(),
            maxSizeBytes = 8,
            nowMillis = { now }
        )

        cache.write("old", byteArrayOf(1, 1, 1, 1))
        now = 2_000L
        cache.write("new", byteArrayOf(2, 2, 2, 2))
        now = 3_000L
        assertNotNull(cache.read("old"))
        now = 4_000L
        cache.write("third", byteArrayOf(3, 3, 3, 3))

        assertNotNull(cache.read("old"))
        assertNull(cache.read("new"))
        assertNotNull(cache.read("third"))
    }

    @Test
    fun write_skipsEntriesLargerThanTheCacheLimit() {
        val cache = MatrixMediaDiskCache(
            directory = temporaryFolder.newFolder(),
            maxSizeBytes = 2
        )

        cache.write("too-large", byteArrayOf(1, 2, 3))

        assertNull(cache.read("too-large"))
    }
}
