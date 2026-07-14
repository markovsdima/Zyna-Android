package com.zyna.app.ui.navigation

import android.content.Context
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import com.zyna.app.BuildConfig
import com.zyna.app.data.matrix.MatrixClientState
import com.zyna.app.ui.app.AppRoute
import com.zyna.app.ui.app.AppTab
import com.zyna.app.ui.app.AppUiState
import com.zyna.app.ui.app.UserProfileUiState
import com.zyna.app.ui.auth.LoginScreen
import com.zyna.app.ui.calls.CallsScreenView
import com.zyna.app.ui.calls.CallsScreenViewActions
import com.zyna.app.ui.calls.CallsScreenViewState
import com.zyna.app.ui.chat.ChatScreenView
import com.zyna.app.ui.chat.ChatScreenViewActions
import com.zyna.app.ui.chat.ChatScreenViewState
import com.zyna.app.ui.contacts.ContactsScreenView
import com.zyna.app.ui.contacts.ContactsScreenViewActions
import com.zyna.app.ui.contacts.ContactsScreenViewState
import com.zyna.app.ui.glass.RootGlassLayerCoordinator
import com.zyna.app.ui.glass.VulkanChatOverlayView
import com.zyna.app.ui.profile.EditProfileScreenView
import com.zyna.app.ui.profile.EditProfileScreenViewActions
import com.zyna.app.ui.profile.EditProfileScreenViewState
import com.zyna.app.ui.profile.ProfileScreenView
import com.zyna.app.ui.profile.ProfileScreenViewActions
import com.zyna.app.ui.profile.ProfileScreenViewState
import com.zyna.app.ui.profile.UserProfileScreenView
import com.zyna.app.ui.profile.UserProfileScreenViewActions
import com.zyna.app.ui.profile.UserProfileScreenViewState
import com.zyna.app.ui.presence.PresenceText
import com.zyna.app.ui.roomdetails.RoomDetailsScreenView
import com.zyna.app.ui.roomdetails.RoomDetailsScreenViewActions
import com.zyna.app.ui.roomdetails.RoomDetailsScreenViewState
import com.zyna.app.ui.rooms.RoomsScreenView
import com.zyna.app.ui.rooms.RoomsScreenViewActions
import com.zyna.app.ui.rooms.RoomsScrollAnchor
import com.zyna.app.ui.rooms.RoomsScreenViewState
import com.zyna.app.ui.security.RecoveryKeyScreen
import com.zyna.app.ui.settings.ChatThemeSettingsScreenView
import com.zyna.app.ui.settings.ChatThemeSettingsScreenViewActions
import com.zyna.app.ui.settings.ChatThemeSettingsScreenViewState
import com.zyna.app.ui.settings.SettingsScreenView
import com.zyna.app.ui.settings.SettingsScreenViewActions
import com.zyna.app.ui.settings.SettingsScreenViewState
import com.zyna.app.ui.theme.ZynaAndroidTheme
import com.zyna.app.util.ZynaPerfLog
import kotlin.math.abs
import kotlin.math.max

class ZynaRootHostView(context: Context) : FrameLayout(context) {
    private enum class FullscreenBackGesturePhase {
        Idle,
        Watching,
        Dragging
    }

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
    private val rootGlassCoordinator = RootGlassLayerCoordinator(
        vulkanOverlay = vulkanOverlayHost,
        foregroundHost = foregroundContainer
    )
    private val tabBar = ZynaTabBarView(context)
    private var bottomInset = 0
    private var lastRouteKey: String? = null
    private var latestState: AppUiState? = null
    private var latestActions: ZynaAppActions? = null
    private var latestPreferences: ZynaRootPreferences? = null
    private var renderSequence = 0L
    private var didScheduleVulkanWarmup = false
    private var didScheduleChatViewWarmup = false
    private var prewarmedChatView: ChatScreenView? = null
    private val roomsScrollAnchors = mutableMapOf<String, RoomsScrollAnchor>()
    private val tabBarInterpolator = DecelerateInterpolator()
    private val fullscreenBackTouchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var fullscreenBackGesturePhase = FullscreenBackGesturePhase.Idle
    private var fullscreenBackPointerId = MotionEvent.INVALID_POINTER_ID
    private var fullscreenBackStartX = 0f
    private var fullscreenBackStartY = 0f
    private var fullscreenBackProgress = 0f
    private var fullscreenBackVelocityTracker: VelocityTracker? = null
    private var systemBackInProgress = false
    private var navigationTouchSuppressionUntilUptimeMs = 0L
    private var isSuppressingNavigationTouchSequence = false

