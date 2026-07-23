package com.zyna.app.ui.roompermissions

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.zyna.app.R
import com.zyna.app.data.matrix.MatrixRoomPermission
import com.zyna.app.ui.settings.SettingsPalette
import kotlin.math.roundToInt

internal data class RoomPermissionsScreenViewState(
    val permissions: RoomPermissionsState,
    val isSpace: Boolean,
    val errorMessage: String?
)

internal data class RoomPermissionsScreenViewActions(
    val onBack: () -> Unit,
    val onOpenMembers: () -> Unit,
    val onRetry: () -> Unit,
    val onSetPermission: (MatrixRoomPermission, RoomPermissionAudience) -> Unit
)

internal class RoomPermissionsScreenView(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private var palette = SettingsPalette.from(context)
    private var statusTopInset = 0
    private var bottomInset = 0
    private var audienceDialog: AlertDialog? = null

    private val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val topBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val backButton = topBarAction(context.getString(R.string.common_back))
    private val titleText = TextView(context).apply {
        text = context.getString(R.string.room_permissions_title)
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = true
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val topBarSpacer = TextView(context)
    private val scrollView = ScrollView(context).apply {
        isFillViewport = true
        clipToPadding = false
    }
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        updatePadding(left = dp(16), right = dp(16), top = dp(12), bottom = dp(24))
    }
    private val rolesHeader = sectionHeader(R.string.room_permissions_roles_section)
    private val membersRolesRow = PermissionRowView(context).apply {
        title = context.getString(R.string.room_permissions_members_roles)
        detail = context.getString(R.string.room_permissions_members_roles_description)
        showsAccessory = true
    }
    private val detailsHeader = sectionHeader(R.string.room_permissions_details_section)
    private val nameRow = permissionRow(R.string.room_permissions_change_name)
    private val avatarRow = permissionRow(R.string.room_permissions_change_avatar)
    private val topicRow = permissionRow(R.string.room_permissions_change_topic)
    private val messagesHeader = sectionHeader(R.string.room_permissions_messages_section)
    private val sendMessagesRow = permissionRow(R.string.room_permissions_send_messages)
    private val redactMessagesRow = permissionRow(R.string.room_permissions_redact_messages)
    private val moderationHeader = sectionHeader(R.string.room_permissions_moderation_section)
    private val inviteMembersRow = permissionRow(R.string.room_permissions_invite_members)
    private val removeMembersRow = permissionRow(R.string.room_permissions_remove_members)
    private val banMembersRow = permissionRow(R.string.room_permissions_ban_members)
    private val spaceHeader = sectionHeader(R.string.room_permissions_space_section)
    private val spaceChildrenRow =
        permissionRow(R.string.room_permissions_manage_space_children)
    private val permissionHint = TextView(context).apply {
        textSize = 13f
        includeFontPadding = true
        updatePadding(left = dp(4), right = dp(4), top = dp(12))
    }
    private val statusText = TextView(context).apply {
        textSize = 14f
        includeFontPadding = true
        gravity = Gravity.CENTER
        maxLines = 4
    }
    private val retryButton = TextView(context).apply {
        text = context.getString(R.string.common_retry)
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = true
        isClickable = true
        isFocusable = true
        updatePadding(left = dp(16), right = dp(16), top = dp(9), bottom = dp(9))
    }
    private val progress = ProgressBar(context).apply { isIndeterminate = true }

    private val permissionRows = linkedMapOf(
        MatrixRoomPermission.CHANGE_NAME to nameRow,
        MatrixRoomPermission.CHANGE_AVATAR to avatarRow,
        MatrixRoomPermission.CHANGE_TOPIC to topicRow,
        MatrixRoomPermission.SEND_MESSAGES to sendMessagesRow,
        MatrixRoomPermission.REDACT_MESSAGES to redactMessagesRow,
        MatrixRoomPermission.INVITE_MEMBERS to inviteMembersRow,
        MatrixRoomPermission.REMOVE_MEMBERS to removeMembersRow,
        MatrixRoomPermission.BAN_MEMBERS to banMembersRow,
        MatrixRoomPermission.MANAGE_SPACE_CHILDREN to spaceChildrenRow
    )

    init {
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        root.addView(topBar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(TOP_BAR_HEIGHT_DP)))
        topBar.addView(backButton, topBarSideParams())
        topBar.addView(titleText, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        topBar.addView(topBarSpacer, topBarSideParams())
        root.addView(scrollView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        scrollView.addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        content.addView(rolesHeader, fullWidthWrapParams())
        content.addView(membersRolesRow, rowParams())
        content.addView(detailsHeader, fullWidthWrapParams())
        content.addView(nameRow, rowParams())
        content.addView(avatarRow, rowParams())
        content.addView(topicRow, rowParams())
        content.addView(messagesHeader, fullWidthWrapParams())
        content.addView(sendMessagesRow, rowParams())
        content.addView(redactMessagesRow, rowParams())
        content.addView(moderationHeader, fullWidthWrapParams())
        content.addView(inviteMembersRow, rowParams())
        content.addView(removeMembersRow, rowParams())
        content.addView(banMembersRow, rowParams())
        content.addView(spaceHeader, fullWidthWrapParams())
        content.addView(spaceChildrenRow, rowParams())
        content.addView(permissionHint, fullWidthWrapParams())
        content.addView(statusText, fullWidthWrapParams().apply { topMargin = dp(18) })
        content.addView(retryButton, wrapParams().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(8)
        })
        content.addView(progress, LinearLayout.LayoutParams(dp(30), dp(30)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(14)
        })

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
        audienceDialog?.dismiss()
        audienceDialog = null
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        palette = SettingsPalette.from(context)
        applyPalette()
    }

    fun render(
        state: RoomPermissionsScreenViewState,
        actions: RoomPermissionsScreenViewActions
    ) {
        val feature = state.permissions
        val permissions = feature.permissions

        backButton.setOnClickListener { if (!feature.isSaving) actions.onBack() }
        backButton.isEnabled = !feature.isSaving
        backButton.alpha = if (feature.isSaving) DISABLED_ALPHA else 1f
        membersRolesRow.setOnClickListener {
            if (!feature.isSaving) actions.onOpenMembers()
        }
        membersRolesRow.isEnabled = !feature.isSaving
        membersRolesRow.alpha = if (feature.isSaving) DISABLED_ALPHA else 1f
        membersRolesRow.updateAccessibility()

        permissionRows.forEach { (permission, row) ->
            val level = permissions?.level(permission)
            val canEdit = permissions?.canEdit(permission) == true && !feature.isSaving
            row.detail = level?.let(::audienceLabel)
                ?: context.getString(R.string.room_permissions_value_unavailable)
            row.showsAccessory = canEdit && level != null
            row.isEnabled = canEdit && level != null
            row.alpha = if (row.isEnabled) 1f else DISABLED_ALPHA
            row.setOnClickListener(
                if (row.isEnabled) {
                    View.OnClickListener {
                        showAudiencePicker(
                            permission = permission,
                            title = row.title,
                            current = checkNotNull(level),
                            ownPowerLevel = checkNotNull(permissions).ownPowerLevel,
                            actions = actions
                        )
                    }
                } else {
                    null
                }
            )
            row.updateAccessibility()
        }

        spaceHeader.visibility = if (state.isSpace) VISIBLE else GONE
        spaceChildrenRow.visibility = if (state.isSpace) VISIBLE else GONE
        permissionHint.text = when {
            permissions == null -> context.getString(R.string.room_permissions_loading_hint)
            permissions.canEdit -> context.getString(R.string.room_permissions_edit_hint)
            else -> context.getString(R.string.room_permissions_read_only_hint)
        }
        statusText.text = state.errorMessage.orEmpty()
        statusText.visibility = if (state.errorMessage.isNullOrBlank()) GONE else VISIBLE
        retryButton.visibility =
            if (feature.error == RoomPermissionsError.LOAD) VISIBLE else GONE
        retryButton.setOnClickListener { actions.onRetry() }
        progress.visibility =
            if (feature.isLoading || feature.isRefreshing || feature.isSaving) VISIBLE else GONE
        if (feature.isSaving) {
            audienceDialog?.dismiss()
        }
    }

    private fun showAudiencePicker(
        permission: MatrixRoomPermission,
        title: String,
        current: Long,
        ownPowerLevel: Long,
        actions: RoomPermissionsScreenViewActions
    ) {
        audienceDialog?.dismiss()
        val audiences = RoomPermissionAudience.entries.filter { audience ->
            audience.powerLevel <= ownPowerLevel
        }
        if (audiences.isEmpty()) return
        val labels = audiences.map(::audienceLabel).toTypedArray()
        val selected = audiences.indexOf(RoomPermissionAudience.fromPowerLevel(current))
        audienceDialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setSingleChoiceItems(labels, selected) { dialog, index ->
                dialog.dismiss()
                actions.onSetPermission(permission, audiences[index])
            }
            .setNegativeButton(R.string.common_cancel, null)
            .create()
            .also { dialog ->
                dialog.setOnDismissListener {
                    if (audienceDialog === dialog) audienceDialog = null
                }
                dialog.show()
            }
    }

    private fun audienceLabel(level: Long): String {
        return audienceLabel(RoomPermissionAudience.fromPowerLevel(level))
    }

    private fun audienceLabel(audience: RoomPermissionAudience): String {
        return context.getString(
            when (audience) {
                RoomPermissionAudience.EVERYONE -> R.string.room_permissions_everyone
                RoomPermissionAudience.MODERATORS -> R.string.room_permissions_moderators
                RoomPermissionAudience.ADMINISTRATORS ->
                    R.string.room_permissions_administrators
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
        listOf(rolesHeader, detailsHeader, messagesHeader, moderationHeader, spaceHeader).forEach {
            it.setTextColor(palette.secondaryText)
        }
        permissionHint.setTextColor(palette.secondaryText)
        statusText.setTextColor(ERROR_COLOR)
        retryButton.setTextColor(palette.actionText)
        retryButton.background = roundedDrawable(palette.surface, 10)
        membersRolesRow.setPalette(palette)
        permissionRows.values.forEach { it.setPalette(palette) }
    }

    private fun topBarAction(text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = true
            isClickable = true
            isFocusable = true
        }
    }

    private fun sectionHeader(textRes: Int): TextView {
        return TextView(context).apply {
            text = context.getString(textRes)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = true
            updatePadding(left = dp(4), right = dp(4), top = dp(22), bottom = dp(7))
        }
    }

    private fun permissionRow(textRes: Int): PermissionRowView {
        return PermissionRowView(context).apply {
            title = context.getString(textRes)
            showsAccessory = true
        }
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
            top = dp(12),
            bottom = bottomInset + dp(24)
        )
    }

    private fun topBarSideParams() =
        LinearLayout.LayoutParams(dp(TOP_BAR_SIDE_WIDTH_DP), LayoutParams.MATCH_PARENT)

    private fun rowParams() =
        LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP))

    private fun fullWidthWrapParams() =
        LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

    private fun wrapParams() =
        LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    private fun roundedDrawable(color: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private companion object {
        const val TOP_BAR_HEIGHT_DP = 56
        const val TOP_BAR_SIDE_WIDTH_DP = 88
        const val ROW_HEIGHT_DP = 58
        const val DISABLED_ALPHA = 0.48f
        const val ERROR_COLOR = 0xFFE5484D.toInt()
    }
}

