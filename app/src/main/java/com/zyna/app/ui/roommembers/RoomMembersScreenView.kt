package com.zyna.app.ui.roommembers

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
import com.zyna.app.data.matrix.MatrixRoomMember
import com.zyna.app.data.matrix.MatrixRoomMemberMembership
import com.zyna.app.data.matrix.MatrixRoomMemberRole
import com.zyna.app.ui.avatar.MatrixAvatarView
import com.zyna.app.ui.settings.SettingsPalette
import kotlin.math.roundToInt

internal data class RoomMembersScreenViewState(
    val searchQuery: String,
    val invitedMembers: List<MatrixRoomMember>,
    val joinedMembers: List<MatrixRoomMember>,
    val isLoading: Boolean,
    val errorMessage: String?,
    val matrixMediaLoader: MatrixMediaLoader?
)

internal data class RoomMembersScreenViewActions(
    val onBack: () -> Unit,
    val onRetry: () -> Unit,
    val onSearchQueryChanged: (String) -> Unit,
    val onOpenProfile: (MatrixRoomMember) -> Unit
)

internal class RoomMembersScreenView(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private var palette = SettingsPalette.from(context)
    private var statusTopInset = 0
    private var bottomInset = 0
    private var isApplyingSearchState = false
    private var actions: RoomMembersScreenViewActions? = null

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val topBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val backButton = TextView(context).apply {
        text = context.getString(R.string.room_members_back)
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
        isClickable = true
        isFocusable = true
    }
    private val titleText = TextView(context).apply {
        text = context.getString(R.string.room_members_title)
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = true
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val topBarSpacer = View(context)
    private val searchField = EditText(context).apply {
        setSingleLine(true)
        hint = context.getString(R.string.room_members_search_hint)
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
        text = context.getString(R.string.room_members_retry)
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
    private val adapter = RoomMembersAdapter(
        invitedTitle = context.getString(R.string.room_members_invited_section),
        joinedTitle = context.getString(R.string.room_members_joined_section)
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
            topBarSpacer,
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
                updateListPadding()
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

    fun render(state: RoomMembersScreenViewState, actions: RoomMembersScreenViewActions) {
        this.actions = actions
        adapter.actions = actions
        adapter.matrixMediaLoader = state.matrixMediaLoader
        adapter.palette = palette

        backButton.setOnClickListener { actions.onBack() }
        retryButton.setOnClickListener { actions.onRetry() }

        if (searchField.text.toString() != state.searchQuery) {
            isApplyingSearchState = true
            searchField.setText(state.searchQuery)
            searchField.setSelection(searchField.text.length)
            isApplyingSearchState = false
        }

        val visibleCount = state.invitedMembers.size + state.joinedMembers.size
        statusText.text = when {
            state.errorMessage != null -> state.errorMessage
            state.isLoading -> context.getString(R.string.room_members_loading)
            visibleCount == 0 && state.searchQuery.isNotBlank() -> {
                context.getString(R.string.room_members_no_results)
            }
            visibleCount == 0 -> context.getString(R.string.room_members_empty)
            else -> ""
        }
        retryButton.visibility = if (state.errorMessage != null) VISIBLE else INVISIBLE

        adapter.submitMembers(
            invited = state.invitedMembers,
            joined = state.joinedMembers
        )
    }

    private fun applyPalette() {
        setBackgroundColor(palette.background)
        root.setBackgroundColor(palette.background)
        topBar.setBackgroundColor(palette.background)
        backButton.setTextColor(palette.actionText)
        titleText.setTextColor(palette.titleText)
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

    private fun updateListPadding() {
        recyclerView.updatePadding(bottom = bottomInset + dp(12))
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
        const val TOP_BAR_SIDE_WIDTH_DP = 88
        const val STATUS_ROW_HEIGHT_DP = 44
    }
}

private class RoomMembersAdapter(
    invitedTitle: String,
    joinedTitle: String
) {
    private val invitedHeader = RoomMemberHeaderAdapter(invitedTitle)
    private val invitedMembers = RoomMemberListAdapter()
    private val joinedHeader = RoomMemberHeaderAdapter(joinedTitle)
    private val joinedMembers = RoomMemberListAdapter()
    private var submissionGeneration = 0L

    val recyclerAdapter = ConcatAdapter(
        invitedHeader,
        invitedMembers,
        joinedHeader,
        joinedMembers
    )

    var actions: RoomMembersScreenViewActions? = null
        set(value) {
            field = value
            invitedMembers.actions = value
            joinedMembers.actions = value
        }

    var matrixMediaLoader: MatrixMediaLoader? = null
        set(value) {
            field = value
            invitedMembers.matrixMediaLoader = value
            joinedMembers.matrixMediaLoader = value
        }

    var palette: SettingsPalette? = null
        set(value) {
            field = value
            invitedHeader.palette = value
            invitedMembers.palette = value
            joinedHeader.palette = value
            joinedMembers.palette = value
        }

    fun submitMembers(
        invited: List<MatrixRoomMember>,
        joined: List<MatrixRoomMember>
    ) {
        submissionGeneration += 1
        val generation = submissionGeneration
        var invitedCommitted = false
        var joinedCommitted = false

        fun updateHeadersAfterBothListsCommit() {
            if (
                generation == submissionGeneration &&
                invitedCommitted &&
                joinedCommitted
            ) {
                invitedHeader.setVisible(invited.isNotEmpty())
                joinedHeader.setVisible(invited.isNotEmpty() && joined.isNotEmpty())
            }
        }

        invitedMembers.submitList(invited) {
            invitedCommitted = true
            updateHeadersAfterBothListsCommit()
        }
        joinedMembers.submitList(joined) {
            joinedCommitted = true
            updateHeadersAfterBothListsCommit()
        }
    }

    fun notifyAppearanceChanged() {
        invitedHeader.notifyAppearanceChanged()
        invitedMembers.notifyDataSetChanged()
        joinedHeader.notifyAppearanceChanged()
        joinedMembers.notifyDataSetChanged()
    }
}

private class RoomMemberHeaderAdapter(
    private val title: String
) : RecyclerView.Adapter<RoomMemberHeaderViewHolder>() {
    var palette: SettingsPalette? = null
    private var isVisible = false

    override fun getItemCount(): Int = if (isVisible) 1 else 0

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): RoomMemberHeaderViewHolder {
        return RoomMemberHeaderViewHolder(parent.context)
    }

    override fun onBindViewHolder(holder: RoomMemberHeaderViewHolder, position: Int) {
        holder.bind(
            title = title,
            palette = palette ?: SettingsPalette.from(holder.itemView.context)
        )
    }

    fun setVisible(visible: Boolean) {
        if (isVisible == visible) {
            return
        }
        isVisible = visible
        if (visible) {
            notifyItemInserted(0)
        } else {
            notifyItemRemoved(0)
        }
    }

    fun notifyAppearanceChanged() {
        if (isVisible) {
            notifyItemChanged(0)
        }
    }
}

private class RoomMemberListAdapter :
    ListAdapter<MatrixRoomMember, RoomMemberViewHolder>(RoomMemberDiffCallback) {
    var actions: RoomMembersScreenViewActions? = null
    var matrixMediaLoader: MatrixMediaLoader? = null
    var palette: SettingsPalette? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RoomMemberViewHolder {
        return RoomMemberViewHolder(parent.context)
    }

    override fun onBindViewHolder(holder: RoomMemberViewHolder, position: Int) {
        holder.bind(
            member = getItem(position),
            matrixMediaLoader = matrixMediaLoader,
            palette = palette ?: SettingsPalette.from(holder.itemView.context),
            actions = actions
        )
    }
}

private class RoomMemberHeaderViewHolder(context: Context) : RecyclerView.ViewHolder(
    TextView(context).apply {
        layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = true
        isFocusable = true
        updatePadding(left = dp(context, 16), right = dp(context, 16), top = dp(context, 14), bottom = dp(context, 6))
    }
) {
    private val textView = itemView as TextView

    fun bind(title: String, palette: SettingsPalette) {
        textView.text = title.uppercase()
        textView.setTextColor(palette.secondaryText)
        textView.setBackgroundColor(palette.background)
        textView.contentDescription = title
    }
}

private class RoomMemberViewHolder(context: Context) : RecyclerView.ViewHolder(
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
        updatePadding(left = dp(16), right = dp(16), top = dp(9), bottom = dp(9))
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
        ellipsize = TextUtils.TruncateAt.MIDDLE
        includeFontPadding = true
    }
    private val roleText = TextView(context).apply {
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
        maxLines = 1
        updatePadding(left = dp(9), right = dp(9), top = dp(5), bottom = dp(5))
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
            roleText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(8)
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
        member: MatrixRoomMember,
        matrixMediaLoader: MatrixMediaLoader?,
        palette: SettingsPalette,
        actions: RoomMembersScreenViewActions?
    ) {
        root.setBackgroundColor(palette.background)
        row.setBackgroundColor(palette.background)
        avatar.setPaletteBackground(palette.background)
        avatar.render(
            userId = member.userId,
            displayName = member.displayNameOrUserId,
            avatarUrl = member.avatarUrl,
            localAvatarPath = null,
            matrixMediaLoader = matrixMediaLoader,
            sizePx = dp(44)
        )
        nameText.text = member.displayNameOrUserId
        userIdText.text = member.userId
        nameText.setTextColor(palette.titleText)
        userIdText.setTextColor(palette.secondaryText)
        separator.setBackgroundColor(palette.separator)

        val roleLabel = member.roleLabel(itemView.context)
        roleText.text = roleLabel.orEmpty()
        roleText.visibility = if (roleLabel == null) View.GONE else View.VISIBLE
        roleText.setTextColor(palette.actionText)
        roleText.background = roundedDrawable(palette.selectedFill, dp(12))

        row.setOnClickListener { actions?.onOpenProfile(member) }
        itemView.contentDescription = buildString {
            append(member.displayNameOrUserId)
            append(". ")
            append(member.userId)
            roleLabel?.let {
                append(". ")
                append(it)
            }
        }
    }

    private fun MatrixRoomMember.roleLabel(context: Context): String? {
        if (membership == MatrixRoomMemberMembership.INVITED) {
            return context.getString(R.string.room_member_invited)
        }
        return when (role) {
            MatrixRoomMemberRole.OWNER -> context.getString(R.string.room_member_role_owner)
            MatrixRoomMemberRole.ADMIN -> context.getString(R.string.room_member_role_admin)
            MatrixRoomMemberRole.MODERATOR -> {
                context.getString(R.string.room_member_role_moderator)
            }
            MatrixRoomMemberRole.MEMBER -> null
        }
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

private object RoomMemberDiffCallback : DiffUtil.ItemCallback<MatrixRoomMember>() {
    override fun areItemsTheSame(
        oldItem: MatrixRoomMember,
        newItem: MatrixRoomMember
    ): Boolean {
        return oldItem.userId == newItem.userId && oldItem.membership == newItem.membership
    }

    override fun areContentsTheSame(
        oldItem: MatrixRoomMember,
        newItem: MatrixRoomMember
    ): Boolean {
        return oldItem == newItem
    }
}

private fun dp(context: Context, value: Int): Int {
    return (value * context.resources.displayMetrics.density).roundToInt()
}
