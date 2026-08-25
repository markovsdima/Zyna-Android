package com.zyna.app.ui.createroom

import com.zyna.app.data.matrix.MatrixRoomCreationAccess
import com.zyna.app.data.matrix.MatrixRoomCreationKind
import com.zyna.app.data.matrix.MatrixRoomCreationRequest
import com.zyna.app.data.matrix.MatrixRoomPostingPermission
import com.zyna.app.data.matrix.MatrixRoomSummary
import com.zyna.app.data.matrix.MatrixSpaceMembership
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
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.ErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val USER_ID = "@alice:example.org"
private const val ROOM_ID = "!created:example.org"
private const val PARENT_SPACE_ID = "!parent:example.org"

class CreateRoomStoreTest {
    @Test
    fun privateCreateNormalizesFieldsCachesAndFinishes() = runBlocking {
        val fixture = CreateRoomFixture(coroutineContext)
        try {
            fixture.store.begin(target())
            fixture.store.setName("  Friends  ")
            fixture.store.setTopic("  Weekend plans  ")
            fixture.store.setPostingPermission(CreateRoomPostingPermission.MODERATORS_ONLY)

            fixture.store.create()
            awaitCreateRoomCondition { fixture.createdRooms.isNotEmpty() }

            val request = fixture.creationRequests.single()
            assertEquals("Friends", request.name)
            assertEquals("Weekend plans", request.topic)
            assertEquals(MatrixRoomCreationKind.ROOM, request.kind)
            assertEquals(MatrixRoomCreationAccess.Private, request.access)
            assertEquals(MatrixRoomPostingPermission.MODERATORS_ONLY, request.postingPermission)
            assertNull(request.aliasLocalPart)
            assertEquals(listOf("cache:$ROOM_ID"), fixture.calls)
            assertEquals(CreateRoomState(), fixture.store.state.value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun publicCreateAutoFillsChecksAndRechecksAddress() = runBlocking {
        val fixture = CreateRoomFixture(coroutineContext)
        try {
            fixture.store.begin(target())
            fixture.store.setName("Public Friends")
            fixture.store.setAccess(CreateRoomAccess.PUBLIC)
            awaitCreateRoomCondition {
                fixture.store.state.value.aliasAvailability ==
                    CreateRoomAliasAvailability.AVAILABLE
            }

            assertEquals("public-friends", fixture.store.state.value.aliasLocalPart)
            assertEquals("#public-friends:example.org", fixture.store.state.value.fullAlias)
            assertTrue(fixture.store.state.value.canCreate)

            fixture.store.create()
            awaitCreateRoomCondition { fixture.createdRooms.isNotEmpty() }

            assertEquals(
                listOf(
                    "available:#public-friends:example.org",
                    "available:#public-friends:example.org",
                    "cache:$ROOM_ID"
                ),
                fixture.calls
            )
            assertEquals(MatrixRoomCreationAccess.Public, fixture.creationRequests.single().access)
            assertEquals("public-friends", fixture.creationRequests.single().aliasLocalPart)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun publicAddressMustBeAvailableBeforeCreate() = runBlocking {
        val fixture = CreateRoomFixture(coroutineContext)
        fixture.aliasAvailableBehavior = { false }
        try {
            fixture.store.begin(target())
            fixture.store.setName("Friends")
            fixture.store.setAccess(CreateRoomAccess.PUBLIC)
            awaitCreateRoomCondition {
                fixture.store.state.value.aliasAvailability == CreateRoomAliasAvailability.TAKEN
            }

            assertFalse(fixture.store.state.value.canCreate)
            fixture.store.create()
            yield()
            assertTrue(fixture.creationRequests.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun storylineCreateUsesSpaceContractAndIgnoresPostingPermission() = runBlocking {
        val fixture = CreateRoomFixture(coroutineContext)
        val storylineTarget = CreateRoomTarget(USER_ID, mode = CreateRoomMode.STORYLINE)
        try {
            fixture.store.begin(storylineTarget)
            fixture.store.setName("Product")
            fixture.store.setPostingPermission(CreateRoomPostingPermission.MODERATORS_ONLY)

            assertEquals(
                CreateRoomPostingPermission.ALL_MEMBERS,
                fixture.store.state.value.postingPermission
            )

            fixture.store.create()
            awaitCreateRoomCondition { fixture.createdRooms.isNotEmpty() }

            val request = fixture.creationRequests.single()
            assertEquals(MatrixRoomCreationKind.SPACE, request.kind)
            assertEquals(MatrixRoomCreationAccess.Private, request.access)
            assertEquals(MatrixRoomPostingPermission.ALL_MEMBERS, request.postingPermission)
            assertEquals(storylineTarget, fixture.createdRooms.single().first)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun invalidStorylineParentIsRejectedWithoutReplacingActiveSession() = runBlocking {
        val fixture = CreateRoomFixture(coroutineContext)
        try {
            fixture.store.begin(target())
            fixture.store.setName("Existing draft")

            val didBegin = fixture.store.begin(
                CreateRoomTarget(
                    userId = USER_ID,
                    mode = CreateRoomMode.STORYLINE,
                    parent = CreateRoomParent(PARENT_SPACE_ID, "Product")
                )
            )

            assertFalse(didBegin)
            assertEquals(target(), fixture.store.state.value.target)
            assertEquals("Existing draft", fixture.store.state.value.name)
            assertTrue(fixture.warnings.single() is IllegalArgumentException)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun trackRequiresAParentWithoutReplacingActiveSession() = runBlocking {
        val fixture = CreateRoomFixture(coroutineContext)
        try {
            fixture.store.begin(target())
            fixture.store.setName("Existing draft")

            val didBegin = fixture.store.begin(
                CreateRoomTarget(userId = USER_ID, mode = CreateRoomMode.TRACK)
            )

            assertFalse(didBegin)
            assertEquals(target(), fixture.store.state.value.target)
            assertEquals("Existing draft", fixture.store.state.value.name)
            assertTrue(fixture.warnings.single() is IllegalArgumentException)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun trackCreatesRestrictedSpaceLinksItAndFinishes() = runBlocking {
        val fixture = CreateRoomFixture(coroutineContext)
        fixture.createBehavior = { request ->
            room(request.name, request.avatarUrl).copy(
                isSpace = true,
                membership = MatrixSpaceMembership.JOINED
            )
        }
        val trackTarget = trackTarget()
        try {
            fixture.store.begin(trackTarget)
            fixture.store.setName("Android")

            fixture.store.create()
            awaitCreateRoomCondition { fixture.createdRooms.isNotEmpty() }

            val request = fixture.creationRequests.single()
            assertEquals(MatrixRoomCreationKind.SPACE, request.kind)
            assertEquals(
                MatrixRoomCreationAccess.Restricted(PARENT_SPACE_ID),
                request.access
            )
            assertEquals(
                listOf(PARENT_SPACE_ID, PARENT_SPACE_ID),
                fixture.parentPermissionChecks
            )
            assertEquals(listOf(ROOM_ID), fixture.childReadinessChecks)
            assertEquals(listOf(PARENT_SPACE_ID to ROOM_ID), fixture.addCalls)
            assertEquals(listOf(trackTarget to ROOM_ID), fixture.confirmedChildren)
            assertEquals(trackTarget, fixture.createdRooms.single().first)
            assertEquals(CreateRoomState(), fixture.store.state.value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun trackWaitsForItsLocalRoomBeforeLinkingItToTheParent() = runBlocking {
        val fixture = CreateRoomFixture(coroutineContext)
        val roomReady = CompletableDeferred<Unit>()
        fixture.createBehavior = { request ->
            room(request.name, request.avatarUrl).copy(
                isSpace = true,
                membership = MatrixSpaceMembership.JOINED
            )
        }
        fixture.awaitChildReadyBehavior = { roomReady.await() }
        try {
            fixture.store.begin(trackTarget())
            fixture.store.setName("Android")

            fixture.store.create()
            awaitCreateRoomCondition { fixture.childReadinessChecks.isNotEmpty() }

            assertEquals(ROOM_ID, fixture.store.state.value.pendingCreatedRoom?.id)
            assertTrue(fixture.store.state.value.isCreating)
            assertFalse(fixture.store.state.value.canRetryParentLink)
            assertTrue(fixture.addCalls.isEmpty())
            assertTrue(fixture.createdRooms.isEmpty())

            roomReady.complete(Unit)
            awaitCreateRoomCondition { fixture.createdRooms.isNotEmpty() }

            assertEquals(listOf(PARENT_SPACE_ID to ROOM_ID), fixture.addCalls)
            assertEquals(1, fixture.creationRequests.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun failedTrackReadinessRetriesOnlyTheExistingSpace() = runBlocking {
        val fixture = CreateRoomFixture(coroutineContext)
        var readinessAttempts = 0
        fixture.createBehavior = { request ->
            room(request.name, request.avatarUrl).copy(
                isSpace = true,
                membership = MatrixSpaceMembership.JOINED
            )
        }
        fixture.awaitChildReadyBehavior = {
            readinessAttempts += 1
            if (readinessAttempts == 1) error("room not ready")
        }
        try {
            fixture.store.begin(trackTarget())
            fixture.store.setName("Android")

            fixture.store.create()
            awaitCreateRoomCondition {
                fixture.store.state.value.error == CreateRoomError.ADD_TO_PARENT
            }

            assertEquals(ROOM_ID, fixture.store.state.value.pendingCreatedRoom?.id)
            assertTrue(fixture.store.state.value.canRetryParentLink)
            assertEquals(1, fixture.creationRequests.size)
            assertTrue(fixture.addCalls.isEmpty())

            fixture.store.create()
            awaitCreateRoomCondition { fixture.createdRooms.isNotEmpty() }

            assertEquals(1, fixture.creationRequests.size)
            assertEquals(listOf(ROOM_ID, ROOM_ID), fixture.childReadinessChecks)
            assertEquals(listOf(PARENT_SPACE_ID to ROOM_ID), fixture.addCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun revokedParentPermissionStopsBeforeCreatingTrack() = runBlocking {
        val fixture = CreateRoomFixture(coroutineContext)
        fixture.canManageParentBehavior = { false }
        try {
            fixture.store.begin(trackTarget())
            fixture.store.setName("Android")

            fixture.store.create()
            awaitCreateRoomCondition {
                fixture.store.state.value.error == CreateRoomError.PERMISSION_CHANGED
            }

            assertTrue(fixture.creationRequests.isEmpty())
            assertTrue(fixture.addCalls.isEmpty())
            assertTrue(fixture.store.state.value.canCreate)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun permissionRevokedAfterTrackCreationRetriesOnlyItsParentLink() = runBlocking {
        val fixture = CreateRoomFixture(coroutineContext)
        var permissionChecks = 0
        fixture.canManageParentBehavior = {
            permissionChecks += 1
            permissionChecks != 2
        }
        fixture.createBehavior = { request ->
            room(request.name, request.avatarUrl).copy(
                isSpace = true,
                membership = MatrixSpaceMembership.JOINED
            )
        }
        try {
            fixture.store.begin(trackTarget())
            fixture.store.setName("Android")

            fixture.store.create()
            awaitCreateRoomCondition {
                fixture.store.state.value.error == CreateRoomError.PERMISSION_CHANGED
            }

            assertEquals(1, fixture.creationRequests.size)
            assertEquals(ROOM_ID, fixture.store.state.value.pendingCreatedRoom?.id)
            assertTrue(fixture.addCalls.isEmpty())

            fixture.store.create()
            awaitCreateRoomCondition { fixture.createdRooms.isNotEmpty() }

            assertEquals(1, fixture.creationRequests.size)
            assertEquals(listOf(PARENT_SPACE_ID to ROOM_ID), fixture.addCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun failedTrackLinkRetriesOnlyTheExistingSpace() = runBlocking {
        val fixture = CreateRoomFixture(coroutineContext)
        var addAttempts = 0
        fixture.createBehavior = { request ->
            room(request.name, request.avatarUrl).copy(
                isSpace = true,
                membership = MatrixSpaceMembership.JOINED
            )
        }
        fixture.addChildBehavior = { _, _ ->
            addAttempts += 1
            if (addAttempts == 1) error("link failed")
        }
        try {
            fixture.store.begin(trackTarget())
            fixture.store.setName("Android")

            fixture.store.create()
            awaitCreateRoomCondition {
                fixture.store.state.value.error == CreateRoomError.ADD_TO_PARENT
            }

            val failed = fixture.store.state.value
            assertEquals(ROOM_ID, failed.pendingCreatedRoom?.id)
            assertTrue(failed.canCreate)
            assertEquals(1, fixture.creationRequests.size)
            assertTrue(fixture.createdRooms.isEmpty())

            fixture.store.create()
            awaitCreateRoomCondition { fixture.createdRooms.isNotEmpty() }

            assertEquals(1, fixture.creationRequests.size)
            assertEquals(2, fixture.addCalls.size)
            assertEquals(1, fixture.confirmedChildren.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun hierarchyReconcilesTrackLinkReportedAsFailed() = runBlocking {
        val fixture = CreateRoomFixture(coroutineContext)
        fixture.createBehavior = { request ->
            room(request.name, request.avatarUrl).copy(
                isSpace = true,
                membership = MatrixSpaceMembership.JOINED
            )
        }
        fixture.addChildBehavior = { _, _ -> error("ambiguous write") }
        fixture.reconcileChildBehavior = { _, _ -> true }
        try {
            fixture.store.begin(trackTarget())
            fixture.store.setName("Android")

            fixture.store.create()
            awaitCreateRoomCondition { fixture.createdRooms.isNotEmpty() }

            assertEquals(listOf(trackTarget() to ROOM_ID), fixture.reconciledChildren)
            assertEquals(listOf(trackTarget() to ROOM_ID), fixture.confirmedChildren)
            assertEquals(1, fixture.warnings.size)
            assertEquals(CreateRoomState(), fixture.store.state.value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun childChatDefaultsToRestrictedAccessLinksToParentAndFinishes() = runBlocking {
        val fixture = CreateRoomFixture(coroutineContext)
        val childTarget = CreateRoomTarget(
            userId = USER_ID,
            parent = CreateRoomParent(PARENT_SPACE_ID, "Product")
        )
        try {
            fixture.store.begin(childTarget)
            fixture.store.setName("Android")

            assertEquals(CreateRoomAccess.PARENT_MEMBERS, fixture.store.state.value.access)
            assertTrue(fixture.store.state.value.target?.isChildChat == true)
            assertTrue(fixture.store.state.value.canCreate)

            fixture.store.create()
            awaitCreateRoomCondition { fixture.createdRooms.isNotEmpty() }

            val request = fixture.creationRequests.single()
            assertEquals(MatrixRoomCreationKind.ROOM, request.kind)
            assertEquals(
                MatrixRoomCreationAccess.Restricted(PARENT_SPACE_ID),
                request.access
            )
            assertEquals(
                listOf(PARENT_SPACE_ID, PARENT_SPACE_ID),
                fixture.parentPermissionChecks
            )
            assertEquals(listOf(ROOM_ID), fixture.childReadinessChecks)
            assertEquals(listOf(PARENT_SPACE_ID to ROOM_ID), fixture.addCalls)
            assertEquals(listOf(childTarget to ROOM_ID), fixture.confirmedChildren)
            assertEquals(childTarget, fixture.createdRooms.single().first)
            assertEquals(CreateRoomAccess.PARENT_MEMBERS, fixture.createdAccesses.single())
            assertEquals(CreateRoomState(), fixture.store.state.value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun unsupportedRestrictedRoomVersionSuggestsAnotherAccessOption() = runBlocking {
        val fixture = CreateRoomFixture(coroutineContext)
        fixture.createBehavior = {
            throw ClientException.MatrixApi(
                ErrorKind.UnsupportedRoomVersion,
                "M_UNSUPPORTED_ROOM_VERSION",
                "Unsupported room version",
                null
            )
        }
        try {
            fixture.store.begin(
                CreateRoomTarget(
                    userId = USER_ID,
                    parent = CreateRoomParent(PARENT_SPACE_ID, "Product")
                )
            )
            fixture.store.setName("Android")

            fixture.store.create()
            awaitCreateRoomCondition {
                fixture.store.state.value.error ==
                    CreateRoomError.RESTRICTED_ACCESS_UNSUPPORTED
            }

            assertNull(fixture.store.state.value.pendingCreatedRoom)
            assertTrue(fixture.store.state.value.canCreate)
            assertTrue(fixture.addCalls.isEmpty())
            assertTrue(fixture.createdRooms.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun automaticAddressFollowsNameAfterPrivateRoundTrip() = runBlocking {
        val fixture = CreateRoomFixture(coroutineContext)
        try {
            fixture.store.begin(target())
            fixture.store.setName("Old name")
            fixture.store.setAccess(CreateRoomAccess.PUBLIC)
            awaitCreateRoomCondition { fixture.store.state.value.canCreate }

            fixture.store.setAccess(CreateRoomAccess.PRIVATE)
            fixture.store.setName("New name")
            fixture.store.setAccess(CreateRoomAccess.PUBLIC)
            awaitCreateRoomCondition { fixture.store.state.value.canCreate }

            assertEquals("new-name", fixture.store.state.value.aliasLocalPart)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun createRecheckStopsBeforeUploadWhenAddressWasTaken() = runBlocking {
        val fixture = CreateRoomFixture(coroutineContext)
        var checks = 0
        fixture.aliasAvailableBehavior = {
            checks += 1
            checks == 1
        }
        try {
            fixture.beginWithAvatar()
            fixture.store.setAccess(CreateRoomAccess.PUBLIC)
            awaitCreateRoomCondition { fixture.store.state.value.canCreate }

            fixture.store.create()
            awaitCreateRoomCondition {
                fixture.store.state.value.aliasAvailability == CreateRoomAliasAvailability.TAKEN
            }

            assertFalse(fixture.store.state.value.isCreating)
            assertTrue(fixture.calls.none { it.startsWith("upload:") })
            assertTrue(fixture.creationRequests.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun failedAddressCheckCanBeRetried() = runBlocking {
        val fixture = CreateRoomFixture(coroutineContext)
        var attempts = 0
        fixture.aliasAvailableBehavior = {
            attempts += 1
            if (attempts == 1) error("offline")
            true
        }
        try {
            fixture.store.begin(target())
            fixture.store.setName("Friends")
            fixture.store.setAccess(CreateRoomAccess.PUBLIC)
            awaitCreateRoomCondition {
                fixture.store.state.value.aliasAvailability == CreateRoomAliasAvailability.ERROR
            }

            fixture.store.retryAliasCheck()
            awaitCreateRoomCondition {
                fixture.store.state.value.aliasAvailability ==
                    CreateRoomAliasAvailability.AVAILABLE
            }

            assertTrue(fixture.store.state.value.canCreate)
            assertEquals(2, attempts)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun invalidAddressDoesNotHitAvailabilityApi() = runBlocking {
        val fixture = CreateRoomFixture(coroutineContext)
        try {
            fixture.store.begin(target())
            fixture.store.setName("Friends")
            fixture.store.setAccess(CreateRoomAccess.PUBLIC)
            awaitCreateRoomCondition { fixture.store.state.value.canCreate }
            fixture.calls.clear()

            fixture.store.setAliasLocalPart("bad address")
            awaitCreateRoomCondition {
                fixture.store.state.value.aliasAvailability ==
                    CreateRoomAliasAvailability.INVALID
            }

            assertTrue(fixture.calls.isEmpty())
            assertFalse(fixture.store.state.value.canCreate)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun createTimeAddressCheckFailureIsRetryableWithoutUploading() = runBlocking {
        val fixture = CreateRoomFixture(coroutineContext)
        var checks = 0
        fixture.aliasAvailableBehavior = {
            checks += 1
            if (checks == 1) true else error("offline")
        }
        try {
            fixture.beginWithAvatar()
            fixture.store.setAccess(CreateRoomAccess.PUBLIC)
            awaitCreateRoomCondition { fixture.store.state.value.canCreate }

            fixture.store.create()
            awaitCreateRoomCondition {
                fixture.store.state.value.error == CreateRoomError.ADDRESS_CHECK
            }

            assertEquals(CreateRoomAliasAvailability.ERROR, fixture.store.state.value.aliasAvailability)
            assertTrue(fixture.calls.none { it.startsWith("upload:") })
            assertTrue(fixture.creationRequests.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun lateAddressResultCannotValidateNewerAddress() = runBlocking {
        val fixture = CreateRoomFixture(coroutineContext)
        val oldCheckStarted = CompletableDeferred<Unit>()
        val releaseOldCheck = CompletableDeferred<Unit>()
        fixture.aliasAvailableBehavior = { alias ->
            if (alias.contains("old")) {
                oldCheckStarted.complete(Unit)
                withContext(NonCancellable) { releaseOldCheck.await() }
                false
            } else {
                true
            }
        }
        try {
            fixture.store.begin(target())
            fixture.store.setName("Old")
            fixture.store.setAccess(CreateRoomAccess.PUBLIC)
            oldCheckStarted.await()

            fixture.store.setAliasLocalPart("new")
            awaitCreateRoomCondition {
                fixture.store.state.value.aliasAvailability ==
                    CreateRoomAliasAvailability.AVAILABLE
            }
            releaseOldCheck.complete(Unit)
            yield()

            assertEquals("new", fixture.store.state.value.aliasLocalPart)
            assertEquals(CreateRoomAliasAvailability.AVAILABLE, fixture.store.state.value.aliasAvailability)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun retryAfterCreateFailureReusesUploadedAvatar() = runBlocking {
        val fixture = CreateRoomFixture(coroutineContext)
        var createAttempts = 0
        fixture.createBehavior = { request ->
            createAttempts += 1
            if (createAttempts == 1) error("create failed")
            room(request.name, request.avatarUrl)
        }
        try {
            fixture.beginWithAvatar()

            fixture.store.create()
            awaitCreateRoomCondition { fixture.store.state.value.error == CreateRoomError.CREATE }

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

            assertTrue(fixture.creationRequests.isEmpty())
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
        fixture.createBehavior = { request ->
            createStarted.complete(Unit)
            withContext(NonCancellable) { releaseCreate.await() }
            room(request.name, request.avatarUrl)
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
    val creationRequests = mutableListOf<MatrixRoomCreationRequest>()
    val createdRooms = mutableListOf<Pair<CreateRoomTarget, MatrixRoomSummary>>()
    val createdAccesses = mutableListOf<CreateRoomAccess>()
    val cancelledTargets = mutableListOf<CreateRoomTarget>()
    val warnings = mutableListOf<Throwable>()
    val parentPermissionChecks = mutableListOf<String>()
    val childReadinessChecks = mutableListOf<String>()
    val addCalls = mutableListOf<Pair<String, String>>()
    val confirmedChildren = mutableListOf<Pair<CreateRoomTarget, String>>()
    val reconciledChildren = mutableListOf<Pair<CreateRoomTarget, String>>()

    var uploadBehavior: suspend (String, String) -> String = { _, _ -> "mxc://example/avatar" }
    var aliasAvailableBehavior: suspend (String) -> Boolean = { true }
    var createBehavior: suspend (MatrixRoomCreationRequest) -> MatrixRoomSummary = { request ->
        room(request.name, request.avatarUrl)
    }
    var cacheBehavior: suspend (String, MatrixRoomSummary) -> Unit = { _, _ -> }
    var canManageParentBehavior: suspend (String) -> Boolean = { true }
    var awaitChildReadyBehavior: suspend (String) -> Unit = { }
    var addChildBehavior: suspend (String, String) -> Unit = { _, _ -> }
    var reconcileChildBehavior: suspend (CreateRoomTarget, String) -> Boolean? = { _, _ -> false }

    val store = CreateRoomStore(
        scope = scope,
        driver = CreateRoomDriver(
            uploadMedia = { path, mimeType ->
                calls += "upload:$path:$mimeType"
                uploadBehavior(path, mimeType)
            },
            suggestAliasLocalPart = { name ->
                name.trim().lowercase().replace(' ', '-')
            },
            isAliasValid = { alias -> alias.startsWith('#') && !alias.contains(' ') },
            isAliasAvailable = { alias ->
                calls += "available:$alias"
                aliasAvailableBehavior(alias)
            },
            canManageParent = { _, parentSpaceId ->
                parentPermissionChecks += parentSpaceId
                canManageParentBehavior(parentSpaceId)
            },
            createRoom = { request ->
                creationRequests += request
                createBehavior(request)
            },
            cacheCreatedRoom = { userId, createdRoom ->
                calls += "cache:${createdRoom.id}"
                cacheBehavior(userId, createdRoom)
            },
            awaitChildReadyForParentLink = { _, childRoomId ->
                childReadinessChecks += childRoomId
                awaitChildReadyBehavior(childRoomId)
            },
            addChildToParent = { _, parentSpaceId, childRoomId ->
                addCalls += parentSpaceId to childRoomId
                addChildBehavior(parentSpaceId, childRoomId)
            },
            confirmChildAdded = { target, createdRoom ->
                confirmedChildren += target to createdRoom.id
            },
            reconcileChildAdded = { target, childRoomId ->
                reconciledChildren += target to childRoomId
                reconcileChildBehavior(target, childRoomId)
            },
            deleteDraft = { path -> path?.let(deletedDrafts::add) }
        ),
        onCreated = { target, createdRoom, access ->
            createdRooms += target to createdRoom
            createdAccesses += access
        },
        onCancelled = cancelledTargets::add,
        onWarning = { _, error -> warnings += error },
        aliasCheckDebounceMillis = 0
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

private fun trackTarget(): CreateRoomTarget = CreateRoomTarget(
    userId = USER_ID,
    mode = CreateRoomMode.TRACK,
    parent = CreateRoomParent(PARENT_SPACE_ID, "Product")
)

private fun room(name: String, avatarUrl: String?): MatrixRoomSummary {
    return MatrixRoomSummary(id = ROOM_ID, displayName = name, avatarUrl = avatarUrl)
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
        while (!condition()) yield()
    }
}
