package com.zyna.app.ui.rooms

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
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
import com.zyna.app.data.matrix.MatrixLastOwnMessageStatus
import com.zyna.app.data.matrix.MatrixRoomSummary
import com.zyna.app.data.presence.UserPresenceStatus
import com.zyna.app.ui.presence.PresenceText
import com.zyna.app.ui.time.AndroidTimeTextFormatter
import com.zyna.app.ui.time.TimeTextFormatter
import com.zyna.app.util.ZynaPerfLog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt

data class RoomsScreenViewState(
    val rooms: List<MatrixRoomSummary>,
    val isSynchronizing: Boolean,
    val hasSynchronizationError: Boolean,
    val title: String,
    val showBack: Boolean,
    val showCreateRoom: Boolean,
    val matrixMediaLoader: MatrixMediaLoader?,
    val presenceByUserId: Map<String, UserPresenceStatus>,
    val initialScrollAnchor: RoomsScrollAnchor?,
    val bottomContentPaddingPx: Int
)

data class RoomsScrollAnchor(
    val roomId: String,
    val offsetPx: Int
)

data class RoomsScreenViewActions(
    val onOpenRoom: (MatrixRoomSummary) -> Unit,
    val onCreateRoom: (() -> Unit)?,
    val onBack: (() -> Unit)?,
    val onRetrySynchronization: () -> Unit,
    val onVisibleRoomsChanged: (List<String>) -> Unit,
    val onVisibleRoomsInactive: () -> Unit
)

