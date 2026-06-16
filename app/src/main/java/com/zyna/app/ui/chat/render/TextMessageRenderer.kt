package com.zyna.app.ui.chat.render

import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

internal class TextMessageRenderer(context: Context) : MessageContentRenderer {
    private val density = context.resources.displayMetrics.density
    private val senderPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG)
    private val bodyPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG)
    private val timePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG)
    private val timeSpacing = 6.dpToPx(density)
    private val senderBottomSpacing = 3.dpToPx(density)
    private val timeLineSpacing = 2.dpToPx(density)

    init {
        senderPaint.textSize = 12.spToPx(context)
        senderPaint.isFakeBoldText = true
        bodyPaint.textSize = 16.spToPx(context)
        timePaint.textSize = 11.spToPx(context)
    }

    override fun supports(content: MessageContent): Boolean {
        return content is MessageContent.Text || content is MessageContent.Redacted
    }

    override fun measure(
        message: MessageRenderModel,
        theme: MessageRenderTheme,
        maxWidthPx: Int
    ): TextMessageLayout {
        val widthLimit = max(1, maxWidthPx)
        val isRedacted = message.isRedacted
        senderPaint.color = theme.metadataColor(message)
        bodyPaint.color = if (isRedacted) theme.metadataColor(message) else theme.textColor(message)
        bodyPaint.textSkewX = if (isRedacted) REDACTED_TEXT_SKEW_X else 0f
        timePaint.color = theme.metadataColor(message)

        val senderLayout = makeSenderLayout(message, widthLimit)
        val senderHeight = if (senderLayout == null) 0 else senderLayout.height + senderBottomSpacing
        val senderWidth = senderLayout?.measuredLineWidth() ?: 0

        val bodyText = message.content.renderText().ifEmpty { " " }
        val bodyLayout = makeLayout(
            text = bodyText,
            paint = bodyPaint,
            width = widthLimit
        )
        val bodyWidth = bodyLayout.maxLineWidth()
        val lastLineWidth = bodyLayout.lastLineWidth()

        val timeLayout = makeLayout(
            text = message.metadataText(),
            paint = timePaint,
            width = widthLimit
        )
        val timeWidth = timeLayout.measuredLineWidth()
        val timeRowHeight = timeLayout.height
        val inlineWidth = lastLineWidth + timeSpacing + timeWidth
        val fitsInline = inlineWidth <= widthLimit

        val contentWidth = if (fitsInline) {
            max(max(senderWidth, bodyWidth), inlineWidth)
        } else {
            max(max(senderWidth, bodyWidth), timeWidth)
        }.coerceIn(1, widthLimit)

        val bodyY = senderHeight
        val timeX = contentWidth - timeWidth
        val timeY = if (fitsInline) {
            bodyY + bodyLayout.height - timeRowHeight
        } else {
            bodyY + bodyLayout.height + timeLineSpacing
        }
        val contentHeight = if (fitsInline) {
            senderHeight + bodyLayout.height
        } else {
            senderHeight + bodyLayout.height + timeLineSpacing + timeRowHeight
        }

        return TextMessageLayout(
            width = contentWidth,
            height = contentHeight,
            senderLayout = senderLayout,
            senderY = 0,
            bodyLayout = bodyLayout,
            bodyY = bodyY,
            timeLayout = timeLayout,
            timeX = timeX,
            timeY = timeY
        )
    }

    override fun draw(canvas: Canvas, layout: MessageContentLayout) {
        layout as TextMessageLayout

        layout.senderLayout?.let { sender ->
            val save = canvas.save()
            canvas.translate(0f, layout.senderY.toFloat())
            sender.draw(canvas)
            canvas.restoreToCount(save)
        }

        val bodySave = canvas.save()
        canvas.translate(0f, layout.bodyY.toFloat())
        layout.bodyLayout.draw(canvas)
        canvas.restoreToCount(bodySave)

        val timeSave = canvas.save()
        canvas.translate(layout.timeX.toFloat(), layout.timeY.toFloat())
        layout.timeLayout.draw(canvas)
        canvas.restoreToCount(timeSave)
    }

    private fun makeSenderLayout(message: MessageRenderModel, maxWidth: Int): StaticLayout? {
        val sender = message.senderText.takeIf { it.isNotBlank() } ?: return null
        val ellipsized = TextUtils.ellipsize(
            sender,
            senderPaint,
            maxWidth.toFloat(),
            TextUtils.TruncateAt.END
        )
        val width = ceil(senderPaint.measureText(ellipsized, 0, ellipsized.length).toDouble())
            .toInt()
            .coerceIn(1, maxWidth)
        return makeLayout(
            text = ellipsized,
            paint = senderPaint,
            width = width,
            maxLines = 1,
            ellipsize = TextUtils.TruncateAt.END
        )
    }

    private fun makeLayout(
        text: CharSequence,
        paint: TextPaint,
        width: Int,
        maxLines: Int = Int.MAX_VALUE,
        ellipsize: TextUtils.TruncateAt? = null
    ): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, width))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .setMaxLines(maxLines)
            .setEllipsize(ellipsize)
            .build()
    }
}

internal data class TextMessageLayout(
    override val width: Int,
    override val height: Int,
    val senderLayout: StaticLayout?,
    val senderY: Int,
    val bodyLayout: StaticLayout,
    val bodyY: Int,
    val timeLayout: StaticLayout,
    val timeX: Int,
    val timeY: Int
) : MessageContentLayout

private fun MessageRenderModel.metadataText(): String {
    return when (deliveryState) {
        RenderDeliveryState.SENT -> timestampText
        RenderDeliveryState.SENDING -> "$timestampText - sending"
        RenderDeliveryState.FAILED -> "$timestampText - failed"
    }
}

private fun MessageContent.renderText(): String {
    return when (this) {
        is MessageContent.Text -> body
        MessageContent.Redacted -> REDACTED_MESSAGE_TEXT
    }
}

private fun StaticLayout.maxLineWidth(): Int {
    var width = 0f
    for (index in 0 until lineCount) {
        width = max(width, getLineWidth(index))
    }
    return ceil(width.toDouble()).toInt().coerceAtLeast(1)
}

private fun StaticLayout.lastLineWidth(): Int {
    if (lineCount == 0) {
        return 0
    }
    return ceil(getLineWidth(lineCount - 1).toDouble()).toInt()
}

private fun StaticLayout.measuredLineWidth(): Int {
    if (lineCount == 0) {
        return min(width, 1)
    }
    return maxLineWidth().coerceAtMost(width)
}

private fun Int.spToPx(context: Context): Float {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        toFloat(),
        context.resources.displayMetrics
    )
}

private const val REDACTED_TEXT_SKEW_X = -0.12f
