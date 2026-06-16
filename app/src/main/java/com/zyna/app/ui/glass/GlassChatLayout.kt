package com.zyna.app.ui.glass

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
    private val source = RecyclerViewGlassBackdropSource(recyclerView, palette.background)

    init {
        clipChildren = false
        clipToPadding = false
        setBackgroundColor(palette.background)

        recyclerView.apply {
            clipToPadding = false
            overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
            itemAnimator = null
            layoutManager = LinearLayoutManager(context).apply {
                reverseLayout = true
            }
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
        glassController.invalidateRegions()
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
