package com.zyna.app.ui.createroom

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.zyna.app.R
import com.zyna.app.data.media.MatrixMediaLoader
import com.zyna.app.ui.avatar.MatrixAvatarView
import com.zyna.app.ui.settings.SettingsPalette
import kotlin.math.roundToInt

internal data class CreateRoomScreenViewState(
    val creation: CreateRoomState,
    val errorMessage: String?,
    val matrixMediaLoader: MatrixMediaLoader?,
    val bottomContentPaddingPx: Int
)

internal data class CreateRoomScreenViewActions(
    val onBack: () -> Unit,
    val onNameChanged: (String) -> Unit,
    val onTopicChanged: (String) -> Unit,
    val onAccessChanged: (CreateRoomAccess) -> Unit,
    val onPostingPermissionChanged: (CreateRoomPostingPermission) -> Unit,
    val onAliasChanged: (String) -> Unit,
    val onRetryAliasCheck: () -> Unit,
    val onPickAvatar: (Long) -> Unit,
    val onRemoveAvatar: () -> Unit,
    val onCreate: () -> Unit,
    val onDiscardChangesConfirmed: () -> Unit,
    val onDiscardChangesCancelled: () -> Unit
)

internal class CreateRoomScreenView(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private var palette = SettingsPalette.from(context)
    private var statusTopInset = 0
    private var bottomContentPaddingPx = 0
    private var isRendering = false
    private var actions: CreateRoomScreenViewActions? = null
    private var discardDialog: AlertDialog? = null

    private val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val topBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val cancelButton = topBarAction(context.getString(R.string.common_cancel), bold = false)
    private val titleText = TextView(context).apply {
        text = context.getString(R.string.create_group_title)
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = true
        maxLines = 1
    }
    private val createButton = topBarAction(
        context.getString(R.string.create_group_create),
        bold = true
    )
    private val scrollView = ScrollView(context).apply {
        isFillViewport = true
        clipToPadding = false
    }
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        updatePadding(left = dp(20), right = dp(20), top = dp(24))
    }
    private val avatarView = MatrixAvatarView(context)
    private val changePhotoButton = secondaryAction(R.string.profile_edit_change_photo)
    private val removePhotoButton = secondaryAction(R.string.profile_edit_remove_photo)
    private val nameLabel = sectionFieldLabel(R.string.create_group_name)
    private val nameEdit = field(singleLine = true).apply {
        imeOptions = EditorInfo.IME_ACTION_NEXT
    }
    private val topicLabel = sectionFieldLabel(R.string.create_group_topic)
    private val topicEdit = field(singleLine = false).apply {
        minLines = 2
        maxLines = 3
        gravity = Gravity.TOP or Gravity.START
        imeOptions = EditorInfo.IME_ACTION_DONE
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
    }
    private val accessHeader = sectionHeader(R.string.create_group_access)
    private val privateAccess = CreateRoomOptionView(context)
    private val publicAccess = CreateRoomOptionView(context)
    private val addressContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        updatePadding(left = dp(14), right = dp(14), top = dp(14), bottom = dp(14))
    }
    private val addressLabel = sectionFieldLabel(R.string.create_group_address)
    private val addressInputRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val addressPrefix = addressAffix("#")
    private val addressEdit = field(singleLine = true).apply {
        textSize = 16f
        imeOptions = EditorInfo.IME_ACTION_DONE
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }
    private val addressSuffix = addressAffix("")
    private val addressStatus = TextView(context).apply {
        textSize = 13f
        includeFontPadding = true
    }
    private val retryAddress = TextView(context).apply {
        text = context.getString(R.string.create_group_address_retry)
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = true
        isClickable = true
        isFocusable = true
    }
    private val postingHeader = sectionHeader(R.string.create_group_posting_permissions)
    private val allMembersPosting = CreateRoomOptionView(context)
    private val moderatorsPosting = CreateRoomOptionView(context)
    private val encryptionNote = TextView(context).apply {
        textSize = 13f
        includeFontPadding = true
        gravity = Gravity.START
        updatePadding(left = dp(4), right = dp(4), top = dp(14))
    }
    private val errorText = TextView(context).apply {
        textSize = 14f
        gravity = Gravity.CENTER
        includeFontPadding = true
        maxLines = 4
    }
    private val progress = ProgressBar(context).apply { isIndeterminate = true }

    init {
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        root.addView(topBar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(TOP_BAR_HEIGHT_DP)))
        topBar.addView(cancelButton, topBarActionParams())
        topBar.addView(titleText, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        topBar.addView(createButton, topBarActionParams())
        root.addView(scrollView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        scrollView.addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        content.addView(avatarView, LinearLayout.LayoutParams(dp(108), dp(108)).apply {
            bottomMargin = dp(12)
        })
        content.addView(changePhotoButton, wrapContentParams())
        content.addView(removePhotoButton, wrapContentParams().apply { topMargin = dp(2) })
        content.addView(nameLabel, fullWidthWrapParams().apply { topMargin = dp(26) })
        content.addView(nameEdit, fullWidthWrapParams().apply { topMargin = dp(6) })
        content.addView(topicLabel, fullWidthWrapParams().apply { topMargin = dp(18) })
        content.addView(topicEdit, fullWidthWrapParams().apply { topMargin = dp(6) })

        content.addView(accessHeader, fullWidthWrapParams())
        content.addView(privateAccess, fullWidthWrapParams())
        content.addView(publicAccess, fullWidthWrapParams().apply { topMargin = dp(8) })

        addressContainer.addView(addressLabel, fullWidthWrapParams())
        addressContainer.addView(addressInputRow, fullWidthWrapParams().apply { topMargin = dp(6) })
        addressInputRow.addView(addressPrefix, wrapContentParams())
        addressInputRow.addView(addressEdit, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addressInputRow.addView(addressSuffix, wrapContentParams())
        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(addressStatus, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(retryAddress, wrapContentParams())
        }
        addressContainer.addView(statusRow, fullWidthWrapParams().apply { topMargin = dp(6) })
        content.addView(addressContainer, fullWidthWrapParams().apply { topMargin = dp(10) })

        content.addView(postingHeader, fullWidthWrapParams())
        content.addView(allMembersPosting, fullWidthWrapParams())
        content.addView(moderatorsPosting, fullWidthWrapParams().apply { topMargin = dp(8) })
        content.addView(encryptionNote, fullWidthWrapParams())
        content.addView(errorText, fullWidthWrapParams().apply { topMargin = dp(18) })
        content.addView(progress, LinearLayout.LayoutParams(dp(32), dp(32)).apply { topMargin = dp(18) })

        privateAccess.setContent(
            context.getString(R.string.create_group_access_private),
            context.getString(R.string.create_group_access_private_description)
        )
        publicAccess.setContent(
            context.getString(R.string.create_group_access_public),
            context.getString(R.string.create_group_access_public_description)
        )
        allMembersPosting.setContent(
            context.getString(R.string.create_group_posting_all_members),
            context.getString(R.string.create_group_posting_all_members_description)
        )
        moderatorsPosting.setContent(
            context.getString(R.string.create_group_posting_moderators),
            context.getString(R.string.create_group_posting_moderators_description)
        )

        nameEdit.watch { actions?.onNameChanged(it) }
        topicEdit.watch { actions?.onTopicChanged(it) }
        addressEdit.watch { actions?.onAliasChanged(it) }

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

    override fun onDetachedFromWindow() {
        discardDialog?.dismiss()
        discardDialog = null
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        palette = SettingsPalette.from(context)
        applyPalette()
    }

    fun render(state: CreateRoomScreenViewState, actions: CreateRoomScreenViewActions) {
        this.actions = actions
        val creation = state.creation
        val isStoryline = creation.target?.mode == CreateRoomMode.STORYLINE
        bottomContentPaddingPx = state.bottomContentPaddingPx
        scrollView.updatePadding(bottom = bottomContentPaddingPx + dp(24))

        titleText.setText(
            if (isStoryline) R.string.create_storyline_title else R.string.create_group_title
        )
        nameLabel.setText(
            if (isStoryline) R.string.create_storyline_name else R.string.create_group_name
        )
        topicLabel.setText(
            if (isStoryline) R.string.create_storyline_topic else R.string.create_group_topic
        )
        addressLabel.setText(
            if (isStoryline) R.string.create_storyline_address else R.string.create_group_address
        )
        privateAccess.setContent(
            context.getString(
                if (isStoryline) {
                    R.string.create_storyline_access_private
                } else {
                    R.string.create_group_access_private
                }
            ),
            context.getString(
                if (isStoryline) {
                    R.string.create_storyline_access_private_description
                } else {
                    R.string.create_group_access_private_description
                }
            )
        )
        publicAccess.setContent(
            context.getString(
                if (isStoryline) {
                    R.string.create_storyline_access_public
                } else {
                    R.string.create_group_access_public
                }
            ),
            context.getString(
                if (isStoryline) {
                    R.string.create_storyline_access_public_description
                } else {
                    R.string.create_group_access_public_description
                }
            )
        )
        val postingVisibility = if (isStoryline) GONE else VISIBLE
        postingHeader.visibility = postingVisibility
        allMembersPosting.visibility = postingVisibility
        moderatorsPosting.visibility = postingVisibility

        isRendering = true
        nameEdit.replaceText(creation.name)
        topicEdit.replaceText(creation.topic)
        addressEdit.replaceText(creation.aliasLocalPart)
        isRendering = false

        avatarView.render(
            userId = creation.target?.userId.orEmpty(),
            displayName = creation.name,
            avatarUrl = null,
            localAvatarPath = creation.avatarLocalPath,
            matrixMediaLoader = state.matrixMediaLoader,
            sizePx = dp(108)
        )
        addressSuffix.text = creation.target?.serverName?.let { ":$it" }.orEmpty()
        privateAccess.isOptionSelected = creation.access == CreateRoomAccess.PRIVATE
        publicAccess.isOptionSelected = creation.access == CreateRoomAccess.PUBLIC
        allMembersPosting.isOptionSelected =
            creation.postingPermission == CreateRoomPostingPermission.ALL_MEMBERS
        moderatorsPosting.isOptionSelected =
            creation.postingPermission == CreateRoomPostingPermission.MODERATORS_ONLY

        addressContainer.visibility = if (creation.access == CreateRoomAccess.PUBLIC) VISIBLE else GONE
        renderAddressStatus(creation.aliasAvailability)
        encryptionNote.text = context.getString(
            if (isStoryline) {
                if (creation.access == CreateRoomAccess.PRIVATE) {
                    R.string.create_storyline_private_note
                } else {
                    R.string.create_storyline_public_note
                }
            } else if (creation.access == CreateRoomAccess.PRIVATE) {
                R.string.create_group_encryption_private_note
            } else {
                R.string.create_group_encryption_public_note
            }
        )

        val isCreating = creation.isCreating
        if (isCreating || !creation.isDiscardConfirmationVisible) {
            discardDialog?.dismiss()
        } else {
            showDiscardConfirmation(actions, isStoryline)
        }

        cancelButton.setOnClickListener { if (!isCreating) actions.onBack() }
        createButton.setOnClickListener { if (creation.canCreate) actions.onCreate() }
        changePhotoButton.setOnClickListener {
            if (!isCreating) actions.onPickAvatar(creation.editSessionId)
        }
        removePhotoButton.setOnClickListener { if (!isCreating) actions.onRemoveAvatar() }
        privateAccess.setOnClickListener {
            if (!isCreating) actions.onAccessChanged(CreateRoomAccess.PRIVATE)
        }
        publicAccess.setOnClickListener {
            if (!isCreating) actions.onAccessChanged(CreateRoomAccess.PUBLIC)
        }
        allMembersPosting.setOnClickListener {
            if (!isCreating) {
                actions.onPostingPermissionChanged(CreateRoomPostingPermission.ALL_MEMBERS)
            }
        }
        moderatorsPosting.setOnClickListener {
            if (!isCreating) {
                actions.onPostingPermissionChanged(CreateRoomPostingPermission.MODERATORS_ONLY)
            }
        }
        retryAddress.setOnClickListener { if (!isCreating) actions.onRetryAliasCheck() }

        removePhotoButton.visibility = if (creation.hasAvatar) VISIBLE else GONE
        progress.visibility = if (isCreating) VISIBLE else GONE
        errorText.text = state.errorMessage.orEmpty()
        errorText.visibility = if (state.errorMessage.isNullOrBlank()) GONE else VISIBLE
        applyEnabledState(creation)
    }

    private fun renderAddressStatus(status: CreateRoomAliasAvailability) {
        val (textRes, color) = when (status) {
            CreateRoomAliasAvailability.NOT_REQUIRED -> null to palette.secondaryText
            CreateRoomAliasAvailability.CHECKING ->
                R.string.create_group_address_checking to palette.secondaryText
            CreateRoomAliasAvailability.AVAILABLE ->
                R.string.create_group_address_available to SUCCESS_COLOR
            CreateRoomAliasAvailability.TAKEN ->
                R.string.create_group_address_taken to ERROR_COLOR
            CreateRoomAliasAvailability.INVALID ->
                R.string.create_group_address_invalid to ERROR_COLOR
            CreateRoomAliasAvailability.ERROR ->
                R.string.create_group_address_check_error to ERROR_COLOR
        }
        addressStatus.text = textRes?.let(context::getString).orEmpty()
        addressStatus.setTextColor(color)
        retryAddress.visibility = if (status == CreateRoomAliasAvailability.ERROR) VISIBLE else GONE
    }

    private fun applyEnabledState(state: CreateRoomState) {
        val enabled = !state.isCreating
        cancelButton.isEnabled = enabled
        cancelButton.alpha = if (enabled) 1f else DISABLED_ALPHA
        createButton.isEnabled = state.canCreate
        createButton.alpha = if (state.canCreate) 1f else DISABLED_ALPHA
        listOf(nameEdit, topicEdit, addressEdit).forEach { field ->
            field.isEnabled = enabled
            field.alpha = if (enabled) 1f else DISABLED_ALPHA
        }
        listOf(privateAccess, publicAccess, allMembersPosting, moderatorsPosting).forEach { option ->
            option.isEnabled = enabled
            option.alpha = if (enabled) 1f else DISABLED_ALPHA
        }
        changePhotoButton.isEnabled = enabled
        removePhotoButton.isEnabled = enabled
        changePhotoButton.alpha = if (enabled) 1f else DISABLED_ALPHA
        removePhotoButton.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    private fun showDiscardConfirmation(
        actions: CreateRoomScreenViewActions,
        isStoryline: Boolean
    ) {
        if (discardDialog?.isShowing == true) return
        var handled = false
        discardDialog = AlertDialog.Builder(context)
            .setTitle(
                if (isStoryline) {
                    R.string.create_storyline_discard_title
                } else {
                    R.string.create_group_discard_title
                }
            )
            .setMessage(
                if (isStoryline) {
                    R.string.create_storyline_discard_message
                } else {
                    R.string.create_group_discard_message
                }
            )
            .setNegativeButton(R.string.profile_edit_keep_editing) { _, _ ->
                handled = true
                actions.onDiscardChangesCancelled()
            }
            .setPositiveButton(R.string.profile_edit_discard) { _, _ ->
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
                    if (discardDialog === dialog) discardDialog = null
                }
                dialog.show()
            }
    }

    private fun applyPalette() {
        setBackgroundColor(palette.background)
        root.setBackgroundColor(palette.background)
        topBar.setBackgroundColor(palette.background)
        scrollView.setBackgroundColor(palette.background)
        content.setBackgroundColor(palette.background)
        cancelButton.setTextColor(palette.actionText)
        createButton.setTextColor(palette.actionText)
        titleText.setTextColor(palette.titleText)
        avatarView.setPaletteBackground(palette.background)
        changePhotoButton.setTextColor(palette.actionText)
        changePhotoButton.background = roundedDrawable(palette.selectedFill, 13)
        removePhotoButton.setTextColor(ERROR_COLOR)
        listOf(nameLabel, topicLabel, addressLabel, accessHeader, postingHeader).forEach {
            it.setTextColor(palette.secondaryText)
        }
        listOf(nameEdit, topicEdit, addressEdit).forEach {
            it.setTextColor(palette.primaryText)
            it.setHintTextColor(palette.secondaryText)
            it.background = roundedDrawable(palette.surface, 10)
        }
        addressContainer.background = roundedDrawable(palette.surface, 12)
        addressEdit.background = null
        addressPrefix.setTextColor(palette.secondaryText)
        addressSuffix.setTextColor(palette.secondaryText)
        retryAddress.setTextColor(palette.actionText)
        encryptionNote.setTextColor(palette.secondaryText)
        errorText.setTextColor(ERROR_COLOR)
        listOf(privateAccess, publicAccess, allMembersPosting, moderatorsPosting).forEach {
            it.setPalette(palette)
        }
    }

    private fun topBarAction(text: String, bold: Boolean): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 16f
            if (bold) typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = true
            isClickable = true
            isFocusable = true
            updatePadding(left = dp(18), right = dp(18))
        }
    }

    private fun secondaryAction(textRes: Int): TextView {
        return TextView(context).apply {
            text = context.getString(textRes)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = true
            isClickable = true
            isFocusable = true
            updatePadding(left = dp(16), right = dp(16), top = dp(8), bottom = dp(8))
        }
    }

    private fun field(singleLine: Boolean): EditText {
        return EditText(context).apply {
            textSize = 17f
            setSingleLine(singleLine)
            includeFontPadding = true
            setSelectAllOnFocus(false)
            updatePadding(left = dp(14), right = dp(14), top = dp(10), bottom = dp(10))
        }
    }

    private fun sectionFieldLabel(textRes: Int): TextView {
        return TextView(context).apply {
            text = context.getString(textRes)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = true
            gravity = Gravity.START
        }
    }

    private fun sectionHeader(textRes: Int): TextView {
        return sectionFieldLabel(textRes).apply {
            updatePadding(left = dp(4), right = dp(4), top = dp(24), bottom = dp(7))
        }
    }

    private fun addressAffix(value: String): TextView {
        return TextView(context).apply {
            text = value
            textSize = 16f
            includeFontPadding = true
        }
    }

    private fun EditText.watch(onChanged: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isRendering) onChanged(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun EditText.replaceText(value: String) {
        val oldValue = text.toString()
        if (oldValue == value) return
        val oldSelectionStart = selectionStart.takeIf { it >= 0 } ?: oldValue.length
        val oldSelectionEnd = selectionEnd.takeIf { it >= 0 } ?: oldSelectionStart
        setText(value)
        setSelection(
            remapSelectionAfterNormalization(oldValue, value, oldSelectionStart),
            remapSelectionAfterNormalization(oldValue, value, oldSelectionEnd)
        )
    }

    private fun updateTopBarHeight() {
        topBar.updatePadding(top = statusTopInset)
        val params = topBar.layoutParams as LinearLayout.LayoutParams
        params.height = dp(TOP_BAR_HEIGHT_DP) + statusTopInset
        topBar.layoutParams = params
    }

    private fun topBarActionParams() = LinearLayout.LayoutParams(
        LayoutParams.WRAP_CONTENT,
        LayoutParams.MATCH_PARENT
    )

    private fun wrapContentParams() = LinearLayout.LayoutParams(
        LayoutParams.WRAP_CONTENT,
        LayoutParams.WRAP_CONTENT
    )

    private fun fullWidthWrapParams() = LinearLayout.LayoutParams(
        LayoutParams.MATCH_PARENT,
        LayoutParams.WRAP_CONTENT
    )

    private fun roundedDrawable(color: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private companion object {
        const val TOP_BAR_HEIGHT_DP = 56
        const val DISABLED_ALPHA = 0.45f
        const val SUCCESS_COLOR = 0xFF2E9D62.toInt()
        const val ERROR_COLOR = 0xFFE5484D.toInt()
    }
}

private class CreateRoomOptionView(context: Context) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val textColumn = LinearLayout(context).apply { orientation = VERTICAL }
    private val titleText = TextView(context).apply {
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = true
    }
    private val subtitleText = TextView(context).apply {
        textSize = 13f
        includeFontPadding = true
        maxLines = 3
    }
    private val radio = RadioButton(context).apply {
        isClickable = false
        isFocusable = false
    }

    var isOptionSelected: Boolean = false
        set(value) {
            field = value
            radio.isChecked = value
            isSelected = value
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        updatePadding(left = dp(14), right = dp(8), top = dp(11), bottom = dp(11))
        textColumn.addView(titleText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        textColumn.addView(subtitleText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2)
        })
        addView(textColumn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(radio, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun setContent(title: String, subtitle: String) {
        titleText.text = title
        subtitleText.text = subtitle
        contentDescription = "$title. $subtitle"
    }

    fun setPalette(palette: SettingsPalette) {
        titleText.setTextColor(palette.primaryText)
        subtitleText.setTextColor(palette.secondaryText)
        background = GradientDrawable().apply {
            setColor(palette.surface)
            cornerRadius = dp(12).toFloat()
        }
        radio.buttonTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(palette.actionText, palette.secondaryText)
        )
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()
}
