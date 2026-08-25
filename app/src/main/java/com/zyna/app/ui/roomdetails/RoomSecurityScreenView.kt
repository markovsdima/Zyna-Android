package com.zyna.app.ui.roomdetails

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
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.zyna.app.R
import com.zyna.app.data.matrix.MatrixRoomHistoryVisibility
import kotlin.math.roundToInt

internal data class RoomSecurityScreenViewState(
    val security: RoomSecurityState,
    val errorMessage: String?
)

internal data class RoomSecurityScreenViewActions(
    val onBack: () -> Unit,
    val onRetry: () -> Unit,
    val onAccessChanged: (RoomSecurityAccessOption) -> Unit,
    val onAuthorizedSpaceToggled: (String) -> Unit,
    val onHistoryChanged: (MatrixRoomHistoryVisibility) -> Unit,
    val onEncryptionChanged: (Boolean) -> Unit,
    val onConfirmEncryption: () -> Unit,
    val onCancelEncryption: () -> Unit,
    val onAddressChanged: (String) -> Unit,
    val onRetryAddressCheck: () -> Unit,
    val onDirectoryVisibilityChanged: (Boolean) -> Unit,
    val onSave: () -> Unit,
    val onConfirmDiscard: () -> Unit,
    val onCancelDiscard: () -> Unit
)