class RoomsScreenView(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private var isDarkTheme = resources.configuration.isNightMode()
    private var palette = RoomsPalette.from(isDarkTheme)
    private var statusTopInset = 0

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(palette.background)
    }
    private val topBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(palette.background)
        updatePadding(left = dp(8), right = dp(8))
    }
    private val backButton = TextView(context).apply {
        gravity = Gravity.CENTER
        text = "Back"
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        isClickable = true
        isFocusable = true
    }
    private val titleText = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        updatePadding(left = dp(12), right = dp(8))
    }
    private val createRoomButton = TextView(context).apply {
        gravity = Gravity.CENTER
        text = context.getString(R.string.rooms_create_group)
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        isClickable = true
        isFocusable = true
    }
    private val retrySynchronizationButton = TextView(context).apply {
        gravity = Gravity.CENTER
        text = context.getString(R.string.common_retry)
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        isClickable = true
        isFocusable = true
        updatePadding(left = dp(12), right = dp(12), top = dp(8), bottom = dp(8))
    }
    private val synchronizationErrorBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        updatePadding(left = dp(20), right = dp(8), top = dp(5), bottom = dp(5))
        addView(
            TextView(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                text = context.getString(R.string.rooms_update_error)
                textSize = 14f
                tag = SYNCHRONIZATION_ERROR_TEXT_TAG
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        addView(
            retrySynchronizationButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }
    private val contentFrame = FrameLayout(context)
    private val recyclerView = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context)
        clipToPadding = false
        setHasFixedSize(true)
        itemAnimator = null
    }
    private val emptyView = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 16f
        includeFontPadding = true
    }
    private val adapter = RoomsAdapter(palette)
    private var consumedInitialScrollAnchor: RoomsScrollAnchor? = null
    private var latestActions: RoomsScreenViewActions? = null
    private var lastReportedVisibleRoomIds: List<String>? = null
    private var lastReportedVisibleRange: Pair<Int, Int>? = null

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
                dp(64),
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
            createRoomButton,
            LinearLayout.LayoutParams(
                dp(72),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            synchronizationErrorBar,
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
        recyclerView.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    dispatchVisibleRooms()
                }
            }
        )
        contentFrame.addView(
            recyclerView,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        contentFrame.addView(
            emptyView,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        applyPalette()
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val nextTopInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            if (statusTopInset != nextTopInset) {
                statusTopInset = nextTopInset
                topBar.updatePadding(top = statusTopInset)
                val params = topBar.layoutParams as LinearLayout.LayoutParams
                params.height = dp(TOP_BAR_HEIGHT_DP) + statusTopInset
                topBar.layoutParams = params
            }
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
        adapter.restartAvatarLoads()
    }

    override fun onDetachedFromWindow() {
        latestActions?.onVisibleRoomsInactive?.invoke()
        lastReportedVisibleRoomIds = null
        lastReportedVisibleRange = null
        adapter.cancelAvatarLoads()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView !== this) return
        if (visibility == View.VISIBLE) {
            post { dispatchVisibleRooms(force = true) }
        } else {
            latestActions?.onVisibleRoomsInactive?.invoke()
            // The store has released this screen's viewport ownership. Forget the local
            // delivery snapshot as well, so the same visible rows reclaim it on return.
            lastReportedVisibleRoomIds = null
            lastReportedVisibleRange = null
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        updateThemeIfNeeded(force = true)
    }

    fun render(state: RoomsScreenViewState, actions: RoomsScreenViewActions) {
        latestActions = actions
        updateThemeIfNeeded(force = false)
        val renderStart = ZynaPerfLog.start()
        val pendingInitialScrollAnchor = state.initialScrollAnchor
            ?.takeUnless { it == consumedInitialScrollAnchor }
        val listMutationScrollAnchor = pendingInitialScrollAnchor
            ?: captureScrollAnchorForListMutation()
        val shouldKeepListAtTop = pendingInitialScrollAnchor == null &&
            state.rooms.isNotEmpty() &&
            recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE &&
            isListAtTop()
        titleText.text = state.title
        backButton.visibility = if (state.showBack) View.VISIBLE else View.GONE
        backButton.setOnClickListener { actions.onBack?.invoke() }
        createRoomButton.visibility = if (state.showCreateRoom) View.VISIBLE else View.GONE
        createRoomButton.setOnClickListener { actions.onCreateRoom?.invoke() }
        retrySynchronizationButton.setOnClickListener { actions.onRetrySynchronization() }
        synchronizationErrorBar.visibility = if (state.hasSynchronizationError) {
            View.VISIBLE
        } else {
            View.GONE
        }
        recyclerView.setPadding(
            recyclerView.paddingLeft,
            recyclerView.paddingTop,
            recyclerView.paddingRight,
            state.bottomContentPaddingPx
        )
        emptyView.text = when {
            state.isSynchronizing -> context.getString(R.string.rooms_loading)
            state.hasSynchronizationError -> ""
            else -> context.getString(R.string.rooms_empty)
        }
        emptyView.visibility = if (state.rooms.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (state.rooms.isEmpty()) View.GONE else View.VISIBLE

        val roomCount = state.rooms.size
        adapter.onRoomClicked = { room ->
            ZynaPerfLog.mark {
                "rooms.tap roomId=${room.id} name=${room.displayName} rooms=$roomCount"
            }
            actions.onOpenRoom(room)
        }
        adapter.setMatrixMediaLoader(state.matrixMediaLoader)
        adapter.setPresenceStatuses(state.presenceByUserId)
        adapter.submitList(state.rooms) {
            var didRestoreScrollAnchor = false
            if (listMutationScrollAnchor != null) {
                didRestoreScrollAnchor = restoreScrollAnchor(listMutationScrollAnchor, state.rooms)
                if (pendingInitialScrollAnchor != null) {
                    val userStartedScrolling =
                        recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE
                    if (didRestoreScrollAnchor || userStartedScrolling) {
                        consumedInitialScrollAnchor = pendingInitialScrollAnchor
                    }
                }
            }
            val shouldFallbackInvalidInitialAnchor =
                pendingInitialScrollAnchor != null && !didRestoreScrollAnchor
            val canAdjustScrollAfterCommit =
                recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE
            if (
                canAdjustScrollAfterCommit &&
                (shouldKeepListAtTop || shouldFallbackInvalidInitialAnchor)
            ) {
                (recyclerView.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(0, recyclerView.paddingTop)
                if (pendingInitialScrollAnchor != null) {
                    consumedInitialScrollAnchor = pendingInitialScrollAnchor
                }
            }
            recyclerView.post { dispatchVisibleRooms(force = true) }
        }
        ZynaPerfLog.end(renderStart, "roomsView.render") {
            "title=${state.title} rooms=${state.rooms.size} " +
                "synchronizing=${state.isSynchronizing}"
        }
    }

    private fun dispatchVisibleRooms(force: Boolean = false) {
        val actions = latestActions ?: return
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        val visibleRange = firstVisible to lastVisible
        if (!force && visibleRange == lastReportedVisibleRange) return
        lastReportedVisibleRange = visibleRange
        val roomIds = if (
            firstVisible == RecyclerView.NO_POSITION ||
            lastVisible == RecyclerView.NO_POSITION
        ) {
            emptyList()
        } else {
            val fromIndex = (firstVisible - VISIBLE_PREFETCH_BEFORE).coerceAtLeast(0)
            val toIndex = (lastVisible + VISIBLE_PREFETCH_AFTER)
                .coerceAtMost(adapter.currentList.lastIndex)
            if (fromIndex > toIndex) {
                emptyList()
            } else {
                (fromIndex..toIndex).mapNotNull { index -> adapter.currentList.getOrNull(index)?.id }
            }
        }
        if (lastReportedVisibleRoomIds != roomIds) {
            lastReportedVisibleRoomIds = roomIds
            actions.onVisibleRoomsChanged(roomIds)
        }
    }

    fun captureScrollAnchor(): RoomsScrollAnchor? {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return null
        val position = layoutManager.findFirstVisibleItemPosition()
        if (position == RecyclerView.NO_POSITION) {
            return null
        }
        val room = adapter.currentList.getOrNull(position) ?: return null
        val child = layoutManager.findViewByPosition(position) ?: return null
        return RoomsScrollAnchor(
            roomId = room.id,
            offsetPx = child.top - recyclerView.paddingTop
        )
    }

    private fun updateThemeIfNeeded(force: Boolean) {
        val nextDarkTheme = resources.configuration.isNightMode()
        if (!force && nextDarkTheme == isDarkTheme) {
            return
        }
        isDarkTheme = nextDarkTheme
        palette = RoomsPalette.from(nextDarkTheme)
        applyPalette()
    }

    private fun applyPalette() {
        setBackgroundColor(palette.background)
        root.setBackgroundColor(palette.background)
        topBar.setBackgroundColor(palette.background)
        backButton.setTextColor(palette.actionText)
        createRoomButton.setTextColor(palette.actionText)
        retrySynchronizationButton.setTextColor(palette.actionText)
        synchronizationErrorBar.setBackgroundColor(palette.secondaryBackground)
        synchronizationErrorBar.findViewWithTag<TextView>(SYNCHRONIZATION_ERROR_TEXT_TAG)
            ?.setTextColor(palette.secondaryText)
        titleText.setTextColor(palette.titleText)
        emptyView.setTextColor(palette.secondaryText)
        adapter.setPalette(palette)
    }

    private fun captureScrollAnchorForListMutation(): RoomsScrollAnchor? {
        if (recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
            return null
        }
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return null
        val position = layoutManager.findFirstVisibleItemPosition()
        if (position == RecyclerView.NO_POSITION) {
            return null
        }
        val child = layoutManager.findViewByPosition(position) ?: return null
        val isScrolledFromTop = position > 0 || child.top < recyclerView.paddingTop
        if (!isScrolledFromTop) {
            return null
        }
        return captureScrollAnchor()
    }

    private fun isListAtTop(): Boolean {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return true
        val position = layoutManager.findFirstVisibleItemPosition()
        if (position == RecyclerView.NO_POSITION) return adapter.currentList.isEmpty()
        if (position != 0) return false
        val child = layoutManager.findViewByPosition(position) ?: return false
        return child.top >= recyclerView.paddingTop
    }

    private fun restoreScrollAnchor(anchor: RoomsScrollAnchor, rooms: List<MatrixRoomSummary>): Boolean {
        if (recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
            return false
        }
        val index = rooms.indexOfFirst { room -> room.id == anchor.roomId }
        if (index < 0) {
            return false
        }
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return false
        layoutManager.scrollToPositionWithOffset(
            index,
            recyclerView.paddingTop + anchor.offsetPx
        )
        return true
    }

    private fun dp(value: Int): Int {
        return (value * density).roundToInt()
    }

    private companion object {
        const val SYNCHRONIZATION_ERROR_TEXT_TAG = "rooms-sync-error-text"
        const val VISIBLE_PREFETCH_BEFORE = 4
        const val VISIBLE_PREFETCH_AFTER = 20
        const val TOP_BAR_HEIGHT_DP = 64
    }
}

private class RoomsAdapter(
    private var palette: RoomsPalette
) : ListAdapter<MatrixRoomSummary, RoomViewHolder>(RoomDiffCallback) {
    var onRoomClicked: (MatrixRoomSummary) -> Unit = {}
    private var matrixMediaLoader: MatrixMediaLoader? = null
    private var presenceByUserId: Map<String, UserPresenceStatus> = emptyMap()
    private val boundHolders = mutableSetOf<RoomViewHolder>()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).id.djb2StableHash()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RoomViewHolder {
        return RoomViewHolder(parent)
    }

    override fun onBindViewHolder(holder: RoomViewHolder, position: Int) {
        boundHolders += holder
        holder.bind(
            room = getItem(position),
            palette = palette,
            matrixMediaLoader = matrixMediaLoader,
            presence = getItem(position).directUserPresence(),
            onClick = onRoomClicked
        )
    }

    override fun onBindViewHolder(
        holder: RoomViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        val payload = payloads.roomRowPayloadOrNull()
        if (payload == null) {
            onBindViewHolder(holder, position)
            return
        }

        boundHolders += holder
        holder.update(
            room = getItem(position),
            palette = palette,
            matrixMediaLoader = matrixMediaLoader,
            payload = payload,
            presence = getItem(position).directUserPresence(),
            onClick = onRoomClicked
        )
    }

    override fun onViewRecycled(holder: RoomViewHolder) {
        boundHolders -= holder
        holder.recycle()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        cancelAvatarLoads()
        boundHolders.clear()
    }

    fun cancelAvatarLoads() {
        boundHolders.forEach { holder ->
            holder.cancelAvatarLoad()
        }
    }

    fun restartAvatarLoads() {
        boundHolders.forEach { holder ->
            holder.restartAvatarLoad()
        }
    }

    fun setMatrixMediaLoader(nextLoader: MatrixMediaLoader?) {
        if (matrixMediaLoader === nextLoader) {
            return
        }
        matrixMediaLoader = nextLoader
        if (itemCount > 0) {
            notifyItemRangeChanged(
                0,
                itemCount,
                RoomRowPayload(reloadAvatar = true)
            )
        }
    }

    fun setPresenceStatuses(nextStatuses: Map<String, UserPresenceStatus>) {
        if (presenceByUserId == nextStatuses) {
            return
        }
        presenceByUserId = nextStatuses
        boundHolders.forEach { holder ->
            holder.updatePresence(holder.currentRoomDirectUserId()?.let(nextStatuses::get))
        }
    }

    fun setPalette(nextPalette: RoomsPalette) {
        if (palette == nextPalette) {
            return
        }
        palette = nextPalette
        if (itemCount > 0) {
            notifyItemRangeChanged(
                0,
                itemCount,
                RoomRowPayload(reloadAvatar = false)
            )
        }
    }

    private fun MatrixRoomSummary.directUserPresence(): UserPresenceStatus? {
        return directUserId?.takeIf { it.isNotBlank() }?.let(presenceByUserId::get)
    }
}

private class RoomViewHolder(parent: ViewGroup) : RecyclerView.ViewHolder(
    RoomRowView(parent.context).apply {
        layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
) {
    private val rowView = itemView as RoomRowView

    fun bind(
        room: MatrixRoomSummary,
        palette: RoomsPalette,
        matrixMediaLoader: MatrixMediaLoader?,
        presence: UserPresenceStatus?,
        onClick: (MatrixRoomSummary) -> Unit
    ) {
        rowView.bind(room, palette, matrixMediaLoader, presence)
        rowView.setOnClickListener { onClick(room) }
    }

    fun update(
        room: MatrixRoomSummary,
        palette: RoomsPalette,
        matrixMediaLoader: MatrixMediaLoader?,
        payload: RoomRowPayload,
        presence: UserPresenceStatus?,
        onClick: (MatrixRoomSummary) -> Unit
    ) {
        rowView.update(room, palette, matrixMediaLoader, payload.reloadAvatar, presence)
        rowView.setOnClickListener { onClick(room) }
    }

    fun updatePresence(presence: UserPresenceStatus?) {
        rowView.updatePresence(presence)
    }

    fun currentRoomDirectUserId(): String? {
        return rowView.currentRoomDirectUserId()
    }

    fun recycle() {
        rowView.recycle()
    }

    fun cancelAvatarLoad() {
        rowView.cancelAvatarLoad()
    }

    fun restartAvatarLoad() {
        rowView.restartAvatarLoad()
    }
}

private class RoomRowView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val timeTextFormatter = AndroidTimeTextFormatter(context)
    private val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val onlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val onlineBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = sp(16f)
    }
    private val previewPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(14f)
    }
    private val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(12f)
    }
    private val badgeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(11.5f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val avatarTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(17f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val badgeRect = RectF()
    private val avatarShaderMatrix = Matrix()
    private var room: MatrixRoomSummary? = null
    private var presence: UserPresenceStatus? = null
    private var palette = RoomsPalette.from(context.resources.configuration.isNightMode())
    private var avatarFillColor = palette.avatarColors.first()
    private var avatarBitmap: Bitmap? = null
    private var avatarBitmapShader: BitmapShader? = null
    private var avatarBitmapShaderSource: Bitmap? = null
    private var avatarLoadHandle: AutoCloseable? = null
    private var avatarLoadUrl: String? = null
    private var avatarLoadLoader: MatrixMediaLoader? = null

    init {
        isClickable = true
        isFocusable = true
        setWillNotDraw(false)
        applySelectableForeground()
    }

    fun bind(
        room: MatrixRoomSummary,
        palette: RoomsPalette,
        matrixMediaLoader: MatrixMediaLoader?,
        presence: UserPresenceStatus?
    ) {
        this.room = room
        this.palette = palette
        this.presence = presence
        avatarFillColor = room.avatarColor(palette)
        setBackgroundColor(palette.background)
        contentDescription = room.accessibilityText(context, presence)
        bindAvatar(
            avatarUrl = room.avatarUrl?.takeIf { it.isNotBlank() },
            matrixMediaLoader = matrixMediaLoader
        )
        invalidate()
    }

    fun update(
        room: MatrixRoomSummary,
        palette: RoomsPalette,
        matrixMediaLoader: MatrixMediaLoader?,
        reloadAvatar: Boolean,
        presence: UserPresenceStatus?
    ) {
        this.room = room
        this.palette = palette
        this.presence = presence
        avatarFillColor = room.avatarColor(palette)
        setBackgroundColor(palette.background)
        contentDescription = room.accessibilityText(context, presence)
        if (reloadAvatar) {
            bindAvatar(
                avatarUrl = room.avatarUrl?.takeIf { it.isNotBlank() },
                matrixMediaLoader = matrixMediaLoader
            )
        }
        invalidate()
    }

    fun updatePresence(nextPresence: UserPresenceStatus?) {
        val wasOnline = PresenceText.isOnline(presence)
        val isOnline = PresenceText.isOnline(nextPresence)
        presence = nextPresence
        room?.let { contentDescription = it.accessibilityText(context, nextPresence) }
        if (wasOnline != isOnline) {
            invalidate()
        }
    }

    fun currentRoomDirectUserId(): String? {
        return room?.directUserId?.takeIf { it.isNotBlank() }
    }

    fun recycle() {
        cancelAvatarLoad()
        avatarLoadUrl = null
        avatarLoadLoader = null
        avatarBitmap = null
        avatarBitmapShader = null
        avatarBitmapShaderSource = null
    }

    fun cancelAvatarLoad() {
        avatarLoadHandle?.close()
        avatarLoadHandle = null
    }

    fun restartAvatarLoad() {
        val currentRoom = room ?: return
        bindAvatar(
            avatarUrl = currentRoom.avatarUrl?.takeIf { it.isNotBlank() },
            matrixMediaLoader = avatarLoadLoader
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val titleHeight = titlePaint.fontHeight()
        val previewHeight = previewPaint.fontHeight()
        val desiredHeight = max(
            dp(ROW_HEIGHT_DP),
            (titleHeight + previewHeight + dp(28)).roundToInt()
        )
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val room = room ?: return
        val widthPx = width
        if (widthPx <= 0) {
            return
        }

        titlePaint.color = palette.titleText
        previewPaint.color = palette.secondaryText
        metaPaint.color = palette.secondaryText
        badgeTextPaint.color = palette.unreadText
        avatarTextPaint.color = palette.avatarText
        dividerPaint.color = palette.divider

        val left = dp(20).toFloat()
        val right = widthPx - dp(20).toFloat()
        val avatarSize = dp(AVATAR_SIZE_DP).toFloat()
        val avatarCenterX = left + avatarSize / 2f
        val avatarCenterY = height / 2f
        if (!drawAvatarBitmap(canvas, avatarCenterX, avatarCenterY, avatarSize, room.isSpace)) {
            avatarPaint.color = avatarFillColor
            if (room.isSpace) {
                canvas.drawRoundRect(
                    avatarRect(avatarCenterX, avatarCenterY, avatarSize),
                    avatarSize * SPACE_AVATAR_CORNER_RATIO,
                    avatarSize * SPACE_AVATAR_CORNER_RATIO,
                    avatarPaint
                )
            } else {
                canvas.drawCircle(avatarCenterX, avatarCenterY, avatarSize / 2f, avatarPaint)
            }
            canvas.drawText(
                room.avatarInitial(),
                avatarCenterX,
                centerBaseline(avatarCenterY, avatarTextPaint),
                avatarTextPaint
            )
        }
        drawOnlineIndicator(canvas, avatarCenterX, avatarCenterY, avatarSize)

        val textLeft = left + avatarSize + dp(12)
        val timeText = room.lastMessageAtMillis
            ?.takeUnless { room.isSpace }
            ?.formatRoomTimestamp(timeTextFormatter)
            .orEmpty()
        val statusText = room.lastOwnMessageStatus
            ?.takeUnless { room.isSpace }
            ?.label()
            .orEmpty()
        val badgeText = room.unreadBadgeText().takeUnless { room.isSpace }
        val rawTrailingWidth = max(
            max(metaPaint.measureText(timeText), metaPaint.measureText(statusText)),
            badgeText?.let { badgeTextPaint.measureText(it) + dp(14) }
                ?: room.unreadDotWidth().takeUnless { room.isSpace }
                ?: 0f
        )
        val horizontalGap = dp(12).toFloat()
        val maxTrailingWidth = (right - textLeft - dp(48) - horizontalGap).coerceAtLeast(0f)
        val trailingWidth = rawTrailingWidth.coerceAtMost(maxTrailingWidth)
        val textRight = if (trailingWidth > 0f) {
            right - trailingWidth - horizontalGap
        } else {
            right
        }
        val titleMetrics = titlePaint.fontMetrics
        val previewMetrics = previewPaint.fontMetrics
        val titleHeight = titlePaint.fontHeight()
        val previewHeight = previewPaint.fontHeight()
        val textGap = dp(4).toFloat()
        val textTop = (height - titleHeight - previewHeight - textGap) / 2f
        val titleBaseline = textTop - titleMetrics.ascent
        val previewBaseline = textTop + titleHeight + textGap - previewMetrics.ascent
        canvas.drawText(
            room.displayName.ellipsize(titlePaint, textRight - textLeft),
            textLeft,
            titleBaseline,
            titlePaint
        )
        canvas.drawText(
            room.previewText(context).ellipsize(previewPaint, textRight - textLeft),
            textLeft,
            previewBaseline,
            previewPaint
        )

        if (timeText.isNotEmpty() && trailingWidth > 0f) {
            metaPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(timeText.ellipsize(metaPaint, trailingWidth), right, titleBaseline, metaPaint)
        }
        if (statusText.isNotEmpty() && trailingWidth > 0f) {
            metaPaint.textAlign = Paint.Align.RIGHT
            metaPaint.color = if (room.lastOwnMessageStatus == MatrixLastOwnMessageStatus.FAILED) {
                palette.errorText
            } else {
                palette.secondaryText
            }
            canvas.drawText(statusText.ellipsize(metaPaint, trailingWidth), right, previewBaseline, metaPaint)
        }
        if (!room.isSpace) {
            drawUnreadIndicator(canvas, room, right, trailingWidth)
        }
        canvas.drawLine(textLeft, height - 0.5f, widthPx.toFloat(), height - 0.5f, dividerPaint)
    }

    private fun bindAvatar(
        avatarUrl: String?,
        matrixMediaLoader: MatrixMediaLoader?
    ) {
        if (avatarUrl == null || matrixMediaLoader == null) {
            avatarLoadHandle?.close()
            avatarLoadHandle = null
            avatarLoadUrl = avatarUrl
            avatarLoadLoader = matrixMediaLoader
            clearAvatarBitmap()
            return
        }

        if (
            avatarLoadUrl == avatarUrl &&
            avatarLoadLoader === matrixMediaLoader &&
            avatarBitmap?.isRecycled == false
        ) {
            return
        }
        if (
            avatarLoadUrl == avatarUrl &&
            avatarLoadLoader === matrixMediaLoader &&
            avatarLoadHandle != null
        ) {
            return
        }

        avatarLoadHandle?.close()
        avatarLoadUrl = avatarUrl
        avatarLoadLoader = matrixMediaLoader
        clearAvatarBitmap()

        val avatarSizePx = dp(AVATAR_SIZE_DP)
        matrixMediaLoader.cachedAvatar(avatarUrl, avatarSizePx)?.let { cached ->
            setAvatarBitmap(
                avatarUrl = avatarUrl,
                matrixMediaLoader = matrixMediaLoader,
                bitmap = cached
            )
            return
        }

        avatarLoadHandle = matrixMediaLoader.loadAvatar(avatarUrl, avatarSizePx) { bitmap ->
            if (avatarLoadUrl == avatarUrl && avatarLoadLoader === matrixMediaLoader) {
                avatarLoadHandle = null
                setAvatarBitmap(
                    avatarUrl = avatarUrl,
                    matrixMediaLoader = matrixMediaLoader,
                    bitmap = bitmap
                )
            }
        }
    }

    private fun setAvatarBitmap(
        avatarUrl: String,
        matrixMediaLoader: MatrixMediaLoader,
        bitmap: Bitmap?
    ) {
        if (avatarLoadUrl != avatarUrl || avatarLoadLoader !== matrixMediaLoader) {
            return
        }
        avatarBitmap = bitmap?.takeIf { !it.isRecycled }
        avatarBitmapShader = null
        avatarBitmapShaderSource = null
        invalidate()
    }

    private fun clearAvatarBitmap() {
        if (avatarBitmap == null && avatarBitmapShader == null && avatarBitmapShaderSource == null) {
            return
        }
        avatarBitmap = null
        avatarBitmapShader = null
        avatarBitmapShaderSource = null
        invalidate()
    }

    private fun drawAvatarBitmap(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        size: Float,
        isSpace: Boolean
    ): Boolean {
        val bitmap = avatarBitmap?.takeIf { !it.isRecycled } ?: return false
        val shader = avatarShaderFor(bitmap)
        val scale = max(
            size / bitmap.width.coerceAtLeast(1).toFloat(),
            size / bitmap.height.coerceAtLeast(1).toFloat()
        )
        avatarShaderMatrix.reset()
        avatarShaderMatrix.setScale(scale, scale)
        avatarShaderMatrix.postTranslate(
            centerX - bitmap.width * scale / 2f,
            centerY - bitmap.height * scale / 2f
        )
        shader.setLocalMatrix(avatarShaderMatrix)
        avatarPaint.shader = shader
        if (isSpace) {
            canvas.drawRoundRect(
                avatarRect(centerX, centerY, size),
                size * SPACE_AVATAR_CORNER_RATIO,
                size * SPACE_AVATAR_CORNER_RATIO,
                avatarPaint
            )
        } else {
            canvas.drawCircle(centerX, centerY, size / 2f, avatarPaint)
        }
        avatarPaint.shader = null
        return true
    }

    private fun avatarRect(centerX: Float, centerY: Float, size: Float): RectF {
        return RectF(
            centerX - size / 2f,
            centerY - size / 2f,
            centerX + size / 2f,
            centerY + size / 2f
        )
    }

    private fun avatarShaderFor(bitmap: Bitmap): BitmapShader {
        if (avatarBitmapShaderSource !== bitmap || avatarBitmapShader == null) {
            avatarBitmapShader = BitmapShader(
                bitmap,
                Shader.TileMode.CLAMP,
                Shader.TileMode.CLAMP
            )
            avatarBitmapShaderSource = bitmap
        }
        return requireNotNull(avatarBitmapShader)
    }

    private fun drawUnreadIndicator(
        canvas: Canvas,
        room: MatrixRoomSummary,
        right: Float,
        availableWidth: Float
    ) {
        if (!room.showsUnreadIndicator() || availableWidth <= 0f) {
            return
        }
        val color = if (room.unreadMentionCount > 0) {
            palette.mentionFill
        } else {
            palette.unreadFill
        }
        badgePaint.color = color
        val badgeText = room.unreadBadgeText()
        val badgeCenterY = height - dp(19).toFloat()
        if (badgeText == null) {
            val radius = dp(5).toFloat()
            if (availableWidth < radius * 2f) {
                return
            }
            canvas.drawCircle(right - radius, badgeCenterY, radius, badgePaint)
            return
        }

        val badgeHeight = dp(20).toFloat()
        if (availableWidth < badgeHeight) {
            return
        }
        val badgeWidth = max(dp(20).toFloat(), badgeTextPaint.measureText(badgeText) + dp(12))
            .coerceAtMost(availableWidth)
        val visibleBadgeText = badgeText.ellipsize(
            badgeTextPaint,
            (badgeWidth - dp(8)).coerceAtLeast(0f)
        )
        badgeRect.set(
            right - badgeWidth,
            badgeCenterY - badgeHeight / 2f,
            right,
            badgeCenterY + badgeHeight / 2f
        )
        canvas.drawRoundRect(badgeRect, badgeHeight / 2f, badgeHeight / 2f, badgePaint)
        canvas.drawText(
            visibleBadgeText,
            badgeRect.centerX(),
            centerBaseline(badgeRect.centerY(), badgeTextPaint),
            badgeTextPaint
        )
    }

    private fun drawOnlineIndicator(
        canvas: Canvas,
        avatarCenterX: Float,
        avatarCenterY: Float,
        avatarSize: Float
    ) {
        if (!PresenceText.isOnline(presence)) {
            return
        }
        onlineBorderPaint.color = palette.onlineBorder
        onlinePaint.color = palette.onlineFill
        val dotRadius = dp(5).toFloat()
        val borderRadius = dotRadius + dp(2).toFloat()
        val centerX = avatarCenterX + avatarSize / 2f - dotRadius
        val centerY = avatarCenterY + avatarSize / 2f - dotRadius
        canvas.drawCircle(centerX, centerY, borderRadius, onlineBorderPaint)
        canvas.drawCircle(centerX, centerY, dotRadius, onlinePaint)
    }

    private fun applySelectableForeground() {
        val outValue = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)) {
            foreground = ContextCompat.getDrawable(context, outValue.resourceId)
        }
    }

    private fun MatrixRoomSummary.unreadDotWidth(): Float {
        return if (showsUnreadIndicator()) dp(10).toFloat() else 0f
    }

    private fun dp(value: Int): Int {
        return (value * density).roundToInt()
    }

    private fun sp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            value,
            resources.displayMetrics
        )
    }
}

