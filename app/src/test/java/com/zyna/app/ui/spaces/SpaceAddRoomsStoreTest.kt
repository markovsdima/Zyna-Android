package com.zyna.app.ui.spaces

import com.zyna.app.data.matrix.MatrixRoomSummary
import com.zyna.app.data.matrix.MatrixSpaceMembership
import com.zyna.app.data.matrix.MatrixSpaceRoom
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ADD_USER = "@alice:example.org"
private const val ADD_SPACE = "!space:example.org"

class SpaceAddRoomsStoreTest {
    @Test
    fun candidatesAreJoinedGroupChatsOutsideTheCurrentSpace() = runBlocking {
        val fixture = SpaceAddRoomsFixture(coroutineContext)
        val joined = room("joined")
        fixture.source.value = SpaceAddRoomsSource(
            rooms = listOf(
                joined,
                room("direct", directUserId = "@bob:example.org"),
                room("space", isSpace = true),
                room("invite", membership = MatrixSpaceMembership.INVITED),
                room("existing")
            ),
            childRoomIds = setOf("existing"),
            canManage = true
        )
        try {
            fixture.store.activate(fixture.target)
            awaitSpaceCondition { fixture.store.state.value.canManage }

            assertEquals(listOf(joined), fixture.store.state.value.availableRooms)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun searchAndSelectionSurviveRoomListUpdates() = runBlocking {
        val fixture = SpaceAddRoomsFixture(coroutineContext)
        val alpha = room("alpha", "Alpha")
        val beta = room("beta", "Beta")
        fixture.source.value = source(alpha, beta)
        try {
            fixture.store.activate(fixture.target)
            awaitSpaceCondition { fixture.store.state.value.availableRooms.size == 2 }
            fixture.store.setSearchQuery("alp")
            fixture.store.toggleRoom(alpha.id)

            fixture.source.value = source(beta, alpha.copy(lastMessageText = "Updated"))
            awaitSpaceCondition {
                fixture.store.state.value.availableRooms.firstOrNull()?.id == beta.id
            }

            val state = fixture.store.state.value
            assertEquals("alp", state.searchQuery)
            assertEquals(setOf(alpha.id), state.selectedRoomIds)
            assertEquals(listOf(alpha.id), state.visibleRooms.map(MatrixRoomSummary::id))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun successfulBatchIsConfirmedRefreshedAndCloses() = runBlocking {
        val fixture = SpaceAddRoomsFixture(coroutineContext)
        val alpha = room("alpha")
        val beta = room("beta")
        fixture.source.value = source(alpha, beta)
        try {
            fixture.store.activate(fixture.target)
            awaitSpaceCondition { fixture.store.state.value.canManage }
            fixture.store.toggleRoom(alpha.id)
            fixture.store.toggleRoom(beta.id)
            fixture.store.save()
            awaitSpaceCondition { fixture.addedCallbacks.isNotEmpty() }

            assertEquals(setOf(alpha.id, beta.id), fixture.addCalls.toSet())
            assertEquals(setOf(alpha.id, beta.id), fixture.confirmedIds.toSet())
            assertEquals(1, fixture.refreshCount)
            assertEquals(listOf(emptySet<String>()), fixture.refreshRequests)
            assertEquals(setOf(alpha.id, beta.id), fixture.addedCallbacks.single())
            assertFalse(fixture.store.state.value.isSaving)
            assertEquals(null, fixture.store.state.value.error)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun partialBatchHidesSuccessAndKeepsOnlyFailureSelected() = runBlocking {
        val fixture = SpaceAddRoomsFixture(coroutineContext)
        val added = room("added")
        val failed = room("failed")
        fixture.source.value = source(added, failed)
        fixture.addBehavior = { roomId ->
            if (roomId == failed.id) error("server rejected addition")
        }
        fixture.expectedWarningCount = 1
        try {
            fixture.store.activate(fixture.target)
            awaitSpaceCondition { fixture.store.state.value.canManage }
            fixture.store.toggleRoom(added.id)
            fixture.store.toggleRoom(failed.id)
            fixture.store.save()
            awaitSpaceCondition {
                fixture.store.state.value.error == SpaceAddRoomsError.PARTIAL_ADD
            }

            val state = fixture.store.state.value
            assertEquals(listOf(failed.id), state.availableRooms.map(MatrixRoomSummary::id))
            assertEquals(setOf(failed.id), state.selectedRoomIds)
            assertEquals(listOf(added.id), fixture.confirmedIds)
            assertEquals(listOf(setOf(failed.id)), fixture.refreshRequests)
            assertTrue(fixture.addedCallbacks.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun refreshedGraphReconcilesAdditionReportedAsFailed() = runBlocking {
        val fixture = SpaceAddRoomsFixture(coroutineContext)
        val room = room("ambiguous")
        fixture.source.value = source(room)
        fixture.addBehavior = { error("inverse relationship update failed") }
        fixture.refreshBehavior = { fixture.currentChildIds += room.id }
        fixture.expectedWarningCount = 1
        try {
            fixture.store.activate(fixture.target)
            awaitSpaceCondition { fixture.store.state.value.canManage }
            fixture.store.toggleRoom(room.id)
            fixture.store.save()
            awaitSpaceCondition { fixture.addedCallbacks.isNotEmpty() }

            assertEquals(setOf(room.id), fixture.addedCallbacks.single())
            assertEquals(listOf(room.id), fixture.confirmedIds)
            assertEquals(listOf(setOf(room.id)), fixture.refreshRequests)
            assertEquals(null, fixture.store.state.value.error)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun freshPermissionCheckPreventsAnyAddition() = runBlocking {
        val fixture = SpaceAddRoomsFixture(coroutineContext)
        val room = room("room")
        fixture.source.value = source(room)
        fixture.loadCanManage = { false }
        try {
            fixture.store.activate(fixture.target)
            awaitSpaceCondition { fixture.store.state.value.canManage }
            fixture.store.toggleRoom(room.id)
            fixture.store.save()
            awaitSpaceCondition {
                fixture.store.state.value.error == SpaceAddRoomsError.PERMISSION_CHANGED
            }

            assertTrue(fixture.addCalls.isEmpty())
            assertFalse(fixture.store.state.value.canManage)
            assertFalse(fixture.store.state.value.isSaving)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun livePermissionRevocationDisablesSelection() = runBlocking {
        val fixture = SpaceAddRoomsFixture(coroutineContext)
        val room = room("room")
        fixture.source.value = source(room)
        try {
            fixture.store.activate(fixture.target)
            awaitSpaceCondition { fixture.store.state.value.canManage }
            fixture.store.toggleRoom(room.id)

            fixture.source.value = source(room).copy(canManage = false)
            awaitSpaceCondition { !fixture.store.state.value.canManage }

            assertEquals(SpaceAddRoomsError.PERMISSION_CHANGED, fixture.store.state.value.error)
            fixture.store.toggleRoom(room.id)
            assertEquals(setOf(room.id), fixture.store.state.value.selectedRoomIds)
        } finally {
            fixture.close()
        }
    }
}

private class SpaceAddRoomsFixture(parentContext: CoroutineContext) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(parentContext + job)

    val target = SpaceAddRoomsTarget(
        userId = ADD_USER,
        spaceId = ADD_SPACE,
        parentSpaceId = null,
        displayName = "Story"
    )
    val source = MutableStateFlow(SpaceAddRoomsSource(emptyList(), emptySet(), false))
    val addCalls = mutableListOf<String>()
    val confirmedIds = mutableListOf<String>()
    val currentChildIds = linkedSetOf<String>()
    val refreshRequests = mutableListOf<Set<String>>()
    val addedCallbacks = mutableListOf<Set<String>>()
    val warnings = mutableListOf<Pair<String, Throwable>>()
    var refreshCount = 0
    var expectedWarningCount = 0
    var loadCanManage: suspend () -> Boolean = { source.value.canManage }
    var addBehavior: suspend (String) -> Unit = {}
    var refreshBehavior: suspend () -> Unit = {}

    val store = SpaceAddRoomsStore(
        scope = scope,
        driver = SpaceAddRoomsDriver(
            observeSource = { source },
            loadCanManage = { loadCanManage() },
            addChild = { _, _, roomId ->
                addCalls += roomId
                addBehavior(roomId)
            },
            confirmAdded = { _, rooms ->
                confirmedIds += rooms.map(MatrixSpaceRoom::roomId)
            },
            refreshChildren = { _, roomIdsToFind ->
                refreshCount += 1
                refreshRequests += roomIdsToFind
                refreshBehavior()
                currentChildIds
            }
        ),
        onAdded = { _, roomIds -> addedCallbacks += roomIds },
        onWarning = { message, error -> warnings += message to error }
    )

    suspend fun close() {
        try {
            assertEquals("Unexpected warnings: $warnings", expectedWarningCount, warnings.size)
        } finally {
            job.cancelAndJoin()
        }
    }
}

private fun source(vararg rooms: MatrixRoomSummary): SpaceAddRoomsSource {
    return SpaceAddRoomsSource(rooms.toList(), emptySet(), canManage = true)
}

private fun room(
    id: String,
    displayName: String = id,
    directUserId: String? = null,
    isSpace: Boolean = false,
    membership: MatrixSpaceMembership = MatrixSpaceMembership.JOINED
): MatrixRoomSummary {
    return MatrixRoomSummary(
        id = id,
        displayName = displayName,
        avatarUrl = null,
        directUserId = directUserId,
        isSpace = isSpace,
        membership = membership
    )
}
