package com.zyna.app.ui.invitemembers

import android.content.Context
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
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.zyna.app.R
import com.zyna.app.data.media.MatrixMediaLoader
import com.zyna.app.data.matrix.MatrixRoomMemberMembership
import com.zyna.app.ui.avatar.MatrixAvatarView
import com.zyna.app.ui.settings.SettingsPalette
import kotlin.math.roundToInt

internal data class InviteMembersScreenViewState(
    val searchQuery: String,
    val selectedMembers: List<InviteMemberCandidate>,
    val searchResults: List<InviteMemberCandidate>,
    val canInviteMembers: Boolean,
    val canSubmit: Boolean,
    val isPreparing: Boolean,
    val isSearching: Boolean,
    val isSending: Boolean,
    val preparationErrorMessage: String?,
    val permissionErrorMessage: String?,
    val searchErrorMessage: String?,
    val sendErrorMessage: String?,
    val failedInviteCount: Int,
    val permissionDenied: Boolean,
    val canSkip: Boolean,
    val matrixMediaLoader: MatrixMediaLoader?
)

internal data class InviteMembersScreenViewActions(
    val onBack: () -> Unit,
    val onRetryPreparation: () -> Unit,
    val onRetrySearch: () -> Unit,
    val onSearchQueryChanged: (String) -> Unit,
    val onToggleSelection: (InviteMemberCandidate) -> Unit,
    val onSend: () -> Unit
)

