package com.zyna.app.ui.spaces

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.zyna.app.R
import com.zyna.app.data.media.MatrixMediaLoader
import com.zyna.app.data.matrix.MatrixSpaceMembership
import com.zyna.app.data.matrix.MatrixSpaceRoom
import com.zyna.app.data.matrix.MatrixSpaceRoomKind
import com.zyna.app.ui.avatar.MatrixAvatarView
import com.zyna.app.ui.avatar.MatrixAvatarShape
import kotlin.math.roundToInt

enum class SpacePresentationKind {
    STORYLINE,
    TRACK
}

data class SpaceScreenViewState(
    val spaceId: String,
    val presentationKind: SpacePresentationKind,
    val space: MatrixSpaceRoom,
    val tracks: List<MatrixSpaceRoom>,
    val chats: List<MatrixSpaceRoom>,
    val isKnown: Boolean,
    val isPaginating: Boolean,
    val endReached: Boolean,
    val error: SpaceLoadError?,
    val matrixMediaLoader: MatrixMediaLoader?
)

data class SpaceScreenViewActions(
    val onBack: () -> Unit,
    val onOpenDetails: () -> Unit,
    val onOpenRoom: (MatrixSpaceRoom) -> Unit,
    val onLoadMore: () -> Unit,
    val onRetry: () -> Unit
)

