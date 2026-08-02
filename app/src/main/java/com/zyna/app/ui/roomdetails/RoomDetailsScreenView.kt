package com.zyna.app.ui.roomdetails

import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.zyna.app.R
import com.zyna.app.data.media.MatrixMediaLoader
import com.zyna.app.data.matrix.MatrixRoomAccess
import com.zyna.app.data.matrix.MatrixRoomEncryption
import com.zyna.app.data.matrix.MatrixRoomHistoryVisibility
import com.zyna.app.data.matrix.MatrixRoomKind
import com.zyna.app.ui.avatar.MatrixAvatarView
import kotlin.math.roundToInt

internal data class RoomDetailsScreenViewState(
    val roomId: String,
    val displayName: String,
    val avatarUrl: String?,
    val directUserId: String?,
    val kind: MatrixRoomKind,
    val topic: String?,
    val joinedMemberCount: Long?,
    val encryption: MatrixRoomEncryption?,
    val access: MatrixRoomAccess?,
    val historyVisibility: MatrixRoomHistoryVisibility?,
    val pinnedEventCount: Int?,
    val canonicalAlias: String?,
    val roomVersion: String?,
    val canInviteMembers: Boolean,
    val canEditRoomProfile: Boolean,
    val unreadCount: Long,
    val isMarkedUnread: Boolean,
    val isLoading: Boolean,
    val errorMessage: String?,
    val leave: RoomLeaveState,
    val matrixMediaLoader: MatrixMediaLoader?
)

internal data class RoomDetailsScreenViewActions(
    val onBack: () -> Unit,
    val onOpenDirectUserProfile: () -> Unit,
    val onOpenProfileEditor: () -> Unit,
    val onOpenMembers: () -> Unit,
    val onOpenInviteMembers: () -> Unit,
    val onOpenPermissions: () -> Unit,
    val onRetry: () -> Unit,
    val onRequestLeave: () -> Unit,
    val onConfirmLeave: () -> Unit,
    val onCancelLeave: () -> Unit
)

