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
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.zyna.app.ui.avatar.MatrixAvatarView
import com.zyna.app.data.media.MatrixMediaLoader
import com.zyna.app.ui.settings.SettingsPalette
import kotlin.math.roundToInt

internal data class ProfileEditorScreenViewState(
    val identityId: String,
    val displayName: String,
    val editDisplayName: String,
    val editTopic: String?,
    val avatarUrl: String?,
    val editAvatarLocalPath: String?,
    val hasAvatar: Boolean,
    val editSessionId: Long,
    val isSaving: Boolean,
    val canSave: Boolean,
    val canChangeName: Boolean,
    val canChangeTopic: Boolean,
    val canChangeAvatar: Boolean,
    val errorMessage: String?,
    val backLabel: String,
    val saveLabel: String,
    val title: String,
    val nameLabel: String,
    val topicLabel: String?,
    val changePhotoLabel: String,
    val removePhotoLabel: String,
    val discardTitle: String,
    val discardMessage: String,
    val keepEditingLabel: String,
    val discardLabel: String,
    val matrixMediaLoader: MatrixMediaLoader?,
    val bottomContentPaddingPx: Int,
    val isDiscardConfirmationVisible: Boolean
)

internal data class ProfileEditorScreenViewActions(
    val onBack: () -> Unit,
    val onDisplayNameChanged: (String) -> Unit,
    val onTopicChanged: (String) -> Unit,
    val onPickAvatar: (Long) -> Unit,
    val onRemoveAvatar: () -> Unit,
    val onSave: () -> Unit,
    val onDiscardChangesConfirmed: () -> Unit,
    val onDiscardChangesCancelled: () -> Unit
)

