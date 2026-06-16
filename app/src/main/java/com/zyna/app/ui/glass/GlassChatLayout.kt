package com.zyna.app.ui.glass

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zyna.app.ui.chat.render.MessageContent
import com.zyna.app.ui.chat.render.MessageContextMenuRequest
import com.zyna.app.ui.chat.render.MessageRenderModel
import kotlin.math.max

/** Chat view shell that gives the list and input glass a shared backdrop source. */
class GlassChatLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val density = resources.displayMetrics.density
    val glassController = GlassBackdropController(this)
    val recyclerView = RecyclerView(context)
    val inputBar = GlassInputBarView(context, glassController)
    var onLoadOlderMessages: () -> Unit = {}

    private val emptyView = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 15f
        includeFontPadding = true
    }
    private val composerErrorView = TextView(context).apply {
        gravity = Gravity.START
        textSize = 12f
        includeFontPadding = true
        visibility = GONE
    }

    private var palette = defaultPalette()
    private var imeBottomInset = 0
    private var navBottomInset = 0
    private var paletteApplied = false
    private var composerErrorMessage: String? = null
    private var isLoadingOlderMessages = false
    private var canLoadOlderMessages = false
    private var prefetchCheckPosted = false
    private var isContextMenuShowing = false
    private var isContextGestureActive = false
    private var recyclerAccessibilityBeforeMenu = IMPORTANT_FOR_ACCESSIBILITY_AUTO
    private val chatLayoutManager = LockableLinearLayoutManager(context).apply {
        reverseLayout = true
    }
    private val source = RecyclerViewGlassBackdropSource(recyclerView, palette.background)
    private val contextMenuLayer = MessageContextMenuLayer(context, glassController).apply {
        onDismissRequested = {
            dismissMessageContextMenu()
        }
        onActionSelected = { message, action ->
            handleMessageContextAction(message, action)
            dismissMessageContextMenu()
        }
    }

    init {
        clipChildren = false
        clipToPadding = false
        setBackgroundColor(palette.background)

        recyclerView.apply {
            clipToPadding = false
            overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
            itemAnimator = null
            layoutManager = chatLayoutManager
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    glassController.invalidateBackdrop()
                    maybeLoadOlderMessages()
                }

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    glassController.invalidateBackdrop()
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        maybeLoadOlderMessages()
                    }
                }
            })
        }

        glassController.source = source
        addView(recyclerView)
        addView(emptyView)
        addView(composerErrorView)
        addView(inputBar)
        addView(contextMenuLayer)

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            if (imeBottomInset != ime || navBottomInset != nav) {
                imeBottomInset = ime
                navBottomInset = nav
                requestLayout()
                glassController.invalidateRegions()
            }
            insets
        }

        setPalette(palette)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    fun setPalette(newPalette: GlassPalette) {
        if (paletteApplied && palette == newPalette) {
            return
        }
        paletteApplied = true
        palette = newPalette
        source.fallbackColor = newPalette.background
        setBackgroundColor(newPalette.background)
        emptyView.setTextColor(newPalette.hint)
        inputBar.setPalette(newPalette)
        contextMenuLayer.setPalette(newPalette)
        glassController.invalidateBackdrop()
    }

    fun setEmptyState(isEmpty: Boolean, isLoading: Boolean) {
        emptyView.text = if (isLoading) "Loading messages" else "No messages"
        emptyView.visibility = if (isEmpty) VISIBLE else GONE
    }

    fun setPaginationState(isLoadingOlder: Boolean, canLoadOlder: Boolean) {
        isLoadingOlderMessages = isLoadingOlder
        canLoadOlderMessages = canLoadOlder
        prefetchOlderMessagesIfNeeded()
    }

    fun setComposerState(isSending: Boolean, errorMessage: String?, errorColor: Int) {
        val normalizedError = errorMessage?.takeIf { it.isNotBlank() }
        inputBar.setSending(
            sending = isSending,
            sendFailed = normalizedError != null
        )

        if (composerErrorMessage == normalizedError && composerErrorView.currentTextColor == errorColor) {
            return
        }

        composerErrorMessage = normalizedError
        composerErrorView.text = normalizedError.orEmpty()
        composerErrorView.setTextColor(errorColor)
        composerErrorView.visibility = if (normalizedError == null) GONE else VISIBLE
        requestLayout()
        glassController.invalidateRegions()
    }

    fun invalidateGlassContent() {
        glassController.invalidateBackdrop()
    }

    internal fun beginMessageContextMenuGesture(request: MessageContextMenuRequest): Boolean {
        isContextGestureActive = true
        setContextScrollLocked(true)
        val didBegin = contextMenuLayer.beginPreview(request)
        if (!didBegin) {
            isContextGestureActive = false
            setContextScrollLocked(false)
            return false
        }
        return true
    }

    internal fun showMessageContextMenu(request: MessageContextMenuRequest): Boolean {
        if (!isContextGestureActive) {
            isContextGestureActive = true
            setContextScrollLocked(true)
        }
        val didShow = contextMenuLayer.show(request)
        if (!didShow) {
            dismissMessageContextMenu(animated = false)
            return false
        }
        if (!isContextMenuShowing) {
            recyclerAccessibilityBeforeMenu = recyclerView.importantForAccessibility
        }
        isContextMenuShowing = true
        recyclerView.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        return true
    }

    internal fun handleMessageContextGestureEvent(action: Int, rawX: Float, rawY: Float) {
        contextMenuLayer.handleGestureEvent(action, rawX, rawY)
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            isContextGestureActive = false
            if (!isContextMenuShowing) {
                contextMenuLayer.dismiss(animated = true)
                setContextScrollLocked(false)
            }
        }
    }

    internal fun dismissMessageContextMenu(animated: Boolean = true) {
        if (!isContextMenuShowing) {
            contextMenuLayer.dismiss(animated = animated)
            if (!isContextGestureActive) {
                setContextScrollLocked(false)
            }
            return
        }
        isContextMenuShowing = false
        recyclerView.importantForAccessibility = recyclerAccessibilityBeforeMenu
        if (!isContextGestureActive) {
            setContextScrollLocked(false)
        }
        contextMenuLayer.dismiss(animated)
    }

    fun prefetchOlderMessagesIfNeeded() {
        if (prefetchCheckPosted) {
            return
        }

        prefetchCheckPosted = true
        post {
            prefetchCheckPosted = false
            maybeLoadOlderMessages()
        }
    }

    private fun maybeLoadOlderMessages() {
        if (isLoadingOlderMessages || !canLoadOlderMessages) {
            return
        }

        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val itemCount = recyclerView.adapter?.itemCount ?: return
        if (itemCount == 0) {
            return
        }
        val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
        val shouldLoadMore =
            itemCount < OLDER_PREFETCH_TARGET_ITEMS || (
                lastVisibleItem != RecyclerView.NO_POSITION &&
                    lastVisibleItem >= itemCount - LOAD_OLDER_THRESHOLD
            )

        if (shouldLoadMore) {
            onLoadOlderMessages()
        }
    }

    fun scrollToBottom(animated: Boolean) {
        val itemCount = recyclerView.adapter?.itemCount ?: 0
        if (itemCount == 0) {
            return
        }
        if (animated) {
            recyclerView.smoothScrollToPosition(0)
        } else {
            recyclerView.scrollToPosition(0)
        }
        glassController.invalidateBackdrop()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        recyclerView.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        inputBar.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(max(1, height / 2), MeasureSpec.AT_MOST)
        )
        emptyView.measure(
            MeasureSpec.makeMeasureSpec((width - 48.dpToPx(density)).coerceAtLeast(0), MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST)
        )
        composerErrorView.measure(
            MeasureSpec.makeMeasureSpec((width - 32.dpToPx(density)).coerceAtLeast(0), MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST)
        )
        contextMenuLayer.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )

        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        recyclerView.layout(0, 0, width, height)

        val bottomInset = if (imeBottomInset > 0) imeBottomInset else navBottomInset
        val bottomMargin = 6.dpToPx(density)
        val inputTop = (height - bottomInset - bottomMargin - inputBar.measuredHeight)
            .coerceAtLeast(0)
        inputBar.layout(0, inputTop, width, inputTop + inputBar.measuredHeight)
        val contentBottom = layoutComposerError(inputTop, width)

        val emptyWidth = emptyView.measuredWidth
        val emptyHeight = emptyView.measuredHeight
        val emptyLeft = (width - emptyWidth) / 2
        val availableBottom = contentBottom.coerceAtLeast(0)
        val emptyTop = ((availableBottom - emptyHeight) / 2).coerceAtLeast(0)
        emptyView.layout(emptyLeft, emptyTop, emptyLeft + emptyWidth, emptyTop + emptyHeight)

        updateRecyclerPadding(contentBottom)
        contextMenuLayer.layout(0, 0, width, height)
        glassController.invalidateRegions()
    }

    private fun handleMessageContextAction(
        message: MessageRenderModel,
        action: MessageContextMenuAction
    ) {
        when (action) {
            MessageContextMenuAction.COPY -> copyMessageText(message)
        }
    }

    private fun copyMessageText(message: MessageRenderModel) {
        val text = when (val content = message.content) {
            is MessageContent.Text -> content.body
        }.takeIf { it.isNotBlank() } ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Message", text))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setContextScrollLocked(locked: Boolean) {
        if (chatLayoutManager.isScrollLocked == locked) {
            return
        }
        chatLayoutManager.isScrollLocked = locked
        if (locked) {
            recyclerView.stopScroll()
        }
    }

    private fun layoutComposerError(inputTop: Int, width: Int): Int {
        if (composerErrorView.visibility != VISIBLE) {
            return inputTop
        }

        val horizontal = 16.dpToPx(density)
        val gap = 4.dpToPx(density)
        val errorBottom = (inputTop - gap).coerceAtLeast(0)
        val errorTop = (errorBottom - composerErrorView.measuredHeight).coerceAtLeast(0)
        composerErrorView.layout(
            horizontal,
            errorTop,
            (width - horizontal).coerceAtLeast(horizontal),
            errorTop + composerErrorView.measuredHeight
        )
        return errorTop
    }

    private fun updateRecyclerPadding(contentBottom: Int) {
        val horizontal = 12.dpToPx(density)
        val top = 12.dpToPx(density)
        val bottom = (height - contentBottom) + 12.dpToPx(density)
        if (
            recyclerView.paddingLeft != horizontal ||
            recyclerView.paddingTop != top ||
            recyclerView.paddingRight != horizontal ||
            recyclerView.paddingBottom != bottom
        ) {
            recyclerView.setPadding(horizontal, top, horizontal, bottom)
        }
    }
}

private class LockableLinearLayoutManager(context: Context) : LinearLayoutManager(context) {
    var isScrollLocked: Boolean = false

    override fun canScrollVertically(): Boolean {
        return !isScrollLocked && super.canScrollVertically()
    }
}

private const val LOAD_OLDER_THRESHOLD = 240
private const val OLDER_PREFETCH_TARGET_ITEMS = 1_000

private fun defaultPalette(): GlassPalette {
    return GlassPalette(
        background = 0xfffbfbff.toInt(),
        glassTint = 0xb8ffffff.toInt(),
        glassTintStrong = 0xd9ffffff.toInt(),
        stroke = 0x24000000,
        text = 0xff15151a.toInt(),
        hint = 0x9915151a.toInt()
    )
}
