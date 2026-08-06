package com.zyna.app.ui.spaces

import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.zyna.app.R
import kotlin.math.roundToInt

internal data class SpaceAccessScreenViewState(
    val access: SpaceAccessState,
    val presentationKind: SpacePresentationKind,
    val errorMessage: String?
)

internal data class SpaceAccessScreenViewActions(
    val onBack: () -> Unit,
    val onRetry: () -> Unit,
    val onAccessChanged: (SpaceAccessOption) -> Unit,
    val onAddressChanged: (String) -> Unit,
    val onRetryAddressCheck: () -> Unit,
    val onDirectoryVisibilityChanged: (Boolean) -> Unit,
    val onSave: () -> Unit,
    val onConfirmDiscard: () -> Unit,
    val onCancelDiscard: () -> Unit
)

/** Matrix Space access editor with intentionally separate access, address, and directory sections. */
@Suppress("SetTextI18n")
internal class SpaceAccessScreenView(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private var palette = SpaceAccessPalette.from(context)
    private var topInset = 0
    private var bottomInset = 0
    private var isRendering = false
    private var discardDialog: AlertDialog? = null

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
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val saveButton = actionText(context.getString(R.string.profile_edit_save))
    private val scrollView = ScrollView(context).apply {
        isFillViewport = true
        clipToPadding = false
    }
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        updatePadding(left = dp(20), right = dp(20), top = dp(24), bottom = dp(24))
    }
    private val status = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 15f
    }
    private val retryButton = filledButton(context.getString(R.string.common_retry))

    private val accessHeader = sectionHeader(context.getString(R.string.space_access_join_header))
    private val privateRow = AccessOptionRow(
        SpaceAccessOption.PRIVATE,
        R.string.space_access_private_title,
        R.string.space_access_private_description
    )
    private val parentRow = AccessOptionRow(
        SpaceAccessOption.PARENT_MEMBERS,
        R.string.space_access_parent_title,
        R.string.space_access_parent_description
    )
    private val publicRow = AccessOptionRow(
        SpaceAccessOption.PUBLIC,
        R.string.space_access_public_title,
        R.string.space_access_public_description
    )
    private val unsupportedAccess = TextView(context).apply {
        textSize = 14f
        setText(R.string.space_access_unsupported)
        updatePadding(left = dp(14), right = dp(14), top = dp(14), bottom = dp(14))
    }

    private val addressHeader = sectionHeader(context.getString(R.string.space_access_address_header))
    private val addressDescription = TextView(context).apply {
        textSize = 14f
        setText(R.string.space_access_address_description)
    }
    private val addressField = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        updatePadding(left = dp(14), right = dp(14))
    }
    private val addressPrefix = TextView(context).apply {
        text = "#"
        textSize = 17f
    }
    private val addressEdit = EditText(context).apply {
        background = null
        textSize = 17f
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        imeOptions = EditorInfo.IME_ACTION_DONE
        setSelectAllOnFocus(false)
        updatePadding(left = 0, right = dp(4), top = dp(10), bottom = dp(10))
    }
    private val addressSuffix = TextView(context).apply {
        textSize = 15f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val currentAddress = TextView(context).apply { textSize = 13f }
    private val addressStatus = TextView(context).apply { textSize = 13f }
    private val retryAddressButton = actionText(context.getString(R.string.common_retry)).apply {
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
    }

    private val directoryHeader = sectionHeader(context.getString(R.string.space_access_directory_header))
    private val unsupportedDirectory = TextView(context).apply {
        textSize = 14f
        setText(R.string.space_access_directory_unsupported)
        updatePadding(left = dp(14), right = dp(14), top = dp(14), bottom = dp(14))
    }
    private val directoryRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        updatePadding(left = dp(14), right = dp(10), top = dp(12), bottom = dp(12))
    }
    private val directoryTexts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val directoryTitle = TextView(context).apply {
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setText(R.string.space_access_directory_title)
    }
    private val directoryDescription = TextView(context).apply {
        textSize = 13f
        setText(R.string.space_access_directory_description)
    }
    private val directorySwitch = Switch(context)

    init {
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        root.addView(topBar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(TOP_BAR_DP)))
        topBar.addView(backButton, LinearLayout.LayoutParams(dp(SIDE_DP), LayoutParams.MATCH_PARENT))
        topBar.addView(titleText, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        topBar.addView(saveButton, LinearLayout.LayoutParams(dp(SIDE_DP), LayoutParams.MATCH_PARENT))
        root.addView(scrollView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        scrollView.addView(
            content,
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        content.addView(status)
        content.addView(retryButton, matchWrap(top = 12))
        content.addView(accessHeader, matchWrap(top = 16))
        content.addView(privateRow.view, matchWrap(top = 8))
        content.addView(parentRow.view, matchWrap(top = 8))
        content.addView(publicRow.view, matchWrap(top = 8))
        content.addView(unsupportedAccess, matchWrap(top = 8))
        content.addView(addressHeader, matchWrap(top = 24))
        content.addView(addressDescription, matchWrap(top = 8))
        content.addView(addressField, matchWrap(top = 10))
        addressField.addView(addressPrefix)
        addressField.addView(addressEdit, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addressField.addView(addressSuffix)
        content.addView(currentAddress, matchWrap(top = 6))
        content.addView(addressStatus, matchWrap(top = 6))
        content.addView(retryAddressButton, matchWrap(top = 2))
        content.addView(directoryHeader, matchWrap(top = 24))
        content.addView(directoryRow, matchWrap(top = 8))
        content.addView(unsupportedDirectory, matchWrap(top = 8))
        directoryTexts.addView(directoryTitle)
        directoryTexts.addView(directoryDescription)
        directoryRow.addView(
            directoryTexts,
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        directoryRow.addView(
            directorySwitch,
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        )

        addressEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isRendering) latestActions?.onAddressChanged(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
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

    private var latestActions: SpaceAccessScreenViewActions? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    override fun onDetachedFromWindow() {
        discardDialog?.dismiss()
        discardDialog = null
        latestActions = null
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        palette = SpaceAccessPalette.from(context)
        applyPalette()
    }

    fun render(state: SpaceAccessScreenViewState, actions: SpaceAccessScreenViewActions) {
        latestActions = actions
        val access = state.access
        val isStoryline = state.presentationKind == SpacePresentationKind.STORYLINE
        titleText.text = context.getString(
            if (isStoryline) R.string.space_access_storyline_title
            else R.string.space_access_track_title
        )
        backButton.setOnClickListener { actions.onBack() }
        backButton.isEnabled = !access.isSaving
        saveButton.setOnClickListener(if (access.canSave) View.OnClickListener { actions.onSave() } else null)
        saveButton.isEnabled = access.canSave
        saveButton.alpha = if (access.canSave) 1f else DISABLED_ALPHA

        status.text = when {
            access.isLoading -> context.getString(R.string.space_access_loading)
            access.isSaving -> context.getString(R.string.space_access_saving)
            else -> state.errorMessage.orEmpty()
        }
        status.visibility = if (status.text.isBlank()) GONE else VISIBLE
        retryButton.visibility = if (access.error == SpaceAccessError.LOAD) VISIBLE else GONE
        retryButton.setOnClickListener { actions.onRetry() }

        val showsContent = !access.isLoading && access.target != null &&
            access.error != SpaceAccessError.LOAD
        accessHeader.visibility = if (showsContent) VISIBLE else GONE
        val showsSupportedAccess = showsContent && access.isJoinRuleSupported
        privateRow.view.visibility = if (showsSupportedAccess) VISIBLE else GONE
        publicRow.view.visibility = if (showsSupportedAccess) VISIBLE else GONE
        val showsParent = showsSupportedAccess && access.target?.parentSpaceId != null
        parentRow.view.visibility = if (showsParent) VISIBLE else GONE
        unsupportedAccess.visibility = if (showsContent && !access.isJoinRuleSupported) {
            VISIBLE
        } else {
            GONE
        }

        val canSelectAccess = access.canChangeAccess && !access.isSaving
        privateRow.render(access.editAccess == SpaceAccessOption.PRIVATE, canSelectAccess, actions)
        parentRow.render(access.editAccess == SpaceAccessOption.PARENT_MEMBERS, canSelectAccess, actions)
        publicRow.render(access.editAccess == SpaceAccessOption.PUBLIC, canSelectAccess, actions)

        addressHeader.visibility = if (showsContent) VISIBLE else GONE
        addressDescription.visibility = if (showsContent) VISIBLE else GONE
        addressField.visibility = if (showsContent) VISIBLE else GONE
        addressSuffix.text = ":${access.serverName}"
        isRendering = true
        if (addressEdit.text.toString() != access.editAddressLocalPart) {
            addressEdit.setText(access.editAddressLocalPart)
            addressEdit.setSelection(addressEdit.text?.length ?: 0)
        }
        isRendering = false
        addressEdit.isEnabled = access.canChangeAddress && !access.isSaving
        addressField.alpha = if (addressEdit.isEnabled) 1f else DISABLED_ALPHA
        currentAddress.text = access.displayedAddress?.let { address ->
            context.getString(R.string.space_access_current_address, address)
        }.orEmpty()
        currentAddress.visibility = if (
            showsContent && !access.displayedAddress.isNullOrBlank() &&
            access.displayedAddress != access.editFullAddress
        ) VISIBLE else GONE
        addressStatus.text = when (access.addressAvailability) {
            SpaceAddressAvailability.UNCHANGED -> ""
            SpaceAddressAvailability.CHECKING ->
                context.getString(R.string.space_access_address_checking)
            SpaceAddressAvailability.AVAILABLE,
            SpaceAddressAvailability.OWNED_BY_SPACE ->
                context.getString(R.string.space_access_address_available)
            SpaceAddressAvailability.TAKEN ->
                context.getString(R.string.space_access_address_taken)
            SpaceAddressAvailability.REMOVAL_UNSUPPORTED ->
                context.getString(R.string.space_access_address_removal_unsupported)
            SpaceAddressAvailability.INVALID ->
                context.getString(R.string.space_access_address_invalid)
            SpaceAddressAvailability.ERROR ->
                context.getString(R.string.space_access_address_check_error)
        }
        addressStatus.visibility = if (showsContent && addressStatus.text.isNotBlank()) {
            VISIBLE
        } else {
            GONE
        }
        retryAddressButton.visibility = if (
            showsContent &&
            access.addressAvailability == SpaceAddressAvailability.ERROR
        ) VISIBLE else GONE
        retryAddressButton.setOnClickListener { actions.onRetryAddressCheck() }

        directoryHeader.visibility = if (showsContent) VISIBLE else GONE
        val showsDirectory = showsContent && access.isDirectoryVisibilitySupported
        directoryRow.visibility = if (showsDirectory) VISIBLE else GONE
        unsupportedDirectory.visibility = if (showsContent && !showsDirectory) VISIBLE else GONE
        directorySwitch.setOnCheckedChangeListener(null)
        directorySwitch.isChecked = access.editIsVisibleInDirectory
        val canToggleDirectory = access.canChangeDirectoryVisibility &&
            (
                access.editIsVisibleInDirectory ||
                    (access.editAccess == SpaceAccessOption.PUBLIC && access.hasUsableAddress)
            ) &&
            !access.isSaving
        directorySwitch.isEnabled = canToggleDirectory
        directoryRow.alpha = if (canToggleDirectory) 1f else DISABLED_ALPHA
        directoryRow.isClickable = canToggleDirectory
        directoryRow.isFocusable = canToggleDirectory
        directorySwitch.setOnCheckedChangeListener { _, checked ->
            if (!isRendering) actions.onDirectoryVisibilityChanged(checked)
        }
        directoryRow.setOnClickListener(
            if (canToggleDirectory) {
                View.OnClickListener {
                    actions.onDirectoryVisibilityChanged(!directorySwitch.isChecked)
                }
            } else {
                null
            }
        )

        renderDiscardDialog(access, state.presentationKind, actions)
        applyPalette()
    }

    private fun renderDiscardDialog(
        state: SpaceAccessState,
        presentationKind: SpacePresentationKind,
        actions: SpaceAccessScreenViewActions
    ) {
        if (!state.isDiscardConfirmationVisible) {
            discardDialog?.dismiss()
            discardDialog = null
            return
        }
        if (discardDialog?.isShowing == true) return
        discardDialog = AlertDialog.Builder(context)
            .setTitle(R.string.profile_edit_discard_title)
            .setMessage(
                if (presentationKind == SpacePresentationKind.STORYLINE) {
                    R.string.space_access_storyline_discard
                } else {
                    R.string.space_access_track_discard
                }
            )
            .setNegativeButton(R.string.profile_edit_keep_editing) { _, _ ->
                actions.onCancelDiscard()
            }
            .setPositiveButton(R.string.profile_edit_discard) { _, _ ->
                actions.onConfirmDiscard()
            }
            .setOnCancelListener { actions.onCancelDiscard() }
            .create()
            .also(AlertDialog::show)
    }

    private inner class AccessOptionRow(
        private val option: SpaceAccessOption,
        titleRes: Int,
        descriptionRes: Int
    ) {
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            updatePadding(left = dp(14), right = dp(14), top = dp(12), bottom = dp(12))
        }
        private val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        private val title = TextView(context).apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setText(titleRes)
        }
        private val description = TextView(context).apply {
            textSize = 13f
            setText(descriptionRes)
        }
        private val indicator = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        }

        init {
            texts.addView(title)
            texts.addView(description)
            view.addView(texts, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            view.addView(indicator, LinearLayout.LayoutParams(dp(40), LayoutParams.MATCH_PARENT))
        }

        fun render(
            isSelected: Boolean,
            isEnabled: Boolean,
            actions: SpaceAccessScreenViewActions
        ) {
            indicator.text = if (isSelected) "●" else "○"
            indicator.setTextColor(palette.actionText)
            view.isEnabled = isEnabled
            view.isClickable = isEnabled
            view.isFocusable = isEnabled
            view.alpha = if (isEnabled) 1f else DISABLED_ALPHA
            view.setOnClickListener(
                if (isEnabled) View.OnClickListener { actions.onAccessChanged(option) }
                else null
            )
            view.contentDescription = "${title.text}. ${description.text}. " +
                context.getString(
                    if (isSelected) R.string.space_access_selected
                    else R.string.space_access_not_selected
                )
        }

        fun applyPalette() {
            view.background = roundedDrawable(palette.surface, CARD_RADIUS_DP)
            title.setTextColor(palette.primaryText)
            description.setTextColor(palette.secondaryText)
            indicator.setTextColor(palette.actionText)
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
        saveButton.setTextColor(palette.actionText)
        status.setTextColor(palette.secondaryText)
        retryButton.setTextColor(palette.actionText)
        retryButton.background = roundedDrawable(palette.surface, CARD_RADIUS_DP)
        listOf(accessHeader, addressHeader, directoryHeader).forEach {
            it.setTextColor(palette.secondaryText)
        }
        unsupportedAccess.setTextColor(palette.secondaryText)
        unsupportedAccess.background = roundedDrawable(palette.surface, CARD_RADIUS_DP)
        unsupportedDirectory.setTextColor(palette.secondaryText)
        unsupportedDirectory.background = roundedDrawable(palette.surface, CARD_RADIUS_DP)
        privateRow.applyPalette()
        parentRow.applyPalette()
        publicRow.applyPalette()
        addressDescription.setTextColor(palette.secondaryText)
        addressField.background = roundedDrawable(palette.surface, CARD_RADIUS_DP)
        addressPrefix.setTextColor(palette.primaryText)
        addressEdit.setTextColor(palette.primaryText)
        addressEdit.setHintTextColor(palette.secondaryText)
        addressSuffix.setTextColor(palette.secondaryText)
        currentAddress.setTextColor(palette.secondaryText)
        addressStatus.setTextColor(palette.secondaryText)
        retryAddressButton.setTextColor(palette.actionText)
        directoryRow.background = roundedDrawable(palette.surface, CARD_RADIUS_DP)
        directoryTitle.setTextColor(palette.primaryText)
        directoryDescription.setTextColor(palette.secondaryText)
    }

    private fun sectionHeader(value: String): TextView {
        return TextView(context).apply {
            text = value
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }
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
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
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
        const val CARD_RADIUS_DP = 12
        const val DISABLED_ALPHA = 0.48f
    }
}

private data class SpaceAccessPalette(
    val background: Int,
    val surface: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val actionText: Int
) {
    companion object {
        fun from(context: Context): SpaceAccessPalette {
            val dark = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            return if (dark) {
                SpaceAccessPalette(
                    background = Color.rgb(18, 18, 22),
                    surface = Color.rgb(31, 31, 36),
                    primaryText = Color.rgb(232, 225, 229),
                    secondaryText = Color.rgb(202, 196, 208),
                    actionText = Color.rgb(208, 188, 255)
                )
            } else {
                SpaceAccessPalette(
                    background = Color.WHITE,
                    surface = Color.rgb(247, 242, 250),
                    primaryText = Color.rgb(29, 27, 32),
                    secondaryText = Color.rgb(73, 69, 79),
                    actionText = Color.rgb(103, 80, 164)
                )
            }
        }
    }
}