    init {
        setBackgroundColor(Color.BLACK)
        navigationStack.onRootGlassLayerStateChanged = { ownerKey, translationX ->
            rootGlassCoordinator.setPresentedOwner(ownerKey, translationX)
        }
        navigationStack.onAppliedEntryKeysChanged = { keys ->
            lastRouteKey = keys
                .takeIf { it.isNotEmpty() }
                ?.joinToString(separator = "/")
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
            latestActions?.onSelectTab(tab.toAppTab())
        }

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val nextBottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val didBottomInsetChange = bottomInset != nextBottomInset
            bottomInset = nextBottomInset
            tabBar.setBottomInset(bottomInset)
            val params = tabBar.layoutParams as LayoutParams
            params.height = dp(ZynaTabBarView.BASE_HEIGHT_DP) + bottomInset
            tabBar.layoutParams = params
            if (didBottomInsetChange && latestState?.navState?.showsTabs == true) {
                renderLatest(animated = false)
            }
            insets
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (shouldSuppressNavigationTouch(ev)) {
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (handleFullscreenBackGesture(ev, fromIntercept = true)) {
            return true
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (
            fullscreenBackGesturePhase != FullscreenBackGesturePhase.Idle &&
            handleFullscreenBackGesture(event, fromIntercept = false)
        ) {
            return true
        }
        return super.onTouchEvent(event)
    }

    fun render(
        state: AppUiState,
        actions: ZynaAppActions,
        preferences: ZynaRootPreferences
    ) {
        val renderStart = ZynaPerfLog.start()
        renderSequence += 1
        val sequence = renderSequence
        ZynaPerfLog.mark {
            "root.render.begin seq=$sequence route=${state.route.perfName()} " +
                "messages=${state.chatMessages.size} loading=${state.isLoadingChat}"
        }
        latestState = state
        latestActions = actions
        latestPreferences = preferences

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
        if (
            state.route is AppRoute.Chat &&
            (navigationStack.topView() as? ChatScreenView)?.handleBack() == true
        ) {
            return true
        }
        return actions.onNavigateBack()
    }

    fun handleSystemBackStarted(): Boolean {
        if (!canStartNavigationBackGesture()) {
            return false
        }
        systemBackInProgress = navigationStack.beginInteractivePop(
            ZynaNavigationStackView.BackInteractionStyle.PredictiveBack
        )
        return systemBackInProgress
    }

    fun handleSystemBackProgressed(progress: Float) {
        if (!systemBackInProgress) {
            return
        }
        navigationStack.updateInteractivePop(progress)
    }

    fun handleSystemBackCancelled() {
        if (!systemBackInProgress) {
            return
        }
        systemBackInProgress = false
        suppressNavigationTouches()
        navigationStack.cancelInteractivePop()
    }

    fun handleSystemBackPressed(): Boolean {
        if (!systemBackInProgress) {
            return false
        }
        systemBackInProgress = false
        suppressNavigationTouches()
        navigationStack.finishInteractivePop {
            latestActions?.onNavigateBack() == true
        }
        return true
    }

    private fun handleFullscreenBackGesture(
        event: MotionEvent,
        fromIntercept: Boolean
    ): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetFullscreenBackGesture()
                if (
                    !canStartNavigationBackGesture() ||
                    isTouchInsideVisibleTabBar(event.y) ||
                    hasHorizontalScrollingChildAt(event.x, event.y)
                ) {
                    return false
                }
                fullscreenBackGesturePhase = FullscreenBackGesturePhase.Watching
                fullscreenBackPointerId = event.getPointerId(0)
                fullscreenBackStartX = event.x
                fullscreenBackStartY = event.y
                fullscreenBackVelocityTracker = VelocityTracker.obtain().also {
                    it.addMovement(event)
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(fullscreenBackPointerId)
                if (pointerIndex < 0) {
                    resetFullscreenBackGesture()
                    return false
                }
                val x = event.getX(pointerIndex)
                val y = event.getY(pointerIndex)
                return when (fullscreenBackGesturePhase) {
                    FullscreenBackGesturePhase.Idle -> false
                    FullscreenBackGesturePhase.Watching ->
                        maybeStartFullscreenBackDrag(event, x, y, fromIntercept)
                    FullscreenBackGesturePhase.Dragging -> {
                        if (!fromIntercept) {
                            fullscreenBackVelocityTracker?.addMovement(event)
                            updateFullscreenBackDrag(x)
                        }
                        true
                    }
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_POINTER_UP -> {
                if (fullscreenBackGesturePhase == FullscreenBackGesturePhase.Dragging) {
                    if (!fromIntercept) {
                        finishFullscreenBackDrag(event)
                    }
                    return true
                }
                resetFullscreenBackGesture()
                return false
            }
        }
        return fullscreenBackGesturePhase == FullscreenBackGesturePhase.Dragging
    }

    private fun maybeStartFullscreenBackDrag(
        event: MotionEvent,
        x: Float,
        y: Float,
        fromIntercept: Boolean
    ): Boolean {
        val dx = x - fullscreenBackStartX
        val dy = y - fullscreenBackStartY
        val absDy = abs(dy)
        val startThreshold = max(
            fullscreenBackTouchSlop * FULLSCREEN_BACK_TOUCH_SLOP_MULTIPLIER,
            dp(FULLSCREEN_BACK_MIN_START_DP).toFloat()
        )

        if (dx < -fullscreenBackTouchSlop || absDy > fullscreenBackTouchSlop && absDy > dx) {
            resetFullscreenBackGesture()
            return false
        }

        val isBackSwipe = dx >= startThreshold && dx > absDy * FULLSCREEN_BACK_DIRECTION_RATIO
        if (!isBackSwipe) {
            return false
        }

        val didBegin = navigationStack.beginInteractivePop(
            ZynaNavigationStackView.BackInteractionStyle.FullScreenDrag
        )
        if (!didBegin) {
            resetFullscreenBackGesture()
            return false
        }

        fullscreenBackGesturePhase = FullscreenBackGesturePhase.Dragging
        fullscreenBackStartX = x
        fullscreenBackStartY = y
        fullscreenBackProgress = 0f
        parent?.requestDisallowInterceptTouchEvent(true)
        if (!fromIntercept) {
            fullscreenBackVelocityTracker?.addMovement(event)
        }
        return true
    }

    private fun updateFullscreenBackDrag(x: Float) {
        val width = max(width, resources.displayMetrics.widthPixels).toFloat()
        val dx = max(0f, x - fullscreenBackStartX)
        fullscreenBackProgress = (dx / width).coerceIn(0f, 1f)
        navigationStack.updateInteractivePop(fullscreenBackProgress)
    }

    private fun finishFullscreenBackDrag(event: MotionEvent) {
        fullscreenBackVelocityTracker?.addMovement(event)
        fullscreenBackVelocityTracker?.computeCurrentVelocity(1000)
        val velocityX = fullscreenBackVelocityTracker?.xVelocity ?: 0f
        val velocityY = fullscreenBackVelocityTracker?.yVelocity ?: 0f
        val velocityThreshold = dp(FULLSCREEN_BACK_FINISH_VELOCITY_DP).toFloat()
        val shouldFinish =
            fullscreenBackProgress >= FULLSCREEN_BACK_FINISH_PROGRESS ||
                velocityX >= velocityThreshold &&
                velocityX > abs(velocityY)

        val actions = latestActions
        resetFullscreenBackGesture(resetStack = false)
        suppressNavigationTouches()
        if (shouldFinish && actions != null) {
            navigationStack.finishInteractivePop {
                actions.onNavigateBack()
            }
        } else {
            navigationStack.cancelInteractivePop()
        }
    }

    private fun resetFullscreenBackGesture(resetStack: Boolean = true) {
        if (resetStack && fullscreenBackGesturePhase == FullscreenBackGesturePhase.Dragging) {
            navigationStack.cancelInteractivePop()
        }
        fullscreenBackGesturePhase = FullscreenBackGesturePhase.Idle
        fullscreenBackPointerId = MotionEvent.INVALID_POINTER_ID
        fullscreenBackStartX = 0f
        fullscreenBackStartY = 0f
        fullscreenBackProgress = 0f
        fullscreenBackVelocityTracker?.recycle()
        fullscreenBackVelocityTracker = null
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun suppressNavigationTouches() {
        navigationTouchSuppressionUntilUptimeMs =
            SystemClock.uptimeMillis() + NAVIGATION_TOUCH_SUPPRESSION_MS
    }

    private fun shouldSuppressNavigationTouch(event: MotionEvent): Boolean {
        val isWindowActive =
            SystemClock.uptimeMillis() < navigationTouchSuppressionUntilUptimeMs
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isSuppressingNavigationTouchSequence = isWindowActive
                isSuppressingNavigationTouchSequence
            }
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP -> isSuppressingNavigationTouchSequence || isWindowActive
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                val shouldSuppress = isSuppressingNavigationTouchSequence || isWindowActive
                isSuppressingNavigationTouchSequence = false
                shouldSuppress
            }
            else -> false
        }
    }

