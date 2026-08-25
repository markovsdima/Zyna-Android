package com.zyna.app.ui.contacts

import com.zyna.app.data.matrix.MatrixContact
import com.zyna.app.data.matrix.MatrixRoomSummary
import com.zyna.app.data.matrix.MatrixUserProfile
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ALICE = "@alice:example.org"
private const val ALINA = "@alina:example.org"
private const val BOB = "@bob:example.org"

class ContactsStoreTest {
    @Test
    fun contactsForCombinesMatchingDirectRoomsWithServerResults() {
        val state = ContactsState(
            searchQuery = "ali",
            searchResults = listOf(
                profile(ALICE, "Remote Alice", "mxc://example.org/alice"),
                profile(ALINA, "Alina", null)
            )
        )

        assertEquals(
            listOf(
                MatrixContact(
                    userId = ALICE,
                    displayName = "Alice",
                    avatarUrl = "mxc://example.org/alice",
                    roomId = "!alice:example.org"
                ),
                MatrixContact(
                    userId = ALINA,
                    displayName = "Alina",
                    avatarUrl = null,
                    roomId = null
                )
            ),
            state.contactsFor(
                listOf(
                    room("!bob:example.org", "Bob", BOB),
                    room("!alice:example.org", "Alice", ALICE)
                )
            )
        )
    }

    @Test
    fun shortQueryFiltersDirectContactsWithoutStartingServerSearch() = runBlocking {
        val fixture = ContactsStoreFixture(coroutineContext)
        try {
            fixture.store.setSearchQuery("b")

            assertEquals("b", fixture.store.state.value.searchQuery)
            assertFalse(fixture.store.state.value.isSearching)
            assertEquals(
                listOf(MatrixContact(BOB, "Bob", null, "!bob:example.org")),
                fixture.store.state.value.contactsFor(
                    listOf(
                        room("!alice:example.org", "Alice", ALICE),
                        room("!bob:example.org", "Bob", BOB)
                    )
                )
            )
            yield()
            assertEquals(emptyList<String>(), fixture.searchTerms)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun serverSearchPublishesResultsAfterDebounce() = runBlocking {
        val fixture = ContactsStoreFixture(coroutineContext)
        val debounceGate = CompletableDeferred<Unit>()
        fixture.delayBehavior = { debounceGate.await() }
        fixture.searchBehavior = { listOf(profile(ALICE, "Alice", null)) }
        try {
            fixture.store.setSearchQuery("alice")

            assertTrue(fixture.store.state.value.isSearching)
            awaitCondition { fixture.requestedDelays.isNotEmpty() }
            assertEquals(listOf(250L), fixture.requestedDelays)
            assertEquals(emptyList<String>(), fixture.searchTerms)

            debounceGate.complete(Unit)
            awaitCondition { !fixture.store.state.value.isSearching }

            assertEquals(listOf("alice"), fixture.searchTerms)
            assertEquals(ALICE, fixture.store.state.value.searchResults.single().userId)
            assertNull(fixture.store.state.value.searchErrorMessage)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun changingQueryRejectsLateCompletionFromCancelledSearch() = runBlocking {
        val fixture = ContactsStoreFixture(coroutineContext)
        val firstSearchStarted = CompletableDeferred<Unit>()
        val firstSearchGate = CompletableDeferred<Unit>()
        fixture.searchBehavior = { query ->
            if (query == "alice") {
                firstSearchStarted.complete(Unit)
                try {
                    firstSearchGate.await()
                    listOf(profile(ALICE, "Unexpected Alice", null))
                } catch (_: CancellationException) {
                    listOf(profile(ALICE, "Stale Alice", null))
                }
            } else {
                listOf(profile(BOB, "Bob", null))
            }
        }
        try {
            fixture.store.setSearchQuery("alice")
            firstSearchStarted.await()
            fixture.store.setSearchQuery("bob")
            awaitCondition { !fixture.store.state.value.isSearching }
            firstSearchGate.complete(Unit)
            yield()

            assertEquals("bob", fixture.store.state.value.searchQuery)
            assertEquals(BOB, fixture.store.state.value.searchResults.single().userId)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun failureIsReportedAndClearResetsState() = runBlocking {
        val fixture = ContactsStoreFixture(coroutineContext)
        fixture.searchBehavior = { error("search failed") }
        try {
            fixture.store.setSearchQuery("alice")
            awaitCondition { fixture.store.state.value.searchErrorMessage == "search failed" }

            assertEquals("Failed to search contacts", fixture.warnings.single().first)
            fixture.store.clear()
            assertEquals(ContactsState(), fixture.store.state.value)
        } finally {
            fixture.close()
        }
    }
}

private class ContactsStoreFixture(parentContext: CoroutineContext) {
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(parentContext + scopeJob)

    val searchTerms = mutableListOf<String>()
    val requestedDelays = mutableListOf<Long>()
    val warnings = mutableListOf<Pair<String, Throwable>>()
    var searchBehavior: suspend (String) -> List<MatrixUserProfile> = { emptyList() }
    var delayBehavior: suspend (Long) -> Unit = {}

    val store = ContactsStore(
        scope = scope,
        driver = ContactsDriver(
            searchUsers = { searchTerm, _ ->
                searchTerms += searchTerm
                searchBehavior(searchTerm)
            },
            delayMillis = { durationMillis ->
                requestedDelays += durationMillis
                delayBehavior(durationMillis)
            }
        ),
        onWarning = { message, error -> warnings += message to error }
    )

    suspend fun close() {
        scopeJob.cancelAndJoin()
    }
}

private fun room(
    roomId: String,
    displayName: String,
    directUserId: String
): MatrixRoomSummary {
    return MatrixRoomSummary(
        id = roomId,
        displayName = displayName,
        avatarUrl = null,
        directUserId = directUserId
    )
}

private fun profile(
    userId: String,
    displayName: String,
    avatarUrl: String?
): MatrixUserProfile {
    return MatrixUserProfile(
        userId = userId,
        displayName = displayName,
        avatarUrl = avatarUrl
    )
}

private suspend fun awaitCondition(condition: () -> Boolean) {
    withTimeout(1_000L) {
        while (!condition()) {
            yield()
        }
    }
}
