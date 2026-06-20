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
import com.zyna.app.data.messaging.CaptionPlacement
import com.zyna.app.data.messaging.normalizedMessageCaption
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
    private val timeBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val imagePath = Path()
    private val imageRadii = FloatArray(8)
    private val imageSrcRect = Rect()
    private val imageDstRect = RectF()
    private val captionTopInset = 6.imageDpToPx(density)
    private val captionHorizontalInset = 12.imageDpToPx(density)
    private val captionBottomInset = 6.imageDpToPx(density)
    private val headerTopInset = 7.imageDpToPx(density)
    private val headerHorizontalInset = 12.imageDpToPx(density)
    private val forwardedBottomInset = 2.imageDpToPx(density)
    private val replyHeaderBottomInset = 4.imageDpToPx(density)
    private val senderBottomSpacing = 4.imageDpToPx(density)
    private val replyBarWidth = 2.imageDpToPx(density)
    private val replyHorizontalSpacing = 6.imageDpToPx(density)
    private val replyLineSpacing = 1.imageDpToPx(density)
    private val replyBottomInset = 4.imageDpToPx(density)
    private val imageCornerRadius = 10.imageDpToPx(density).toFloat()
    private val timeBadgeHorizontalPadding = 6.imageDpToPx(density)
    private val timeBadgeVerticalPadding = 2.imageDpToPx(density)
    private val timeBadgeInset = 8.imageDpToPx(density)
    private val timeBadgeRadius = 8.imageDpToPx(density).toFloat()
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

    override fun chrome(message: MessageRenderModel): MessageContentChrome {
        return if (message.isBareImage()) {
            MessageContentChrome.BARE
        } else {
            MessageContentChrome.FLUSH_BUBBLE
        }
    }

    override fun measure(
        message: MessageRenderModel,
        theme: MessageRenderTheme,
        maxWidthPx: Int
    ): ImageMessageLayout {
        val content = message.content as MessageContent.Image
        val isBareImage = message.isBareImage()
        val widthLimit = max(1, maxWidthPx)
        senderPaint.color = theme.metadataColor(message)
        captionPaint.color = theme.textColor(message)
        timePaint.color = Color.WHITE
        forwardedPaint.color = theme.metadataColor(message)
        replySenderPaint.color = theme.metadataColor(message)
        replyBodyPaint.color = theme.metadataColor(message)
        replyBarPaint.color = theme.metadataColor(message)
        placeholderPaint.color = theme.metadataColor(message).withAlpha(36)
        placeholderTextPaint.color = theme.metadataColor(message).withAlpha(180)
        timeBadgePaint.color = Color.argb(102, 0, 0, 0)

        val imageSize = imageSize(content, widthLimit)
        val forwardedLayout = makeForwardedHeaderLayout(message.forwardedFrom, imageSize.width)
        val forwardedHeight = forwardedLayout?.let { headerTopInset + it.height + forwardedBottomInset } ?: 0
        val replyLayout = makeReplyHeaderLayout(message.replyInfo, imageSize.width)
        val replyHeight = replyLayout?.let { headerTopInset + it.height + replyHeaderBottomInset } ?: 0
        val captionLayout = makeCaptionLayout(
            caption = content.caption,
            maxWidth = (imageSize.width - captionHorizontalInset * 2).coerceAtLeast(1)
        )
        val captionHeight = captionLayout?.let {
            captionTopInset + it.height + captionBottomInset
        } ?: 0
        val topCaptionHeight = if (content.captionPlacement == CaptionPlacement.TOP) captionHeight else 0
        val imageY = forwardedHeight + replyHeight + topCaptionHeight
        val totalHeight = imageY +
            imageSize.height +
            if (content.captionPlacement == CaptionPlacement.BOTTOM) captionHeight else 0

        val timeLayout = makeLayout(
            text = message.metadataText(),
            paint = timePaint,
            width = imageSize.width
        )
        val timeWidth = timeLayout.measuredLineWidth()
        return ImageMessageLayout(
            width = imageSize.width,
            height = if (isBareImage) imageSize.height else totalHeight,
            isBareImage = isBareImage,
            hasHeader = forwardedLayout != null || replyLayout != null,
            hasCaption = captionLayout != null,
            captionPlacement = content.captionPlacement,
            forwardedHeaderLayout = forwardedLayout,
            forwardedY = headerTopInset,
            replyHeaderLayout = replyLayout,
            replyY = forwardedHeight + headerTopInset,
            senderLayout = null,
            senderY = 0,
            imageY = if (isBareImage) 0 else imageY,
            imageWidth = imageSize.width,
            imageHeight = imageSize.height,
            imageInfo = content.imageInfo,
            captionLayout = captionLayout,
            captionY = if (content.captionPlacement == CaptionPlacement.TOP) {
                forwardedHeight + replyHeight + captionTopInset
            } else {
                imageY + imageSize.height + captionTopInset
            },
            timeLayout = timeLayout,
            timeX = imageSize.width - timeWidth - timeBadgeHorizontalPadding - timeBadgeInset,
            timeY = imageY + imageSize.height - timeLayout.height - timeBadgeVerticalPadding - timeBadgeInset
        )
    }

    override fun draw(canvas: Canvas, layout: MessageContentLayout) {
        layout as ImageMessageLayout

        layout.forwardedHeaderLayout?.let { forwarded ->
            val save = canvas.save()
            canvas.translate(headerHorizontalInset.toFloat(), layout.forwardedY.toFloat())
            forwarded.draw(canvas)
            canvas.restoreToCount(save)
        }

        layout.replyHeaderLayout?.let { reply ->
            val save = canvas.save()
            canvas.translate(headerHorizontalInset.toFloat(), layout.replyY.toFloat())
            drawReplyHeader(canvas, reply)
            canvas.restoreToCount(save)
        }

        if (layout.captionPlacement == CaptionPlacement.TOP) {
            drawCaption(canvas, layout)
        }

        drawImage(canvas, layout)
        drawTimeBadge(canvas, layout)
        if (layout.captionPlacement == CaptionPlacement.BOTTOM) {
            drawCaption(canvas, layout)
        }
    }

    private fun drawCaption(canvas: Canvas, layout: ImageMessageLayout) {
        val caption = layout.captionLayout ?: return
        val save = canvas.save()
        canvas.translate(captionHorizontalInset.toFloat(), layout.captionY.toFloat())
        caption.draw(canvas)
        canvas.restoreToCount(save)
    }

    private fun drawImage(canvas: Canvas, layout: ImageMessageLayout) {
        imageDstRect.set(
            0f,
            layout.imageY.toFloat(),
            layout.imageWidth.toFloat(),
            (layout.imageY + layout.imageHeight).toFloat()
        )
        imagePath.reset()
        setImageRadii(layout)
        imagePath.addRoundRect(imageDstRect, imageRadii, Path.Direction.CW)

        val save = canvas.save()
        canvas.clipPath(imagePath)
        canvas.drawRect(imageDstRect, placeholderPaint)
        val bitmap = imageLoader?.cachedPreviewImage(
            imageInfo = layout.imageInfo,
            targetWidthPx = layout.imageWidth,
            targetHeightPx = layout.imageHeight
        )
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

    private fun setImageRadii(layout: ImageMessageLayout) {
        var topLeft = true
        var topRight = true
        var bottomRight = true
        var bottomLeft = true

        if (layout.hasHeader || (layout.hasCaption && layout.captionPlacement == CaptionPlacement.TOP)) {
            topLeft = false
            topRight = false
        }
        if (layout.hasCaption && layout.captionPlacement == CaptionPlacement.BOTTOM) {
            bottomRight = false
            bottomLeft = false
        }

        fun radius(enabled: Boolean): Float = if (enabled) imageCornerRadius else 0f
        val tl = radius(topLeft)
        val tr = radius(topRight)
        val br = radius(bottomRight)
        val bl = radius(bottomLeft)
        imageRadii[0] = tl
        imageRadii[1] = tl
        imageRadii[2] = tr
        imageRadii[3] = tr
        imageRadii[4] = br
        imageRadii[5] = br
        imageRadii[6] = bl
        imageRadii[7] = bl
    }

    private fun drawTimeBadge(canvas: Canvas, layout: ImageMessageLayout) {
        val timeWidth = layout.timeLayout.measuredLineWidth()
        val badgeLeft = (
            layout.imageWidth -
                timeWidth -
                timeBadgeHorizontalPadding * 2 -
                timeBadgeInset
            ).toFloat().coerceAtLeast(0f)
        val badgeTop = (
            layout.imageY +
                layout.imageHeight -
                layout.timeLayout.height -
                timeBadgeVerticalPadding * 2 -
                timeBadgeInset
            ).toFloat().coerceAtLeast(layout.imageY.toFloat())
        val badgeRight = badgeLeft + timeWidth + timeBadgeHorizontalPadding * 2
        val badgeBottom = badgeTop + layout.timeLayout.height + timeBadgeVerticalPadding * 2
        canvas.drawRoundRect(
            badgeLeft,
            badgeTop,
            badgeRight,
            badgeBottom,
            timeBadgeRadius,
            timeBadgeRadius,
            timeBadgePaint
        )
        val save = canvas.save()
        canvas.translate(
            badgeLeft + timeBadgeHorizontalPadding,
            badgeTop + timeBadgeVerticalPadding
        )
        layout.timeLayout.draw(canvas)
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
        val text = caption.normalizedMessageCaption() ?: return null
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
            maxWidth = (maxWidth - headerHorizontalInset * 2).coerceAtLeast(1)
        )
    }

    private fun makeReplyHeaderLayout(
        replyInfo: MessageReplyPreview?,
        maxWidth: Int
    ): ReplyHeaderLayout? {
        replyInfo ?: return null
        val textWidth = (maxWidth - headerHorizontalInset * 2 - replyBarWidth - replyHorizontalSpacing)
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
    val isBareImage: Boolean,
    val hasHeader: Boolean,
    val hasCaption: Boolean,
    val captionPlacement: CaptionPlacement,
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

private fun MessageRenderModel.isBareImage(): Boolean {
    val image = content as? MessageContent.Image ?: return false
    return replyInfo == null &&
        forwardedFrom.isNullOrBlank() &&
        image.caption.normalizedMessageCaption() == null
}

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