private fun Paint.fontHeight(): Float {
    return fontMetrics.let { it.descent - it.ascent }
}

private object RoomDiffCallback : DiffUtil.ItemCallback<MatrixRoomSummary>() {
    override fun areItemsTheSame(oldItem: MatrixRoomSummary, newItem: MatrixRoomSummary): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: MatrixRoomSummary, newItem: MatrixRoomSummary): Boolean {
        return oldItem == newItem
    }

    override fun getChangePayload(oldItem: MatrixRoomSummary, newItem: MatrixRoomSummary): Any? {
        if (oldItem == newItem) {
            return null
        }
        return RoomRowPayload(
            reloadAvatar = oldItem.avatarUrl != newItem.avatarUrl
        )
    }
}

private data class RoomRowPayload(
    val reloadAvatar: Boolean
)

private fun List<Any>.roomRowPayloadOrNull(): RoomRowPayload? {
    var hasPayload = false
    var reloadAvatar = false
    forEach { payload ->
        val roomPayload = payload as? RoomRowPayload ?: return null
        hasPayload = true
        reloadAvatar = reloadAvatar || roomPayload.reloadAvatar
    }
    return if (hasPayload) {
        RoomRowPayload(reloadAvatar = reloadAvatar)
    } else {
        null
    }
}