internal class RoomDetailsScreenView(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private var palette = RoomDetailsPalette.from(context)
    private var statusTopInset = 0
    private var bottomInset = 0
    private var leaveConfirmationDialog: AlertDialog? = null
    private var leaveConfirmationKey: String? = null

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val topBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val backButton = TextView(context).apply {
        gravity = Gravity.CENTER
        text = "Back"
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        isClickable = true
        isFocusable = true
    }
    private val titleText = TextView(context).apply {
        gravity = Gravity.CENTER
        text = "Room Details"
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = true
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val editButton = TextView(context).apply {
        gravity = Gravity.CENTER
        text = context.getString(R.string.room_profile_edit_action)
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        isClickable = true
        isFocusable = true
    }
    private val scrollView = ScrollView(context).apply {
        isFillViewport = true
        clipToPadding = false
    }
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        updatePadding(left = dp(16), right = dp(16), top = dp(18), bottom = dp(24))
    }
    private val avatarView = MatrixAvatarView(context)
    private val nameText = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 24f
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = true
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val subtitleText = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 14f
        includeFontPadding = true
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        minHeight = dp(SUBTITLE_MIN_HEIGHT_DP)
    }
    private val roomVersionText = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 13f
        includeFontPadding = true
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        minHeight = dp(ROOM_VERSION_MIN_HEIGHT_DP)
    }
    private val tagRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }
    private val typeTag = tagView()
    private val encryptionTag = tagView()
    private val statusText = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 14f
        includeFontPadding = true
        maxLines = 3
    }
    private val retryButton = TextView(context).apply {
        gravity = Gravity.CENTER
        text = "Retry"
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = true
        isClickable = true
        isFocusable = true
        updatePadding(left = dp(16), right = dp(16), top = dp(9), bottom = dp(9))
    }
    private val quickActions = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }
    private val membersAction = quickActionView("Members").apply {
        isEnabled = true
        isClickable = true
        isFocusable = true
    }
    private val inviteAction = quickActionView(
        context.getString(R.string.invite_members_action)
    ).apply {
        isEnabled = true
        isClickable = true
        isFocusable = true
    }
    private val mediaAction = quickActionView("Media")
    private val searchAction = quickActionView("Search")
    private val infoHeader = sectionHeader("Info")
    private val roomIdRow = RoomDetailsRowView(context).apply {
        title = "Room ID"
        showsAccessory = false
    }
    private val directUserRow = RoomDetailsRowView(context).apply {
        title = "User Profile"
        showsAccessory = true
        isClickable = true
        isFocusable = true
    }
    private val unreadRow = RoomDetailsRowView(context).apply {
        title = "Unread"
        showsAccessory = false
    }
    private val addressRow = RoomDetailsRowView(context).apply {
        title = "Address"
        showsAccessory = false
    }
    private val sectionsHeader = sectionHeader("Sections")
    private val membersRow = RoomDetailsRowView(context).apply {
        title = "Members"
        showsAccessory = true
        isClickable = true
        isFocusable = true
    }
    private val permissionsRow = RoomDetailsRowView(context).apply {
        title = context.getString(R.string.room_permissions_title)
        showsAccessory = true
        isClickable = true
        isFocusable = true
    }
    private val pinnedRow = disabledRow("Pinned Messages")
    private val mediaRow = disabledRow("Shared Media")
    private val securityRow = disabledRow("Security & Privacy")
    private val historyRow = disabledRow("Room History")
    private val leaveRow = RoomDetailsRowView(context).apply {
        title = context.getString(R.string.room_leave_action)
        showsAccessory = false
        isClickable = true
        isFocusable = true
    }

    init {
        setBackgroundColor(palette.background)
        addView(
            root,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            topBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(TOP_BAR_HEIGHT_DP)
            )
        )
        topBar.addView(
            backButton,
            LinearLayout.LayoutParams(
                dp(88),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        topBar.addView(
            titleText,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
            )
        )
        topBar.addView(
            editButton,
            LinearLayout.LayoutParams(
                dp(88),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        scrollView.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        content.addView(
            avatarView,
            LinearLayout.LayoutParams(dp(AVATAR_SIZE_DP), dp(AVATAR_SIZE_DP))
        )
        content.addView(
            nameText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
            }
        )
        content.addView(
            subtitleText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(2)
            }
        )
        content.addView(
            roomVersionText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(2)
            }
        )
        tagRow.addView(typeTag, tagLayoutParams())
        tagRow.addView(encryptionTag, tagLayoutParams())
        content.addView(
            tagRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        )
        content.addView(
            statusText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
        )
        content.addView(
            retryButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        )
        quickActions.addView(membersAction, quickActionLayoutParams())
        quickActions.addView(inviteAction, quickActionLayoutParams())
        quickActions.addView(mediaAction, quickActionLayoutParams())
        quickActions.addView(searchAction, quickActionLayoutParams())
        content.addView(
            quickActions,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(72)
            ).apply {
                topMargin = dp(22)
            }
        )
        content.addView(infoHeader)
        content.addView(roomIdRow, rowLayoutParams())
        content.addView(directUserRow, rowLayoutParams())
        content.addView(addressRow, rowLayoutParams())
        content.addView(unreadRow, rowLayoutParams())
        content.addView(sectionsHeader)
        content.addView(membersRow, rowLayoutParams())
        content.addView(permissionsRow, rowLayoutParams())
        content.addView(pinnedRow, rowLayoutParams())
        content.addView(mediaRow, rowLayoutParams())
        content.addView(securityRow, rowLayoutParams())
        content.addView(historyRow, rowLayoutParams())
        content.addView(
            leaveRow,
            rowLayoutParams().apply { topMargin = dp(18) }
        )

        applyPalette()
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val nextTopInset = systemBars.top
            val nextBottomInset = systemBars.bottom
            if (statusTopInset != nextTopInset) {
                statusTopInset = nextTopInset
                updateTopBarHeight()
            }
            if (bottomInset != nextBottomInset) {
                bottomInset = nextBottomInset
                updateContentPadding()
            }
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        palette = RoomDetailsPalette.from(context)
        applyPalette()
    }

    override fun onDetachedFromWindow() {
        leaveConfirmationDialog?.setOnDismissListener(null)
        leaveConfirmationDialog?.dismiss()
        leaveConfirmationDialog = null
        leaveConfirmationKey = null
        super.onDetachedFromWindow()
    }

    fun render(state: RoomDetailsScreenViewState, actions: RoomDetailsScreenViewActions) {
        backButton.setOnClickListener { actions.onBack() }
        val showsEdit = state.kind != MatrixRoomKind.DIRECT && state.canEditRoomProfile
        editButton.visibility = if (showsEdit) VISIBLE else INVISIBLE
        editButton.isEnabled = showsEdit
        editButton.setOnClickListener(
            if (showsEdit) View.OnClickListener { actions.onOpenProfileEditor() } else null
        )
        retryButton.setOnClickListener { actions.onRetry() }
        val leave = state.leave.takeIf { it.target?.roomId == state.roomId }
            ?: RoomLeaveState()
        leaveRow.isEnabled = !leave.isBusy
        leaveRow.alpha = if (leave.isBusy) DISABLED_ALPHA else 1f
        leaveRow.detail = when {
            leave.isPreparing -> context.getString(R.string.room_leave_preparing)
            leave.isLeaving -> context.getString(R.string.room_leave_in_progress)
            leave.error == RoomLeaveError.PREPARE ->
                context.getString(R.string.room_leave_prepare_error)
            leave.error == RoomLeaveError.LEAVE -> context.getString(R.string.room_leave_error)
            else -> null
        }
        leaveRow.setOnClickListener(
            if (!leave.isBusy) View.OnClickListener { actions.onRequestLeave() } else null
        )
        renderLeaveConfirmation(state, leave, actions)
        val showsMembers = state.kind != MatrixRoomKind.DIRECT
        val showsInvite = showsMembers && state.canInviteMembers
        membersAction.visibility = if (showsMembers) VISIBLE else GONE
        membersAction.isFocusable = showsMembers
        membersRow.visibility = if (showsMembers) VISIBLE else GONE
        membersRow.isFocusable = showsMembers
        if (showsMembers) {
            membersAction.setOnClickListener { actions.onOpenMembers() }
            membersRow.setOnClickListener { actions.onOpenMembers() }
            permissionsRow.setOnClickListener { actions.onOpenPermissions() }
        } else {
            membersAction.setOnClickListener(null)
            membersRow.setOnClickListener(null)
            permissionsRow.setOnClickListener(null)
        }
        permissionsRow.visibility = if (showsMembers) VISIBLE else GONE
        permissionsRow.isFocusable = showsMembers
        inviteAction.visibility = if (showsInvite) VISIBLE else GONE
        inviteAction.isFocusable = showsInvite
        inviteAction.setOnClickListener(
            if (showsInvite) View.OnClickListener { actions.onOpenInviteMembers() } else null
        )
        avatarView.render(
            userId = state.directUserId?.takeIf { it.isNotBlank() } ?: state.roomId,
            displayName = state.displayName,
            avatarUrl = state.avatarUrl,
            localAvatarPath = null,
            matrixMediaLoader = state.matrixMediaLoader,
            sizePx = dp(AVATAR_SIZE_DP)
        )
        nameText.text = state.displayName
        val subtitle = state.subtitle()
        subtitleText.text = subtitle.ifBlank { EMPTY_SUBTITLE_PLACEHOLDER }
        subtitleText.contentDescription = subtitle.takeIf { it.isNotBlank() }
        roomVersionText.text = context.getString(
            R.string.room_profile_room_version,
            state.roomVersion ?: UNKNOWN_ROOM_VERSION
        )
        typeTag.text = state.kind.label()
        encryptionTag.text = state.encryption?.label() ?: loadingValue(state)
        roomIdRow.detail = state.roomId
        directUserRow.detail = state.directUserId.orEmpty()
        val hasDirectUser = !state.directUserId.isNullOrBlank()
        directUserRow.visibility = if (hasDirectUser) VISIBLE else GONE
        directUserRow.isFocusable = hasDirectUser
        if (hasDirectUser) {
            directUserRow.setOnClickListener { actions.onOpenDirectUserProfile() }
        } else {
            directUserRow.setOnClickListener(null)
        }
        addressRow.detail = state.canonicalAlias.orEmpty()
        addressRow.visibility = if (state.canonicalAlias.isNullOrBlank()) GONE else VISIBLE
        unreadRow.detail = state.unreadLabel()
        unreadRow.visibility = if (state.unreadCount > 0 || state.isMarkedUnread) VISIBLE else GONE
        if (showsMembers) {
            membersAction.text = state.joinedMemberCount
                ?.let { count -> "Members\n$count" }
                ?: "Members"
            membersRow.detail = state.joinedMemberCount?.toString() ?: loadingValue(state)
        }
        pinnedRow.detail = state.pinnedEventCount?.toString() ?: loadingValue(state)
        securityRow.detail = listOfNotNull(
            state.encryption?.label(),
            state.access?.label()
        ).joinToString(separator = " · ").ifBlank { loadingValue(state) }
        historyRow.detail = state.historyVisibility?.label() ?: loadingValue(state)
        statusText.text = state.errorMessage.orEmpty()
        statusText.visibility = if (statusText.text.isNullOrBlank()) GONE else VISIBLE
        retryButton.visibility = if (state.errorMessage != null) VISIBLE else GONE
        contentDescription = "${state.displayName}. ${state.roomId}"
    }

    private fun applyPalette() {
        setBackgroundColor(palette.background)
        root.setBackgroundColor(palette.background)
        topBar.setBackgroundColor(palette.background)
        backButton.setTextColor(palette.actionText)
        editButton.setTextColor(palette.actionText)
        titleText.setTextColor(palette.titleText)
        scrollView.setBackgroundColor(palette.background)
        content.setBackgroundColor(palette.background)
        avatarView.setPaletteBackground(palette.background)
        nameText.setTextColor(palette.titleText)
        subtitleText.setTextColor(palette.secondaryText)
        roomVersionText.setTextColor(palette.secondaryText)
        infoHeader.setTextColor(palette.secondaryText)
        sectionsHeader.setTextColor(palette.secondaryText)
        typeTag.setTextColor(palette.tagText)
        typeTag.background = roundedDrawable(palette.tagFill, TAG_RADIUS_DP)
        encryptionTag.setTextColor(palette.tagText)
        encryptionTag.background = roundedDrawable(palette.tagFill, TAG_RADIUS_DP)
        statusText.setTextColor(palette.secondaryText)
        retryButton.setTextColor(palette.actionText)
        retryButton.background = roundedDrawable(palette.surface, CARD_RADIUS_DP)
        membersAction.setTextColor(palette.actionText)
        membersAction.background = roundedDrawable(palette.surface, CARD_RADIUS_DP)
        membersAction.alpha = 1f
        inviteAction.setTextColor(palette.actionText)
        inviteAction.background = roundedDrawable(palette.surface, CARD_RADIUS_DP)
        inviteAction.alpha = 1f
        listOf(mediaAction, searchAction).forEach { action ->
            action.setTextColor(palette.actionText)
            action.background = roundedDrawable(palette.surface, CARD_RADIUS_DP)
            action.alpha = DISABLED_ALPHA
        }
        listOf(
            roomIdRow,
            directUserRow,
            addressRow,
            unreadRow,
            membersRow,
            permissionsRow,
            pinnedRow,
            mediaRow,
            securityRow,
            historyRow,
            leaveRow
        ).forEach { row ->
            row.setPalette(palette)
        }
        leaveRow.setTitleColor(palette.destructiveText)
    }

    private fun renderLeaveConfirmation(
        state: RoomDetailsScreenViewState,
        leave: RoomLeaveState,
        actions: RoomDetailsScreenViewActions
    ) {
        val pending = leave.pendingConfirmation
        if (pending == null) {
            leaveConfirmationDialog?.setOnDismissListener(null)
            leaveConfirmationDialog?.dismiss()
            leaveConfirmationDialog = null
            leaveConfirmationKey = null
            return
        }
        val key = "${state.roomId}:${pending.context.needsOwnershipWarning}"
        if (leaveConfirmationDialog?.isShowing == true && leaveConfirmationKey == key) return
        leaveConfirmationDialog?.setOnDismissListener(null)
        leaveConfirmationDialog?.dismiss()
        leaveConfirmationKey = key
        val needsOwnerWarning = pending.context.needsOwnershipWarning
        val message = when {
            needsOwnerWarning -> R.string.room_leave_last_owner_message
            state.kind == MatrixRoomKind.DIRECT -> R.string.room_leave_direct_message
            state.access == MatrixRoomAccess.PUBLIC -> R.string.room_leave_public_message
            else -> R.string.room_leave_private_message
        }
        leaveConfirmationDialog = AlertDialog.Builder(context)
            .setTitle(
                if (needsOwnerWarning) {
                    context.getString(R.string.room_leave_last_owner_title)
                } else {
                    context.getString(R.string.room_leave_title, state.displayName)
                }
            )
            .setMessage(message)
            .setOnCancelListener { actions.onCancelLeave() }
            .setNegativeButton(R.string.common_cancel) { _, _ -> actions.onCancelLeave() }
            .setPositiveButton(R.string.room_leave_confirm) { _, _ -> actions.onConfirmLeave() }
            .create()
            .also(AlertDialog::show)
    }

    private fun sectionHeader(text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = true
            updatePadding(left = dp(4), right = dp(4), top = dp(22), bottom = dp(6))
        }
    }

    private fun tagView(): TextView {
        return TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            updatePadding(left = dp(10), right = dp(10), top = dp(5), bottom = dp(5))
        }
    }

    private fun quickActionView(text: String): TextView {
        return TextView(context).apply {
            gravity = Gravity.CENTER
            this.text = text
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = true
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            isEnabled = false
        }
    }

    private fun disabledRow(title: String): RoomDetailsRowView {
        return RoomDetailsRowView(context).apply {
            this.title = title
            showsAccessory = false
            isEnabled = false
            alpha = DISABLED_ALPHA
        }
    }

    private fun tagLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = dp(4)
            rightMargin = dp(4)
        }
    }

    private fun quickActionLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.MATCH_PARENT,
            1f
        ).apply {
            leftMargin = dp(4)
            rightMargin = dp(4)
        }
    }

    private fun rowLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(ROW_HEIGHT_DP)
        )
    }

    private fun updateTopBarHeight() {
        topBar.updatePadding(top = statusTopInset)
        val params = topBar.layoutParams as LinearLayout.LayoutParams
        params.height = dp(TOP_BAR_HEIGHT_DP) + statusTopInset
        topBar.layoutParams = params
    }

    private fun updateContentPadding() {
        content.updatePadding(
            left = dp(16),
            right = dp(16),
            top = dp(18),
            bottom = bottomInset + dp(24)
        )
    }

    private fun roundedDrawable(color: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
        }
    }

    private fun RoomDetailsScreenViewState.unreadLabel(): String {
        return when {
            unreadCount > 0 -> if (unreadCount > 99) "99+" else unreadCount.toString()
            isMarkedUnread -> "Marked unread"
            else -> "None"
        }
    }

    private fun RoomDetailsScreenViewState.subtitle(): String {
        return topic?.takeIf { it.isNotBlank() }
            ?: joinedMemberCount
                ?.takeIf { kind != MatrixRoomKind.DIRECT }
                ?.let { count -> "$count members" }
            ?: canonicalAlias.orEmpty()
    }

    private fun loadingValue(state: RoomDetailsScreenViewState): String {
        return if (state.isLoading) "Loading" else "Unavailable"
    }

    private fun MatrixRoomKind.label(): String {
        return when (this) {
            MatrixRoomKind.DIRECT -> "Direct"
            MatrixRoomKind.GROUP -> "Group"
            MatrixRoomKind.SPACE -> "Space"
        }
    }

    private fun MatrixRoomEncryption.label(): String {
        return when (this) {
            MatrixRoomEncryption.ENCRYPTED -> "Encrypted"
            MatrixRoomEncryption.NOT_ENCRYPTED -> "Not encrypted"
            MatrixRoomEncryption.UNKNOWN -> "Encryption unknown"
        }
    }

    private fun MatrixRoomAccess.label(): String {
        return when (this) {
            MatrixRoomAccess.PUBLIC -> "Public"
            MatrixRoomAccess.PRIVATE -> "Private"
            MatrixRoomAccess.ASK_TO_JOIN -> "Ask to join"
            MatrixRoomAccess.RESTRICTED -> "Restricted access"
            MatrixRoomAccess.CUSTOM -> "Custom access"
            MatrixRoomAccess.UNKNOWN -> "Access unknown"
        }
    }

    private fun MatrixRoomHistoryVisibility.label(): String {
        return when (this) {
            MatrixRoomHistoryVisibility.SHARED -> "New members can see history"
            MatrixRoomHistoryVisibility.INVITED -> "History from invite"
            MatrixRoomHistoryVisibility.JOINED -> "History from joining"
            MatrixRoomHistoryVisibility.WORLD_READABLE -> "History visible to anyone"
            MatrixRoomHistoryVisibility.CUSTOM -> "Custom history"
        }
    }

    private fun dp(value: Int): Int {
        return (value * density).roundToInt()
    }

    private companion object {
        const val TOP_BAR_HEIGHT_DP = 64
        const val AVATAR_SIZE_DP = 96
        const val SUBTITLE_MIN_HEIGHT_DP = 20
        const val ROOM_VERSION_MIN_HEIGHT_DP = 19
        const val ROW_HEIGHT_DP = 56
        const val CARD_RADIUS_DP = 8
        const val TAG_RADIUS_DP = 12
        const val DISABLED_ALPHA = 0.48f
        const val EMPTY_SUBTITLE_PLACEHOLDER = "\u00A0"
        const val UNKNOWN_ROOM_VERSION = "—"
    }
}

