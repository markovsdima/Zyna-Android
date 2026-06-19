package com.zyna.app.ui.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import java.io.File
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.zyna.app.data.messaging.CaptionPlacement
import com.zyna.app.data.messaging.MediaGroupLayoutOverride
import com.zyna.app.data.outgoing.OutgoingPhotoDraftItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

data class PhotoMessageEditorResult(
    val items: List<OutgoingPhotoDraftItem>,
    val caption: String?,
    val captionPlacement: CaptionPlacement,
    val layoutOverride: MediaGroupLayoutOverride?
)

@Composable
fun PhotoMessageEditor(
    items: List<OutgoingPhotoDraftItem>,
    isSending: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onDiscardItem: (OutgoingPhotoDraftItem) -> Unit = {},
    onSend: (PhotoMessageEditorResult) -> Unit
) {
    var orderedItems by remember(items) { mutableStateOf(items) }
    var selectedIndex by remember(items) { mutableIntStateOf(0) }
    var caption by remember(items) { mutableStateOf("") }
    var captionPlacement by remember(items) { mutableStateOf(CaptionPlacement.BOTTOM) }
    var captionFieldBounds by remember { mutableStateOf<Rect?>(null) }
    val currentCaptionFieldBounds by rememberUpdatedState(captionFieldBounds)
    val focusManager = LocalFocusManager.current
    var editedLayoutOverride by remember(items) {
        mutableStateOf<MediaGroupLayoutOverride?>(null)
    }
    val itemCount = orderedItems.size
    val layoutOverride = sanitizedLayoutOverride(
        layoutOverride = editedLayoutOverride,
        itemCount = itemCount
    )
    val secondarySplit = resolvedSecondarySplitPermille(itemCount, layoutOverride)
    fun updateLayoutOverride(
        primarySplitPermille: Int = resolvedPrimarySplitPermille(itemCount, layoutOverride),
        secondarySplitPermille: Int = secondarySplit
    ) {
        editedLayoutOverride = sanitizedLayoutOverride(
            itemCount = itemCount,
            primarySplitPermille = primarySplitPermille,
            secondarySplitPermille = secondarySplitPermille
        )
    }

    LaunchedEffect(layoutOverride) {
        if (editedLayoutOverride != layoutOverride) {
            editedLayoutOverride = layoutOverride
        }
    }

    val sendLayoutOverride = sanitizedLayoutOverride(
        itemCount = itemCount,
        primarySplitPermille = resolvedPrimarySplitPermille(itemCount, layoutOverride),
        secondarySplitPermille = resolvedSecondarySplitPermille(itemCount, layoutOverride)
    )
    val canResetLayout = itemCount <= MAX_VISIBLE_PHOTO_ITEMS &&
        supportsInteractiveLayout(itemCount) &&
        sendLayoutOverride != null

    fun moveItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex != toIndex && fromIndex in orderedItems.indices && toIndex in orderedItems.indices) {
            orderedItems = orderedItems.moved(fromIndex, toIndex)
            selectedIndex = toIndex
        }
    }

    fun removeItem(item: OutgoingPhotoDraftItem) {
        val currentIndex = orderedItems.indexOfFirst { it.localPath == item.localPath }
        if (currentIndex == -1) {
            return
        }
        orderedItems = orderedItems.toMutableList().also { it.removeAt(currentIndex) }
        selectedIndex = selectedIndex.coerceAtMost(orderedItems.lastIndex.coerceAtLeast(0))
        onDiscardItem(item)
        if (orderedItems.isEmpty()) {
            onDismiss()
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val down = event.changes.firstOrNull { change ->
                            change.pressed && !change.previousPressed
                        } ?: continue
                        val bounds = currentCaptionFieldBounds
                        if (bounds == null || !bounds.contains(down.position)) {
                            focusManager.clearFocus()
                        }
                    }
                }
            },
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !isSending
                ) {
                    Text("Cancel")
                }
                Text(
                    text = if (itemCount == 1) "Photo" else "$itemCount photos",
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (canResetLayout) {
                    TextButton(
                        onClick = {
                            editedLayoutOverride = null
                        },
                        enabled = !isSending
                    ) {
                        Text("Reset")
                    }
                }
                Button(
                    onClick = {
                        onSend(
                            PhotoMessageEditorResult(
                                items = orderedItems,
                                caption = caption.trim().takeIf { it.isNotEmpty() },
                                captionPlacement = captionPlacement,
                                layoutOverride = sendLayoutOverride
                            )
                        )
                    },
                    enabled = !isSending && itemCount > 0
                ) {
                    Text(if (isSending) "Sending" else "Send")
                }
            }

            if (isSending) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                PhotoGroupPreview(
                    items = orderedItems,
                    caption = caption.trim().takeIf { it.isNotEmpty() },
                    captionPlacement = captionPlacement,
                    layoutOverride = sendLayoutOverride,
                    enabled = !isSending,
                    onCaptionPlacementChanged = { captionPlacement = it },
                    onPrimarySplitChanged = { updateLayoutOverride(primarySplitPermille = it.roundToInt()) },
                    onSecondarySplitChanged = { updateLayoutOverride(secondarySplitPermille = it.roundToInt()) },
                    onMoveItem = ::moveItem,
                    onRemoveItem = ::removeItem,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            captionFieldBounds = coordinates.boundsInRoot()
                        },
                    enabled = !isSending,
                    label = { Text("Caption") },
                    minLines = 1,
                    maxLines = 4
                )

                if (itemCount > MAX_VISIBLE_PHOTO_ITEMS) {
                    PhotoOrderStrip(
                        items = orderedItems,
                        selectedIndex = selectedIndex.coerceIn(0, itemCount - 1),
                        enabled = !isSending,
                        onSelected = { selectedIndex = it },
                        onMoveItem = { item, direction ->
                            val currentIndex = orderedItems.indexOfFirst {
                                it.localPath == item.localPath
                            }
                            val nextIndex = (currentIndex + direction)
                                .coerceIn(0, orderedItems.lastIndex)
                            if (currentIndex != -1 && currentIndex != nextIndex) {
                                moveItem(currentIndex, nextIndex)
                            }
                        },
                        onRemoveItem = ::removeItem
                    )
                }

                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoGroupPreview(
    items: List<OutgoingPhotoDraftItem>,
    caption: String?,
    captionPlacement: CaptionPlacement,
    layoutOverride: MediaGroupLayoutOverride?,
    enabled: Boolean,
    onCaptionPlacementChanged: (CaptionPlacement) -> Unit,
    onPrimarySplitChanged: (Float) -> Unit,
    onSecondarySplitChanged: (Float) -> Unit,
    onMoveItem: (Int, Int) -> Unit,
    onRemoveItem: (OutgoingPhotoDraftItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(8.dp)
    val previewCaption = caption ?: "Caption"
    val isCaptionPlaceholder = caption == null
    Column(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
    ) {
        if (captionPlacement == CaptionPlacement.TOP) {
            CaptionStrip(
                caption = previewCaption,
                isPlaceholder = isCaptionPlaceholder,
                placement = captionPlacement,
                enabled = enabled,
                onPlacementChanged = onCaptionPlacementChanged
            )
        }
        PhotoGrid(
            items = items,
            captionPlacement = captionPlacement,
            layoutOverride = layoutOverride,
            enabled = enabled,
            onPrimarySplitChanged = onPrimarySplitChanged,
            onSecondarySplitChanged = onSecondarySplitChanged,
            onMoveItem = onMoveItem,
            onRemoveItem = onRemoveItem,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (items.size == 1) 1.08f else 1.24f)
        )
        if (captionPlacement == CaptionPlacement.BOTTOM) {
            CaptionStrip(
                caption = previewCaption,
                isPlaceholder = isCaptionPlaceholder,
                placement = captionPlacement,
                enabled = enabled,
                onPlacementChanged = onCaptionPlacementChanged
            )
        }
    }
}

