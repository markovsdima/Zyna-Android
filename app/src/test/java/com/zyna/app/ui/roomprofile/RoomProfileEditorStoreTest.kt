package com.zyna.app.ui.roomprofile

import com.zyna.app.data.matrix.MatrixRoomAccess
import com.zyna.app.data.matrix.MatrixRoomCapabilities
import com.zyna.app.data.matrix.MatrixRoomDetails
import com.zyna.app.data.matrix.MatrixRoomEncryption
import com.zyna.app.data.matrix.MatrixRoomHistoryVisibility
import com.zyna.app.data.matrix.MatrixRoomKind
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
private const val ROOM_A = "!a:example.org"
private const val ROOM_B = "!b:example.org"

class RoomProfileEditorStoreTest {
    @Test
    fun beginEditPublishesIndependentFieldCapabilities() = runBlocking {
        val fixture = RoomProfileEditorFixture(coroutineContext)
        try {
            fixture.store.beginEdit(
                target(ROOM_A),
                details(
                    roomId = ROOM_A,
                    capabilities = MatrixRoomCapabilities(
                        canInviteMembers = false,
                        canChangeName = true,
                        canChangeTopic = true,
                        canChangeAvatar = false
                    )
                )
            )

            val state = fixture.store.state.value
            assertEquals("Original", state.editDisplayName)
            assertEquals("Original topic", state.editTopic)
            assertTrue(state.canChangeName)
            assertTrue(state.canChangeTopic)
            assertFalse(state.canChangeAvatar)
            assertFalse(state.hasUnsavedChanges)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun saveRechecksCapabilitiesAndFinishesAfterAllMutations() = runBlocking {
        val fixture = RoomProfileEditorFixture(coroutineContext)
        try {
            fixture.beginEditableRoom()
            val sessionId = fixture.store.state.value.editSessionId
            fixture.store.setDisplayNameDraft(" Renamed ")
            fixture.store.setTopicDraft(" Updated topic ")
            fixture.store.setAvatarDraft(avatarDraft("/draft/new.jpg"), target(ROOM_A), sessionId)

            fixture.store.save()
            awaitEditorCondition { fixture.finishedTargets.isNotEmpty() }

            assertEquals(
                listOf(
                    "capabilities",
                    "name:Renamed",
                    "topic:Updated topic",
                    "avatar:/draft/new.jpg"
                ),
                fixture.calls
            )
            assertEquals(listOf(target(ROOM_A)), fixture.finishedTargets)
            assertEquals(RoomProfileEditorState(), fixture.store.state.value)
            assertEquals(listOf("/draft/new.jpg"), fixture.deletedDrafts)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun avatarFailureAfterNameSaveLeavesOnlyAvatarDirtyAndRetryable() = runBlocking {
        val fixture = RoomProfileEditorFixture(coroutineContext)
        var avatarAttempts = 0
        fixture.uploadAvatarBehavior = { _, _, _ ->
            avatarAttempts += 1
            if (avatarAttempts == 1) error("upload failed")
        }
        try {
            fixture.beginEditableRoom()
            val sessionId = fixture.store.state.value.editSessionId
            fixture.store.setDisplayNameDraft("Renamed")
            fixture.store.setAvatarDraft(avatarDraft("/draft/new.jpg"), target(ROOM_A), sessionId)

            fixture.store.save()
            awaitEditorCondition {
                fixture.store.state.value.error == RoomProfileEditorError.PARTIAL_SAVE
            }

            val partiallySaved = fixture.store.state.value
            assertEquals("Renamed", partiallySaved.displayName)
            assertFalse(partiallySaved.hasNameChange)
            assertTrue(partiallySaved.hasAvatarChange)
            assertTrue(partiallySaved.canSave)

            fixture.store.save()
            awaitEditorCondition { fixture.finishedTargets.isNotEmpty() }

            assertEquals(1, fixture.calls.count { it.startsWith("name:") })
            assertEquals(2, avatarAttempts)
            assertEquals(listOf(target(ROOM_A)), fixture.finishedTargets)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun avatarFailureAfterTopicSaveDoesNotRepeatTheTopic() = runBlocking {
        val fixture = RoomProfileEditorFixture(coroutineContext)
        var avatarAttempts = 0
        fixture.uploadAvatarBehavior = { _, _, _ ->
            avatarAttempts += 1
            if (avatarAttempts == 1) error("upload failed")
        }
        try {
            fixture.beginEditableRoom()
            val sessionId = fixture.store.state.value.editSessionId
            fixture.store.setTopicDraft("Updated topic")
            fixture.store.setAvatarDraft(avatarDraft("/draft/new.jpg"), target(ROOM_A), sessionId)

            fixture.store.save()
            awaitEditorCondition {
                fixture.store.state.value.error == RoomProfileEditorError.PARTIAL_SAVE
            }

            val partiallySaved = fixture.store.state.value
            assertEquals("Updated topic", partiallySaved.topic)
            assertFalse(partiallySaved.hasTopicChange)
            assertTrue(partiallySaved.hasAvatarChange)
            assertTrue(partiallySaved.canSave)

            fixture.store.save()
            awaitEditorCondition { fixture.finishedTargets.isNotEmpty() }

            assertEquals(1, fixture.calls.count { it.startsWith("topic:") })
            assertEquals(2, avatarAttempts)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun clearingTopicWritesAnEmptyStateEvent() = runBlocking {
        val fixture = RoomProfileEditorFixture(coroutineContext)
        try {
            fixture.beginEditableRoom()
            fixture.store.setTopicDraft("   ")

            assertTrue(fixture.store.state.value.hasTopicChange)
            assertTrue(fixture.store.state.value.canSave)

            fixture.store.save()
            awaitEditorCondition { fixture.finishedTargets.isNotEmpty() }

            assertEquals(listOf("capabilities", "topic:"), fixture.calls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun revokedTopicPermissionStopsBeforeMutation() = runBlocking {
        val fixture = RoomProfileEditorFixture(coroutineContext)
        fixture.capabilities = MatrixRoomCapabilities(
            canInviteMembers = true,
            canChangeName = true,
            canChangeTopic = false,
            canChangeAvatar = true
        )
        try {
            fixture.beginEditableRoom()
            fixture.store.setTopicDraft("Updated topic")

            fixture.store.save()
            awaitEditorCondition {
                fixture.store.state.value.error == RoomProfileEditorError.PERMISSION_CHANGED
            }

            assertEquals(listOf("capabilities"), fixture.calls)
            assertFalse(fixture.store.state.value.canChangeTopic)
            assertTrue(fixture.store.state.value.hasTopicChange)
            assertFalse(fixture.store.state.value.canSave)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun revokedPermissionStopsBeforeMutationAndUpdatesCapabilityState() = runBlocking {
        val fixture = RoomProfileEditorFixture(coroutineContext)
        fixture.capabilities = MatrixRoomCapabilities(
            canInviteMembers = true,
            canChangeName = false,
            canChangeTopic = true,
            canChangeAvatar = true
        )
        try {
            fixture.beginEditableRoom()
            fixture.store.setDisplayNameDraft("Renamed")

            fixture.store.save()
            awaitEditorCondition {
                fixture.store.state.value.error == RoomProfileEditorError.PERMISSION_CHANGED
            }

            assertEquals(listOf("capabilities"), fixture.calls)
            assertFalse(fixture.store.state.value.canChangeName)
            assertTrue(fixture.store.state.value.hasNameChange)
            assertFalse(fixture.store.state.value.canSave)
            assertTrue(fixture.finishedTargets.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun replacingEditSessionRejectsLateCompletionFromPreviousRoom() = runBlocking {
        val fixture = RoomProfileEditorFixture(coroutineContext)
        val nameStarted = CompletableDeferred<Unit>()
        val releaseName = CompletableDeferred<Unit>()
        fixture.setNameBehavior = { roomId, _ ->
            if (roomId == ROOM_A) {
                nameStarted.complete(Unit)
                withContext(NonCancellable) { releaseName.await() }
            }
        }
        try {
            fixture.beginEditableRoom()
            fixture.store.setDisplayNameDraft("Renamed A")
            fixture.store.save()
            nameStarted.await()

            fixture.store.beginEdit(target(ROOM_B), details(ROOM_B))
            releaseName.complete(Unit)
            yield()

            assertEquals(ROOM_B, fixture.store.state.value.target?.roomId)
            assertEquals("Original", fixture.store.state.value.editDisplayName)
            assertTrue(fixture.finishedTargets.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun dirtyExitRequiresConfirmationAndConfirmDeletesDraft() = runBlocking {
        val fixture = RoomProfileEditorFixture(coroutineContext)
        try {
            fixture.beginEditableRoom()
            val sessionId = fixture.store.state.value.editSessionId
            fixture.store.setAvatarDraft(avatarDraft("/draft/new.jpg"), target(ROOM_A), sessionId)

            fixture.store.requestExit()
            assertTrue(fixture.store.state.value.isDiscardConfirmationVisible)
            assertTrue(fixture.finishedTargets.isEmpty())

            fixture.store.confirmDiscard()

            assertEquals(RoomProfileEditorState(), fixture.store.state.value)
            assertEquals(listOf("/draft/new.jpg"), fixture.deletedDrafts)
            assertEquals(listOf(target(ROOM_A)), fixture.finishedTargets)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun staleAvatarDraftIsDeletedInsteadOfCrossingEditSessions() = runBlocking {
        val fixture = RoomProfileEditorFixture(coroutineContext)
        try {
            fixture.beginEditableRoom()
            val oldSessionId = fixture.store.state.value.editSessionId
            fixture.store.beginEdit(target(ROOM_B), details(ROOM_B))

            fixture.store.setAvatarDraft(
                avatarDraft("/draft/stale.jpg"),
                target(ROOM_A),
                oldSessionId
            )

            assertEquals(listOf("/draft/stale.jpg"), fixture.deletedDrafts)
            assertNull(fixture.store.state.value.editAvatarLocalPath)
        } finally {
            fixture.close()
        }
    }
}

private class RoomProfileEditorFixture(parentContext: CoroutineContext) {
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(parentContext + scopeJob)

    val calls = mutableListOf<String>()
    val deletedDrafts = mutableListOf<String>()
    val finishedTargets = mutableListOf<RoomProfileEditorTarget>()
    var capabilities = MatrixRoomCapabilities(
        canInviteMembers = true,
        canChangeName = true,
        canChangeTopic = true,
        canChangeAvatar = true
    )
    var setNameBehavior: suspend (String, String) -> Unit = { _, _ -> }
    var setTopicBehavior: suspend (String, String) -> Unit = { _, _ -> }
    var uploadAvatarBehavior: suspend (String, String, String) -> Unit = { _, _, _ -> }
    var removeAvatarBehavior: suspend (String) -> Unit = {}

    val store = RoomProfileEditorStore(
        scope = scope,
        driver = RoomProfileEditorDriver(
            loadCapabilities = {
                calls += "capabilities"
                capabilities
            },
            setName = { roomId, name ->
                calls += "name:$name"
                setNameBehavior(roomId, name)
            },
            setTopic = { roomId, topic ->
                calls += "topic:$topic"
                setTopicBehavior(roomId, topic)
            },
            uploadAvatar = { roomId, path, mimeType ->
                calls += "avatar:$path"
                uploadAvatarBehavior(roomId, path, mimeType)
            },
            removeAvatar = { roomId ->
                calls += "remove-avatar"
                removeAvatarBehavior(roomId)
            },
            deleteDraft = { path -> path?.let(deletedDrafts::add) }
        ),
        onEditFinished = { target, _ -> finishedTargets += target }
    )

    fun beginEditableRoom() {
        store.beginEdit(target(ROOM_A), details(ROOM_A))
    }

    suspend fun close() {
        scopeJob.cancelAndJoin()
    }
}

private fun target(roomId: String): RoomProfileEditorTarget {
    return RoomProfileEditorTarget(USER_ID, roomId)
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

private fun details(
    roomId: String,
    capabilities: MatrixRoomCapabilities = MatrixRoomCapabilities(
        canInviteMembers = true,
        canChangeName = true,
        canChangeTopic = true,
        canChangeAvatar = true
    )
): MatrixRoomDetails {
    return MatrixRoomDetails(
        roomId = roomId,
        displayName = "Original",
        avatarUrl = "mxc://example.org/avatar",
        directUserId = null,
        kind = MatrixRoomKind.GROUP,
        topic = "Original topic",
        joinedMemberCount = 3,
        encryption = MatrixRoomEncryption.ENCRYPTED,
        access = MatrixRoomAccess.PRIVATE,
        historyVisibility = MatrixRoomHistoryVisibility.INVITED,
        pinnedEventCount = 0,
        canonicalAlias = null,
        capabilities = capabilities
    )
}

private suspend fun awaitEditorCondition(condition: () -> Boolean) {
    withTimeout(1_000L) {
        while (!condition()) yield()
    }
}
