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
import com.zyna.app.data.matrix.MatrixRoomKind
import com.zyna.app.data.matrix.MatrixSpaceJoinRule
import com.zyna.app.data.matrix.MatrixSpaceMembership
import com.zyna.app.data.matrix.MatrixSpaceRoom
import com.zyna.app.data.matrix.MatrixSpaceRoomKind
import com.zyna.app.ui.app.AppRoute
import com.zyna.app.ui.app.toSpaceRoomSeed
import com.zyna.app.ui.app.AppTab
import com.zyna.app.ui.app.AppUiState
import com.zyna.app.ui.auth.LoginScreen
import com.zyna.app.ui.calls.CallHistoryState
import com.zyna.app.ui.calls.CallsScreenView
import com.zyna.app.ui.calls.CallsScreenViewActions
import com.zyna.app.ui.calls.CallsScreenViewState
import com.zyna.app.ui.chat.ChatCallInfoState
import com.zyna.app.ui.chat.ChatComposerState
import com.zyna.app.ui.chat.ChatFeatureState
import com.zyna.app.ui.chat.ChatScreenView
import com.zyna.app.ui.chat.ChatScreenViewActions
import com.zyna.app.ui.chat.ChatScreenViewState
import com.zyna.app.ui.chat.ChatTimelineState
import com.zyna.app.ui.contacts.ContactsFeatureState
import com.zyna.app.ui.contacts.ContactsScreenView
import com.zyna.app.ui.contacts.ContactsScreenViewActions
import com.zyna.app.ui.contacts.ContactsScreenViewState
import com.zyna.app.ui.createroom.CreateRoomError
import com.zyna.app.ui.createroom.CreateRoomScreenView
import com.zyna.app.ui.createroom.CreateRoomScreenViewActions
import com.zyna.app.ui.createroom.CreateRoomScreenViewState
import com.zyna.app.ui.createroom.CreateRoomState
import com.zyna.app.ui.glass.RootGlassLayerCoordinator
import com.zyna.app.ui.glass.VulkanChatOverlayView
import com.zyna.app.ui.invitemembers.InviteMembersScreenView
import com.zyna.app.ui.invitemembers.InviteMembersScreenViewActions
import com.zyna.app.ui.invitemembers.InviteMembersScreenViewState
import com.zyna.app.ui.invitemembers.InviteMembersState
import com.zyna.app.ui.profile.ProfileEditorScreenView
import com.zyna.app.ui.profile.ProfileEditorScreenViewActions
import com.zyna.app.ui.profile.ProfileEditorScreenViewState
import com.zyna.app.ui.profile.OwnProfileState
import com.zyna.app.ui.profile.OwnProfileAvatarChange
import com.zyna.app.ui.profile.ProfileFeatureState
import com.zyna.app.ui.profile.ProfileScreenView
import com.zyna.app.ui.profile.ProfileScreenViewActions
import com.zyna.app.ui.profile.ProfileScreenViewState
import com.zyna.app.ui.profile.UserProfileScreenView
import com.zyna.app.ui.profile.UserProfileScreenViewActions
import com.zyna.app.ui.profile.UserProfileScreenViewState
import com.zyna.app.ui.profile.UserProfileState
import com.zyna.app.ui.presence.PresenceText
import com.zyna.app.ui.roomdetails.RoomDetailsScreenView
import com.zyna.app.ui.roomdetails.RoomDetailsScreenViewActions
import com.zyna.app.ui.roomdetails.RoomDetailsScreenViewState
import com.zyna.app.ui.roomdetails.RoomDetailsState
import com.zyna.app.ui.roomdetails.RoomFeatureState
import com.zyna.app.ui.roomdetails.RoomLeaveState
import com.zyna.app.ui.roommembers.RoomMembersScreenView
import com.zyna.app.ui.roommembers.RoomMembersScreenViewActions
import com.zyna.app.ui.roommembers.RoomMembersScreenViewState
import com.zyna.app.ui.roommembers.RoomMembersState
import com.zyna.app.ui.roommembers.RoomMemberDetailsScreenView
import com.zyna.app.ui.roommembers.RoomMemberDetailsScreenViewActions
import com.zyna.app.ui.roommembers.RoomMemberDetailsScreenViewState
import com.zyna.app.ui.roommembers.RoomMemberModerationError
import com.zyna.app.ui.roommembers.RoomMemberModerationState
import com.zyna.app.ui.roompermissions.RoomPermissionsError
import com.zyna.app.ui.roompermissions.RoomPermissionsScreenView
import com.zyna.app.ui.roompermissions.RoomPermissionsScreenViewActions
import com.zyna.app.ui.roompermissions.RoomPermissionsScreenViewState
import com.zyna.app.ui.roompermissions.RoomPermissionsState
import com.zyna.app.ui.roomroles.RoomRoleManagementError
import com.zyna.app.ui.roomroles.RoomRoleManagementState
import com.zyna.app.ui.roomprofile.RoomProfileAvatarChange
import com.zyna.app.ui.roomprofile.RoomProfileEditorError
import com.zyna.app.ui.roomprofile.RoomProfileEditorState
import com.zyna.app.ui.rooms.RoomsScreenView
import com.zyna.app.ui.rooms.RoomsScreenViewActions
import com.zyna.app.ui.rooms.RoomsScrollAnchor
import com.zyna.app.ui.rooms.RoomsScreenViewState
import com.zyna.app.ui.rooms.RoomListState
import com.zyna.app.ui.security.SessionSecurityScreen
import com.zyna.app.ui.settings.ChatThemeSettingsScreenView
import com.zyna.app.ui.settings.ChatThemeSettingsScreenViewActions
import com.zyna.app.ui.settings.ChatThemeSettingsScreenViewState
import com.zyna.app.ui.settings.SettingsScreenView
import com.zyna.app.ui.settings.SettingsScreenViewActions
import com.zyna.app.ui.settings.SettingsScreenViewState
import com.zyna.app.ui.spaces.SpaceFeatureState
import com.zyna.app.ui.spaces.SpaceJoinPreviewScreenActions
import com.zyna.app.ui.spaces.SpaceJoinPreviewScreenState
import com.zyna.app.ui.spaces.SpaceJoinPreviewScreenView
import com.zyna.app.ui.spaces.SpaceJoinState
import com.zyna.app.ui.spaces.SpaceLeaveScreenActions
import com.zyna.app.ui.spaces.SpaceLeaveScreenState
import com.zyna.app.ui.spaces.SpaceLeaveScreenView
import com.zyna.app.ui.spaces.SpaceLeaveState
import com.zyna.app.ui.spaces.SpacePresentationKind
import com.zyna.app.ui.spaces.SpaceScreenView
import com.zyna.app.ui.spaces.SpaceScreenViewActions
import com.zyna.app.ui.spaces.SpaceScreenViewState
import com.zyna.app.ui.spaces.VisibleChatRootRoomsProjection
import com.zyna.app.ui.theme.ZynaAndroidTheme
import com.zyna.app.util.ZynaPerfLog
import kotlin.math.abs
import kotlin.math.max

enum class ZynaOverlayPresentation(val coversTabBar: Boolean) {
    FULLSCREEN(coversTabBar = true),
    OVER_CONTENT(coversTabBar = false)
}

private fun RoomProfileEditorError?.localizedMessage(context: Context): String? {
    val stringId = when (this) {
        null -> return null
        RoomProfileEditorError.AVATAR_PREPARATION ->
            com.zyna.app.R.string.room_profile_edit_avatar_error
        RoomProfileEditorError.PERMISSION_CHANGED ->
            com.zyna.app.R.string.room_profile_edit_permission_changed
        RoomProfileEditorError.SAVE -> com.zyna.app.R.string.room_profile_edit_save_error
        RoomProfileEditorError.PARTIAL_SAVE ->
            com.zyna.app.R.string.room_profile_edit_partial_save_error
    }
    return context.getString(stringId)
}

