package com.zyna.app.ui.roomdetails

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
import kotlin.math.roundToInt

internal data class RoomDetailsScreenViewState(
    val roomId: String,
    val displayName: String,
    val directUserId: String?,
    val unreadCount: Long,
    val isMarkedUnread: Boolean
)

internal data class RoomDetailsScreenViewActions(
    val onBack: () -> Unit,
    val onOpenDirectUserProfile: () -> Unit
)

internal class RoomDetailsScreenView(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private var palette = RoomDetailsPalette.from(context)
    private var statusTopInset = 0
    private var bottomInset = 0
    private var avatarDrawableColor: Int? = null

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
    private val topBarSpacer = View(context)
    private val scrollView = ScrollView(context).apply {
        isFillViewport = true
        clipToPadding = false
    }
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        updatePadding(left = dp(16), right = dp(16), top = dp(18), bottom = dp(24))
    }
    private val avatarText = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 34f
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
    }
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
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val tagRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }
    private val typeTag = tagView()
    private val unreadTag = tagView()
    private val quickActions = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }
    private val membersAction = quickActionView("Members")
    private val mediaAction = quickActionView("Media")
    private val searchAction = quickActionView("Search")
    private val muteAction = quickActionView("Mute")
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
    private val sectionsHeader = sectionHeader("Sections")
    private val membersRow = disabledRow("Members")
    private val pinnedRow = disabledRow("Pinned Messages")
    private val mediaRow = disabledRow("Shared Media")
    private val securityRow = disabledRow("Security & Privacy")

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
            topBarSpacer,
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
            avatarText,
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
        tagRow.addView(typeTag, tagLayoutParams())
        tagRow.addView(unreadTag, tagLayoutParams())
        content.addView(
            tagRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        )
        quickActions.addView(membersAction, quickActionLayoutParams())
        quickActions.addView(mediaAction, quickActionLayoutParams())
        quickActions.addView(searchAction, quickActionLayoutParams())
        quickActions.addView(muteAction, quickActionLayoutParams())
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
        content.addView(unreadRow, rowLayoutParams())
        content.addView(sectionsHeader)
        content.addView(membersRow, rowLayoutParams())
        content.addView(pinnedRow, rowLayoutParams())
        content.addView(mediaRow, rowLayoutParams())
        content.addView(securityRow, rowLayoutParams())

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

    fun render(state: RoomDetailsScreenViewState, actions: RoomDetailsScreenViewActions) {
        backButton.setOnClickListener { actions.onBack() }
        avatarText.text = state.displayName.avatarInitial()
        updateAvatarBackground(state.stableAvatarColor())
        nameText.text = state.displayName
        subtitleText.text = state.roomId
        typeTag.text = if (state.directUserId.isNullOrBlank()) "Group" else "Direct"
        unreadTag.text = state.unreadLabel()
        unreadTag.visibility = if (state.unreadCount > 0 || state.isMarkedUnread) VISIBLE else GONE
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
        unreadRow.detail = state.unreadLabel()
        unreadRow.visibility = if (state.unreadCount > 0 || state.isMarkedUnread) VISIBLE else GONE
        contentDescription = "${state.displayName}. ${state.roomId}"
    }

    private fun applyPalette() {
        setBackgroundColor(palette.background)
        root.setBackgroundColor(palette.background)
        topBar.setBackgroundColor(palette.background)
        backButton.setTextColor(palette.actionText)
        titleText.setTextColor(palette.titleText)
        scrollView.setBackgroundColor(palette.background)
        content.setBackgroundColor(palette.background)
        avatarText.setTextColor(palette.avatarText)
        nameText.setTextColor(palette.titleText)
        subtitleText.setTextColor(palette.secondaryText)
        infoHeader.setTextColor(palette.secondaryText)
        sectionsHeader.setTextColor(palette.secondaryText)
        typeTag.setTextColor(palette.tagText)
        typeTag.background = roundedDrawable(palette.tagFill, TAG_RADIUS_DP)
        unreadTag.setTextColor(palette.tagText)
        unreadTag.background = roundedDrawable(palette.tagFill, TAG_RADIUS_DP)
        listOf(membersAction, mediaAction, searchAction, muteAction).forEach { action ->
            action.setTextColor(palette.actionText)
            action.background = roundedDrawable(palette.surface, CARD_RADIUS_DP)
            action.alpha = DISABLED_ALPHA
        }
        listOf(roomIdRow, directUserRow, unreadRow, membersRow, pinnedRow, mediaRow, securityRow).forEach { row ->
            row.setPalette(palette)
        }
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
            maxLines = 1
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

    private fun updateAvatarBackground(color: Int) {
        if (avatarDrawableColor == color) {
            return
        }
        avatarDrawableColor = color
        avatarText.background = circleDrawable(color)
    }

    private fun circleDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
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

    private fun RoomDetailsScreenViewState.stableAvatarColor(): Int {
        val source = directUserId?.takeIf { it.isNotBlank() } ?: roomId
        val colors = palette.avatarColors
        return colors[source.djb2HashIndex(colors.size)]
    }

    private fun String.avatarInitial(): String {
        return trim().firstOrNull()?.uppercaseChar()?.toString() ?: "#"
    }

    private fun String.djb2HashIndex(size: Int): Int {
        if (size <= 0) return 0
        var hash = 5381
        forEach { char ->
            hash = ((hash shl 5) + hash) + char.code
        }
        return (hash and Int.MAX_VALUE) % size
    }

    private fun dp(value: Int): Int {
        return (value * density).roundToInt()
    }

    private companion object {
        const val TOP_BAR_HEIGHT_DP = 64
        const val AVATAR_SIZE_DP = 96
        const val ROW_HEIGHT_DP = 56
        const val CARD_RADIUS_DP = 8
        const val TAG_RADIUS_DP = 12
        const val DISABLED_ALPHA = 0.48f
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
    val avatarColors: List<Int>,
    val avatarText: Int,
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
                    avatarColors = listOf(
                        Color.rgb(88, 86, 214),
                        Color.rgb(52, 199, 89),
                        Color.rgb(255, 149, 0),
                        Color.rgb(255, 45, 85),
                        Color.rgb(90, 200, 250),
                        Color.rgb(175, 82, 222)
                    ),
                    avatarText = Color.WHITE,
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
                    avatarColors = listOf(
                        Color.rgb(88, 86, 214),
                        Color.rgb(52, 199, 89),
                        Color.rgb(255, 149, 0),
                        Color.rgb(255, 45, 85),
                        Color.rgb(0, 122, 255),
                        Color.rgb(175, 82, 222)
                    ),
                    avatarText = Color.WHITE,
                    tagFill = Color.rgb(231, 224, 236),
                    tagText = Color.rgb(73, 69, 79)
                )
            }
        }
    }
}
