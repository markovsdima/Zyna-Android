package com.zyna.app.ui.glass

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Vibrator
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.zyna.app.BuildConfig
import com.zyna.app.data.matrix.MatrixMediaGroupItem
import com.zyna.app.data.matrix.MatrixMessageDeliveryState
import com.zyna.app.data.messaging.normalizedMessageCaption
import com.zyna.app.ui.chat.render.MessageContent
import com.zyna.app.ui.chat.render.MessageContextMenuRequest
import com.zyna.app.ui.chat.render.MessageRenderModel
import com.zyna.app.ui.chat.render.PaintSplashTarget
import com.zyna.app.ui.chat.render.PhotoGroupContextSelection
import com.zyna.app.ui.chat.render.RenderDeliveryState
import kotlin.math.min
import kotlin.math.roundToInt

internal class MessageContextMenuLayer @JvmOverloads constructor(
    context: Context,
    private val controller: GlassBackdropController,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val density = resources.displayMetrics.density
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val menuClipBackground = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
    }
    private val menuGlass = GlassPanelView(context, controller).apply {
        captureBackdropWhenTransparent = true
    }
    private val menuContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = menuClipBackground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            clipToOutline = true
            elevation = 10.dpToPx(density).toFloat()
        }
    }
    private val layerLocation = IntArray(2)
    private val cellLocation = IntArray(2)
    private val bubbleBoundsInScreen = RectF()
    private val bubbleBoundsInLayer = RectF()
    private val drawingBubbleBoundsInLayer = RectF()
    private val shiftedBubbleBoundsInLayer = RectF()
    private val cancelDistancePx = ViewConfiguration.get(context).scaledTouchSlop * 2f

    private var selectedRequest: MessageContextMenuRequest? = null
    private var selectedMessage: MessageRenderModel? = null
    private var palette: GlassPalette = defaultMenuPalette()
    private var animator: ValueAnimator? = null
    private var isDismissing = false
    private var isGestureCancelEnabled = false
    private var isMenuOpenRequested = false
    private var hoveredActionView: TextView? = null
    private var selectedCellTargetOffsetY = 0f
    private var selectedCellAnticipationScale = 1f
        set(value) {
            field = value
            selectedCellLayer.invalidate()
        }
    private var activationRawX = 0f
    private var activationRawY = 0f
    private var pressProgress = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            selectedCellLayer.invalidate()
        }
    private var menuProgress = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            menuContainer.alpha = field
            menuGlass.alpha = field
            selectedCellLayer.invalidate()
        }

    var onDismissRequested: () -> Unit = {}
    var onActionSelected: ((
        request: MessageContextMenuRequest,
        action: MessageContextMenuAction
    ) -> Unit)? = null
    var onGlassGeometryChanged: () -> Unit = {}
    var onDismissFullyHidden: () -> Unit = {}
    val selectedCellLayer: View = SelectedCellLayer(context)

    init {
        visibility = GONE
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        isHapticFeedbackEnabled = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setWillNotDraw(true)
        clipChildren = false
        clipToPadding = false
        addView(menuGlass)
        addView(menuContainer)
        applyPalette(palette)
    }

    fun setPalette(nextPalette: GlassPalette) {
        if (palette == nextPalette) {
            return
        }
        palette = nextPalette
        applyPalette(nextPalette)
    }

    fun beginPreview(request: MessageContextMenuRequest): Boolean {
        animator?.cancel()
        animator = null
        restoreSelectedSource()
        isDismissing = false
        isMenuOpenRequested = false
        clearHoveredActionView()
        if (!setSelectedRequest(request)) {
            clearSelection(cancelAnimator = false)
            return false
        }

        request.cell.setContextMenuSourceHidden(true)
        visibility = VISIBLE
        selectedCellLayer.visibility = VISIBLE
        bringToFront()
        isGestureCancelEnabled = true
        menuProgress = 0f
        controller.invalidateRegions()
        requestFocus()
        requestLayout()
        animateState(
            targetPressProgress = 1f,
            targetMenuProgress = 0f,
            duration = PREVIEW_SHRINK_DURATION_MS,
            clearAfterEnd = false
        )
        return true
    }

    fun show(request: MessageContextMenuRequest): Boolean {
        if (visibility != VISIBLE || selectedRequest?.cell !== request.cell) {
            if (!beginPreview(request)) {
                return false
            }
        } else if (!setSelectedRequest(request)) {
            clearSelection(cancelAnimator = false)
            return false
        }
        isDismissing = false
        isGestureCancelEnabled = false
        isMenuOpenRequested = true
        visibility = VISIBLE
        selectedCellLayer.visibility = VISIBLE
        bringToFront()
        requestFocus()
        requestLayout()
        animateState(
            targetPressProgress = 0f,
            targetMenuProgress = 1f,
            duration = OPEN_ANIMATION_DURATION_MS,
            clearAfterEnd = false
        )
        return true
    }

    fun dismiss(animated: Boolean) {
        if (visibility != VISIBLE) {
            clearSelection()
            return
        }
        if (isDismissing) {
            return
        }
        animator?.cancel()
        isDismissing = true
        isGestureCancelEnabled = false
        isMenuOpenRequested = false
        clearHoveredActionView()
        if (animated) {
            animateState(
                targetPressProgress = 0f,
                targetMenuProgress = 0f,
                duration = DISMISS_ANIMATION_DURATION_MS,
                clearAfterEnd = true
            )
        } else {
            pressProgress = 0f
            menuProgress = 0f
            clearSelection()
        }
    }

    fun handleGestureEvent(action: Int, rawX: Float, rawY: Float) {
        if (visibility != VISIBLE || isDismissing) {
            return
        }
        if (isMenuOpenRequested && handleActionHoverGesture(action, rawX, rawY)) {
            return
        }
        if (action == MotionEvent.ACTION_MOVE && isGestureCancelEnabled) {
            val dx = rawX - activationRawX
            val dy = rawY - activationRawY
            if (dx * dx + dy * dy >= cancelDistancePx * cancelDistancePx) {
                onDismissRequested()
            }
        }
    }

    fun captureSelectedPaintSplashTarget(root: View): PaintSplashTarget? {
        val request = selectedRequest ?: return null
        return request.cell.capturePaintSplashTarget(root)
    }

    fun captureSelectedPhotoPaintSplashTarget(root: View): PaintSplashTarget? {
        val request = selectedRequest ?: return null
        val selection = request.photoGroupSelection
            ?.takeIf { it.canDeletePhoto() }
            ?: return null
        return request.cell.capturePhotoGroupSelectionPaintSplashTarget(root, selection)
    }

    fun selectedPhotoRedactionMessageId(): String? {
        return selectedRequest
            ?.photoGroupSelection
            ?.takeIf { it.canDeletePhoto() }
            ?.item
            ?.messageId
    }

    fun selectedPhotoGroupRedactionMessageIds(): List<String> {
        val message = selectedRequest?.message ?: return emptyList()
        val content = message.content as? MessageContent.PhotoGroup ?: return emptyList()
        if (message.redactionTargetMessageId == null) {
            return emptyList()
        }
        return content.items
            .filter { it.canRedactGroupItem() }
            .map { it.messageId }
    }

    internal fun collectVulkanGlassRects(
        out: MutableList<VulkanChatGlassRect>,
        originLeft: Int = left,
        originTop: Int = top
    ) {
        if (
            visibility != VISIBLE ||
            menuGlass.width <= 0 ||
            menuGlass.height <= 0 ||
            menuProgress <= 0.01f
        ) {
            return
        }

        out.add(
            VulkanChatGlassRect(
                left = (originLeft + menuGlass.left).toFloat(),
                top = (originTop + menuGlass.top).toFloat(),
                right = (originLeft + menuGlass.right).toFloat(),
                bottom = (originTop + menuGlass.bottom).toFloat(),
                cornerRadius = 14f.dpToPx(density),
                opacity = 0.76f * menuProgress,
                bezelWidth = 36f.dpToPx(density),
                glassThickness = 55f.dpToPx(density)
            )
        )
    }

    fun playSelectedCellDeleteAnticipation(onEnd: () -> Unit): Boolean {
        if (visibility != VISIBLE || selectedRequest == null) {
            return false
        }
        animator?.cancel()
        animator = null
        val startScale = selectedCellAnticipationScale
        var didFinish = false

        fun finishOnce() {
            if (didFinish) {
                return
            }
            didFinish = true
            onEnd()
        }

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DELETE_ANTICIPATION_DURATION_MS
            interpolator = AccelerateInterpolator()
            addUpdateListener {
                val animatedProgress = it.animatedValue as Float
                selectedCellAnticipationScale = lerp(
                    startScale,
                    DELETE_ANTICIPATION_SCALE,
                    animatedProgress
                )
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    finishOnce()
                }

                override fun onAnimationEnd(animation: Animator) {
                    finishOnce()
                }
            })
            start()
        }
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                onDismissRequested()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            onDismissRequested()
            return true
        }
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (visibility != VISIBLE || selectedRequest == null) {
            val emptySpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY)
            menuContainer.measure(emptySpec, emptySpec)
            menuGlass.measure(emptySpec, emptySpec)
            setMeasuredDimension(width, height)
            return
        }

        val horizontalSafe = 24.dpToPx(density)
        val maxMenuWidth = min(240.dpToPx(density), (width - horizontalSafe).coerceAtLeast(1))
        menuContainer.measure(
            MeasureSpec.makeMeasureSpec(maxMenuWidth, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(height.coerceAtLeast(1), MeasureSpec.AT_MOST)
        )
        menuGlass.measure(
            MeasureSpec.makeMeasureSpec(menuContainer.measuredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(menuContainer.measuredHeight, MeasureSpec.EXACTLY)
        )
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (visibility != VISIBLE || selectedRequest == null) {
            menuGlass.layout(0, 0, 0, 0)
            menuContainer.layout(0, 0, 0, 0)
            selectedCellLayer.invalidate()
            return
        }

        updateBubbleBoundsInLayer()
        val menuWidth = menuContainer.measuredWidth
        val menuHeight = menuContainer.measuredHeight
        val margin = 12.dpToPx(density)
        val gap = 8.dpToPx(density)
        selectedCellTargetOffsetY = computeSelectedCellTargetOffsetY(menuHeight, margin, gap)
        shiftedBubbleBoundsInLayer.set(bubbleBoundsInLayer)
        shiftedBubbleBoundsInLayer.offset(0f, selectedCellTargetOffsetY)

        val desiredLeft = if (selectedMessage?.isOutgoing == true) {
            shiftedBubbleBoundsInLayer.right.roundToInt() - menuWidth
        } else {
            shiftedBubbleBoundsInLayer.left.roundToInt()
        }
        val menuLeft = desiredLeft.coerceIn(margin, (width - margin - menuWidth).coerceAtLeast(margin))

        val menuTop = (shiftedBubbleBoundsInLayer.bottom.roundToInt() + gap)
            .coerceAtLeast(margin)

        menuContainer.pivotX = if (selectedMessage?.isOutgoing == true) menuWidth.toFloat() else 0f
        menuContainer.pivotY = 0f
        menuGlass.pivotX = menuContainer.pivotX
        menuGlass.pivotY = menuContainer.pivotY
        menuGlass.alpha = menuContainer.alpha
        menuGlass.layout(menuLeft, menuTop, menuLeft + menuWidth, menuTop + menuHeight)
        menuContainer.layout(menuLeft, menuTop, menuLeft + menuWidth, menuTop + menuHeight)
        selectedCellLayer.invalidate()
        controller.invalidateRegions()
        onGlassGeometryChanged()
    }

    private fun buildMenu(request: MessageContextMenuRequest) {
        menuContainer.removeAllViews()
        val message = request.message
        val photoGroupContent = message.content as? MessageContent.PhotoGroup
        val redactableGroupItems = if (message.redactionTargetMessageId != null) {
            photoGroupContent
                ?.items
                ?.filter { it.canRedactGroupItem() }
                .orEmpty()
        } else {
            emptyList()
        }
        val actions = buildList {
            if (
                message.eventId != null &&
                message.content !is MessageContent.Redacted &&
                message.outgoingEnvelopeId == null
            ) {
                add(MessageContextMenuAction.REPLY)
            }
            if (message.canShowForwardAction()) {
                add(MessageContextMenuAction.FORWARD)
            }
            if (message.editInfo != null) {
                add(MessageContextMenuAction.EDIT)
            }
            if (message.copyableText() != null) {
                add(MessageContextMenuAction.COPY)
            }
            if (photoGroupContent != null && redactableGroupItems.isNotEmpty()) {
                if (request.photoGroupSelection?.canDeletePhoto() == true) {
                    add(MessageContextMenuAction.DELETE_PHOTO)
                }
                add(MessageContextMenuAction.DELETE_GROUP)
            } else if (message.redactionTargetMessageId != null) {
                add(MessageContextMenuAction.DELETE)
            }
            if (message.outgoingEnvelopeId != null && message.canRetryOutgoingEnvelope) {
                add(MessageContextMenuAction.RETRY_SEND)
            }
            if (message.outgoingEnvelopeId != null && message.canDiscardOutgoingEnvelope) {
                add(MessageContextMenuAction.REMOVE_FAILED_SEND)
            }
            if (
                BuildConfig.DEBUG &&
                message.outgoingEnvelopeId != null &&
                message.deliveryState != RenderDeliveryState.FAILED
            ) {
                add(MessageContextMenuAction.DEBUG_MARK_FAILED)
            }
        }

        actions.forEachIndexed { index, action ->
            if (index > 0) {
                menuContainer.addView(MenuDivider(context, palette))
            }
            menuContainer.addView(createActionView(action))
        }
    }

    private fun createActionView(action: MessageContextMenuAction): TextView {
        val selectableBackground = TypedValue()
        context.theme.resolveAttribute(
            android.R.attr.selectableItemBackground,
            selectableBackground,
            true
        )
        return TextView(context).apply {
            text = action.title
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            minWidth = 156.dpToPx(density)
            minHeight = 44.dpToPx(density)
            setTextColor(action.textColor(palette))
            setPadding(
                16.dpToPx(density),
                0,
                18.dpToPx(density),
                0
            )
            setBackgroundResource(selectableBackground.resourceId)
            isClickable = true
            isFocusable = true
            isHapticFeedbackEnabled = true
            tag = action
            contentDescription = action.title
            setOnClickListener {
                selectedRequest?.let { request ->
                    onActionSelected?.invoke(request, action)
                }
            }
        }
    }

    private fun drawDim(canvas: Canvas, drawingLayer: View) {
        val alpha = (72 * menuProgress).roundToInt()
        if (alpha <= 0) {
            return
        }
        dimPaint.color = Color.argb(alpha, 0, 0, 0)
        canvas.drawRect(
            0f,
            0f,
            drawingLayer.width.toFloat(),
            drawingLayer.height.toFloat(),
            dimPaint
        )
    }

    private fun drawSelectedCell(canvas: Canvas, drawingLayer: View) {
        val request = selectedRequest ?: return
        val cell = request.cell
        if (!cell.isAttachedToWindow || cell.visibility != View.VISIBLE) {
            return
        }
        drawingLayer.getLocationOnScreen(layerLocation)
        cell.getLocationOnScreen(cellLocation)

        val layerCellX = (cellLocation[0] - layerLocation[0]).toFloat()
        val layerCellY = (cellLocation[1] - layerLocation[1]).toFloat()
        drawingBubbleBoundsInLayer.set(bubbleBoundsInScreen)
        drawingBubbleBoundsInLayer.offset(
            -layerLocation[0].toFloat(),
            -layerLocation[1].toFloat()
        )
        val currentOffsetY = selectedCellTargetOffsetY * menuProgress
        val scale = currentSelectedCellScale() * selectedCellAnticipationScale
        val save = canvas.save()
        canvas.translate(layerCellX, layerCellY + currentOffsetY)
        canvas.scale(
            scale,
            scale,
            drawingBubbleBoundsInLayer.centerX() - layerCellX,
            drawingBubbleBoundsInLayer.centerY() - layerCellY
        )
        cell.drawForContextMenu(canvas)
        canvas.restoreToCount(save)
    }

    private fun computeSelectedCellTargetOffsetY(menuHeight: Int, margin: Int, gap: Int): Float {
        val safeTop = margin.toFloat()
        val safeBottom = (height - margin).toFloat()
        val minOffsetForTop = safeTop - bubbleBoundsInLayer.top
        val maxOffsetForMenuBottom = safeBottom - (bubbleBoundsInLayer.bottom + gap + menuHeight)

        return if (minOffsetForTop <= maxOffsetForMenuBottom) {
            0f.coerceIn(minOffsetForTop, maxOffsetForMenuBottom)
        } else {
            maxOffsetForMenuBottom
        }
    }

    private fun currentSelectedCellScale(): Float {
        return lerp(1f, CELL_PRESSED_SCALE, pressProgress)
    }

    private fun updateBubbleBoundsInLayer() {
        getLocationOnScreen(layerLocation)
        bubbleBoundsInLayer.set(bubbleBoundsInScreen)
        bubbleBoundsInLayer.offset(
            -layerLocation[0].toFloat(),
            -layerLocation[1].toFloat()
        )
    }

    private fun applyPalette(nextPalette: GlassPalette) {
        val cornerRadius = 14f.dpToPx(density)
        menuClipBackground.cornerRadius = cornerRadius
        menuGlass.glassStyle = GlassStyle(
            cornerRadiusPx = cornerRadius,
            blurRadiusPx = 4f.dpToPx(density),
            downscale = 3,
            tintColor = nextPalette.glassTintStrong,
            strokeColor = nextPalette.stroke,
            strokeWidthPx = 1f.dpToPx(density),
            refractionIntensity = 1.1f,
            bevelWidthPx = 32f.dpToPx(density),
            refractionThicknessPx = 48f.dpToPx(density),
            chromaSpread = 0.02f,
            adaptiveContrast = 0.24f
        )
        for (index in 0 until menuContainer.childCount) {
            val child = menuContainer.getChildAt(index)
            if (child is TextView) {
                val action = child.tag as? MessageContextMenuAction
                child.setTextColor(action?.textColor(nextPalette) ?: nextPalette.text)
            } else if (child is MenuDivider) {
                child.setPalette(nextPalette)
            }
        }
        invalidate()
    }

    private fun handleActionHoverGesture(action: Int, rawX: Float, rawY: Float): Boolean {
        return when (action) {
            MotionEvent.ACTION_MOVE -> {
                updateHoveredActionView(
                    nextView = findActionViewAt(rawX, rawY),
                    withHaptic = true
                )
                hoveredActionView != null
            }
            MotionEvent.ACTION_UP -> {
                val actionView = findActionViewAt(rawX, rawY)
                updateHoveredActionView(actionView)
                val selectedAction = actionView?.tag as? MessageContextMenuAction
                val request = selectedRequest
                clearHoveredActionView()
                if (request != null && selectedAction != null) {
                    onActionSelected?.invoke(request, selectedAction)
                    true
                } else {
                    false
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                clearHoveredActionView()
                false
            }
            else -> false
        }
    }

    private fun findActionViewAt(rawX: Float, rawY: Float): TextView? {
        getLocationOnScreen(layerLocation)
        val layerX = rawX - layerLocation[0]
        val layerY = rawY - layerLocation[1]
        if (
            layerX < menuContainer.left ||
            layerX > menuContainer.right ||
            layerY < menuContainer.top ||
            layerY > menuContainer.bottom
        ) {
            return null
        }

        val menuX = layerX - menuContainer.left
        val menuY = layerY - menuContainer.top
        for (index in 0 until menuContainer.childCount) {
            val child = menuContainer.getChildAt(index)
            if (
                child is TextView &&
                child.visibility == VISIBLE &&
                menuX >= child.left &&
                menuX <= child.right &&
                menuY >= child.top &&
                menuY <= child.bottom
            ) {
                return child
            }
        }
        return null
    }

    private fun updateHoveredActionView(nextView: TextView?, withHaptic: Boolean = false) {
        if (hoveredActionView === nextView) {
            return
        }
        hoveredActionView?.isPressed = false
        hoveredActionView = nextView
        hoveredActionView?.isPressed = true
        if (withHaptic && nextView != null) {
            performActionHoverHaptic(nextView)
        }
    }

    private fun clearHoveredActionView() {
        hoveredActionView?.isPressed = false
        hoveredActionView = null
    }

    private fun performActionHoverHaptic(view: View) {
        val hapticHost = selectedRequest?.cell ?: view
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        if (!vibrator.hasAmplitudeControl()) {
            return
        }
        hapticHost.performHapticFeedback(
            HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        )
    }

    private fun setSelectedRequest(request: MessageContextMenuRequest): Boolean {
        selectedRequest = request
        selectedMessage = request.message
        bubbleBoundsInScreen.set(request.bubbleBoundsInScreen)
        activationRawX = request.touchRawX
        activationRawY = request.touchRawY
        buildMenu(request)
        return menuContainer.childCount > 0
    }

    private fun animateState(
        targetPressProgress: Float,
        targetMenuProgress: Float,
        duration: Long,
        clearAfterEnd: Boolean
    ) {
        animator?.cancel()
        animator = null
        val startPressProgress = pressProgress
        val startMenuProgress = menuProgress
        var wasCanceled = false
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val animatedProgress = it.animatedValue as Float
                pressProgress = lerp(startPressProgress, targetPressProgress, animatedProgress)
                menuProgress = lerp(startMenuProgress, targetMenuProgress, animatedProgress)
                onGlassGeometryChanged()
            }
            if (clearAfterEnd) {
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationCancel(animation: Animator) {
                        wasCanceled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (!wasCanceled) {
                            clearSelection(cancelAnimator = false)
                        }
                    }
                })
            }
            start()
        }
    }

    private fun clearSelection(cancelAnimator: Boolean = true) {
        val wasDismissing = isDismissing
        if (cancelAnimator) {
            animator?.cancel()
        }
        animator = null
        isDismissing = false
        isGestureCancelEnabled = false
        isMenuOpenRequested = false
        clearHoveredActionView()
        menuContainer.removeAllViews()
        restoreSelectedSource()
        selectedRequest = null
        selectedMessage = null
        selectedCellTargetOffsetY = 0f
        selectedCellAnticipationScale = 1f
        visibility = GONE
        selectedCellLayer.visibility = GONE
        pressProgress = 0f
        menuProgress = 0f
        selectedCellLayer.invalidate()
        controller.invalidateRegions()
        if (wasDismissing) {
            onDismissFullyHidden()
        }
    }

    private fun restoreSelectedSource() {
        selectedRequest?.cell?.setContextMenuSourceHidden(false)
    }

    private inner class SelectedCellLayer(context: Context) : View(context) {
        init {
            visibility = GONE
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            setWillNotDraw(false)
        }

        override fun onDraw(canvas: Canvas) {
            drawDim(canvas, this)
            drawSelectedCell(canvas, this)
        }
    }
}

