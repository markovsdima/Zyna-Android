package com.zyna.app.ui.app

enum class AppTab {
    CONTACTS,
    CALLS,
    CHATS,
    PROFILE
}

sealed interface AppNavMode {
    data object Login : AppNavMode
    data class RecoveryKey(val userId: String) : AppNavMode
    data object Main : AppNavMode
}

sealed interface AppRoute {
    data object Login : AppRoute
    data class RecoveryKey(val userId: String) : AppRoute
    data object Contacts : AppRoute
    data object Calls : AppRoute
    data object Rooms : AppRoute
    data object ForwardPicker : AppRoute
    data object Profile : AppRoute
    data object EditProfile : AppRoute
    data object Settings : AppRoute
    data object ChatThemeSettings : AppRoute
    data class RoomDetails(
        val roomId: String
    ) : AppRoute
    data class Chat(
        val roomId: String,
        val displayName: String
    ) : AppRoute
}

data class AppNavState(
    val mode: AppNavMode = AppNavMode.Login,
    val selectedTab: AppTab = AppTab.CHATS,
    val contactsStack: List<AppRoute> = listOf(AppRoute.Contacts),
    val callsStack: List<AppRoute> = listOf(AppRoute.Calls),
    val chatsStack: List<AppRoute> = listOf(AppRoute.Rooms),
    val profileStack: List<AppRoute> = listOf(AppRoute.Profile)
) {
    val visibleStack: List<AppRoute>
        get() = when (val currentMode = mode) {
            AppNavMode.Login -> listOf(AppRoute.Login)
            is AppNavMode.RecoveryKey -> listOf(AppRoute.RecoveryKey(currentMode.userId))
            AppNavMode.Main -> stackFor(selectedTab)
        }

    val top: AppRoute
        get() = visibleStack.last()

    val activeChatRoute: AppRoute.Chat?
        get() {
            if (mode != AppNavMode.Main || selectedTab != AppTab.CHATS) {
                return null
            }
            return chatsStack.filterIsInstance<AppRoute.Chat>().lastOrNull()
        }

    val showsTabs: Boolean
        get() {
            if (mode != AppNavMode.Main) {
                return false
            }
            return selectedTab != AppTab.CHATS || chatsStack.lastOrNull() == AppRoute.Rooms
        }

    val isChatsRoot: Boolean
        get() = mode == AppNavMode.Main &&
            selectedTab == AppTab.CHATS &&
            chatsStack.lastOrNull() == AppRoute.Rooms

    fun routeForClientState(
        shouldShowLogin: Boolean,
        recoveryUserId: String?
    ): AppNavState {
        if (shouldShowLogin) {
            return AppNavState(mode = AppNavMode.Login)
        }
        if (recoveryUserId != null) {
            return AppNavState(mode = AppNavMode.RecoveryKey(recoveryUserId))
        }
        return if (mode == AppNavMode.Login || mode is AppNavMode.RecoveryKey) {
            enterMain()
        } else {
            this
        }
    }

    fun enterMain(): AppNavState {
        return copy(mode = AppNavMode.Main, selectedTab = AppTab.CHATS)
    }

    fun selectTab(tab: AppTab): AppNavState {
        if (mode != AppNavMode.Main || !showsTabs) {
            return this
        }
        return if (selectedTab == tab) {
            popSelectedTabToRoot()
        } else {
            copy(selectedTab = tab)
        }
    }

    fun openChat(roomId: String, displayName: String): AppNavState {
        return copy(
            mode = AppNavMode.Main,
            selectedTab = AppTab.CHATS,
            chatsStack = listOf(
                AppRoute.Rooms,
                AppRoute.Chat(roomId = roomId, displayName = displayName)
            )
        )
    }

    fun closeChat(): AppNavState {
        return copy(
            mode = AppNavMode.Main,
            selectedTab = AppTab.CHATS,
            chatsStack = listOf(AppRoute.Rooms)
        )
    }

    fun openForwardPicker(): AppNavState {
        if (mode != AppNavMode.Main) {
            return this
        }
        val baseStack = ensureChatsRoot(chatsStack)
        if (baseStack.lastOrNull() == AppRoute.ForwardPicker) {
            return copy(selectedTab = AppTab.CHATS, chatsStack = baseStack)
        }
        return copy(
            selectedTab = AppTab.CHATS,
            chatsStack = baseStack + AppRoute.ForwardPicker
        )
    }

    fun closeForwardPicker(): AppNavState {
        if (chatsStack.lastOrNull() != AppRoute.ForwardPicker) {
            return this
        }
        return copy(chatsStack = chatsStack.dropLast(1).ifEmpty { listOf(AppRoute.Rooms) })
    }

    fun openRoomDetails(): AppNavState {
        if (mode != AppNavMode.Main || selectedTab != AppTab.CHATS) {
            return this
        }
        val chatRoute = chatsStack.lastOrNull() as? AppRoute.Chat ?: return this
        return copy(chatsStack = chatsStack + AppRoute.RoomDetails(chatRoute.roomId))
    }

    fun openProfileSettings(): AppNavState {
        return copy(
            mode = AppNavMode.Main,
            selectedTab = AppTab.PROFILE,
            profileStack = listOf(AppRoute.Profile, AppRoute.Settings)
        )
    }

    fun openEditProfile(): AppNavState {
        return copy(
            mode = AppNavMode.Main,
            selectedTab = AppTab.PROFILE,
            profileStack = listOf(AppRoute.Profile, AppRoute.EditProfile)
        )
    }

    fun closeEditProfile(): AppNavState {
        if (profileStack.lastOrNull() != AppRoute.EditProfile) {
            return this
        }
        return copy(profileStack = profileStack.dropLast(1).ifEmpty { listOf(AppRoute.Profile) })
    }

    fun openChatThemeSettings(): AppNavState {
        return copy(
            mode = AppNavMode.Main,
            selectedTab = AppTab.PROFILE,
            profileStack = listOf(AppRoute.Profile, AppRoute.Settings, AppRoute.ChatThemeSettings)
        )
    }

    fun popActiveStack(): AppNavState? {
        if (mode != AppNavMode.Main) {
            return null
        }
        return when (selectedTab) {
            AppTab.CONTACTS -> popStack(contactsStack) {
                copy(contactsStack = it)
            } ?: copy(selectedTab = AppTab.CHATS)
            AppTab.CALLS -> popStack(callsStack) {
                copy(callsStack = it)
            } ?: copy(selectedTab = AppTab.CHATS)
            AppTab.CHATS -> popStack(chatsStack) {
                copy(chatsStack = it)
            }
            AppTab.PROFILE -> popStack(profileStack) {
                copy(profileStack = it)
            } ?: copy(selectedTab = AppTab.CHATS)
        }
    }

    fun stackFor(tab: AppTab): List<AppRoute> {
        return when (tab) {
            AppTab.CONTACTS -> contactsStack.ifEmpty { listOf(AppRoute.Contacts) }
            AppTab.CALLS -> callsStack.ifEmpty { listOf(AppRoute.Calls) }
            AppTab.CHATS -> chatsStack.ifEmpty { listOf(AppRoute.Rooms) }
            AppTab.PROFILE -> profileStack.ifEmpty { listOf(AppRoute.Profile) }
        }
    }

    private fun popSelectedTabToRoot(): AppNavState {
        return when (selectedTab) {
            AppTab.CONTACTS -> copy(contactsStack = listOf(AppRoute.Contacts))
            AppTab.CALLS -> copy(callsStack = listOf(AppRoute.Calls))
            AppTab.CHATS -> copy(chatsStack = listOf(AppRoute.Rooms))
            AppTab.PROFILE -> copy(profileStack = listOf(AppRoute.Profile))
        }
    }

    private fun popStack(
        stack: List<AppRoute>,
        update: (List<AppRoute>) -> AppNavState
    ): AppNavState? {
        if (stack.size <= 1) {
            return null
        }
        return update(stack.dropLast(1))
    }

    private fun ensureChatsRoot(stack: List<AppRoute>): List<AppRoute> {
        return if (stack.firstOrNull() == AppRoute.Rooms) {
            stack
        } else {
            listOf(AppRoute.Rooms) + stack
        }
    }
}