@Composable
private fun CaptionStrip(
    caption: String,
    isPlaceholder: Boolean,
    placement: CaptionPlacement,
    enabled: Boolean,
    onPlacementChanged: (CaptionPlacement) -> Unit
) {
    val dragThresholdPx = 28.dp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var totalDragY = 0f
                detectDragGestures(
                    onDragStart = { totalDragY = 0f },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        totalDragY += dragAmount.y
                    },
                    onDragEnd = {
                        val threshold = dragThresholdPx.toPx()
                        when {
                            totalDragY < -threshold && placement != CaptionPlacement.TOP -> {
                                onPlacementChanged(CaptionPlacement.TOP)
                            }
                            totalDragY > threshold && placement != CaptionPlacement.BOTTOM -> {
                                onPlacementChanged(CaptionPlacement.BOTTOM)
                            }
                        }
                    }
                )
            }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = caption,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isPlaceholder) 0.58f else 1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PhotoGrid(
    items: List<OutgoingPhotoDraftItem>,
    captionPlacement: CaptionPlacement,
    layoutOverride: MediaGroupLayoutOverride?,
    enabled: Boolean,
    onPrimarySplitChanged: (Float) -> Unit,
    onSecondarySplitChanged: (Float) -> Unit,
    onMoveItem: (Int, Int) -> Unit,
    onRemoveItem: (OutgoingPhotoDraftItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val visibleItems = items.take(MAX_VISIBLE_PHOTO_ITEMS)
    val extraCount = (items.size - visibleItems.size).coerceAtLeast(0)
    val density = LocalDensity.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    var draggedItem by remember { mutableStateOf<OutgoingPhotoDraftItem?>(null) }
    var dragCenter by remember { mutableStateOf(Offset.Zero) }
    var dragSize by remember { mutableStateOf(IntSize.Zero) }

    val primarySplit = resolvedPrimarySplitPermille(visibleItems.size, layoutOverride).toFloat()
    val secondarySplit = resolvedSecondarySplitPermille(visibleItems.size, layoutOverride).toFloat()
    val currentPrimarySplit by rememberUpdatedState(primarySplit)
    val currentSecondarySplit by rememberUpdatedState(secondarySplit)
    val spacingPx = with(density) { PHOTO_GRID_SPACING_DP.dp.roundToPx() }
    val frames = remember(size, visibleItems.size, primarySplit, secondarySplit, spacingPx) {
        photoGridFrames(
            size = size,
            itemCount = visibleItems.size,
            primarySplitPermille = primarySplit.roundToInt(),
            secondarySplitPermille = secondarySplit.roundToInt(),
            spacingPx = spacingPx
        )
    }
    val directEditingEnabled = enabled && items.size <= MAX_VISIBLE_PHOTO_ITEMS
    val canReorder = directEditingEnabled && visibleItems.size > 1
    val touchInsetPx = with(density) { 8.dp.toPx() }
    val latestFrames by rememberUpdatedState(frames)
    val latestVisibleItems by rememberUpdatedState(visibleItems)

    LaunchedEffect(items.map { it.localPath }) {
        val currentDraggedItem = draggedItem
        if (currentDraggedItem != null && items.none { it.localPath == currentDraggedItem.localPath }) {
            draggedItem = null
            dragSize = IntSize.Zero
            dragCenter = Offset.Zero
        }
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .onSizeChanged { size = it }
            .pointerInput(canReorder, visibleItems.size, size) {
                if (!canReorder) return@pointerInput
                var activeIndex: Int? = null
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        val currentFrames = latestFrames
                        val currentItems = latestVisibleItems
                        val index = currentFrames.indexOfFirst { it.contains(offset, touchInsetPx) }
                        activeIndex = index.takeIf { it != -1 }
                        val frame = activeIndex?.let { currentFrames.getOrNull(it) }
                        val item = activeIndex?.let { currentItems.getOrNull(it) }
                        if (frame != null && item != null) {
                            draggedItem = item
                            dragSize = IntSize(frame.width, frame.height)
                            dragCenter = frame.center
                        }
                    },
                    onDragEnd = {
                        activeIndex = null
                        draggedItem = null
                        dragSize = IntSize.Zero
                    },
                    onDragCancel = {
                        activeIndex = null
                        draggedItem = null
                        dragSize = IntSize.Zero
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val currentIndex = activeIndex
                        if (currentIndex != null) {
                            dragCenter += dragAmount
                            val currentFrames = latestFrames
                            val currentItems = latestVisibleItems
                            val targetIndex = currentFrames.indexOfFirst {
                                it.contains(change.position, touchInsetPx)
                            }
                            if (
                                targetIndex != -1 &&
                                targetIndex != currentIndex &&
                                targetIndex < currentItems.size
                            ) {
                                onMoveItem(currentIndex, targetIndex)
                                activeIndex = targetIndex
                            }
                        }
                    }
                )
            }
    ) {
        frames.forEachIndexed { index, frame ->
            val item = visibleItems.getOrNull(index) ?: return@forEachIndexed
            val shape = photoTileShape(
                index = index,
                itemCount = visibleItems.size,
                captionPlacement = captionPlacement,
                radius = 8.dp
            )
            val isDraggedItem = draggedItem?.localPath == item.localPath
            Box(
                modifier = Modifier
                    .offset { IntOffset(frame.left, frame.top) }
                    .size(
                        width = with(density) { frame.width.toDp() },
                        height = with(density) { frame.height.toDp() }
                    )
                    .alpha(if (isDraggedItem) 0.22f else 1f)
            ) {
                PhotoTile(
                    item = item,
                    modifier = Modifier.fillMaxSize(),
                    shape = shape,
                    extraCount = if (index == frames.lastIndex) extraCount else 0
                )
                if (directEditingEnabled) {
                    RemovePhotoButton(
                        enabled = enabled,
                        onClick = { onRemoveItem(item) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .zIndex(15f)
                    )
                }
            }
        }

        PhotoGridLayoutControls(
            enabled = directEditingEnabled,
            itemCount = visibleItems.size,
            frames = frames,
            size = size,
            spacingPx = spacingPx,
            currentPrimarySplit = currentPrimarySplit,
            currentSecondarySplit = currentSecondarySplit,
            onPrimarySplitChanged = onPrimarySplitChanged,
            onSecondarySplitChanged = onSecondarySplitChanged
        )

        val overlayItem = draggedItem
        if (overlayItem != null && dragSize.width > 0 && dragSize.height > 0) {
            PhotoTile(
                item = overlayItem,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (dragCenter.x - dragSize.width / 2f).roundToInt(),
                            (dragCenter.y - dragSize.height / 2f).roundToInt()
                        )
                    }
                    .size(
                        width = with(density) { dragSize.width.toDp() },
                        height = with(density) { dragSize.height.toDp() }
                    )
                    .zIndex(20f),
                shape = RoundedCornerShape(8.dp)
            )
        }
    }
}