internal class ProfileEditorScreenView(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private var palette = SettingsPalette.from(context)
    private var statusTopInset = 0
    private var bottomContentPaddingPx = 0
    private var isRendering = false
    private var discardChangesDialog: AlertDialog? = null

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
    private val avatarView = MatrixAvatarView(context)
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
    private val topicLabel = TextView(context).apply {
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = true
        gravity = Gravity.START
    }
    private val topicEdit = EditText(context).apply {
        textSize = 17f
        setSingleLine(false)
        minLines = 2
        maxLines = 4
        gravity = Gravity.TOP or Gravity.START
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
            topicLabel,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(20)
            }
        )
        content.addView(
            topicEdit,
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
        topicEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isRendering) {
                    actions?.onTopicChanged(s?.toString().orEmpty())
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

    private var actions: ProfileEditorScreenViewActions? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    override fun onDetachedFromWindow() {
        discardChangesDialog?.dismiss()
        discardChangesDialog = null
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        palette = SettingsPalette.from(context)
        applyPalette()
    }

    fun render(
        state: ProfileEditorScreenViewState,
        actions: ProfileEditorScreenViewActions
    ) {
        this.actions = actions
        bottomContentPaddingPx = state.bottomContentPaddingPx
        updateContentPadding()

        backButton.text = state.backLabel
        saveButton.text = state.saveLabel
        titleText.text = state.title
        nameLabel.text = state.nameLabel
        topicLabel.text = state.topicLabel.orEmpty()
        changePhotoButton.text = state.changePhotoLabel
        removePhotoButton.text = state.removePhotoLabel
        isRendering = true
        if (nameEdit.text.toString() != state.editDisplayName) {
            nameEdit.setText(state.editDisplayName)
            nameEdit.setSelection(nameEdit.text?.length ?: 0)
        }
        val nextTopic = state.editTopic.orEmpty()
        if (topicEdit.text.toString() != nextTopic) {
            topicEdit.setText(nextTopic)
            topicEdit.setSelection(topicEdit.text?.length ?: 0)
        }
        isRendering = false

        val showsTopic = state.editTopic != null && state.topicLabel != null
        topicLabel.visibility = if (showsTopic) VISIBLE else GONE
        topicEdit.visibility = if (showsTopic) VISIBLE else GONE
        nameEdit.imeOptions = if (showsTopic) {
            EditorInfo.IME_ACTION_NEXT
        } else {
            EditorInfo.IME_ACTION_DONE
        }

        avatarView.render(
            userId = state.identityId,
            displayName = state.editDisplayName.takeIf { it.isNotBlank() }
                ?: state.displayName,
            avatarUrl = state.avatarUrl,
            localAvatarPath = state.editAvatarLocalPath,
            matrixMediaLoader = state.matrixMediaLoader,
            sizePx = dp(112)
        )

        val isSaving = state.isSaving
        if (isSaving || !state.isDiscardConfirmationVisible) {
            discardChangesDialog?.dismiss()
        } else {
            showDiscardChangesConfirmation(state, actions)
        }
        backButton.isEnabled = !isSaving
        saveButton.isEnabled = state.canSave
        changePhotoButton.isEnabled = !isSaving && state.canChangeAvatar
        removePhotoButton.isEnabled = !isSaving && state.canChangeAvatar

        backButton.setOnClickListener { if (!isSaving) actions.onBack() }
        saveButton.setOnClickListener { if (state.canSave) actions.onSave() }
        changePhotoButton.setOnClickListener {
            if (!isSaving && state.canChangeAvatar) {
                actions.onPickAvatar(state.editSessionId)
            }
        }
        removePhotoButton.setOnClickListener {
            if (!isSaving && state.canChangeAvatar) actions.onRemoveAvatar()
        }

        changePhotoButton.visibility = if (state.canChangeAvatar) VISIBLE else GONE
        removePhotoButton.visibility = if (state.canChangeAvatar && state.hasAvatar) VISIBLE else GONE
        progress.visibility = if (isSaving) VISIBLE else GONE
        errorText.text = state.errorMessage.orEmpty()
        errorText.visibility = if (state.errorMessage.isNullOrBlank()) GONE else VISIBLE
        applyEnabledState(state)
    }

    private fun showDiscardChangesConfirmation(
        state: ProfileEditorScreenViewState,
        actions: ProfileEditorScreenViewActions
    ) {
        if (discardChangesDialog?.isShowing == true) {
            return
        }
        var handled = false
        discardChangesDialog = AlertDialog.Builder(context)
            .setTitle(state.discardTitle)
            .setMessage(state.discardMessage)
            .setNegativeButton(state.keepEditingLabel) { _, _ ->
                handled = true
                actions.onDiscardChangesCancelled()
            }
            .setPositiveButton(state.discardLabel) { _, _ ->
                handled = true
                actions.onDiscardChangesConfirmed()
            }
            .create()
            .also { dialog ->
                dialog.setOnCancelListener {
                    if (!handled) {
                        handled = true
                        actions.onDiscardChangesCancelled()
                    }
                }
                dialog.setOnDismissListener {
                    if (discardChangesDialog === dialog) {
                        discardChangesDialog = null
                    }
                }
                dialog.show()
            }
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
        avatarView.setPaletteBackground(palette.background)
        changePhotoButton.setTextColor(palette.actionText)
        changePhotoButton.background = roundedDrawable(palette.selectedFill, dp(13))
        removePhotoButton.setTextColor(0xFFE5484D.toInt())
        nameLabel.setTextColor(palette.secondaryText)
        topicLabel.setTextColor(palette.secondaryText)
        nameEdit.setTextColor(palette.primaryText)
        nameEdit.setHintTextColor(palette.secondaryText)
        nameEdit.background = roundedDrawable(palette.surface, dp(10))
        topicEdit.setTextColor(palette.primaryText)
        topicEdit.setHintTextColor(palette.secondaryText)
        topicEdit.background = roundedDrawable(palette.surface, dp(10))
        errorText.setTextColor(0xFFE5484D.toInt())
    }

    private fun applyEnabledState(state: ProfileEditorScreenViewState) {
        val disabledAlpha = 0.45f
        backButton.alpha = if (state.isSaving) disabledAlpha else 1f
        saveButton.alpha = if (state.canSave) 1f else disabledAlpha
        changePhotoButton.alpha = if (!state.isSaving && state.canChangeAvatar) 1f else disabledAlpha
        removePhotoButton.alpha = if (!state.isSaving && state.canChangeAvatar) 1f else disabledAlpha
        nameEdit.isEnabled = !state.isSaving && state.canChangeName
        nameEdit.alpha = if (nameEdit.isEnabled) 1f else disabledAlpha
        topicEdit.isEnabled = !state.isSaving && state.canChangeTopic
        topicEdit.alpha = if (topicEdit.isEnabled) 1f else disabledAlpha
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
