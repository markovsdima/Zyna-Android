package com.zyna.app.ui.calls

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallHistoryItem
import com.zyna.app.data.calls.matrixrtc.MatrixRtcCallHistoryOutcome
import com.zyna.app.data.media.MatrixMediaLoader
import com.zyna.app.ui.profile.ProfileAvatarView
import com.zyna.app.ui.settings.SettingsPalette
import kotlin.math.roundToInt

internal data class CallsScreenViewState(
    val calls: List<MatrixRtcCallHistoryItem>,
    val matrixMediaLoader: MatrixMediaLoader?,
    val bottomContentPaddingPx: Int
)

internal data class CallsScreenViewActions(
    val onOpenRoom: (MatrixRtcCallHistoryItem) -> Unit,
    val onCall: (MatrixRtcCallHistoryItem) -> Unit
)

internal class CallsScreenView(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private var palette = SettingsPalette.from(context)
    private var statusTopInset = 0

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val topBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val titleText = TextView(context).apply {
        text = context.getString(R.string.calls_title)
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = true
        updatePadding(left = dp(20), right = dp(12))
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
    private val adapter = CallAdapter()

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

    fun render(state: CallsScreenViewState, actions: CallsScreenViewActions) {
        adapter.actions = actions
        adapter.matrixMediaLoader = state.matrixMediaLoader
        adapter.palette = palette

        statusText.text = if (state.calls.isEmpty()) {
            context.getString(R.string.calls_empty)
        } else {
            ""
        }
        statusText.visibility = if (statusText.text.isNullOrBlank()) GONE else VISIBLE

        recyclerView.updatePadding(bottom = state.bottomContentPaddingPx + dp(12))
        adapter.submitList(state.calls)
    }

    private fun applyPalette() {
        setBackgroundColor(palette.background)
        root.setBackgroundColor(palette.background)
        topBar.setBackgroundColor(palette.background)
        titleText.setTextColor(palette.titleText)
        statusText.setTextColor(palette.secondaryText)
        recyclerView.setBackgroundColor(palette.background)
    }

    private fun updateTopBarHeight() {
        topBar.updatePadding(top = statusTopInset)
        val params = topBar.layoutParams as LinearLayout.LayoutParams
        params.height = dp(TOP_BAR_HEIGHT_DP) + statusTopInset
        topBar.layoutParams = params
    }

    private fun dp(value: Int): Int {
        return (value * density).roundToInt()
    }

    private companion object {
        const val TOP_BAR_HEIGHT_DP = 64
    }
}

private class CallAdapter :
    ListAdapter<MatrixRtcCallHistoryItem, CallViewHolder>(CallDiffCallback) {
    var actions: CallsScreenViewActions? = null
    var matrixMediaLoader: MatrixMediaLoader? = null
    var palette: SettingsPalette? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CallViewHolder {
        return CallViewHolder(parent.context)
    }

    override fun onBindViewHolder(holder: CallViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(
            item = item,
            matrixMediaLoader = matrixMediaLoader,
            palette = palette ?: SettingsPalette.from(holder.itemView.context),
            actions = actions
        )
    }
}

private class CallViewHolder(context: Context) : RecyclerView.ViewHolder(
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
    private val avatar = ProfileAvatarView(context)
    private val textColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val titleText = TextView(context).apply {
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = true
    }
    private val subtitleText = TextView(context).apply {
        textSize = 13f
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = true
    }
    private val callButton = TextView(context).apply {
        text = context.getString(R.string.calls_call)
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
        textColumn.addView(titleText)
        textColumn.addView(subtitleText)
        row.addView(
            textColumn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            callButton,
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
        item: MatrixRtcCallHistoryItem,
        matrixMediaLoader: MatrixMediaLoader?,
        palette: SettingsPalette,
        actions: CallsScreenViewActions?
    ) {
        root.setBackgroundColor(palette.background)
        row.setBackgroundColor(palette.background)
        avatar.setPalette(palette)
        avatar.render(
            userId = item.senderId,
            displayName = item.title,
            avatarUrl = item.roomAvatarUrl,
            localAvatarPath = null,
            matrixMediaLoader = matrixMediaLoader,
            sizePx = dp(44)
        )
        titleText.text = item.title
        subtitleText.text = item.subtitle(itemView.context)
        titleText.setTextColor(palette.titleText)
        subtitleText.setTextColor(palette.secondaryText)
        separator.setBackgroundColor(palette.separator)

        row.alpha = 1f
        callButton.alpha = 1f
        callButton.isEnabled = true
        callButton.setTextColor(palette.actionText)
        callButton.compoundDrawableTintList = ColorStateList.valueOf(palette.actionText)
        callButton.background = roundedDrawable(palette.surface, dp(12))

        row.setOnClickListener { actions?.onOpenRoom(item) }
        callButton.setOnClickListener { actions?.onCall(item) }
        itemView.contentDescription = "${item.title}. ${subtitleText.text}"
    }

    private fun MatrixRtcCallHistoryItem.subtitle(context: Context): String {
        val direction = context.getString(
            if (isOutgoing) R.string.calls_outgoing else R.string.calls_incoming
        )
        val outcome = context.getString(outcome.stringResId())
        val time = DateUtils.formatDateTime(
            context,
            timestampMillis,
            DateUtils.FORMAT_SHOW_DATE or
                DateUtils.FORMAT_SHOW_TIME or
                DateUtils.FORMAT_ABBREV_MONTH
        )
        return context.getString(
            R.string.calls_subtitle_format,
            outcome,
            direction,
            time
        )
    }

    private fun MatrixRtcCallHistoryOutcome.stringResId(): Int {
        return when (this) {
            MatrixRtcCallHistoryOutcome.STARTED -> R.string.calls_outcome_started
            MatrixRtcCallHistoryOutcome.ANSWERED -> R.string.calls_outcome_answered
            MatrixRtcCallHistoryOutcome.DECLINED -> R.string.calls_outcome_declined
            MatrixRtcCallHistoryOutcome.DECLINED_BY_ME -> R.string.calls_outcome_declined_by_me
            MatrixRtcCallHistoryOutcome.CANCELLED_BY_ME -> R.string.calls_outcome_cancelled_by_me
            MatrixRtcCallHistoryOutcome.MISSED -> R.string.calls_outcome_missed
            MatrixRtcCallHistoryOutcome.UNANSWERED -> R.string.calls_outcome_unanswered
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

private object CallDiffCallback : DiffUtil.ItemCallback<MatrixRtcCallHistoryItem>() {
    override fun areItemsTheSame(
        oldItem: MatrixRtcCallHistoryItem,
        newItem: MatrixRtcCallHistoryItem
    ): Boolean {
        return oldItem.eventId == newItem.eventId
    }

    override fun areContentsTheSame(
        oldItem: MatrixRtcCallHistoryItem,
        newItem: MatrixRtcCallHistoryItem
    ): Boolean {
        return oldItem == newItem
    }
}