@Composable
private fun PhotoTile(
    item: OutgoingPhotoDraftItem,
    modifier: Modifier = Modifier,
    extraCount: Int = 0,
    shape: RoundedCornerShape = RoundedCornerShape(0.dp)
) {
    val bitmap = rememberLocalPreview(item.localPath, maxSizePx = 720)
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        if (extraCount > 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+$extraCount",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium
                )
            }
        }
    }
}

@Composable
private fun RemovePhotoButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "x",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun PhotoGridLayoutControls(
    enabled: Boolean,
    itemCount: Int,
    frames: List<PhotoSlotFrame>,
    size: IntSize,
    spacingPx: Int,
    currentPrimarySplit: Float,
    currentSecondarySplit: Float,
    onPrimarySplitChanged: (Float) -> Unit,
    onSecondarySplitChanged: (Float) -> Unit
) {
    if (!enabled || !supportsInteractiveLayout(itemCount) || frames.isEmpty() || size == IntSize.Zero) {
        return
    }

    val density = LocalDensity.current
    val guideThickness = with(density) {
        maxOf(2.dp.toPx(), spacingPx.toFloat()).toDp()
    }
    val handleHitSize = 40.dp
    val handleSize = 32.dp
    val guideThicknessPx = with(density) { guideThickness.roundToPx() }
    val handleHitSizePx = with(density) { handleHitSize.roundToPx() }
    val primaryDividerCenterX = frames[0].right + spacingPx / 2f
    val latestPrimarySplit by rememberUpdatedState(currentPrimarySplit)
    val latestSecondarySplit by rememberUpdatedState(currentSecondarySplit)

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (primaryDividerCenterX - guideThicknessPx / 2f).roundToInt(),
                    0
                )
            }
            .width(guideThickness)
            .fillMaxHeight()
            .zIndex(8f)
            .background(Color.White.copy(alpha = 0.16f))
    )

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (primaryDividerCenterX - handleHitSizePx / 2f).roundToInt(),
                    (size.height / 2f - handleHitSizePx / 2f).roundToInt()
                )
            }
            .size(handleHitSize)
            .zIndex(10f)
            .pointerInput(enabled, itemCount, size) {
                if (!enabled) return@pointerInput
                var activePrimarySplit = latestPrimarySplit
                detectDragGestures(
                    onDragStart = {
                        activePrimarySplit = latestPrimarySplit
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val totalWidth = (size.width - spacingPx).coerceAtLeast(1).toFloat()
                        val next = (activePrimarySplit + dragAmount.x / totalWidth * SPLIT_SCALE)
                            .coerceIn(
                                minimumPrimarySplitPermille(itemCount).toFloat(),
                                maximumPrimarySplitPermille(itemCount).toFloat()
                            )
                        activePrimarySplit = next
                        onPrimarySplitChanged(next)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        DividerHandle(
            axis = DividerAxis.Horizontal,
            modifier = Modifier.size(handleSize)
        )
    }

    if (itemCount != 3 || frames.size < 3) {
        return
    }

    val rightColumnFrame = frames[1]
    val secondaryDividerCenterY = frames[1].bottom + spacingPx / 2f

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    rightColumnFrame.left,
                    (secondaryDividerCenterY - guideThicknessPx / 2f).roundToInt()
                )
            }
            .width(with(density) { rightColumnFrame.width.toDp() })
            .height(guideThickness)
            .zIndex(8f)
            .background(Color.White.copy(alpha = 0.16f))
    )

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (rightColumnFrame.left + rightColumnFrame.width / 2f - handleHitSizePx / 2f).roundToInt(),
                    (secondaryDividerCenterY - handleHitSizePx / 2f).roundToInt()
                )
            }
            .size(handleHitSize)
            .zIndex(10f)
            .pointerInput(enabled, itemCount, size) {
                if (!enabled) return@pointerInput
                var activeSecondarySplit = latestSecondarySplit
                detectDragGestures(
                    onDragStart = {
                        activeSecondarySplit = latestSecondarySplit
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val totalHeight = (size.height - spacingPx).coerceAtLeast(1).toFloat()
                        val next = (activeSecondarySplit + dragAmount.y / totalHeight * SPLIT_SCALE)
                            .coerceIn(280f, 720f)
                        activeSecondarySplit = next
                        onSecondarySplitChanged(next)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        DividerHandle(
            axis = DividerAxis.Vertical,
            modifier = Modifier.size(handleSize)
        )
    }
}

@Composable
private fun DividerHandle(
    axis: DividerAxis,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val arrowSize = 6.dp.toPx()
        val gap = 2.dp.toPx()
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val firstPath = Path()
        val secondPath = Path()

        if (axis == DividerAxis.Horizontal) {
            firstPath.moveTo(centerX - gap, centerY)
            firstPath.lineTo(centerX - gap - arrowSize, centerY - arrowSize)
            firstPath.lineTo(centerX - gap - arrowSize, centerY + arrowSize)
            firstPath.close()

            secondPath.moveTo(centerX + gap, centerY)
            secondPath.lineTo(centerX + gap + arrowSize, centerY - arrowSize)
            secondPath.lineTo(centerX + gap + arrowSize, centerY + arrowSize)
            secondPath.close()
        } else {
            firstPath.moveTo(centerX, centerY - gap)
            firstPath.lineTo(centerX - arrowSize, centerY - gap - arrowSize)
            firstPath.lineTo(centerX + arrowSize, centerY - gap - arrowSize)
            firstPath.close()

            secondPath.moveTo(centerX, centerY + gap)
            secondPath.lineTo(centerX - arrowSize, centerY + gap + arrowSize)
            secondPath.lineTo(centerX + arrowSize, centerY + gap + arrowSize)
            secondPath.close()
        }

        drawPath(firstPath, Color.White.copy(alpha = 0.96f))
        drawPath(secondPath, Color.White.copy(alpha = 0.96f))
    }
}

