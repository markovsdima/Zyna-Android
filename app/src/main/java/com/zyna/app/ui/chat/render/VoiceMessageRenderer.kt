package com.zyna.app.ui.chat.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import com.zyna.app.data.media.AudioPlaybackSnapshot
import com.zyna.app.data.matrix.MatrixAudioInfo
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal class VoiceMessageRenderer(
    context: Context,
    private val playbackSnapshotProvider: () -> AudioPlaybackSnapshot
) : MessageContentRenderer {
    private val density = context.resources.displayMetrics.density
    private val senderPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG)
    private val timePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG)
    private val durationPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG)
    private val forwardedPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG)
    private val replySenderPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG)
    private val replyBodyPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG)
    private val replyBarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val waveformPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val loadingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val playPath = Path()
    private val rowHeight = 42.dpToPx(density)
    private val minVoiceWidth = 210.dpToPx(density)
    private val maxVoiceWidth = 286.dpToPx(density)
    private val buttonSize = 34.dpToPx(density).toFloat()
    private val buttonWaveformSpacing = 10.dpToPx(density).toFloat()
    private val barGap = 2.dpToPx(density).toFloat()
    private val minBarWidth = 2.dpToPx(density).toFloat()
    private val waveformHeight = 24.dpToPx(density).toFloat()
    private val metadataTopSpacing = 3.dpToPx(density)
    private val senderBottomSpacing = 3.dpToPx(density)
    private val replyBarWidth = 2.dpToPx(density)
    private val replyHorizontalSpacing = 6.dpToPx(density)
    private val replyLineSpacing = 1.dpToPx(density)
    private val replyBottomInset = 4.dpToPx(density)
    private val replyBottomSpacing = 7.dpToPx(density)
    private val forwardedBottomSpacing = 5.dpToPx(density)

    init {
        senderPaint.textSize = 12.spToPx(context)
        senderPaint.isFakeBoldText = true
        timePaint.textSize = 11.spToPx(context)
        durationPaint.textSize = 11.spToPx(context)
        forwardedPaint.textSize = 12.spToPx(context)
        forwardedPaint.isFakeBoldText = true
        replySenderPaint.textSize = 12.spToPx(context)
        replySenderPaint.isFakeBoldText = true
        replyBodyPaint.textSize = 12.spToPx(context)
        loadingPaint.style = Paint.Style.STROKE
        loadingPaint.strokeWidth = 2.dpToPx(density).toFloat()
        loadingPaint.strokeCap = Paint.Cap.ROUND
        iconPaint.style = Paint.Style.FILL
    }

    override fun supports(content: MessageContent): Boolean {
        return content is MessageContent.Voice
    }

    override fun measure(
        message: MessageRenderModel,
        theme: MessageRenderTheme,
        maxWidthPx: Int
    ): VoiceMessageLayout {
        val content = message.content as MessageContent.Voice
        val widthLimit = max(1, maxWidthPx)
        val voiceWidth = min(maxVoiceWidth, widthLimit)
            .coerceAtLeast(min(minVoiceWidth, widthLimit))

        senderPaint.color = theme.metadataColor(message)
        timePaint.color = theme.metadataColor(message)
        durationPaint.color = theme.metadataColor(message)
        forwardedPaint.color = theme.metadataColor(message)
        replySenderPaint.color = theme.metadataColor(message)
        replyBodyPaint.color = theme.metadataColor(message)
        replyBarPaint.color = theme.metadataColor(message)

        val forwardedLayout = makeForwardedHeaderLayout(message.forwardedFrom, voiceWidth)
        val forwardedHeight = if (forwardedLayout == null) {
            0
        } else {
            forwardedLayout.height + forwardedBottomSpacing
        }
        val replyLayout = makeReplyHeaderLayout(message.replyInfo, voiceWidth)
        val replyHeight = if (replyLayout == null) 0 else replyLayout.height + replyBottomSpacing
        val senderLayout = makeSenderLayout(message, voiceWidth)
        val senderHeight = if (senderLayout == null) 0 else senderLayout.height + senderBottomSpacing

        val rowY = forwardedHeight + replyHeight + senderHeight
        val metadataTextHeight = ceil(
            max(
                durationPaint.descent() - durationPaint.ascent(),
                timePaint.descent() - timePaint.ascent()
            ).toDouble()
        ).toInt()
        val metadataY = rowY + rowHeight + metadataTopSpacing
        val metadataBaseline = metadataY - durationPaint.ascent()
        val timeText = message.metadataText()
        val timeWidth = ceil(timePaint.measureText(timeText).toDouble()).toInt()
            .coerceAtLeast(1)
        val waveformX = buttonSize + buttonWaveformSpacing
        val waveformWidth = (voiceWidth - waveformX).coerceAtLeast(1f)

        return VoiceMessageLayout(
            width = voiceWidth,
            height = metadataY + metadataTextHeight,
            messageId = message.id,
            audioInfo = content.audioInfo,
            forwardedHeaderLayout = forwardedLayout,
            forwardedY = 0,
            replyHeaderLayout = replyLayout,
            replyY = forwardedHeight,
            senderLayout = senderLayout,
            senderY = forwardedHeight + replyHeight,
            rowY = rowY,
            buttonCenterX = buttonSize / 2f,
            buttonCenterY = rowY + rowHeight / 2f,
            waveformX = waveformX,
            waveformCenterY = rowY + rowHeight / 2f,
            waveformWidth = waveformWidth,
            metadataBaseline = metadataBaseline,
            durationX = waveformX,
            timeText = timeText,
            timeX = voiceWidth - timeWidth,
            waveformSeed = content.audioInfo.sourceJson.hashCode(),
            buttonColor = theme.textColor(message).withAlpha(34),
            iconColor = theme.textColor(message).withAlpha(232),
            loadingColor = theme.textColor(message).withAlpha(214),
            waveformActiveColor = theme.textColor(message).withAlpha(236),
            waveformInactiveColor = theme.metadataColor(message).withAlpha(112),
            metadataColor = theme.metadataColor(message).withAlpha(178)
        )
    }

    override fun draw(canvas: Canvas, layout: MessageContentLayout) {
        layout as VoiceMessageLayout

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

        val snapshot = playbackSnapshotProvider()
        val isActive = snapshot.isFor(layout.messageId, layout.audioInfo)
        val isLoading = isActive && snapshot.isLoading
        val isPlaying = isActive && snapshot.isPlaying
        val durationMillis = activeDurationMillis(layout.audioInfo, snapshot, isActive)
        val currentMillis = if (isActive) snapshot.currentPositionMillis.coerceAtLeast(0L) else 0L
        val progress = if (isActive && durationMillis > 0L) {
            (currentMillis.toFloat() / durationMillis.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

        drawButton(canvas, layout, isLoading = isLoading, isPlaying = isPlaying)
        drawWaveform(canvas, layout, progress)
        drawMetadata(canvas, layout, currentMillis, durationMillis, isActive)
    }

    private fun drawButton(
        canvas: Canvas,
        layout: VoiceMessageLayout,
        isLoading: Boolean,
        isPlaying: Boolean
    ) {
        val centerX = layout.buttonCenterX
        val centerY = layout.buttonCenterY
        val radius = buttonSize / 2f
        buttonPaint.color = layout.buttonColor
        canvas.drawCircle(centerX, centerY, radius, buttonPaint)

        iconPaint.color = layout.iconColor
        loadingPaint.color = layout.loadingColor
        if (isLoading) {
            canvas.drawArc(
                centerX - radius / 2f,
                centerY - radius / 2f,
                centerX + radius / 2f,
                centerY + radius / 2f,
                -90f,
                270f,
                false,
                loadingPaint
            )
        } else if (isPlaying) {
            val barWidth = 4.dpToPx(density).toFloat()
            val barHeight = 15.dpToPx(density).toFloat()
            val gap = 4.dpToPx(density).toFloat()
            val left = centerX - gap / 2f - barWidth
            val top = centerY - barHeight / 2f
            canvas.drawRoundRect(left, top, left + barWidth, top + barHeight, 1.5f, 1.5f, iconPaint)
            val rightLeft = centerX + gap / 2f
            canvas.drawRoundRect(
                rightLeft,
                top,
                rightLeft + barWidth,
                top + barHeight,
                1.5f,
                1.5f,
                iconPaint
            )
        } else {
            playPath.rewind()
            val triangleWidth = 12.dpToPx(density).toFloat()
            val triangleHeight = 15.dpToPx(density).toFloat()
            val left = centerX - triangleWidth / 3f
            playPath.moveTo(left, centerY - triangleHeight / 2f)
            playPath.lineTo(left, centerY + triangleHeight / 2f)
            playPath.lineTo(left + triangleWidth, centerY)
            playPath.close()
            canvas.drawPath(playPath, iconPaint)
        }
    }

    private fun drawWaveform(
        canvas: Canvas,
        layout: VoiceMessageLayout,
        progress: Float
    ) {
        val barCount = WAVEFORM_BAR_COUNT
        val totalGap = barGap * (barCount - 1)
        val barWidth = max(minBarWidth, (layout.waveformWidth - totalGap) / barCount)
        val usedWidth = barWidth * barCount + totalGap
        val startX = layout.waveformX + (layout.waveformWidth - usedWidth).coerceAtLeast(0f) / 2f
        val topLimit = layout.waveformCenterY - waveformHeight / 2f
        val progressIndex = progress * barCount

        for (index in 0 until barCount) {
            val sample = waveformSample(layout.audioInfo, layout.waveformSeed, index, barCount)
            val barHeight = (waveformHeight * (0.18f + sample * 0.82f))
                .coerceIn(3.dpToPx(density).toFloat(), waveformHeight)
            val left = startX + index * (barWidth + barGap)
            val top = topLimit + (waveformHeight - barHeight) / 2f
            waveformPaint.color = if (index + 0.5f <= progressIndex) {
                layout.waveformActiveColor
            } else {
                layout.waveformInactiveColor
            }
            canvas.drawRoundRect(
                left,
                top,
                left + barWidth,
                top + barHeight,
                barWidth / 2f,
                barWidth / 2f,
                waveformPaint
            )
        }
    }

    private fun drawMetadata(
        canvas: Canvas,
        layout: VoiceMessageLayout,
        currentMillis: Long,
        durationMillis: Long,
        isActive: Boolean
    ) {
        durationPaint.color = layout.metadataColor
        timePaint.color = layout.metadataColor
        val durationText = if (isActive && currentMillis > 0L) {
            currentMillis.formatAudioDuration()
        } else {
            durationMillis.formatAudioDuration()
        }
        canvas.drawText(durationText, layout.durationX, layout.metadataBaseline, durationPaint)
        canvas.drawText(layout.timeText, layout.timeX.toFloat(), layout.metadataBaseline, timePaint)
    }

    private fun activeDurationMillis(
        audioInfo: MatrixAudioInfo,
        snapshot: AudioPlaybackSnapshot,
        isActive: Boolean
    ): Long {
        return when {
            isActive && snapshot.durationMillis > 0L -> snapshot.durationMillis
            audioInfo.durationMillis != null && audioInfo.durationMillis > 0L -> audioInfo.durationMillis
            else -> 0L
        }
    }

    private fun waveformSample(
        audioInfo: MatrixAudioInfo,
        seed: Int,
        index: Int,
        barCount: Int
    ): Float {
        val waveform = audioInfo.waveform
        if (waveform.isNotEmpty()) {
            val sampleIndex = ((index.toFloat() / barCount.toFloat()) * waveform.size)
                .roundToInt()
                .coerceIn(0, waveform.lastIndex)
            return waveform[sampleIndex].coerceIn(0f, 1f)
        }
        var value = seed xor (index * 0x45d9f3b)
        value = value xor (value ushr 16)
        value *= 0x45d9f3b
        value = value xor (value ushr 16)
        val normalized = (value and 0xFF).toFloat() / 255f
        return 0.18f + normalized * 0.82f
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

    private fun makeSenderLayout(message: MessageRenderModel, maxWidth: Int): StaticLayout? {
        val sender = message.senderText.takeIf { it.isNotBlank() } ?: return null
        return makeSingleLineLayout(
            text = sender,
            paint = senderPaint,
            maxWidth = maxWidth
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

internal data class VoiceMessageLayout(
    override val width: Int,
    override val height: Int,
    val messageId: String,
    val audioInfo: MatrixAudioInfo,
    val forwardedHeaderLayout: StaticLayout?,
    val forwardedY: Int,
    val replyHeaderLayout: ReplyHeaderLayout?,
    val replyY: Int,
    val senderLayout: StaticLayout?,
    val senderY: Int,
    val rowY: Int,
    val buttonCenterX: Float,
    val buttonCenterY: Float,
    val waveformX: Float,
    val waveformCenterY: Float,
    val waveformWidth: Float,
    val metadataBaseline: Float,
    val durationX: Float,
    val timeText: String,
    val timeX: Int,
    val waveformSeed: Int,
    val buttonColor: Int,
    val iconColor: Int,
    val loadingColor: Int,
    val waveformActiveColor: Int,
    val waveformInactiveColor: Int,
    val metadataColor: Int
) : MessageContentLayout

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

private fun Long.formatAudioDuration(): String {
    val totalSeconds = (this / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "$minutes:${seconds.toString().padStart(2, '0')}"
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

private fun Int.spToPx(context: Context): Float {
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

private const val WAVEFORM_BAR_COUNT = 34