private data class RoomsPalette(
    val background: Int,
    val secondaryBackground: Int,
    val titleText: Int,
    val secondaryText: Int,
    val actionText: Int,
    val divider: Int,
    val avatarColors: List<Int>,
    val avatarText: Int,
    val unreadFill: Int,
    val mentionFill: Int,
    val unreadText: Int,
    val errorText: Int,
    val onlineFill: Int,
    val onlineBorder: Int
) {
    companion object {
        fun from(isDarkTheme: Boolean): RoomsPalette {
            return if (isDarkTheme) {
                RoomsPalette(
                    background = Color.rgb(18, 18, 22),
                    secondaryBackground = Color.rgb(30, 29, 34),
                    titleText = Color.rgb(232, 225, 229),
                    secondaryText = Color.rgb(202, 196, 208),
                    actionText = Color.rgb(208, 188, 255),
                    divider = Color.argb(42, 255, 255, 255),
                    avatarColors = DARK_IOS_SYSTEM_AVATAR_COLORS,
                    avatarText = Color.WHITE,
                    unreadFill = Color.rgb(208, 188, 255),
                    mentionFill = Color.rgb(255, 180, 171),
                    unreadText = Color.rgb(33, 0, 93),
                    errorText = Color.rgb(255, 180, 171),
                    onlineFill = Color.rgb(52, 199, 89),
                    onlineBorder = Color.rgb(18, 18, 22)
                )
            } else {
                RoomsPalette(
                    background = Color.WHITE,
                    secondaryBackground = Color.rgb(247, 242, 248),
                    titleText = Color.rgb(29, 27, 32),
                    secondaryText = Color.rgb(73, 69, 79),
                    actionText = Color.rgb(33, 0, 93),
                    divider = Color.argb(28, 0, 0, 0),
                    avatarColors = LIGHT_IOS_SYSTEM_AVATAR_COLORS,
                    avatarText = Color.WHITE,
                    unreadFill = Color.rgb(103, 80, 164),
                    mentionFill = Color.rgb(186, 26, 26),
                    unreadText = Color.WHITE,
                    errorText = Color.rgb(186, 26, 26),
                    onlineFill = Color.rgb(52, 199, 89),
                    onlineBorder = Color.WHITE
                )
            }
        }
    }
}