private class RoomDetailsRowView(context: Context) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private var palette = RoomDetailsPalette.from(context)
    private val titleText = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        textSize = 17f
        includeFontPadding = true
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val detailText = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        textSize = 14f
        includeFontPadding = true
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.MIDDLE
    }
    private val accessoryText = TextView(context).apply {
        gravity = Gravity.CENTER
        text = ">"
        textSize = 18f
        includeFontPadding = false
    }

    var title: String
        get() = titleText.text.toString()
        set(value) {
            titleText.text = value
        }

    var detail: String?
        get() = detailText.text.toString().takeIf { it.isNotBlank() }
        set(value) {
            detailText.text = value.orEmpty()
        }

    var showsAccessory: Boolean
        get() = accessoryText.visibility == VISIBLE
        set(value) {
            accessoryText.visibility = if (value) VISIBLE else GONE
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        updatePadding(left = dp(16), right = dp(10))
        addView(
            titleText,
            LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
            )
        )
        addView(
            detailText,
            LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
            )
        )
        addView(
            accessoryText,
            LayoutParams(
                dp(28),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setPalette(palette)
    }

    fun setPalette(nextPalette: RoomDetailsPalette) {
        palette = nextPalette
        setBackgroundColor(palette.surface)
        titleText.setTextColor(palette.primaryText)
        detailText.setTextColor(palette.secondaryText)
        accessoryText.setTextColor(palette.secondaryText)
    }

    fun setTitleColor(color: Int) {
        titleText.setTextColor(color)
    }

    private fun dp(value: Int): Int {
        return (value * density).roundToInt()
    }
}

