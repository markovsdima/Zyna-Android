package com.zyna.app.ui.profile

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.zyna.app.ui.avatar.MatrixAvatarView
import com.zyna.app.data.media.MatrixMediaLoader
import com.zyna.app.ui.settings.SettingsPalette
import kotlin.math.roundToInt

internal data class ProfileScreenViewState(
    val profile: OwnProfileState,
    val matrixMediaLoader: MatrixMediaLoader?,
    val bottomContentPaddingPx: Int
)

internal data class ProfileScreenViewActions(
    val onEditProfile: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onRefreshProfile: () -> Unit
)

internal class ProfileScreenView(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private var palette = SettingsPalette.from(context)
    private var statusTopInset = 0
    private var bottomContentPaddingPx = 0

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val topBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val titleText = TextView(context).apply {
        text = "Profile"
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = true
        updatePadding(left = dp(20), right = dp(12))
    }
    private val settingsButton = TextView(context).apply {
        text = "Settings"
        textSize = 16f
        gravity = Gravity.CENTER
        includeFontPadding = true
        isClickable = true
        isFocusable = true
        updatePadding(left = dp(12), right = dp(20))
    }
    private val scrollView = ScrollView(context).apply {
        isFillViewport = true
        clipToPadding = false
    }
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        updatePadding(left = dp(20), right = dp(20), top = dp(36))
    }
    private val avatarView = MatrixAvatarView(context)
    private val nameText = TextView(context).apply {
        textSize = 26f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = true
        maxLines = 2
    }
    private val userIdText = TextView(context).apply {
        textSize = 14f
        gravity = Gravity.CENTER
        includeFontPadding = true
        maxLines = 2
    }
    private val editButton = TextView(context).apply {
        text = "Edit Profile"
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = true
        isClickable = true
        isFocusable = true
        updatePadding(left = dp(22), right = dp(22), top = dp(10), bottom = dp(10))
    }
    private val statusText = TextView(context).apply {
        textSize = 14f
        gravity = Gravity.CENTER
        includeFontPadding = true
        maxLines = 3
    }
    private val retryButton = TextView(context).apply {
        text = "Retry"
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = true
        isClickable = true
        isFocusable = true
        updatePadding(left = dp(18), right = dp(18), top = dp(8), bottom = dp(8))
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
            titleText,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
            )
        )
        topBar.addView(
            settingsButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
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
            LinearLayout.LayoutParams(dp(112), dp(112)).apply {
                bottomMargin = dp(20)
            }
        )
        content.addView(
            nameText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        content.addView(
            userIdText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
        )
        content.addView(
            editButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(24)
            }
        )
        content.addView(
            statusText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
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
            val nextTopInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            if (statusTopInset != nextTopInset) {
                statusTopInset = nextTopInset
                updateTopBarHeight()
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

    fun render(state: ProfileScreenViewState, actions: ProfileScreenViewActions) {
        bottomContentPaddingPx = state.bottomContentPaddingPx
        updateContentPadding()

        val profile = state.profile
        val userId = profile.userId
        nameText.text = profile.effectiveDisplayName.takeIf { it.isNotBlank() } ?: "Profile"
        userIdText.text = userId
        avatarView.render(
            userId = userId,
            displayName = profile.displayName,
            avatarUrl = profile.avatarUrl,
            localAvatarPath = null,
            matrixMediaLoader = state.matrixMediaLoader,
            sizePx = dp(112)
        )

        settingsButton.setOnClickListener { actions.onOpenSettings() }
        editButton.setOnClickListener { actions.onEditProfile() }
        retryButton.setOnClickListener { actions.onRefreshProfile() }

        statusText.text = when {
            profile.errorMessage != null -> profile.errorMessage
            profile.isLoading -> "Loading profile..."
            else -> ""
        }
        statusText.visibility = if (statusText.text.isNullOrBlank()) GONE else VISIBLE
        retryButton.visibility = if (profile.errorMessage != null) VISIBLE else GONE
    }

    private fun applyPalette() {
        setBackgroundColor(palette.background)
        root.setBackgroundColor(palette.background)
        topBar.setBackgroundColor(palette.background)
        titleText.setTextColor(palette.titleText)
        settingsButton.setTextColor(palette.actionText)
        scrollView.setBackgroundColor(palette.background)
        content.setBackgroundColor(palette.background)
        avatarView.setPaletteBackground(palette.background)
        nameText.setTextColor(palette.titleText)
        userIdText.setTextColor(palette.secondaryText)
        editButton.setTextColor(palette.actionText)
        editButton.background = roundedDrawable(palette.selectedFill, dp(14))
        statusText.setTextColor(palette.secondaryText)
        retryButton.setTextColor(palette.actionText)
        retryButton.background = roundedDrawable(palette.surface, dp(12))
    }

    private fun updateTopBarHeight() {
        topBar.updatePadding(top = statusTopInset)
        val params = topBar.layoutParams as LinearLayout.LayoutParams
        params.height = dp(TOP_BAR_HEIGHT_DP) + statusTopInset
        topBar.layoutParams = params
    }

    private fun updateContentPadding() {
        scrollView.updatePadding(bottom = bottomContentPaddingPx + dp(24))
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