internal enum class MessageContextMenuAction(
    val title: String,
    val isDestructive: Boolean = false
) {
    REPLY("Reply"),
    FORWARD("Forward"),
    EDIT("Edit"),
    COPY("Copy"),
    DELETE_PHOTO("Delete Photo", true),
    DELETE_GROUP("Delete Group", true),
    DELETE("Delete", true),
    RETRY_SEND("Retry Send"),
    REMOVE_FAILED_SEND("Remove Failed Send", true),
    DEBUG_MARK_FAILED("Debug Mark Failed")
}

private fun MessageContextMenuAction.textColor(palette: GlassPalette): Int {
    return if (isDestructive) DESTRUCTIVE_TEXT_COLOR else palette.text
}

private class MenuDivider(
    context: Context,
    palette: GlassPalette
) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setPalette(palette)
    }

    fun setPalette(palette: GlassPalette) {
        paint.color = palette.stroke
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            1.dpToPx(density).coerceAtLeast(1)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val y = height / 2f
        canvas.drawLine(12.dpToPx(density).toFloat(), y, width.toFloat(), y, paint)
    }
}

private fun MessageRenderModel.copyableText(): String? {
    val text = when (val messageContent = content) {
        is MessageContent.Text -> messageContent.body
        is MessageContent.Image -> messageContent.caption.normalizedMessageCaption()
        is MessageContent.PhotoGroup -> messageContent.caption.normalizedMessageCaption()
        MessageContent.Redacted -> null
    }
    return text?.takeIf { it.isNotBlank() }
}

