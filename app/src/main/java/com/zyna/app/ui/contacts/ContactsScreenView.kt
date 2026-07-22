package com.zyna.app.ui.contacts

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.zyna.app.R
import com.zyna.app.data.media.MatrixMediaLoader
import com.zyna.app.data.matrix.MatrixContact
import com.zyna.app.ui.avatar.MatrixAvatarView
import com.zyna.app.ui.settings.SettingsPalette
import kotlin.math.roundToInt

internal data class ContactsScreenViewState(
    val contacts: List<MatrixContact>,
    val searchQuery: String,
    val isSearching: Boolean,
    val errorMessage: String?,
    val actionUserId: String?,
    val matrixMediaLoader: MatrixMediaLoader?,
    val bottomContentPaddingPx: Int
)

internal data class ContactsScreenViewActions(
    val onSearchQueryChanged: (String) -> Unit,
    val onOpenProfile: (MatrixContact) -> Unit,
    val onOpenChat: (MatrixContact) -> Unit,
    val onCall: (MatrixContact) -> Unit
)

internal class ContactsScreenView(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private var palette = SettingsPalette.from(context)
    private var statusTopInset = 0
    private var isApplyingSearchState = false
    private var actions: ContactsScreenViewActions? = null

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val topBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val titleText = TextView(context).apply {
        text = context.getString(R.string.contacts_title)
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = true
        updatePadding(left = dp(20), right = dp(12))
    }
    private val searchField = EditText(context).apply {
        setSingleLine(true)
        hint = context.getString(R.string.contacts_search_hint)
        textSize = 16f
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        inputType = android.text.InputType.TYPE_CLASS_TEXT
        includeFontPadding = true
        background = roundedDrawable(SettingsPalette.from(context).surface, dp(12))
        updatePadding(left = dp(14), right = dp(14), top = dp(10), bottom = dp(10))
    }
    private val statusText = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 14f
        includeFontPadding = true
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        updatePadding(left = dp(20), right = dp(20), top = dp(8), bottom = dp(8))
    }
    private val recyclerView = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context)
        clipToPadding = false
        itemAnimator = null
    }
    private val adapter = ContactAdapter()

    init {
        setBackgroundColor(palette.background)
        recyclerView.adapter = adapter
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
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        )
        root.addView(
            searchField,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(16)
                rightMargin = dp(16)
                bottomMargin = dp(8)
            }
        )
        root.addView(
            statusText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            recyclerView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!isApplyingSearchState) {
                    actions?.onSearchQueryChanged(s?.toString().orEmpty())
                }
            }
        })
        searchField.setOnEditorActionListener { view, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                view.clearFocus()
                context.getSystemService(InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(view.windowToken, 0)
                true
            } else {
                false
            }
        }

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
        adapter.palette = palette
    }

    fun render(state: ContactsScreenViewState, actions: ContactsScreenViewActions) {
        this.actions = actions
        adapter.actions = actions
        adapter.matrixMediaLoader = state.matrixMediaLoader
        adapter.actionUserId = state.actionUserId
        adapter.palette = palette

        if (searchField.text.toString() != state.searchQuery) {
            isApplyingSearchState = true
            searchField.setText(state.searchQuery)
            searchField.setSelection(searchField.text.length)
            isApplyingSearchState = false
        }

        statusText.text = when {
            state.errorMessage != null -> state.errorMessage
            state.isSearching -> context.getString(R.string.contacts_searching)
            state.contacts.isEmpty() && state.searchQuery.isBlank() -> {
                context.getString(R.string.contacts_empty)
            }
            state.contacts.isEmpty() -> context.getString(R.string.contacts_no_results)
            else -> ""
        }
        statusText.visibility = if (statusText.text.isNullOrBlank()) GONE else VISIBLE

        recyclerView.updatePadding(bottom = state.bottomContentPaddingPx + dp(12))
        adapter.submitList(state.contacts)
    }

    private fun applyPalette() {
        setBackgroundColor(palette.background)
        root.setBackgroundColor(palette.background)
        topBar.setBackgroundColor(palette.background)
        titleText.setTextColor(palette.titleText)
        searchField.setTextColor(palette.primaryText)
        searchField.setHintTextColor(palette.secondaryText)
        searchField.background = roundedDrawable(palette.surface, dp(12))
        statusText.setTextColor(palette.secondaryText)
        recyclerView.setBackgroundColor(palette.background)
    }

    private fun updateTopBarHeight() {
        topBar.updatePadding(top = statusTopInset)
        val params = topBar.layoutParams as LinearLayout.LayoutParams
        params.height = dp(TOP_BAR_HEIGHT_DP) + statusTopInset
        topBar.layoutParams = params
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

private class ContactAdapter : ListAdapter<MatrixContact, ContactViewHolder>(ContactDiffCallback) {
    var actions: ContactsScreenViewActions? = null
    var matrixMediaLoader: MatrixMediaLoader? = null
    var actionUserId: String? = null
    var palette: SettingsPalette? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        return ContactViewHolder(parent.context)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        holder.bind(
            contact = getItem(position),
            matrixMediaLoader = matrixMediaLoader,
            isBusy = getItem(position).userId == actionUserId,
            palette = palette ?: SettingsPalette.from(holder.itemView.context),
            actions = actions
        )
    }
}