    private fun canStartNavigationBackGesture(): Boolean {
        val state = latestState ?: return false
        if (latestActions == null || overlayContainer.childCount > 0) {
            return false
        }
        if (!navigationStack.canStartInteractivePop()) {
            return false
        }
        if (state.route == AppRoute.EditProfile && state.ownProfile.isSaving) {
            return false
        }
        if (
            state.route is AppRoute.Chat &&
            (navigationStack.topView() as? ChatScreenView)?.canStartNavigationBackGesture() != true
        ) {
            return false
        }
        return true
    }

    private fun isTouchInsideVisibleTabBar(y: Float): Boolean {
        return tabBar.visibility == View.VISIBLE && y >= tabBar.y
    }

    private fun hasHorizontalScrollingChildAt(x: Float, y: Float): Boolean {
        val topView = navigationStack.topView() ?: return false
        val localX = x - navigationStack.x - topView.x
        val localY = y - navigationStack.y - topView.y
        return hasHorizontalScrollingChildAt(topView, localX, localY)
    }

    private fun hasHorizontalScrollingChildAt(view: View, x: Float, y: Float): Boolean {
        if (
            view.visibility != View.VISIBLE ||
            x < 0f ||
            y < 0f ||
            x > view.width ||
            y > view.height
        ) {
            return false
        }
        if (view.canScrollHorizontally(-1)) {
            return true
        }
        val group = view as? ViewGroup ?: return false
        for (index in group.childCount - 1 downTo 0) {
            val child = group.getChildAt(index)
            val childX = x + group.scrollX - child.left - child.translationX
            val childY = y + group.scrollY - child.top - child.translationY
            if (hasHorizontalScrollingChildAt(child, childX, childY)) {
                return true
            }
        }
        return false
    }