private class PermissionRowView(context: Context) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val titleText = TextView(context).apply {
        textSize = 16f
        includeFontPadding = true
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
    }
    private val detailText = TextView(context).apply {
        textSize = 14f
        includeFontPadding = true
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
    }
    private val accessory = TextView(context).apply {
        text = "›"
        textSize = 24f
        includeFontPadding = false
        gravity = Gravity.CENTER
    }

    var title: String
        get() = titleText.text.toString()
        set(value) {
            titleText.text = value
        }

    var detail: String
        get() = detailText.text.toString()
        set(value) {
            detailText.text = value
        }

    var showsAccessory: Boolean
        get() = accessory.visibility == VISIBLE
        set(value) {
            accessory.visibility = if (value) VISIBLE else GONE
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        updatePadding(left = dp(14), right = dp(10))
        addView(titleText, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(detailText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply {
            marginStart = dp(12)
        })
        addView(accessory, LayoutParams(dp(22), LayoutParams.MATCH_PARENT).apply {
            marginStart = dp(4)
        })
    }

    fun setPalette(palette: SettingsPalette) {
        titleText.setTextColor(palette.primaryText)
        detailText.setTextColor(palette.secondaryText)
        accessory.setTextColor(palette.secondaryText)
        background = GradientDrawable().apply {
            setColor(palette.surface)
            cornerRadius = dp(10).toFloat()
        }
    }

    fun updateAccessibility() {
        contentDescription = listOf(title, detail)
            .filter { it.isNotBlank() }
            .joinToString(separator = ". ")
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()
}