private class ContactViewHolder(context: Context) : RecyclerView.ViewHolder(
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
) {
    private val density = context.resources.displayMetrics.density
    private val root = itemView as LinearLayout
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        updatePadding(left = dp(16), right = dp(12), top = dp(10), bottom = dp(10))
    }
    private val avatar = MatrixAvatarView(context)
    private val textColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val nameText = TextView(context).apply {
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = true
    }
    private val userIdText = TextView(context).apply {
        textSize = 13f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = true
    }
    private val messageButton = TextView(context).apply {
        text = context.getString(R.string.contacts_message)
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = true
        isClickable = true
        isFocusable = true
        compoundDrawablePadding = dp(4)
        setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_tab_chats_24, 0, 0, 0)
        updatePadding(left = dp(10), right = dp(10), top = dp(7), bottom = dp(7))
    }
    private val callButton = TextView(context).apply {
        text = context.getString(R.string.contacts_call)
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = true
        isClickable = true
        isFocusable = true
        compoundDrawablePadding = dp(4)
        setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_tab_calls_24, 0, 0, 0)
        updatePadding(left = dp(10), right = dp(10), top = dp(7), bottom = dp(7))
    }
    private val separator = View(context)

    init {
        root.addView(row)
        row.addView(
            avatar,
            LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                rightMargin = dp(12)
            }
        )
        textColumn.addView(nameText)
        textColumn.addView(userIdText)
        row.addView(
            textColumn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            messageButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(8)
            }
        )
        row.addView(
            callButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(6)
            }
        )
        root.addView(
            separator,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                leftMargin = dp(72)
            }
        )
    }

    fun bind(
        contact: MatrixContact,
        matrixMediaLoader: MatrixMediaLoader?,
        isBusy: Boolean,
        palette: SettingsPalette,
        actions: ContactsScreenViewActions?
    ) {
        root.setBackgroundColor(palette.background)
        row.setBackgroundColor(palette.background)
        avatar.setPaletteBackground(palette.background)
        avatar.render(
            userId = contact.userId,
            displayName = contact.displayName,
            avatarUrl = contact.avatarUrl,
            localAvatarPath = null,
            matrixMediaLoader = matrixMediaLoader,
            sizePx = dp(44)
        )
        nameText.text = contact.displayName
        userIdText.text = contact.userId
        nameText.setTextColor(palette.titleText)
        userIdText.setTextColor(palette.secondaryText)
        separator.setBackgroundColor(palette.separator)

        val enabledAlpha = if (isBusy) 0.48f else 1f
        row.alpha = enabledAlpha
        messageButton.alpha = enabledAlpha
        callButton.alpha = enabledAlpha
        messageButton.isEnabled = !isBusy
        callButton.isEnabled = !isBusy
        messageButton.setTextColor(palette.actionText)
        callButton.setTextColor(palette.actionText)
        messageButton.compoundDrawableTintList = ColorStateList.valueOf(palette.actionText)
        callButton.compoundDrawableTintList = ColorStateList.valueOf(palette.actionText)
        messageButton.background = roundedDrawable(palette.selectedFill, dp(12))
        callButton.background = roundedDrawable(palette.surface, dp(12))

        row.setOnClickListener { actions?.onOpenProfile(contact) }
        messageButton.setOnClickListener { actions?.onOpenChat(contact) }
        callButton.setOnClickListener { actions?.onCall(contact) }
        itemView.contentDescription = "${contact.displayName}. ${contact.userId}"
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
}

private object ContactDiffCallback : DiffUtil.ItemCallback<MatrixContact>() {
    override fun areItemsTheSame(oldItem: MatrixContact, newItem: MatrixContact): Boolean {
        return oldItem.userId == newItem.userId
    }

    override fun areContentsTheSame(oldItem: MatrixContact, newItem: MatrixContact): Boolean {
        return oldItem == newItem
    }
}