class SpaceScreenView(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private var palette = SpacePalette.from(context)
    private var statusTopInset = 0
    private var bottomInset = 0
    private var latestActions: SpaceScreenViewActions? = null
    private var latestPaginationStatus: SpacePaginationStatus? = null
    private var renderedTracks: List<MatrixSpaceRoom>? = null
    private var renderedChats: List<MatrixSpaceRoom>? = null
    private val autoPaginationGate = SpaceAutoPaginationGate()

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val topBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        updatePadding(left = dp(8), right = dp(8))
    }
    private val backButton = TextView(context).apply {
        gravity = Gravity.CENTER
        text = context.getString(R.string.common_back)
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        isClickable = true
        isFocusable = true
    }
    private val topTitle = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val detailsButton = TextView(context).apply {
        gravity = Gravity.CENTER
        text = "•••"
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.space_details)
    }
    private val header = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        updatePadding(left = dp(20), right = dp(20), top = dp(16), bottom = dp(16))
    }
    private val avatarView = MatrixAvatarView(context)
    private val nameText = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 24f
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val kindText = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 14f
        minHeight = dp(22)
    }
    private val topicText = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 15f
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
    }
    private val contentFrame = FrameLayout(context)
    private val recyclerView = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context)
        clipToPadding = false
        setHasFixedSize(false)
        itemAnimator = null
    }
    private val emptyText = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 15f
        updatePadding(left = dp(32), right = dp(32), bottom = dp(48))
    }
    private val retryButton = TextView(context).apply {
        gravity = Gravity.CENTER
        text = context.getString(R.string.common_retry)
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        isClickable = true
        isFocusable = true
        updatePadding(left = dp(18), right = dp(18), top = dp(10), bottom = dp(10))
    }
    private val inlineRetryButton = TextView(context).apply {
        gravity = Gravity.CENTER
        text = context.getString(R.string.common_retry)
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        isClickable = true
        isFocusable = true
        updatePadding(left = dp(12), right = dp(12), top = dp(8), bottom = dp(8))
    }
    private val cachedErrorBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        updatePadding(left = dp(20), right = dp(8), top = dp(5), bottom = dp(5))
        addView(
            TextView(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                text = context.getString(R.string.space_update_error)
                textSize = 14f
                tag = CACHED_ERROR_TEXT_TAG
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        addView(
            inlineRetryButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }
    private val loadingSkeleton = SpaceLoadingSkeletonView(context).apply {
        visibility = View.GONE
    }
    private val errorContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        visibility = View.GONE
        addView(
            TextView(context).apply {
                gravity = Gravity.CENTER
                text = context.getString(R.string.space_load_error)
                textSize = 15f
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        addView(
            retryButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )
    }
    private val adapter = SpaceItemsAdapter()

    init {
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
            LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.MATCH_PARENT)
        )
        topBar.addView(
            topTitle,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        )
        topBar.addView(
            detailsButton,
            LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.MATCH_PARENT)
        )
        root.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        header.addView(
            avatarView,
            LinearLayout.LayoutParams(dp(88), dp(88))
        )
        header.addView(
            nameText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        )
        header.addView(
            kindText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        header.addView(
            topicText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )
        root.addView(
            cachedErrorBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            contentFrame,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        recyclerView.adapter = adapter
        contentFrame.addView(
            recyclerView,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        contentFrame.addView(
            loadingSkeleton,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        contentFrame.addView(
            emptyText,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        contentFrame.addView(
            errorContainer,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        recyclerView.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy < 0) return
                    if (latestPaginationStatus?.canLoadMore != true) return
                    val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    if (manager.findLastVisibleItemPosition() >= adapter.itemCount - 4) {
                        latestActions?.onLoadMore?.invoke()
                    }
                }
            }
        )
        applyPalette()
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            if (statusTopInset != systemBars.top) {
                statusTopInset = systemBars.top
                topBar.updatePadding(
                    left = dp(8),
                    top = statusTopInset,
                    right = dp(8)
                )
                topBar.layoutParams = (topBar.layoutParams as LinearLayout.LayoutParams).apply {
                    height = dp(TOP_BAR_HEIGHT_DP) + statusTopInset
                }
            }
            if (bottomInset != systemBars.bottom) {
                bottomInset = systemBars.bottom
                recyclerView.updatePadding(bottom = bottomInset + dp(20))
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
        palette = SpacePalette.from(context)
        applyPalette()
    }

    fun render(state: SpaceScreenViewState, actions: SpaceScreenViewActions) {
        latestActions = actions
        latestPaginationStatus = state.paginationStatus()
        topTitle.text = when (state.presentationKind) {
            SpacePresentationKind.STORYLINE -> context.getString(R.string.space_storyline)
            SpacePresentationKind.TRACK -> context.getString(R.string.space_track)
        }
        nameText.text = state.space.displayName
        kindText.text = topTitle.text
        topicText.text = state.space.topic.orEmpty()
        topicText.visibility = if (state.space.topic.isNullOrBlank()) View.GONE else View.VISIBLE
        avatarView.render(
            userId = state.space.roomId,
            displayName = state.space.displayName,
            avatarUrl = state.space.avatarUrl,
            localAvatarPath = null,
            matrixMediaLoader = state.matrixMediaLoader,
            sizePx = dp(88),
            shape = MatrixAvatarShape.ROUNDED_RECT
        )

        backButton.setOnClickListener { actions.onBack() }
        detailsButton.setOnClickListener { actions.onOpenDetails() }
        retryButton.setOnClickListener { actions.onRetry() }
        inlineRetryButton.setOnClickListener { actions.onRetry() }
        adapter.matrixMediaLoader = state.matrixMediaLoader
        adapter.onRoomClicked = actions.onOpenRoom

        if (renderedTracks !== state.tracks || renderedChats !== state.chats) {
            renderedTracks = state.tracks
            renderedChats = state.chats
            adapter.submitList(
                buildList {
                    if (state.tracks.isNotEmpty()) {
                        add(SpaceListItem.Section(context.getString(R.string.space_tracks)))
                        state.tracks.forEach { add(SpaceListItem.Room(it)) }
                    }
                    if (state.chats.isNotEmpty()) {
                        add(SpaceListItem.Section(context.getString(R.string.space_chats)))
                        state.chats.forEach { add(SpaceListItem.Room(it)) }
                    }
                }
            )
        }
        val hasItems = state.tracks.isNotEmpty() || state.chats.isNotEmpty()

        val showError = !state.isKnown && state.error != null
        val showCachedError = state.isKnown && state.error != null
        val showLoading = !state.isKnown && state.error == null
        val showEmpty = state.isKnown && !hasItems
        errorContainer.visibility = if (showError) View.VISIBLE else View.GONE
        cachedErrorBar.visibility = if (showCachedError) View.VISIBLE else View.GONE
        loadingSkeleton.visibility = if (showLoading) View.VISIBLE else View.GONE
        emptyText.visibility = if (showEmpty) View.VISIBLE else View.GONE
        emptyText.text = context.getString(R.string.space_empty)
        recyclerView.visibility = if (hasItems) View.VISIBLE else View.INVISIBLE

        // If the first page fits without scrolling, continue paginating automatically.
        if (autoPaginationGate.shouldSchedule(state.paginationStatus())) {
            recyclerView.post {
                val status = latestPaginationStatus ?: return@post
                if (
                    autoPaginationGate.shouldRequestAfterLayout(
                        status = status,
                        canScrollForward = recyclerView.canScrollVertically(1)
                    )
                ) {
                    latestActions?.onLoadMore?.invoke()
                }
            }
        }
    }

    private fun applyPalette() {
        setBackgroundColor(palette.background)
        root.setBackgroundColor(palette.background)
        topBar.setBackgroundColor(palette.background)
        header.setBackgroundColor(palette.background)
        contentFrame.setBackgroundColor(palette.background)
        recyclerView.setBackgroundColor(palette.background)
        backButton.setTextColor(palette.accent)
        detailsButton.setTextColor(palette.accent)
        retryButton.setTextColor(palette.accent)
        inlineRetryButton.setTextColor(palette.accent)
        cachedErrorBar.setBackgroundColor(palette.secondaryBackground)
        cachedErrorBar.findViewWithTag<TextView>(CACHED_ERROR_TEXT_TAG)
            ?.setTextColor(palette.secondaryText)
        loadingSkeleton.setPlaceholderColor(palette.tertiaryText)
        topTitle.setTextColor(palette.primaryText)
        nameText.setTextColor(palette.primaryText)
        kindText.setTextColor(palette.secondaryText)
        topicText.setTextColor(palette.secondaryText)
        emptyText.setTextColor(palette.secondaryText)
        avatarView.setPaletteBackground(palette.background)
        adapter.palette = palette
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private companion object {
        const val TOP_BAR_HEIGHT_DP = 56
        const val CACHED_ERROR_TEXT_TAG = "cached-space-error"
    }
}

private class SpaceLoadingSkeletonView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setPlaceholderColor(color: Int) {
        paint.color = color
        paint.alpha = 92
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = dp(20f)
        val avatarSize = dp(48f)
        val textLeft = left + avatarSize + dp(12f)
        val longTextRight = width - dp(42f)
        val shortTextRight = width - dp(116f)
        repeat(4) { index ->
            val top = dp(14f) + index * dp(68f)
            canvas.drawRoundRect(
                left,
                top,
                left + avatarSize,
                top + avatarSize,
                dp(13f),
                dp(13f),
                paint
            )
            canvas.drawRoundRect(
                textLeft,
                top + dp(8f),
                longTextRight,
                top + dp(20f),
                dp(6f),
                dp(6f),
                paint
            )
            canvas.drawRoundRect(
                textLeft,
                top + dp(29f),
                shortTextRight,
                top + dp(39f),
                dp(5f),
                dp(5f),
                paint
            )
        }
    }

    private fun dp(value: Float): Float = value * density
}

private fun SpaceScreenViewState.paginationStatus(): SpacePaginationStatus {
    return SpacePaginationStatus(
        spaceId = spaceId,
        loadedRoomCount = tracks.size + chats.size,
        isKnown = isKnown,
        isPaginating = isPaginating,
        endReached = endReached
    )
}

private sealed interface SpaceListItem {
    data class Section(val title: String) : SpaceListItem
    data class Room(val room: MatrixSpaceRoom) : SpaceListItem
}

private class SpaceItemsAdapter : ListAdapter<SpaceListItem, RecyclerView.ViewHolder>(
    SpaceItemDiff
) {
    var palette = SpacePalette.fromContextFallback()
        set(value) {
            if (field == value) return
            field = value
            if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
        }
    var matrixMediaLoader: MatrixMediaLoader? = null
    var onRoomClicked: (MatrixSpaceRoom) -> Unit = {}

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return when (val item = getItem(position)) {
            is SpaceListItem.Section -> "section:${item.title}".stableHash()
            is SpaceListItem.Room -> item.room.roomId.stableHash()
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is SpaceListItem.Section -> VIEW_TYPE_SECTION
            is SpaceListItem.Room -> VIEW_TYPE_ROOM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_SECTION -> SectionHolder(parent)
            else -> RoomHolder(parent)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is SpaceListItem.Section -> (holder as SectionHolder).bind(item.title, palette)
            is SpaceListItem.Room -> (holder as RoomHolder).bind(
                room = item.room,
                palette = palette,
                matrixMediaLoader = matrixMediaLoader,
                onClick = onRoomClicked
            )
        }
    }

    private companion object {
        const val VIEW_TYPE_SECTION = 1
        const val VIEW_TYPE_ROOM = 2
    }
}

private class SectionHolder(parent: ViewGroup) : RecyclerView.ViewHolder(
    TextView(parent.context).apply {
        val density = resources.displayMetrics.density
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(
            (20 * density).roundToInt(),
            (20 * density).roundToInt(),
            (20 * density).roundToInt(),
            (7 * density).roundToInt()
        )
        layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
) {
    fun bind(title: String, palette: SpacePalette) {
        (itemView as TextView).apply {
            text = title.uppercase()
            setTextColor(palette.secondaryText)
            setBackgroundColor(palette.background)
        }
    }
}

private class RoomHolder(parent: ViewGroup) : RecyclerView.ViewHolder(
    SpaceRoomRowView(parent.context).apply {
        layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
) {
    fun bind(
        room: MatrixSpaceRoom,
        palette: SpacePalette,
        matrixMediaLoader: MatrixMediaLoader?,
        onClick: (MatrixSpaceRoom) -> Unit
    ) {
        val row = itemView as SpaceRoomRowView
        row.bind(room, palette, matrixMediaLoader)
        row.isFocusable = true
        row.setOnClickListener { onClick(room) }
    }
}

private class SpaceRoomRowView(context: Context) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val avatar = MatrixAvatarView(context)
    private val labels = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val title = TextView(context).apply {
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val subtitle = TextView(context).apply {
        textSize = 13f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val accessory = TextView(context).apply {
        text = "›"
        textSize = 28f
        gravity = Gravity.CENTER
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(68)
        updatePadding(left = dp(20), right = dp(14), top = dp(8), bottom = dp(8))
        foreground = ContextCompat.getDrawable(context, android.R.drawable.list_selector_background)
        addView(avatar, LayoutParams(dp(48), dp(48)))
        addView(
            labels,
            LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                leftMargin = dp(12)
            }
        )
        labels.addView(
            title,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        labels.addView(
            subtitle,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        addView(accessory, LayoutParams(dp(28), ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun bind(
        room: MatrixSpaceRoom,
        palette: SpacePalette,
        matrixMediaLoader: MatrixMediaLoader?
    ) {
        setBackgroundColor(palette.background)
        title.text = room.displayName
        title.setTextColor(palette.primaryText)
        subtitle.setTextColor(palette.secondaryText)
        accessory.setTextColor(palette.tertiaryText)
        val baseSubtitle = if (room.kind == MatrixSpaceRoomKind.SPACE) {
            resources.getQuantityString(
                R.plurals.space_items_count,
                room.childrenCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                room.childrenCount
            )
        } else {
            resources.getQuantityString(
                R.plurals.space_members_count,
                room.joinedMemberCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                room.joinedMemberCount
            )
        }
        subtitle.text = when (room.membership) {
            MatrixSpaceMembership.INVITED ->
                context.getString(R.string.space_invited_format, baseSubtitle)
            MatrixSpaceMembership.KNOCKED ->
                context.getString(R.string.space_requested_format, baseSubtitle)
            MatrixSpaceMembership.JOINED -> baseSubtitle
            else -> context.getString(R.string.space_not_joined_format, baseSubtitle)
        }
        alpha = if (room.membership == MatrixSpaceMembership.JOINED) 1f else 0.72f
        accessory.visibility = View.VISIBLE
        avatar.setPaletteBackground(palette.background)
        avatar.render(
            userId = room.roomId,
            displayName = room.displayName,
            avatarUrl = room.avatarUrl,
            localAvatarPath = null,
            matrixMediaLoader = matrixMediaLoader,
            sizePx = dp(48),
            shape = if (room.kind == MatrixSpaceRoomKind.SPACE) {
                MatrixAvatarShape.ROUNDED_RECT
            } else {
                MatrixAvatarShape.CIRCLE
            }
        )
        contentDescription = "${room.displayName}, ${subtitle.text}"
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()
}

private object SpaceItemDiff : DiffUtil.ItemCallback<SpaceListItem>() {
    override fun areItemsTheSame(oldItem: SpaceListItem, newItem: SpaceListItem): Boolean {
        return when {
            oldItem is SpaceListItem.Section && newItem is SpaceListItem.Section ->
                oldItem.title == newItem.title
            oldItem is SpaceListItem.Room && newItem is SpaceListItem.Room ->
                oldItem.room.roomId == newItem.room.roomId
            else -> false
        }
    }

    override fun areContentsTheSame(oldItem: SpaceListItem, newItem: SpaceListItem): Boolean {
        return oldItem == newItem
    }
}

data class SpacePalette(
    val background: Int,
    val secondaryBackground: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val tertiaryText: Int,
    val accent: Int,
    val error: Int
) {
    companion object {
        fun from(context: Context): SpacePalette {
            val dark = (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            return if (dark) {
                SpacePalette(
                    background = Color.rgb(0x00, 0x00, 0x00),
                    secondaryBackground = Color.rgb(0x1C, 0x1C, 0x1E),
                    primaryText = Color.WHITE,
                    secondaryText = Color.rgb(0x98, 0x98, 0x9F),
                    tertiaryText = Color.rgb(0x63, 0x63, 0x68),
                    accent = Color.rgb(0x0A, 0x84, 0xFF),
                    error = Color.rgb(0xFF, 0x45, 0x3A)
                )
            } else {
                SpacePalette(
                    background = Color.WHITE,
                    secondaryBackground = Color.rgb(0xF2, 0xF2, 0xF7),
                    primaryText = Color.rgb(0x1C, 0x1C, 0x1E),
                    secondaryText = Color.rgb(0x63, 0x63, 0x68),
                    tertiaryText = Color.rgb(0xC7, 0xC7, 0xCC),
                    accent = Color.rgb(0x00, 0x7A, 0xFF),
                    error = Color.rgb(0xD7, 0x00, 0x15)
                )
            }
        }

        fun fromContextFallback(): SpacePalette {
            return SpacePalette(
                background = Color.WHITE,
                secondaryBackground = Color.LTGRAY,
                primaryText = Color.BLACK,
                secondaryText = Color.DKGRAY,
                tertiaryText = Color.LTGRAY,
                accent = Color.BLUE,
                error = Color.RED
            )
        }
    }
}

private fun String.stableHash(): Long {
    var hash = 5381L
    forEach { character -> hash = ((hash shl 5) + hash) xor character.code.toLong() }
    return hash
}
