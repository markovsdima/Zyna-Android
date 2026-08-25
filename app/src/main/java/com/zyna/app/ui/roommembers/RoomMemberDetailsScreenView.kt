package com.zyna.app.ui.roommembers

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.zyna.app.R
import com.zyna.app.data.media.MatrixMediaLoader
import com.zyna.app.data.matrix.MatrixRoomMember
import com.zyna.app.data.matrix.MatrixRoomMemberMembership
import com.zyna.app.data.matrix.MatrixRoomMemberModerationAction
import com.zyna.app.data.matrix.MatrixRoomMemberRole
import com.zyna.app.ui.avatar.MatrixAvatarView
import com.zyna.app.ui.settings.SettingsPalette
import kotlin.math.roundToInt

internal data class RoomMemberDetailsScreenViewState(
    val moderation: RoomMemberModerationState,
    val directActionUserId: String?,
    val directActionErrorMessage: String?,
    val errorMessage: String?,
    val matrixMediaLoader: MatrixMediaLoader?
)

internal data class RoomMemberDetailsScreenViewActions(
    val onBack: () -> Unit,
    val onMessage: () -> Unit,
    val onRetry: () -> Unit,
    val onRequestAction: (MatrixRoomMemberModerationAction) -> Unit,
    val onConfirmAction: (String?) -> Unit,
    val onCancelAction: () -> Unit
)

