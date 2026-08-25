package com.zyna.app.ui.settings

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.zyna.app.R
import com.zyna.app.data.presence.PresenceProviderMode
import com.zyna.app.data.security.MatrixLogoutWarning
import com.zyna.app.ui.theme.AppThemeMode
import kotlin.math.roundToInt

internal data class SettingsScreenViewState(
    val selectedChatThemeTitle: String,
    val selectedAppThemeMode: AppThemeMode,
    val selectedPresenceProvider: PresenceProviderMode,
    val isSessionSecurityReady: Boolean,
    val isLoggingOut: Boolean,
    val logoutErrorMessage: String?,
    val isLogoutConfirmationVisible: Boolean,
    val logoutWarning: MatrixLogoutWarning?,
    val bottomContentPaddingPx: Int
)

internal data class SettingsScreenViewActions(
    val onBack: () -> Unit,
    val onOpenChatTheme: () -> Unit,
    val onSelectAppThemeMode: (AppThemeMode) -> Unit,
    val onSelectPresenceProvider: (PresenceProviderMode) -> Unit,
    val onOpenSessionSecurity: () -> Unit,
    val onLogoutRequested: () -> Unit,
    val onLogoutConfirmed: () -> Unit,
    val onLogoutCancelled: () -> Unit
)

internal class SettingsScreenView(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private var palette = SettingsPalette.from(context)
    private var statusTopInset = 0
    private var bottomContentPaddingPx = 0
    private var logoutDialog: AlertDialog? = null
    private var appThemeDialog: AlertDialog? = null
    private var presenceProviderDialog: AlertDialog? = null

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
        includeFontPadding = true
        isClickable = true
        isFocusable = true
        updatePadding(left = dp(20), right = dp(12))
    }
    private val titleText = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        text = "Settings"
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = true
        updatePadding(left = dp(20), right = dp(20))
    }
    private val scrollView = ScrollView(context).apply {
        isFillViewport = true
        clipToPadding = false
    }
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        updatePadding(left = dp(16), right = dp(16), top = dp(18))
    }
    private val appearanceHeader = sectionHeader("Appearance")
    private val appThemeRow = SettingsRowView(context).apply {
        title = "App Theme"
        isClickable = true
        isFocusable = true
    }
    private val chatThemeRow = SettingsRowView(context).apply {
        title = "Chat Theme"
        isClickable = true
        isFocusable = true
    }
    private val presenceRow = SettingsRowView(context).apply {
        title = context.getString(R.string.settings_presence)
        isClickable = true
        isFocusable = true
    }
    private val accountHeader = sectionHeader("Account")
    private val encryptionRow = SettingsRowView(context).apply {
        title = context.getString(R.string.settings_encryption)
        isClickable = true
        isFocusable = true
    }
    private val logoutRow = SettingsRowView(context).apply {
        title = context.getString(R.string.settings_logout)
        showsAccessory = false
        isClickable = true
        isFocusable = true
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
        content.addView(appearanceHeader)
        content.addView(appThemeRow, rowLayoutParams())
        content.addView(chatThemeRow, rowLayoutParams())
        content.addView(presenceRow, rowLayoutParams())
        content.addView(accountHeader)
        content.addView(encryptionRow, rowLayoutParams())
        content.addView(logoutRow, rowLayoutParams())

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
        appThemeDialog?.dismiss()
        appThemeDialog = null
        presenceProviderDialog?.dismiss()
        presenceProviderDialog = null
        logoutDialog?.dismiss()
        logoutDialog = null
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        palette = SettingsPalette.from(context)
        applyPalette()
    }

    fun render(state: SettingsScreenViewState, actions: SettingsScreenViewActions) {
        bottomContentPaddingPx = state.bottomContentPaddingPx
        updateContentPadding()
        backButton.setOnClickListener { actions.onBack() }
        appThemeRow.detail = state.selectedAppThemeMode.title
        appThemeRow.setOnClickListener {
            showAppThemePicker(
                selectedMode = state.selectedAppThemeMode,
                onSelectMode = actions.onSelectAppThemeMode
            )
        }
        chatThemeRow.detail = state.selectedChatThemeTitle
        chatThemeRow.setOnClickListener { actions.onOpenChatTheme() }
        presenceRow.detail = state.selectedPresenceProvider.title(context)
        presenceRow.setOnClickListener {
            showPresenceProviderPicker(
                selectedProvider = state.selectedPresenceProvider,
                onSelectProvider = actions.onSelectPresenceProvider
            )
        }
        encryptionRow.detail = context.getString(
            if (state.isSessionSecurityReady) {
                R.string.settings_encryption_ready
            } else {
                R.string.settings_encryption_action_required
            }
        )
        encryptionRow.setOnClickListener { actions.onOpenSessionSecurity() }
        logoutRow.detail = when {
            state.isLoggingOut -> context.getString(R.string.settings_logout_preparing)
            state.logoutErrorMessage != null -> context.getString(R.string.settings_logout_error)
            else -> null
        }
        logoutRow.isEnabled = !state.isLoggingOut
        logoutRow.setOnClickListener {
            if (!state.isLoggingOut) actions.onLogoutRequested()
        }
        if (state.isLogoutConfirmationVisible) {
            showLogoutConfirmation(
                warning = state.logoutWarning,
                onConfirm = actions.onLogoutConfirmed,
                onCancel = actions.onLogoutCancelled
            )
        }
    }

    private fun applyPalette() {
        setBackgroundColor(palette.background)
        root.setBackgroundColor(palette.background)
        topBar.setBackgroundColor(palette.background)
        backButton.setTextColor(palette.actionText)
        titleText.setTextColor(palette.titleText)
        scrollView.setBackgroundColor(palette.background)
        content.setBackgroundColor(palette.background)
        appearanceHeader.setTextColor(palette.secondaryText)
        accountHeader.setTextColor(palette.secondaryText)
        appThemeRow.setPalette(palette)
        chatThemeRow.setPalette(palette)
        presenceRow.setPalette(palette)
        encryptionRow.setPalette(palette)
        logoutRow.setPalette(palette)
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
        scrollView.updatePadding(bottom = bottomContentPaddingPx + dp(16))
    }

    private fun showAppThemePicker(
        selectedMode: AppThemeMode,
        onSelectMode: (AppThemeMode) -> Unit
    ) {
        val existingDialog = appThemeDialog
        if (existingDialog?.isShowing == true) {
            return
        }
        val modes = AppThemeMode.entries.toTypedArray()
        val labels = modes.map { it.title }.toTypedArray()
        val selectedIndex = modes.indexOf(selectedMode).coerceAtLeast(0)
        appThemeDialog = AlertDialog.Builder(context)
            .setTitle("App Theme")
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                modes.getOrNull(which)?.let(onSelectMode)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .create()
            .also { dialog ->
                dialog.setOnDismissListener {
                    if (appThemeDialog === dialog) {
                        appThemeDialog = null
                    }
                }
                dialog.show()
            }
    }

    private fun showPresenceProviderPicker(
        selectedProvider: PresenceProviderMode,
        onSelectProvider: (PresenceProviderMode) -> Unit
    ) {
        val existingDialog = presenceProviderDialog
        if (existingDialog?.isShowing == true) {
            return
        }
        val providers = PresenceProviderMode.entries.toTypedArray()
        val labels = providers.map { it.title(context) }.toTypedArray()
        val selectedIndex = providers.indexOf(selectedProvider).coerceAtLeast(0)
        presenceProviderDialog = AlertDialog.Builder(context)
            .setTitle(R.string.settings_presence)
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                providers.getOrNull(which)?.let(onSelectProvider)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .create()
            .also { dialog ->
                dialog.setOnDismissListener {
                    if (presenceProviderDialog === dialog) {
                        presenceProviderDialog = null
                    }
                }
                dialog.show()
            }
    }

    private fun showLogoutConfirmation(
        warning: MatrixLogoutWarning?,
        onConfirm: () -> Unit,
        onCancel: () -> Unit
    ) {
        val existingDialog = logoutDialog
        if (existingDialog?.isShowing == true) {
            return
        }
        var handled = false
        val messageRes = when (warning) {
            MatrixLogoutWarning.SECURITY_NOT_READY -> R.string.settings_logout_security_warning
            MatrixLogoutWarning.BACKUP_NOT_READY -> R.string.settings_logout_backup_warning
            null -> R.string.settings_logout_confirmation
        }
        val positiveButtonRes = if (warning == null) {
            R.string.settings_logout
        } else {
            R.string.settings_logout_anyway
        }
        logoutDialog = AlertDialog.Builder(context)
            .setTitle(R.string.settings_logout_title)
            .setMessage(messageRes)
            .setNegativeButton(R.string.common_cancel) { _, _ ->
                handled = true
                onCancel()
            }
            .setPositiveButton(positiveButtonRes) { _, _ ->
                handled = true
                onConfirm()
            }
            .create()
            .also { dialog ->
                dialog.setOnCancelListener {
                    if (!handled) {
                        handled = true
                        onCancel()
                    }
                }
                dialog.setOnDismissListener {
                    if (logoutDialog === dialog) {
                        logoutDialog = null
                    }
                }
                dialog.show()
            }
    }

    private fun dp(value: Int): Int {
        return (value * density).roundToInt()
    }

    private companion object {
        const val TOP_BAR_HEIGHT_DP = 64
        const val ROW_HEIGHT_DP = 56
    }
}

private fun PresenceProviderMode.title(context: Context): String {
    val resId = when (this) {
        PresenceProviderMode.ZYNA_REALTIME -> R.string.presence_provider_zyna_realtime
        PresenceProviderMode.MATRIX_STANDARD -> R.string.presence_provider_matrix_standard
        PresenceProviderMode.OFF -> R.string.presence_provider_off
    }
    return context.getString(resId)
}

private class SettingsRowView(context: Context) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private var palette = SettingsPalette.from(context)
    private val titleText = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        textSize = 17f
        includeFontPadding = true
        maxLines = 1
    }
    private val detailText = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        textSize = 15f
        includeFontPadding = true
        maxLines = 1
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

    fun setPalette(nextPalette: SettingsPalette) {
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