private enum class DividerAxis {
    Horizontal,
    Vertical
}

private const val MAX_VISIBLE_PHOTO_ITEMS = 4
private const val SPLIT_SCALE = 1000
private const val PHOTO_GRID_SPACING_DP = 2

private data class PhotoSlotFrame(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
) {
    val right: Int
        get() = left + width

    val bottom: Int
        get() = top + height

    val center: Offset
        get() = Offset(left + width / 2f, top + height / 2f)

    fun contains(offset: Offset, insetPx: Float = 0f): Boolean {
        return offset.x >= left - insetPx &&
            offset.x <= right + insetPx &&
            offset.y >= top - insetPx &&
            offset.y <= bottom + insetPx
    }
}

private fun photoGridFrames(
    size: IntSize,
    itemCount: Int,
    primarySplitPermille: Int,
    secondarySplitPermille: Int,
    spacingPx: Int
): List<PhotoSlotFrame> {
    if (size.width <= 0 || size.height <= 0) {
        return emptyList()
    }

    val visibleCount = visibleItemCount(itemCount)
    val spacing = spacingPx.toFloat()
    val width = size.width.toFloat()
    val height = size.height.toFloat()
    return when (visibleCount) {
        0 -> emptyList()
        1 -> listOf(slotFrame(0f, 0f, width, height))
        2 -> {
            val split = resolvedPrimarySplitPermille(2, primarySplitPermille) / SPLIT_SCALE.toFloat()
            val totalWidth = (width - spacing).coerceAtLeast(1f)
            val leftWidth = totalWidth * split
            listOf(
                slotFrame(0f, 0f, leftWidth, height),
                slotFrame(leftWidth + spacing, 0f, width, height)
            )
        }
        3 -> {
            val primarySplit = resolvedPrimarySplitPermille(3, primarySplitPermille) / SPLIT_SCALE.toFloat()
            val secondarySplit = resolvedSecondarySplitPermille(3, secondarySplitPermille) / SPLIT_SCALE.toFloat()
            val totalWidth = (width - spacing).coerceAtLeast(1f)
            val totalHeight = (height - spacing).coerceAtLeast(1f)
            val leftWidth = totalWidth * primarySplit
            val rightStart = leftWidth + spacing
            val topRightHeight = totalHeight * secondarySplit
            listOf(
                slotFrame(0f, 0f, leftWidth, height),
                slotFrame(rightStart, 0f, width, topRightHeight),
                slotFrame(rightStart, topRightHeight + spacing, width, height)
            )
        }
        else -> {
            val itemWidth = (width - spacing) / 2f
            val itemHeight = (height - spacing) / 2f
            listOf(
                slotFrame(0f, 0f, itemWidth, itemHeight),
                slotFrame(itemWidth + spacing, 0f, width, itemHeight),
                slotFrame(0f, itemHeight + spacing, itemWidth, height),
                slotFrame(itemWidth + spacing, itemHeight + spacing, width, height)
            )
        }
    }
}

