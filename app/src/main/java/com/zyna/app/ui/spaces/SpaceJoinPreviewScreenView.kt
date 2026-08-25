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
import com.zyna.app.data.media.MatrixMediaLoader
import com.zyna.app.data.matrix.MatrixSpaceJoinRule
import com.zyna.app.data.matrix.MatrixSpaceMembership
import com.zyna.app.data.matrix.MatrixSpaceRoom
import com.zyna.app.data.matrix.MatrixSpaceRoomKind
import com.zyna.app.ui.avatar.MatrixAvatarShape
import com.zyna.app.ui.avatar.MatrixAvatarView
import kotlin.math.roundToInt

internal data class SpaceJoinPreviewScreenState(
    val join: SpaceJoinState,
    val fallbackRoom: MatrixSpaceRoom,
    val isRootSpace: Boolean,
    val matrixMediaLoader: MatrixMediaLoader?
)

internal data class SpaceJoinPreviewScreenActions(
    val onBack: () -> Unit,
    val onPrimaryAction: () -> Unit,
    val onRetry: () -> Unit
)

internal class SpaceJoinPreviewScreenView(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private var palette = SpacePalette.from(context)
    private var topInset = 0
    private var bottomInset = 0

    private val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val topBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        updatePadding(left = dp(8), right = dp(8))
    }
    private val backButton = TextView(context).apply {
        text = context.getString(R.string.common_back)
        gravity = Gravity.CENTER
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        isClickable = true
        isFocusable = true
    }
    private val titleText = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
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
        gravity = Gravity.CENTER_HORIZONTAL
        updatePadding(left = dp(20), right = dp(20), top = dp(24), bottom = dp(24))
    }
    private val avatar = MatrixAvatarView(context)
    private val nameText = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 25f
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val kindText = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 14f
    }
    private val topicText = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 15f
        maxLines = 4
        ellipsize = TextUtils.TruncateAt.END
    }
    private val infoCard = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val accessRow = SpacePreviewInfoRow(context)
    private val membersRow = SpacePreviewInfoRow(context)
    private val contentsRow = SpacePreviewInfoRow(context)
    private val addressRow = SpacePreviewInfoRow(context)
    private val statusText = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 14f
        maxLines = 4
    }
    private val errorText = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 14f
        maxLines = 3
        visibility = View.GONE
    }
    private val retryButton = TextView(context).apply {
        text = context.getString(R.string.common_retry)
        gravity = Gravity.CENTER
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        isClickable = true
        isFocusable = true
        visibility = View.GONE
        updatePadding(left = dp(18), right = dp(18), top = dp(10), bottom = dp(10))
    }
    private val primaryButton = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        minHeight = dp(50)
        isClickable = true
        isFocusable = true
    }

    init {
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        root.addView(topBar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(52)))
        topBar.addView(backButton, LinearLayout.LayoutParams(dp(76), LayoutParams.MATCH_PARENT))
        topBar.addView(titleText, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        topBar.addView(trailingSpacer, LinearLayout.LayoutParams(dp(76), LayoutParams.MATCH_PARENT))
        root.addView(scrollView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        scrollView.addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        content.addView(avatar, linearParams(dp(96), dp(96)))
        content.addView(nameText, fullWidthWrap().apply { topMargin = dp(14) })
        content.addView(kindText, fullWidthWrap().apply { topMargin = dp(4) })
        content.addView(topicText, fullWidthWrap().apply { topMargin = dp(12) })
        content.addView(infoCard, fullWidthWrap().apply { topMargin = dp(22) })
        infoCard.addView(accessRow, fullWidthWrap())
        infoCard.addView(membersRow, fullWidthWrap())
        infoCard.addView(contentsRow, fullWidthWrap())
        infoCard.addView(addressRow, fullWidthWrap())
        content.addView(statusText, fullWidthWrap().apply { topMargin = dp(20) })
        content.addView(errorText, fullWidthWrap().apply { topMargin = dp(10) })
        content.addView(retryButton, wrapParams().apply { topMargin = dp(8) })
        content.addView(primaryButton, fullWidthWrap().apply { topMargin = dp(20) })

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topInset = bars.top
            bottomInset = bars.bottom
            topBar.updatePadding(left = dp(8), top = topInset, right = dp(8))
            topBar.layoutParams = (topBar.layoutParams as LinearLayout.LayoutParams).apply {
                height = dp(52) + topInset
            }
            scrollView.updatePadding(bottom = bottomInset)
            insets
        }
        applyPalette()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        palette = SpacePalette.from(context)
        applyPalette()
    }

    fun render(
        state: SpaceJoinPreviewScreenState,
        actions: SpaceJoinPreviewScreenActions
    ) {
        val room = state.join.room ?: state.fallbackRoom
        val isSpace = room.kind == MatrixSpaceRoomKind.SPACE
        titleText.text = context.getString(
            when {
                state.isRootSpace -> R.string.space_preview_story_title
                isSpace -> R.string.space_preview_track_title
                else -> R.string.space_preview_chat_title
            }
        )
        nameText.text = room.displayName
        kindText.text = context.getString(
            when {
                state.isRootSpace -> R.string.space_storyline
                isSpace -> R.string.space_track
                else -> R.string.space_preview_chat
            }
        )
        topicText.text = room.topic.orEmpty()
        topicText.visibility = if (room.topic.isNullOrBlank()) View.GONE else View.VISIBLE
        avatar.setPaletteBackground(palette.background)
        avatar.render(
            userId = room.roomId,
            displayName = room.displayName,
            avatarUrl = room.avatarUrl,
            localAvatarPath = null,
            matrixMediaLoader = state.matrixMediaLoader,
            sizePx = dp(96),
            shape = if (isSpace) MatrixAvatarShape.ROUNDED_RECT else MatrixAvatarShape.CIRCLE
        )

        accessRow.bind(
            context.getString(R.string.space_preview_access),
            accessLabel(room, state.join)
        )
        membersRow.bind(
            context.getString(R.string.space_preview_members),
            room.joinedMemberCount.toString()
        )
        contentsRow.visibility = if (isSpace) View.VISIBLE else View.GONE
        if (isSpace) {
            contentsRow.bind(
                context.getString(R.string.space_preview_contents),
                room.childrenCount.toString()
            )
        }
        addressRow.visibility = if (room.canonicalAlias.isNullOrBlank()) View.GONE else View.VISIBLE
        room.canonicalAlias?.takeIf(String::isNotBlank)?.let { alias ->
            addressRow.bind(context.getString(R.string.space_preview_address), alias)
        }

        val presentation = actionPresentation(state.join, room)
        statusText.text = context.getString(presentation.message)
        primaryButton.text = context.getString(presentation.title)
        val canSubmit = state.join.primaryAction != null && !state.join.isSubmitting
        primaryButton.isEnabled = canSubmit
        primaryButton.isClickable = canSubmit
        primaryButton.alpha = if (canSubmit) 1f else 0.55f
        primaryButton.setOnClickListener(if (canSubmit) View.OnClickListener {
            actions.onPrimaryAction()
        } else null)

        val errorMessage = when (state.join.error) {
            SpaceJoinError.LOAD -> R.string.space_preview_load_error
            SpaceJoinError.ACTION -> R.string.space_preview_action_error
            SpaceJoinError.ACCESS_CHANGED -> R.string.space_preview_access_changed
            null -> null
        }
        errorText.visibility = if (errorMessage == null) View.GONE else View.VISIBLE
        errorMessage?.let(errorText::setText)
        val canRetryLoad = state.join.error == SpaceJoinError.LOAD && !state.join.isSubmitting
        retryButton.visibility = if (canRetryLoad) View.VISIBLE else View.GONE
        retryButton.setOnClickListener(if (canRetryLoad) View.OnClickListener {
            actions.onRetry()
        } else null)
        backButton.isEnabled = true
        backButton.alpha = 1f
        backButton.setOnClickListener { actions.onBack() }
    }

    private fun actionPresentation(
        state: SpaceJoinState,
        room: MatrixSpaceRoom
    ): SpacePreviewActionPresentation {
        if (state.isSubmitting) {
            return when (state.primaryAction) {
                SpaceJoinPrimaryAction.KNOCK -> SpacePreviewActionPresentation(
                    R.string.space_preview_sending_request,
                    R.string.space_preview_knock_message
                )
                SpaceJoinPrimaryAction.ACCEPT_INVITE -> SpacePreviewActionPresentation(
                    R.string.space_preview_joining,
                    R.string.space_preview_invited_message
                )
                SpaceJoinPrimaryAction.JOIN -> SpacePreviewActionPresentation(
                    R.string.space_preview_joining,
                    if (state.context?.hasUnsupportedRestrictedAllowRules == true) {
                        R.string.space_preview_custom_restricted_message
                    } else if (room.joinRule == MatrixSpaceJoinRule.RESTRICTED ||
                        room.joinRule == MatrixSpaceJoinRule.KNOCK_RESTRICTED
                    ) {
                        R.string.space_preview_restricted_message
                    } else {
                        R.string.space_preview_public_message
                    }
                )
                SpaceJoinPrimaryAction.OPEN -> SpacePreviewActionPresentation(
                    R.string.space_preview_open,
                    R.string.space_preview_already_joined
                )
                null -> SpacePreviewActionPresentation(
                    R.string.space_preview_checking_access,
                    R.string.space_preview_checking_access
                )
            }
        }
        return when (state.primaryAction) {
            SpaceJoinPrimaryAction.OPEN -> SpacePreviewActionPresentation(
                R.string.space_preview_open,
                R.string.space_preview_already_joined
            )
            SpaceJoinPrimaryAction.ACCEPT_INVITE -> SpacePreviewActionPresentation(
                R.string.space_preview_accept_invite,
                R.string.space_preview_invited_message
            )
            SpaceJoinPrimaryAction.JOIN -> SpacePreviewActionPresentation(
                R.string.space_preview_join,
                if (state.context?.hasUnsupportedRestrictedAllowRules == true) {
                    R.string.space_preview_custom_restricted_message
                } else if (room.joinRule == MatrixSpaceJoinRule.RESTRICTED ||
                    room.joinRule == MatrixSpaceJoinRule.KNOCK_RESTRICTED
                ) {
                    R.string.space_preview_restricted_message
                } else {
                    R.string.space_preview_public_message
                }
            )
            SpaceJoinPrimaryAction.KNOCK -> SpacePreviewActionPresentation(
                R.string.space_preview_ask_to_join,
                R.string.space_preview_knock_message
            )
            null -> when {
                state.isRefreshing -> SpacePreviewActionPresentation(
                    R.string.space_preview_checking_access,
                    R.string.space_preview_checking_access
                )
                room.membership == MatrixSpaceMembership.KNOCKED -> SpacePreviewActionPresentation(
                    R.string.space_preview_request_pending,
                    R.string.space_preview_request_sent
                )
                room.membership == MatrixSpaceMembership.BANNED -> SpacePreviewActionPresentation(
                    R.string.space_preview_access_unknown,
                    R.string.space_preview_banned
                )
                room.joinRule == MatrixSpaceJoinRule.INVITE ||
                    room.joinRule == MatrixSpaceJoinRule.PRIVATE ||
                    room.joinRule == MatrixSpaceJoinRule.RESTRICTED -> SpacePreviewActionPresentation(
                    R.string.space_preview_access_unknown,
                    R.string.space_preview_invite_required
                )
                else -> SpacePreviewActionPresentation(
                    R.string.space_preview_access_unknown,
                    R.string.space_preview_access_unsupported
                )
            }
        }
    }

    private fun accessLabel(room: MatrixSpaceRoom, state: SpaceJoinState): String {
        if (state.context?.hasUnsupportedRestrictedAllowRules == true) {
            return context.getString(R.string.space_preview_access_custom_restricted)
        }
        val resource = when (room.joinRule) {
            MatrixSpaceJoinRule.PUBLIC -> R.string.space_preview_access_public
            MatrixSpaceJoinRule.RESTRICTED -> R.string.space_preview_access_restricted
            MatrixSpaceJoinRule.KNOCK,
            MatrixSpaceJoinRule.KNOCK_RESTRICTED -> R.string.space_preview_access_knock
            MatrixSpaceJoinRule.INVITE,
            MatrixSpaceJoinRule.PRIVATE -> R.string.space_preview_access_invite
            MatrixSpaceJoinRule.CUSTOM,
            MatrixSpaceJoinRule.UNKNOWN -> if (state.isRefreshing) {
                R.string.space_preview_checking_access
            } else {
                R.string.space_preview_access_unknown
            }
        }
        return context.getString(resource)
    }

    private fun applyPalette() {
        setBackgroundColor(palette.background)
        root.setBackgroundColor(palette.background)
        topBar.setBackgroundColor(palette.background)
        backButton.setTextColor(palette.accent)
        titleText.setTextColor(palette.primaryText)
        nameText.setTextColor(palette.primaryText)
        kindText.setTextColor(palette.secondaryText)
        topicText.setTextColor(palette.secondaryText)
        infoCard.background = roundedDrawable(palette.secondaryBackground, dp(14))
        accessRow.applyPalette(palette)
        membersRow.applyPalette(palette)
        contentsRow.applyPalette(palette)
        addressRow.applyPalette(palette)
        statusText.setTextColor(palette.secondaryText)
        errorText.setTextColor(palette.error)
        retryButton.setTextColor(palette.accent)
        primaryButton.setTextColor(Color.WHITE)
        primaryButton.background = roundedDrawable(palette.accent, dp(13))
    }

    private fun roundedDrawable(color: Int, radiusPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx.toFloat()
        }
    }

    private fun linearParams(width: Int, height: Int) = LinearLayout.LayoutParams(width, height)
    private fun fullWidthWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
    private fun wrapParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
    private fun dp(value: Int): Int = (value * density).roundToInt()
}

private data class SpacePreviewActionPresentation(
    val title: Int,
    val message: Int
)

private class SpacePreviewInfoRow(context: Context) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val title = TextView(context).apply {
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 2
    }
    private val value = TextView(context).apply {
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        textSize = 14f
        maxLines = 3
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52)
        updatePadding(left = dp(16), right = dp(16), top = dp(8), bottom = dp(8))
        addView(title, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.42f))
        addView(value, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.58f).apply {
            leftMargin = dp(12)
        })
    }

    fun bind(title: String, value: String) {
        this.title.text = title
        this.value.text = value
    }

    fun applyPalette(palette: SpacePalette) {
        title.setTextColor(palette.primaryText)
        value.setTextColor(palette.secondaryText)
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()
}