private fun CreateRoomError?.localizedMessage(context: Context): String? {
    val stringId = when (this) {
        null -> return null
        CreateRoomError.AVATAR_PREPARATION -> com.zyna.app.R.string.create_group_avatar_error
        CreateRoomError.AVATAR_UPLOAD -> com.zyna.app.R.string.create_group_avatar_upload_error
        CreateRoomError.ADDRESS_CHECK ->
            com.zyna.app.R.string.create_group_address_check_create_error
        CreateRoomError.CREATE -> com.zyna.app.R.string.create_group_error
    }
    return context.getString(stringId)
}

private fun RoomPermissionsError?.localizedMessage(context: Context): String? {
    val stringId = when (this) {
        null -> return null
        RoomPermissionsError.LOAD -> com.zyna.app.R.string.room_permissions_load_error
        RoomPermissionsError.SAVE -> com.zyna.app.R.string.room_permissions_save_error
        RoomPermissionsError.PERMISSION_CHANGED ->
            com.zyna.app.R.string.room_permissions_permission_changed
    }
    return context.getString(stringId)
}

private fun RoomRoleManagementError?.localizedMessage(context: Context): String? {
    val stringId = when (this) {
        null -> return null
        RoomRoleManagementError.SAVE -> com.zyna.app.R.string.room_roles_save_error
        RoomRoleManagementError.PERMISSION_CHANGED ->
            com.zyna.app.R.string.room_roles_permission_changed
    }
    return context.getString(stringId)
}

