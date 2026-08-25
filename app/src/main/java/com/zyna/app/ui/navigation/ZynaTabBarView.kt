package com.zyna.app.ui.navigation

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.zyna.app.R
import kotlin.math.min
import kotlin.math.roundToInt

class ZynaTabBarView(context: Context) : LinearLayout(context) {
    enum class Tab(
        val title: String,
        val iconRes: Int
    ) {
        CONTACTS("Contacts", R.drawable.ic_tab_contacts_24),
        CALLS("Calls", R.drawable.ic_tab_calls_24),
        CHATS("Chats", R.drawable.ic_tab_chats_24),
        PROFILE("Profile", R.drawable.ic_tab_profile_24)
    }

    var onTabSelected: (Tab) -> Unit = {}

    private val density = resources.displayMetrics.density
    private var bottomInset = 0
    private var palette = TabBarPalette.from(context)
    private val separatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f
    }
    private val itemViews = Tab.entries.associateWith { tab ->
        TabItemView(context, tab).apply {
            setOnClickListener { onTabSelected(tab) }
        }
    }

    private var selectedTab = Tab.CHATS

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        clipToPadding = false
        clipChildren = false
        setWillNotDraw(false)
        minimumHeight = dp(BASE_HEIGHT_DP)
        elevation = dp(12).toFloat()
        applyPalette()
        setBottomInset(0)

        Tab.entries.forEach { tab ->
            addView(
                itemViews.getValue(tab),
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            )
        }
        setSelectedTab(Tab.CHATS)
    }

    fun setBottomInset(inset: Int) {
        if (bottomInset == inset && paddingBottom == inset + dp(VERTICAL_PADDING_DP)) {
            return
        }
        bottomInset = inset
        setPadding(
            dp(HORIZONTAL_PADDING_DP),
            dp(VERTICAL_PADDING_DP),
            dp(HORIZONTAL_PADDING_DP),
            bottomInset + dp(VERTICAL_PADDING_DP)
        )
    }

    fun setSelectedTab(tab: Tab) {
        selectedTab = tab
        itemViews.forEach { (itemTab, view) ->
            view.setSelectedState(itemTab == selectedTab)
        }
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        isClickable = visibility == View.VISIBLE
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        itemViews.values.forEach { item ->
            item.isEnabled = enabled
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        val nextPalette = TabBarPalette.from(context)
        if (nextPalette != palette) {
            palette = nextPalette
            applyPalette()
        }
    }

    private fun applyPalette() {
        background = GradientDrawable().apply {
            setColor(palette.background)
        }
        separatorPaint.color = palette.separator
        itemViews.values.forEach { item ->
            item.setPalette(palette)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawLine(0f, 0.5f, width.toFloat(), 0.5f, separatorPaint)
    }

    private fun dp(value: Int): Int {
        return (value * density).roundToInt()
    }

    private inner class TabItemView(
        context: Context,
        private val tab: Tab
    ) : View(context) {
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 11.5f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val iconBounds = RectF()
        private val pillBounds = RectF()
        private val iconDrawable = ContextCompat.getDrawable(context, tab.iconRes)?.mutate()
        private val normalTypeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        private val boldTypeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        private var isTabSelected = false
        private var itemPalette = palette

        init {
            isClickable = true
            isFocusable = true
            contentDescription = tab.title
        }

        fun setSelectedState(selected: Boolean) {
            if (isTabSelected == selected) {
                return
            }
            isTabSelected = selected
            isSelected = selected
            invalidate()
        }

        fun setPalette(nextPalette: TabBarPalette) {
            itemPalette = nextPalette
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val availableHeight = height - paddingTop - paddingBottom
            if (width <= 0 || availableHeight <= 0) return

            val centerX = width / 2f
            val contentTop = paddingTop.toFloat()
            val iconSize = min(24f * density, availableHeight * 0.45f)
            val iconTop = contentTop + 7f * density
            iconBounds.set(
                centerX - iconSize / 2f,
                iconTop,
                centerX + iconSize / 2f,
                iconTop + iconSize
            )

            if (isTabSelected) {
                pillBounds.set(
                    centerX - 27f * density,
                    iconBounds.top - 5f * density,
                    centerX + 27f * density,
                    iconBounds.bottom + 5f * density
                )
                pillPaint.color = itemPalette.activeFill
                canvas.drawRoundRect(pillBounds, 18f * density, 18f * density, pillPaint)
            }

            val color = if (isTabSelected) itemPalette.active else itemPalette.inactive
            textPaint.color = color
            textPaint.typeface = if (isTabSelected) boldTypeface else normalTypeface

            iconDrawable?.let { icon ->
                icon.setTint(color)
                icon.setBounds(
                    iconBounds.left.roundToInt(),
                    iconBounds.top.roundToInt(),
                    iconBounds.right.roundToInt(),
                    iconBounds.bottom.roundToInt()
                )
                icon.draw(canvas)
            }
            val textY = height - paddingBottom - 8f * density
            canvas.drawText(tab.title, centerX, textY, textPaint)
        }
    }

    private data class TabBarPalette(
        val background: Int,
        val separator: Int,
        val active: Int,
        val inactive: Int,
        val activeFill: Int
    ) {
        companion object {
            fun from(context: Context): TabBarPalette {
                val isDark = (
                    context.resources.configuration.uiMode and
                        Configuration.UI_MODE_NIGHT_MASK
                    ) == Configuration.UI_MODE_NIGHT_YES
                return if (isDark) {
                    TabBarPalette(
                        background = Color.argb(246, 18, 18, 22),
                        separator = Color.argb(58, 255, 255, 255),
                        active = Color.rgb(234, 221, 255),
                        inactive = Color.argb(174, 202, 196, 208),
                        activeFill = Color.argb(46, 208, 188, 255)
                    )
                } else {
                    TabBarPalette(
                        background = Color.argb(246, 255, 255, 255),
                        separator = Color.argb(28, 0, 0, 0),
                        active = Color.rgb(33, 0, 93),
                        inactive = Color.argb(154, 73, 69, 79),
                        activeFill = Color.argb(22, 33, 0, 93)
                    )
                }
            }
        }
    }

    companion object {
        const val BASE_HEIGHT_DP = 72
        private const val HORIZONTAL_PADDING_DP = 8
        private const val VERTICAL_PADDING_DP = 4
    }
}
