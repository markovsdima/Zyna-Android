package com.zyna.app.ui.navigation

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import com.zyna.app.BuildConfig
import com.zyna.app.ui.app.AppRoute
import com.zyna.app.ui.app.AppUiState
import com.zyna.app.ui.auth.LoginScreen
import com.zyna.app.ui.chat.ChatScreenView
import com.zyna.app.ui.chat.ChatScreenViewActions
import com.zyna.app.ui.chat.ChatScreenViewState
import com.zyna.app.ui.glass.VulkanChatOverlayView
import com.zyna.app.ui.rooms.RoomsScreen
import com.zyna.app.ui.security.RecoveryKeyScreen
import com.zyna.app.ui.theme.ZynaAndroidTheme
import com.zyna.app.util.ZynaPerfLog

class ZynaRootHostView(context: Context) : FrameLayout(context) {
    private val navigationStack = ZynaNavigationStackView(context)
    private val vulkanOverlayHost = VulkanChatOverlayView(context).apply {
        setOverlayEnabled(BuildConfig.VULKAN_CHAT_GLASS_ENABLED)
    }
    private val foregroundContainer = FrameLayout(context).apply {
        clipChildren = false
        clipToPadding = false
    }
    private val chatOverlayContainer = FrameLayout(context)
    private val overlayContainer = FrameLayout(context)
    private val tabBar = ZynaTabBarView(context)
    private var bottomInset = 0
    private var lastRouteKey: String? = null
    private var latestState: AppUiState? = null
    private var latestActions: ZynaAppActions? = null
    private var selectedTab = ZynaTabBarView.Tab.CHATS
    private var renderSequence = 0L
    private var didScheduleVulkanWarmup = false