private fun MatrixRoomSummary.previewText(context: Context): String {
    if (isSpace) return context.getString(R.string.space_storyline)
    return lastMessageText?.takeIf { it.isNotBlank() } ?: "No messages"
}

private fun MatrixRoomSummary.showsUnreadIndicator(): Boolean {
    return unreadCount > 0 || isMarkedUnread
}

private fun MatrixRoomSummary.unreadBadgeText(): String? {
    if (unreadCount <= 0) return null
    return if (unreadCount > 99) "99+" else unreadCount.toString()
}

private fun MatrixRoomSummary.avatarInitial(): String {
    val source = displayName.trim().firstOrNull()?.uppercaseChar() ?: '#'
    return source.toString()
}

private fun MatrixRoomSummary.avatarColor(palette: RoomsPalette): Int {
    val colors = palette.avatarColors
    return colors[stableAvatarId().djb2HashIndex(colors.size)]
}

private fun MatrixRoomSummary.stableAvatarId(): String {
    return directUserId?.takeIf { it.isNotBlank() } ?: id
}

private fun MatrixRoomSummary.accessibilityText(
    context: Context,
    presence: UserPresenceStatus?
): String {
    val unread = unreadBadgeText()
        ?.takeUnless { isSpace }
        ?.let { ", $it unread" }
        .orEmpty()
    val online = if (PresenceText.isOnline(presence)) ", online" else ""
    return "$displayName, ${previewText(context)}$unread$online"
}

