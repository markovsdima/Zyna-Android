package com.zyna.app.ui.app

enum class AppTab {
    CONTACTS,
    CALLS,
    CHATS,
    PROFILE
}

sealed interface EditProfileExitDestination {
    data object Back : EditProfileExitDestination
    data class Tab(val tab: AppTab) : EditProfileExitDestination
}

internal enum class EditProfileExitDecision {
    NOT_APPLICABLE,
    BLOCKED_WHILE_SAVING,
    REQUEST_CONFIRMATION,
    EXIT
}

internal fun editProfileExitDecision(
    route: AppRoute,
    isSaving: Boolean,
    hasUnsavedChanges: Boolean
): EditProfileExitDecision {
    if (route != AppRoute.EditProfile) {
        return EditProfileExitDecision.NOT_APPLICABLE
    }
    if (isSaving) {
        return EditProfileExitDecision.BLOCKED_WHILE_SAVING
    }
    return if (hasUnsavedChanges) {
        EditProfileExitDecision.REQUEST_CONFIRMATION
    } else {
        EditProfileExitDecision.EXIT
    }
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
    data class UserProfile(
        val userId: String
    ) : AppRoute
    data object Calls : AppRoute
    data object Rooms : AppRoute
    data object CreateRoom : AppRoute
    data object ForwardPicker : AppRoute
    data object Profile : AppRoute
    data object EditProfile : AppRoute
    data object Settings : AppRoute
    data class SessionSecurity(val userId: String) : AppRoute
    data object ChatThemeSettings : AppRoute
    data class RoomDetails(
        val roomId: String
    ) : AppRoute
    data class EditRoomProfile(
        val roomId: String
    ) : AppRoute
    data class RoomMembers(
        val roomId: String
    ) : AppRoute
    data class RoomMemberDetails(
        val roomId: String,
        val userId: String
    ) : AppRoute
    data class RoomPermissions(
        val roomId: String
    ) : AppRoute
    data class RoomRoleManagement(
        val roomId: String
    ) : AppRoute
    data class InviteRoomMembers(
        val roomId: String
    ) : AppRoute
    data class InviteCreatedRoomMembers(
        val roomId: String,
        val displayName: String,
        val avatarUrl: String?
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

    val activeRoomDetailsRoute: AppRoute.RoomDetails?
        get() {
            if (mode != AppNavMode.Main || selectedTab != AppTab.CHATS) {
                return null
            }
            val roomId = when (val topRoute = chatsStack.lastOrNull()) {
                is AppRoute.RoomDetails -> topRoute.roomId
                is AppRoute.EditRoomProfile -> topRoute.roomId
                is AppRoute.RoomMembers -> topRoute.roomId
                is AppRoute.RoomMemberDetails -> topRoute.roomId
                is AppRoute.RoomPermissions -> topRoute.roomId
                is AppRoute.RoomRoleManagement -> topRoute.roomId
                is AppRoute.InviteRoomMembers -> topRoute.roomId
                else -> return null
            }
            return chatsStack
                .filterIsInstance<AppRoute.RoomDetails>()
                .lastOrNull { detailsRoute -> detailsRoute.roomId == roomId }
        }

    val activeRoomPermissionsRoute: AppRoute.RoomPermissions?
        get() {
            if (mode != AppNavMode.Main || selectedTab != AppTab.CHATS) {
                return null
            }
            val roomId = when (val topRoute = chatsStack.lastOrNull()) {
                is AppRoute.RoomPermissions -> topRoute.roomId
                is AppRoute.RoomRoleManagement -> topRoute.roomId
                else -> return null
            }
            return chatsStack
                .filterIsInstance<AppRoute.RoomPermissions>()
                .lastOrNull { permissionsRoute -> permissionsRoute.roomId == roomId }
        }

    val showsTabs: Boolean
        get() {
            if (mode != AppNavMode.Main) {
                return false
            }
            if (top is AppRoute.UserProfile || top is AppRoute.SessionSecurity) {
                return false
            }
            if (selectedTab == AppTab.CONTACTS && contactsStack.lastOrNull() != AppRoute.Contacts) {
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

    fun openCreateRoom(): AppNavState {
        if (mode != AppNavMode.Main || selectedTab != AppTab.CHATS) {
            return this
        }
        val rootedStack = ensureChatsRoot(chatsStack)
        if (rootedStack.lastOrNull() != AppRoute.Rooms) {
            return this
        }
        return copy(chatsStack = rootedStack + AppRoute.CreateRoom)
    }

    fun openCreatedRoomInvites(
        roomId: String,
        displayName: String,
        avatarUrl: String?
    ): AppNavState {
        if (
            mode != AppNavMode.Main ||
            selectedTab != AppTab.CHATS ||
            chatsStack.lastOrNull() != AppRoute.CreateRoom ||
            roomId.isBlank()
        ) {
            return this
        }
        return copy(
            chatsStack = chatsStack.dropLast(1) + AppRoute.InviteCreatedRoomMembers(
                roomId = roomId,
                displayName = displayName,
                avatarUrl = avatarUrl
            )
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

    fun openUserProfile(userId: String): AppNavState {
        if (mode != AppNavMode.Main || userId.isBlank()) {
            return this
        }
        val route = AppRoute.UserProfile(userId = userId)
        return when (selectedTab) {
            AppTab.CONTACTS -> copy(
                contactsStack = pushUserProfile(
                    stack = contactsStack,
                    root = AppRoute.Contacts,
                    route = route
                )
            )
            AppTab.CALLS -> copy(
                callsStack = pushUserProfile(
                    stack = callsStack,
                    root = AppRoute.Calls,
                    route = route
                )
            )
            AppTab.CHATS -> copy(
                chatsStack = pushUserProfile(
                    stack = chatsStack,
                    root = AppRoute.Rooms,
                    route = route
                )
            )
            AppTab.PROFILE -> copy(
                profileStack = pushUserProfile(
                    stack = profileStack,
                    root = AppRoute.Profile,
                    route = route
                )
            )
        }
    }

    fun openRoomDetails(): AppNavState {
        if (mode != AppNavMode.Main || selectedTab != AppTab.CHATS) {
            return this
        }
        val chatRoute = chatsStack.lastOrNull() as? AppRoute.Chat ?: return this
        return copy(chatsStack = chatsStack + AppRoute.RoomDetails(chatRoute.roomId))
    }

    fun openRoomMembers(): AppNavState {
        if (mode != AppNavMode.Main || selectedTab != AppTab.CHATS) {
            return this
        }
        val roomId = when (val topRoute = chatsStack.lastOrNull()) {
            is AppRoute.RoomDetails -> topRoute.roomId
            else -> return this
        }
        val ownsRoom = chatsStack.any { route ->
            route is AppRoute.RoomDetails && route.roomId == roomId
        }
        if (!ownsRoom) return this
        return copy(chatsStack = chatsStack + AppRoute.RoomMembers(roomId))
    }

    fun openEditRoomProfile(): AppNavState {
        if (mode != AppNavMode.Main || selectedTab != AppTab.CHATS) {
            return this
        }
        val detailsRoute = chatsStack.lastOrNull() as? AppRoute.RoomDetails ?: return this
        return copy(chatsStack = chatsStack + AppRoute.EditRoomProfile(detailsRoute.roomId))
    }

    fun openRoomMemberDetails(userId: String): AppNavState {
        if (
            mode != AppNavMode.Main ||
            selectedTab != AppTab.CHATS ||
            userId.isBlank()
        ) {
            return this
        }
        val membersRoute = chatsStack.lastOrNull() as? AppRoute.RoomMembers ?: return this
        val ownsRoom = chatsStack.any { route ->
            route is AppRoute.RoomDetails && route.roomId == membersRoute.roomId
        }
        if (!ownsRoom) return this
        return copy(
            chatsStack = chatsStack + AppRoute.RoomMemberDetails(
                roomId = membersRoute.roomId,
                userId = userId
            )
        )
    }

    fun openRoomPermissions(): AppNavState {
        if (mode != AppNavMode.Main || selectedTab != AppTab.CHATS) {
            return this
        }
        val detailsRoute = chatsStack.lastOrNull() as? AppRoute.RoomDetails ?: return this
        return copy(chatsStack = chatsStack + AppRoute.RoomPermissions(detailsRoute.roomId))
    }

    fun openRoomRoleManagement(): AppNavState {
        if (mode != AppNavMode.Main || selectedTab != AppTab.CHATS) {
            return this
        }
        val permissionsRoute =
            chatsStack.lastOrNull() as? AppRoute.RoomPermissions ?: return this
        val ownsRoom = chatsStack.any { route ->
            route is AppRoute.RoomDetails && route.roomId == permissionsRoute.roomId
        }
        if (!ownsRoom) return this
        return copy(
            chatsStack = chatsStack + AppRoute.RoomRoleManagement(permissionsRoute.roomId)
        )
    }

    fun openInviteRoomMembers(): AppNavState {
        if (mode != AppNavMode.Main || selectedTab != AppTab.CHATS) {
            return this
        }
        val roomId = when (val route = chatsStack.lastOrNull()) {
            is AppRoute.RoomDetails -> route.roomId
            is AppRoute.RoomMembers -> route.roomId
            else -> return this
        }
        val ownsRoom = chatsStack.any { route ->
            route is AppRoute.RoomDetails && route.roomId == roomId
        }
        if (!ownsRoom) {
            return this
        }
        return copy(chatsStack = chatsStack + AppRoute.InviteRoomMembers(roomId))
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

    fun exitEditProfile(destination: EditProfileExitDestination): AppNavState {
        val closed = closeEditProfile()
        return when (destination) {
            EditProfileExitDestination.Back -> closed
            is EditProfileExitDestination.Tab -> closed.selectTab(destination.tab)
        }
    }

    fun openChatThemeSettings(): AppNavState {
        return copy(
            mode = AppNavMode.Main,
            selectedTab = AppTab.PROFILE,
            profileStack = listOf(AppRoute.Profile, AppRoute.Settings, AppRoute.ChatThemeSettings)
        )
    }

    fun openSessionSecurity(userId: String): AppNavState {
        if (mode != AppNavMode.Main || userId.isBlank()) return this
        val route = AppRoute.SessionSecurity(userId)
        return when (selectedTab) {
            AppTab.CONTACTS -> copy(
                contactsStack = contactsStack.pushSingleTop(route)
            )
            AppTab.CALLS -> copy(
                callsStack = callsStack.pushSingleTop(route)
            )
            AppTab.CHATS -> copy(
                chatsStack = chatsStack.pushSingleTop(route)
            )
            AppTab.PROFILE -> copy(
                profileStack = profileStack.pushSingleTop(route)
            )
        }
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

    private fun List<AppRoute>.pushSingleTop(route: AppRoute): List<AppRoute> {
        return if (lastOrNull() == route) this else this + route
    }

    private fun ensureChatsRoot(stack: List<AppRoute>): List<AppRoute> {
        return if (stack.firstOrNull() == AppRoute.Rooms) {
            stack
        } else {
            listOf(AppRoute.Rooms) + stack
        }
    }

    private fun pushUserProfile(
        stack: List<AppRoute>,
        root: AppRoute,
        route: AppRoute.UserProfile
    ): List<AppRoute> {
        val rootedStack = if (stack.firstOrNull() == root) {
            stack
        } else {
            listOf(root) + stack
        }
        val baseStack = if (rootedStack.lastOrNull() is AppRoute.UserProfile) {
            rootedStack.dropLast(1)
        } else {
            rootedStack
        }
        return baseStack + route
    }
}
