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
import com.zyna.app.data.messaging.CaptionPlacement
import com.zyna.app.data.messaging.MediaGroupLayoutOverride
import com.zyna.app.data.messaging.normalizedMessageCaption
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal class PhotoGroupMessageRenderer(
    context: Context,
    private val imageLoader: MatrixMediaLoader?
) : MessageContentRenderer {
    private val density = context.resources.displayMetrics.density
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
    private val overflowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val overflowTextPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG)
    private val tilePath = Path()
    private val tileRadii = FloatArray(8)
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val mediaBounds = RectF()

    private val maxMediaWidth = 320.groupDpToPx(density)
    private val maxMediaHeight = 390.groupDpToPx(density)
    private val minMediaHeight = 128.groupDpToPx(density)
    private val mediaSpacing = 2.groupDpToPx(density)
    private val mediaCornerRadius = 10.groupDpToPx(density).toFloat()
    private val captionTopInset = 6.groupDpToPx(density)
    private val captionHorizontalInset = 12.groupDpToPx(density)
    private val captionBottomInset = 6.groupDpToPx(density)
    private val headerTopInset = 7.groupDpToPx(density)
    private val headerHorizontalInset = 12.groupDpToPx(density)
    private val forwardedBottomInset = 2.groupDpToPx(density)
    private val replyBottomInset = 4.groupDpToPx(density)
    private val replyBarWidth = 2.groupDpToPx(density)
    private val replyHorizontalSpacing = 6.groupDpToPx(density)
    private val replyLineSpacing = 1.groupDpToPx(density)
    private val replyContentBottomInset = 4.groupDpToPx(density)
    private val timeBadgeHorizontalPadding = 6.groupDpToPx(density)
    private val timeBadgeVerticalPadding = 2.groupDpToPx(density)
    private val timeBadgeInset = 8.groupDpToPx(density)
    private val timeBadgeRadius = 8.groupDpToPx(density).toFloat()

    init {
        captionPaint.textSize = 14.groupSpToPx(context)
        timePaint.textSize = 11.groupSpToPx(context)
        forwardedPaint.textSize = 12.groupSpToPx(context)
        forwardedPaint.isFakeBoldText = true
        replySenderPaint.textSize = 12.groupSpToPx(context)
        replySenderPaint.isFakeBoldText = true
        replyBodyPaint.textSize = 12.groupSpToPx(context)
        placeholderTextPaint.textSize = 14.groupSpToPx(context)
        placeholderTextPaint.isFakeBoldText = true
        placeholderTextPaint.textAlign = Paint.Align.CENTER
        overflowTextPaint.textSize = 22.groupSpToPx(context)
        overflowTextPaint.isFakeBoldText = true
        overflowTextPaint.textAlign = Paint.Align.CENTER
    }

    override fun supports(content: MessageContent): Boolean {
        return content is MessageContent.PhotoGroup
    }

    override fun chrome(message: MessageRenderModel): MessageContentChrome {
        val content = message.content as? MessageContent.PhotoGroup
        val hasCaption = content?.caption.normalizedMessageCaption() != null
        val hasHeader = message.replyInfo != null || !message.forwardedFrom.isNullOrBlank()
        return if (hasCaption || hasHeader) {
            MessageContentChrome.FLUSH_BUBBLE
        } else {
            MessageContentChrome.BARE
        }
    }

    override fun measure(
        message: MessageRenderModel,
        theme: MessageRenderTheme,
        maxWidthPx: Int
    ): PhotoGroupMessageLayout {
        val content = message.content as MessageContent.PhotoGroup
        val widthLimit = max(1, maxWidthPx)
        captionPaint.color = theme.textColor(message)
        timePaint.color = Color.WHITE
        forwardedPaint.color = theme.metadataColor(message)
        replySenderPaint.color = theme.metadataColor(message)
        replyBodyPaint.color = theme.metadataColor(message)
        replyBarPaint.color = theme.metadataColor(message)
        placeholderPaint.color = theme.metadataColor(message).withGroupAlpha(36)
        placeholderTextPaint.color = theme.metadataColor(message).withGroupAlpha(180)
        timeBadgePaint.color = Color.argb(102, 0, 0, 0)
        overflowPaint.color = Color.argb(97, 0, 0, 0)
        overflowTextPaint.color = Color.WHITE

        val mediaWidth = min(widthLimit, maxMediaWidth).coerceAtLeast(1)
        val mediaHeight = preferredMediaHeight(
            width = mediaWidth,
            itemCount = content.items.size,
            primaryAspectRatio = content.items.firstOrNull()?.imageInfo?.aspectRatio()
        )
        val forwardedLayout = makeForwardedHeaderLayout(message.forwardedFrom, mediaWidth)
        val forwardedHeight = forwardedLayout?.let { headerTopInset + it.height + forwardedBottomInset } ?: 0
        val replyLayout = makeReplyHeaderLayout(message.replyInfo, mediaWidth)
        val replyHeight = replyLayout?.let { headerTopInset + it.height + replyBottomInset } ?: 0
        val captionLayout = makeCaptionLayout(
            caption = content.caption,
            maxWidth = (mediaWidth - captionHorizontalInset * 2).coerceAtLeast(1)
        )
        val captionHeight = captionLayout?.let {
            captionTopInset + it.height + captionBottomInset
        } ?: 0
        val topCaptionHeight = if (content.captionPlacement == CaptionPlacement.TOP) captionHeight else 0
        val mediaY = forwardedHeight + replyHeight + topCaptionHeight
        val totalHeight = mediaY +
            mediaHeight +
            if (content.captionPlacement == CaptionPlacement.BOTTOM) captionHeight else 0

        val timeLayout = makeLayout(
            text = message.metadataText(),
            paint = timePaint,
            width = mediaWidth
        )

        return PhotoGroupMessageLayout(
            width = mediaWidth,
            height = totalHeight,
            items = content.items,
            totalHint = content.totalHint,
            captionPlacement = content.captionPlacement,
            layoutOverride = content.layoutOverride,
            forwardedHeaderLayout = forwardedLayout,
            forwardedY = headerTopInset,
            replyHeaderLayout = replyLayout,
            replyY = forwardedHeight + headerTopInset,
            captionLayout = captionLayout,
            captionY = if (content.captionPlacement == CaptionPlacement.TOP) {
                forwardedHeight + replyHeight + captionTopInset
            } else {
                mediaY + mediaHeight + captionTopInset
            },
            mediaY = mediaY,
            mediaWidth = mediaWidth,
            mediaHeight = mediaHeight,
            hasHeader = forwardedLayout != null || replyLayout != null,
            timeLayout = timeLayout
        )
    }

    override fun draw(canvas: Canvas, layout: MessageContentLayout) {
        layout as PhotoGroupMessageLayout
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
        drawMedia(canvas, layout)
        drawTimeBadge(canvas, layout)
        if (layout.captionPlacement == CaptionPlacement.BOTTOM) {
            drawCaption(canvas, layout)
        }
    }

    private fun drawCaption(canvas: Canvas, layout: PhotoGroupMessageLayout) {
        val caption = layout.captionLayout ?: return
        val save = canvas.save()
        canvas.translate(captionHorizontalInset.toFloat(), layout.captionY.toFloat())
        caption.draw(canvas)
        canvas.restoreToCount(save)
    }

    private fun drawMedia(canvas: Canvas, layout: PhotoGroupMessageLayout) {
        mediaBounds.set(
            0f,
            layout.mediaY.toFloat(),
            layout.mediaWidth.toFloat(),
            (layout.mediaY + layout.mediaHeight).toFloat()
        )
        val frames = PhotoGroupLayout.frames(
            bounds = mediaBounds,
            itemCount = layout.items.size,
            layoutOverride = layout.layoutOverride,
            spacingPx = mediaSpacing
        )
        val visibleCount = PhotoGroupLayout.visibleItemCount(layout.items.size)
        for (index in 0 until visibleCount) {
            val frame = frames.getOrNull(index) ?: continue
            val item = layout.items.getOrNull(index) ?: continue
            val roundedCorners = PhotoGroupLayout.roundedCorners(
                index = index,
                itemCount = layout.items.size,
                hasHeader = layout.hasHeader,
                captionPlacement = if (layout.captionLayout == null) null else layout.captionPlacement
            )
            drawTile(canvas, frame, item.imageInfo, roundedCorners)
        }

        val overflowCount = layout.items.size - visibleCount
        val overflowFrame = frames.lastOrNull()
        if (overflowCount > 0 && overflowFrame != null) {
            canvas.drawRoundRect(
                overflowFrame,
                mediaCornerRadius,
                mediaCornerRadius,
                overflowPaint
            )
            val text = "+$overflowCount"
            val baseline = overflowFrame.centerY() -
                (overflowTextPaint.descent() + overflowTextPaint.ascent()) / 2f
            canvas.drawText(text, overflowFrame.centerX(), baseline, overflowTextPaint)
        }
    }

    private fun drawTile(
        canvas: Canvas,
        frame: RectF,
        imageInfo: com.zyna.app.data.matrix.MatrixImageInfo,
        roundedCorners: PhotoGroupLayout.Corners
    ) {
        tilePath.reset()
        setTileRadii(roundedCorners)
        tilePath.addRoundRect(frame, tileRadii, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(tilePath)
        canvas.drawRect(frame, placeholderPaint)
        val bitmap = imageLoader?.cachedImage(imageInfo)
        if (bitmap != null && !bitmap.isRecycled) {
            srcRect.setCenterCrop(
                bitmap = bitmap,
                targetWidth = frame.width().roundToInt().coerceAtLeast(1),
                targetHeight = frame.height().roundToInt().coerceAtLeast(1)
            )
            dstRect.set(frame)
            canvas.drawBitmap(bitmap, srcRect, dstRect, bitmapPaint)
        } else {
            val baseline = frame.centerY() -
                (placeholderTextPaint.descent() + placeholderTextPaint.ascent()) / 2f
            canvas.drawText("Photo", frame.centerX(), baseline, placeholderTextPaint)
        }
        canvas.restoreToCount(save)
    }

    private fun setTileRadii(corners: PhotoGroupLayout.Corners) {
        fun radius(enabled: Boolean): Float = if (enabled) mediaCornerRadius else 0f
        val topLeft = radius(corners.topLeft)
        val topRight = radius(corners.topRight)
        val bottomRight = radius(corners.bottomRight)
        val bottomLeft = radius(corners.bottomLeft)
        tileRadii[0] = topLeft
        tileRadii[1] = topLeft
        tileRadii[2] = topRight
        tileRadii[3] = topRight
        tileRadii[4] = bottomRight
        tileRadii[5] = bottomRight
        tileRadii[6] = bottomLeft
        tileRadii[7] = bottomLeft
    }

    private fun drawTimeBadge(canvas: Canvas, layout: PhotoGroupMessageLayout) {
        val timeWidth = layout.timeLayout.measuredLineWidth()
        val badgeLeft = (
            layout.mediaWidth -
                timeWidth -
                timeBadgeHorizontalPadding * 2 -
                timeBadgeInset
            ).toFloat().coerceAtLeast(0f)
        val badgeTop = (
            layout.mediaY +
                layout.mediaHeight -
                layout.timeLayout.height -
                timeBadgeVerticalPadding * 2 -
                timeBadgeInset
            ).toFloat().coerceAtLeast(layout.mediaY.toFloat())
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

    private fun makeCaptionLayout(caption: String?, maxWidth: Int): StaticLayout? {
        val text = caption.normalizedMessageCaption() ?: return null
        return makeLayout(text = text, paint = captionPaint, width = maxWidth)
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
        val height = senderLayout.height + replyLineSpacing + bodyLayout.height + replyContentBottomInset
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
        val contentHeight = (layout.height - replyContentBottomInset).coerceAtLeast(1)
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

    private fun preferredMediaHeight(
        width: Int,
        itemCount: Int,
        primaryAspectRatio: Float?
    ): Int {
        val resolvedCount = max(1, itemCount)
        val rawHeight = when (resolvedCount) {
            1 -> if (primaryAspectRatio != null && primaryAspectRatio > 0f) {
                width / primaryAspectRatio
            } else {
                width * 0.78f
            }
            2 -> width * 0.74f
            3 -> width * 0.82f
            else -> width.toFloat()
        }
        return rawHeight.roundToInt().coerceIn(min(minMediaHeight, maxMediaHeight), maxMediaHeight)
    }
}

internal data class PhotoGroupMessageLayout(
    override val width: Int,
    override val height: Int,
    val items: List<com.zyna.app.data.matrix.MatrixMediaGroupItem>,
    val totalHint: Int,
    val captionPlacement: CaptionPlacement,
    val layoutOverride: MediaGroupLayoutOverride?,
    val forwardedHeaderLayout: StaticLayout?,
    val forwardedY: Int,
    val replyHeaderLayout: ReplyHeaderLayout?,
    val replyY: Int,
    val captionLayout: StaticLayout?,
    val captionY: Int,
    val mediaY: Int,
    val mediaWidth: Int,
    val mediaHeight: Int,
    val hasHeader: Boolean,
    val timeLayout: StaticLayout
) : MessageContentLayout

internal object PhotoGroupLayout {
    const val MAX_VISIBLE_ITEMS = 4
    private const val SPLIT_SCALE = 1000

    data class Corners(
        val topLeft: Boolean,
        val topRight: Boolean,
        val bottomRight: Boolean,
        val bottomLeft: Boolean
    )

    fun visibleItemCount(totalCount: Int): Int {
        return min(max(totalCount, 0), MAX_VISIBLE_ITEMS)
    }

    fun frames(
        bounds: RectF,
        itemCount: Int,
        layoutOverride: MediaGroupLayoutOverride?,
        spacingPx: Int
    ): List<RectF> {
        val visibleCount = visibleItemCount(itemCount)
        val spacing = spacingPx.toFloat()
        return when (visibleCount) {
            0 -> emptyList()
            1 -> listOf(RectF(bounds))
            2 -> {
                val primarySplit = resolvedPrimarySplitPermille(2, layoutOverride) / SPLIT_SCALE.toFloat()
                val totalWidth = bounds.width() - spacing
                val leftWidth = totalWidth * primarySplit
                val rightWidth = totalWidth - leftWidth
                listOf(
                    RectF(bounds.left, bounds.top, bounds.left + leftWidth, bounds.bottom).integral(),
                    RectF(bounds.left + leftWidth + spacing, bounds.top, bounds.right, bounds.bottom).integral()
                )
            }
            3 -> {
                val primarySplit = resolvedPrimarySplitPermille(3, layoutOverride) / SPLIT_SCALE.toFloat()
                val secondarySplit = resolvedSecondarySplitPermille(3, layoutOverride) / SPLIT_SCALE.toFloat()
                val totalWidth = bounds.width() - spacing
                val totalHeight = bounds.height() - spacing
                val leftWidth = totalWidth * primarySplit
                val rightWidth = totalWidth - leftWidth
                val topRightHeight = totalHeight * secondarySplit
                val bottomRightHeight = totalHeight - topRightHeight
                listOf(
                    RectF(bounds.left, bounds.top, bounds.left + leftWidth, bounds.bottom).integral(),
                    RectF(
                        bounds.left + leftWidth + spacing,
                        bounds.top,
                        bounds.right,
                        bounds.top + topRightHeight
                    ).integral(),
                    RectF(
                        bounds.left + leftWidth + spacing,
                        bounds.top + topRightHeight + spacing,
                        bounds.right,
                        bounds.top + topRightHeight + spacing + bottomRightHeight
                    ).integral()
                )
            }
            else -> {
                val itemWidth = (bounds.width() - spacing) / 2f
                val itemHeight = (bounds.height() - spacing) / 2f
                listOf(
                    RectF(bounds.left, bounds.top, bounds.left + itemWidth, bounds.top + itemHeight).integral(),
                    RectF(bounds.left + itemWidth + spacing, bounds.top, bounds.right, bounds.top + itemHeight).integral(),
                    RectF(bounds.left, bounds.top + itemHeight + spacing, bounds.left + itemWidth, bounds.bottom).integral(),
                    RectF(bounds.left + itemWidth + spacing, bounds.top + itemHeight + spacing, bounds.right, bounds.bottom).integral()
                )
            }
        }
    }

    fun roundedCorners(
        index: Int,
        itemCount: Int,
        hasHeader: Boolean,
        captionPlacement: CaptionPlacement?
    ): Corners {
        val visibleCount = visibleItemCount(itemCount)
        var topLeft = false
        var topRight = false
        var bottomRight = false
        var bottomLeft = false
        when (visibleCount) {
            1 -> {
                topLeft = true
                topRight = true
                bottomRight = true
                bottomLeft = true
            }
            2 -> if (index == 0) {
                topLeft = true
                bottomLeft = true
            } else {
                topRight = true
                bottomRight = true
            }
            3 -> when (index) {
                0 -> {
                    topLeft = true
                    bottomLeft = true
                }
                1 -> topRight = true
                else -> bottomRight = true
            }
            else -> when (index) {
                0 -> topLeft = true
                1 -> topRight = true
                2 -> bottomLeft = true
                else -> bottomRight = true
            }
        }

        if (hasHeader || captionPlacement == CaptionPlacement.TOP) {
            topLeft = false
            topRight = false
        }
        if (captionPlacement == CaptionPlacement.BOTTOM) {
            bottomRight = false
            bottomLeft = false
        }

        return Corners(
            topLeft = topLeft,
            topRight = topRight,
            bottomRight = bottomRight,
            bottomLeft = bottomLeft
        )
    }

    private fun resolvedPrimarySplitPermille(
        itemCount: Int,
        layoutOverride: MediaGroupLayoutOverride?
    ): Int {
        val value = layoutOverride?.primarySplitPermille ?: defaultPrimarySplitPermille(itemCount)
        return when (visibleItemCount(itemCount)) {
            2 -> value.coerceIn(350, 650)
            3 -> value.coerceIn(420, 720)
            else -> value
        }
    }

    private fun resolvedSecondarySplitPermille(
        itemCount: Int,
        layoutOverride: MediaGroupLayoutOverride?
    ): Int {
        val value = layoutOverride?.secondarySplitPermille ?: defaultSecondarySplitPermille(itemCount)
        return when (visibleItemCount(itemCount)) {
            3 -> value.coerceIn(280, 720)
            else -> value
        }
    }

    private fun defaultPrimarySplitPermille(itemCount: Int): Int {
        return when (visibleItemCount(itemCount)) {
            3 -> 600
            else -> 500
        }
    }

    private fun defaultSecondarySplitPermille(itemCount: Int): Int {
        return when (visibleItemCount(itemCount)) {
            3 -> 500
            else -> 500
        }
    }
}

private fun com.zyna.app.data.matrix.MatrixImageInfo.aspectRatio(): Float? {
    val width = width?.takeIf { it > 0 } ?: return null
    val height = height?.takeIf { it > 0 } ?: return null
    return width.toFloat() / height.toFloat()
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

private fun RectF.integral(): RectF {
    left = left.roundToInt().toFloat()
    top = top.roundToInt().toFloat()
    right = right.roundToInt().toFloat()
    bottom = bottom.roundToInt().toFloat()
    return this
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

private fun Int.groupDpToPx(density: Float): Int {
    return (this * density).roundToInt()
}

private fun Int.groupSpToPx(context: Context): Float {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        toFloat(),
        context.resources.displayMetrics
    )
}

private fun Int.withGroupAlpha(alpha: Int): Int {
    return Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(this),
        Color.green(this),
        Color.blue(this)
    )
}