private fun slotFrame(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float
): PhotoSlotFrame {
    val roundedLeft = left.roundToInt()
    val roundedTop = top.roundToInt()
    val roundedRight = right.roundToInt()
    val roundedBottom = bottom.roundToInt()
    return PhotoSlotFrame(
        left = roundedLeft,
        top = roundedTop,
        width = (roundedRight - roundedLeft).coerceAtLeast(1),
        height = (roundedBottom - roundedTop).coerceAtLeast(1)
    )
}

@Composable
private fun photoTileShape(
    index: Int,
    itemCount: Int,
    captionPlacement: CaptionPlacement,
    radius: Dp
): RoundedCornerShape {
    val corners = roundedCorners(
        index = index,
        itemCount = itemCount,
        captionPlacement = captionPlacement
    )
    return RoundedCornerShape(
        topStart = if (corners.topLeft) radius else 0.dp,
        topEnd = if (corners.topRight) radius else 0.dp,
        bottomEnd = if (corners.bottomRight) radius else 0.dp,
        bottomStart = if (corners.bottomLeft) radius else 0.dp
    )
}

private data class PhotoCorners(
    val topLeft: Boolean,
    val topRight: Boolean,
    val bottomRight: Boolean,
    val bottomLeft: Boolean
)

private fun roundedCorners(
    index: Int,
    itemCount: Int,
    captionPlacement: CaptionPlacement
): PhotoCorners {
    var topLeft = false
    var topRight = false
    var bottomRight = false
    var bottomLeft = false
    when (visibleItemCount(itemCount)) {
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

    if (captionPlacement == CaptionPlacement.TOP) {
        topLeft = false
        topRight = false
    } else {
        bottomRight = false
        bottomLeft = false
    }

    return PhotoCorners(
        topLeft = topLeft,
        topRight = topRight,
        bottomRight = bottomRight,
        bottomLeft = bottomLeft
    )
}

private fun sanitizedLayoutOverride(
    layoutOverride: MediaGroupLayoutOverride?,
    itemCount: Int
): MediaGroupLayoutOverride? {
    val visibleCount = visibleItemCount(itemCount)
    if (!supportsInteractiveLayout(visibleCount) || layoutOverride == null) {
        return null
    }
    return sanitizedLayoutOverride(
        itemCount = visibleCount,
        primarySplitPermille = layoutOverride.primarySplitPermille,
        secondarySplitPermille = layoutOverride.secondarySplitPermille
            ?: defaultSecondarySplitPermille(visibleCount)
    )
}

private fun sanitizedLayoutOverride(
    itemCount: Int,
    primarySplitPermille: Int,
    secondarySplitPermille: Int
): MediaGroupLayoutOverride? {
    val visibleCount = visibleItemCount(itemCount)
    if (!supportsInteractiveLayout(visibleCount)) {
        return null
    }

    val primary = resolvedPrimarySplitPermille(visibleCount, primarySplitPermille)
    val secondary = if (visibleCount == 3) {
        resolvedSecondarySplitPermille(visibleCount, secondarySplitPermille)
    } else {
        null
    }
    val defaultPrimary = defaultPrimarySplitPermille(visibleCount)
    val defaultSecondary = if (visibleCount == 3) {
        defaultSecondarySplitPermille(visibleCount)
    } else {
        null
    }

    if (primary == defaultPrimary && secondary == defaultSecondary) {
        return null
    }

    return MediaGroupLayoutOverride(
        primarySplitPermille = primary,
        secondarySplitPermille = secondary
    )
}

private fun visibleItemCount(totalCount: Int): Int {
    return minOf(maxOf(totalCount, 0), MAX_VISIBLE_PHOTO_ITEMS)
}

private fun supportsInteractiveLayout(itemCount: Int): Boolean {
    return visibleItemCount(itemCount) in 2..3
}

private fun resolvedPrimarySplitPermille(
    itemCount: Int,
    layoutOverride: MediaGroupLayoutOverride?
): Int {
    return resolvedPrimarySplitPermille(
        itemCount = itemCount,
        value = layoutOverride?.primarySplitPermille ?: defaultPrimarySplitPermille(itemCount)
    )
}

private fun resolvedPrimarySplitPermille(
    itemCount: Int,
    value: Int
): Int {
    return value.coerceIn(
        minimumPrimarySplitPermille(itemCount),
        maximumPrimarySplitPermille(itemCount)
    )
}

private fun resolvedSecondarySplitPermille(
    itemCount: Int,
    layoutOverride: MediaGroupLayoutOverride?
): Int {
    return resolvedSecondarySplitPermille(
        itemCount = itemCount,
        value = layoutOverride?.secondarySplitPermille ?: defaultSecondarySplitPermille(itemCount)
    )
}

private fun resolvedSecondarySplitPermille(
    itemCount: Int,
    value: Int
): Int {
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
    return 500
}

private fun minimumPrimarySplitPermille(itemCount: Int): Int {
    return when (visibleItemCount(itemCount)) {
        3 -> 420
        2 -> 350
        else -> 500
    }
}

private fun maximumPrimarySplitPermille(itemCount: Int): Int {
    return when (visibleItemCount(itemCount)) {
        3 -> 720
        2 -> 650
        else -> 500
    }
}

@Composable
private fun PhotoOrderStrip(
    items: List<OutgoingPhotoDraftItem>,
    selectedIndex: Int,
    enabled: Boolean,
    onSelected: (Int) -> Unit,
    onMoveItem: (OutgoingPhotoDraftItem, Int) -> Unit,
    onRemoveItem: (OutgoingPhotoDraftItem) -> Unit
) {
    val threshold = 36.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEachIndexed { index, item ->
            val selected = index == selectedIndex
            Surface(
                onClick = { if (enabled) onSelected(index) },
                enabled = enabled,
                shape = RoundedCornerShape(8.dp),
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                tonalElevation = if (selected) 2.dp else 0.dp,
                modifier = Modifier
                    .size(74.dp)
                    .pointerInput(enabled, item.localPath) {
                        if (!enabled) return@pointerInput
                        var totalDragX = 0f
                        detectDragGestures(
                            onDragStart = {
                                totalDragX = 0f
                                onSelected(index)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                totalDragX += dragAmount.x
                                val thresholdPx = threshold.toPx()
                                when {
                                    totalDragX < -thresholdPx -> {
                                        onMoveItem(item, 1)
                                        totalDragX = 0f
                                    }
                                    totalDragX > thresholdPx -> {
                                        onMoveItem(item, -1)
                                        totalDragX = 0f
                                    }
                                }
                            }
                        )
                    }
            ) {
                Box {
                    PhotoTile(
                        item = item,
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    Text(
                        text = (index + 1).toString(),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .background(Color.Black.copy(alpha = 0.52f))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                    RemovePhotoButton(
                        enabled = enabled,
                        onClick = { onRemoveItem(item) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberLocalPreview(path: String, maxSizePx: Int): ImageBitmap? {
    var image by remember(path, maxSizePx) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path, maxSizePx) {
        image = withContext(Dispatchers.IO) {
            decodeLocalPreview(path, maxSizePx)?.asImageBitmap()
        }
    }
    return image
}

private fun decodeLocalPreview(path: String, maxSizePx: Int): Bitmap? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val imageDecoderBitmap = runCatching {
            val source = ImageDecoder.createSource(File(path))
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val maxDimension = maxOf(info.size.width, info.size.height).coerceAtLeast(1)
                var sampleSize = 1
                while (maxDimension / sampleSize > maxSizePx) {
                    sampleSize *= 2
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(sampleSize.coerceAtLeast(1))
            }
        }.getOrNull()
        if (imageDecoderBitmap != null) {
            return imageDecoderBitmap
        }
    }

    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(path, bounds)
    val maxDimension = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
    var sampleSize = 1
    while (maxDimension / sampleSize > maxSizePx) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize.coerceAtLeast(1)
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeFile(path, options)
}

private fun <T> List<T>.moved(fromIndex: Int, toIndex: Int): List<T> {
    if (fromIndex !in indices || toIndex !in indices || fromIndex == toIndex) {
        return this
    }
    return toMutableList().also { items ->
        val item = items.removeAt(fromIndex)
        items.add(toIndex, item)
    }
}