private fun MatrixLastOwnMessageStatus.label(): String {
    return when (this) {
        MatrixLastOwnMessageStatus.PENDING -> "Sending"
        MatrixLastOwnMessageStatus.SENT -> "Sent"
        MatrixLastOwnMessageStatus.READ -> "Read"
        MatrixLastOwnMessageStatus.FAILED -> "Failed"
    }
}

private fun Long.formatRoomTimestamp(timeTextFormatter: TimeTextFormatter): String {
    val zone = ZoneId.systemDefault()
    val dateTime = Instant.ofEpochMilli(this).atZone(zone)
    val today = LocalDate.now(zone)
    return when (dateTime.toLocalDate()) {
        today -> timeTextFormatter.format(this)
        today.minusDays(1) -> "Yesterday"
        else -> ROOM_DATE_FORMATTER.format(dateTime)
    }
}

private fun CharSequence.ellipsize(paint: TextPaint, availableWidth: Float): String {
    if (availableWidth <= 0f) {
        return ""
    }
    return TextUtils.ellipsize(this, paint, availableWidth, TextUtils.TruncateAt.END).toString()
}

private fun centerBaseline(centerY: Float, paint: Paint): Float {
    val metrics = paint.fontMetrics
    return centerY - (metrics.ascent + metrics.descent) / 2f
}

