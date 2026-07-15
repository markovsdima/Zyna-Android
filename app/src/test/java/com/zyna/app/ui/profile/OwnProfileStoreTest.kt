package com.zyna.app.ui.profile

import com.zyna.app.data.matrix.MatrixOwnProfile
import com.zyna.app.data.profile.ProfileAvatarDraft
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

private const val USER_ID = "@alice:example.org"

class OwnProfileStoreTest {
    @Test
    fun activationLoadsOnceWhileRefreshForcesAnotherLoad() = runBlocking {
        val fixture = OwnProfileStoreFixture(coroutineContext)
        try {
            fixture.store.activate(USER_ID)
            awaitCondition { fixture.store.state.value.displayName == "Alice" }

            fixture.store.activate(USER_ID)
            yield()
            assertEquals(1, fixture.loadCount)

            fixture.loadedProfile = profile(displayName = "Alice Updated")
            fixture.store.refresh()
            awaitCondition { fixture.store.state.value.displayName == "Alice Updated" }

            assertEquals(2, fixture.loadCount)
            assertFalse(fixture.store.state.value.isLoading)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun beginEditCancelsLoadAndRejectsItsLateCompletion() = runBlocking {
        val fixture = OwnProfileStoreFixture(coroutineContext)
        val loadStarted = CompletableDeferred<Unit>()
        val loadGate = CompletableDeferred<Unit>()
        fixture.loadBehavior = {
            loadStarted.complete(Unit)
            try {
                loadGate.await()
                profile(displayName = "Unexpected")
            } catch (_: CancellationException) {
                profile(displayName = "Stale")
            }
        }
        try {
            fixture.store.activate(USER_ID)
            loadStarted.await()

            fixture.store.beginEdit()
            val editSessionId = fixture.store.state.value.editSessionId
            loadGate.complete(Unit)
            yield()

            assertTrue(editSessionId > 0L)
            assertEquals(editSessionId, fixture.store.state.value.editSessionId)
            assertNull(fixture.store.state.value.displayName)
            assertFalse(fixture.store.state.value.isLoading)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun editSessionOwnsAndCleansOnlyItsCurrentAvatarDraft() = runBlocking {
        val fixture = OwnProfileStoreFixture(coroutineContext)
        try {
            fixture.store.activate(USER_ID)
            awaitCondition { fixture.store.state.value.displayName == "Alice" }
            fixture.store.beginEdit()
            val editSessionId = fixture.store.state.value.editSessionId

            fixture.store.setAvatarDraft(draft("first.jpg"), editSessionId)
            fixture.store.setAvatarDraft(draft("second.jpg"), editSessionId)
            fixture.store.setAvatarDraft(draft("stale.jpg"), editSessionId + 1)

            assertEquals("second.jpg", fixture.store.state.value.editAvatarLocalPath)
            assertEquals(listOf("first.jpg", "stale.jpg"), fixture.deletedDrafts)

            fixture.store.removeAvatarDraft()
            assertNull(fixture.store.state.value.editAvatarLocalPath)
            assertEquals(
                OwnProfileAvatarChange.REMOVE,
                fixture.store.state.value.editAvatarChange
            )
            assertEquals(
                listOf("first.jpg", "stale.jpg", "second.jpg"),
                fixture.deletedDrafts
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun saveAppliesChangesRefreshesStateAndFinishesNavigation() = runBlocking {
        val fixture = OwnProfileStoreFixture(coroutineContext)
        try {
            fixture.store.activate(USER_ID)
            awaitCondition { fixture.store.state.value.displayName == "Alice" }
            fixture.store.beginEdit()
            val editSessionId = fixture.store.state.value.editSessionId
            fixture.store.setDisplayNameDraft("  Alice Updated  ")
            fixture.store.setAvatarDraft(draft("avatar.jpg"), editSessionId)
            fixture.loadedProfile = profile(
                displayName = "Alice Updated",
                avatarUrl = "mxc://example.org/new-avatar"
            )

            fixture.store.save()
            awaitCondition { fixture.finishedEditCount == 1 }

            assertEquals(listOf("Alice Updated"), fixture.displayNames)
            assertEquals(listOf("avatar.jpg" to "image/jpeg"), fixture.uploadedAvatars)
            assertEquals(listOf("avatar.jpg"), fixture.deletedDrafts)
            assertEquals("Alice Updated", fixture.store.state.value.displayName)
            assertEquals(0L, fixture.store.state.value.editSessionId)
            assertFalse(fixture.store.state.value.isSaving)
            assertEquals(2, fixture.loadCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun unchangedSaveClearsEditWithoutCallingTheDriver() = runBlocking {
        val fixture = OwnProfileStoreFixture(coroutineContext)
        try {
            fixture.store.activate(USER_ID)
            awaitCondition { fixture.store.state.value.displayName == "Alice" }
            fixture.store.beginEdit()

            fixture.store.save()

            assertEquals(1, fixture.finishedEditCount)
            assertEquals(0L, fixture.store.state.value.editSessionId)
            assertTrue(fixture.displayNames.isEmpty())
            assertTrue(fixture.uploadedAvatars.isEmpty())
            assertEquals(1, fixture.loadCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun saveRemovedAvatarCallsDriverAndRefreshesProfile() = runBlocking {
        val fixture = OwnProfileStoreFixture(coroutineContext)
        try {
            fixture.store.activate(USER_ID)
            awaitCondition { fixture.store.state.value.displayName == "Alice" }
            fixture.store.beginEdit()
            fixture.store.removeAvatarDraft()
            fixture.loadedProfile = profile(avatarUrl = null)

            fixture.store.save()
            awaitCondition { fixture.finishedEditCount == 1 }

            assertEquals(1, fixture.removeAvatarCount)
            assertTrue(fixture.uploadedAvatars.isEmpty())
            assertNull(fixture.store.state.value.avatarUrl)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun saveFailureKeepsDraftForRetryAndReportsError() = runBlocking {
        val fixture = OwnProfileStoreFixture(coroutineContext)
        fixture.uploadAvatarBehavior = { _, _ -> error("upload failed") }
        try {
            fixture.store.activate(USER_ID)
            awaitCondition { fixture.store.state.value.displayName == "Alice" }
            fixture.store.beginEdit()
            val editSessionId = fixture.store.state.value.editSessionId
            fixture.store.setAvatarDraft(draft("retry.jpg"), editSessionId)

            fixture.store.save()
            awaitCondition { fixture.store.state.value.errorMessage == "upload failed" }

            assertFalse(fixture.store.state.value.isSaving)
            assertEquals("retry.jpg", fixture.store.state.value.editAvatarLocalPath)
            assertTrue(fixture.deletedDrafts.isEmpty())
            assertEquals(0, fixture.finishedEditCount)
            assertEquals("Failed to save own profile", fixture.warnings.single().first)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun deactivationCancelsSaveClearsDraftAndRejectsCompletion() = runBlocking {
        val fixture = OwnProfileStoreFixture(coroutineContext)
        val saveStarted = CompletableDeferred<Unit>()
        val saveGate = CompletableDeferred<Unit>()
        fixture.setDisplayNameBehavior = {
            saveStarted.complete(Unit)
            saveGate.await()
        }
        try {
            fixture.store.activate(USER_ID)
            awaitCondition { fixture.store.state.value.displayName == "Alice" }
            fixture.store.beginEdit()
            val editSessionId = fixture.store.state.value.editSessionId
            fixture.store.setDisplayNameDraft("Changed")
            fixture.store.setAvatarDraft(draft("cancelled.jpg"), editSessionId)
            fixture.store.save()
            saveStarted.await()

            fixture.store.deactivate()
            saveGate.complete(Unit)
            yield()

            assertEquals(OwnProfileState(), fixture.store.state.value)
            assertEquals(listOf("cancelled.jpg"), fixture.deletedDrafts)
            assertEquals(0, fixture.finishedEditCount)
        } finally {
            fixture.close()
        }
    }
}

private class OwnProfileStoreFixture(parentContext: CoroutineContext) {
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(parentContext + scopeJob)

    var loadedProfile = profile()
    var loadBehavior: suspend () -> MatrixOwnProfile = { loadedProfile }
    var loadCount = 0
    var finishedEditCount = 0
    var removeAvatarCount = 0
    val displayNames = mutableListOf<String>()
    val uploadedAvatars = mutableListOf<Pair<String, String>>()
    val deletedDrafts = mutableListOf<String>()
    val warnings = mutableListOf<Pair<String, Throwable>>()

    var setDisplayNameBehavior: suspend (String) -> Unit = {}
    var uploadAvatarBehavior: suspend (String, String) -> Unit = { _, _ -> }

    val store = OwnProfileStore(
        scope = scope,
        driver = OwnProfileDriver(
            loadProfile = {
                loadCount += 1
                loadBehavior()
            },
            setDisplayName = { displayName ->
                displayNames += displayName
                setDisplayNameBehavior(displayName)
            },
            uploadAvatar = { localPath, mimeType ->
                uploadedAvatars += localPath to mimeType
                uploadAvatarBehavior(localPath, mimeType)
            },
            removeAvatar = { removeAvatarCount += 1 },
            deleteDraft = { path -> path?.let(deletedDrafts::add) }
        ),
        onEditFinished = { finishedEditCount += 1 },
        onWarning = { message, error -> warnings += message to error }
    )

    suspend fun close() {
        scopeJob.cancelAndJoin()
    }
}

private fun profile(
    displayName: String = "Alice",
    avatarUrl: String? = "mxc://example.org/avatar"
): MatrixOwnProfile {
    return MatrixOwnProfile(
        userId = USER_ID,
        displayName = displayName,
        avatarUrl = avatarUrl
    )
}

private fun draft(path: String): ProfileAvatarDraft {
    return ProfileAvatarDraft(
        localPath = path,
        mimeType = "image/jpeg",
        sizeBytes = 100L,
        width = 256,
        height = 256
    )
}

private suspend fun awaitCondition(condition: () -> Boolean) {
    withTimeout(1_000L) {
        while (!condition()) {
            yield()
        }
    }
}
