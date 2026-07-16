package com.zyna.app.ui.profile

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
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
import com.zyna.app.data.presence.UserPresenceStatus
import com.zyna.app.ui.presence.PresenceText
import com.zyna.app.ui.settings.SettingsPalette
import kotlin.math.roundToInt

internal data class UserProfileScreenViewState(
    val profile: UserProfileState,
    val roomId: String?,
    val presence: UserPresenceStatus?,
    val actionUserId: String?,
    val actionErrorMessage: String?,
    val matrixMediaLoader: MatrixMediaLoader?
)

internal data class UserProfileScreenViewActions(
    val onBack: () -> Unit,
    val onMessage: () -> Unit,
    val onCall: () -> Unit,
    val onRefresh: () -> Unit
)

internal class UserProfileScreenView(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private var palette = SettingsPalette.from(context)
    private var statusTopInset = 0
    private var bottomInset = 0

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val topBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val backButton = TextView(context).apply {
        text = context.getString(R.string.user_profile_back)
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
        isClickable = true
        isFocusable = true
    }
    private val titleText = TextView(context).apply {
        text = context.getString(R.string.user_profile_title)
        textSize = 17f
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
        gravity = Gravity.CENTER_HORIZONTAL
        updatePadding(left = dp(20), right = dp(20), top = dp(32), bottom = dp(24))
    }
    private val avatarView = ProfileAvatarView(context)
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
        ellipsize = TextUtils.TruncateAt.END
    }
    private val presenceText = TextView(context).apply {
        textSize = 14f
        gravity = Gravity.CENTER
        includeFontPadding = true
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val chatStateText = TextView(context).apply {
        textSize = 13f
        gravity = Gravity.CENTER
        includeFontPadding = true
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        updatePadding(left = dp(12), right = dp(12), top = dp(6), bottom = dp(6))
    }
    private val actionsRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }
    private val messageButton = actionButton(
        context.getString(R.string.contacts_message),
        R.drawable.ic_tab_chats_24
    )
    private val callButton = actionButton(
        context.getString(R.string.contacts_call),
        R.drawable.ic_tab_calls_24
    )
    private val statusText = TextView(context).apply {
        textSize = 14f
        gravity = Gravity.CENTER
        includeFontPadding = true
        maxLines = 3
    }
    private val retryButton = actionButton(context.getString(R.string.user_profile_retry))

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
        topBar.addView(backButton, LinearLayout.LayoutParams(dp(88), ViewGroup.LayoutParams.MATCH_PARENT))
        topBar.addView(titleText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        topBar.addView(topBarSpacer, LinearLayout.LayoutParams(dp(88), ViewGroup.LayoutParams.MATCH_PARENT))
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
                bottomMargin = dp(20)
            }
        )
        content.addView(nameText, matchWrapParams())
        content.addView(
            userIdText,
            matchWrapParams().apply {
                topMargin = dp(4)
            }
        )
        content.addView(
            presenceText,
            matchWrapParams().apply {
                topMargin = dp(6)
            }
        )
        content.addView(
            chatStateText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
            }
        )
        actionsRow.addView(messageButton, actionLayoutParams())
        actionsRow.addView(callButton, actionLayoutParams())
        content.addView(
            actionsRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(24)
            }
        )
        content.addView(
            statusText,
            matchWrapParams().apply {
                topMargin = dp(18)
            }
        )
        content.addView(
            retryButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
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
        palette = SettingsPalette.from(context)
        applyPalette()
    }

    fun render(state: UserProfileScreenViewState, actions: UserProfileScreenViewActions) {
        val profile = state.profile
        val isBusy = state.actionUserId == profile.userId
        backButton.setOnClickListener { actions.onBack() }
        messageButton.setOnClickListener { actions.onMessage() }
        callButton.setOnClickListener { actions.onCall() }
        retryButton.setOnClickListener { actions.onRefresh() }

        titleText.text = profile.effectiveDisplayName.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.user_profile_title)
        nameText.text = profile.effectiveDisplayName
        userIdText.text = profile.userId
        val presenceLabel = PresenceText.label(
            context = context,
            status = state.presence,
            style = PresenceText.LastSeenStyle.EXPANDED
        )
        presenceText.text = presenceLabel.orEmpty()
        presenceText.visibility = if (presenceLabel.isNullOrBlank()) GONE else VISIBLE
        avatarView.render(
            userId = profile.userId,
            displayName = profile.displayName,
            avatarUrl = profile.avatarUrl,
            localAvatarPath = null,
            matrixMediaLoader = state.matrixMediaLoader,
            sizePx = dp(112)
        )

        chatStateText.text = if (state.roomId.isNullOrBlank()) {
            context.getString(R.string.user_profile_new_direct_chat)
        } else {
            context.getString(R.string.user_profile_direct_chat)
        }
        val enabledAlpha = if (isBusy) 0.48f else 1f
        messageButton.isEnabled = !isBusy
        callButton.isEnabled = !isBusy
        messageButton.alpha = enabledAlpha
        callButton.alpha = enabledAlpha

        statusText.text = when {
            state.actionErrorMessage != null -> state.actionErrorMessage
            profile.errorMessage != null -> profile.errorMessage
            profile.isLoading -> context.getString(R.string.user_profile_loading)
            isBusy -> context.getString(R.string.user_profile_opening_chat)
            else -> ""
        }
        statusText.visibility = if (statusText.text.isNullOrBlank()) GONE else VISIBLE
        retryButton.visibility = if (profile.errorMessage != null) VISIBLE else GONE
        contentDescription = "${profile.effectiveDisplayName}. ${profile.userId}"
    }

    private fun applyPalette() {
        setBackgroundColor(palette.background)
        root.setBackgroundColor(palette.background)
        topBar.setBackgroundColor(palette.background)
        backButton.setTextColor(palette.actionText)
        titleText.setTextColor(palette.titleText)
        scrollView.setBackgroundColor(palette.background)
        content.setBackgroundColor(palette.background)
        avatarView.setPalette(palette)
        nameText.setTextColor(palette.titleText)
        userIdText.setTextColor(palette.secondaryText)
        presenceText.setTextColor(palette.secondaryText)
        chatStateText.setTextColor(palette.secondaryText)
        chatStateText.background = roundedDrawable(palette.surface, dp(12))
        messageButton.setTextColor(palette.actionText)
        messageButton.compoundDrawableTintList = ColorStateList.valueOf(palette.actionText)
        messageButton.background = roundedDrawable(palette.selectedFill, dp(14))
        callButton.setTextColor(palette.actionText)
        callButton.compoundDrawableTintList = ColorStateList.valueOf(palette.actionText)
        callButton.background = roundedDrawable(palette.surface, dp(14))
        statusText.setTextColor(palette.secondaryText)
        retryButton.setTextColor(palette.actionText)
        retryButton.background = roundedDrawable(palette.surface, dp(12))
    }

    private fun actionButton(text: CharSequence, iconResId: Int? = null): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = true
            isClickable = true
            isFocusable = true
            if (iconResId != null) {
                compoundDrawablePadding = dp(6)
                setCompoundDrawablesWithIntrinsicBounds(iconResId, 0, 0, 0)
            }
            updatePadding(left = dp(22), right = dp(22), top = dp(10), bottom = dp(10))
        }
    }

    private fun actionLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        ).apply {
            leftMargin = dp(6)
            rightMargin = dp(6)
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
            top = dp(32),
            bottom = bottomInset + dp(24)
        )
    }

    private fun roundedDrawable(color: Int, radiusPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx.toFloat()
        }
    }

    private fun dp(value: Int): Int {
        return (value * density).roundToInt()
    }

    private companion object {
        const val TOP_BAR_HEIGHT_DP = 64
    }
}
