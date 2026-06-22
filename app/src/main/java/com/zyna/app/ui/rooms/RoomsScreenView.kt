package com.zyna.app.ui.rooms

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
import androidx.recyclerview.widget.SimpleItemAnimator
import com.zyna.app.data.matrix.MatrixLastOwnMessageStatus
import com.zyna.app.data.matrix.MatrixRoomSummary
import com.zyna.app.util.ZynaPerfLog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt

data class RoomsScreenViewState(
    val rooms: List<MatrixRoomSummary>,
    val isRefreshing: Boolean,
    val title: String,
    val showLogout: Boolean,
    val showBack: Boolean,
    val bottomContentPaddingPx: Int
)

data class RoomsScreenViewActions(
    val onRefresh: () -> Unit,
    val onOpenRoom: (MatrixRoomSummary) -> Unit,
    val onLogout: (() -> Unit)?,
    val onBack: (() -> Unit)?
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
    private val refreshButton = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        isClickable = true
        isFocusable = true
    }
    private val logoutButton = TextView(context).apply {
        gravity = Gravity.CENTER
        text = "Log out"
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        isClickable = true
        isFocusable = true
    }
    private val contentFrame = FrameLayout(context)
    private val recyclerView = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context)
        clipToPadding = false
        setHasFixedSize(true)
        itemAnimator?.let { animator ->
            (animator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        }
    }
    private val emptyView = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 16f
        includeFontPadding = true
    }
    private val adapter = RoomsAdapter(palette)

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
            refreshButton,
            LinearLayout.LayoutParams(
                dp(96),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        topBar.addView(
            logoutButton,
            LinearLayout.LayoutParams(
                dp(84),
                ViewGroup.LayoutParams.MATCH_PARENT
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
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        updateThemeIfNeeded(force = true)
    }

    fun render(state: RoomsScreenViewState, actions: RoomsScreenViewActions) {
        updateThemeIfNeeded(force = false)
        val renderStart = ZynaPerfLog.start()
        titleText.text = state.title
        backButton.visibility = if (state.showBack) View.VISIBLE else View.GONE
        backButton.setOnClickListener { actions.onBack?.invoke() }
        logoutButton.visibility = if (state.showLogout) View.VISIBLE else View.GONE
        logoutButton.setOnClickListener { actions.onLogout?.invoke() }
        refreshButton.text = if (state.isRefreshing) "Syncing" else "Refresh"
        refreshButton.isEnabled = !state.isRefreshing
        refreshButton.alpha = if (state.isRefreshing) 0.54f else 1f
        refreshButton.setOnClickListener { actions.onRefresh() }

        recyclerView.setPadding(
            recyclerView.paddingLeft,
            recyclerView.paddingTop,
            recyclerView.paddingRight,
            state.bottomContentPaddingPx
        )
        emptyView.text = if (state.isRefreshing) "Loading chats" else "No chats"
        emptyView.visibility = if (state.rooms.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (state.rooms.isEmpty()) View.GONE else View.VISIBLE

        val roomCount = state.rooms.size
        adapter.onRoomClicked = { room ->
            ZynaPerfLog.mark {
                "rooms.tap roomId=${room.id} name=${room.displayName} rooms=$roomCount"
            }
            actions.onOpenRoom(room)
        }
        adapter.submitList(state.rooms)
        ZynaPerfLog.end(renderStart, "roomsView.render") {
            "title=${state.title} rooms=${state.rooms.size} refreshing=${state.isRefreshing}"
        }
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
        titleText.setTextColor(palette.titleText)
        refreshButton.setTextColor(palette.actionText)
        logoutButton.setTextColor(palette.actionText)
        emptyView.setTextColor(palette.secondaryText)
        adapter.setPalette(palette)
    }

    private fun dp(value: Int): Int {
        return (value * density).roundToInt()
    }

    private companion object {
        const val TOP_BAR_HEIGHT_DP = 64
    }
}

private class RoomsAdapter(
    private var palette: RoomsPalette
) : ListAdapter<MatrixRoomSummary, RoomViewHolder>(RoomDiffCallback) {
    var onRoomClicked: (MatrixRoomSummary) -> Unit = {}

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
        holder.bind(
            room = getItem(position),
            palette = palette,
            onClick = onRoomClicked
        )
    }

    fun setPalette(nextPalette: RoomsPalette) {
        if (palette == nextPalette) {
            return
        }
        palette = nextPalette
        if (itemCount > 0) {
            notifyItemRangeChanged(0, itemCount)
        }
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
        onClick: (MatrixRoomSummary) -> Unit
    ) {
        rowView.bind(room, palette)
        rowView.setOnClickListener { onClick(room) }
    }
}

private class RoomRowView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
    private var room: MatrixRoomSummary? = null
    private var palette = RoomsPalette.from(context.resources.configuration.isNightMode())
    private var avatarFillColor = palette.avatarColors.first()

    init {
        isClickable = true
        isFocusable = true
        setWillNotDraw(false)
        applySelectableForeground()
    }

    fun bind(room: MatrixRoomSummary, palette: RoomsPalette) {
        this.room = room
        this.palette = palette
        avatarFillColor = room.avatarColor(palette)
        setBackgroundColor(palette.background)
        contentDescription = room.accessibilityText()
        invalidate()
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
        val avatarSize = dp(46).toFloat()
        val avatarCenterX = left + avatarSize / 2f
        val avatarCenterY = height / 2f
        avatarPaint.color = avatarFillColor
        canvas.drawCircle(avatarCenterX, avatarCenterY, avatarSize / 2f, avatarPaint)
        canvas.drawText(
            room.avatarInitial(),
            avatarCenterX,
            centerBaseline(avatarCenterY, avatarTextPaint),
            avatarTextPaint
        )

        val textLeft = left + avatarSize + dp(12)
        val timeText = room.lastMessageAtMillis?.formatRoomTimestamp().orEmpty()
        val statusText = room.lastOwnMessageStatus?.label().orEmpty()
        val badgeText = room.unreadBadgeText()
        val rawTrailingWidth = max(
            max(metaPaint.measureText(timeText), metaPaint.measureText(statusText)),
            badgeText?.let { badgeTextPaint.measureText(it) + dp(14) } ?: room.unreadDotWidth()
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
            room.previewText().ellipsize(previewPaint, textRight - textLeft),
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
        drawUnreadIndicator(canvas, room, right, trailingWidth)
        canvas.drawLine(textLeft, height - 0.5f, widthPx.toFloat(), height - 0.5f, dividerPaint)
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
}

private data class RoomsPalette(
    val background: Int,
    val titleText: Int,
    val secondaryText: Int,
    val actionText: Int,
    val divider: Int,
    val avatarColors: List<Int>,
    val avatarText: Int,
    val unreadFill: Int,
    val mentionFill: Int,
    val unreadText: Int,
    val errorText: Int
) {
    companion object {
        fun from(isDarkTheme: Boolean): RoomsPalette {
            return if (isDarkTheme) {
                RoomsPalette(
                    background = Color.rgb(18, 18, 22),
                    titleText = Color.rgb(232, 225, 229),
                    secondaryText = Color.rgb(202, 196, 208),
                    actionText = Color.rgb(208, 188, 255),
                    divider = Color.argb(42, 255, 255, 255),
                    avatarColors = DARK_IOS_SYSTEM_AVATAR_COLORS,
                    avatarText = Color.WHITE,
                    unreadFill = Color.rgb(208, 188, 255),
                    mentionFill = Color.rgb(255, 180, 171),
                    unreadText = Color.rgb(33, 0, 93),
                    errorText = Color.rgb(255, 180, 171)
                )
            } else {
                RoomsPalette(
                    background = Color.WHITE,
                    titleText = Color.rgb(29, 27, 32),
                    secondaryText = Color.rgb(73, 69, 79),
                    actionText = Color.rgb(33, 0, 93),
                    divider = Color.argb(28, 0, 0, 0),
                    avatarColors = LIGHT_IOS_SYSTEM_AVATAR_COLORS,
                    avatarText = Color.WHITE,
                    unreadFill = Color.rgb(103, 80, 164),
                    mentionFill = Color.rgb(186, 26, 26),
                    unreadText = Color.WHITE,
                    errorText = Color.rgb(186, 26, 26)
                )
            }
        }
    }
}

private fun MatrixRoomSummary.previewText(): String {
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
    return colors[id.djb2HashIndex(colors.size)]
}

private fun MatrixRoomSummary.accessibilityText(): String {
    val unread = unreadBadgeText()?.let { ", $it unread" }.orEmpty()
    return "$displayName, ${previewText()}$unread"
}

private fun MatrixLastOwnMessageStatus.label(): String {
    return when (this) {
        MatrixLastOwnMessageStatus.PENDING -> "Sending"
        MatrixLastOwnMessageStatus.SENT -> "Sent"
        MatrixLastOwnMessageStatus.READ -> "Read"
        MatrixLastOwnMessageStatus.FAILED -> "Failed"
    }
}

private fun Long.formatRoomTimestamp(): String {
    val zone = ZoneId.systemDefault()
    val dateTime = Instant.ofEpochMilli(this).atZone(zone)
    val today = LocalDate.now(zone)
    return when (dateTime.toLocalDate()) {
        today -> ROOM_TIME_FORMATTER.format(dateTime)
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

private val ROOM_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
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
private const val ROW_HEIGHT_DP = 76
private const val DJB2_OFFSET = 5381L
private const val DJB2_MULTIPLIER = 33L