    private fun renderLatest(animated: Boolean) {
        val state = latestState ?: return
        val actions = latestActions ?: return
        val preferences = latestPreferences ?: return
        if (state.route == AppRoute.Login || state.route is AppRoute.RecoveryKey) {
            roomsScrollAnchors.clear()
        }
        val entriesStart = ZynaPerfLog.start()
        val entries = entriesFor(state, actions, preferences)
        ZynaPerfLog.end(
            entriesStart,
            "root.entriesFor"
        ) {
            "route=${state.route.perfName()} keys=${entries.map { it.key }}"
        }
        val hadRenderedRoute = lastRouteKey != null
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
        if (
            state.route == AppRoute.Login ||
            state.route is AppRoute.RecoveryKey ||
            (state.selectedTab != AppTab.CHATS && state.navState.activeChatRoute == null)
        ) {
            discardPrewarmedChatView()
        }
        val showTabs = state.navState.showsTabs
        setTabBarPresented(showTabs, animated = animated && hadRenderedRoute)
        tabBar.setSelectedTab(state.selectedTab.toTabBarTab())
        ZynaPerfLog.mark {
            "root.tabs route=${state.route.perfName()} visible=$showTabs selected=${state.selectedTab}"
        }
        if (state.navState.isChatsRoot) {
            scheduleVulkanWarmup()
            scheduleChatViewWarmup()
        }
    }

    private fun setTabBarPresented(presented: Boolean, animated: Boolean) {
        tabBar.animate().cancel()
        val hiddenTranslationY = tabBarHiddenTranslationY()
        tabBar.isEnabled = presented

        if (!animated) {
            tabBar.visibility = if (presented) View.VISIBLE else View.GONE
            tabBar.alpha = 1f
            tabBar.translationY = if (presented) 0f else hiddenTranslationY
            return
        }

        if (presented) {
            if (tabBar.visibility != View.VISIBLE) {
                tabBar.alpha = 1f
                tabBar.translationY = hiddenTranslationY
                tabBar.visibility = View.VISIBLE
            }
            tabBar.animate()
                .translationY(0f)
                .setDuration(TAB_BAR_ANIMATION_MS)
                .setInterpolator(tabBarInterpolator)
                .withEndAction {
                    if (latestState?.navState?.showsTabs == true) {
                        tabBar.alpha = 1f
                        tabBar.translationY = 0f
                    }
                }
                .start()
        } else {
            if (tabBar.visibility != View.VISIBLE) {
                tabBar.alpha = 1f
                tabBar.translationY = hiddenTranslationY
                return
            }
            tabBar.animate()
                .translationY(hiddenTranslationY)
                .setDuration(TAB_BAR_ANIMATION_MS)
                .setInterpolator(tabBarInterpolator)
                .withEndAction {
                    if (latestState?.navState?.showsTabs != true) {
                        tabBar.visibility = View.GONE
                        tabBar.alpha = 1f
                        tabBar.translationY = tabBarHiddenTranslationY()
                    }
                }
                .start()
        }
    }

    private fun tabBarHiddenTranslationY(): Float {
        val measuredHeight = tabBar.height.takeIf { it > 0 }
            ?: (dp(ZynaTabBarView.BASE_HEIGHT_DP) + bottomInset)
        return measuredHeight.toFloat()
    }

