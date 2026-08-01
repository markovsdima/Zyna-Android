package com.zyna.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zyna.app.data.matrix.MatrixSpaceJoinRule
import com.zyna.app.data.matrix.MatrixSpaceListSnapshot
import com.zyna.app.data.matrix.MatrixSpaceMembership
import com.zyna.app.data.matrix.MatrixSpaceRoom
import com.zyna.app.data.matrix.MatrixSpaceRoomKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpaceCacheRepositoryTest {
    private lateinit var database: ZynaDatabase
    private lateinit var repository: SpaceCacheRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ZynaDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = SpaceCacheRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun knownEmptySnapshotSurvivesLeftJoinProjection() = runBlocking {
        val expected = MatrixSpaceListSnapshot(
            isKnown = true,
            endReached = true,
            updatedAtMillis = 1_000L
        )

        repository.cacheTopLevelSpaces(USER_ID, expected)

        assertEquals(
            expected,
            repository.observeTopLevelSpaces(USER_ID).first { snapshot -> snapshot.isKnown }
        )
    }

    @Test
    fun replacingSnapshotPreservesServerOrderAndRemovesOldEntries() = runBlocking {
        repository.cacheSpaceChildren(
            userId = USER_ID,
            spaceId = SPACE_ID,
            snapshot = snapshot(
                rooms = listOf(
                    spaceRoom("!second:example.org", "Second"),
                    spaceRoom("!first:example.org", "First")
                ),
                updatedAtMillis = 2_000L
            )
        )

        assertEquals(
            listOf("!second:example.org", "!first:example.org"),
            repository.observeSpaceChildren(USER_ID, SPACE_ID)
                .first { snapshot -> snapshot.isKnown }
                .rooms
                .map(MatrixSpaceRoom::roomId)
        )

        repository.cacheSpaceChildren(
            userId = USER_ID,
            spaceId = SPACE_ID,
            snapshot = snapshot(
                rooms = listOf(spaceRoom("!replacement:example.org", "Replacement")),
                updatedAtMillis = 3_000L
            )
        )

        assertEquals(
            listOf("!replacement:example.org"),
            repository.observeSpaceChildren(USER_ID, SPACE_ID)
                .first { current ->
                    current.rooms.singleOrNull()?.roomId == "!replacement:example.org"
                }
                .rooms
                .map(MatrixSpaceRoom::roomId)
        )
    }

    @Test
    fun warmSessionRestoresDurableSnapshotIntoFreshMemoryMirror() = runBlocking {
        val expected = snapshot(
            rooms = listOf(spaceRoom("!cached:example.org", "Cached")),
            updatedAtMillis = 4_000L
        )
        repository.cacheSpaceChildren(USER_ID, SPACE_ID, expected)
        val freshRepository = SpaceCacheRepository(database)

        assertNull(freshRepository.peekSpaceChildren(USER_ID, SPACE_ID))

        freshRepository.warmSession(USER_ID)

        assertEquals(expected, freshRepository.peekSpaceChildren(USER_ID, SPACE_ID))
    }

    @Test
    fun clearAllRemovesDurableAndMemorySnapshots() = runBlocking {
        repository.cacheTopLevelSpaces(
            USER_ID,
            snapshot(
                rooms = listOf(spaceRoom(SPACE_ID, "Story", MatrixSpaceRoomKind.SPACE)),
                updatedAtMillis = 5_000L
            )
        )

        repository.clearAll()

        assertNull(repository.peekTopLevelSpaces(USER_ID))
        assertFalse(
            repository.observeTopLevelSpaces(USER_ID)
                .first { snapshot -> !snapshot.isKnown }
                .isKnown
        )
    }

    private fun snapshot(
        rooms: List<MatrixSpaceRoom>,
        updatedAtMillis: Long
    ): MatrixSpaceListSnapshot {
        return MatrixSpaceListSnapshot(
            rooms = rooms,
            isKnown = true,
            endReached = true,
            updatedAtMillis = updatedAtMillis
        )
    }

    private fun spaceRoom(
        roomId: String,
        displayName: String,
        kind: MatrixSpaceRoomKind = MatrixSpaceRoomKind.ROOM
    ): MatrixSpaceRoom {
        return MatrixSpaceRoom(
            roomId = roomId,
            displayName = displayName,
            avatarUrl = null,
            topic = null,
            kind = kind,
            membership = MatrixSpaceMembership.JOINED,
            joinedMemberCount = 3,
            childrenCount = if (kind == MatrixSpaceRoomKind.SPACE) 2 else 0,
            canonicalAlias = null,
            joinRule = MatrixSpaceJoinRule.INVITE,
            worldReadable = false,
            guestCanJoin = false,
            isDirect = false,
            isDm = false,
            via = listOf("example.org")
        )
    }

    private companion object {
        const val USER_ID = "@alice:example.org"
        const val SPACE_ID = "!story:example.org"
    }
}
