package com.zyna.app.ui.spaces

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
import kotlin.math.roundToInt

internal data class SpaceLeaveScreenState(
    val leave: SpaceLeaveState,
    val presentationKind: SpacePresentationKind
)

internal data class SpaceLeaveScreenActions(
    val onBack: () -> Unit,
    val onRetry: () -> Unit,
    val onToggleRoom: (String) -> Unit,
    val onToggleAll: () -> Unit,
    val onResolveOwnership: () -> Unit,
    val onLeave: () -> Unit
)

/** Safe Space-leave flow backed by the SDK's complete descendant graph. */
internal class SpaceLeaveScreenView(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private var palette = SpaceLeavePalette.from(context)
    private var topInset = 0
    private var bottomInset = 0

    private val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val topBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val backButton = actionText(context.getString(R.string.common_cancel))
    private val titleText = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = true
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val trailingSpacer = View(context)
    private val scrollView = ScrollView(context).apply {
        isFillViewport = true
        clipToPadding = false
    }
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        updatePadding(left = dp(20), right = dp(20), top = dp(24), bottom = dp(24))
    }
    private val heading = TextView(context).apply {
        textSize = 24f
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = true
    }
    private val description = TextView(context).apply {
        textSize = 15f
        includeFontPadding = true
    }
    private val quickAction = actionText("").apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
    private val status = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 15f
        includeFontPadding = true
    }
    private val retryButton = filledButton(context.getString(R.string.common_retry))
    private val roomsContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val roomRows = linkedMapOf<String, SpaceLeaveRoomRow>()
    private var roomRowOrder: List<String> = emptyList()
    private val ownershipButton = filledButton("")
    private val leaveButton = filledButton(context.getString(R.string.space_leave_submit))

    init {
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        root.addView(topBar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(TOP_BAR_DP)))
        topBar.addView(backButton, LinearLayout.LayoutParams(dp(SIDE_DP), LayoutParams.MATCH_PARENT))
        topBar.addView(titleText, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        topBar.addView(trailingSpacer, LinearLayout.LayoutParams(dp(SIDE_DP), LayoutParams.MATCH_PARENT))
        root.addView(scrollView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        scrollView.addView(
            content,
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        content.addView(heading)
        content.addView(description, matchWrap(top = 8))
        content.addView(quickAction, matchWrap(top = 12))
        content.addView(status, matchWrap(top = 24))
        content.addView(retryButton, matchWrap(top = 12))
        content.addView(roomsContainer, matchWrap(top = 16))
        content.addView(ownershipButton, matchWrap(top = 20))
        content.addView(leaveButton, matchWrap(top = 20))
        applyPalette()

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            if (topInset != systemBars.top) {
                topInset = systemBars.top
                topBar.updatePadding(top = topInset)
                topBar.layoutParams = (topBar.layoutParams as LinearLayout.LayoutParams).apply {
                    height = dp(TOP_BAR_DP) + topInset
                }
            }
            if (bottomInset != systemBars.bottom) {
                bottomInset = systemBars.bottom
                content.updatePadding(
                    left = dp(20),
                    right = dp(20),
                    top = dp(24),
                    bottom = dp(24) + bottomInset
                )
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
        palette = SpaceLeavePalette.from(context)
        applyPalette()
    }

    fun render(state: SpaceLeaveScreenState, actions: SpaceLeaveScreenActions) {
        val leave = state.leave
        val name = leave.root?.room?.displayName
            ?.takeIf(String::isNotBlank)
            ?: leave.target?.displayName.orEmpty()
        titleText.text = context.getString(
            if (state.presentationKind == SpacePresentationKind.STORYLINE) {
                R.string.space_leave_storyline_title
            } else {
                R.string.space_leave_track_title
            }
        )
        backButton.setOnClickListener { actions.onBack() }
        backButton.isEnabled = !leave.isLeaving

        if (leave.needsOwnerChange) {
            heading.text = context.getString(R.string.space_leave_last_owner_heading)
            description.text = context.getString(
                if (leave.areCreatorsPrivileged) {
                    R.string.space_leave_last_owner_message
                } else {
                    R.string.space_leave_last_admin_message
                },
                name
            )
        } else {
            heading.text = context.getString(R.string.space_leave_heading, name)
            description.text = context.getString(R.string.space_leave_description)
        }

        status.text = when {
            leave.isLoading -> context.getString(R.string.space_leave_loading)
            leave.isLeaving -> context.getString(R.string.space_leave_in_progress)
            leave.error == SpaceLeaveError.LOAD ->
                context.getString(R.string.space_leave_load_error)
            leave.error == SpaceLeaveError.LEAVE -> context.getString(R.string.space_leave_error)
            leave.error == SpaceLeaveError.PARTIAL_LEAVE ->
                context.getString(R.string.space_leave_partial_error)
            else -> ""
        }
        status.visibility = if (status.text.isBlank()) GONE else VISIBLE
        retryButton.visibility = if (leave.error != null) VISIBLE else GONE
        retryButton.setOnClickListener { actions.onRetry() }

        val descendants = leave.descendants
        quickAction.visibility = if (
            !leave.isLoading && !leave.needsOwnerChange &&
            leave.selectableRoomIds.isNotEmpty() && leave.error == null
        ) VISIBLE else GONE
        quickAction.text = context.getString(
            if (leave.areAllSelected) R.string.space_leave_deselect_all
            else R.string.space_leave_select_all
        )
        quickAction.setOnClickListener { actions.onToggleAll() }

        if (leave.target == null) {
            clearRoomRows()
        } else if (!leave.isLoading && leave.root != null) {
            renderRoomRows(
                items = descendants,
                actions = actions,
                isInteractive = leave.error == null
            )
        }
        roomsContainer.visibility = if (
            !leave.needsOwnerChange &&
            (leave.error == null || leave.error == SpaceLeaveError.PARTIAL_LEAVE)
        ) VISIBLE else GONE

        ownershipButton.visibility = if (leave.needsOwnerChange) VISIBLE else GONE
        ownershipButton.text = context.getString(
            if (leave.areCreatorsPrivileged) R.string.space_leave_choose_owner
            else R.string.space_leave_roles_permissions
        )
        ownershipButton.setOnClickListener { actions.onResolveOwnership() }

        leaveButton.visibility = if (leave.root != null && !leave.needsOwnerChange) VISIBLE else GONE
        leaveButton.isEnabled = leave.canLeave
        leaveButton.alpha = if (leave.canLeave) 1f else DISABLED_ALPHA
        leaveButton.text = if (leave.selectedRoomIds.isEmpty()) {
            context.getString(R.string.space_leave_submit)
        } else {
            resources.getQuantityString(
                R.plurals.space_leave_submit_with_rooms,
                leave.selectedRoomIds.size,
                leave.selectedRoomIds.size
            )
        }
        leaveButton.setOnClickListener(
            if (leave.canLeave) View.OnClickListener { actions.onLeave() } else null
        )
    }

    private fun renderRoomRows(
        items: List<SelectableSpaceLeaveRoom>,
        actions: SpaceLeaveScreenActions,
        isInteractive: Boolean
    ) {
        val desiredIds = items.map { it.value.room.roomId }
        val desiredIdSet = desiredIds.toHashSet()
        roomRows.keys.toList().forEach { roomId ->
            if (roomId !in desiredIdSet) roomRows.remove(roomId)
        }
        if (roomRowOrder != desiredIds) {
            roomsContainer.removeAllViews()
            desiredIds.forEach { roomId ->
                val row = roomRows.getOrPut(roomId, ::SpaceLeaveRoomRow)
                roomsContainer.addView(
                    row.view,
                    LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(ROOM_ROW_DP))
                )
            }
            roomRowOrder = desiredIds
        }
        items.forEach { item ->
            roomRows.getValue(item.value.room.roomId).render(item, actions, isInteractive)
        }
    }

    private fun clearRoomRows() {
        roomsContainer.removeAllViews()
        roomRows.clear()
        roomRowOrder = emptyList()
    }

    private inner class SpaceLeaveRoomRow {
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            updatePadding(left = dp(14), right = dp(14))
        }
        private val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        private val name = TextView(context).apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        private val detail = TextView(context).apply {
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        private val indicator = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        }
        private var isSelectable = false

        init {
            texts.addView(name)
            texts.addView(detail)
            view.addView(texts, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            view.addView(
                indicator,
                LinearLayout.LayoutParams(dp(40), LayoutParams.MATCH_PARENT)
            )
            applyPalette()
        }

        fun render(
            item: SelectableSpaceLeaveRoom,
            actions: SpaceLeaveScreenActions,
            isInteractive: Boolean
        ) {
            isSelectable = item.isSelectable
            val isEnabled = item.isSelectable && isInteractive
            view.alpha = if (isEnabled) 1f else DISABLED_ALPHA
            view.isEnabled = isEnabled
            view.isClickable = isEnabled
            view.isFocusable = isEnabled
            view.setOnClickListener(
                if (isEnabled) {
                    View.OnClickListener { actions.onToggleRoom(item.value.room.roomId) }
                } else {
                    null
                }
            )
            name.text = item.value.room.displayName
            detail.text = context.getString(
                if (!item.isSelectable) R.string.space_leave_last_owner_room
                else if (item.isSelected) R.string.space_leave_room_selected
                else R.string.space_leave_room_not_selected
            )
            indicator.text = when {
                !item.isSelectable -> "!"
                item.isSelected -> "✓"
                else -> "○"
            }
            applyPalette()
        }

        fun applyPalette() {
            view.background = roundedDrawable(palette.surface, 10)
            name.setTextColor(palette.primaryText)
            detail.setTextColor(palette.secondaryText)
            indicator.setTextColor(
                if (isSelectable) palette.actionText else palette.destructiveText
            )
        }
    }

    private fun applyPalette() {
        setBackgroundColor(palette.background)
        root.setBackgroundColor(palette.background)
        topBar.setBackgroundColor(palette.background)
        scrollView.setBackgroundColor(palette.background)
        content.setBackgroundColor(palette.background)
        backButton.setTextColor(palette.actionText)
        titleText.setTextColor(palette.primaryText)
        heading.setTextColor(palette.primaryText)
        description.setTextColor(palette.secondaryText)
        quickAction.setTextColor(palette.actionText)
        status.setTextColor(palette.secondaryText)
        retryButton.setTextColor(palette.actionText)
        retryButton.background = roundedDrawable(palette.surface, 10)
        ownershipButton.setTextColor(palette.actionText)
        ownershipButton.background = roundedDrawable(palette.surface, 10)
        leaveButton.setTextColor(Color.WHITE)
        leaveButton.background = roundedDrawable(palette.destructiveText, 10)
        roomRows.values.forEach(SpaceLeaveRoomRow::applyPalette)
    }

    private fun actionText(value: String): TextView {
        return TextView(context).apply {
            text = value
            gravity = Gravity.CENTER
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            isClickable = true
            isFocusable = true
        }
    }

    private fun filledButton(value: String): TextView {
        return actionText(value).apply {
            minHeight = dp(52)
            updatePadding(left = dp(16), right = dp(16), top = dp(12), bottom = dp(12))
        }
    }

    private fun matchWrap(top: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
        }
    }

    private fun roundedDrawable(color: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
        }
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private companion object {
        const val TOP_BAR_DP = 64
        const val SIDE_DP = 92
        const val ROOM_ROW_DP = 72
        const val DISABLED_ALPHA = 0.48f
    }
}

private data class SpaceLeavePalette(
    val background: Int,
    val surface: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val actionText: Int,
    val destructiveText: Int
) {
    companion object {
        fun from(context: Context): SpaceLeavePalette {
            val dark = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            return if (dark) {
                SpaceLeavePalette(
                    background = Color.rgb(18, 18, 22),
                    surface = Color.rgb(31, 31, 36),
                    primaryText = Color.rgb(232, 225, 229),
                    secondaryText = Color.rgb(202, 196, 208),
                    actionText = Color.rgb(208, 188, 255),
                    destructiveText = Color.rgb(186, 26, 26)
                )
            } else {
                SpaceLeavePalette(
                    background = Color.WHITE,
                    surface = Color.rgb(247, 242, 250),
                    primaryText = Color.rgb(29, 27, 32),
                    secondaryText = Color.rgb(73, 69, 79),
                    actionText = Color.rgb(103, 80, 164),
                    destructiveText = Color.rgb(186, 26, 26)
                )
            }
        }
    }
}