/** Security editor for ordinary Matrix group rooms. */
@Suppress("SetTextI18n")
internal class RoomSecurityScreenView(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private var palette = RoomSecurityPalette.from(context)
    private var topInset = 0
    private var bottomInset = 0
    private var isRendering = false
    private var discardDialog: AlertDialog? = null
    private var encryptionDialog: AlertDialog? = null
    private var latestActions: RoomSecurityScreenViewActions? = null

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
        setText(R.string.room_security_title)
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

    private val accessHeader = sectionHeader(R.string.room_security_access_header)
    private val inviteRow = ChoiceRow(
        titleRes = R.string.room_security_invite_title,
        descriptionRes = R.string.room_security_invite_description
    )
    private val parentSpaceRow = ChoiceRow(
        titleRes = R.string.room_security_parent_spaces_title,
        descriptionRes = R.string.room_security_parent_spaces_description
    )
    private val publicRow = ChoiceRow(
        titleRes = R.string.room_security_public_title,
        descriptionRes = R.string.room_security_public_description
    )
    private val unsupportedAccess = unsupportedText(R.string.room_security_access_unsupported)

    private val authorizedHeader = sectionHeader(R.string.room_security_authorized_parents_header)
    private val authorizedDescription = descriptionText(
        R.string.room_security_authorized_parents_description
    )
    private val authorizedContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val noParentSpaces = unsupportedText(R.string.room_security_no_parent_spaces)

    private val addressHeader = sectionHeader(R.string.room_security_address_header)
    private val addressDescription = descriptionText(R.string.room_security_address_description)
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

    private val directoryHeader = sectionHeader(R.string.room_security_directory_header)
    private val directoryRow = SwitchRow(
        titleRes = R.string.room_security_directory_title,
        descriptionRes = R.string.room_security_directory_description
    )
    private val unsupportedDirectory = unsupportedText(
        R.string.room_security_directory_unsupported
    )

    private val encryptionHeader = sectionHeader(R.string.room_security_encryption_header)
    private val encryptionRow = SwitchRow(
        titleRes = R.string.room_security_encryption_title,
        descriptionRes = R.string.room_security_encryption_description
    )
    private val encryptionUnknown = unsupportedText(R.string.room_security_encryption_unknown)

    private val historyHeader = sectionHeader(R.string.room_security_history_header)
    private val sharedHistoryRow = ChoiceRow(
        titleRes = R.string.room_security_history_shared_title,
        descriptionRes = R.string.room_security_history_shared_description
    )
    private val invitedHistoryRow = ChoiceRow(
        titleRes = R.string.room_security_history_invited_title,
        descriptionRes = R.string.room_security_history_invited_description
    )
    private val joinedHistoryRow = ChoiceRow(
        titleRes = R.string.room_security_history_joined_title,
        descriptionRes = R.string.room_security_history_joined_description
    )
    private val worldHistoryRow = ChoiceRow(
        titleRes = R.string.room_security_history_world_title,
        descriptionRes = R.string.room_security_history_world_description
    )
    private val unsupportedHistory = unsupportedText(R.string.room_security_history_unsupported)
    private val historyWarning = descriptionText(R.string.room_security_world_readable_conflict)

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
        content.addView(publicRow.view, matchWrap(top = 8))
        content.addView(parentSpaceRow.view, matchWrap(top = 8))
        content.addView(inviteRow.view, matchWrap(top = 8))
        content.addView(unsupportedAccess, matchWrap(top = 8))

        content.addView(authorizedHeader, matchWrap(top = 20))
        content.addView(authorizedDescription, matchWrap(top = 8))
        content.addView(authorizedContainer, matchWrap(top = 2))
        content.addView(noParentSpaces, matchWrap(top = 8))

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
        content.addView(directoryRow.view, matchWrap(top = 8))
        content.addView(unsupportedDirectory, matchWrap(top = 8))

        content.addView(encryptionHeader, matchWrap(top = 24))
        content.addView(encryptionRow.view, matchWrap(top = 8))
        content.addView(encryptionUnknown, matchWrap(top = 8))

        content.addView(historyHeader, matchWrap(top = 24))
        content.addView(sharedHistoryRow.view, matchWrap(top = 8))
        content.addView(invitedHistoryRow.view, matchWrap(top = 8))
        content.addView(joinedHistoryRow.view, matchWrap(top = 8))
        content.addView(worldHistoryRow.view, matchWrap(top = 8))
        content.addView(unsupportedHistory, matchWrap(top = 8))
        content.addView(historyWarning, matchWrap(top = 8))

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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    override fun onDetachedFromWindow() {
        discardDialog?.dismiss()
        discardDialog = null
        encryptionDialog?.dismiss()
        encryptionDialog = null
        latestActions = null
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        palette = RoomSecurityPalette.from(context)
        applyPalette()
    }

    fun render(state: RoomSecurityScreenViewState, actions: RoomSecurityScreenViewActions) {
        latestActions = actions
        val security = state.security
        backButton.setOnClickListener { actions.onBack() }
        backButton.isEnabled = !security.isSaving
        saveButton.setOnClickListener(
            if (security.canSave) View.OnClickListener { actions.onSave() } else null
        )
        saveButton.isEnabled = security.canSave
        saveButton.alpha = if (security.canSave) 1f else DISABLED_ALPHA

        status.text = when {
            security.isLoading -> context.getString(R.string.room_security_loading)
            security.isSaving -> context.getString(R.string.room_security_saving)
            else -> state.errorMessage.orEmpty()
        }
        status.visibility = if (status.text.isBlank()) GONE else VISIBLE
        retryButton.visibility = if (security.error == RoomSecurityError.LOAD) VISIBLE else GONE
        retryButton.setOnClickListener { actions.onRetry() }

        val showsContent = !security.isLoading && security.target != null &&
            security.error != RoomSecurityError.LOAD
        renderAccess(security, showsContent, actions)
        renderAddress(security, showsContent, actions)
        renderDirectory(security, showsContent, actions)
        renderEncryption(security, showsContent, actions)
        renderHistory(security, showsContent, actions)
        renderDialogs(security, actions)
        applyPalette()
    }

    private fun renderAccess(
        state: RoomSecurityState,
        showsContent: Boolean,
        actions: RoomSecurityScreenViewActions
    ) {
        accessHeader.visibility = if (showsContent) VISIBLE else GONE
        val supported = showsContent && state.isJoinRuleSupported
        publicRow.view.visibility = if (supported) VISIBLE else GONE
        inviteRow.view.visibility = if (supported) VISIBLE else GONE
        val canOfferParentSpaces = state.selectableSpaceIds.isNotEmpty() ||
            state.access == RoomSecurityAccessOption.PARENT_SPACE_MEMBERS
        parentSpaceRow.view.visibility = if (supported && canOfferParentSpaces) VISIBLE else GONE
        unsupportedAccess.visibility = if (showsContent && !supported) VISIBLE else GONE

        val canSelect = state.canChangeAccess && !state.isSaving
        publicRow.render(
            selected = state.editAccess == RoomSecurityAccessOption.PUBLIC,
            enabled = canSelect,
            onClick = { actions.onAccessChanged(RoomSecurityAccessOption.PUBLIC) }
        )
        inviteRow.render(
            selected = state.editAccess == RoomSecurityAccessOption.INVITE_ONLY,
            enabled = canSelect,
            onClick = { actions.onAccessChanged(RoomSecurityAccessOption.INVITE_ONLY) }
        )
        parentSpaceRow.render(
            selected = state.editAccess == RoomSecurityAccessOption.PARENT_SPACE_MEMBERS,
            enabled = canSelect && state.selectableSpaceIds.isNotEmpty(),
            onClick = { actions.onAccessChanged(RoomSecurityAccessOption.PARENT_SPACE_MEMBERS) }
        )

        val showsAuthorized = supported &&
            state.editAccess == RoomSecurityAccessOption.PARENT_SPACE_MEMBERS
        authorizedHeader.visibility = if (showsAuthorized) VISIBLE else GONE
        authorizedDescription.visibility = if (showsAuthorized) VISIBLE else GONE
        authorizedContainer.visibility = if (
            showsAuthorized && state.selectableSpaceIds.isNotEmpty()
        ) VISIBLE else GONE
        noParentSpaces.visibility = if (
            showsAuthorized && state.selectableSpaceIds.isEmpty()
        ) VISIBLE else GONE
        rebuildAuthorizedSpaces(state, actions)
    }

    private fun rebuildAuthorizedSpaces(
        state: RoomSecurityState,
        actions: RoomSecurityScreenViewActions
    ) {
        authorizedContainer.removeAllViews()
        if (authorizedContainer.visibility != VISIBLE) return
        val knownById = state.parentSpaces.associateBy(RoomSecurityParentSpace::roomId)
        state.selectableSpaceIds
            .sortedWith(
                compareBy<String> {
                    knownById[it]?.displayName?.lowercase() ?: it.lowercase()
                }.thenBy { it }
            )
            .forEachIndexed { index, spaceId ->
                val known = knownById[spaceId]
                val row = CheckRow(
                    title = known?.displayName
                        ?: context.getString(R.string.room_security_unknown_parent_space),
                    description = if (known == null) spaceId else null
                )
                row.render(
                    checked = spaceId in state.editAuthorizedSpaceIds,
                    enabled = state.canChangeAccess && !state.isSaving,
                    onClick = { actions.onAuthorizedSpaceToggled(spaceId) }
                )
                row.applyPalette()
                authorizedContainer.addView(row.view, matchWrap(top = if (index == 0) 6 else 8))
            }
    }

    private fun renderAddress(
        state: RoomSecurityState,
        showsContent: Boolean,
        actions: RoomSecurityScreenViewActions
    ) {
        addressHeader.visibility = if (showsContent) VISIBLE else GONE
        addressDescription.visibility = if (showsContent) VISIBLE else GONE
        addressField.visibility = if (showsContent) VISIBLE else GONE
        addressSuffix.text = ":${state.serverName}"
        isRendering = true
        if (addressEdit.text.toString() != state.editAddressLocalPart) {
            addressEdit.setText(state.editAddressLocalPart)
            addressEdit.setSelection(addressEdit.text?.length ?: 0)
        }
        isRendering = false
        addressEdit.isEnabled = state.canChangeAddress && !state.isSaving
        addressField.alpha = if (addressEdit.isEnabled) 1f else DISABLED_ALPHA
        currentAddress.text = state.displayedAddress?.let { address ->
            context.getString(R.string.room_security_current_address, address)
        }.orEmpty()
        currentAddress.visibility = if (
            showsContent && !state.displayedAddress.isNullOrBlank() &&
            state.displayedAddress != state.editFullAddress
        ) VISIBLE else GONE
        addressStatus.text = when (state.addressAvailability) {
            RoomSecurityAddressAvailability.UNCHANGED -> ""
            RoomSecurityAddressAvailability.CHECKING ->
                context.getString(R.string.room_security_address_checking)
            RoomSecurityAddressAvailability.AVAILABLE,
            RoomSecurityAddressAvailability.OWNED_BY_ROOM ->
                context.getString(R.string.room_security_address_available)
            RoomSecurityAddressAvailability.REMOVAL_READY ->
                context.getString(R.string.room_security_address_removal_ready)
            RoomSecurityAddressAvailability.TAKEN ->
                context.getString(R.string.room_security_address_taken)
            RoomSecurityAddressAvailability.INVALID ->
                context.getString(R.string.room_security_address_invalid)
            RoomSecurityAddressAvailability.ERROR ->
                context.getString(R.string.room_security_address_check_error)
        }
        addressStatus.visibility = if (showsContent && addressStatus.text.isNotBlank()) {
            VISIBLE
        } else {
            GONE
        }
        retryAddressButton.visibility = if (
            showsContent && state.addressAvailability == RoomSecurityAddressAvailability.ERROR
        ) VISIBLE else GONE
        retryAddressButton.setOnClickListener { actions.onRetryAddressCheck() }
    }

    private fun renderDirectory(
        state: RoomSecurityState,
        showsContent: Boolean,
        actions: RoomSecurityScreenViewActions
    ) {
        directoryHeader.visibility = if (showsContent) VISIBLE else GONE
        val supported = showsContent && state.isDirectoryVisibilitySupported
        directoryRow.view.visibility = if (supported) VISIBLE else GONE
        unsupportedDirectory.visibility = if (showsContent && !supported) VISIBLE else GONE
        val canToggle = state.canChangeDirectoryVisibility &&
            (state.editIsVisibleInDirectory ||
                (state.editAccess == RoomSecurityAccessOption.PUBLIC &&
                    state.hasUsableLocalAddress)) &&
            !state.isSaving
        directoryRow.render(
            checked = state.editIsVisibleInDirectory,
            enabled = canToggle,
            descriptionRes = R.string.room_security_directory_description,
            onChanged = actions.onDirectoryVisibilityChanged
        )
    }

    private fun renderEncryption(
        state: RoomSecurityState,
        showsContent: Boolean,
        actions: RoomSecurityScreenViewActions
    ) {
        encryptionHeader.visibility = if (showsContent) VISIBLE else GONE
        val supported = showsContent && state.isEncrypted != null
        encryptionRow.view.visibility = if (supported) VISIBLE else GONE
        encryptionUnknown.visibility = if (showsContent && !supported) VISIBLE else GONE
        if (!supported) return
        val canToggle = state.isEncrypted == false && state.canEnableEncryption && !state.isSaving
        encryptionRow.render(
            checked = state.editIsEncrypted,
            enabled = canToggle,
            dimWhenDisabled = state.isEncrypted != true,
            descriptionRes = if (state.isEncrypted == true) {
                R.string.room_security_encryption_enabled_description
            } else {
                R.string.room_security_encryption_description
            },
            onChanged = actions.onEncryptionChanged
        )
    }

    private fun renderHistory(
        state: RoomSecurityState,
        showsContent: Boolean,
        actions: RoomSecurityScreenViewActions
    ) {
        historyHeader.visibility = if (showsContent) VISIBLE else GONE
        val supported = showsContent && state.isHistoryVisibilitySupported
        val rows = listOf(sharedHistoryRow, invitedHistoryRow, joinedHistoryRow, worldHistoryRow)
        rows.forEach { it.view.visibility = if (supported) VISIBLE else GONE }
        unsupportedHistory.visibility = if (showsContent && !supported) VISIBLE else GONE
        historyWarning.visibility = if (
            supported && state.hasInvalidWorldReadableCombination
        ) VISIBLE else GONE
        if (!supported) return
        val canSelect = state.canChangeHistoryVisibility && !state.isSaving
        sharedHistoryRow.render(
            selected = state.editHistoryVisibility == MatrixRoomHistoryVisibility.SHARED,
            enabled = canSelect,
            onClick = { actions.onHistoryChanged(MatrixRoomHistoryVisibility.SHARED) }
        )
        invitedHistoryRow.render(
            selected = state.editHistoryVisibility == MatrixRoomHistoryVisibility.INVITED,
            enabled = canSelect,
            onClick = { actions.onHistoryChanged(MatrixRoomHistoryVisibility.INVITED) }
        )
        joinedHistoryRow.render(
            selected = state.editHistoryVisibility == MatrixRoomHistoryVisibility.JOINED,
            enabled = canSelect,
            onClick = { actions.onHistoryChanged(MatrixRoomHistoryVisibility.JOINED) }
        )
        val canSelectWorld = canSelect && (
            state.editHistoryVisibility == MatrixRoomHistoryVisibility.WORLD_READABLE ||
                (state.editAccess == RoomSecurityAccessOption.PUBLIC && !state.editIsEncrypted)
            )
        worldHistoryRow.render(
            selected = state.editHistoryVisibility == MatrixRoomHistoryVisibility.WORLD_READABLE,
            enabled = canSelectWorld,
            onClick = { actions.onHistoryChanged(MatrixRoomHistoryVisibility.WORLD_READABLE) }
        )
    }

    private fun renderDialogs(
        state: RoomSecurityState,
        actions: RoomSecurityScreenViewActions
    ) {
        if (!state.isDiscardConfirmationVisible) {
            discardDialog?.dismiss()
            discardDialog = null
        } else if (discardDialog?.isShowing != true) {
            discardDialog = AlertDialog.Builder(context)
                .setTitle(R.string.profile_edit_discard_title)
                .setMessage(R.string.room_security_discard)
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

        if (!state.isEncryptionConfirmationVisible) {
            encryptionDialog?.dismiss()
            encryptionDialog = null
        } else if (encryptionDialog?.isShowing != true) {
            encryptionDialog = AlertDialog.Builder(context)
                .setTitle(R.string.room_security_enable_encryption_title)
                .setMessage(R.string.room_security_enable_encryption_message)
                .setNegativeButton(R.string.common_cancel) { _, _ ->
                    actions.onCancelEncryption()
                }
                .setPositiveButton(R.string.room_security_enable_encryption) { _, _ ->
                    actions.onConfirmEncryption()
                }
                .setOnCancelListener { actions.onCancelEncryption() }
                .create()
                .also(AlertDialog::show)
        }
    }

    private inner class ChoiceRow(titleRes: Int, descriptionRes: Int) {
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

        fun render(selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
            indicator.text = if (selected) "●" else "○"
            view.isEnabled = enabled
            view.isClickable = enabled
            view.isFocusable = enabled
            view.alpha = if (enabled) 1f else DISABLED_ALPHA
            view.setOnClickListener(if (enabled) View.OnClickListener { onClick() } else null)
            view.contentDescription = "${title.text}. ${description.text}. " +
                context.getString(
                    if (selected) R.string.space_access_selected
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

    private inner class CheckRow(title: String, description: String?) {
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            updatePadding(left = dp(14), right = dp(14), top = dp(12), bottom = dp(12))
        }
        private val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        private val titleText = TextView(context).apply {
            text = title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }
        private val descriptionText = TextView(context).apply {
            text = description.orEmpty()
            textSize = 13f
            visibility = if (description.isNullOrBlank()) GONE else VISIBLE
        }
        private val indicator = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        }

        init {
            texts.addView(titleText)
            texts.addView(descriptionText)
            view.addView(texts, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            view.addView(indicator, LinearLayout.LayoutParams(dp(40), LayoutParams.MATCH_PARENT))
        }

        fun render(checked: Boolean, enabled: Boolean, onClick: () -> Unit) {
            indicator.text = if (checked) "☑" else "☐"
            view.isEnabled = enabled
            view.isClickable = enabled
            view.isFocusable = enabled
            view.alpha = if (enabled) 1f else DISABLED_ALPHA
            view.setOnClickListener(if (enabled) View.OnClickListener { onClick() } else null)
            view.contentDescription = "${titleText.text}. ${descriptionText.text}. " +
                context.getString(
                    if (checked) R.string.space_access_selected
                    else R.string.space_access_not_selected
                )
        }

        fun applyPalette() {
            view.background = roundedDrawable(palette.surface, CARD_RADIUS_DP)
            titleText.setTextColor(palette.primaryText)
            descriptionText.setTextColor(palette.secondaryText)
            indicator.setTextColor(palette.actionText)
        }
    }

    private inner class SwitchRow(titleRes: Int, descriptionRes: Int) {
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            updatePadding(left = dp(14), right = dp(10), top = dp(12), bottom = dp(12))
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
        private val switch = SwitchCompat(context)

        init {
            texts.addView(title)
            texts.addView(description)
            view.addView(texts, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            view.addView(switch)
        }

        fun render(
            checked: Boolean,
            enabled: Boolean,
            dimWhenDisabled: Boolean = true,
            descriptionRes: Int,
            onChanged: (Boolean) -> Unit
        ) {
            description.setText(descriptionRes)
            isRendering = true
            switch.setOnCheckedChangeListener(null)
            switch.isChecked = checked
            isRendering = false
            switch.isEnabled = enabled
            view.isEnabled = enabled
            view.isClickable = enabled
            view.isFocusable = enabled
            view.alpha = if (enabled || !dimWhenDisabled) 1f else DISABLED_ALPHA
            switch.setOnCheckedChangeListener { _, value ->
                if (!isRendering) onChanged(value)
            }
            view.setOnClickListener(
                if (enabled) View.OnClickListener { onChanged(!switch.isChecked) } else null
            )
        }

        fun applyPalette() {
            view.background = roundedDrawable(palette.surface, CARD_RADIUS_DP)
            title.setTextColor(palette.primaryText)
            description.setTextColor(palette.secondaryText)
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
        listOf(
            accessHeader,
            authorizedHeader,
            addressHeader,
            directoryHeader,
            encryptionHeader,
            historyHeader
        ).forEach { it.setTextColor(palette.secondaryText) }
        listOf(
            unsupportedAccess,
            noParentSpaces,
            unsupportedDirectory,
            encryptionUnknown,
            unsupportedHistory
        ).forEach {
            it.setTextColor(palette.secondaryText)
            it.background = roundedDrawable(palette.surface, CARD_RADIUS_DP)
        }
        listOf(authorizedDescription, addressDescription, historyWarning).forEach {
            it.setTextColor(palette.secondaryText)
        }
        inviteRow.applyPalette()
        parentSpaceRow.applyPalette()
        publicRow.applyPalette()
        sharedHistoryRow.applyPalette()
        invitedHistoryRow.applyPalette()
        joinedHistoryRow.applyPalette()
        worldHistoryRow.applyPalette()
        addressField.background = roundedDrawable(palette.surface, CARD_RADIUS_DP)
        addressPrefix.setTextColor(palette.primaryText)
        addressEdit.setTextColor(palette.primaryText)
        addressEdit.setHintTextColor(palette.secondaryText)
        addressSuffix.setTextColor(palette.secondaryText)
        currentAddress.setTextColor(palette.secondaryText)
        addressStatus.setTextColor(palette.secondaryText)
        retryAddressButton.setTextColor(palette.actionText)
        directoryRow.applyPalette()
        encryptionRow.applyPalette()
    }

    private fun sectionHeader(textRes: Int): TextView {
        return TextView(context).apply {
            setText(textRes)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun descriptionText(textRes: Int): TextView {
        return TextView(context).apply {
            setText(textRes)
            textSize = 14f
        }
    }

    private fun unsupportedText(textRes: Int): TextView {
        return descriptionText(textRes).apply {
            updatePadding(left = dp(14), right = dp(14), top = dp(14), bottom = dp(14))
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
        ).apply { topMargin = dp(top) }
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

private data class RoomSecurityPalette(
    val background: Int,
    val surface: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val actionText: Int
) {
    companion object {
        fun from(context: Context): RoomSecurityPalette {
            val dark = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            return if (dark) {
                RoomSecurityPalette(
                    background = Color.rgb(18, 18, 22),
                    surface = Color.rgb(31, 31, 36),
                    primaryText = Color.rgb(232, 225, 229),
                    secondaryText = Color.rgb(202, 196, 208),
                    actionText = Color.rgb(208, 188, 255)
                )
            } else {
                RoomSecurityPalette(
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