private fun MessageRenderModel.canShowForwardAction(): Boolean {
    if (!canForward || eventId.isNullOrBlank() || outgoingEnvelopeId != null) {
        return false
    }
    return content !is MessageContent.Redacted
}

private fun PhotoGroupContextSelection.canDeletePhoto(): Boolean {
    return !isOverflowTile && item.canRedactGroupItem()
}

private fun MatrixMediaGroupItem.canRedactGroupItem(): Boolean {
    return !eventId.isNullOrBlank() && deliveryState == MatrixMessageDeliveryState.SENT
}

private fun lerp(from: Float, to: Float, progress: Float): Float {
    return from + (to - from) * progress.coerceIn(0f, 1f)
}

private const val CELL_PRESSED_SCALE = 0.96f
private const val DELETE_ANTICIPATION_SCALE = 0.95f
private const val DELETE_ANTICIPATION_DURATION_MS = 80L
private const val PREVIEW_SHRINK_DURATION_MS = 170L
private const val OPEN_ANIMATION_DURATION_MS = 260L
private const val DISMISS_ANIMATION_DURATION_MS = 170L
private val DESTRUCTIVE_TEXT_COLOR = Color.rgb(211, 47, 47)

private fun defaultMenuPalette(): GlassPalette {
    return GlassPalette(
        background = 0xfffbfbff.toInt(),
        glassTint = 0xb8ffffff.toInt(),
        glassTintStrong = 0xf2ffffff.toInt(),
        stroke = 0x24000000,
        text = 0xff15151a.toInt(),
        hint = 0x9915151a.toInt()
    )
}