    init {
        setBackgroundColor(Color.BLACK)
        navigationStack.onRootGlassLayerStateChanged = { translationX, isPresented ->
            vulkanOverlayHost.translationX = translationX
            foregroundContainer.translationX = translationX
            vulkanOverlayHost.setPresentationSuppressed(!isPresented)
            foregroundContainer.visibility = if (isPresented) View.VISIBLE else View.GONE
        }
        addView(
            navigationStack,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        addView(
            vulkanOverlayHost,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        addView(
            foregroundContainer,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        addView(
            tabBar,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(ZynaTabBarView.BASE_HEIGHT_DP),
                Gravity.BOTTOM
            )
        )
        addView(
            chatOverlayContainer,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        addView(
            overlayContainer,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        tabBar.onTabSelected = { tab ->
            selectedTab = tab
            renderLatest(animated = false)
        }

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val nextBottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val didBottomInsetChange = bottomInset != nextBottomInset
            bottomInset = nextBottomInset
            tabBar.setBottomInset(bottomInset)
            val params = tabBar.layoutParams as LayoutParams
            params.height = dp(ZynaTabBarView.BASE_HEIGHT_DP) + bottomInset
            tabBar.layoutParams = params
            if (didBottomInsetChange && latestState?.route == AppRoute.Rooms) {
                renderLatest(animated = false)
            }
            insets
        }
    }

    fun render(state: AppUiState, actions: ZynaAppActions) {
        val renderStart = ZynaPerfLog.start()
        renderSequence += 1
        val sequence = renderSequence
        ZynaPerfLog.mark {
            "root.render.begin seq=$sequence route=${state.route.perfName()} " +
                "messages=${state.chatMessages.size} loading=${state.isLoadingChat}"
        }
        latestState = state
        latestActions = actions

        if (state.route is AppRoute.Chat || state.route is AppRoute.ForwardPicker) {
            selectedTab = ZynaTabBarView.Tab.CHATS
        }

        renderLatest(animated = true)
        ZynaPerfLog.end(
            renderStart,
            "root.render.done"
        ) {
            "seq=$sequence route=${state.route.perfName()} messages=${state.chatMessages.size}"
        }
    }

    fun showOverlay(view: View?) {
        if (view == null) {
            overlayContainer.removeAllViews()
            return
        }
        if (overlayContainer.childCount == 1 && overlayContainer.getChildAt(0) === view) {
            return
        }

        overlayContainer.removeAllViews()
        (view.parent as? ViewGroup)?.removeView(view)
        overlayContainer.addView(
            view,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    fun handleBack(): Boolean {
        val state = latestState ?: return false
        val actions = latestActions ?: return false
        return when (state.route) {
            is AppRoute.Chat -> {
                if ((navigationStack.topView() as? ChatScreenView)?.handleBack() == true) {
                    return true
                }
                actions.onCloseChat()
                true
            }
            AppRoute.ForwardPicker -> {
                actions.onCancelForwardPicker()
                true
            }
            AppRoute.Rooms -> {
                if (selectedTab != ZynaTabBarView.Tab.CHATS) {
                    selectedTab = ZynaTabBarView.Tab.CHATS
                    renderLatest(animated = false)
                    true
                } else {
                    false
                }
            }
            AppRoute.Login,
            is AppRoute.RecoveryKey -> false
        }
    }

    private fun renderLatest(animated: Boolean) {
        val state = latestState ?: return
        val actions = latestActions ?: return
        val entriesStart = ZynaPerfLog.start()
        val entries = entriesFor(state, actions)
        ZynaPerfLog.end(
            entriesStart,
            "root.entriesFor"
        ) {
            "route=${state.route.perfName()} keys=${entries.map { it.key }}"
        }
        val nextRouteKey = entries.joinToString(separator = "/") { it.key }
        val shouldAnimate = animated && lastRouteKey != null && sameRoot(lastRouteKey, nextRouteKey)
        val stackStart = ZynaPerfLog.start()
        navigationStack.setEntries(entries, animated = shouldAnimate)
        ZynaPerfLog.end(
            stackStart,
            "root.stack.setEntries"
        ) {
            "route=${state.route.perfName()} animate=$shouldAnimate key=$nextRouteKey"
        }
        lastRouteKey = nextRouteKey

        val showTabs = state.route == AppRoute.Rooms
        tabBar.visibility = if (showTabs) View.VISIBLE else View.GONE
        tabBar.setSelectedTab(selectedTab)
        ZynaPerfLog.mark {
            "root.tabs route=${state.route.perfName()} visible=$showTabs selected=$selectedTab"
        }
        if (state.route == AppRoute.Rooms) {
            scheduleVulkanWarmup()
        }
    }

    private fun entriesFor(
        state: AppUiState,
        actions: ZynaAppActions
    ): List<ZynaScreenEntry> {
        return when (val route = state.route) {
            AppRoute.Login -> listOf(loginEntry(state, actions))
            is AppRoute.RecoveryKey -> listOf(recoveryEntry(state, actions, route))
            AppRoute.Rooms -> {
                if (selectedTab == ZynaTabBarView.Tab.CHATS) {
                    listOf(roomsEntry(state, actions, title = "Chats", onBack = null, withBottomPadding = true))
                } else {
                    listOf(tabPlaceholderEntry(selectedTab))
                }
            }
            AppRoute.ForwardPicker -> buildList {
                add(roomsEntry(state, actions, title = "Chats", onBack = null, withBottomPadding = false))
                val returnRoute = state.forwardReturnRoute as? AppRoute.Chat
                if (returnRoute != null) {
                    add(chatEntry(state, actions, returnRoute))
                }
                add(roomsEntry(state, actions, title = "Forward to", onBack = actions.onCancelForwardPicker, withBottomPadding = false))
            }
            is AppRoute.Chat -> listOf(
                roomsEntry(state, actions, title = "Chats", onBack = null, withBottomPadding = false),
                chatEntry(state, actions, route)
            )
        }
    }

    private fun loginEntry(state: AppUiState, actions: ZynaAppActions): ZynaScreenEntry {
        return composeEntry("login") {
            ZynaAndroidTheme {
                LoginScreen(
                    isBusy = state.isBusy,
                    errorMessage = state.errorMessage,
                    onLogin = actions.onLogin
                )
            }
        }
    }

    private fun recoveryEntry(
        state: AppUiState,
        actions: ZynaAppActions,
        route: AppRoute.RecoveryKey
    ): ZynaScreenEntry {
        return composeEntry("recovery:${route.userId}") {
            ZynaAndroidTheme {
                RecoveryKeyScreen(
                    userId = route.userId,
                    isRecovering = state.isRecovering,
                    errorMessage = state.recoveryErrorMessage,
                    onSubmit = actions.onSubmitRecoveryKey
                )
            }
        }
    }

    private fun roomsEntry(
        state: AppUiState,
        actions: ZynaAppActions,
        title: String,
        onBack: (() -> Unit)?,
        withBottomPadding: Boolean
    ): ZynaScreenEntry {
        return composeEntry("rooms:$title:${onBack != null}") {
            ZynaAndroidTheme {
                RoomsScreen(
                    rooms = state.rooms,
                    isRefreshing = state.isRefreshingRooms,
                    onRefresh = actions.onRefreshRooms,
                    onOpenRoom = if (title == "Forward to") {
                        actions.onForwardRoomSelected
                    } else {
                        actions.onOpenRoom
                    },
                    onLogout = if (title == "Forward to") null else actions.onLogout,
                    title = title,
                    onBack = onBack,
                    bottomContentPaddingDp = if (withBottomPadding) {
                        ZynaTabBarView.BASE_HEIGHT_DP +
                            (bottomInset / resources.displayMetrics.density).toInt()
                    } else {
                        0
                    }
                )
            }
        }
    }

    private fun chatEntry(
        state: AppUiState,
        actions: ZynaAppActions,
        route: AppRoute.Chat
    ): ZynaScreenEntry {
        return ZynaScreenEntry(
            key = "chat:${route.roomId}",
            ownsRootGlassLayers = true,
            createView = { context ->
                ZynaPerfLog.mark { "root.chatEntry.createView roomId=${route.roomId}" }
                ChatScreenView(context, vulkanOverlayHost, foregroundContainer, chatOverlayContainer)
            },
            updateView = { view ->
                val updateStart = ZynaPerfLog.start()
                (view as ChatScreenView).render(
                    state = ChatScreenViewState(
                        roomName = route.displayName,
                        roomId = route.roomId,
                        messages = state.chatMessages,
                        windowChangeOrigin = state.chatWindowChangeOrigin,
                        isLoading = state.isLoadingChat,
                        isLoadingOlder = state.isLoadingOlderChatMessages,
                        canLoadOlder = state.canLoadOlderChatMessages,
                        canLoadNewer = state.canLoadNewerChatMessages,
                        isAtLiveEdge = state.isChatAtLiveEdge,
                        scrollToLiveEdgeRequested = state.chatScrollToLiveEdgeRequested,
                        errorMessage = state.chatErrorMessage,
                        isSendingMessage = state.isSendingChatMessage,
                        sendErrorMessage = state.chatSendErrorMessage,
                        replyTarget = state.chatReplyTarget,
                        editTarget = state.chatEditTarget,
                        forwardTarget = state.chatForwardTarget,
                        matrixMediaLoader = actions.matrixMediaLoader,
                        jumpTargetEventId = state.chatJumpTargetEventId
                    ),
                    actions = ChatScreenViewActions(
                        onRefresh = actions.onRefreshChat,
                        onBack = actions.onCloseChat,
                        onLoadOlder = actions.onLoadOlderChatMessages,
                        onLoadNewer = actions.onLoadNewerChatMessages,
                        onJumpToLiveEdge = actions.onJumpToChatLiveEdge,
                        onSendMessage = actions.onSendChatMessage,
                        onAttachPhotos = actions.onAttachPhotos,
                        onReplyToMessage = actions.onReplyToMessage,
                        onReplyHeaderClicked = actions.onReplyHeaderClicked,
                        onCancelReply = actions.onCancelReply,
                        onEditMessage = actions.onEditMessage,
                        onCancelEdit = actions.onCancelEdit,
                        onForwardMessage = actions.onForwardMessage,
                        onCancelForward = actions.onCancelForward,
                        onRetryOutgoingEnvelope = actions.onRetryOutgoingEnvelope,
                        onDiscardOutgoingEnvelope = actions.onDiscardOutgoingEnvelope,
                        onRedactMessage = actions.onRedactMessage,
                        onRedactMessages = actions.onRedactMessages,
                        onDebugMarkOutgoingEnvelopeFailed = actions.onDebugMarkOutgoingEnvelopeFailed,
                        onVisibleReadReceiptCandidate = actions.onVisibleReadReceiptCandidate,
                        onJumpTargetConsumed = actions.onChatJumpTargetConsumed,
                        onScrollToLiveEdgeConsumed = actions.onChatScrollToLiveEdgeConsumed
                    )
                )
                ZynaPerfLog.end(
                    updateStart,
                    "root.chatEntry.updateView"
                ) {
                    "roomId=${route.roomId} messages=${state.chatMessages.size} " +
                        "origin=${state.chatWindowChangeOrigin}"
                }
            }
        )
    }

    private fun tabPlaceholderEntry(tab: ZynaTabBarView.Tab): ZynaScreenEntry {
        return ZynaScreenEntry(
            key = "tab:${tab.name}",
            createView = { context ->
                TextView(context).apply {
                    gravity = Gravity.CENTER
                    textSize = 20f
                    setTextColor(Color.BLACK)
                    text = tab.title
                    setBackgroundColor(Color.WHITE)
                }
            },
            updateView = { view ->
                (view as TextView).text = tab.title
            }
        )
    }

    private fun composeEntry(
        key: String,
        content: @androidx.compose.runtime.Composable () -> Unit
    ): ZynaScreenEntry {
        return ZynaScreenEntry(
            key = key,
            createView = { context ->
                ComposeView(context).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                }
            },
            updateView = { view ->
                (view as ComposeView).setContent(content)
            }
        )
    }

    private fun sameRoot(current: String?, next: String): Boolean {
        if (current == null) return false
        val currentRoot = current.substringBefore("/")
        val nextRoot = next.substringBefore("/")
        return currentRoot == nextRoot || currentRoot == "rooms:Chats:false" || nextRoot == "rooms:Chats:false"
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun scheduleVulkanWarmup() {
        if (didScheduleVulkanWarmup || !BuildConfig.VULKAN_CHAT_GLASS_ENABLED) {
            return
        }
        didScheduleVulkanWarmup = true
        postDelayed(
            {
                val start = ZynaPerfLog.start()
                vulkanOverlayHost.warmSurface()
                ZynaPerfLog.end(start, "root.vulkanWarmup")
            },
            VULKAN_WARMUP_DELAY_MS
        )
    }

    private fun AppRoute.perfName(): String {
        return when (this) {
            AppRoute.ForwardPicker -> "ForwardPicker"
            AppRoute.Login -> "Login"
            is AppRoute.RecoveryKey -> "RecoveryKey"
            AppRoute.Rooms -> "Rooms"
            is AppRoute.Chat -> "Chat(${roomId.takeLast(10)})"
        }
    }

    private companion object {
        const val VULKAN_WARMUP_DELAY_MS = 350L
    }
}
