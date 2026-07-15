package com.zyna.app.ui.profile

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.zyna.app.data.media.MatrixMediaLoader
import com.zyna.app.ui.settings.SettingsPalette
import kotlin.math.roundToInt

internal data class EditProfileScreenViewState(
    val profile: OwnProfileState,
    val matrixMediaLoader: MatrixMediaLoader?,
    val bottomContentPaddingPx: Int
)

internal data class EditProfileScreenViewActions(
    val onBack: () -> Unit,
    val onDisplayNameChanged: (String) -> Unit,
    val onPickAvatar: (Long) -> Unit,
    val onRemoveAvatar: () -> Unit,
    val onSave: () -> Unit
)

internal class EditProfileScreenView(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private var palette = SettingsPalette.from(context)
    private var statusTopInset = 0
    private var bottomContentPaddingPx = 0
    private var isRendering = false

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val topBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val backButton = TextView(context).apply {
        text = "Back"
        textSize = 16f
        gravity = Gravity.CENTER
        includeFontPadding = true
        isClickable = true
        isFocusable = true
        updatePadding(left = dp(20), right = dp(12))
    }
    private val titleText = TextView(context).apply {
        text = "Edit Profile"
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = true
        maxLines = 1
    }
    private val saveButton = TextView(context).apply {
        text = "Save"
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
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
        updatePadding(left = dp(20), right = dp(20), top = dp(30))
    }
    private val avatarView = ProfileAvatarView(context)
    private val changePhotoButton = TextView(context).apply {
        text = "Change Photo"
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = true
        isClickable = true
        isFocusable = true
        updatePadding(left = dp(16), right = dp(16), top = dp(8), bottom = dp(8))
    }
    private val removePhotoButton = TextView(context).apply {
        text = "Remove Photo"
        textSize = 15f
        gravity = Gravity.CENTER
        includeFontPadding = true
        isClickable = true
        isFocusable = true
        updatePadding(left = dp(16), right = dp(16), top = dp(8), bottom = dp(8))
    }
    private val nameLabel = TextView(context).apply {
        text = "Display Name"
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = true
        gravity = Gravity.START
    }
    private val nameEdit = EditText(context).apply {
        textSize = 18f
        setSingleLine(true)
        imeOptions = EditorInfo.IME_ACTION_DONE
        includeFontPadding = true
        setSelectAllOnFocus(false)
        updatePadding(left = dp(14), right = dp(14), top = dp(10), bottom = dp(10))
    }
    private val errorText = TextView(context).apply {
        textSize = 14f
        gravity = Gravity.CENTER
        includeFontPadding = true
        maxLines = 4
    }
    private val progress = ProgressBar(context).apply {
        isIndeterminate = true
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
                ViewGroup.LayoutParams.WRAP_CONTENT,
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
            saveButton,
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
                bottomMargin = dp(14)
            }
        )
        content.addView(
            changePhotoButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        content.addView(
            removePhotoButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
        )
        content.addView(
            nameLabel,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(30)
            }
        )
        content.addView(
            nameEdit,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
            }
        )
        content.addView(
            errorText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(18)
            }
        )
        content.addView(
            progress,
            LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                topMargin = dp(18)
            }
        )

        nameEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isRendering) {
                    actions?.onDisplayNameChanged(s?.toString().orEmpty())
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

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

    private var actions: EditProfileScreenViewActions? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        palette = SettingsPalette.from(context)
        applyPalette()
    }

    fun render(
        state: EditProfileScreenViewState,
        actions: EditProfileScreenViewActions
    ) {
        this.actions = actions
        bottomContentPaddingPx = state.bottomContentPaddingPx
        updateContentPadding()

        val profile = state.profile
        isRendering = true
        if (nameEdit.text.toString() != profile.editDisplayName) {
            nameEdit.setText(profile.editDisplayName)
            nameEdit.setSelection(nameEdit.text?.length ?: 0)
        }
        isRendering = false

        val avatarUrl = profile.avatarUrl.takeUnless {
            profile.editAvatarChange == OwnProfileAvatarChange.REMOVE
        }
        val localAvatarPath = profile.editAvatarLocalPath.takeIf {
            profile.editAvatarChange == OwnProfileAvatarChange.REPLACE
        }
        avatarView.render(
            userId = profile.userId,
            displayName = profile.editDisplayName.takeIf { it.isNotBlank() }
                ?: profile.displayName,
            avatarUrl = avatarUrl,
            localAvatarPath = localAvatarPath,
            matrixMediaLoader = state.matrixMediaLoader,
            sizePx = dp(112)
        )

        val isSaving = profile.isSaving
        backButton.isEnabled = !isSaving
        saveButton.isEnabled = !isSaving
        changePhotoButton.isEnabled = !isSaving
        removePhotoButton.isEnabled = !isSaving

        backButton.setOnClickListener { if (!isSaving) actions.onBack() }
        saveButton.setOnClickListener { if (!isSaving) actions.onSave() }
        changePhotoButton.setOnClickListener {
            if (!isSaving) {
                actions.onPickAvatar(profile.editSessionId)
            }
        }
        removePhotoButton.setOnClickListener { if (!isSaving) actions.onRemoveAvatar() }

        removePhotoButton.visibility = if (profile.hasAvatar) VISIBLE else GONE
        progress.visibility = if (isSaving) VISIBLE else GONE
        errorText.text = profile.errorMessage.orEmpty()
        errorText.visibility = if (profile.errorMessage.isNullOrBlank()) GONE else VISIBLE
        applyEnabledState(isSaving)
    }

    private fun applyPalette() {
        setBackgroundColor(palette.background)
        root.setBackgroundColor(palette.background)
        topBar.setBackgroundColor(palette.background)
        backButton.setTextColor(palette.actionText)
        titleText.setTextColor(palette.titleText)
        saveButton.setTextColor(palette.actionText)
        scrollView.setBackgroundColor(palette.background)
        content.setBackgroundColor(palette.background)
        avatarView.setPalette(palette)
        changePhotoButton.setTextColor(palette.actionText)
        changePhotoButton.background = roundedDrawable(palette.selectedFill, dp(13))
        removePhotoButton.setTextColor(0xFFE5484D.toInt())
        nameLabel.setTextColor(palette.secondaryText)
        nameEdit.setTextColor(palette.primaryText)
        nameEdit.setHintTextColor(palette.secondaryText)
        nameEdit.background = roundedDrawable(palette.surface, dp(10))
        errorText.setTextColor(0xFFE5484D.toInt())
    }

    private fun applyEnabledState(isSaving: Boolean) {
        val disabledAlpha = 0.45f
        backButton.alpha = if (isSaving) disabledAlpha else 1f
        saveButton.alpha = if (isSaving) disabledAlpha else 1f
        changePhotoButton.alpha = if (isSaving) disabledAlpha else 1f
        removePhotoButton.alpha = if (isSaving) disabledAlpha else 1f
        nameEdit.isEnabled = !isSaving
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
