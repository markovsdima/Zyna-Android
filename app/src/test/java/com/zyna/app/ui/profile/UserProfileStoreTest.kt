package com.zyna.app.ui.profile

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
import org.junit.Assert.assertTrue
import org.junit.Test

private const val USER_A = "@alice:example.org"
private const val USER_B = "@bob:example.org"

class UserProfileStoreTest {
    @Test
    fun openPublishesSeedBeforeReplacingItWithLoadedProfile() = runBlocking {
        val fixture = UserProfileStoreFixture(coroutineContext)
        val loadGate = CompletableDeferred<Unit>()
        fixture.loadBehavior = { userId ->
            loadGate.await()
            profile(userId, displayName = "Loaded Alice")
        }
        try {
            fixture.store.open(
                userId = USER_A,
                seedDisplayName = "Seed Alice",
                seedAvatarUrl = "mxc://example.org/seed"
            )

            assertEquals(
                UserProfileState(
                    userId = USER_A,
                    displayName = "Seed Alice",
                    avatarUrl = "mxc://example.org/seed",
                    isLoading = true
                ),
                fixture.store.state.value
            )

            loadGate.complete(Unit)
            awaitCondition { fixture.store.state.value.displayName == "Loaded Alice" }

            assertFalse(fixture.store.state.value.isLoading)
            assertEquals(listOf(USER_A), fixture.loadedUserIds)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun refreshPreservesLoadedProfileAsSeedAndStartsAnotherLoad() = runBlocking {
        val fixture = UserProfileStoreFixture(coroutineContext)
        try {
            fixture.store.open(USER_A, "Seed", null)
            awaitCondition { !fixture.store.state.value.isLoading }
            fixture.loadBehavior = { userId ->
                profile(userId, displayName = "Refreshed")
            }

            fixture.store.refresh()
            assertTrue(fixture.store.state.value.isLoading)
            assertEquals("Alice", fixture.store.state.value.displayName)
            awaitCondition { fixture.store.state.value.displayName == "Refreshed" }

            assertEquals(listOf(USER_A, USER_A), fixture.loadedUserIds)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun openingAnotherUserRejectsLateCompletionFromPreviousRoute() = runBlocking {
        val fixture = UserProfileStoreFixture(coroutineContext)
        val firstLoadStarted = CompletableDeferred<Unit>()
        val firstLoadGate = CompletableDeferred<Unit>()
        fixture.loadBehavior = { userId ->
            if (userId == USER_A) {
                firstLoadStarted.complete(Unit)
                try {
                    firstLoadGate.await()
                    profile(USER_A, "Unexpected Alice")
                } catch (_: CancellationException) {
                    profile(USER_A, "Stale Alice")
                }
            } else {
                profile(USER_B, "Bob")
            }
        }
        try {
            fixture.store.open(USER_A, "Seed Alice", null)
            firstLoadStarted.await()
            fixture.store.open(USER_B, "Seed Bob", null)
            awaitCondition { fixture.store.state.value.displayName == "Bob" }
            firstLoadGate.complete(Unit)
            yield()

            assertEquals(USER_B, fixture.store.state.value.userId)
            assertEquals("Bob", fixture.store.state.value.displayName)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun failureKeepsSeedAndClearResetsState() = runBlocking {
        val fixture = UserProfileStoreFixture(coroutineContext)
        fixture.loadBehavior = { error("profile failed") }
        try {
            fixture.store.open(USER_A, "Seed Alice", "mxc://example.org/seed")
            awaitCondition { fixture.store.state.value.errorMessage == "profile failed" }

            assertEquals("Seed Alice", fixture.store.state.value.displayName)
            assertEquals("Failed to load user profile", fixture.warnings.single().first)

            fixture.store.clear()
            assertEquals(UserProfileState(), fixture.store.state.value)
        } finally {
            fixture.close()
        }
    }
}

private class UserProfileStoreFixture(parentContext: CoroutineContext) {
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(parentContext + scopeJob)

    val loadedUserIds = mutableListOf<String>()
    val warnings = mutableListOf<Pair<String, Throwable>>()
    var loadBehavior: suspend (String) -> MatrixUserProfile = { userId -> profile(userId) }

    val store = UserProfileStore(
        scope = scope,
        driver = UserProfileDriver { userId ->
            loadedUserIds += userId
            loadBehavior(userId)
        },
        onWarning = { message, error -> warnings += message to error }
    )

    suspend fun close() {
        scopeJob.cancelAndJoin()
    }
}

private fun profile(
    userId: String,
    displayName: String = "Alice",
    avatarUrl: String? = "mxc://example.org/avatar"
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
