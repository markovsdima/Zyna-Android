package com.zyna.app.ui.settings

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zyna.app.ui.chat.theme.ChatBubbleTheme
import com.zyna.app.ui.chat.theme.ChatBubbleThemes
import com.zyna.app.ui.chat.theme.MessageBubbleGradientSpec
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class ChatThemeSettingsScreenViewState(
    val selectedTheme: ChatBubbleTheme,
    val bottomContentPaddingPx: Int
)

internal data class ChatThemeSettingsScreenViewActions(
    val onBack: () -> Unit,
    val onSelectTheme: (String) -> Unit
)

internal class ChatThemeSettingsScreenView(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private var palette = SettingsPalette.from(context)
    private var statusTopInset = 0
    private var selectedTheme = ChatBubbleThemes.fallback
    private var actions = ChatThemeSettingsScreenViewActions(onBack = {}, onSelectTheme = {})

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
        typeface = Typeface.DEFAULT_BOLD
        isClickable = true
        isFocusable = true
    }
    private val titleText = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        text = "Chat Theme"
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = true
        maxLines = 1
    }
    private val controlsRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        updatePadding(left = dp(20), right = dp(20), top = dp(8), bottom = dp(6))
    }
    private val previousButton = TextView(context).apply {
        gravity = Gravity.CENTER
        text = "<"
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        isClickable = true
        isFocusable = true
    }
    private val themeTitleText = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = true
        maxLines = 1
    }
    private val nextButton = TextView(context).apply {
        gravity = Gravity.CENTER
        text = ">"
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        isClickable = true
        isFocusable = true
    }
    private val previewView = ChatThemePreviewView(context)
    private val recyclerView = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context)
        clipToPadding = false
        itemAnimator = null
    }
    private val adapter = ChatThemeAdapter(
        onThemeClicked = { theme ->
            actions.onSelectTheme(theme.id)
        }
    )

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
                dp(80),
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
            SpaceView(context),
            LinearLayout.LayoutParams(
                dp(80),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            controlsRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(56)
            )
        )
        controlsRow.addView(
            previousButton,
            LinearLayout.LayoutParams(
                dp(44),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        controlsRow.addView(
            themeTitleText,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
            )
        )
        controlsRow.addView(
            nextButton,
            LinearLayout.LayoutParams(
                dp(44),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            previewView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(196)
            ).apply {
                leftMargin = dp(20)
                rightMargin = dp(20)
                bottomMargin = dp(12)
            }
        )
        root.addView(
            recyclerView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        recyclerView.adapter = adapter

        backButton.setOnClickListener { actions.onBack() }
        previousButton.setOnClickListener { selectRelative(-1) }
        nextButton.setOnClickListener { selectRelative(1) }

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
    }

    fun render(
        state: ChatThemeSettingsScreenViewState,
        actions: ChatThemeSettingsScreenViewActions
    ) {
        this.actions = actions
        selectedTheme = state.selectedTheme
        themeTitleText.text = selectedTheme.title
        previewView.render(selectedTheme, palette)
        adapter.setState(
            themes = ChatBubbleThemes.all,
            selectedThemeId = selectedTheme.id,
            palette = palette
        )
        recyclerView.updatePadding(bottom = state.bottomContentPaddingPx + dp(16))
    }

    private fun selectRelative(offset: Int) {
        val themes = ChatBubbleThemes.all
        if (themes.isEmpty()) {
            return
        }
        val currentIndex = themes.indexOfFirst { it.id == selectedTheme.id }.takeIf { it >= 0 } ?: 0
        val nextIndex = (currentIndex + offset + themes.size) % themes.size
        actions.onSelectTheme(themes[nextIndex].id)
    }

    private fun applyPalette() {
        setBackgroundColor(palette.background)
        root.setBackgroundColor(palette.background)
        topBar.setBackgroundColor(palette.background)
        controlsRow.setBackgroundColor(palette.background)
        recyclerView.setBackgroundColor(palette.background)
        backButton.setTextColor(palette.actionText)
        titleText.setTextColor(palette.titleText)
        themeTitleText.setTextColor(palette.primaryText)
        previousButton.setTextColor(palette.actionText)
        nextButton.setTextColor(palette.actionText)
        previewView.render(selectedTheme, palette)
        adapter.setState(
            themes = ChatBubbleThemes.all,
            selectedThemeId = selectedTheme.id,
            palette = palette
        )
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

private class SpaceView(context: Context) : View(context)

private class ChatThemeAdapter(
    private val onThemeClicked: (ChatBubbleTheme) -> Unit
) : RecyclerView.Adapter<ChatThemeViewHolder>() {
    private var themes: List<ChatBubbleTheme> = emptyList()
    private var selectedThemeId: String = ""
    private var palette: SettingsPalette? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatThemeViewHolder {
        return ChatThemeViewHolder(parent, onThemeClicked)
    }

    override fun onBindViewHolder(holder: ChatThemeViewHolder, position: Int) {
        val theme = themes[position]
        holder.bind(
            theme = theme,
            selected = theme.id == selectedThemeId,
            palette = palette ?: SettingsPalette.from(holder.itemView.context)
        )
    }

    override fun getItemCount(): Int {
        return themes.size
    }

    fun setState(
        themes: List<ChatBubbleTheme>,
        selectedThemeId: String,
        palette: SettingsPalette
    ) {
        val changed = this.themes != themes ||
            this.selectedThemeId != selectedThemeId ||
            this.palette != palette
        if (!changed) {
            return
        }
        this.themes = themes
        this.selectedThemeId = selectedThemeId
        this.palette = palette
        notifyDataSetChanged()
    }
}

private class ChatThemeViewHolder(
    parent: ViewGroup,
    onThemeClicked: (ChatBubbleTheme) -> Unit
) : RecyclerView.ViewHolder(
    ChatThemeRowView(parent.context).apply {
        layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            62.dpToPx(parent.context)
        )
    }
) {
    private val rowView = itemView as ChatThemeRowView
    private var theme: ChatBubbleTheme? = null

    init {
        itemView.setOnClickListener {
            theme?.let(onThemeClicked)
        }
    }

    fun bind(theme: ChatBubbleTheme, selected: Boolean, palette: SettingsPalette) {
        this.theme = theme
        rowView.bind(theme, selected, palette)
    }
}

private class ChatThemeRowView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val swatchRect = RectF()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
        textSize = sp(17f)
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(2).toFloat()
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val swatchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val checkPath = Path()
    private var cachedSwatchSpec: MessageBubbleGradientSpec? = null
    private var cachedSwatchLeft = Float.NaN
    private var cachedSwatchTop = Float.NaN
    private var cachedSwatchWidth = Float.NaN
    private var cachedSwatchHeight = Float.NaN
    private var cachedSwatchShader: LinearGradient? = null
    private var theme = ChatBubbleThemes.fallback
    private var selected = false
    private var palette = SettingsPalette.from(context)

    init {
        isClickable = true
        isFocusable = true
    }

    fun bind(theme: ChatBubbleTheme, selected: Boolean, palette: SettingsPalette) {
        this.theme = theme
        this.selected = selected
        this.palette = palette
        contentDescription = if (selected) {
            "${theme.title}, selected"
        } else {
            theme.title
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        backgroundPaint.color = if (selected) palette.selectedFill else palette.background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        val swatchSize = dp(34).toFloat()
        val swatchLeft = dp(22).toFloat()
        val swatchTop = (height - swatchSize) / 2f
        swatchRect.set(swatchLeft, swatchTop, swatchLeft + swatchSize, swatchTop + swatchSize)
        drawSwatch(canvas, swatchRect, theme.outgoingGradient)

        textPaint.color = palette.primaryText
        val textX = swatchRect.right + dp(16)
        val baseline = height / 2f - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f
        canvas.drawText(theme.title, textX, baseline, textPaint)

        if (selected) {
            drawCheck(canvas)
        }

        dividerPaint.color = palette.separator
        canvas.drawLine(textX, height - 0.5f, width.toFloat(), height - 0.5f, dividerPaint)
    }

    private fun drawSwatch(canvas: Canvas, rect: RectF, spec: MessageBubbleGradientSpec) {
        swatchPaint.style = Paint.Style.FILL
        swatchPaint.alpha = 255
        swatchPaint.shader = swatchShader(rect, spec)
        canvas.drawOval(rect, swatchPaint)
        swatchPaint.shader = null
        swatchPaint.style = Paint.Style.STROKE
        swatchPaint.strokeWidth = 1f
        swatchPaint.color = palette.separator
        canvas.drawOval(rect, swatchPaint)
        swatchPaint.style = Paint.Style.FILL
        swatchPaint.alpha = 255
    }

    private fun swatchShader(rect: RectF, spec: MessageBubbleGradientSpec): LinearGradient {
        val rectWidth = rect.width()
        val rectHeight = rect.height()
        val cached = cachedSwatchShader
        if (
            cached != null &&
            cachedSwatchSpec == spec &&
            cachedSwatchLeft == rect.left &&
            cachedSwatchTop == rect.top &&
            cachedSwatchWidth == rectWidth &&
            cachedSwatchHeight == rectHeight
        ) {
            return cached
        }

        val nextShader = LinearGradient(
            rect.left + spec.startX * rectWidth,
            rect.top + spec.startY * rectHeight,
            rect.left + spec.endX * rectWidth,
            rect.top + spec.endY * rectHeight,
            spec.colors.toIntArray(),
            spec.positions?.toFloatArray(),
            Shader.TileMode.CLAMP
        )
        cachedSwatchSpec = spec
        cachedSwatchLeft = rect.left
        cachedSwatchTop = rect.top
        cachedSwatchWidth = rectWidth
        cachedSwatchHeight = rectHeight
        cachedSwatchShader = nextShader
        return nextShader
    }

    private fun drawCheck(canvas: Canvas) {
        val centerX = width - dp(32).toFloat()
        val centerY = height / 2f
        checkPath.rewind()
        checkPath.moveTo(centerX - dp(8), centerY)
        checkPath.lineTo(centerX - dp(2), centerY + dp(6))
        checkPath.lineTo(centerX + dp(10), centerY - dp(8))
        checkPaint.color = palette.actionText
        canvas.drawPath(checkPath, checkPaint)
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

private class ChatThemePreviewView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val backgroundRect = RectF()
    private val incomingRect = RectF()
    private val outgoingRect = RectF()
    private val shortOutgoingRect = RectF()
    private val outgoingMaskPath = Path()
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(15f)
    }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11f)
        textAlign = Paint.Align.RIGHT
    }
    private var cachedGradientSpec: MessageBubbleGradientSpec? = null
    private var cachedGradientWidth = -1
    private var cachedGradientHeight = -1
    private var cachedGradientShader: LinearGradient? = null
    private var theme = ChatBubbleThemes.fallback
    private var palette = SettingsPalette.from(context)

    fun render(theme: ChatBubbleTheme, palette: SettingsPalette) {
        if (this.theme == theme && this.palette == palette) {
            return
        }
        this.theme = theme
        this.palette = palette
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        backgroundRect.set(0f, 0f, width.toFloat(), height.toFloat())
        backgroundPaint.color = palette.surface
        canvas.drawRoundRect(backgroundRect, dp(8).toFloat(), dp(8).toFloat(), backgroundPaint)

        val horizontalInset = dp(16).toFloat()
        val incomingWidth = min(width * 0.72f, dp(230).toFloat())
        incomingRect.set(
            horizontalInset,
            dp(18).toFloat(),
            horizontalInset + incomingWidth,
            dp(62).toFloat()
        )

        val outgoingWidth = min(width * 0.76f, dp(246).toFloat())
        outgoingRect.set(
            width - horizontalInset - outgoingWidth,
            dp(78).toFloat(),
            width - horizontalInset,
            dp(130).toFloat()
        )

        val shortWidth = min(width * 0.48f, dp(150).toFloat())
        shortOutgoingRect.set(
            width - horizontalInset - shortWidth,
            dp(146).toFloat(),
            width - horizontalInset,
            dp(182).toFloat()
        )

        drawIncomingBubble(canvas)
        drawOutgoingGradientMask(canvas)
        drawPreviewText(canvas)
    }

    private fun drawIncomingBubble(canvas: Canvas) {
        bubblePaint.color = palette.background
        canvas.drawRoundRect(incomingRect, dp(18).toFloat(), dp(18).toFloat(), bubblePaint)
    }

    private fun drawOutgoingGradientMask(canvas: Canvas) {
        outgoingMaskPath.rewind()
        outgoingMaskPath.addRoundRect(outgoingRect, dp(18).toFloat(), dp(18).toFloat(), Path.Direction.CW)
        outgoingMaskPath.addRoundRect(shortOutgoingRect, dp(18).toFloat(), dp(18).toFloat(), Path.Direction.CW)
        val spec = theme.outgoingGradient
        bubblePaint.shader = gradientShader(spec)
        canvas.drawPath(outgoingMaskPath, bubblePaint)
        bubblePaint.shader = null
    }

    private fun gradientShader(spec: MessageBubbleGradientSpec): LinearGradient {
        val cached = cachedGradientShader
        if (
            cached != null &&
            cachedGradientSpec == spec &&
            cachedGradientWidth == width &&
            cachedGradientHeight == height
        ) {
            return cached
        }

        val nextShader = LinearGradient(
            spec.startX * width,
            spec.startY * height,
            spec.endX * width,
            spec.endY * height,
            spec.colors.toIntArray(),
            spec.positions?.toFloatArray(),
            Shader.TileMode.CLAMP
        )
        cachedGradientSpec = spec
        cachedGradientWidth = width
        cachedGradientHeight = height
        cachedGradientShader = nextShader
        return nextShader
    }

    private fun drawPreviewText(canvas: Canvas) {
        textPaint.typeface = Typeface.DEFAULT
        textPaint.color = palette.primaryText
        timePaint.color = palette.secondaryText
        drawBubbleText(canvas, incomingRect, "How does this look?", "12:41", outgoing = false)

        textPaint.color = android.graphics.Color.WHITE
        timePaint.color = android.graphics.Color.argb(222, 255, 255, 255)
        drawBubbleText(canvas, outgoingRect, "Clean. Keep it.", "12:42", outgoing = true)
        drawBubbleText(canvas, shortOutgoingRect, "Done", "12:43", outgoing = true)
    }

    private fun drawBubbleText(
        canvas: Canvas,
        rect: RectF,
        text: String,
        time: String,
        outgoing: Boolean
    ) {
        val left = rect.left + dp(12)
        val right = rect.right - dp(12)
        val centerY = rect.centerY()
        val textBaseline = centerY - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f
        val timeBaseline = rect.bottom - dp(if (outgoing) 9 else 8).toFloat()
        val timeWidth = dp(42).toFloat()
        val textRight = max(left, right - timeWidth - dp(6))
        val clipped = text.ellipsize(textPaint, textRight - left)
        canvas.drawText(clipped, left, textBaseline, textPaint)
        canvas.drawText(time, right, timeBaseline, timePaint)
    }

    private fun String.ellipsize(paint: Paint, maxWidth: Float): String {
        if (maxWidth <= 0f || paint.measureText(this) <= maxWidth) {
            return this
        }
        val ellipsis = "..."
        val ellipsisWidth = paint.measureText(ellipsis)
        var end = length
        while (end > 0 && paint.measureText(substring(0, end)) + ellipsisWidth > maxWidth) {
            end--
        }
        return substring(0, end) + ellipsis
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

private fun Int.dpToPx(context: Context): Int {
    return (this * context.resources.displayMetrics.density).roundToInt()
}