internal class RoomMemberDetailsScreenView(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private var palette = SettingsPalette.from(context)
    private var statusTopInset = 0
    private var bottomInset = 0
    private var confirmationDialog: AlertDialog? = null
    private var presentedAction: MatrixRoomMemberModerationAction? = null

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val topBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val backButton = TextView(context).apply {
        text = context.getString(R.string.room_member_details_back)
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
        isClickable = true
        isFocusable = true
    }
    private val titleText = TextView(context).apply {
        text = context.getString(R.string.room_member_details_title)
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = true
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val topBarSpacer = View(context)
    private val scrollView = ScrollView(context).apply {
        isFillViewport = true
        clipToPadding = false
    }
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        updatePadding(left = dp(20), right = dp(20), top = dp(28), bottom = dp(24))
    }
    private val avatarView = MatrixAvatarView(context)
    private val nameText = TextView(context).apply {
        textSize = 26f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = true
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val userIdText = TextView(context).apply {
        textSize = 14f
        gravity = Gravity.CENTER
        includeFontPadding = true
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.MIDDLE
    }
    private val roleText = TextView(context).apply {
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = true
        updatePadding(left = dp(14), right = dp(14), top = dp(7), bottom = dp(7))
    }
    private val messageButton = actionButton(context.getString(R.string.room_member_message))
    private val moderationTitle = sectionTitle(context.getString(R.string.room_member_moderation))
    private val cancelInviteButton =
        actionButton(context.getString(R.string.room_member_cancel_invite))
    private val kickButton = actionButton(context.getString(R.string.room_member_kick))
    private val banButton = actionButton(context.getString(R.string.room_member_ban))
    private val unbanButton = actionButton(context.getString(R.string.room_member_unban))
    private val statusText = TextView(context).apply {
        textSize = 14f
        gravity = Gravity.CENTER
        includeFontPadding = true
        maxLines = 3
    }
    private val retryButton = actionButton(context.getString(R.string.room_member_retry))

    init {
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
            LinearLayout.LayoutParams(dp(TOP_BAR_SIDE_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT)
        )
        topBar.addView(
            titleText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        )
        topBar.addView(
            topBarSpacer,
            LinearLayout.LayoutParams(dp(TOP_BAR_SIDE_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT)
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
            LinearLayout.LayoutParams(dp(112), dp(112)).apply {
                bottomMargin = dp(18)
            }
        )
        content.addView(nameText, matchWrapParams())
        content.addView(
            userIdText,
            matchWrapParams().apply { topMargin = dp(4) }
        )
        content.addView(
            roleText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        )
        content.addView(
            messageButton,
            matchWrapParams().apply { topMargin = dp(24) }
        )
        content.addView(
            moderationTitle,
            matchWrapParams().apply { topMargin = dp(28) }
        )
        listOf(cancelInviteButton, kickButton, banButton, unbanButton).forEach { button ->
            content.addView(
                button,
                matchWrapParams().apply { topMargin = dp(10) }
            )
        }
        content.addView(
            statusText,
            matchWrapParams().apply { topMargin = dp(18) }
        )
        content.addView(
            retryButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        )

        applyPalette()
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            if (statusTopInset != systemBars.top) {
                statusTopInset = systemBars.top
                updateTopBarHeight()
            }
            if (bottomInset != systemBars.bottom) {
                bottomInset = systemBars.bottom
                updateContentPadding()
            }
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    override fun onDetachedFromWindow() {
        confirmationDialog?.dismiss()
        confirmationDialog = null
        presentedAction = null
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        palette = SettingsPalette.from(context)
        applyPalette()
    }

    fun render(
        state: RoomMemberDetailsScreenViewState,
        actions: RoomMemberDetailsScreenViewActions
    ) {
        val moderation = state.moderation
        val member = moderation.member
        val isOpeningChat = member != null && state.directActionUserId == member.userId
        val isBusy = moderation.isSaving || isOpeningChat

        backButton.setOnClickListener { actions.onBack() }
        backButton.isEnabled = !moderation.isSaving
        backButton.alpha = if (moderation.isSaving) DISABLED_ALPHA else 1f
        messageButton.setOnClickListener { actions.onMessage() }
        retryButton.setOnClickListener { actions.onRetry() }
        cancelInviteButton.setOnClickListener {
            actions.onRequestAction(MatrixRoomMemberModerationAction.KICK)
        }
        kickButton.setOnClickListener {
            actions.onRequestAction(MatrixRoomMemberModerationAction.KICK)
        }
        banButton.setOnClickListener {
            actions.onRequestAction(MatrixRoomMemberModerationAction.BAN)
        }
        unbanButton.setOnClickListener {
            actions.onRequestAction(MatrixRoomMemberModerationAction.UNBAN)
        }

        val displayName = member?.displayNameOrUserId
            ?: moderation.target?.memberUserId.orEmpty()
        titleText.text = displayName.ifBlank {
            context.getString(R.string.room_member_details_title)
        }
        nameText.text = displayName
        userIdText.text = member?.userId ?: moderation.target?.memberUserId.orEmpty()
        avatarView.render(
            userId = member?.userId ?: moderation.target?.memberUserId.orEmpty(),
            displayName = member?.displayName,
            avatarUrl = member?.avatarUrl,
            localAvatarPath = null,
            matrixMediaLoader = state.matrixMediaLoader,
            sizePx = dp(112)
        )
        roleText.text = member?.roleLabel().orEmpty()
        roleText.visibility = if (member == null) INVISIBLE else VISIBLE

        messageButton.visibility = if (moderation.canSendMessage) VISIBLE else GONE
        messageButton.isEnabled = moderation.canSendMessage && !isBusy
        messageButton.alpha = if (isBusy) DISABLED_ALPHA else 1f

        val canKick =
            moderation.isActionAvailable(MatrixRoomMemberModerationAction.KICK)
        val canBan =
            moderation.isActionAvailable(MatrixRoomMemberModerationAction.BAN)
        val canUnban =
            moderation.isActionAvailable(MatrixRoomMemberModerationAction.UNBAN)
        val isInvite = member?.membership == MatrixRoomMemberMembership.INVITED
        cancelInviteButton.visibility = if (canKick && isInvite) VISIBLE else GONE
        kickButton.visibility = if (canKick && !isInvite) VISIBLE else GONE
        banButton.visibility = if (canBan) VISIBLE else GONE
        unbanButton.visibility = if (canUnban) VISIBLE else GONE
        val hasModerationActions = canKick || canBan || canUnban
        moderationTitle.visibility = if (hasModerationActions) VISIBLE else GONE
        listOf(cancelInviteButton, kickButton, banButton, unbanButton).forEach { button ->
            button.isEnabled = !isBusy
            button.alpha = if (isBusy) DISABLED_ALPHA else 1f
        }

        statusText.text = when {
            state.directActionErrorMessage != null -> state.directActionErrorMessage
            state.errorMessage != null -> state.errorMessage
            moderation.savingAction != null -> moderation.savingAction.savingLabel()
            isOpeningChat -> context.getString(R.string.room_member_opening_chat)
            moderation.isLoading && member == null ->
                context.getString(R.string.room_member_loading)
            else -> ""
        }
        statusText.visibility = if (statusText.text.isNullOrBlank()) GONE else VISIBLE
        retryButton.visibility =
            if (moderation.error == RoomMemberModerationError.LOAD) VISIBLE else GONE
        contentDescription = "$displayName. ${userIdText.text}. ${roleText.text}"

        renderConfirmation(moderation.pendingConfirmation, actions)
    }

    private fun renderConfirmation(
        pending: PendingRoomMemberModeration?,
        actions: RoomMemberDetailsScreenViewActions
    ) {
        if (pending == null) {
            confirmationDialog?.dismiss()
            confirmationDialog = null
            presentedAction = null
            return
        }
        if (
            confirmationDialog?.isShowing == true &&
            presentedAction == pending.action
        ) {
            return
        }
        confirmationDialog?.dismiss()
        val input = EditText(context).apply {
            hint = context.getString(R.string.room_member_reason_hint)
            inputType =
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP
        }
        val inputContainer = FrameLayout(context).apply {
            updatePadding(left = dp(24), right = dp(24))
            addView(
                input,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val action = pending.action
        val dialog = AlertDialog.Builder(context)
            .setTitle(pending.confirmationTitle())
            .setMessage(pending.confirmationMessage())
            .setView(inputContainer)
            .setNegativeButton(R.string.common_cancel) { _, _ ->
                actions.onCancelAction()
            }
            .setPositiveButton(pending.confirmationButton()) { _, _ ->
                actions.onConfirmAction(input.text?.toString())
            }
            .create()
        dialog.setOnCancelListener { actions.onCancelAction() }
        dialog.setOnDismissListener {
            if (confirmationDialog === dialog) {
                confirmationDialog = null
                presentedAction = null
            }
        }
        confirmationDialog = dialog
        presentedAction = action
        dialog.show()
        if (action != MatrixRoomMemberModerationAction.UNBAN) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(destructiveTextColor())
        }
    }

    private fun MatrixRoomMember.roleLabel(): String {
        if (membership == MatrixRoomMemberMembership.BANNED) {
            return context.getString(R.string.room_member_banned)
        }
        if (membership == MatrixRoomMemberMembership.INVITED) {
            return context.getString(R.string.room_member_invited)
        }
        return when (role) {
            MatrixRoomMemberRole.CREATOR -> context.getString(R.string.room_member_role_creator)
            MatrixRoomMemberRole.OWNER -> context.getString(R.string.room_member_role_owner)
            MatrixRoomMemberRole.ADMIN -> context.getString(R.string.room_member_role_admin)
            MatrixRoomMemberRole.MODERATOR -> {
                context.getString(R.string.room_member_role_moderator)
            }
            MatrixRoomMemberRole.MEMBER -> context.getString(R.string.room_roles_member)
        }
    }

    private fun PendingRoomMemberModeration.confirmationTitle(): String {
        val title = if (
            action == MatrixRoomMemberModerationAction.KICK &&
            membership == MatrixRoomMemberMembership.INVITED
        ) {
            R.string.room_member_cancel_invite_confirm_title
        } else {
            when (action) {
                MatrixRoomMemberModerationAction.KICK -> R.string.room_member_kick_confirm_title
                MatrixRoomMemberModerationAction.BAN -> R.string.room_member_ban_confirm_title
                MatrixRoomMemberModerationAction.UNBAN -> R.string.room_member_unban_confirm_title
            }
        }
        return context.getString(
            title,
            displayName
        )
    }

    private fun PendingRoomMemberModeration.confirmationMessage(): String {
        val message = if (
            action == MatrixRoomMemberModerationAction.KICK &&
            membership == MatrixRoomMemberMembership.INVITED
        ) {
            R.string.room_member_cancel_invite_confirm_message
        } else {
            when (action) {
                MatrixRoomMemberModerationAction.KICK -> R.string.room_member_kick_confirm_message
                MatrixRoomMemberModerationAction.BAN -> R.string.room_member_ban_confirm_message
                MatrixRoomMemberModerationAction.UNBAN -> {
                    R.string.room_member_unban_confirm_message
                }
            }
        }
        return context.getString(
            message
        )
    }

    private fun PendingRoomMemberModeration.confirmationButton(): Int {
        return when {
            action == MatrixRoomMemberModerationAction.KICK &&
                membership == MatrixRoomMemberMembership.INVITED -> {
                R.string.room_member_cancel_invite
            }
            action == MatrixRoomMemberModerationAction.KICK -> R.string.room_member_kick
            action == MatrixRoomMemberModerationAction.BAN -> R.string.room_member_ban
            else -> R.string.room_member_unban
        }
    }

    private fun MatrixRoomMemberModerationAction.savingLabel(): String {
        return context.getString(
            when (this) {
                MatrixRoomMemberModerationAction.KICK -> R.string.room_member_removing
                MatrixRoomMemberModerationAction.BAN -> R.string.room_member_banning
                MatrixRoomMemberModerationAction.UNBAN -> R.string.room_member_unbanning
            }
        )
    }

    private fun applyPalette() {
        setBackgroundColor(palette.background)
        root.setBackgroundColor(palette.background)
        topBar.setBackgroundColor(palette.background)
        scrollView.setBackgroundColor(palette.background)
        content.setBackgroundColor(palette.background)
        backButton.setTextColor(palette.actionText)
        titleText.setTextColor(palette.titleText)
        avatarView.setPaletteBackground(palette.background)
        nameText.setTextColor(palette.titleText)
        userIdText.setTextColor(palette.secondaryText)
        roleText.setTextColor(palette.actionText)
        roleText.background = roundedDrawable(palette.selectedFill, dp(12))
        messageButton.setTextColor(palette.actionText)
        messageButton.background = roundedDrawable(palette.selectedFill, dp(14))
        moderationTitle.setTextColor(palette.secondaryText)
        cancelInviteButton.setTextColor(warningTextColor())
        cancelInviteButton.background = roundedDrawable(palette.surface, dp(14))
        kickButton.setTextColor(warningTextColor())
        kickButton.background = roundedDrawable(palette.surface, dp(14))
        banButton.setTextColor(destructiveTextColor())
        banButton.background = roundedDrawable(palette.surface, dp(14))
        unbanButton.setTextColor(palette.actionText)
        unbanButton.background = roundedDrawable(palette.surface, dp(14))
        statusText.setTextColor(palette.secondaryText)
        retryButton.setTextColor(palette.actionText)
        retryButton.background = roundedDrawable(palette.surface, dp(12))
    }

    private fun actionButton(text: CharSequence): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = true
            isClickable = true
            isFocusable = true
            minHeight = dp(48)
            updatePadding(left = dp(18), right = dp(18), top = dp(11), bottom = dp(11))
        }
    }

    private fun sectionTitle(text: CharSequence): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = true
            gravity = Gravity.START
        }
    }

    private fun matchWrapParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
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
            left = dp(20),
            right = dp(20),
            top = dp(28),
            bottom = bottomInset + dp(24)
        )
    }

    private fun roundedDrawable(color: Int, radiusPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx.toFloat()
        }
    }

    private fun destructiveTextColor(): Int {
        return if (isDarkMode()) Color.rgb(255, 180, 171) else Color.rgb(186, 26, 26)
    }

    private fun warningTextColor(): Int {
        return if (isDarkMode()) Color.rgb(255, 184, 108) else Color.rgb(130, 75, 0)
    }

    private fun isDarkMode(): Boolean {
        return (
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            ) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private companion object {
        const val TOP_BAR_HEIGHT_DP = 64
        const val TOP_BAR_SIDE_WIDTH_DP = 88
        const val DISABLED_ALPHA = 0.48f
    }
}