internal class InviteMembersScreenView(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private var palette = SettingsPalette.from(context)
    private var statusTopInset = 0
    private var bottomInset = 0
    private var isApplyingSearchState = false
    private var actions: InviteMembersScreenViewActions? = null

    private val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val topBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val backButton = TextView(context).apply {
        text = context.getString(R.string.common_cancel)
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
        isClickable = true
        isFocusable = true
    }
    private val titleText = TextView(context).apply {
        text = context.getString(R.string.invite_members_title)
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = true
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val sendButton = TextView(context).apply {
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
        isClickable = true
        isFocusable = true
    }
    private val searchField = EditText(context).apply {
        setSingleLine(true)
        hint = context.getString(R.string.invite_members_search_hint)
        textSize = 16f
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        inputType = android.text.InputType.TYPE_CLASS_TEXT
        includeFontPadding = true
        updatePadding(left = dp(14), right = dp(14), top = dp(10), bottom = dp(10))
    }
    private val statusRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        updatePadding(left = dp(20), right = dp(16))
    }
    private val statusText = TextView(context).apply {
        textSize = 14f
        includeFontPadding = true
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val retryButton = TextView(context).apply {
        text = context.getString(R.string.invite_members_retry)
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = true
        isClickable = true
        isFocusable = true
        updatePadding(left = dp(12), right = dp(12), top = dp(6), bottom = dp(6))
    }
    private val recyclerView = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context)
        clipToPadding = false
        itemAnimator = null
    }
    private val adapter = InviteMembersAdapter(
        selectedTitle = context.getString(R.string.invite_members_selected_section),
        resultsTitle = context.getString(R.string.invite_members_results_section)
    )

    init {
        recyclerView.adapter = adapter.recyclerAdapter
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
            LinearLayout.LayoutParams(dp(TOP_BAR_SIDE_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT)
        )
        topBar.addView(
            titleText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        )
        topBar.addView(
            sendButton,
            LinearLayout.LayoutParams(dp(TOP_BAR_SIDE_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT)
        )
        root.addView(
            searchField,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(16)
                rightMargin = dp(16)
                bottomMargin = dp(4)
            }
        )
        statusRow.addView(
            statusText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        )
        statusRow.addView(
            retryButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            statusRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(STATUS_ROW_HEIGHT_DP))
        )
        root.addView(
            recyclerView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                text: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) = Unit

            override fun onTextChanged(
                text: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) = Unit

            override fun afterTextChanged(text: Editable?) {
                if (!isApplyingSearchState) {
                    actions?.onSearchQueryChanged(text?.toString().orEmpty())
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
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            if (statusTopInset != systemBars.top) {
                statusTopInset = systemBars.top
                updateTopBarHeight()
            }
            if (bottomInset != systemBars.bottom) {
                bottomInset = systemBars.bottom
                recyclerView.updatePadding(bottom = bottomInset + dp(12))
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
        adapter.notifyAppearanceChanged()
    }

    fun render(state: InviteMembersScreenViewState, actions: InviteMembersScreenViewActions) {
        this.actions = actions
        adapter.actions = actions
        adapter.matrixMediaLoader = state.matrixMediaLoader
        adapter.palette = palette

        backButton.text = context.getString(
            if (state.canSkip) R.string.common_skip else R.string.common_cancel
        )
        backButton.isEnabled = !state.isSending
        backButton.alpha = if (state.isSending) DISABLED_ALPHA else 1f
        backButton.setOnClickListener(
            if (state.isSending) null else View.OnClickListener { actions.onBack() }
        )
        retryButton.setOnClickListener {
            if (
                state.searchErrorMessage != null &&
                state.preparationErrorMessage == null &&
                state.permissionErrorMessage == null
            ) {
                actions.onRetrySearch()
            } else {
                actions.onRetryPreparation()
            }
        }
        sendButton.text = when {
            state.isSending -> context.getString(R.string.invite_members_sending)
            state.selectedMembers.isEmpty() -> context.getString(R.string.invite_members_send)
            else -> context.getString(
                R.string.invite_members_send_count,
                state.selectedMembers.size
            )
        }
        sendButton.isEnabled = state.canSubmit
        sendButton.alpha = if (state.canSubmit || state.isSending) 1f else DISABLED_ALPHA
        sendButton.setOnClickListener(
            if (state.canSubmit) View.OnClickListener { actions.onSend() } else null
        )
        searchField.isEnabled = !state.isSending && !state.permissionDenied
        if (searchField.text.toString() != state.searchQuery) {
            isApplyingSearchState = true
            searchField.setText(state.searchQuery)
            searchField.setSelection(searchField.text.length)
            isApplyingSearchState = false
        }

        statusText.text = state.statusText(context)
        retryButton.visibility = if (
            state.preparationErrorMessage != null ||
            state.permissionErrorMessage != null ||
            state.searchErrorMessage != null
        ) VISIBLE else INVISIBLE
        adapter.submit(
            selected = state.selectedMembers,
            results = state.searchResults,
            interactionsEnabled = !state.isSending && state.canInviteMembers
        )
    }

    private fun InviteMembersScreenViewState.statusText(context: Context): String {
        return when {
            permissionDenied -> context.getString(R.string.invite_members_permission_denied)
            permissionErrorMessage != null -> {
                context.getString(R.string.invite_members_permission_error)
            }
            preparationErrorMessage != null -> {
                context.getString(R.string.invite_members_preparation_error)
            }
            isPreparing -> context.getString(R.string.invite_members_preparing)
            failedInviteCount > 0 -> resources.getQuantityString(
                R.plurals.invite_members_failed_count,
                failedInviteCount,
                failedInviteCount
            )
            sendErrorMessage != null -> context.getString(R.string.invite_members_send_error)
            searchQuery.isBlank() -> context.getString(R.string.invite_members_search_prompt)
            searchQuery.trim().length < INVITE_MEMBERS_MIN_SEARCH_LENGTH -> {
                context.getString(R.string.invite_members_min_characters)
            }
            isSearching -> context.getString(R.string.invite_members_searching)
            searchErrorMessage != null -> context.getString(R.string.invite_members_search_error)
            searchResults.isEmpty() && selectedMembers.isEmpty() -> {
                context.getString(R.string.invite_members_no_results)
            }
            else -> ""
        }
    }

    private fun applyPalette() {
        setBackgroundColor(palette.background)
        root.setBackgroundColor(palette.background)
        topBar.setBackgroundColor(palette.background)
        backButton.setTextColor(palette.actionText)
        titleText.setTextColor(palette.titleText)
        sendButton.setTextColor(palette.actionText)
        searchField.setTextColor(palette.primaryText)
        searchField.setHintTextColor(palette.secondaryText)
        searchField.background = roundedDrawable(palette.surface, dp(12))
        statusRow.setBackgroundColor(palette.background)
        statusText.setTextColor(palette.secondaryText)
        retryButton.setTextColor(palette.actionText)
        retryButton.background = roundedDrawable(palette.surface, dp(10))
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

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private companion object {
        const val TOP_BAR_HEIGHT_DP = 64
        const val TOP_BAR_SIDE_WIDTH_DP = 96
        const val STATUS_ROW_HEIGHT_DP = 44
        const val DISABLED_ALPHA = 0.42f
    }
}

private class InviteMembersAdapter(
    selectedTitle: String,
    resultsTitle: String
) {
    private val selectedHeader = InviteMemberHeaderAdapter(selectedTitle)
    private val selectedMembers = InviteMemberListAdapter(selectedSection = true)
    private val resultsHeader = InviteMemberHeaderAdapter(resultsTitle)
    private val results = InviteMemberListAdapter(selectedSection = false)
    private var submissionGeneration = 0L

    val recyclerAdapter = ConcatAdapter(
        selectedHeader,
        selectedMembers,
        resultsHeader,
        results
    )

    var actions: InviteMembersScreenViewActions? = null
        set(value) {
            field = value
            selectedMembers.actions = value
            results.actions = value
        }
    var matrixMediaLoader: MatrixMediaLoader? = null
        set(value) {
            field = value
            selectedMembers.matrixMediaLoader = value
            results.matrixMediaLoader = value
        }
    var palette: SettingsPalette? = null
        set(value) {
            field = value
            selectedHeader.palette = value
            selectedMembers.palette = value
            resultsHeader.palette = value
            results.palette = value
        }

    fun submit(
        selected: List<InviteMemberCandidate>,
        results: List<InviteMemberCandidate>,
        interactionsEnabled: Boolean
    ) {
        submissionGeneration += 1
        val generation = submissionGeneration
        var selectedCommitted = false
        var resultsCommitted = false

        fun updateHeadersAfterBothListsCommit() {
            if (
                generation == submissionGeneration &&
                selectedCommitted &&
                resultsCommitted
            ) {
                selectedHeader.setVisible(selected.isNotEmpty())
                resultsHeader.setVisible(results.isNotEmpty())
            }
        }

        selectedMembers.interactionsEnabled = interactionsEnabled
        this.results.interactionsEnabled = interactionsEnabled
        selectedMembers.submitList(selected) {
            selectedCommitted = true
            updateHeadersAfterBothListsCommit()
        }
        this.results.submitList(results) {
            resultsCommitted = true
            updateHeadersAfterBothListsCommit()
        }
    }

    fun notifyAppearanceChanged() {
        selectedHeader.notifyAppearanceChanged()
        selectedMembers.notifyDataSetChanged()
        resultsHeader.notifyAppearanceChanged()
        results.notifyDataSetChanged()
    }
}

private class InviteMemberHeaderAdapter(
    private val title: String
) : RecyclerView.Adapter<InviteMemberHeaderViewHolder>() {
    var palette: SettingsPalette? = null
    private var isVisible = false

    override fun getItemCount(): Int = if (isVisible) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InviteMemberHeaderViewHolder {
        return InviteMemberHeaderViewHolder(parent.context)
    }

    override fun onBindViewHolder(holder: InviteMemberHeaderViewHolder, position: Int) {
        holder.bind(title, palette ?: SettingsPalette.from(holder.itemView.context))
    }

    fun setVisible(visible: Boolean) {
        if (isVisible == visible) return
        isVisible = visible
        if (visible) notifyItemInserted(0) else notifyItemRemoved(0)
    }

    fun notifyAppearanceChanged() {
        if (isVisible) notifyItemChanged(0)
    }
}

private class InviteMemberListAdapter(
    private val selectedSection: Boolean
) : ListAdapter<InviteMemberCandidate, InviteMemberViewHolder>(InviteMemberDiffCallback) {
    var actions: InviteMembersScreenViewActions? = null
    var matrixMediaLoader: MatrixMediaLoader? = null
    var palette: SettingsPalette? = null
    var interactionsEnabled = true
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InviteMemberViewHolder {
        return InviteMemberViewHolder(parent.context)
    }

    override fun onBindViewHolder(holder: InviteMemberViewHolder, position: Int) {
        holder.bind(
            candidate = getItem(position),
            isSelected = selectedSection,
            interactionsEnabled = interactionsEnabled,
            matrixMediaLoader = matrixMediaLoader,
            palette = palette ?: SettingsPalette.from(holder.itemView.context),
            actions = actions
        )
    }
}

private class InviteMemberHeaderViewHolder(context: Context) : RecyclerView.ViewHolder(
    TextView(context).apply {
        layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = true
        updatePadding(
            left = dp(context, 16),
            right = dp(context, 16),
            top = dp(context, 14),
            bottom = dp(context, 6)
        )
    }
) {
    private val textView = itemView as TextView

    fun bind(title: String, palette: SettingsPalette) {
        textView.text = title.uppercase()
        textView.setTextColor(palette.secondaryText)
        textView.setBackgroundColor(palette.background)
    }
}

private class InviteMemberViewHolder(context: Context) : RecyclerView.ViewHolder(
    LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        updatePadding(
            left = dp(context, 16),
            right = dp(context, 16),
            top = dp(context, 9),
            bottom = dp(context, 9)
        )
        isClickable = true
        isFocusable = true
    }
) {
    private val density = context.resources.displayMetrics.density
    private val row = itemView as LinearLayout
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
        ellipsize = TextUtils.TruncateAt.MIDDLE
        includeFontPadding = true
    }
    private val accessory = TextView(context).apply {
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
        updatePadding(left = dp(9), right = dp(9), top = dp(5), bottom = dp(5))
    }

    init {
        row.addView(
            avatar,
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) }
        )
        textColumn.addView(nameText)
        textColumn.addView(userIdText)
        row.addView(
            textColumn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            accessory,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(8) }
        )
    }

    fun bind(
        candidate: InviteMemberCandidate,
        isSelected: Boolean,
        interactionsEnabled: Boolean,
        matrixMediaLoader: MatrixMediaLoader?,
        palette: SettingsPalette,
        actions: InviteMembersScreenViewActions?
    ) {
        val profile = candidate.profile
        row.setBackgroundColor(palette.background)
        avatar.setPaletteBackground(palette.background)
        avatar.render(
            userId = profile.userId,
            displayName = profile.effectiveDisplayName,
            avatarUrl = profile.avatarUrl,
            localAvatarPath = null,
            matrixMediaLoader = matrixMediaLoader,
            sizePx = dp(44)
        )
        nameText.text = profile.effectiveDisplayName
        userIdText.text = profile.userId
        nameText.setTextColor(palette.titleText)
        userIdText.setTextColor(palette.secondaryText)

        val membershipLabel = when (candidate.membership) {
            MatrixRoomMemberMembership.JOINED -> {
                itemView.context.getString(R.string.invite_members_already_member)
            }
            MatrixRoomMemberMembership.INVITED -> {
                itemView.context.getString(R.string.invite_members_already_invited)
            }
            null -> null
        }
        accessory.text = when {
            membershipLabel != null -> membershipLabel
            isSelected -> itemView.context.getString(R.string.invite_members_remove)
            else -> itemView.context.getString(R.string.invite_members_add)
        }
        accessory.setTextColor(palette.actionText)
        accessory.background = GradientDrawable().apply {
            setColor(palette.selectedFill)
            cornerRadius = dp(12).toFloat()
        }

        val canToggle = interactionsEnabled && membershipLabel == null
        row.isEnabled = canToggle
        row.alpha = if (membershipLabel == null) 1f else DISABLED_ALPHA
        row.setOnClickListener(
            if (canToggle) {
                View.OnClickListener { actions?.onToggleSelection(candidate) }
            } else {
                null
            }
        )
        itemView.contentDescription = buildString {
            append(profile.effectiveDisplayName)
            append(". ")
            append(profile.userId)
            append(". ")
            append(accessory.text)
        }
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private companion object {
        const val DISABLED_ALPHA = 0.52f
    }
}

private object InviteMemberDiffCallback : DiffUtil.ItemCallback<InviteMemberCandidate>() {
    override fun areItemsTheSame(
        oldItem: InviteMemberCandidate,
        newItem: InviteMemberCandidate
    ): Boolean = oldItem.profile.userId == newItem.profile.userId

    override fun areContentsTheSame(
        oldItem: InviteMemberCandidate,
        newItem: InviteMemberCandidate
    ): Boolean = oldItem == newItem
}

private fun dp(context: Context, value: Int): Int {
    return (value * context.resources.displayMetrics.density).roundToInt()
}
