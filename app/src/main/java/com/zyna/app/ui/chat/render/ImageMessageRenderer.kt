package com.zyna.app.ui.chat.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import com.zyna.app.data.media.MatrixMediaLoader
import com.zyna.app.data.matrix.MatrixImageInfo
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal class ImageMessageRenderer(
    context: Context,
    private val imageLoader: MatrixMediaLoader?
) : MessageContentRenderer {
    private val density = context.resources.displayMetrics.density
    private val senderPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG)
    private val captionPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG)
    private val timePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG)
    private val forwardedPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG)
    private val replySenderPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG)
    private val replyBodyPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG)
    private val replyBarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val placeholderTextPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val imagePath = Path()
    private val imageSrcRect = Rect()
    private val imageDstRect = RectF()
    private val timeLineSpacing = 5.imageDpToPx(density)
    private val captionTopSpacing = 7.imageDpToPx(density)
    private val headerBottomSpacing = 6.imageDpToPx(density)
    private val senderBottomSpacing = 4.imageDpToPx(density)
    private val replyBarWidth = 2.imageDpToPx(density)
    private val replyHorizontalSpacing = 6.imageDpToPx(density)
    private val replyLineSpacing = 1.imageDpToPx(density)
    private val replyBottomInset = 4.imageDpToPx(density)
    private val imageCornerRadius = 10.imageDpToPx(density).toFloat()
    private val maxImageWidth = 320.imageDpToPx(density)
    private val minImageHeight = 128.imageDpToPx(density)
    private val maxImageHeight = 390.imageDpToPx(density)

    init {
        senderPaint.textSize = 12.imageSpToPx(context)
        senderPaint.isFakeBoldText = true
        captionPaint.textSize = 15.imageSpToPx(context)
        timePaint.textSize = 11.imageSpToPx(context)
        forwardedPaint.textSize = 12.imageSpToPx(context)
        forwardedPaint.isFakeBoldText = true
        replySenderPaint.textSize = 12.imageSpToPx(context)
        replySenderPaint.isFakeBoldText = true
        replyBodyPaint.textSize = 12.imageSpToPx(context)
        placeholderTextPaint.textSize = 14.imageSpToPx(context)
        placeholderTextPaint.isFakeBoldText = true
        placeholderTextPaint.textAlign = Paint.Align.CENTER
    }

    override fun supports(content: MessageContent): Boolean {
        return content is MessageContent.Image
    }

    override fun measure(
        message: MessageRenderModel,
        theme: MessageRenderTheme,
        maxWidthPx: Int
    ): ImageMessageLayout {
        val content = message.content as MessageContent.Image
        val widthLimit = max(1, maxWidthPx)
        senderPaint.color = theme.metadataColor(message)
        captionPaint.color = theme.textColor(message)
        timePaint.color = theme.metadataColor(message)
        forwardedPaint.color = theme.metadataColor(message)
        replySenderPaint.color = theme.metadataColor(message)
        replyBodyPaint.color = theme.metadataColor(message)
        replyBarPaint.color = theme.metadataColor(message)
        placeholderPaint.color = theme.metadataColor(message).withAlpha(36)
        placeholderTextPaint.color = theme.metadataColor(message).withAlpha(180)

        val forwardedLayout = makeForwardedHeaderLayout(message.forwardedFrom, widthLimit)
        val forwardedHeight = forwardedLayout?.let { it.height + headerBottomSpacing } ?: 0
        val forwardedWidth = forwardedLayout?.measuredLineWidth() ?: 0

        val replyLayout = makeReplyHeaderLayout(message.replyInfo, widthLimit)
        val replyHeight = replyLayout?.let { it.height + headerBottomSpacing } ?: 0
        val replyWidth = replyLayout?.width ?: 0

        val senderLayout = makeSenderLayout(message, widthLimit)
        val senderHeight = senderLayout?.let { it.height + senderBottomSpacing } ?: 0
        val senderWidth = senderLayout?.measuredLineWidth() ?: 0

        val imageSize = imageSize(content, widthLimit)
        val captionLayout = makeCaptionLayout(content.caption, imageSize.width)
        val captionHeight = captionLayout?.let { captionTopSpacing + it.height } ?: 0
        val captionWidth = captionLayout?.maxLineWidth() ?: 0

        val timeLayout = makeLayout(
            text = message.metadataText(),
            paint = timePaint,
            width = imageSize.width
        )
        val timeWidth = timeLayout.measuredLineWidth()
        val timeY = forwardedHeight +
            replyHeight +
            senderHeight +
            imageSize.height +
            captionHeight +
            timeLineSpacing

        val contentWidth = max(
            max(max(senderWidth, imageSize.width), max(forwardedWidth, replyWidth)),
            max(captionWidth, timeWidth)
        ).coerceIn(1, widthLimit)

        return ImageMessageLayout(
            width = contentWidth,
            height = timeY + timeLayout.height,
            forwardedHeaderLayout = forwardedLayout,
            forwardedY = 0,
            replyHeaderLayout = replyLayout,
            replyY = forwardedHeight,
            senderLayout = senderLayout,
            senderY = forwardedHeight + replyHeight,
            imageY = forwardedHeight + replyHeight + senderHeight,
            imageWidth = imageSize.width,
            imageHeight = imageSize.height,
            imageInfo = content.imageInfo,
            captionLayout = captionLayout,
            captionY = forwardedHeight + replyHeight + senderHeight + imageSize.height + captionTopSpacing,
            timeLayout = timeLayout,
            timeX = contentWidth - timeWidth,
            timeY = timeY
        )
    }

    override fun draw(canvas: Canvas, layout: MessageContentLayout) {
        layout as ImageMessageLayout

        layout.forwardedHeaderLayout?.let { forwarded ->
            val save = canvas.save()
            canvas.translate(0f, layout.forwardedY.toFloat())
            forwarded.draw(canvas)
            canvas.restoreToCount(save)
        }

        layout.replyHeaderLayout?.let { reply ->
            val save = canvas.save()
            canvas.translate(0f, layout.replyY.toFloat())
            drawReplyHeader(canvas, reply)
            canvas.restoreToCount(save)
        }

        layout.senderLayout?.let { sender ->
            val save = canvas.save()
            canvas.translate(0f, layout.senderY.toFloat())
            sender.draw(canvas)
            canvas.restoreToCount(save)
        }

        drawImage(canvas, layout)

        layout.captionLayout?.let { caption ->
            val save = canvas.save()
            canvas.translate(0f, layout.captionY.toFloat())
            caption.draw(canvas)
            canvas.restoreToCount(save)
        }

        val timeSave = canvas.save()
        canvas.translate(layout.timeX.toFloat(), layout.timeY.toFloat())
        layout.timeLayout.draw(canvas)
        canvas.restoreToCount(timeSave)
    }

    private fun drawImage(canvas: Canvas, layout: ImageMessageLayout) {
        imageDstRect.set(
            0f,
            layout.imageY.toFloat(),
            layout.imageWidth.toFloat(),
            (layout.imageY + layout.imageHeight).toFloat()
        )
        imagePath.reset()
        imagePath.addRoundRect(
            imageDstRect,
            imageCornerRadius,
            imageCornerRadius,
            Path.Direction.CW
        )

        val save = canvas.save()
        canvas.clipPath(imagePath)
        canvas.drawRect(imageDstRect, placeholderPaint)
        val bitmap = imageLoader?.cachedImage(layout.imageInfo)
        if (bitmap != null && !bitmap.isRecycled) {
            imageSrcRect.setCenterCrop(
                bitmap = bitmap,
                targetWidth = layout.imageWidth,
                targetHeight = layout.imageHeight
            )
            canvas.drawBitmap(bitmap, imageSrcRect, imageDstRect, bitmapPaint)
        } else {
            val baseline = imageDstRect.centerY() -
                (placeholderTextPaint.descent() + placeholderTextPaint.ascent()) / 2f
            canvas.drawText("Photo", imageDstRect.centerX(), baseline, placeholderTextPaint)
        }
        canvas.restoreToCount(save)
    }

    private fun imageSize(content: MessageContent.Image, maxWidth: Int): ImageSize {
        val imageWidth = min(maxWidth, maxImageWidth).coerceAtLeast(1)
        val sourceWidth = content.imageInfo.width?.takeIf { it > 0 } ?: 4
        val sourceHeight = content.imageInfo.height?.takeIf { it > 0 } ?: 3
        val aspect = (sourceHeight.toFloat() / sourceWidth.toFloat()).coerceIn(0.45f, 2.1f)
        val imageHeight = (imageWidth * aspect)
            .roundToInt()
            .coerceIn(min(minImageHeight, maxImageHeight), maxImageHeight)
        return ImageSize(width = imageWidth, height = imageHeight)
    }

    private fun makeCaptionLayout(caption: String?, maxWidth: Int): StaticLayout? {
        val text = caption?.takeIf { it.isNotBlank() } ?: return null
        return makeLayout(
            text = text,
            paint = captionPaint,
            width = maxWidth
        )
    }

    private fun makeForwardedHeaderLayout(
        forwardedFrom: String?,
        maxWidth: Int
    ): StaticLayout? {
        val sender = forwardedFrom?.takeIf { it.isNotBlank() } ?: return null
        return makeSingleLineLayout(
            text = "Forwarded from $sender",
            paint = forwardedPaint,
            maxWidth = maxWidth
        )
    }

    private fun makeReplyHeaderLayout(
        replyInfo: MessageReplyPreview?,
        maxWidth: Int
    ): ReplyHeaderLayout? {
        replyInfo ?: return null
        val textWidth = (maxWidth - replyBarWidth - replyHorizontalSpacing)
            .coerceAtLeast(1)
        val senderLayout = makeSingleLineLayout(
            text = replyInfo.senderText.ifBlank { "Unknown" },
            paint = replySenderPaint,
            maxWidth = textWidth
        )
        val bodyLayout = makeSingleLineLayout(
            text = replyInfo.body.ifBlank { "Message" },
            paint = replyBodyPaint,
            maxWidth = textWidth
        )
        val width = replyBarWidth +
            replyHorizontalSpacing +
            max(senderLayout.measuredLineWidth(), bodyLayout.measuredLineWidth())
        val height = senderLayout.height + replyLineSpacing + bodyLayout.height + replyBottomInset
        return ReplyHeaderLayout(
            width = width.coerceIn(1, maxWidth),
            height = height,
            senderLayout = senderLayout,
            senderY = 0,
            bodyLayout = bodyLayout,
            bodyY = senderLayout.height + replyLineSpacing
        )
    }

    private fun drawReplyHeader(canvas: Canvas, layout: ReplyHeaderLayout) {
        val contentHeight = (layout.height - replyBottomInset).coerceAtLeast(1)
        val radius = replyBarWidth / 2f
        canvas.drawRoundRect(
            0f,
            0f,
            replyBarWidth.toFloat(),
            contentHeight.toFloat(),
            radius,
            radius,
            replyBarPaint
        )

        val textX = replyBarWidth + replyHorizontalSpacing
        val senderSave = canvas.save()
        canvas.translate(textX.toFloat(), layout.senderY.toFloat())
        layout.senderLayout.draw(canvas)
        canvas.restoreToCount(senderSave)

        val bodySave = canvas.save()
        canvas.translate(textX.toFloat(), layout.bodyY.toFloat())
        layout.bodyLayout.draw(canvas)
        canvas.restoreToCount(bodySave)
    }

    private fun makeSenderLayout(message: MessageRenderModel, maxWidth: Int): StaticLayout? {
        val sender = message.senderText.takeIf { it.isNotBlank() } ?: return null
        return makeSingleLineLayout(
            text = sender,
            paint = senderPaint,
            maxWidth = maxWidth
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

    private fun makeSingleLineLayout(
        text: CharSequence,
        paint: TextPaint,
        maxWidth: Int
    ): StaticLayout {
        val ellipsized = TextUtils.ellipsize(
            text,
            paint,
            maxWidth.toFloat(),
            TextUtils.TruncateAt.END
        )
        val width = ceil(paint.measureText(ellipsized, 0, ellipsized.length).toDouble())
            .toInt()
            .coerceIn(1, maxWidth)
        return makeLayout(
            text = ellipsized,
            paint = paint,
            width = width,
            maxLines = 1,
            ellipsize = TextUtils.TruncateAt.END
        )
    }
}

internal data class ImageMessageLayout(
    override val width: Int,
    override val height: Int,
    val forwardedHeaderLayout: StaticLayout?,
    val forwardedY: Int,
    val replyHeaderLayout: ReplyHeaderLayout?,
    val replyY: Int,
    val senderLayout: StaticLayout?,
    val senderY: Int,
    val imageY: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val imageInfo: MatrixImageInfo,
    val captionLayout: StaticLayout?,
    val captionY: Int,
    val timeLayout: StaticLayout,
    val timeX: Int,
    val timeY: Int
) : MessageContentLayout

private data class ImageSize(
    val width: Int,
    val height: Int
)

private fun MessageRenderModel.metadataText(): String {
    return when (deliveryState) {
        RenderDeliveryState.SENT -> when {
            isEditPending -> "$timestampText - editing"
            isEditFailed -> "$timestampText - edit failed"
            isEdited -> "$timestampText - edited"
            else -> timestampText
        }
        RenderDeliveryState.SENDING -> "$timestampText - sending"
        RenderDeliveryState.FAILED -> "$timestampText - failed"
    }
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

private fun StaticLayout.maxLineWidth(): Int {
    var width = 0f
    for (index in 0 until lineCount) {
        width = max(width, getLineWidth(index))
    }
    return ceil(width.toDouble()).toInt().coerceAtLeast(1)
}

private fun StaticLayout.measuredLineWidth(): Int {
    if (lineCount == 0) {
        return min(width, 1)
    }
    return maxLineWidth().coerceAtMost(width)
}

private fun Int.imageDpToPx(density: Float): Int {
    return (this * density).roundToInt()
}

private fun Int.imageSpToPx(context: Context): Float {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        toFloat(),
        context.resources.displayMetrics
    )
}

private fun Int.withAlpha(alpha: Int): Int {
    return Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(this),
        Color.green(this),
        Color.blue(this)
    )
}
