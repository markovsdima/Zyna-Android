package com.zyna.app.ui.spaces

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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.zyna.app.R
import com.zyna.app.data.media.MatrixMediaLoader
import com.zyna.app.data.matrix.MatrixRoomSummary
import com.zyna.app.ui.avatar.MatrixAvatarShape
import com.zyna.app.ui.avatar.MatrixAvatarView
import com.zyna.app.ui.settings.SettingsPalette
import kotlin.math.roundToInt

internal data class SpaceAddRoomsScreenState(
    val addRooms: SpaceAddRoomsState,
    val isRoomListSynchronizing: Boolean,
    val isLoadingFullCoverage: Boolean,
    val hasRoomListError: Boolean,
    val matrixMediaLoader: MatrixMediaLoader?
)

internal data class SpaceAddRoomsScreenActions(
    val onBack: () -> Unit,
    val onRetryRoomList: () -> Unit,
    val onSearchQueryChanged: (String) -> Unit,
    val onToggleRoom: (String) -> Unit,
    val onSave: () -> Unit
)

internal class SpaceAddRoomsScreenView(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private var palette = SettingsPalette.from(context)
    private var statusTopInset = 0
    private var bottomInset = 0
    private var isApplyingSearchState = false
    private var actions: SpaceAddRoomsScreenActions? = null

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
        text = context.getString(R.string.space_add_rooms_title)
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val saveButton = TextView(context).apply {
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
        isClickable = true
        isFocusable = true
    }
    private val searchField = EditText(context).apply {
        setSingleLine(true)
        hint = context.getString(R.string.space_add_rooms_search_hint)
        textSize = 16f
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        inputType = android.text.InputType.TYPE_CLASS_TEXT
        updatePadding(left = dp(14), right = dp(14), top = dp(10), bottom = dp(10))
    }
    private val statusText = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        textSize = 14f
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        updatePadding(left = dp(20), right = dp(20))
    }
    private val recyclerView = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context)
        clipToPadding = false
        itemAnimator = null
    }
    private val adapter = SpaceAddRoomsAdapter()

    init {
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
            backButton,
            LinearLayout.LayoutParams(dp(TOP_BAR_SIDE_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT)
        )
        topBar.addView(
            titleText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        )
        topBar.addView(
            saveButton,
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
        root.addView(
            statusText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(STATUS_ROW_HEIGHT_DP)
            )
        )
        root.addView(
            recyclerView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        searchField.addTextChangedListener(
            object : TextWatcher {
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
            }
        )
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
                topBar.updatePadding(top = statusTopInset)
                topBar.layoutParams = (topBar.layoutParams as LinearLayout.LayoutParams).apply {
                    height = dp(TOP_BAR_HEIGHT_DP) + statusTopInset
                }
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

    fun render(state: SpaceAddRoomsScreenState, actions: SpaceAddRoomsScreenActions) {
        this.actions = actions
        val addRooms = state.addRooms
        val interactionsEnabled = addRooms.canManage && !addRooms.isSaving
        val canSave = addRooms.canSave && !state.isLoadingFullCoverage

        backButton.isEnabled = !addRooms.isSaving
        backButton.alpha = if (addRooms.isSaving) DISABLED_ALPHA else 1f
        backButton.setOnClickListener(
            if (addRooms.isSaving) null else View.OnClickListener { actions.onBack() }
        )
        saveButton.text = when {
            addRooms.isSaving -> context.getString(R.string.space_add_rooms_adding)
            addRooms.selectedRoomIds.isEmpty() ->
                context.getString(R.string.space_add_rooms_action)
            else -> resources.getQuantityString(
                R.plurals.space_add_rooms_action_count,
                addRooms.selectedRoomIds.size,
                addRooms.selectedRoomIds.size
            )
        }
        saveButton.isEnabled = canSave
        saveButton.alpha = if (canSave || addRooms.isSaving) 1f else DISABLED_ALPHA
        saveButton.setOnClickListener(
            if (canSave) View.OnClickListener { actions.onSave() } else null
        )
        searchField.isEnabled = interactionsEnabled
        if (searchField.text.toString() != addRooms.searchQuery) {
            isApplyingSearchState = true
            searchField.setText(addRooms.searchQuery)
            searchField.setSelection(searchField.text.length)
            isApplyingSearchState = false
        }
        statusText.text = when {
            addRooms.error == SpaceAddRoomsError.PERMISSION_CHANGED ->
                context.getString(R.string.space_add_rooms_permission_changed)
            addRooms.error == SpaceAddRoomsError.PARTIAL_ADD ->
                context.getString(R.string.space_add_rooms_partial_error)
            addRooms.error == SpaceAddRoomsError.ADD ->
                context.getString(R.string.space_add_rooms_error)
            addRooms.isSaving -> context.getString(R.string.space_add_rooms_adding)
            state.hasRoomListError -> context.getString(R.string.space_add_rooms_retry_update)
            state.isRoomListSynchronizing ->
                context.getString(R.string.space_add_rooms_syncing)
            addRooms.availableRooms.isEmpty() ->
                context.getString(R.string.space_add_rooms_empty)
            addRooms.visibleRooms.isEmpty() && addRooms.searchQuery.isNotBlank() ->
                context.getString(R.string.space_add_rooms_no_results)
            else -> context.getString(R.string.space_add_rooms_prompt)
        }
        statusText.setTextColor(
            if (state.hasRoomListError) palette.actionText else palette.secondaryText
        )
        statusText.setOnClickListener(
            if (state.hasRoomListError && !addRooms.isSaving) {
                View.OnClickListener { actions.onRetryRoomList() }
            } else {
                null
            }
        )

        adapter.actions = actions
        adapter.matrixMediaLoader = state.matrixMediaLoader
        adapter.palette = palette
        adapter.interactionsEnabled = interactionsEnabled
        adapter.submitList(
            addRooms.visibleRooms.map { room ->
                SpaceAddRoomsItem(room, room.id in addRooms.selectedRoomIds)
            }
        )
    }

    private fun applyPalette() {
        setBackgroundColor(palette.background)
        root.setBackgroundColor(palette.background)
        topBar.setBackgroundColor(palette.background)
        backButton.setTextColor(palette.actionText)
        titleText.setTextColor(palette.titleText)
        saveButton.setTextColor(palette.actionText)
        searchField.setTextColor(palette.primaryText)
        searchField.setHintTextColor(palette.secondaryText)
        searchField.background = roundedDrawable(palette.surface, dp(12))
        statusText.setTextColor(palette.secondaryText)
        recyclerView.setBackgroundColor(palette.background)
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
        const val STATUS_ROW_HEIGHT_DP = 52
        const val DISABLED_ALPHA = 0.42f
    }
}

private data class SpaceAddRoomsItem(
    val room: MatrixRoomSummary,
    val isSelected: Boolean
)

private class SpaceAddRoomsAdapter :
    ListAdapter<SpaceAddRoomsItem, SpaceAddRoomsViewHolder>(SpaceAddRoomsDiffCallback) {
    var actions: SpaceAddRoomsScreenActions? = null
    var matrixMediaLoader: MatrixMediaLoader? = null
    var palette: SettingsPalette? = null
    var interactionsEnabled = true
        set(value) {
            if (field == value) return
            field = value
            notifyVisibleItemsChanged()
        }

    fun notifyAppearanceChanged() {
        notifyVisibleItemsChanged()
    }

    private fun notifyVisibleItemsChanged() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SpaceAddRoomsViewHolder {
        return SpaceAddRoomsViewHolder(parent.context)
    }

    override fun onBindViewHolder(holder: SpaceAddRoomsViewHolder, position: Int) {
        holder.bind(
            item = getItem(position),
            interactionsEnabled = interactionsEnabled,
            matrixMediaLoader = matrixMediaLoader,
            palette = palette ?: SettingsPalette.from(holder.itemView.context),
            actions = actions
        )
    }
}

private class SpaceAddRoomsViewHolder(context: Context) : RecyclerView.ViewHolder(
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
    }
    private val previewText = TextView(context).apply {
        textSize = 13f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val checkText = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
    }

    init {
        row.addView(
            avatar,
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) }
        )
        textColumn.addView(nameText)
        textColumn.addView(previewText)
        row.addView(
            textColumn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            checkText,
            LinearLayout.LayoutParams(dp(28), dp(28)).apply { leftMargin = dp(10) }
        )
    }

    fun bind(
        item: SpaceAddRoomsItem,
        interactionsEnabled: Boolean,
        matrixMediaLoader: MatrixMediaLoader?,
        palette: SettingsPalette,
        actions: SpaceAddRoomsScreenActions?
    ) {
        val room = item.room
        row.setBackgroundColor(palette.background)
        avatar.setPaletteBackground(palette.background)
        avatar.render(
            userId = room.id,
            displayName = room.displayName,
            avatarUrl = room.avatarUrl,
            localAvatarPath = null,
            matrixMediaLoader = matrixMediaLoader,
            sizePx = dp(44),
            shape = MatrixAvatarShape.ROUNDED_RECT
        )
        nameText.text = room.displayName
        previewText.text = room.lastMessageText
            ?.takeIf(String::isNotBlank)
            ?: itemView.context.getString(R.string.space_preview_chat)
        nameText.setTextColor(palette.titleText)
        previewText.setTextColor(palette.secondaryText)
        checkText.text = if (item.isSelected) "✓" else ""
        checkText.setTextColor(palette.actionText)
        checkText.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (item.isSelected) palette.selectedFill else palette.background)
            setStroke(dp(2), if (item.isSelected) palette.actionText else palette.secondaryText)
        }
        row.isEnabled = interactionsEnabled
        row.alpha = if (interactionsEnabled) 1f else DISABLED_ALPHA
        row.setOnClickListener(
            if (interactionsEnabled) {
                View.OnClickListener { actions?.onToggleRoom(room.id) }
            } else {
                null
            }
        )
        val selection = itemView.context.getString(
            if (item.isSelected) {
                R.string.space_add_rooms_room_selected
            } else {
                R.string.space_add_rooms_room_not_selected
            }
        )
        itemView.contentDescription = "${room.displayName}. $selection"
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private companion object {
        const val DISABLED_ALPHA = 0.52f
    }
}

private object SpaceAddRoomsDiffCallback : DiffUtil.ItemCallback<SpaceAddRoomsItem>() {
    override fun areItemsTheSame(
        oldItem: SpaceAddRoomsItem,
        newItem: SpaceAddRoomsItem
    ): Boolean = oldItem.room.id == newItem.room.id

    override fun areContentsTheSame(
        oldItem: SpaceAddRoomsItem,
        newItem: SpaceAddRoomsItem
    ): Boolean = oldItem == newItem
}

private fun dp(context: Context, value: Int): Int {
    return (value * context.resources.displayMetrics.density).roundToInt()
}
