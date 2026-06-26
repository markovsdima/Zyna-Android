package com.zyna.app.ui.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavStateTest {
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
        assertEquals(listOf(AppRoute.Settings, AppRoute.ChatThemeSettings), nextState.visibleStack)
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
        assertEquals(listOf(AppRoute.Settings, AppRoute.ChatThemeSettings), state.profileStack)

        val profileState = state.selectTab(AppTab.PROFILE)

        assertEquals(AppTab.PROFILE, profileState.selectedTab)
        assertEquals(listOf(AppRoute.Settings, AppRoute.ChatThemeSettings), profileState.visibleStack)
    }

    @Test
    fun selectTab_onActiveTabPopsThatTabToRoot() {
        val state = AppNavState()
            .enterMain()
            .openChatThemeSettings()

        val nextState = state.selectTab(AppTab.PROFILE)

        assertEquals(AppTab.PROFILE, nextState.selectedTab)
        assertEquals(listOf(AppRoute.Settings), nextState.profileStack)
        assertEquals(listOf(AppRoute.Settings), nextState.visibleStack)
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
        assertEquals(listOf(AppRoute.Settings, AppRoute.ChatThemeSettings), state.visibleStack)
        assertTrue(state.showsTabs)

        val nextState = state.popActiveStack()

        requireNotNull(nextState)
        assertEquals(AppTab.PROFILE, nextState.selectedTab)
        assertEquals(listOf(AppRoute.Settings), nextState.visibleStack)
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
