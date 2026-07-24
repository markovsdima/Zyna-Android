package com.zyna.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zyna.app.data.matrix.MatrixRoomAccess
import com.zyna.app.data.matrix.MatrixRoomCapabilities
import com.zyna.app.data.matrix.MatrixRoomCreatorSemantics
import com.zyna.app.data.matrix.MatrixRoomDetails
import com.zyna.app.data.matrix.MatrixRoomEncryption
import com.zyna.app.data.matrix.MatrixRoomHistoryVisibility
import com.zyna.app.data.matrix.MatrixRoomKind
import com.zyna.app.data.matrix.MatrixRoomSummary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomDetailsCacheTest {
    private lateinit var database: ZynaDatabase
    private lateinit var repository: LocalCacheRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, ZynaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LocalCacheRepository(database, context)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun cachedDetailsAreExposedInRoomFlowAndSurviveSummaryRefresh() = runBlocking {
        val details = roomDetails(displayName = "Cached group")
        repository.cacheRoomDetails(USER_ID, details)

        assertEquals(details, repository.observeRoomDetails(USER_ID, ROOM_ID).first { it != null })
        assertEquals(
            details,
            repository.observeRooms(USER_ID).first { it.isNotEmpty() }.single().roomDetails
        )

        repository.cacheRoomsSnapshot(
            userId = USER_ID,
            rooms = listOf(
                MatrixRoomSummary(
                    id = ROOM_ID,
                    displayName = "Updated summary",
                    avatarUrl = details.avatarUrl,
                    unreadCount = 4
                )
            )
        )

        val refreshed = repository.observeRooms(USER_ID).first { rooms ->
            rooms.singleOrNull()?.unreadCount == 4L
        }.single()
        assertEquals("Updated summary", refreshed.displayName)
        assertEquals(details.copy(displayName = "Updated summary"), refreshed.roomDetails)
    }

    @Test
    fun detailsWriteDoesNotOverwriteSummaryOwnedFields() = runBlocking {
        val summary = MatrixRoomSummary(
            id = ROOM_ID,
            displayName = "Summary name",
            avatarUrl = "mxc://example.org/summary-avatar",
            directUserId = DIRECT_USER_ID
        )
        repository.cacheRoomSummary(USER_ID, summary)

        val narrowerDetails = roomDetails(displayName = "RoomInfo name").copy(
            avatarUrl = null,
            directUserId = null,
            kind = MatrixRoomKind.GROUP
        )
        repository.cacheRoomDetails(USER_ID, narrowerDetails)

        val cached = repository.observeRooms(USER_ID).first { it.isNotEmpty() }.single()
        assertEquals(summary.displayName, cached.displayName)
        assertEquals(summary.avatarUrl, cached.avatarUrl)
        assertEquals(summary.directUserId, cached.directUserId)
        assertEquals(
            narrowerDetails.copy(
                displayName = summary.displayName,
                avatarUrl = summary.avatarUrl,
                directUserId = summary.directUserId,
                kind = MatrixRoomKind.DIRECT
            ),
            cached.roomDetails
        )
    }

    @Test
    fun unknownCapabilityDoesNotOverwriteAKnownCachedPermission() = runBlocking {
        val knownDetails = roomDetails(displayName = "Cached group")
        repository.cacheRoomDetails(USER_ID, knownDetails)

        val updatedDetails = knownDetails.copy(
            topic = "Updated topic",
            capabilities = MatrixRoomCapabilities()
        )
        repository.cacheRoomDetails(USER_ID, updatedDetails)

        assertEquals(
            updatedDetails.copy(capabilities = knownDetails.capabilities),
            repository.observeRoomDetails(USER_ID, ROOM_ID).first { it != null }
        )
    }

    @Test
    fun unknownProtocolMetadataDoesNotOverwriteKnownRoomVersion() = runBlocking {
        val knownDetails = roomDetails(displayName = "Cached group")
        repository.cacheRoomDetails(USER_ID, knownDetails)

        repository.cacheRoomDetails(
            USER_ID,
            knownDetails.copy(
                topic = "Updated topic",
                roomVersion = null,
                creatorSemantics = MatrixRoomCreatorSemantics.UNKNOWN
            )
        )

        assertEquals(
            knownDetails.copy(topic = "Updated topic"),
            repository.observeRoomDetails(USER_ID, ROOM_ID).first { it != null }
        )
    }

    private fun roomDetails(displayName: String): MatrixRoomDetails {
        return MatrixRoomDetails(
            roomId = ROOM_ID,
            displayName = displayName,
            avatarUrl = "mxc://example.org/avatar",
            directUserId = null,
            kind = MatrixRoomKind.GROUP,
            topic = "A cached topic",
            joinedMemberCount = 7,
            encryption = MatrixRoomEncryption.ENCRYPTED,
            access = MatrixRoomAccess.PRIVATE,
            historyVisibility = MatrixRoomHistoryVisibility.INVITED,
            pinnedEventCount = 2,
            canonicalAlias = "#cached:example.org",
            roomVersion = "12",
            creatorSemantics = MatrixRoomCreatorSemantics.PRIVILEGED,
            capabilities = MatrixRoomCapabilities(
                canInviteMembers = true,
                canChangeName = false,
                canChangeAvatar = true
            )
        )
    }

    private companion object {
        const val USER_ID = "@alice:example.org"
        const val ROOM_ID = "!room:example.org"
        const val DIRECT_USER_ID = "@bob:example.org"
    }
}
