package com.zyna.app.ui.app
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavStateTest {
    @Test
    fun spacesKeepContextForNestedTracksAndChats() {
        val root = AppNavState()
            .enterMain()
            .openSpace(
                spaceId = "!story:example.org",
                parentSpaceId = null,
                displayName = "Story",
                avatarUrl = null,
                topic = null
            )
        val track = root.openSpace(
            spaceId = "!track:example.org",
            parentSpaceId = "!story:example.org",
            displayName = "Track",
            avatarUrl = null,
            topic = null
        )
        val chat = track.openChatFromSpace(
            roomId = "!chat:example.org",
            displayName = "Chat"
        )

        assertEquals(4, chat.chatsStack.size)
        assertEquals("!track:example.org", chat.activeSpaceRoute?.spaceId)
        assertEquals("!chat:example.org", chat.activeChatRoute?.roomId)
        assertFalse(chat.showsTabs)

        val backToTrack = chat.closeChat()
        assertEquals(track.chatsStack, backToTrack.chatsStack)
        assertEquals("!track:example.org", backToTrack.activeSpaceRoute?.spaceId)
    }

    @Test
    fun spaceDetailsRemainOwnedByTheSpaceRoute() {
        val space = AppNavState()
            .enterMain()
            .openSpace(
                spaceId = "!story:example.org",
                parentSpaceId = null,
                displayName = "Story",
                avatarUrl = null,
                topic = null
            )

        val details = space.openRoomDetails()

        assertEquals(AppRoute.RoomDetails("!story:example.org"), details.top)
        assertEquals("!story:example.org", details.activeSpaceRoute?.spaceId)
        assertEquals("!story:example.org", details.activeRoomDetailsRoute?.roomId)
    }

    @Test
    fun childChatDetailsKeepTheirParentSpaceActive() {
        val state = AppNavState()
            .enterMain()
            .openSpace(
                spaceId = "!story:example.org",
                parentSpaceId = null,
                displayName = "Story",
                avatarUrl = null,
                topic = null
            )
            .openSpace(
                spaceId = "!track:example.org",
                parentSpaceId = "!story:example.org",
                displayName = "Track",
                avatarUrl = null,
                topic = null
            )
            .openChatFromSpace(
                roomId = "!chat:example.org",
                displayName = "Chat"
            )
            .openRoomDetails()
            .openRoomMembers()

        assertEquals("!track:example.org", state.activeSpaceRoute?.spaceId)
        assertEquals("!chat:example.org", state.activeRoomDetailsRoute?.roomId)
    }

    @Test
    fun nestedSpaceCannotBePushedWithoutItsCurrentParent() {
        val state = AppNavState().enterMain()

        assertEquals(
            state,
            state.openSpace(
                spaceId = "!track:example.org",
                parentSpaceId = "!missing:example.org",
                displayName = "Track",
                avatarUrl = null,
                topic = null
            )
        )
    }

    @Test
    fun membershipPreviewKeepsItsExactParentSpaceActive() {
        val parent = AppNavState()
            .enterMain()
            .openSpace(
                spaceId = "!story:example.org",
                parentSpaceId = null,
                displayName = "Story",
                avatarUrl = null,
                topic = null
            )
        val preview = parent.openSpaceJoinPreview(
            roomId = "!track:example.org",
            parentSpaceId = "!story:example.org",
            displayName = "Track",
            avatarUrl = null,
            topic = null,
            isSpace = true
        )

        assertTrue(preview.top is AppRoute.SpaceJoinPreview)
        assertEquals("!story:example.org", preview.activeSpaceRoute?.spaceId)
        assertEquals("!track:example.org", preview.activeSpaceJoinPreviewRoute?.roomId)
        assertEquals(parent, preview.popActiveStack())
        assertEquals(
            parent,
            parent.openSpaceJoinPreview(
                roomId = "!track:example.org",
                parentSpaceId = "!other:example.org",
                displayName = "Track",
                avatarUrl = null,
                topic = null,
                isSpace = true
            )
        )
    }

    @Test
    fun rootSpaceInvitationOpensPreviewFromRoomsAndIsReplacedAfterJoin() {
        val rooms = AppNavState().enterMain()
        val preview = rooms.openSpaceJoinPreview(
            roomId = "!story:example.org",
            parentSpaceId = null,
            displayName = "Story",
            avatarUrl = null,
            topic = null,
            isSpace = true
        )

        assertEquals("!story:example.org", preview.activeSpaceJoinPreviewRoute?.roomId)
        assertEquals(null, preview.activeSpaceRoute)
        assertEquals(rooms, preview.popActiveStack())

        val joined = preview.openSpace(
            spaceId = "!story:example.org",
            parentSpaceId = null,
            displayName = "Story",
            avatarUrl = null,
            topic = null
        )
        assertEquals("!story:example.org", (joined.top as AppRoute.Space).spaceId)
        assertFalse(joined.chatsStack.any { it is AppRoute.SpaceJoinPreview })
    }

    @Test
    fun joinedPreviewIsReplacedWithoutLosingSpaceContext() {
        val preview = AppNavState()
            .enterMain()
            .openSpace(
                spaceId = "!story:example.org",
                parentSpaceId = null,
                displayName = "Story",
                avatarUrl = null,
                topic = null
            )
            .openSpaceJoinPreview(
                roomId = "!child:example.org",
                parentSpaceId = "!story:example.org",
                displayName = "Child",
                avatarUrl = null,
                topic = null,
                isSpace = false
            )

        val chat = preview.openChatFromSpace("!child:example.org", "Child")
        assertTrue(chat.top is AppRoute.Chat)
        assertFalse(chat.chatsStack.any { it is AppRoute.SpaceJoinPreview })
        assertEquals("!story:example.org", chat.activeSpaceRoute?.spaceId)

        val trackPreview = preview.copy(
            chatsStack = preview.chatsStack.dropLast(1) + AppRoute.SpaceJoinPreview(
                roomId = "!track:example.org",
                parentSpaceId = "!story:example.org",
                displayName = "Track",
                avatarUrl = null,
                topic = null,
                isSpace = true
            )
        )
        val track = trackPreview.openSpace(
            spaceId = "!track:example.org",
            parentSpaceId = "!story:example.org",
            displayName = "Track",
            avatarUrl = null,
            topic = null
        )
        assertEquals("!track:example.org", (track.top as AppRoute.Space).spaceId)
        assertFalse(track.chatsStack.any { it is AppRoute.SpaceJoinPreview })
    }

    @Test
    fun createRoomFlowReplacesEditorWithInvitesAndKeepsTabsHidden() {
        val roomId = "!created:example.org"
        val displayName = "Friends"
        val avatarUrl = "mxc://example/avatar"

        val creationState = AppNavState()
            .enterMain()
            .openCreateRoom()
        val inviteState = creationState.openCreatedRoomInvites(
            roomId = roomId,
            displayName = displayName,
            avatarUrl = avatarUrl
        )

        assertEquals(
            listOf(
                AppRoute.Rooms,
                AppRoute.InviteCreatedRoomMembers(
                    roomId = roomId,
                    displayName = displayName,
                    avatarUrl = avatarUrl
                )
            ),
            inviteState.chatsStack
        )
        assertFalse(inviteState.showsTabs)
        assertFalse(inviteState.chatsStack.contains(AppRoute.CreateRoom))
    }

    @Test
    fun openCreateRoomIsOnlyAllowedFromChatsRoot() {
        val chatState = AppNavState()
            .enterMain()
            .openChat(roomId = "!room:example.org", displayName = "Room")
        val contactsState = AppNavState()
            .enterMain()
            .selectTab(AppTab.CONTACTS)

        assertEquals(chatState, chatState.openCreateRoom())
        assertEquals(contactsState, contactsState.openCreateRoom())
        assertEquals(AppNavState(), AppNavState().openCreateRoom())
    }

    @Test
    fun createdRoomInvitesCannotBeOpenedWithoutOwningCreationRoute() {
        val mainState = AppNavState().enterMain()

        assertEquals(
            mainState,
            mainState.openCreatedRoomInvites(
                roomId = "!created:example.org",
                displayName = "Friends",
                avatarUrl = null
            )
        )
    }

    @Test
    fun defaultState_showsLoginRoute() {
        val state = AppNavState()

        assertEquals(AppNavMode.Login, state.mode)
        assertEquals(AppRoute.Login, state.top)
        assertEquals(listOf(AppRoute.Login), state.visibleStack)
        assertFalse(state.showsTabs)
        assertNull(state.activeChatRoute)
    }

    @Test
    fun routeForClientState_entersRecoveryAndMainFromAuthModes() {
        val recoveryState = AppNavState().routeForClientState(
            shouldShowLogin = false,
            recoveryUserId = "@alice:example.org"
        )

        assertEquals(AppNavMode.RecoveryKey("@alice:example.org"), recoveryState.mode)
        assertEquals(AppRoute.RecoveryKey("@alice:example.org"), recoveryState.top)

        val mainState = recoveryState.routeForClientState(
            shouldShowLogin = false,
            recoveryUserId = null
        )

        assertEquals(AppNavMode.Main, mainState.mode)
        assertEquals(AppTab.CHATS, mainState.selectedTab)
        assertEquals(listOf(AppRoute.Rooms), mainState.visibleStack)
        assertTrue(mainState.showsTabs)
    }

    @Test
    fun routeForClientState_preservesMainNavigationWhenClientRemainsReady() {
        val chatRoute = AppRoute.Chat(roomId = "!room:example.org", displayName = "Room")
        val state = AppNavState()
            .enterMain()
            .openChat(chatRoute.roomId, chatRoute.displayName)
            .closeChat()
            .selectTab(AppTab.PROFILE)
            .openChatThemeSettings()

        val nextState = state.routeForClientState(
            shouldShowLogin = false,
            recoveryUserId = null
        )

        assertEquals(state, nextState)
        assertEquals(AppTab.PROFILE, nextState.selectedTab)
        assertEquals(
            listOf(AppRoute.Profile, AppRoute.Settings, AppRoute.ChatThemeSettings),
            nextState.visibleStack
        )
    }

    @Test
    fun sessionSecurity_canBeReopenedFromSettingsWithoutLeavingMainMode() {
        val state = AppNavState()
            .enterMain()
            .openProfileSettings()
            .openSessionSecurity("@alice:example.org")

        assertEquals(AppNavMode.Main, state.mode)
        assertEquals(AppTab.PROFILE, state.selectedTab)
        assertEquals(
            listOf(
                AppRoute.Profile,
                AppRoute.Settings,
                AppRoute.SessionSecurity("@alice:example.org")
            ),
            state.visibleStack
        )
        assertFalse(state.showsTabs)
    }

    @Test
    fun incomingSessionSecurity_preservesAndRestoresCurrentChatStack() {
        val chatRoute = AppRoute.Chat(roomId = "!room:example.org", displayName = "Room")
        val securityRoute = AppRoute.SessionSecurity("@alice:example.org")
        val chatState = AppNavState()
            .enterMain()
            .openChat(chatRoute.roomId, chatRoute.displayName)

        val verificationState = chatState.openSessionSecurity("@alice:example.org")

        assertEquals(AppNavMode.Main, verificationState.mode)
        assertEquals(AppTab.CHATS, verificationState.selectedTab)
        assertEquals(listOf(AppRoute.Rooms, chatRoute, securityRoute), verificationState.visibleStack)
        assertEquals(chatRoute, verificationState.activeChatRoute)
        assertFalse(verificationState.showsTabs)
        assertEquals(verificationState, verificationState.openSessionSecurity("@alice:example.org"))

        val restoredState = verificationState.popActiveStack()
        requireNotNull(restoredState)
        assertEquals(listOf(AppRoute.Rooms, chatRoute), restoredState.visibleStack)
        assertEquals(chatRoute, restoredState.top)
    }

    @Test
    fun routeForClientState_resetsNavigationWhenLoginIsRequired() {
        val state = AppNavState()
            .enterMain()
            .openChat(roomId = "!room:example.org", displayName = "Room")

        val nextState = state.routeForClientState(
            shouldShowLogin = true,
            recoveryUserId = null
        )

        assertEquals(AppNavMode.Login, nextState.mode)
        assertEquals(AppRoute.Login, nextState.top)
        assertEquals(listOf(AppRoute.Rooms), nextState.chatsStack)
        assertNull(nextState.activeChatRoute)
    }

    @Test
    fun selectTab_switchesVisibleStackAndPreservesOtherStacks() {
        val state = AppNavState()
            .enterMain()
            .openChat(roomId = "!room:example.org", displayName = "Room")
            .closeChat()
            .openChatThemeSettings()
            .selectTab(AppTab.CHATS)

        assertEquals(AppTab.CHATS, state.selectedTab)
        assertEquals(listOf(AppRoute.Rooms), state.visibleStack)
        assertEquals(
            listOf(AppRoute.Profile, AppRoute.Settings, AppRoute.ChatThemeSettings),
            state.profileStack
        )

        val profileState = state.selectTab(AppTab.PROFILE)

        assertEquals(AppTab.PROFILE, profileState.selectedTab)
        assertEquals(
            listOf(AppRoute.Profile, AppRoute.Settings, AppRoute.ChatThemeSettings),
            profileState.visibleStack
        )
    }

    @Test
    fun selectTab_onActiveTabPopsThatTabToRoot() {
        val state = AppNavState()
            .enterMain()
            .openChatThemeSettings()

        val nextState = state.selectTab(AppTab.PROFILE)

        assertEquals(AppTab.PROFILE, nextState.selectedTab)
        assertEquals(listOf(AppRoute.Profile), nextState.profileStack)
        assertEquals(listOf(AppRoute.Profile), nextState.visibleStack)
    }

    @Test
    fun selectTab_isIgnoredWhenTabsAreHidden() {
        val chatRoute = AppRoute.Chat(roomId = "!room:example.org", displayName = "Room")
        val state = AppNavState()
            .enterMain()
            .openChat(chatRoute.roomId, chatRoute.displayName)

        assertFalse(state.showsTabs)

        val nextState = state.selectTab(AppTab.PROFILE)

        assertEquals(state, nextState)
        assertEquals(AppTab.CHATS, nextState.selectedTab)
        assertEquals(listOf(AppRoute.Rooms, chatRoute), nextState.visibleStack)
    }

    @Test
    fun selectTab_isIgnoredOutsideMainMode() {
        val loginState = AppNavState()
        val recoveryState = loginState.routeForClientState(
            shouldShowLogin = false,
            recoveryUserId = "@alice:example.org"
        )

        assertEquals(loginState, loginState.selectTab(AppTab.PROFILE))
        assertEquals(recoveryState, recoveryState.selectTab(AppTab.PROFILE))
    }

    @Test
    fun openAndCloseChat_updatesChatsStackAndActiveChatRoute() {
        val state = AppNavState()
            .enterMain()
            .openChat(roomId = "!room:example.org", displayName = "Room")

        val chatRoute = AppRoute.Chat(roomId = "!room:example.org", displayName = "Room")
        assertEquals(AppTab.CHATS, state.selectedTab)
        assertEquals(listOf(AppRoute.Rooms, chatRoute), state.visibleStack)
        assertEquals(chatRoute, state.activeChatRoute)
        assertFalse(state.showsTabs)

        val closedState = state.closeChat()

        assertEquals(listOf(AppRoute.Rooms), closedState.visibleStack)
        assertNull(closedState.activeChatRoute)
        assertTrue(closedState.showsTabs)
    }

    @Test
    fun roomDetailsPushesOverCurrentChatAndPopsBackToIt() {
        val chatRoute = AppRoute.Chat(roomId = "!room:example.org", displayName = "Room")
        val detailsRoute = AppRoute.RoomDetails(roomId = chatRoute.roomId)
        val state = AppNavState()
            .enterMain()
            .openChat(chatRoute.roomId, chatRoute.displayName)
            .openRoomDetails()

        assertEquals(listOf(AppRoute.Rooms, chatRoute, detailsRoute), state.visibleStack)
        assertEquals(detailsRoute, state.top)
        assertEquals(chatRoute, state.activeChatRoute)
        assertFalse(state.showsTabs)

        val closedState = state.popActiveStack()

        requireNotNull(closedState)
        assertEquals(listOf(AppRoute.Rooms, chatRoute), closedState.visibleStack)
        assertEquals(chatRoute, closedState.activeChatRoute)
    }

    @Test
    fun openRoomDetails_isIgnoredWithoutTopChatRoute() {
        val chatRoute = AppRoute.Chat(roomId = "!room:example.org", displayName = "Room")
        val noChatState = AppNavState().enterMain()
        val forwardPickerState = noChatState
            .openChat(chatRoute.roomId, chatRoute.displayName)
            .openForwardPicker()

        assertEquals(noChatState, noChatState.openRoomDetails())
        assertEquals(forwardPickerState, forwardPickerState.openRoomDetails())
    }

    @Test
    fun roomMembersPushesOverRoomDetailsAndPopsBackToIt() {
        val chatRoute = AppRoute.Chat(roomId = "!room:example.org", displayName = "Room")
        val detailsRoute = AppRoute.RoomDetails(roomId = chatRoute.roomId)
        val membersRoute = AppRoute.RoomMembers(roomId = chatRoute.roomId)
        val state = AppNavState()
            .enterMain()
            .openChat(chatRoute.roomId, chatRoute.displayName)
            .openRoomDetails()
            .openRoomMembers()

        assertEquals(
            listOf(AppRoute.Rooms, chatRoute, detailsRoute, membersRoute),
            state.visibleStack
        )
        assertEquals(membersRoute, state.top)
        assertEquals(chatRoute, state.activeChatRoute)
        assertFalse(state.showsTabs)

        val closedState = state.popActiveStack()

        requireNotNull(closedState)
        assertEquals(listOf(AppRoute.Rooms, chatRoute, detailsRoute), closedState.visibleStack)
        assertEquals(detailsRoute, closedState.top)
    }

    @Test
    fun openRoomMembers_isIgnoredWithoutTopRoomDetailsRoute() {
        val chatState = AppNavState()
            .enterMain()
            .openChat(roomId = "!room:example.org", displayName = "Room")

        assertEquals(chatState, chatState.openRoomMembers())
        assertEquals(AppNavState(), AppNavState().openRoomMembers())
    }

    @Test
    fun memberDetailsPushesFromOwnedMembersAndKeepsRoomOwnersActive() {
        val roomId = "!room:example.org"
        val userId = "@alice:example.org"
        val detailsRoute = AppRoute.RoomDetails(roomId)
        val membersRoute = AppRoute.RoomMembers(roomId)
        val memberRoute = AppRoute.RoomMemberDetails(roomId, userId)
        val state = AppNavState()
            .enterMain()
            .openChat(roomId = roomId, displayName = "Room")
            .openRoomDetails()
            .openRoomMembers()
            .openRoomMemberDetails(userId)

        assertEquals(
            listOf(
                AppRoute.Rooms,
                AppRoute.Chat(roomId, "Room"),
                detailsRoute,
                membersRoute,
                memberRoute
            ),
            state.visibleStack
        )
        assertEquals(detailsRoute, state.activeRoomDetailsRoute)
        assertEquals(membersRoute, state.popActiveStack()?.top)
        assertFalse(state.showsTabs)
    }

    @Test
    fun memberDetailsRequiresOwnedTopMembersRouteAndNonBlankUser() {
        val roomId = "!room:example.org"
        val chatState = AppNavState()
            .enterMain()
            .openChat(roomId = roomId, displayName = "Room")
        val detailsState = chatState.openRoomDetails()
        val malformedMembersState = detailsState.copy(
            chatsStack = detailsState.chatsStack + AppRoute.RoomMembers("!other:example.org")
        )

        assertEquals(chatState, chatState.openRoomMemberDetails("@alice:example.org"))
        assertEquals(detailsState, detailsState.openRoomMemberDetails("@alice:example.org"))
        assertEquals(
            detailsState.openRoomMembers(),
            detailsState.openRoomMembers().openRoomMemberDetails(" ")
        )
        assertEquals(
            malformedMembersState,
            malformedMembersState.openRoomMemberDetails("@alice:example.org")
        )
    }

    @Test
    fun roomPermissionsOwnsDetailsAndCanOpenRoleManagement() {
        val roomId = "!room:example.org"
        val detailsRoute = AppRoute.RoomDetails(roomId)
        val permissionsRoute = AppRoute.RoomPermissions(roomId)
        val rolesRoute = AppRoute.RoomRoleManagement(roomId)
        val permissionsState = AppNavState()
            .enterMain()
            .openChat(roomId = roomId, displayName = "Room")
            .openRoomDetails()
            .openRoomPermissions()

        assertEquals(permissionsRoute, permissionsState.top)
        assertEquals(detailsRoute, permissionsState.activeRoomDetailsRoute)
        assertFalse(permissionsState.showsTabs)

        val rolesState = permissionsState.openRoomRoleManagement()

        assertEquals(rolesRoute, rolesState.top)
        assertEquals(detailsRoute, rolesState.activeRoomDetailsRoute)
        assertEquals(permissionsRoute, rolesState.activeRoomPermissionsRoute)
        assertEquals(permissionsRoute, rolesState.popActiveStack()?.top)
        assertEquals(permissionsState, permissionsState.openRoomMembers())
    }

    @Test
    fun openRoomPermissionsRequiresTheOwnedTopDetailsRoute() {
        val roomId = "!room:example.org"
        val chatState = AppNavState()
            .enterMain()
            .openChat(roomId = roomId, displayName = "Room")
        val membersState = chatState.openRoomDetails().openRoomMembers()

        assertEquals(chatState, chatState.openRoomPermissions())
        assertEquals(membersState, membersState.openRoomPermissions())
        assertEquals(AppNavState(), AppNavState().openRoomPermissions())
    }

    @Test
    fun openRoomRoleManagementRequiresOwnedTopPermissionsRoute() {
        val roomId = "!room:example.org"
        val chatState = AppNavState()
            .enterMain()
            .openChat(roomId = roomId, displayName = "Room")
        val detailsState = chatState.openRoomDetails()
        val permissionsState = detailsState.openRoomPermissions()

        assertEquals(chatState, chatState.openRoomRoleManagement())
        assertEquals(detailsState, detailsState.openRoomRoleManagement())
        assertEquals(
            AppRoute.RoomRoleManagement(roomId),
            permissionsState.openRoomRoleManagement().top
        )
    }

    @Test
    fun editRoomProfilePushesOverDetailsAndKeepsDetailsOwnerActive() {
        val roomId = "!room:example.org"
        val detailsState = AppNavState()
            .enterMain()
            .openChat(roomId = roomId, displayName = "Room")
            .openRoomDetails()

        val editState = detailsState.openEditRoomProfile()

        assertEquals(AppRoute.EditRoomProfile(roomId), editState.top)
        assertEquals(AppRoute.RoomDetails(roomId), editState.activeRoomDetailsRoute)
        assertEquals(AppRoute.RoomDetails(roomId), editState.popActiveStack()?.top)
        assertFalse(editState.showsTabs)
    }

    @Test
    fun openEditRoomProfileIsIgnoredWithoutTopRoomDetailsRoute() {
        val chatState = AppNavState()
            .enterMain()
            .openChat(roomId = "!room:example.org", displayName = "Room")

        assertEquals(chatState, chatState.openEditRoomProfile())
        assertEquals(AppNavState(), AppNavState().openEditRoomProfile())
    }

    @Test
    fun inviteMembersPushesFromDetailsAndMembersAndPopsToItsOwner() {
        val roomId = "!room:example.org"
        val detailsState = AppNavState()
            .enterMain()
            .openChat(roomId = roomId, displayName = "Room")
            .openRoomDetails()
        val inviteRoute = AppRoute.InviteRoomMembers(roomId)

        val fromDetails = detailsState.openInviteRoomMembers()
        assertEquals(inviteRoute, fromDetails.top)
        assertEquals(AppRoute.RoomDetails(roomId), fromDetails.activeRoomDetailsRoute)
        assertEquals(AppRoute.RoomDetails(roomId), fromDetails.popActiveStack()?.top)

        val fromMembers = detailsState.openRoomMembers().openInviteRoomMembers()
        assertEquals(inviteRoute, fromMembers.top)
        assertEquals(AppRoute.RoomDetails(roomId), fromMembers.activeRoomDetailsRoute)
        assertEquals(AppRoute.RoomMembers(roomId), fromMembers.popActiveStack()?.top)
        assertFalse(fromMembers.showsTabs)
    }

    @Test
    fun openInviteMembers_isIgnoredWithoutOwnedDetailsOrMembersRoute() {
        val chatState = AppNavState()
            .enterMain()
            .openChat(roomId = "!room:example.org", displayName = "Room")
        val malformedMembersState = chatState.copy(
            chatsStack = chatState.chatsStack + AppRoute.RoomMembers("!other:example.org")
        )

        assertEquals(chatState, chatState.openInviteRoomMembers())
        assertEquals(malformedMembersState, malformedMembersState.openInviteRoomMembers())
        assertEquals(AppNavState(), AppNavState().openInviteRoomMembers())
        assertNull(chatState.activeRoomDetailsRoute)
        assertNull(malformedMembersState.activeRoomDetailsRoute)
    }

    @Test
    fun userProfilePushesOnContactsStackAndHidesTabs() {
        val profileRoute = AppRoute.UserProfile(userId = "@alice:example.org")
        val state = AppNavState()
            .enterMain()
            .selectTab(AppTab.CONTACTS)
            .openUserProfile(profileRoute.userId)

        assertEquals(AppTab.CONTACTS, state.selectedTab)
        assertEquals(listOf(AppRoute.Contacts, profileRoute), state.visibleStack)
        assertEquals(profileRoute, state.top)
        assertFalse(state.showsTabs)

        val closedState = state.popActiveStack()

        requireNotNull(closedState)
        assertEquals(AppTab.CONTACTS, closedState.selectedTab)
        assertEquals(listOf(AppRoute.Contacts), closedState.visibleStack)
        assertTrue(closedState.showsTabs)
    }

    @Test
    fun userProfilePushesOverRoomDetailsWithoutLosingActiveChat() {
        val chatRoute = AppRoute.Chat(roomId = "!room:example.org", displayName = "Room")
        val detailsRoute = AppRoute.RoomDetails(roomId = chatRoute.roomId)
        val profileRoute = AppRoute.UserProfile(userId = "@alice:example.org")
        val state = AppNavState()
            .enterMain()
            .openChat(chatRoute.roomId, chatRoute.displayName)
            .openRoomDetails()
            .openUserProfile(profileRoute.userId)

        assertEquals(AppTab.CHATS, state.selectedTab)
        assertEquals(listOf(AppRoute.Rooms, chatRoute, detailsRoute, profileRoute), state.visibleStack)
        assertEquals(profileRoute, state.top)
        assertEquals(chatRoute, state.activeChatRoute)
        assertFalse(state.showsTabs)

        val closedState = state.popActiveStack()

        requireNotNull(closedState)
        assertEquals(listOf(AppRoute.Rooms, chatRoute, detailsRoute), closedState.visibleStack)
        assertEquals(detailsRoute, closedState.top)
        assertEquals(chatRoute, closedState.activeChatRoute)
    }

    @Test
    fun userProfileSelectionIsIgnoredOutsideMainMode() {
        val loginState = AppNavState()
        val recoveryState = loginState.routeForClientState(
            shouldShowLogin = false,
            recoveryUserId = "@alice:example.org"
        )

        assertEquals(loginState, loginState.openUserProfile("@bob:example.org"))
        assertEquals(recoveryState, recoveryState.openUserProfile("@bob:example.org"))
    }

    @Test
    fun forwardPickerPushesOverCurrentChatAndPopsBackToIt() {
        val chatRoute = AppRoute.Chat(roomId = "!room:example.org", displayName = "Room")
        val state = AppNavState()
            .enterMain()
            .openChat(chatRoute.roomId, chatRoute.displayName)
            .openForwardPicker()

        assertEquals(listOf(AppRoute.Rooms, chatRoute, AppRoute.ForwardPicker), state.visibleStack)
        assertEquals(AppRoute.ForwardPicker, state.top)
        assertEquals(chatRoute, state.activeChatRoute)
        assertFalse(state.showsTabs)

        val closedState = state.closeForwardPicker()

        assertEquals(listOf(AppRoute.Rooms, chatRoute), closedState.visibleStack)
        assertEquals(chatRoute, closedState.activeChatRoute)
    }

    @Test
    fun openForwardPicker_isIdempotentWhenAlreadyTopRoute() {
        val chatRoute = AppRoute.Chat(roomId = "!room:example.org", displayName = "Room")
        val state = AppNavState()
            .enterMain()
            .openChat(chatRoute.roomId, chatRoute.displayName)
            .openForwardPicker()

        val nextState = state.openForwardPicker()

        assertEquals(state, nextState)
        assertEquals(
            listOf(AppRoute.Rooms, chatRoute, AppRoute.ForwardPicker),
            nextState.visibleStack
        )
        assertEquals(1, nextState.visibleStack.count { it == AppRoute.ForwardPicker })
    }

    @Test
    fun profileThemeSettingsPushesAndPopsOnProfileStack() {
        val state = AppNavState()
            .enterMain()
            .openChatThemeSettings()

        assertEquals(AppTab.PROFILE, state.selectedTab)
        assertEquals(
            listOf(AppRoute.Profile, AppRoute.Settings, AppRoute.ChatThemeSettings),
            state.visibleStack
        )
        assertTrue(state.showsTabs)

        val nextState = state.popActiveStack()

        requireNotNull(nextState)
        assertEquals(AppTab.PROFILE, nextState.selectedTab)
        assertEquals(listOf(AppRoute.Profile, AppRoute.Settings), nextState.visibleStack)
    }

    @Test
    fun editProfilePushesAndPopsOnProfileStack() {
        val state = AppNavState()
            .enterMain()
            .openEditProfile()

        assertEquals(AppTab.PROFILE, state.selectedTab)
        assertEquals(listOf(AppRoute.Profile, AppRoute.EditProfile), state.visibleStack)
        assertTrue(state.showsTabs)

        val nextState = state.popActiveStack()

        requireNotNull(nextState)
        assertEquals(AppTab.PROFILE, nextState.selectedTab)
        assertEquals(listOf(AppRoute.Profile), nextState.visibleStack)
    }

    @Test
    fun closeEditProfileClosesProfileStackEvenWhenAnotherTabIsSelected() {
        val state = AppNavState()
            .enterMain()
            .openEditProfile()
            .selectTab(AppTab.CHATS)

        assertEquals(AppTab.CHATS, state.selectedTab)
        assertEquals(listOf(AppRoute.Profile, AppRoute.EditProfile), state.profileStack)

        val nextState = state.closeEditProfile()

        assertEquals(AppTab.CHATS, nextState.selectedTab)
        assertEquals(listOf(AppRoute.Profile), nextState.profileStack)
        assertEquals(listOf(AppRoute.Rooms), nextState.visibleStack)
    }

    @Test
    fun exitEditProfileBackClosesOnlyTheEditRoute() {
        val state = AppNavState()
            .enterMain()
            .openEditProfile()

        val nextState = state.exitEditProfile(EditProfileExitDestination.Back)

        assertEquals(AppTab.PROFILE, nextState.selectedTab)
        assertEquals(listOf(AppRoute.Profile), nextState.profileStack)
        assertEquals(AppRoute.Profile, nextState.top)
    }

    @Test
    fun exitEditProfileTabClosesTheEditRouteBeforeSelectingDestination() {
        val state = AppNavState()
            .enterMain()
            .openEditProfile()

        val nextState = state.exitEditProfile(
            EditProfileExitDestination.Tab(AppTab.CONTACTS)
        )

        assertEquals(AppTab.CONTACTS, nextState.selectedTab)
        assertEquals(listOf(AppRoute.Profile), nextState.profileStack)
        assertEquals(AppRoute.Contacts, nextState.top)
    }

    @Test
    fun editProfileExitRequestsConfirmationOnlyForUnsavedDraft() {
        assertEquals(
            EditProfileExitDecision.REQUEST_CONFIRMATION,
            editProfileExitDecision(
                route = AppRoute.EditProfile,
                isSaving = false,
                hasUnsavedChanges = true
            )
        )
        assertEquals(
            EditProfileExitDecision.EXIT,
            editProfileExitDecision(
                route = AppRoute.EditProfile,
                isSaving = false,
                hasUnsavedChanges = false
            )
        )
    }

    @Test
    fun editProfileExitIsBlockedWhileSavingAndIgnoredOnOtherRoutes() {
        assertEquals(
            EditProfileExitDecision.BLOCKED_WHILE_SAVING,
            editProfileExitDecision(
                route = AppRoute.EditProfile,
                isSaving = true,
                hasUnsavedChanges = true
            )
        )
        assertEquals(
            EditProfileExitDecision.NOT_APPLICABLE,
            editProfileExitDecision(
                route = AppRoute.Profile,
                isSaving = false,
                hasUnsavedChanges = true
            )
        )
    }

    @Test
    fun routeScopedEditProfileExitIsClearedWhenExternalNavigationOpensChat() {
        val editNavState = AppNavState()
            .enterMain()
            .openEditProfile()
        val state = AppUiState(
            navState = editNavState,
            pendingEditProfileExit = PendingEditProfileExit(
                destination = EditProfileExitDestination.Tab(AppTab.CONTACTS),
                editSessionId = 17L
            )
        )

        val nextState = state.withNavigationState(
            editNavState.openChat(roomId = "!room:example.org", displayName = "Room")
        )

        assertEquals(AppRoute.Chat("!room:example.org", "Room"), nextState.route)
        assertNull(nextState.pendingEditProfileExit)
    }

    @Test
    fun routeScopedEditProfileExitDoesNotReturnAfterSecurityRouteCloses() {
        val editNavState = AppNavState()
            .enterMain()
            .openEditProfile()
        val state = AppUiState(
            navState = editNavState,
            pendingEditProfileExit = PendingEditProfileExit(
                destination = EditProfileExitDestination.Back,
                editSessionId = 23L
            )
        )

        val securityState = state.withNavigationState(
            editNavState.openSessionSecurity("@alice:example.org")
        )
        val returnedState = securityState.withNavigationState(
            requireNotNull(securityState.navState.popActiveStack())
        )

        assertEquals(AppRoute.EditProfile, returnedState.route)
        assertNull(returnedState.pendingEditProfileExit)
    }

    @Test
    fun routeScopedEditProfileExitSurvivesUnrelatedStateUpdatesOnItsOwner() {
        val editNavState = AppNavState()
            .enterMain()
            .openEditProfile()
        val pending = PendingEditProfileExit(
            destination = EditProfileExitDestination.Back,
            editSessionId = 31L
        )
        val state = AppUiState(
            navState = editNavState,
            pendingEditProfileExit = pending
        )

        val nextState = state.withNavigationState(editNavState)

        assertEquals(pending, nextState.pendingEditProfileExit)
    }

    @Test
    fun popActiveStack_returnsToChatsFromNonChatRootAndStopsAtChatsRoot() {
        val profileRootState = AppNavState()
            .enterMain()
            .selectTab(AppTab.PROFILE)

        val chatsState = profileRootState.popActiveStack()

        requireNotNull(chatsState)
        assertEquals(AppTab.CHATS, chatsState.selectedTab)
        assertEquals(listOf(AppRoute.Rooms), chatsState.visibleStack)
        assertNull(chatsState.popActiveStack())
    }

    @Test
    fun popActiveStack_returnsToChatsFromContactsAndCallsRoots() {
        listOf(AppTab.CONTACTS, AppTab.CALLS).forEach { tab ->
            val tabRootState = AppNavState()
                .enterMain()
                .selectTab(tab)

            val chatsState = tabRootState.popActiveStack()

            requireNotNull(chatsState)
            assertEquals(AppTab.CHATS, chatsState.selectedTab)
            assertEquals(listOf(AppRoute.Rooms), chatsState.visibleStack)
        }
    }
}