private fun Configuration.isNightMode(): Boolean {
    return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}

private fun String.djb2HashIndex(size: Int): Int {
    if (size <= 0) {
        return 0
    }
    return (djb2StableHash() % size).toInt()
}

private fun String.djb2StableHash(): Long {
    var hash = DJB2_OFFSET
    encodeToByteArray().forEach { byte ->
        hash = hash * DJB2_MULTIPLIER + (byte.toLong() and 0xffL)
    }
    return java.lang.Long.remainderUnsigned(hash, Long.MAX_VALUE)
}

private val ROOM_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")
private val LIGHT_IOS_SYSTEM_AVATAR_COLORS = listOf(
    Color.rgb(0, 122, 255),
    Color.rgb(52, 199, 89),
    Color.rgb(255, 149, 0),
    Color.rgb(255, 59, 48),
    Color.rgb(175, 82, 222),
    Color.rgb(90, 200, 250),
    Color.rgb(88, 86, 214),
    Color.rgb(255, 45, 85)
)
private val DARK_IOS_SYSTEM_AVATAR_COLORS = listOf(
    Color.rgb(10, 132, 255),
    Color.rgb(48, 209, 88),
    Color.rgb(255, 159, 10),
    Color.rgb(255, 69, 58),
    Color.rgb(191, 90, 242),
    Color.rgb(100, 210, 255),
    Color.rgb(94, 92, 230),
    Color.rgb(255, 55, 95)
)
private const val AVATAR_SIZE_DP = 46
private const val SPACE_AVATAR_CORNER_RATIO = 0.28f
private const val ROW_HEIGHT_DP = 76
private const val DJB2_OFFSET = 5381L
private const val DJB2_MULTIPLIER = 33L