    private fun entriesFor(
        state: AppUiState,
        actions: ZynaAppActions,
        preferences: ZynaRootPreferences
    ): List<ZynaScreenEntry> {
        val stack = state.navigationStack
        return stack.mapIndexed { index, route ->
            val isTop = index == stack.lastIndex
            when (route) {
                AppRoute.Login -> loginEntry(state, actions)
                is AppRoute.RecoveryKey -> recoveryEntry(state, actions, route)
                AppRoute.Contacts -> contactsEntry(state, actions)
                is AppRoute.UserProfile -> userProfileEntry(state, actions, route)
                AppRoute.Calls -> callsEntry(state, actions)
                AppRoute.Rooms -> roomsEntry(
                    state = state,
                    actions = actions,
                    title = "Chats",
                    onBack = null,
                    withBottomPadding = isTop && state.navState.showsTabs
                )
                AppRoute.ForwardPicker -> roomsEntry(
                    state = state,
                    actions = actions,
                    title = "Forward to",
                    onBack = { actions.onNavigateBack() },
                    withBottomPadding = false
                )
                AppRoute.Profile -> profileEntry(state, actions)
                AppRoute.EditProfile -> editProfileEntry(state, actions)
                AppRoute.Settings -> settingsEntry(actions, preferences)
                AppRoute.ChatThemeSettings -> chatThemeSettingsEntry(actions, preferences)
                is AppRoute.RoomDetails -> roomDetailsEntry(state, actions, route)
                is AppRoute.Chat -> chatEntry(state, actions, preferences, route)
            }
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

    private fun contactsEntry(
        state: AppUiState,
        actions: ZynaAppActions
    ): ZynaScreenEntry {
        return ZynaScreenEntry(
            key = "contacts:root",
            createView = { context -> ContactsScreenView(context) },
            updateView = { view ->
                (view as ContactsScreenView).render(
                    state = ContactsScreenViewState(
                        contacts = state.contacts,
                        searchQuery = state.contactsSearchQuery,
                        isSearching = state.isSearchingContacts,
                        errorMessage = state.contactActionErrorMessage
                            ?: state.contactsSearchErrorMessage,
                        actionUserId = state.contactActionUserId,
                        matrixMediaLoader = actions.matrixMediaLoader,
                        bottomContentPaddingPx = dp(ZynaTabBarView.BASE_HEIGHT_DP) + bottomInset
                    ),
                    actions = ContactsScreenViewActions(
                        onSearchQueryChanged = actions.onContactsSearchQueryChanged,
                        onOpenProfile = actions.onOpenUserProfile,
                        onOpenChat = actions.onOpenContactChat,
                        onCall = actions.onCallContact
                    )
                )
            }
        )
    }

    private fun callsEntry(
        state: AppUiState,
        actions: ZynaAppActions
    ): ZynaScreenEntry {
        return ZynaScreenEntry(
            key = "calls:root",
            createView = { context -> CallsScreenView(context) },
            updateView = { view ->
                (view as CallsScreenView).render(
                    state = CallsScreenViewState(
                        calls = state.callHistory,
                        isRefreshing = state.isRefreshingCallHistory,
                        errorMessage = state.callHistoryErrorMessage,
                        matrixMediaLoader = actions.matrixMediaLoader,
                        bottomContentPaddingPx = dp(ZynaTabBarView.BASE_HEIGHT_DP) + bottomInset
                    ),
                    actions = CallsScreenViewActions(
                        onOpenRoom = actions.onOpenCallHistoryRoom,
                        onCall = actions.onCallHistoryItem
                    )
                )
            }
        )
    }

    private fun userProfileEntry(
        state: AppUiState,
        actions: ZynaAppActions,
        route: AppRoute.UserProfile
    ): ZynaScreenEntry {
        return ZynaScreenEntry(
            key = "user:${route.userId}",
            createView = { context -> UserProfileScreenView(context) },
            updateView = { view ->
                (view as UserProfileScreenView).render(
                    state = UserProfileScreenViewState(
                        profile = state.userProfile.takeIf { it.userId == route.userId }
                            ?: UserProfileUiState(userId = route.userId),
                        roomId = state.roomIdForContact(route.userId),
                        presence = state.presenceByUserId[route.userId],
                        actionUserId = state.contactActionUserId,
                        actionErrorMessage = state.contactActionErrorMessage,
                        matrixMediaLoader = actions.matrixMediaLoader
                    ),
                    actions = UserProfileScreenViewActions(
                        onBack = { actions.onNavigateBack() },
                        onMessage = actions.onOpenUserProfileChat,
                        onCall = actions.onCallUserProfile,
                        onRefresh = actions.onRefreshUserProfile
                    )
                )
            }
        )
    }

    private fun roomsEntry(
        state: AppUiState,
        actions: ZynaAppActions,
        title: String,
        onBack: (() -> Unit)?,
        withBottomPadding: Boolean
    ): ZynaScreenEntry {
        val entryKey = roomsEntryKey(title = title, onBack = onBack)
        return ZynaScreenEntry(
            key = entryKey,
            createView = { context ->
                RoomsScreenView(context)
            },
            onViewRemoved = { view ->
                val anchor = (view as? RoomsScreenView)?.captureScrollAnchor()
                if (shouldRetainRoomsScrollAnchor()) {
                    if (anchor != null) {
                        roomsScrollAnchors[entryKey] = anchor
                    } else {
                        roomsScrollAnchors.remove(entryKey)
                    }
                } else {
                    roomsScrollAnchors.remove(entryKey)
                }
            },
            updateView = { view ->
                val updateStart = ZynaPerfLog.start()
                (view as RoomsScreenView).render(
                    state = RoomsScreenViewState(
                        rooms = state.rooms,
                        isRefreshing = state.isRefreshingRooms,
                        title = title,
                        showBack = onBack != null,
                        matrixMediaLoader = actions.matrixMediaLoader,
                        presenceByUserId = state.presenceByUserId,
                        initialScrollAnchor = roomsScrollAnchors[entryKey],
                        bottomContentPaddingPx = if (withBottomPadding) {
                            dp(ZynaTabBarView.BASE_HEIGHT_DP) + bottomInset
                        } else {
                            0
                        }
                    ),
                    actions = RoomsScreenViewActions(
                        onOpenRoom = if (title == "Forward to") {
                            actions.onForwardRoomSelected
                        } else {
                            actions.onOpenRoom
                        },
                        onBack = onBack
                    )
                )
                ZynaPerfLog.end(updateStart, "root.roomsEntry.updateView") {
                    "title=$title rooms=${state.rooms.size} refreshing=${state.isRefreshingRooms}"
                }
            }
        )
    }

    private fun roomsEntryKey(title: String, onBack: (() -> Unit)?): String {
        return "rooms:$title:${onBack != null}"
    }

    private fun shouldRetainRoomsScrollAnchor(): Boolean {
        val state = latestState ?: return false
        if (state.route == AppRoute.Login || state.route is AppRoute.RecoveryKey) {
            return false
        }
        return state.navState.chatsStack.any { route ->
            route == AppRoute.Rooms ||
                route == AppRoute.ForwardPicker ||
                route is AppRoute.Chat
        }
    }

    private fun profileEntry(
        state: AppUiState,
        actions: ZynaAppActions
    ): ZynaScreenEntry {
        return ZynaScreenEntry(
            key = "profile:root",
            createView = { context -> ProfileScreenView(context) },
            updateView = { view ->
                (view as ProfileScreenView).render(
                    state = ProfileScreenViewState(
                        profile = state.ownProfile,
                        matrixMediaLoader = actions.matrixMediaLoader,
                        bottomContentPaddingPx = dp(ZynaTabBarView.BASE_HEIGHT_DP) + bottomInset
                    ),
                    actions = ProfileScreenViewActions(
                        onEditProfile = actions.onOpenEditProfile,
                        onOpenSettings = actions.onOpenProfileSettings,
                        onRefreshProfile = actions.onRefreshOwnProfile
                    )
                )
            }
        )
    }

    private fun editProfileEntry(
        state: AppUiState,
        actions: ZynaAppActions
    ): ZynaScreenEntry {
        return ZynaScreenEntry(
            key = "profile:edit",
            createView = { context -> EditProfileScreenView(context) },
            updateView = { view ->
                (view as EditProfileScreenView).render(
                    state = EditProfileScreenViewState(
                        profile = state.ownProfile,
                        matrixMediaLoader = actions.matrixMediaLoader,
                        bottomContentPaddingPx = dp(ZynaTabBarView.BASE_HEIGHT_DP) + bottomInset
                    ),
                    actions = EditProfileScreenViewActions(
                        onBack = { actions.onNavigateBack() },
                        onDisplayNameChanged = actions.onOwnProfileDisplayNameChanged,
                        onPickAvatar = actions.onPickOwnProfileAvatar,
                        onRemoveAvatar = actions.onRemoveOwnProfileAvatar,
                        onSave = actions.onSaveOwnProfile
                    )
                )
            }
        )
    }

    private fun settingsEntry(
        actions: ZynaAppActions,
        preferences: ZynaRootPreferences
    ): ZynaScreenEntry {
        return ZynaScreenEntry(
            key = "profile:settings",
            createView = { context -> SettingsScreenView(context) },
            updateView = { view ->
                (view as SettingsScreenView).render(
                    state = SettingsScreenViewState(
                        selectedChatThemeTitle = preferences.chatBubbleTheme.title,
                        selectedAppThemeMode = preferences.appThemeMode,
                        selectedPresenceProvider = preferences.presenceProvider,
                        bottomContentPaddingPx = dp(ZynaTabBarView.BASE_HEIGHT_DP) + bottomInset
                    ),
                    actions = SettingsScreenViewActions(
                        onBack = { actions.onNavigateBack() },
                        onOpenChatTheme = actions.onOpenChatThemeSettings,
                        onSelectAppThemeMode = actions.onSelectAppThemeMode,
                        onSelectPresenceProvider = actions.onSelectPresenceProvider,
                        onLogout = actions.onLogout
                    )
                )
            }
        )
    }

    private fun chatThemeSettingsEntry(
        actions: ZynaAppActions,
        preferences: ZynaRootPreferences
    ): ZynaScreenEntry {
        return ZynaScreenEntry(
            key = "profile:settings/chat-theme",
            createView = { context -> ChatThemeSettingsScreenView(context) },
            updateView = { view ->
                (view as ChatThemeSettingsScreenView).render(
                    state = ChatThemeSettingsScreenViewState(
                        selectedTheme = preferences.chatBubbleTheme,
                        bottomContentPaddingPx = dp(ZynaTabBarView.BASE_HEIGHT_DP) + bottomInset
                    ),
                    actions = ChatThemeSettingsScreenViewActions(
                        onBack = { actions.onNavigateBack() },
                        onSelectTheme = actions.onSelectChatBubbleTheme
                    )
                )
            }
        )
    }

    private fun roomDetailsEntry(
        state: AppUiState,
        actions: ZynaAppActions,
        route: AppRoute.RoomDetails
    ): ZynaScreenEntry {
        return ZynaScreenEntry(
            key = "roomDetails:${route.roomId}",
            createView = { context -> RoomDetailsScreenView(context) },
            updateView = { view ->
                val room = state.rooms.firstOrNull { it.id == route.roomId }
                val chatRoute = state.activeChatRoute?.takeIf { it.roomId == route.roomId }
                val displayName = room?.displayName
                    ?: chatRoute?.displayName
                    ?: route.roomId
                (view as RoomDetailsScreenView).render(
                    state = RoomDetailsScreenViewState(
                        roomId = route.roomId,
                        displayName = displayName,
                        directUserId = room?.directUserId,
                        unreadCount = room?.unreadCount ?: 0,
                        isMarkedUnread = room?.isMarkedUnread == true
                    ),
                    actions = RoomDetailsScreenViewActions(
                        onBack = { actions.onNavigateBack() }
                    )
                )
            }
        )
    }

    private fun chatEntry(
        state: AppUiState,
        actions: ZynaAppActions,
        preferences: ZynaRootPreferences,
        route: AppRoute.Chat
    ): ZynaScreenEntry {
        return ZynaScreenEntry(
            key = "chat:${route.roomId}",
            rootGlassOwnerKey = chatGlassOwnerKey(),
            retainViewOnRemove = true,
            createView = { context ->
                takeOrCreateChatScreenView(context, roomId = route.roomId)
            },
            onViewRemoved = { view ->
                recycleChatScreenViewIfPossible(view)
            },
            updateView = { view ->
                val updateStart = ZynaPerfLog.start()
                (view as ChatScreenView).render(
                    state = ChatScreenViewState(
                        roomName = route.displayName,
                        roomId = route.roomId,
                        currentUserId = when (val matrixState = state.matrixState) {
                            is MatrixClientState.LoggedIn -> matrixState.userId
                            is MatrixClientState.Syncing -> matrixState.userId
                            MatrixClientState.LoggedOut,
                            is MatrixClientState.Error,
                            MatrixClientState.LoggingIn,
                            MatrixClientState.RestoringSession -> null
                        },
                        roomSubtitle = PresenceText.label(
                            context = context,
                            status = state.activeChatPresence,
                            style = PresenceText.LastSeenStyle.CHAT
                        )
                            ?: route.roomId,
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
                        audioPlaybackController = actions.audioPlaybackController,
                        voiceRecorderController = actions.voiceRecorderController,
                        jumpTargetEventId = state.chatJumpTargetEventId,
                        callBanner = state.chatCallBanner,
                        chatBubbleTheme = preferences.chatBubbleTheme
                    ),
                    actions = ChatScreenViewActions(
                        onStartCall = {
                            actions.onStartNativeMatrixRtcCall(route.roomId, route.displayName)
                        },
                        onBack = actions.onCloseChat,
                        onOpenRoomDetails = actions.onOpenRoomDetails,
                        onLoadOlder = actions.onLoadOlderChatMessages,
                        onLoadNewer = actions.onLoadNewerChatMessages,
                        onJumpToLiveEdge = actions.onJumpToChatLiveEdge,
                        onSendMessage = actions.onSendChatMessage,
                        onAttachPhotos = actions.onAttachPhotos,
                        onStartVoiceRecording = actions.onStartVoiceRecording,
                        onStopVoiceRecording = actions.onStopVoiceRecording,
                        onCancelVoiceRecording = actions.onCancelVoiceRecording,
                        onFinishVoiceRecordingForSend = actions.onFinishVoiceRecordingForSend,
                        onSendVoiceRecording = actions.onSendVoiceRecording,
                        onToggleVoicePreviewPlayback = actions.onToggleVoicePreviewPlayback,
                        onReplyToMessage = actions.onReplyToMessage,
                        onReplyHeaderClicked = actions.onReplyHeaderClicked,
                        onCancelReply = actions.onCancelReply,
                        onEditMessage = actions.onEditMessage,
                        onCancelEdit = actions.onCancelEdit,
                        onForwardMessage = actions.onForwardMessage,
                        onCancelForward = actions.onCancelForward,
                        onToggleReaction = actions.onToggleReaction,
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

    private fun tabPlaceholderEntry(tab: AppTab): ZynaScreenEntry {
        return ZynaScreenEntry(
            key = "tab:${tab.name}",
            createView = { context ->
                TextView(context).apply {
                    gravity = Gravity.CENTER
                    textSize = 20f
                    setTextColor(Color.BLACK)
                    text = tab.title()
                    setBackgroundColor(Color.WHITE)
                }
            },
            updateView = { view ->
                (view as TextView).text = tab.title()
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
                rootGlassCoordinator.warmSurface()
                ZynaPerfLog.end(start, "root.vulkanWarmup")
            },
            VULKAN_WARMUP_DELAY_MS
        )
    }

    private fun scheduleChatViewWarmup() {
        if (didScheduleChatViewWarmup || prewarmedChatView != null) {
            return
        }
        didScheduleChatViewWarmup = true
        postDelayed(
            {
                if (prewarmedChatView != null || latestState?.navState?.isChatsRoot != true) {
                    didScheduleChatViewWarmup = false
                    return@postDelayed
                }
                Looper.myQueue().addIdleHandler {
                    didScheduleChatViewWarmup = false
                    if (prewarmedChatView == null && latestState?.navState?.isChatsRoot == true) {
                        val start = ZynaPerfLog.start()
                        prewarmedChatView = createChatScreenView(context)
                        ZynaPerfLog.end(start, "root.chatViewWarmup")
                    }
                    false
                }
            },
            CHAT_VIEW_WARMUP_DELAY_MS
        )
    }

    private fun takeOrCreateChatScreenView(context: Context, roomId: String): ChatScreenView {
        val prewarmed = prewarmedChatView
        if (prewarmed != null && prewarmed.canReuseForRoom(roomId)) {
            prewarmedChatView = null
            if (prewarmed.parent !== navigationStack) {
                (prewarmed.parent as? ViewGroup)?.removeView(prewarmed)
            }
            prewarmed.visibility = View.VISIBLE
            prewarmed.isEnabled = true
            ZynaPerfLog.mark {
                "root.chatEntry.createView roomId=$roomId reused=true"
            }
            return prewarmed
        }
        if (prewarmed != null) {
            prewarmedChatView = null
            navigationStack.removeRetainedView(prewarmed)
            ZynaPerfLog.mark {
                "root.chatEntry.createView roomId=$roomId reused=false wrongRoom=true"
            }
            return createChatScreenView(context)
        }

        ZynaPerfLog.mark {
            "root.chatEntry.createView roomId=$roomId reused=false"
        }
        return createChatScreenView(context)
    }

    private fun recycleChatScreenViewIfPossible(view: View) {
        val chatView = view as? ChatScreenView ?: return
        if (latestState?.navState?.isChatsRoot != true) {
            discardPrewarmedChatView(chatView)
            return
        }
        if (chatView.parent !== navigationStack) {
            (chatView.parent as? ViewGroup)?.removeView(chatView)
        }
        chatView.visibility = View.INVISIBLE
        chatView.isEnabled = false
        chatView.translationX = 0f
        chatView.translationY = 0f
        chatView.alpha = 1f
        prewarmedChatView = chatView
        ZynaPerfLog.mark {
            "root.chatViewRecycled"
        }
    }

    private fun discardPrewarmedChatView(view: ChatScreenView? = prewarmedChatView) {
        val chatView = view ?: return
        if (prewarmedChatView === chatView) {
            prewarmedChatView = null
        }
        navigationStack.removeRetainedView(chatView)
        if (chatView.parent !== navigationStack) {
            (chatView.parent as? ViewGroup)?.removeView(chatView)
        }
    }

    private fun createChatScreenView(context: Context): ChatScreenView {
        return ChatScreenView(
            context = context,
            rootGlassOwnerKey = chatGlassOwnerKey(),
            rootGlassCoordinator = rootGlassCoordinator,
            rootOverlayHost = chatOverlayContainer
        )
    }

    private fun chatGlassOwnerKey(): String {
        return CHAT_GLASS_OWNER_KEY
    }

    private fun ZynaTabBarView.Tab.toAppTab(): AppTab {
        return when (this) {
            ZynaTabBarView.Tab.CONTACTS -> AppTab.CONTACTS
            ZynaTabBarView.Tab.CALLS -> AppTab.CALLS
            ZynaTabBarView.Tab.CHATS -> AppTab.CHATS
            ZynaTabBarView.Tab.PROFILE -> AppTab.PROFILE
        }
    }

    private fun AppTab.toTabBarTab(): ZynaTabBarView.Tab {
        return when (this) {
            AppTab.CONTACTS -> ZynaTabBarView.Tab.CONTACTS
            AppTab.CALLS -> ZynaTabBarView.Tab.CALLS
            AppTab.CHATS -> ZynaTabBarView.Tab.CHATS
            AppTab.PROFILE -> ZynaTabBarView.Tab.PROFILE
        }
    }

    private fun AppTab.title(): String {
        return when (this) {
            AppTab.CONTACTS -> "Contacts"
            AppTab.CALLS -> "Calls"
            AppTab.CHATS -> "Chats"
            AppTab.PROFILE -> "Profile"
        }
    }

    private fun AppRoute.perfName(): String {
        return when (this) {
            AppRoute.Calls -> "Calls"
            AppRoute.ChatThemeSettings -> "ChatThemeSettings"
            is AppRoute.UserProfile -> "UserProfile(${userId.takeLast(10)})"
            AppRoute.Contacts -> "Contacts"
            AppRoute.ForwardPicker -> "ForwardPicker"
            AppRoute.Login -> "Login"
            AppRoute.EditProfile -> "EditProfile"
            AppRoute.Profile -> "Profile"
            is AppRoute.RecoveryKey -> "RecoveryKey"
            is AppRoute.RoomDetails -> "RoomDetails(${roomId.takeLast(10)})"
            AppRoute.Rooms -> "Rooms"
            AppRoute.Settings -> "Settings"
            is AppRoute.Chat -> "Chat(${roomId.takeLast(10)})"
        }
    }

    private companion object {
        const val VULKAN_WARMUP_DELAY_MS = 350L
        const val CHAT_VIEW_WARMUP_DELAY_MS = 900L
        const val CHAT_GLASS_OWNER_KEY = "chat"
        const val TAB_BAR_ANIMATION_MS = 220L
        const val FULLSCREEN_BACK_MIN_START_DP = 18
        const val FULLSCREEN_BACK_TOUCH_SLOP_MULTIPLIER = 1.5f
        const val FULLSCREEN_BACK_DIRECTION_RATIO = 2.0f
        const val FULLSCREEN_BACK_FINISH_PROGRESS = 0.38f
        const val FULLSCREEN_BACK_FINISH_VELOCITY_DP = 900
        const val NAVIGATION_TOUCH_SUPPRESSION_MS = 260L
    }
}