private fun RoomMemberModerationError?.localizedMessage(context: Context): String? {
    val stringId = when (this) {
        null -> return null
        RoomMemberModerationError.LOAD -> com.zyna.app.R.string.room_member_load_error
        RoomMemberModerationError.SAVE -> com.zyna.app.R.string.room_member_save_error
        RoomMemberModerationError.PERMISSION_CHANGED ->
            com.zyna.app.R.string.room_member_permission_changed
    }
    return context.getString(stringId)
}

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
    private var latestRoomList: RoomListState? = null
    private var latestRoom: RoomFeatureState? = null
    private var latestSpaces: SpaceFeatureState? = null
    private var latestContacts: ContactsFeatureState? = null
    private var latestProfile: ProfileFeatureState? = null
    private var latestCallHistory: CallHistoryState? = null
    private var latestChat: ChatFeatureState? = null
    private var latestActions: ZynaRootActions? = null
    private var latestDependencies: ZynaRenderDependencies? = null
    private var latestPreferences: ZynaRootPreferences? = null
    private var renderSequence = 0L
    private var didScheduleVulkanWarmup = false
    private var didScheduleChatViewWarmup = false
    private var prewarmedChatView: ChatScreenView? = null
    private val roomsScrollAnchors = mutableMapOf<String, RoomsScrollAnchor>()
    private val visibleChatRootRooms = VisibleChatRootRoomsProjection()
    private val tabBarInterpolator = DecelerateInterpolator()
    private val fullscreenBackTouchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var fullscreenBackGesturePhase = FullscreenBackGesturePhase.Idle
    private var fullscreenBackPointerId = MotionEvent.INVALID_POINTER_ID
    private var fullscreenBackStartX = 0f
    private var fullscreenBackStartY = 0f
    private var fullscreenBackProgress = 0f
    private var fullscreenBackVelocityTracker: VelocityTracker? = null
    private var ownsFullscreenBackTouchSequence = false
    private var systemBackInProgress = false
    private var navigationTouchSuppressionUntilUptimeMs = 0L
    private var isSuppressingNavigationTouchSequence = false
    private var overlayPresentation: ZynaOverlayPresentation? = null

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
            latestActions?.navigation?.onSelectTab(tab.toAppTab())
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
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // Keep an unclaimed stream alive so the watcher can inspect later MOVE events.
            ownsFullscreenBackTouchSequence =
                fullscreenBackGesturePhase == FullscreenBackGesturePhase.Watching
            if (ownsFullscreenBackTouchSequence) {
                return true
            }
        }

        val handledByBackGesture =
            fullscreenBackGesturePhase != FullscreenBackGesturePhase.Idle &&
                handleFullscreenBackGesture(event, fromIntercept = false)
        val shouldConsume = ownsFullscreenBackTouchSequence || handledByBackGesture

        if (
            event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            ownsFullscreenBackTouchSequence = false
        }

        return shouldConsume || super.onTouchEvent(event)
    }

    fun render(
        state: AppUiState,
        roomList: RoomListState,
        room: RoomFeatureState,
        spaces: SpaceFeatureState,
        contacts: ContactsFeatureState,
        profile: ProfileFeatureState,
        callHistory: CallHistoryState,
        chat: ChatFeatureState,
        actions: ZynaRootActions,
        dependencies: ZynaRenderDependencies,
        preferences: ZynaRootPreferences
    ) {
        val renderStart = ZynaPerfLog.start()
        renderSequence += 1
        val sequence = renderSequence
        ZynaPerfLog.mark {
            "root.render.begin seq=$sequence route=${state.route.perfName()} " +
                "messages=${chat.timeline.messages.size} loading=${chat.timeline.isLoading}"
        }
        latestState = state
        latestRoomList = roomList
        latestRoom = room
        latestSpaces = spaces
        latestContacts = contacts
        latestProfile = profile
        latestCallHistory = callHistory
        latestChat = chat
        latestActions = actions
        latestDependencies = dependencies
        latestPreferences = preferences

        renderLatest(animated = true)
        ZynaPerfLog.end(
            renderStart,
            "root.render.done"
        ) {
            "seq=$sequence route=${state.route.perfName()} " +
                "messages=${chat.timeline.messages.size}"
        }
    }

    fun showOverlay(
        view: View?,
        presentation: ZynaOverlayPresentation = ZynaOverlayPresentation.FULLSCREEN
    ) {
        if (view == null) {
            overlayContainer.removeAllViews()
            overlayPresentation = null
            setTabBarPresented(
                presented = latestState?.navState?.showsTabs == true,
                animated = false
            )
            return
        }
        overlayPresentation = presentation
        setTabBarPresented(
            presented = latestState?.navState?.showsTabs == true &&
                !presentation.coversTabBar,
            animated = false
        )
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
        return actions.navigation.onNavigateBack()
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
            latestActions?.navigation?.onNavigateBack() == true
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
                actions.navigation.onNavigateBack()
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
        if (state.route == AppRoute.EditProfile) {
            if (
                latestProfile?.own?.isSaving == true ||
                latestProfile?.own?.hasUnsavedChanges == true
            ) {
                return false
            }
        }
        if (state.route is AppRoute.EditRoomProfile) {
            if (
                latestRoom?.profileEditor?.isSaving == true ||
                latestRoom?.profileEditor?.hasUnsavedChanges == true
            ) {
                return false
            }
        }
        if (state.route == AppRoute.CreateRoom) {
            if (
                latestRoom?.createRoom?.isCreating == true ||
                latestRoom?.createRoom?.hasUnsavedChanges == true
            ) {
                return false
            }
        }
        if (state.route is AppRoute.InviteCreatedRoomMembers) {
            return false
        }
        if (
            state.route is AppRoute.RoomPermissions &&
            latestRoom?.permissions?.isSaving == true
        ) {
            return false
        }
        if (
            state.route is AppRoute.RoomRoleManagement &&
            latestRoom?.roles?.isSaving == true
        ) {
            return false
        }
        if (
            (state.route is AppRoute.InviteRoomMembers ||
                state.route is AppRoute.InviteCreatedRoomMembers) &&
            latestRoom?.inviteMembers?.isSending == true
        ) {
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
        val roomList = latestRoomList ?: return
        val room = latestRoom ?: return
        val spaces = latestSpaces ?: return
        val contacts = latestContacts ?: return
        val profile = latestProfile ?: return
        val callHistory = latestCallHistory ?: return
        val chat = latestChat ?: return
        val actions = latestActions ?: return
        val dependencies = latestDependencies ?: return
        val preferences = latestPreferences ?: return
        if (state.route == AppRoute.Login || state.route is AppRoute.RecoveryKey) {
            roomsScrollAnchors.clear()
        }
        val entriesStart = ZynaPerfLog.start()
        val entries = entriesFor(
            state,
            roomList,
            room,
            spaces,
            contacts,
            profile,
            callHistory,
            chat,
            actions,
            dependencies,
            preferences
        )
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
        val showTabs = state.navState.showsTabs && !overlayCoversTabBar()
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
                    if (
                        latestState?.navState?.showsTabs == true &&
                        !overlayCoversTabBar()
                    ) {
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
                    if (
                        latestState?.navState?.showsTabs != true ||
                        overlayCoversTabBar()
                    ) {
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

    private fun overlayCoversTabBar(): Boolean {
        return overlayContainer.childCount > 0 &&
            overlayPresentation?.coversTabBar != false
    }

    private fun entriesFor(
        state: AppUiState,
        roomList: RoomListState,
        room: RoomFeatureState,
        spaces: SpaceFeatureState,
        contacts: ContactsFeatureState,
        profile: ProfileFeatureState,
        callHistory: CallHistoryState,
        chat: ChatFeatureState,
        actions: ZynaRootActions,
        dependencies: ZynaRenderDependencies,
        preferences: ZynaRootPreferences
    ): List<ZynaScreenEntry> {
        val stack = state.navigationStack
        return stack.mapIndexed { index, route ->
            val isTop = index == stack.lastIndex
            when (route) {
                AppRoute.Login -> loginEntry(state, actions)
                is AppRoute.RecoveryKey -> recoveryEntry(state, actions, route)
                is AppRoute.SessionSecurity -> sessionSecurityEntry(state, actions, route)
                AppRoute.Contacts -> contactsEntry(
                    roomList,
                    contacts,
                    actions,
                    dependencies
                )
                is AppRoute.UserProfile -> userProfileEntry(
                    state,
                    roomList,
                    profile.user,
                    contacts,
                    actions,
                    dependencies,
                    route
                )
                AppRoute.Calls -> callsEntry(callHistory, actions, dependencies)
                AppRoute.Rooms -> roomsEntry(
                    state = state,
                    roomList = roomList,
                    spaces = spaces,
                    actions = actions,
                    dependencies = dependencies,
                    title = "Chats",
                    onBack = null,
                    withBottomPadding = isTop && state.navState.showsTabs
                )
                AppRoute.CreateRoom -> createRoomEntry(
                    creation = room.createRoom,
                    actions = actions,
                    dependencies = dependencies
                )
                AppRoute.ForwardPicker -> roomsEntry(
                    state = state,
                    roomList = roomList,
                    spaces = spaces,
                    actions = actions,
                    dependencies = dependencies,
                    title = "Forward to",
                    onBack = { actions.navigation.onNavigateBack() },
                    withBottomPadding = false
                )
                AppRoute.Profile -> profileEntry(profile.own, actions, dependencies)
                AppRoute.EditProfile -> editProfileEntry(
                    ownProfile = profile.own,
                    isDiscardConfirmationVisible =
                        profile.own.editSessionId != 0L &&
                            state.pendingEditProfileExit?.editSessionId ==
                            profile.own.editSessionId,
                    actions = actions,
                    dependencies = dependencies
                )
                AppRoute.Settings -> settingsEntry(state, actions, preferences)
                AppRoute.ChatThemeSettings -> chatThemeSettingsEntry(actions, preferences)
                is AppRoute.RoomDetails -> roomDetailsEntry(
                    room.details,
                    room.leave,
                    roomList,
                    actions,
                    dependencies,
                    route
                )
                is AppRoute.EditRoomProfile -> editRoomProfileEntry(
                    editor = room.profileEditor,
                    actions = actions,
                    dependencies = dependencies,
                    route = route
                )
                is AppRoute.RoomMembers -> roomMembersEntry(
                    roomMembers = room.members,
                    roomDetails = room.details,
                    actions = actions,
                    dependencies = dependencies,
                    route = route
                )
                is AppRoute.RoomMemberDetails -> roomMemberDetailsEntry(
                    moderation = room.memberModeration,
                    contacts = contacts,
                    actions = actions,
                    dependencies = dependencies,
                    route = route
                )
                is AppRoute.RoomPermissions -> roomPermissionsEntry(
                    permissions = room.permissions,
                    roomDetails = room.details,
                    roomList = roomList,
                    actions = actions,
                    route = route
                )
                is AppRoute.RoomRoleManagement -> roomRoleManagementEntry(
                    roomMembers = room.members,
                    roles = room.roles,
                    actions = actions,
                    dependencies = dependencies,
                    route = route
                )
                is AppRoute.InviteRoomMembers -> inviteMembersEntry(
                    inviteMembers = room.inviteMembers,
                    actions = actions,
                    dependencies = dependencies,
                    roomId = route.roomId,
                    canSkip = false
                )
                is AppRoute.InviteCreatedRoomMembers -> inviteMembersEntry(
                    inviteMembers = room.inviteMembers,
                    actions = actions,
                    dependencies = dependencies,
                    roomId = route.roomId,
                    canSkip = true
                )
                is AppRoute.Space -> spaceEntry(
                    spaces = spaces,
                    actions = actions,
                    dependencies = dependencies,
                    route = route
                )
                is AppRoute.SpaceJoinPreview -> spaceJoinPreviewEntry(
                    spaces = spaces,
                    actions = actions,
                    dependencies = dependencies,
                    route = route
                )
                is AppRoute.SpaceLeave -> spaceLeaveEntry(
                    spaces = spaces,
                    actions = actions,
                    route = route
                )
                is AppRoute.Chat -> chatEntry(
                    state,
                    roomList,
                    chat,
                    actions,
                    dependencies,
                    preferences,
                    route
                )
            }
        }
    }

    private fun loginEntry(state: AppUiState, actions: ZynaRootActions): ZynaScreenEntry {
        return composeEntry("login") {
            ZynaAndroidTheme {
                LoginScreen(
                    isBusy = state.isBusy,
                    errorMessage = state.errorMessage,
                    onLogin = actions.app.onLogin
                )
            }
        }
    }

    private fun recoveryEntry(
        state: AppUiState,
        actions: ZynaRootActions,
        route: AppRoute.RecoveryKey
    ): ZynaScreenEntry {
        return composeEntry("recovery:${route.userId}") {
            ZynaAndroidTheme {
                SessionSecurityScreen(
                    userId = route.userId,
                    state = state.sessionSecurity,
                    onAction = actions.app.onSessionSecurityAction
                )
            }
        }
    }

    private fun sessionSecurityEntry(
        state: AppUiState,
        actions: ZynaRootActions,
        route: AppRoute.SessionSecurity
    ): ZynaScreenEntry {
        return composeEntry("security:${route.userId}") {
            ZynaAndroidTheme {
                SessionSecurityScreen(
                    userId = route.userId,
                    state = state.sessionSecurity,
                    onAction = actions.app.onSessionSecurityAction
                )
            }
        }
    }

    private fun contactsEntry(
        roomList: RoomListState,
        contacts: ContactsFeatureState,
        actions: ZynaRootActions,
        dependencies: ZynaRenderDependencies
    ): ZynaScreenEntry {
        return ZynaScreenEntry(
            key = "contacts:root",
            createView = { context -> ContactsScreenView(context) },
            updateView = { view ->
                (view as ContactsScreenView).render(
                    state = ContactsScreenViewState(
                        contacts = contacts.directory.contactsFor(roomList.rooms),
                        searchQuery = contacts.directory.searchQuery,
                        isSearching = contacts.directory.isSearching,
                        errorMessage = contacts.directRoomAction.errorMessage
                            ?: contacts.directory.searchErrorMessage,
                        actionUserId = contacts.directRoomAction.activeUserId,
                        matrixMediaLoader = dependencies.matrixMediaLoader,
                        bottomContentPaddingPx = dp(ZynaTabBarView.BASE_HEIGHT_DP) + bottomInset
                    ),
                    actions = ContactsScreenViewActions(
                        onSearchQueryChanged = actions.contacts.onSearchQueryChanged,
                        onOpenProfile = { contact ->
                            actions.profile.user.onOpen(
                                contact.userId,
                                contact.displayName,
                                contact.avatarUrl
                            )
                        },
                        onOpenChat = actions.contacts.onOpenChat,
                        onCall = actions.contacts.onCall
                    )
                )
            }
        )
    }

    private fun callsEntry(
        callHistory: CallHistoryState,
        actions: ZynaRootActions,
        dependencies: ZynaRenderDependencies
    ): ZynaScreenEntry {
        return ZynaScreenEntry(
            key = "calls:root",
            createView = { context -> CallsScreenView(context) },
            updateView = { view ->
                (view as CallsScreenView).render(
                    state = CallsScreenViewState(
                        calls = callHistory.calls,
                        matrixMediaLoader = dependencies.matrixMediaLoader,
                        bottomContentPaddingPx = dp(ZynaTabBarView.BASE_HEIGHT_DP) + bottomInset
                    ),
                    actions = CallsScreenViewActions(
                        onOpenRoom = actions.calls.onOpenHistoryRoom,
                        onCall = actions.calls.onCallHistoryItem
                    )
                )
            }
        )
    }

    private fun userProfileEntry(
        state: AppUiState,
        roomList: RoomListState,
        userProfile: UserProfileState,
        contacts: ContactsFeatureState,
        actions: ZynaRootActions,
        dependencies: ZynaRenderDependencies,
        route: AppRoute.UserProfile
    ): ZynaScreenEntry {
        return ZynaScreenEntry(
            key = "user:${route.userId}",
            createView = { context -> UserProfileScreenView(context) },
            updateView = { view ->
                (view as UserProfileScreenView).render(
                    state = UserProfileScreenViewState(
                        profile = userProfile.takeIf { it.userId == route.userId }
                            ?: UserProfileState(userId = route.userId),
                        roomId = roomList.roomIdForDirectUser(route.userId),
                        presence = state.presenceByUserId[route.userId],
                        actionUserId = contacts.directRoomAction.activeUserId,
                        actionErrorMessage = contacts.directRoomAction.errorMessage,
                        matrixMediaLoader = dependencies.matrixMediaLoader
                    ),
                    actions = UserProfileScreenViewActions(
                        onBack = { actions.navigation.onNavigateBack() },
                        onMessage = actions.profile.user.onOpenChat,
                        onCall = actions.profile.user.onCall,
                        onRefresh = actions.profile.user.onRefresh
                    )
                )
            }
        )
    }

    private fun roomsEntry(
        state: AppUiState,
        roomList: RoomListState,
        spaces: SpaceFeatureState,
        actions: ZynaRootActions,
        dependencies: ZynaRenderDependencies,
        title: String,
        onBack: (() -> Unit)?,
        withBottomPadding: Boolean
    ): ZynaScreenEntry {
        val entryKey = roomsEntryKey(title = title, onBack = onBack)
        val visibleRooms = if (onBack == null) {
            visibleChatRootRooms.project(roomList.rooms, spaces.roots)
        } else {
            roomList.rooms.filterNot { it.isSpace }
        }
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
                        rooms = visibleRooms,
                        isSynchronizing = roomList.isSynchronizing,
                        hasSynchronizationError = roomList.hasSynchronizationError,
                        title = title,
                        showBack = onBack != null,
                        showCreateRoom = onBack == null,
                        matrixMediaLoader = dependencies.matrixMediaLoader,
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
                            actions.rooms.onForwardRoomSelected
                        } else {
                            actions.rooms.onOpenRoom
                        },
                        onCreateRoom = actions.rooms.onCreateRoom.takeIf { onBack == null },
                        onBack = onBack,
                        onRetrySynchronization = actions.rooms.onRetrySynchronization,
                        onVisibleRoomsChanged = { roomIds ->
                            actions.rooms.onVisibleRoomsChanged(entryKey, roomIds)
                        },
                        onVisibleRoomsInactive = {
                            actions.rooms.onVisibleRoomsInactive(entryKey)
                        }
                    )
                )
                ZynaPerfLog.end(updateStart, "root.roomsEntry.updateView") {
                    "title=$title rooms=${visibleRooms.size} " +
                        "synchronizing=${roomList.isSynchronizing}"
                }
            }
        )
    }

    private fun spaceEntry(
        spaces: SpaceFeatureState,
        actions: ZynaRootActions,
        dependencies: ZynaRenderDependencies,
        route: AppRoute.Space
    ): ZynaScreenEntry {
        return ZynaScreenEntry(
            key = "space:${route.spaceId}:parent=${route.parentSpaceId.orEmpty()}",
            createView = { context -> SpaceScreenView(context) },
            updateView = { view ->
                val updateStart = ZynaPerfLog.start()
                val children = spaces.children
                val routeOwnedState = children.takeIf {
                    it.target?.spaceId == route.spaceId &&
                        it.target.parentSpaceId == route.parentSpaceId
                }
                val seed = MatrixSpaceRoom(
                    roomId = route.spaceId,
                    displayName = route.displayName,
                    avatarUrl = route.avatarUrl,
                    topic = route.topic,
                    kind = MatrixSpaceRoomKind.SPACE,
                    membership = MatrixSpaceMembership.JOINED,
                    joinedMemberCount = 0L,
                    childrenCount = 0L,
                    canonicalAlias = null,
                    joinRule = MatrixSpaceJoinRule.UNKNOWN,
                    worldReadable = null,
                    guestCanJoin = false,
                    isDirect = false,
                    isDm = false,
                    via = emptyList()
                )
                (view as SpaceScreenView).render(
                    state = SpaceScreenViewState(
                        spaceId = route.spaceId,
                        presentationKind = if (route.parentSpaceId == null) {
                            SpacePresentationKind.STORYLINE
                        } else {
                            SpacePresentationKind.TRACK
                        },
                        space = routeOwnedState?.space ?: seed,
                        tracks = routeOwnedState?.tracks.orEmpty(),
                        chats = routeOwnedState?.chats.orEmpty(),
                        isKnown = routeOwnedState?.isKnown == true,
                        isPaginating = routeOwnedState?.isPaginating == true,
                        endReached = routeOwnedState?.endReached == true,
                        error = routeOwnedState?.error,
                        matrixMediaLoader = dependencies.matrixMediaLoader
                    ),
                    actions = SpaceScreenViewActions(
                        onBack = { actions.navigation.onNavigateBack() },
                        onOpenDetails = actions.spaces.onOpenDetails,
                        onOpenRoom = actions.spaces.onOpenRoom,
                        onLoadMore = actions.spaces.onLoadMore,
                        onRetry = actions.spaces.onRetry
                    )
                )
                ZynaPerfLog.end(updateStart, "root.spaceEntry.updateView") {
                    "spaceId=${route.spaceId} " +
                        "known=${routeOwnedState?.isKnown == true} " +
                        "rooms=${routeOwnedState?.let { it.tracks.size + it.chats.size } ?: 0}"
                }
            }
        )
    }

    private fun spaceJoinPreviewEntry(
        spaces: SpaceFeatureState,
        actions: ZynaRootActions,
        dependencies: ZynaRenderDependencies,
        route: AppRoute.SpaceJoinPreview
    ): ZynaScreenEntry {
        return ZynaScreenEntry(
            key = "space-join:${route.parentSpaceId.orEmpty()}:${route.roomId}",
            createView = { context -> SpaceJoinPreviewScreenView(context) },
            updateView = { view ->
                val routeState = spaces.join.takeIf { state ->
                    state.target?.let { target ->
                        target.parentSpaceId == route.parentSpaceId &&
                            target.roomId == route.roomId
                    } == true
                } ?: SpaceJoinState()
                val fallback = route.toSpaceRoomSeed()
                (view as SpaceJoinPreviewScreenView).render(
                    state = SpaceJoinPreviewScreenState(
                        join = routeState,
                        fallbackRoom = fallback,
                        isRootSpace = route.parentSpaceId == null,
                        matrixMediaLoader = dependencies.matrixMediaLoader
                    ),
                    actions = SpaceJoinPreviewScreenActions(
                        onBack = { actions.navigation.onNavigateBack() },
                        onPrimaryAction = actions.spaces.onPerformJoinAction,
                        onRetry = actions.spaces.onRetryJoinPreview
                    )
                )
            }
        )
    }

    private fun spaceLeaveEntry(
        spaces: SpaceFeatureState,
        actions: ZynaRootActions,
        route: AppRoute.SpaceLeave
    ): ZynaScreenEntry {
        return ZynaScreenEntry(
            key = "space-leave:${route.parentSpaceId.orEmpty()}:${route.spaceId}",
            createView = { context -> SpaceLeaveScreenView(context) },
            updateView = { view ->
                val routeState = spaces.leave.takeIf { state ->
                    state.target?.let { target ->
                        target.spaceId == route.spaceId &&
                            target.parentSpaceId == route.parentSpaceId
                    } == true
                } ?: SpaceLeaveState()
                (view as SpaceLeaveScreenView).render(
                    state = SpaceLeaveScreenState(
                        leave = routeState,
                        presentationKind = if (route.parentSpaceId == null) {
                            SpacePresentationKind.STORYLINE
                        } else {
                            SpacePresentationKind.TRACK
                        }
                    ),
                    actions = SpaceLeaveScreenActions(
                        onBack = { actions.navigation.onNavigateBack() },
                        onRetry = actions.spaces.onRetrySpaceLeave,
                        onToggleRoom = actions.spaces.onToggleLeaveRoom,
                        onToggleAll = actions.spaces.onToggleAllLeaveRooms,
                        onResolveOwnership = actions.spaces.onResolveSpaceOwnership,
                        onLeave = actions.spaces.onLeaveSpace
                    )
                )
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
        ownProfile: OwnProfileState,
        actions: ZynaRootActions,
        dependencies: ZynaRenderDependencies
    ): ZynaScreenEntry {
        return ZynaScreenEntry(
            key = "profile:root",
            createView = { context -> ProfileScreenView(context) },
            updateView = { view ->
                (view as ProfileScreenView).render(
                    state = ProfileScreenViewState(
                        profile = ownProfile,
                        matrixMediaLoader = dependencies.matrixMediaLoader,
                        bottomContentPaddingPx = dp(ZynaTabBarView.BASE_HEIGHT_DP) + bottomInset
                    ),
                    actions = ProfileScreenViewActions(
                        onEditProfile = actions.profile.own.onOpenEdit,
                        onOpenSettings = actions.profile.own.onOpenSettings,
                        onRefreshProfile = actions.profile.own.onRefresh
                    )
                )
            }
        )
    }

    private fun editProfileEntry(
        ownProfile: OwnProfileState,
        isDiscardConfirmationVisible: Boolean,
        actions: ZynaRootActions,
        dependencies: ZynaRenderDependencies
    ): ZynaScreenEntry {
        return ZynaScreenEntry(
            key = "profile:edit",
            createView = { context -> ProfileEditorScreenView(context) },
            updateView = { view ->
                (view as ProfileEditorScreenView).render(
                    state = ProfileEditorScreenViewState(
                        identityId = ownProfile.userId,
                        displayName = ownProfile.displayName.orEmpty(),
                        editDisplayName = ownProfile.editDisplayName,
                        avatarUrl = ownProfile.avatarUrl.takeUnless {
                            ownProfile.editAvatarChange == OwnProfileAvatarChange.REMOVE
                        },
                        editAvatarLocalPath = ownProfile.editAvatarLocalPath.takeIf {
                            ownProfile.editAvatarChange == OwnProfileAvatarChange.REPLACE
                        },
                        hasAvatar = ownProfile.hasAvatar,
                        editSessionId = ownProfile.editSessionId,
                        isSaving = ownProfile.isSaving,
                        canSave = !ownProfile.isSaving && ownProfile.hasUnsavedChanges,
                        canChangeName = true,
                        canChangeAvatar = true,
                        errorMessage = ownProfile.errorMessage,
                        backLabel = context.getString(com.zyna.app.R.string.profile_edit_back),
                        saveLabel = context.getString(com.zyna.app.R.string.profile_edit_save),
                        title = context.getString(com.zyna.app.R.string.profile_edit_title),
                        nameLabel = context.getString(com.zyna.app.R.string.profile_edit_name_label),
                        changePhotoLabel = context.getString(
                            com.zyna.app.R.string.profile_edit_change_photo
                        ),
                        removePhotoLabel = context.getString(
                            com.zyna.app.R.string.profile_edit_remove_photo
                        ),
                        discardTitle = context.getString(
                            com.zyna.app.R.string.profile_edit_discard_title
                        ),
                        discardMessage = context.getString(
                            com.zyna.app.R.string.profile_edit_discard_message
                        ),
                        keepEditingLabel = context.getString(
                            com.zyna.app.R.string.profile_edit_keep_editing
                        ),
                        discardLabel = context.getString(
                            com.zyna.app.R.string.profile_edit_discard
                        ),
                        matrixMediaLoader = dependencies.matrixMediaLoader,
                        bottomContentPaddingPx = dp(ZynaTabBarView.BASE_HEIGHT_DP) + bottomInset,
                        isDiscardConfirmationVisible = isDiscardConfirmationVisible
                    ),
                    actions = ProfileEditorScreenViewActions(
                        onBack = { actions.navigation.onNavigateBack() },
                        onDisplayNameChanged = actions.profile.own.onDisplayNameChanged,
                        onPickAvatar = actions.profile.own.onPickAvatar,
                        onRemoveAvatar = actions.profile.own.onRemoveAvatar,
                        onSave = actions.profile.own.onSave,
                        onDiscardChangesConfirmed =
                            actions.profile.own.onConfirmEditExit,
                        onDiscardChangesCancelled =
                            actions.profile.own.onCancelEditExit
                    )
                )
            }
        )
    }

    private fun editRoomProfileEntry(
        editor: RoomProfileEditorState,
        actions: ZynaRootActions,
        dependencies: ZynaRenderDependencies,
        route: AppRoute.EditRoomProfile
    ): ZynaScreenEntry {
        return ZynaScreenEntry(
            key = "roomProfile:edit:${route.roomId}",
            createView = { context -> ProfileEditorScreenView(context) },
            updateView = { view ->
                val routeState = editor.takeIf { it.target?.roomId == route.roomId }
                    ?: RoomProfileEditorState()
                val isSpace = routeState.kind == MatrixRoomKind.SPACE
                (view as ProfileEditorScreenView).render(
                    state = ProfileEditorScreenViewState(
                        identityId = route.roomId,
                        displayName = routeState.displayName,
                        editDisplayName = routeState.editDisplayName,
                        avatarUrl = routeState.avatarUrl.takeUnless {
                            routeState.editAvatarChange == RoomProfileAvatarChange.REMOVE
                        },
                        editAvatarLocalPath = routeState.editAvatarLocalPath.takeIf {
                            routeState.editAvatarChange == RoomProfileAvatarChange.REPLACE
                        },
                        hasAvatar = routeState.hasAvatar,
                        editSessionId = routeState.editSessionId,
                        isSaving = routeState.isSaving,
                        canSave = routeState.canSave,
                        canChangeName = routeState.canChangeName,
                        canChangeAvatar = routeState.canChangeAvatar,
                        errorMessage = routeState.error.localizedMessage(context)
                            ?: if (
                                routeState.hasNameChange &&
                                routeState.editDisplayName.trim().isEmpty()
                            ) {
                                context.getString(
                                    com.zyna.app.R.string.room_profile_edit_name_required
                                )
                            } else {
                                null
                            },
                        backLabel = context.getString(com.zyna.app.R.string.profile_edit_back),
                        saveLabel = context.getString(com.zyna.app.R.string.profile_edit_save),
                        title = context.getString(
                            if (isSpace) {
                                com.zyna.app.R.string.room_profile_edit_space_title
                            } else {
                                com.zyna.app.R.string.room_profile_edit_group_title
                            }
                        ),
                        nameLabel = context.getString(
                            if (isSpace) {
                                com.zyna.app.R.string.room_profile_edit_space_name
                            } else {
                                com.zyna.app.R.string.room_profile_edit_group_name
                            }
                        ),
                        changePhotoLabel = context.getString(
                            com.zyna.app.R.string.profile_edit_change_photo
                        ),
                        removePhotoLabel = context.getString(
                            com.zyna.app.R.string.profile_edit_remove_photo
                        ),
                        discardTitle = context.getString(
                            com.zyna.app.R.string.profile_edit_discard_title
                        ),
                        discardMessage = context.getString(
                            com.zyna.app.R.string.room_profile_edit_discard_message
                        ),
                        keepEditingLabel = context.getString(
                            com.zyna.app.R.string.profile_edit_keep_editing
                        ),
                        discardLabel = context.getString(
                            com.zyna.app.R.string.profile_edit_discard
                        ),
                        matrixMediaLoader = dependencies.matrixMediaLoader,
                        bottomContentPaddingPx = bottomInset,
                        isDiscardConfirmationVisible =
                            routeState.isDiscardConfirmationVisible
                    ),
                    actions = ProfileEditorScreenViewActions(
                        onBack = { actions.navigation.onNavigateBack() },
                        onDisplayNameChanged =
                            actions.roomProfileEditor.onDisplayNameChanged,
                        onPickAvatar = actions.roomProfileEditor.onPickAvatar,
                        onRemoveAvatar = actions.roomProfileEditor.onRemoveAvatar,
                        onSave = actions.roomProfileEditor.onSave,
                        onDiscardChangesConfirmed =
                            actions.roomProfileEditor.onConfirmDiscard,
                        onDiscardChangesCancelled =
                            actions.roomProfileEditor.onCancelDiscard
                    )
                )
            }
        )
    }

    private fun createRoomEntry(
        creation: CreateRoomState,
        actions: ZynaRootActions,
        dependencies: ZynaRenderDependencies
    ): ZynaScreenEntry {
        return ZynaScreenEntry(
            key = "room:create",
            createView = { context -> CreateRoomScreenView(context) },
            updateView = { view ->
                (view as CreateRoomScreenView).render(
                    state = CreateRoomScreenViewState(
                        creation = creation,
                        errorMessage = creation.error.localizedMessage(context),
                        matrixMediaLoader = dependencies.matrixMediaLoader,
                        bottomContentPaddingPx = bottomInset
                    ),
                    actions = CreateRoomScreenViewActions(
                        onBack = { actions.navigation.onNavigateBack() },
                        onNameChanged = actions.createRoom.onNameChanged,
                        onTopicChanged = actions.createRoom.onTopicChanged,
                        onAccessChanged = actions.createRoom.onAccessChanged,
                        onPostingPermissionChanged =
                            actions.createRoom.onPostingPermissionChanged,
                        onAliasChanged = actions.createRoom.onAliasChanged,
                        onRetryAliasCheck = actions.createRoom.onRetryAliasCheck,
                        onPickAvatar = actions.createRoom.onPickAvatar,
                        onRemoveAvatar = actions.createRoom.onRemoveAvatar,
                        onCreate = actions.createRoom.onCreate,
                        onDiscardChangesConfirmed = actions.createRoom.onConfirmDiscard,
                        onDiscardChangesCancelled = actions.createRoom.onCancelDiscard
                    )
                )
            }
        )
    }

    private fun settingsEntry(
        state: AppUiState,
        actions: ZynaRootActions,
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
                        isSessionSecurityReady = state.sessionSecurity.readyForEncryptedTraffic,
                        isLoggingOut = state.isLoggingOut,
                        logoutErrorMessage = state.logoutErrorMessage,
                        isLogoutConfirmationVisible = state.logoutConfirmation != null,
                        logoutWarning = state.logoutConfirmation?.warning,
                        bottomContentPaddingPx = dp(ZynaTabBarView.BASE_HEIGHT_DP) + bottomInset
                    ),
                    actions = SettingsScreenViewActions(
                        onBack = { actions.navigation.onNavigateBack() },
                        onOpenChatTheme = actions.settings.onOpenChatTheme,
                        onSelectAppThemeMode = actions.settings.onSelectAppThemeMode,
                        onSelectPresenceProvider = actions.settings.onSelectPresenceProvider,
                        onOpenSessionSecurity = actions.settings.onOpenSessionSecurity,
                        onLogoutRequested = actions.settings.onLogoutRequested,
                        onLogoutConfirmed = actions.settings.onLogoutConfirmed,
                        onLogoutCancelled = actions.settings.onLogoutCancelled
                    )
                )
            }
        )
    }

    private fun chatThemeSettingsEntry(
        actions: ZynaRootActions,
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
                        onBack = { actions.navigation.onNavigateBack() },
                        onSelectTheme = actions.settings.onSelectChatBubbleTheme
                    )
                )
            }
        )
    }

    private fun roomDetailsEntry(
        roomDetails: RoomDetailsState,
        roomLeave: RoomLeaveState,
        roomList: RoomListState,
        actions: ZynaRootActions,
        dependencies: ZynaRenderDependencies,
        route: AppRoute.RoomDetails
    ): ZynaScreenEntry {
        return ZynaScreenEntry(
            key = "roomDetails:${route.roomId}",
            createView = { context -> RoomDetailsScreenView(context) },
            updateView = { view ->
                val routeState = roomDetails.takeIf { it.target?.roomId == route.roomId }
                val seed = routeState?.seed ?: roomList.roomForId(route.roomId)
                val details = routeState?.details ?: seed?.roomDetails
                val displayName = details?.displayName
                    ?: seed?.displayName
                    ?: route.roomId
                val directUserId = details?.directUserId ?: seed?.directUserId
                (view as RoomDetailsScreenView).render(
                    state = RoomDetailsScreenViewState(
                        roomId = route.roomId,
                        displayName = displayName,
                        avatarUrl = details?.avatarUrl ?: seed?.avatarUrl,
                        directUserId = directUserId,
                        kind = details?.kind ?: seed?.kind ?: MatrixRoomKind.GROUP,
                        topic = details?.topic,
                        joinedMemberCount = details?.joinedMemberCount,
                        encryption = details?.encryption,
                        access = details?.access,
                        historyVisibility = details?.historyVisibility,
                        pinnedEventCount = details?.pinnedEventCount,
                        canonicalAlias = details?.canonicalAlias,
                        roomVersion = details?.roomVersion,
                        canInviteMembers = details?.capabilities?.canInviteMembers == true,
                        canEditRoomProfile = details?.let {
                            it.kind != MatrixRoomKind.DIRECT &&
                                (it.capabilities.canChangeName == true ||
                                    it.capabilities.canChangeAvatar == true)
                        } == true,
                        unreadCount = seed?.unreadCount ?: 0,
                        isMarkedUnread = seed?.isMarkedUnread == true,
                        isLoading = routeState?.isLoading ?: (details == null),
                        errorMessage = routeState?.errorMessage,
                        leave = roomLeave.takeIf { it.target?.roomId == route.roomId }
                            ?: RoomLeaveState(),
                        matrixMediaLoader = dependencies.matrixMediaLoader
                    ),
                    actions = RoomDetailsScreenViewActions(
                        onBack = { actions.navigation.onNavigateBack() },
                        onOpenDirectUserProfile = {
                            directUserId?.takeIf { it.isNotBlank() }?.let { userId ->
                                actions.profile.user.onOpen(
                                    userId,
                                    displayName,
                                    details?.avatarUrl ?: seed?.avatarUrl
                                )
                            }
                        },
                        onOpenMembers = actions.roomDetails.onOpenMembers,
                        onOpenProfileEditor = actions.roomDetails.onOpenProfileEditor,
                        onOpenInviteMembers = actions.roomDetails.onOpenInviteMembers,
                        onOpenPermissions = actions.roomDetails.onOpenPermissions,
                        onRetry = actions.roomDetails.onRefresh,
                        onRequestLeave = actions.roomDetails.onRequestLeave,
                        onConfirmLeave = actions.roomDetails.onConfirmLeave,
                        onCancelLeave = actions.roomDetails.onCancelLeave
                    )
                )
            }
        )
    }

    private fun roomMembersEntry(
        roomMembers: RoomMembersState,
        roomDetails: RoomDetailsState,
        actions: ZynaRootActions,
        dependencies: ZynaRenderDependencies,
        route: AppRoute.RoomMembers
    ): ZynaScreenEntry {
        return ZynaScreenEntry(
            key = "roomMembers:${route.roomId}",
            createView = { context -> RoomMembersScreenView(context) },
            updateView = { view ->
                val routeState = roomMembers.takeIf { it.target?.roomId == route.roomId }
                (view as RoomMembersScreenView).render(
                    state = RoomMembersScreenViewState(
                        searchQuery = routeState?.searchQuery.orEmpty(),
                        invitedMembers = routeState?.invitedMembers.orEmpty(),
                        joinedMembers = routeState?.joinedMembers.orEmpty(),
                        bannedMembers = routeState?.bannedMembers.orEmpty(),
                        canInviteMembers = roomDetails
                            .takeIf { it.target?.roomId == route.roomId }
                            ?.details
                            ?.capabilities
                            ?.canInviteMembers == true,
                        isLoading = routeState?.isLoading ?: true,
                        errorMessage = routeState?.errorMessage,
                        canRetry = routeState?.errorMessage != null,
                        matrixMediaLoader = dependencies.matrixMediaLoader
                    ),
                    actions = RoomMembersScreenViewActions(
                        onBack = { actions.navigation.onNavigateBack() },
                        onRetry = actions.roomMembers.onRetry,
                        onOpenInviteMembers = actions.roomMembers.onOpenInviteMembers,
                        onSearchQueryChanged = actions.roomMembers.onSearchQueryChanged,
                        onOpenProfile = actions.roomMembers.onOpenMember
                    )
                )
            }
        )
    }

    private fun roomMemberDetailsEntry(
        moderation: RoomMemberModerationState,
        contacts: ContactsFeatureState,
        actions: ZynaRootActions,
        dependencies: ZynaRenderDependencies,
        route: AppRoute.RoomMemberDetails
    ): ZynaScreenEntry {
        return ZynaScreenEntry(
            key = "roomMember:${route.roomId}:${route.userId}",
            createView = { context -> RoomMemberDetailsScreenView(context) },
            updateView = { view ->
                val routeState = moderation.takeIf { state ->
                    state.target?.let { target ->
                        target.roomId == route.roomId &&
                            target.memberUserId == route.userId
                    } == true
                } ?: RoomMemberModerationState()
                (view as RoomMemberDetailsScreenView).render(
                    state = RoomMemberDetailsScreenViewState(
                        moderation = routeState,
                        directActionUserId = contacts.directRoomAction.activeUserId,
                        directActionErrorMessage = contacts.directRoomAction.errorMessage,
                        errorMessage = routeState.error.localizedMessage(view.context),
                        matrixMediaLoader = dependencies.matrixMediaLoader
                    ),
                    actions = RoomMemberDetailsScreenViewActions(
                        onBack = { actions.navigation.onNavigateBack() },
                        onMessage = actions.roomMemberModeration.onMessage,
                        onRetry = actions.roomMemberModeration.onRetry,
                        onRequestAction = actions.roomMemberModeration.onRequest,
                        onConfirmAction = actions.roomMemberModeration.onConfirm,
                        onCancelAction = actions.roomMemberModeration.onCancel
                    )
                )
            }
        )
    }

    private fun roomPermissionsEntry(
        permissions: RoomPermissionsState,
        roomDetails: RoomDetailsState,
        roomList: RoomListState,
        actions: ZynaRootActions,
        route: AppRoute.RoomPermissions
    ): ZynaScreenEntry {
        return ZynaScreenEntry(
            key = "roomPermissions:${route.roomId}",
            createView = { context -> RoomPermissionsScreenView(context) },
            updateView = { view ->
                val routeState = permissions.takeIf { it.target?.roomId == route.roomId }
                    ?: RoomPermissionsState()
                val detailsKind = roomDetails
                    .takeIf { it.target?.roomId == route.roomId }
                    ?.details
                    ?.kind
                    ?: roomList.roomForId(route.roomId)?.kind
                (view as RoomPermissionsScreenView).render(
                    state = RoomPermissionsScreenViewState(
                        permissions = routeState,
                        isSpace = detailsKind == MatrixRoomKind.SPACE,
                        errorMessage = routeState.error.localizedMessage(context)
                    ),
                    actions = RoomPermissionsScreenViewActions(
                        onBack = { actions.navigation.onNavigateBack() },
                        onOpenMembers = actions.roomPermissions.onOpenRoleManagement,
                        onRetry = actions.roomPermissions.onRetry,
                        onSetPermission = actions.roomPermissions.onSetPermission
                    )
                )
            }
        )
    }

    private fun roomRoleManagementEntry(
        roomMembers: RoomMembersState,
        roles: RoomRoleManagementState,
        actions: ZynaRootActions,
        dependencies: ZynaRenderDependencies,
        route: AppRoute.RoomRoleManagement
    ): ZynaScreenEntry {
        return ZynaScreenEntry(
            key = "roomRoleManagement:${route.roomId}",
            createView = { context -> RoomMembersScreenView(context) },
            updateView = { view ->
                val memberState = roomMembers.takeIf { it.target?.roomId == route.roomId }
                val roleState = roles.takeIf { it.target?.roomId == route.roomId }
                    ?: RoomRoleManagementState()
                val roleError = roleState.error.localizedMessage(view.context)
                (view as RoomMembersScreenView).render(
                    state = RoomMembersScreenViewState(
                        searchQuery = memberState?.searchQuery.orEmpty(),
                        invitedMembers = emptyList(),
                        joinedMembers = memberState?.joinedMembers.orEmpty(),
                        bannedMembers = emptyList(),
                        canInviteMembers = false,
                        isLoading = memberState?.isLoading ?: true,
                        errorMessage = roleError ?: memberState?.errorMessage,
                        canRetry = roleError == null && memberState?.errorMessage != null,
                        roleManagement = roleState,
                        matrixMediaLoader = dependencies.matrixMediaLoader
                    ),
                    actions = RoomMembersScreenViewActions(
                        onBack = { actions.navigation.onNavigateBack() },
                        onRetry = actions.roomRoles.onRetryMembers,
                        onOpenInviteMembers = {},
                        onSearchQueryChanged = actions.roomRoles.onSearchQueryChanged,
                        onOpenProfile = {},
                        onSetRole = actions.roomRoles.onSetRole,
                        onConfirmRoleChange = actions.roomRoles.onConfirmRoleChange,
                        onCancelRoleChange = actions.roomRoles.onCancelRoleChange
                    )
                )
            }
        )
    }

    private fun inviteMembersEntry(
        inviteMembers: InviteMembersState,
        actions: ZynaRootActions,
        dependencies: ZynaRenderDependencies,
        roomId: String,
        canSkip: Boolean
    ): ZynaScreenEntry {
        return ZynaScreenEntry(
            key = "inviteMembers:$roomId:$canSkip",
            createView = { context -> InviteMembersScreenView(context) },
            updateView = { view ->
                val routeState = inviteMembers.takeIf { it.target?.roomId == roomId }
                    ?: InviteMembersState(
                        canInviteMembers = true,
                        isPreparing = !canSkip
                    )
                (view as InviteMembersScreenView).render(
                    state = InviteMembersScreenViewState(
                        searchQuery = routeState.searchQuery,
                        selectedMembers = routeState.selectedMembers,
                        searchResults = routeState.searchResults,
                        canInviteMembers = routeState.canInviteMembers,
                        canSubmit = routeState.canSubmit,
                        isPreparing = routeState.isPreparing,
                        isSearching = routeState.isSearching,
                        isSending = routeState.isSending,
                        preparationErrorMessage = routeState.preparationErrorMessage,
                        permissionErrorMessage = routeState.permissionErrorMessage,
                        searchErrorMessage = routeState.searchErrorMessage,
                        sendErrorMessage = routeState.sendErrorMessage,
                        failedInviteCount = routeState.failedInviteCount,
                        permissionDenied = routeState.permissionDenied,
                        canSkip = canSkip,
                        matrixMediaLoader = dependencies.matrixMediaLoader
                    ),
                    actions = InviteMembersScreenViewActions(
                        onBack = { actions.navigation.onNavigateBack() },
                        onRetryPreparation = actions.inviteMembers.onRetryPreparation,
                        onRetrySearch = actions.inviteMembers.onRetrySearch,
                        onSearchQueryChanged = actions.inviteMembers.onSearchQueryChanged,
                        onToggleSelection = { candidate ->
                            actions.inviteMembers.onToggleSelection(candidate.profile)
                        },
                        onSend = actions.inviteMembers.onSend
                    )
                )
            }
        )
    }

    private fun chatEntry(
        state: AppUiState,
        roomList: RoomListState,
        chat: ChatFeatureState,
        actions: ZynaRootActions,
        dependencies: ZynaRenderDependencies,
        preferences: ZynaRootPreferences,
        route: AppRoute.Chat
    ): ZynaScreenEntry {
        val timeline = chat.timeline.takeIf { it.roomId == route.roomId }
            ?: ChatTimelineState(
                roomId = route.roomId,
                isLoading = true,
                canLoadOlder = false
            )
        val composer = chat.composer.takeIf { it.roomId == route.roomId }
            ?: ChatComposerState(roomId = route.roomId)
        val callInfo = chat.callInfo.takeIf { it.roomId == route.roomId }
            ?: ChatCallInfoState(roomId = route.roomId)
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
                val roomDisplayName = roomList.roomForId(route.roomId)
                    ?.displayName
                    ?.takeIf { it.isNotBlank() }
                    ?: route.displayName
                (view as ChatScreenView).render(
                    state = ChatScreenViewState(
                        roomName = roomDisplayName,
                        roomId = route.roomId,
                        currentUserId = when (val matrixState = state.matrixState) {
                            is MatrixClientState.LoggedIn -> matrixState.userId
                            is MatrixClientState.Syncing -> matrixState.userId
                            MatrixClientState.LoggedOut,
                            is MatrixClientState.Error,
                            MatrixClientState.LoggingIn,
                            is MatrixClientState.RestoringSession -> null
                        },
                        roomSubtitle = PresenceText.label(
                            context = context,
                            status = roomList.roomForId(route.roomId)
                                ?.directUserId
                                ?.let(state.presenceByUserId::get),
                            style = PresenceText.LastSeenStyle.CHAT
                        )
                            ?: route.roomId,
                        messages = timeline.messages,
                        windowChangeOrigin = timeline.windowChangeOrigin,
                        isLoading = timeline.isLoading,
                        isLoadingOlder = timeline.isLoadingWindowOperation,
                        canLoadOlder = timeline.canLoadOlder,
                        canLoadNewer = timeline.canLoadNewer,
                        isAtLiveEdge = timeline.isAtLiveEdge,
                        scrollToLiveEdgeRequested = timeline.scrollToLiveEdgeRequested,
                        errorMessage = timeline.errorMessage,
                        isSendingMessage = composer.isSending,
                        sendErrorMessage = composer.errorMessage,
                        replyTarget = composer.replyTarget,
                        editTarget = composer.editTarget,
                        forwardTarget = composer.forwardTarget,
                        matrixMediaLoader = dependencies.matrixMediaLoader,
                        audioPlaybackController = dependencies.audioPlaybackController,
                        voiceRecorderController = dependencies.voiceRecorderController,
                        jumpTargetEventId = timeline.jumpTargetEventId,
                        callBanner = callInfo.banner,
                        chatBubbleTheme = preferences.chatBubbleTheme
                    ),
                    actions = ChatScreenViewActions(
                        onStartCall = {
                            actions.calls.onStart(route.roomId, roomDisplayName)
                        },
                        onBack = actions.chat.navigation.onClose,
                        onOpenRoomDetails = actions.chat.navigation.onOpenRoomDetails,
                        onLoadOlder = actions.chat.timeline.onLoadOlder,
                        onLoadNewer = actions.chat.timeline.onLoadNewer,
                        onJumpToLiveEdge = actions.chat.timeline.onJumpToLiveEdge,
                        onSendMessage = actions.chat.composer.onSendMessage,
                        onAttachPhotos = actions.chat.composer.onAttachPhotos,
                        onStartVoiceRecording = actions.chat.composer.onStartVoiceRecording,
                        onStopVoiceRecording = actions.chat.composer.onStopVoiceRecording,
                        onCancelVoiceRecording = actions.chat.composer.onCancelVoiceRecording,
                        onFinishVoiceRecordingForSend =
                            actions.chat.composer.onFinishVoiceRecordingForSend,
                        onSendVoiceRecording = actions.chat.composer.onSendVoiceRecording,
                        onToggleVoicePreviewPlayback =
                            actions.chat.composer.onToggleVoicePreviewPlayback,
                        onReplyToMessage = actions.chat.composer.onReplyToMessage,
                        onReplyHeaderClicked = actions.chat.timeline.onReplyHeaderClicked,
                        onCancelReply = actions.chat.composer.onCancelReply,
                        onEditMessage = actions.chat.composer.onEditMessage,
                        onCancelEdit = actions.chat.composer.onCancelEdit,
                        onForwardMessage = actions.chat.composer.onForwardMessage,
                        onCancelForward = actions.chat.composer.onCancelForward,
                        onToggleReaction = actions.chat.messages.onToggleReaction,
                        onRetryOutgoingEnvelope = actions.chat.messages.onRetryOutgoingEnvelope,
                        onDiscardOutgoingEnvelope = actions.chat.messages.onDiscardOutgoingEnvelope,
                        onRedactMessage = actions.chat.messages.onRedactMessage,
                        onRedactMessages = actions.chat.messages.onRedactMessages,
                        onDebugMarkOutgoingEnvelopeFailed =
                            actions.chat.messages.onDebugMarkOutgoingEnvelopeFailed,
                        onVisibleReadReceiptCandidate =
                            actions.chat.timeline.onVisibleReadReceiptCandidate,
                        onJumpTargetConsumed = actions.chat.timeline.onJumpTargetConsumed,
                        onScrollToLiveEdgeConsumed =
                            actions.chat.timeline.onScrollToLiveEdgeConsumed
                    )
                )
                ZynaPerfLog.end(
                    updateStart,
                    "root.chatEntry.updateView"
                ) {
                    "roomId=${route.roomId} messages=${timeline.messages.size} " +
                        "origin=${timeline.windowChangeOrigin}"
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
            AppRoute.CreateRoom -> "CreateRoom"
            AppRoute.ForwardPicker -> "ForwardPicker"
            AppRoute.Login -> "Login"
            AppRoute.EditProfile -> "EditProfile"
            is AppRoute.EditRoomProfile -> "EditRoomProfile(${roomId.takeLast(10)})"
            AppRoute.Profile -> "Profile"
            is AppRoute.RecoveryKey -> "RecoveryKey"
            is AppRoute.SessionSecurity -> "SessionSecurity"
            is AppRoute.RoomDetails -> "RoomDetails(${roomId.takeLast(10)})"
            is AppRoute.RoomMembers -> "RoomMembers(${roomId.takeLast(10)})"
            is AppRoute.RoomMemberDetails ->
                "RoomMemberDetails(${roomId.takeLast(10)},${userId.takeLast(10)})"
            is AppRoute.RoomPermissions -> "RoomPermissions(${roomId.takeLast(10)})"
            is AppRoute.RoomRoleManagement ->
                "RoomRoleManagement(${roomId.takeLast(10)})"
            is AppRoute.InviteRoomMembers -> "InviteRoomMembers(${roomId.takeLast(10)})"
            is AppRoute.InviteCreatedRoomMembers ->
                "InviteCreatedRoomMembers(${roomId.takeLast(10)})"
            is AppRoute.Space ->
                "Space(${spaceId.takeLast(10)},parent=${parentSpaceId?.takeLast(10)})"
            is AppRoute.SpaceJoinPreview ->
                "SpaceJoinPreview(${roomId.takeLast(10)},parent=${parentSpaceId?.takeLast(10)})"
            is AppRoute.SpaceLeave ->
                "SpaceLeave(${spaceId.takeLast(10)},parent=${parentSpaceId?.takeLast(10)})"
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