private data class RoomDetailsPalette(
    val background: Int,
    val surface: Int,
    val titleText: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val actionText: Int,
    val destructiveText: Int,
    val tagFill: Int,
    val tagText: Int
) {
    companion object {
        fun from(context: Context): RoomDetailsPalette {
            val isDark = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            return if (isDark) {
                RoomDetailsPalette(
                    background = Color.rgb(18, 18, 22),
                    surface = Color.rgb(31, 31, 36),
                    titleText = Color.rgb(232, 225, 229),
                    primaryText = Color.rgb(232, 225, 229),
                    secondaryText = Color.rgb(202, 196, 208),
                    actionText = Color.rgb(208, 188, 255),
                    destructiveText = Color.rgb(255, 180, 171),
                    tagFill = Color.argb(42, 255, 255, 255),
                    tagText = Color.rgb(232, 225, 229)
                )
            } else {
                RoomDetailsPalette(
                    background = Color.WHITE,
                    surface = Color.rgb(247, 242, 250),
                    titleText = Color.rgb(29, 27, 32),
                    primaryText = Color.rgb(29, 27, 32),
                    secondaryText = Color.rgb(73, 69, 79),
                    actionText = Color.rgb(103, 80, 164),
                    destructiveText = Color.rgb(186, 26, 26),
                    tagFill = Color.rgb(231, 224, 236),
                    tagText = Color.rgb(73, 69, 79)
                )
            }
        }
    }
}
