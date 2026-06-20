package com.zyna.app.ui.chat.viewer

import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import com.zyna.app.data.media.MatrixMediaImageQuality
import com.zyna.app.data.media.MatrixMediaLoader
import kotlin.math.abs
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
internal class PhotoViewerLayer(
    context: Context,
    private val imageLoader: MatrixMediaLoader
) : View(context) {
    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val minimumFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint()
    private val chromeScrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chromeIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.dpToPx(density).toFloat()
    }
    private val chromeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 15.dpToPx(density).toFloat()
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val clipPath = Path()
    private val srcRect = Rect()
    private val drawRect = RectF()
    private val pageDrawRect = RectF()
    private val closeButtonRect = RectF()
    private val pageLabelRect = RectF()
    private val sourceRect = RectF()
    private val targetRect = RectF()
    private val layerLocation = IntArray(2)

    private var openRequest: PhotoViewerOpenRequest? = null
    private var selectedIndex = 0
    private var bitmap: Bitmap? = null
    private var bitmapItemId: String? = null
    private val pageBitmaps = mutableMapOf<String, Bitmap>()
    private var imageLoadHandle: AutoCloseable? = null
    private var animator: ValueAnimator? = null
    private var velocityTracker: VelocityTracker? = null
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragDismissX = 0f
    private var dragDismissY = 0f
    private var panX = 0f
    private var panY = 0f
    private var imageScale = 1f
    private var transitionProgress = 1f
    private var dismissProgress = 0f
    private var isClosing = false
    private var isDraggingToDismiss = false
    private var isPanningImage = false
    private var isPagingImage = false
    private var pageOffsetX = 0f
    private var pendingPageTargetIndex: Int? = null
    private var lastTapUptimeMs = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                settleInterruptedAnimationForNewGesture()
                isDraggingToDismiss = false
                isPagingImage = false
                pageOffsetX = 0f
                imageScale = imageScale.coerceAtLeast(1f)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val before = imageScale
                imageScale = (imageScale * detector.scaleFactor).coerceIn(1f, MAX_IMAGE_SCALE)
                val factor = imageScale / before.coerceAtLeast(0.001f)
                val centerX = width / 2f
                val centerY = height / 2f
                panX = detector.focusX - (detector.focusX - centerX - panX) * factor - centerX
                panY = detector.focusY - (detector.focusY - centerY - panY) * factor - centerY
                clampPan()
                invalidate()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (imageScale <= MIN_ZOOM_RESET_SCALE) {
                    animateTransformTo(scale = 1f, panX = 0f, panY = 0f)
                } else {
                    clampPan()
                    invalidate()
                }
            }
        }
    )

    var onDismissed: () -> Unit = {}

    init {
        visibility = GONE
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setWillNotDraw(false)
    }

    fun open(request: PhotoViewerOpenRequest) {
        if (width <= 0 || height <= 0) {
            transitionProgress = 0f
            openRequest = request
            visibility = VISIBLE
            bringToFront()
            post {
                if (openRequest == request && width > 0 && height > 0) {
                    open(request)
                }
            }
            return
        }
        animator?.cancel()
        imageLoadHandle?.close()
        openRequest = request
        transitionProgress = 0f
        selectedIndex = request.selectedIndex.coerceIn(0, request.items.lastIndex.coerceAtLeast(0))
        pageBitmaps.clear()
        request.items.forEach { item ->
            imageLoader.cachedImage(item.imageInfo)?.let { cached ->
                pageBitmaps[item.id] = cached
            }
        }
        val currentItem = currentItem()
        bitmap = pageBitmaps[currentItem.id]
        bitmapItemId = currentItem.id.takeIf { bitmap != null }
        imageScale = 1f
        panX = 0f
        panY = 0f
        dragDismissX = 0f
        dragDismissY = 0f
        pageOffsetX = 0f
        dismissProgress = 0f
        isClosing = false
        isDraggingToDismiss = false
        isPanningImage = false
        isPagingImage = false
        pendingPageTargetIndex = null
        visibility = VISIBLE
        alpha = 1f
        bringToFront()
        requestFocus()
        updateSourceAndTargetRects()
        loadCurrentImage()
        updateSourceAndTargetRects()
        animateTransition(
            from = 0f,
            to = 1f,
            durationMs = OPEN_DURATION_MS,
            endAction = {
                updateSourceAndTargetRects()
            }
        )
    }

    fun close(animated: Boolean = true) {
        if (openRequest == null || isClosing) {
            return
        }
        pendingPageTargetIndex?.let { targetIndex ->
            animator?.cancel()
            commitPageChange(targetIndex)
        }
        animator?.cancel()
        val closeStartRect = RectF(currentImageRect())
        isClosing = true
        updateCloseSourceRect(closeStartRect)
        if (!animated) {
            cleanup()
            onDismissed()
            return
        }
        animateTransition(
            from = transitionProgress,
            to = 0f,
            durationMs = CLOSE_DURATION_MS,
            endAction = {
                cleanup()
                onDismissed()
            }
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateSourceAndTargetRects()
        clampPan()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val request = openRequest ?: return
        if (request.items.isEmpty() || width <= 0 || height <= 0) {
            return
        }

        val dimAlpha = ((MAX_DIM_ALPHA * transitionProgress) * (1f - dismissProgress)).roundToInt()
            .coerceIn(0, MAX_DIM_ALPHA)
        dimPaint.color = Color.argb(dimAlpha, 0, 0, 0)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        val cornerRadius = request.sourceCornerRadiusPx * (1f - transitionProgress)
        if (shouldDrawPageStrip()) {
            drawPagedPhoto(canvas, selectedIndex, pageOffsetX)
            if (pageOffsetX > 0f && selectedIndex > 0) {
                drawPagedPhoto(canvas, selectedIndex - 1, pageOffsetX - width.toFloat())
            } else if (pageOffsetX < 0f && selectedIndex < request.items.lastIndex) {
                drawPagedPhoto(canvas, selectedIndex + 1, pageOffsetX + width.toFloat())
            }
        } else {
            drawPhoto(canvas, selectedIndex, currentImageRect(), cornerRadius)
        }
        drawChrome(canvas, request)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (openRequest == null || isClosing) {
            return false
        }
        if (transitionProgress < 1f) {
            return true
        }
        scaleDetector.onTouchEvent(event)
        velocityTracker = (velocityTracker ?: VelocityTracker.obtain()).also {
            it.addMovement(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                settleInterruptedAnimationForNewGesture()
                activePointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                isDraggingToDismiss = false
                isPanningImage = false
                isPagingImage = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                isDraggingToDismiss = false
                isPanningImage = false
                isPagingImage = false
                pageOffsetX = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex < 0 || scaleDetector.isInProgress) {
                    return true
                }
                val x = event.getX(pointerIndex)
                val y = event.getY(pointerIndex)
                val dx = x - lastX
                val dy = y - lastY
                val totalDx = x - downX
                val totalDy = y - downY

                if (!isDraggingToDismiss && !isPanningImage && !isPagingImage) {
                    if (imageScale > 1.01f && (abs(totalDx) > touchSlop || abs(totalDy) > touchSlop)) {
                        isPanningImage = true
                    } else if (
                        canPage() &&
                        abs(totalDx) > touchSlop &&
                        abs(totalDx) > abs(totalDy) * PAGE_DIRECTION_BIAS
                    ) {
                        isPagingImage = true
                    } else if (
                        abs(totalDy) > touchSlop &&
                        abs(totalDy) > abs(totalDx) * DISMISS_DIRECTION_BIAS
                    ) {
                        isDraggingToDismiss = true
                    }
                }

                if (isPanningImage) {
                    panX += dx
                    panY += dy
                    clampPan()
                } else if (isPagingImage) {
                    applyPageDrag(dx)
                } else if (isDraggingToDismiss) {
                    dragDismissX += dx * DISMISS_X_FOLLOW
                    dragDismissY += dy
                    dismissProgress = (abs(dragDismissY) / height.coerceAtLeast(1).toFloat())
                        .coerceIn(0f, 1f)
                }
                lastX = x
                lastY = y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val tracker = velocityTracker
                tracker?.computeCurrentVelocity(1000)
                val velocityY = tracker
                    ?.getYVelocity(activePointerId)
                    ?.takeIf { abs(it) >= minimumFlingVelocity }
                    ?: 0f
                val velocityX = tracker
                    ?.getXVelocity(activePointerId)
                    ?.takeIf { abs(it) >= minimumFlingVelocity }
                    ?: 0f
                val didHandleTap = !isPagingImage && handleTap(event)
                if (didHandleTap) {
                    performClick()
                } else {
                    finishGesture(velocityX, velocityY)
                }
                releaseVelocityTracker()
                activePointerId = MotionEvent.INVALID_POINTER_ID
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isPagingImage) {
                    animatePageOffset(
                        to = 0f,
                        durationMs = PAGE_ANIMATION_DURATION_MS,
                        endAction = {
                            isPagingImage = false
                        }
                    )
                } else {
                    animateDismissDragBack()
                }
                releaseVelocityTracker()
                activePointerId = MotionEvent.INVALID_POINTER_ID
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            close(animated = true)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleTap(event: MotionEvent): Boolean {
        if (
            isDraggingToDismiss ||
            isPanningImage ||
            isPagingImage ||
            abs(event.x - downX) > touchSlop ||
            abs(event.y - downY) > touchSlop
        ) {
            return false
        }

        if (closeButtonBounds().contains(event.x, event.y)) {
            close(animated = true)
            return true
        }

        val now = event.eventTime
        val isDoubleTap = now - lastTapUptimeMs <= DOUBLE_TAP_TIMEOUT_MS &&
            abs(event.x - lastTapX) <= DOUBLE_TAP_SLOP_DP.dpToPx(density) &&
            abs(event.y - lastTapY) <= DOUBLE_TAP_SLOP_DP.dpToPx(density)
        lastTapUptimeMs = now
        lastTapX = event.x
        lastTapY = event.y
        if (isDoubleTap) {
            if (imageScale > 1.01f) {
                animateTransformTo(scale = 1f, panX = 0f, panY = 0f)
            } else {
                val targetScale = DOUBLE_TAP_SCALE.coerceAtMost(MAX_IMAGE_SCALE)
                val centerX = width / 2f
                val centerY = height / 2f
                val nextPanX = (centerX - event.x) * (targetScale - 1f)
                val nextPanY = (centerY - event.y) * (targetScale - 1f)
                animateTransformTo(scale = targetScale, panX = nextPanX, panY = nextPanY)
            }
            return true
        }

        if (!currentImageRect().contains(event.x, event.y)) {
            close(animated = true)
            return true
        }
        return true
    }

    private fun finishGesture(velocityX: Float, velocityY: Float) {
        if (isPagingImage) {
            finishPageGesture(velocityX)
            return
        }
        if (isDraggingToDismiss) {
            val shouldDismiss = abs(dragDismissY) > height * DISMISS_DISTANCE_FRACTION ||
                abs(velocityY) > DISMISS_VELOCITY_PX_PER_SECOND
            if (shouldDismiss) {
                close(animated = true)
            } else {
                animateDismissDragBack()
            }
            return
        }
        if (isPanningImage) {
            clampPan()
            invalidate()
        }
    }

    private fun canPage(): Boolean {
        return (openRequest?.items?.size ?: 0) > 1 && transitionProgress >= 1f && !isClosing
    }

    private fun applyPageDrag(dx: Float) {
        val proposedOffset = pageOffsetX + dx
        val hasPrevious = selectedIndex > 0
        val hasNext = selectedIndex < (openRequest?.items?.lastIndex ?: 0)
        pageOffsetX = when {
            proposedOffset > 0f && !hasPrevious -> pageOffsetX + dx * PAGE_EDGE_RESISTANCE
            proposedOffset < 0f && !hasNext -> pageOffsetX + dx * PAGE_EDGE_RESISTANCE
            else -> proposedOffset
        }.coerceIn(-width.toFloat(), width.toFloat())
    }

    private fun finishPageGesture(velocityX: Float) {
        val pageWidth = width.coerceAtLeast(1).toFloat()
        val canGoPrevious = selectedIndex > 0
        val canGoNext = selectedIndex < (openRequest?.items?.lastIndex ?: 0)
        val shouldGoNext = canGoNext && (
            pageOffsetX < -pageWidth * PAGE_SWITCH_FRACTION ||
                velocityX < -PAGE_FLING_VELOCITY_PX_PER_SECOND
            )
        val shouldGoPrevious = canGoPrevious && (
            pageOffsetX > pageWidth * PAGE_SWITCH_FRACTION ||
                velocityX > PAGE_FLING_VELOCITY_PX_PER_SECOND
            )

        when {
            shouldGoNext -> animatePageChange(
                targetIndex = selectedIndex + 1,
                targetOffset = -pageWidth
            )
            shouldGoPrevious -> animatePageChange(
                targetIndex = selectedIndex - 1,
                targetOffset = pageWidth
            )
            else -> animatePageOffset(
                to = 0f,
                durationMs = PAGE_ANIMATION_DURATION_MS,
                endAction = {
                    isPagingImage = false
                }
            )
        }
    }

    private fun animatePageChange(targetIndex: Int, targetOffset: Float) {
        pendingPageTargetIndex = targetIndex
        animatePageOffset(
            to = targetOffset,
            durationMs = PAGE_ANIMATION_DURATION_MS,
            endAction = {
                commitPageChange(targetIndex)
            }
        )
    }

    private fun settleInterruptedAnimationForNewGesture() {
        val targetIndex = pendingPageTargetIndex
        if (targetIndex != null) {
            animator?.cancel()
            commitPageChange(targetIndex)
            return
        }
        animator?.cancel()
        if (isPagingImage || pageOffsetX != 0f) {
            pageOffsetX = 0f
            isPagingImage = false
            invalidate()
        }
        if (
            isDraggingToDismiss ||
            dragDismissX != 0f ||
            dragDismissY != 0f ||
            dismissProgress != 0f
        ) {
            dragDismissX = 0f
            dragDismissY = 0f
            dismissProgress = 0f
            isDraggingToDismiss = false
            invalidate()
        }
    }

    private fun commitPageChange(targetIndex: Int) {
        pendingPageTargetIndex = null
        selectedIndex = targetIndex.coerceIn(0, openRequest?.items?.lastIndex ?: 0)
        pageOffsetX = 0f
        isPagingImage = false
        resetImageTransform()
        adoptCurrentBitmapFromCache()
        updateSourceAndTargetRects()
        loadCurrentImage()
        invalidate()
    }

    private fun animatePageOffset(
        to: Float,
        durationMs: Long,
        endAction: (() -> Unit)?
    ) {
        val startOffset = pageOffsetX
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            var wasCanceled = false
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                pageOffsetX = lerp(startOffset, to, progress)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    wasCanceled = true
                    animator = null
                }

                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    if (!wasCanceled) {
                        pageOffsetX = to
                        endAction?.invoke()
                    }
                }
            })
            start()
        }
    }

    private fun loadCurrentImage() {
        imageLoadHandle?.close()
        val item = currentItem()
        val targetWidth = viewerDecodeTargetWidth()
        val targetHeight = viewerDecodeTargetHeight()
        imageLoader.cachedImage(
            imageInfo = item.imageInfo,
            targetWidthPx = targetWidth,
            targetHeightPx = targetHeight,
            quality = MatrixMediaImageQuality.VIEWER
        )?.let { cached ->
            bitmap = cached
            bitmapItemId = item.id
            pageBitmaps[item.id] = cached
            invalidate()
            return
        }
        imageLoadHandle = imageLoader.loadImage(
            imageInfo = item.imageInfo,
            targetWidthPx = targetWidth,
            targetHeightPx = targetHeight,
            quality = MatrixMediaImageQuality.VIEWER
        ) { loaded ->
            if (openRequest != null && currentItem().id == item.id && loaded != null) {
                bitmap = loaded
                bitmapItemId = item.id
                pageBitmaps[item.id] = loaded
                if (transitionProgress >= 1f && !isClosing) {
                    updateSourceAndTargetRects()
                }
                invalidate()
            }
        }
    }

    private fun adoptCurrentBitmapFromCache() {
        val item = currentItem()
        val cached = pageBitmaps[item.id] ?: imageLoader.cachedImage(item.imageInfo)
        bitmap = cached
        bitmapItemId = item.id.takeIf { cached != null }
        if (cached != null) {
            pageBitmaps[item.id] = cached
        }
    }

    private fun shouldDrawPageStrip(): Boolean {
        return pageOffsetX != 0f && transitionProgress >= 1f && !isClosing
    }

    private fun drawPagedPhoto(canvas: Canvas, index: Int, offsetX: Float) {
        pageDrawRect.set(fitRectForIndex(index))
        pageDrawRect.offset(offsetX, 0f)
        drawPhoto(canvas, index, pageDrawRect, cornerRadius = 0f)
    }

    private fun drawPhoto(canvas: Canvas, index: Int, rect: RectF, cornerRadius: Float) {
        val bitmap = bitmapForIndex(index)
        if (cornerRadius > 0f) {
            clipPath.reset()
            clipPath.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
            val save = canvas.save()
            canvas.clipPath(clipPath)
            drawPhotoContent(canvas, bitmap, rect)
            canvas.restoreToCount(save)
        } else {
            drawPhotoContent(canvas, bitmap, rect)
        }
    }

    private fun drawPhotoContent(canvas: Canvas, bitmap: Bitmap?, rect: RectF) {
        if (bitmap != null && !bitmap.isRecycled) {
            srcRect.setCenterCrop(
                bitmap = bitmap,
                targetWidth = rect.width().roundToInt().coerceAtLeast(1),
                targetHeight = rect.height().roundToInt().coerceAtLeast(1)
            )
            canvas.drawRect(rect, placeholderPaint())
            canvas.drawBitmap(bitmap, srcRect, rect, bitmapPaint)
        } else {
            canvas.drawRect(rect, placeholderPaint())
        }
    }

    private fun drawChrome(canvas: Canvas, request: PhotoViewerOpenRequest) {
        if (transitionProgress < 1f || isClosing) {
            return
        }
        val alpha = ((1f - dismissProgress) * 255f).roundToInt().coerceIn(0, 255)
        if (alpha <= 0) {
            return
        }

        val closeRect = closeButtonBounds()
        chromeScrimPaint.color = Color.argb((alpha * CHROME_SCRIM_ALPHA).roundToInt(), 0, 0, 0)
        canvas.drawCircle(
            closeRect.centerX(),
            closeRect.centerY(),
            closeRect.width() / 2f,
            chromeScrimPaint
        )

        chromeIconPaint.alpha = alpha
        val iconInset = 15.dpToPx(density).toFloat()
        canvas.drawLine(
            closeRect.left + iconInset,
            closeRect.top + iconInset,
            closeRect.right - iconInset,
            closeRect.bottom - iconInset,
            chromeIconPaint
        )
        canvas.drawLine(
            closeRect.right - iconInset,
            closeRect.top + iconInset,
            closeRect.left + iconInset,
            closeRect.bottom - iconInset,
            chromeIconPaint
        )

        if (request.items.size > 1) {
            val label = "${selectedIndex + 1} / ${request.items.size}"
            chromeTextPaint.alpha = alpha
            val horizontalPadding = 12.dpToPx(density).toFloat()
            val labelHeight = 32.dpToPx(density).toFloat()
            val top = CHROME_TOP_DP.dpToPx(density).toFloat()
            val labelWidth = chromeTextPaint.measureText(label) + horizontalPadding * 2f
            pageLabelRect.set(
                width / 2f - labelWidth / 2f,
                top,
                width / 2f + labelWidth / 2f,
                top + labelHeight
            )
            canvas.drawRoundRect(
                pageLabelRect,
                pageLabelRect.height() / 2f,
                pageLabelRect.height() / 2f,
                chromeScrimPaint
            )
            val fontMetrics = chromeTextPaint.fontMetrics
            val baseline = pageLabelRect.centerY() - (fontMetrics.ascent + fontMetrics.descent) / 2f
            canvas.drawText(label, pageLabelRect.centerX(), baseline, chromeTextPaint)
        }
    }

    private fun closeButtonBounds(): RectF {
        val side = CHROME_BUTTON_SIZE_DP.dpToPx(density).toFloat()
        val left = CHROME_SIDE_DP.dpToPx(density).toFloat()
        val top = CHROME_TOP_DP.dpToPx(density).toFloat()
        closeButtonRect.set(left, top, left + side, top + side)
        return closeButtonRect
    }

    private fun bitmapForIndex(index: Int): Bitmap? {
        val request = openRequest ?: return null
        val item = request.items.getOrNull(index) ?: return null
        if (index == selectedIndex && bitmapItemId == item.id) {
            bitmap?.takeIf { !it.isRecycled }?.let { return it }
        }
        pageBitmaps[item.id]?.takeIf { !it.isRecycled }?.let { return it }
        return imageLoader.cachedImage(item.imageInfo)
            ?.takeIf { !it.isRecycled }
            ?.also { pageBitmaps[item.id] = it }
    }

    private fun currentItem(): PhotoViewerItem {
        val request = openRequest ?: error("Photo viewer is not open")
        return request.items[selectedIndex.coerceIn(0, request.items.lastIndex)]
    }

    private fun currentImageRect(): RectF {
        val base = if (isClosing || transitionProgress < 1f) {
            lerp(sourceRect, targetRect, transitionProgress)
        } else {
            RectF(targetRect)
        }
        if (transitionProgress >= 1f && !isClosing) {
            val dismissScale = (1f - dismissProgress * DISMISS_SCALE_REDUCTION)
                .coerceIn(1f - DISMISS_SCALE_REDUCTION, 1f)
            base.scaleAboutCenter(imageScale * dismissScale)
            base.offset(panX + dragDismissX, panY + dragDismissY)
        }
        drawRect.set(base)
        return drawRect
    }

    private fun updateSourceAndTargetRects() {
        val request = openRequest ?: return
        screenRectToLocal(request.sourceBoundsInScreen, sourceRect)
        targetRect.set(fitRectForCurrentItem())
        invalidate()
    }

    private fun updateCloseSourceRect(closeStartRect: RectF) {
        val request = openRequest ?: return
        val currentItemId = currentItem().id
        val latestSource = request.source
            .takeIf { it.isPhotoViewerSourceAvailable }
            ?.photoSourceBoundsInScreen(currentItemId)
            ?: request.sourceBoundsInScreen
        screenRectToLocal(latestSource, sourceRect)
        targetRect.set(closeStartRect)
        imageScale = 1f
        panX = 0f
        panY = 0f
        dragDismissX = 0f
        dragDismissY = 0f
    }

    private fun resetImageTransform() {
        imageScale = 1f
        panX = 0f
        panY = 0f
        dragDismissX = 0f
        dragDismissY = 0f
        dismissProgress = 0f
    }

    private fun fitRectForCurrentItem(): RectF {
        return fitRectForIndex(selectedIndex)
    }

    private fun fitRectForIndex(index: Int): RectF {
        val request = openRequest
        val item = request?.items?.getOrNull(index) ?: currentItem()
        val bitmap = bitmapForIndex(index)
        val imageWidth = item.imageInfo.width?.takeIf { it > 0 } ?: bitmap?.width ?: 1
        val imageHeight = item.imageInfo.height?.takeIf { it > 0 } ?: bitmap?.height ?: 1
        val viewportWidth = width.coerceAtLeast(1).toFloat()
        val viewportHeight = height.coerceAtLeast(1).toFloat()
        val imageAspect = imageWidth.toFloat() / imageHeight.coerceAtLeast(1).toFloat()
        val viewportAspect = viewportWidth / viewportHeight
        val drawWidth: Float
        val drawHeight: Float
        if (imageAspect > viewportAspect) {
            drawWidth = viewportWidth
            drawHeight = viewportWidth / imageAspect
        } else {
            drawHeight = viewportHeight
            drawWidth = viewportHeight * imageAspect
        }
        val left = (viewportWidth - drawWidth) / 2f
        val top = (viewportHeight - drawHeight) / 2f
        return RectF(left, top, left + drawWidth, top + drawHeight)
    }

    private fun screenRectToLocal(screenRect: RectF, out: RectF) {
        getLocationOnScreen(layerLocation)
        out.set(screenRect)
        out.offset(-layerLocation[0].toFloat(), -layerLocation[1].toFloat())
    }

    private fun animateTransition(
        from: Float,
        to: Float,
        durationMs: Long,
        endAction: (() -> Unit)?
    ) {
        animator?.cancel()
        transitionProgress = from
        animator = ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator(TRANSITION_DECELERATION)
            addUpdateListener { animation ->
                transitionProgress = animation.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    animator = null
                }

                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    transitionProgress = to
                    endAction?.invoke()
                }
            })
            start()
        }
    }

    private fun animateDismissDragBack() {
        val startX = dragDismissX
        val startY = dragDismissY
        val startDismissProgress = dismissProgress
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DRAG_BACK_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                dragDismissX = lerp(startX, 0f, progress)
                dragDismissY = lerp(startY, 0f, progress)
                dismissProgress = lerp(startDismissProgress, 0f, progress)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    isDraggingToDismiss = false
                }
            })
            start()
        }
    }

    private fun animateTransformTo(scale: Float, panX: Float, panY: Float) {
        val startScale = imageScale
        val startPanX = this.panX
        val startPanY = this.panY
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ZOOM_ANIMATION_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                imageScale = lerp(startScale, scale, progress).coerceIn(1f, MAX_IMAGE_SCALE)
                this@PhotoViewerLayer.panX = lerp(startPanX, panX, progress)
                this@PhotoViewerLayer.panY = lerp(startPanY, panY, progress)
                clampPan()
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                }
            })
            start()
        }
    }

    private fun clampPan() {
        if (imageScale <= 1.01f || width <= 0 || height <= 0) {
            panX = 0f
            panY = 0f
            return
        }
        val base = targetRect.takeIf { !it.isEmpty } ?: fitRectForCurrentItem()
        val scaledWidth = base.width() * imageScale
        val scaledHeight = base.height() * imageScale
        val maxPanX = ((scaledWidth - width) / 2f).coerceAtLeast(0f)
        val maxPanY = ((scaledHeight - height) / 2f).coerceAtLeast(0f)
        panX = panX.coerceIn(-maxPanX, maxPanX)
        panY = panY.coerceIn(-maxPanY, maxPanY)
    }

    private fun viewerDecodeTargetWidth(): Int {
        val baseRect = targetRect.takeIf { !it.isEmpty } ?: fitRectForCurrentItem()
        return (baseRect.width() * VIEWER_DECODE_SCALE)
            .roundToInt()
            .coerceIn(1, MAX_VIEWER_DECODE_EDGE_PX)
    }

    private fun viewerDecodeTargetHeight(): Int {
        val baseRect = targetRect.takeIf { !it.isEmpty } ?: fitRectForCurrentItem()
        return (baseRect.height() * VIEWER_DECODE_SCALE)
            .roundToInt()
            .coerceIn(1, MAX_VIEWER_DECODE_EDGE_PX)
    }

    private fun cleanup() {
        animator?.cancel()
        animator = null
        imageLoadHandle?.close()
        imageLoadHandle = null
        releaseVelocityTracker()
        openRequest = null
        bitmap = null
        bitmapItemId = null
        pageBitmaps.clear()
        transitionProgress = 0f
        dismissProgress = 0f
        dragDismissX = 0f
        dragDismissY = 0f
        pageOffsetX = 0f
        panX = 0f
        panY = 0f
        imageScale = 1f
        isClosing = false
        isDraggingToDismiss = false
        isPanningImage = false
        isPagingImage = false
        pendingPageTargetIndex = null
        visibility = GONE
    }

    private fun releaseVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun placeholderPaint(): Paint {
        return PLACEHOLDER_PAINT
    }

    private fun RectF.scaleAboutCenter(scale: Float) {
        val centerX = centerX()
        val centerY = centerY()
        val halfWidth = width() * scale / 2f
        val halfHeight = height() * scale / 2f
        set(centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight)
    }

    private fun Rect.setCenterCrop(
        bitmap: Bitmap,
        targetWidth: Int,
        targetHeight: Int
    ) {
        val bitmapWidth = bitmap.width.coerceAtLeast(1)
        val bitmapHeight = bitmap.height.coerceAtLeast(1)
        val targetAspect = targetWidth.toFloat() / targetHeight.coerceAtLeast(1).toFloat()
        val bitmapAspect = bitmapWidth.toFloat() / bitmapHeight.toFloat()

        if (bitmapAspect > targetAspect) {
            val cropWidth = (bitmapHeight * targetAspect).roundToInt().coerceAtLeast(1)
            val left = (bitmapWidth - cropWidth) / 2
            set(left, 0, left + cropWidth, bitmapHeight)
        } else {
            val cropHeight = (bitmapWidth / targetAspect).roundToInt().coerceAtLeast(1)
            val top = (bitmapHeight - cropHeight) / 2
            set(0, top, bitmapWidth, top + cropHeight)
        }
    }

    private fun lerp(start: RectF, end: RectF, progress: Float): RectF {
        return RectF(
            lerp(start.left, end.left, progress),
            lerp(start.top, end.top, progress),
            lerp(start.right, end.right, progress),
            lerp(start.bottom, end.bottom, progress)
        )
    }

    private fun lerp(start: Float, end: Float, progress: Float): Float {
        return start + (end - start) * progress.coerceIn(0f, 1f)
    }

    private fun Int.dpToPx(density: Float): Int {
        return (this * density).roundToInt()
    }

    private companion object {
        const val OPEN_DURATION_MS = 260L
        const val CLOSE_DURATION_MS = 220L
        const val DRAG_BACK_DURATION_MS = 180L
        const val PAGE_ANIMATION_DURATION_MS = 220L
        const val ZOOM_ANIMATION_DURATION_MS = 190L
        const val TRANSITION_DECELERATION = 1.35f
        const val MAX_DIM_ALPHA = 238
        const val VIEWER_DECODE_SCALE = 2.5f
        const val MAX_VIEWER_DECODE_EDGE_PX = 4096
        const val MAX_IMAGE_SCALE = 4f
        const val DOUBLE_TAP_SCALE = 2.5f
        const val DOUBLE_TAP_TIMEOUT_MS = 260L
        const val DOUBLE_TAP_SLOP_DP = 42
        const val MIN_ZOOM_RESET_SCALE = 1.05f
        const val DISMISS_DISTANCE_FRACTION = 0.18f
        const val DISMISS_VELOCITY_PX_PER_SECOND = 1200f
        const val DISMISS_SCALE_REDUCTION = 0.34f
        const val DISMISS_DIRECTION_BIAS = 1.15f
        const val DISMISS_X_FOLLOW = 0.32f
        const val PAGE_DIRECTION_BIAS = 1.12f
        const val PAGE_SWITCH_FRACTION = 0.24f
        const val PAGE_FLING_VELOCITY_PX_PER_SECOND = 900f
        const val PAGE_EDGE_RESISTANCE = 0.28f
        const val CHROME_BUTTON_SIZE_DP = 44
        const val CHROME_SIDE_DP = 12
        const val CHROME_TOP_DP = 14
        const val CHROME_SCRIM_ALPHA = 0.42f
        val PLACEHOLDER_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(24, 24, 28)
        }
    }
}
