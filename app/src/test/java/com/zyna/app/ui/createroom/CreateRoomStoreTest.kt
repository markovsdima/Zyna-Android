package com.zyna.app.ui.createroom

import com.zyna.app.data.matrix.MatrixRoomSummary
import com.zyna.app.data.profile.ProfileAvatarDraft
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val USER_ID = "@alice:example.org"
private const val ROOM_ID = "!created:example.org"

class CreateRoomStoreTest {
    @Test
    fun createWithoutAvatarNormalizesNameCachesAndFinishes() = runBlocking {
        val fixture = CreateRoomFixture(coroutineContext)
        try {
            fixture.store.begin(target())
            fixture.store.setName("  Friends  ")

            fixture.store.create()
            awaitCreateRoomCondition { fixture.createdRooms.isNotEmpty() }

            assertEquals(listOf("create:Friends:null", "cache:$ROOM_ID"), fixture.calls)
            assertEquals("Friends", fixture.createdRooms.single().second.displayName)
            assertEquals(CreateRoomState(), fixture.store.state.value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun retryAfterCreateFailureReusesUploadedAvatar() = runBlocking {
        val fixture = CreateRoomFixture(coroutineContext)
        var createAttempts = 0
        fixture.createBehavior = { name, avatarUrl ->
            createAttempts += 1
            if (createAttempts == 1) error("create failed")
            room(name, avatarUrl)
        }
        try {
            fixture.beginWithAvatar()

            fixture.store.create()
            awaitCreateRoomCondition {
                fixture.store.state.value.error == CreateRoomError.CREATE
            }

            val failed = fixture.store.state.value
            assertEquals("mxc://example/avatar", failed.uploadedAvatarUrl)
            assertTrue(failed.canCreate)

            fixture.store.create()
            awaitCreateRoomCondition { fixture.createdRooms.isNotEmpty() }

            assertEquals(1, fixture.calls.count { it.startsWith("upload:") })
            assertEquals(2, createAttempts)
            assertEquals(listOf("/draft/avatar.jpg"), fixture.deletedDrafts)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun avatarUploadFailureDoesNotCreateRoom() = runBlocking {
        val fixture = CreateRoomFixture(coroutineContext)
        fixture.uploadBehavior = { _, _ -> error("upload failed") }
        try {
            fixture.beginWithAvatar()

            fixture.store.create()
            awaitCreateRoomCondition {
                fixture.store.state.value.error == CreateRoomError.AVATAR_UPLOAD
            }

            assertTrue(fixture.calls.none { it.startsWith("create:") })
            assertTrue(fixture.store.state.value.canCreate)
            assertTrue(fixture.createdRooms.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cacheFailureDoesNotTurnCreatedRoomIntoUiFailure() = runBlocking {
        val fixture = CreateRoomFixture(coroutineContext)
        fixture.cacheBehavior = { _, _ -> error("cache failed") }
        try {
            fixture.store.begin(target())
            fixture.store.setName("Friends")

            fixture.store.create()
            awaitCreateRoomCondition { fixture.createdRooms.isNotEmpty() }

            assertEquals(1, fixture.warnings.size)
            assertEquals(CreateRoomState(), fixture.store.state.value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun dirtyExitRequiresConfirmationAndDeletesDraft() = runBlocking {
        val fixture = CreateRoomFixture(coroutineContext)
        try {
            fixture.beginWithAvatar()

            fixture.store.requestExit()
            assertTrue(fixture.store.state.value.isDiscardConfirmationVisible)
            assertTrue(fixture.cancelledTargets.isEmpty())

            fixture.store.confirmDiscard()

            assertEquals(listOf(target()), fixture.cancelledTargets)
            assertEquals(listOf("/draft/avatar.jpg"), fixture.deletedDrafts)
            assertEquals(CreateRoomState(), fixture.store.state.value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun invalidWhitespaceNameIsStillTreatedAsAUserEditOnExit() = runBlocking {
        val fixture = CreateRoomFixture(coroutineContext)
        try {
            fixture.store.begin(target())
            fixture.store.setName(" ")

            assertTrue(fixture.store.state.value.hasUnsavedChanges)
            assertFalse(fixture.store.state.value.canCreate)
            fixture.store.requestExit()

            assertTrue(fixture.store.state.value.isDiscardConfirmationVisible)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun replacementSessionRejectsLateCreateCompletion() = runBlocking {
        val fixture = CreateRoomFixture(coroutineContext)
        val createStarted = CompletableDeferred<Unit>()
        val releaseCreate = CompletableDeferred<Unit>()
        fixture.createBehavior = { name, avatarUrl ->
            createStarted.complete(Unit)
            withContext(NonCancellable) { releaseCreate.await() }
            room(name, avatarUrl)
        }
        try {
            fixture.store.begin(target())
            fixture.store.setName("Old group")
            val firstSessionId = fixture.store.state.value.editSessionId
            fixture.store.create()
            createStarted.await()

            fixture.store.begin(target())
            val replacementSessionId = fixture.store.state.value.editSessionId
            releaseCreate.complete(Unit)
            yield()

            assertTrue(replacementSessionId > firstSessionId)
            assertEquals("", fixture.store.state.value.name)
            assertTrue(fixture.createdRooms.isEmpty())
            assertTrue(fixture.calls.none { it.startsWith("cache:") })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun staleAvatarDraftIsDeletedInsteadOfCrossingSessions() = runBlocking {
        val fixture = CreateRoomFixture(coroutineContext)
        try {
            fixture.store.begin(target())
            val oldSessionId = fixture.store.state.value.editSessionId
            fixture.store.begin(target())

            fixture.store.setAvatarDraft(
                avatarDraft("/draft/stale.jpg"),
                target(),
                oldSessionId
            )

            assertEquals(listOf("/draft/stale.jpg"), fixture.deletedDrafts)
            assertNull(fixture.store.state.value.avatarLocalPath)
        } finally {
            fixture.close()
        }
    }
}

private class CreateRoomFixture(parentContext: CoroutineContext) {
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(parentContext + scopeJob)

    val calls = mutableListOf<String>()
    val deletedDrafts = mutableListOf<String>()
    val createdRooms = mutableListOf<Pair<CreateRoomTarget, MatrixRoomSummary>>()
    val cancelledTargets = mutableListOf<CreateRoomTarget>()
    val warnings = mutableListOf<Throwable>()

    var uploadBehavior: suspend (String, String) -> String = { _, _ ->
        "mxc://example/avatar"
    }
    var createBehavior: suspend (String, String?) -> MatrixRoomSummary = { name, avatarUrl ->
        room(name, avatarUrl)
    }
    var cacheBehavior: suspend (String, MatrixRoomSummary) -> Unit = { _, _ -> }

    val store = CreateRoomStore(
        scope = scope,
        driver = CreateRoomDriver(
            uploadMedia = { path, mimeType ->
                calls += "upload:$path:$mimeType"
                uploadBehavior(path, mimeType)
            },
            createPrivateGroup = { name, avatarUrl ->
                calls += "create:$name:$avatarUrl"
                createBehavior(name, avatarUrl)
            },
            cacheCreatedRoom = { userId, createdRoom ->
                calls += "cache:${createdRoom.id}"
                cacheBehavior(userId, createdRoom)
            },
            deleteDraft = { path -> path?.let(deletedDrafts::add) }
        ),
        onCreated = { target, createdRoom -> createdRooms += target to createdRoom },
        onCancelled = cancelledTargets::add,
        onWarning = { _, error -> warnings += error }
    )

    fun beginWithAvatar() {
        store.begin(target())
        store.setName("Friends")
        store.setAvatarDraft(
            avatarDraft("/draft/avatar.jpg"),
            target(),
            store.state.value.editSessionId
        )
    }

    suspend fun close() {
        scopeJob.cancelAndJoin()
    }
}

private fun target(): CreateRoomTarget = CreateRoomTarget(USER_ID)

private fun room(name: String, avatarUrl: String?): MatrixRoomSummary {
    return MatrixRoomSummary(
        id = ROOM_ID,
        displayName = name,
        avatarUrl = avatarUrl
    )
}

private fun avatarDraft(path: String): ProfileAvatarDraft {
    return ProfileAvatarDraft(
        localPath = path,
        mimeType = "image/jpeg",
        sizeBytes = 1,
        width = 768,
        height = 768
    )
}

private suspend fun awaitCreateRoomCondition(condition: () -> Boolean) {
    withTimeout(2_000) {
        while (!condition()) {
            yield()
        }
    }
}
