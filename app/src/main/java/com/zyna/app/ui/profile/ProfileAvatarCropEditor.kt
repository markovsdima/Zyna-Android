package com.zyna.app.ui.profile

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zyna.app.R
import com.zyna.app.data.profile.ProfileAvatarCropGeometry
import com.zyna.app.data.profile.ProfileAvatarCropSpec
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun ProfileAvatarCropEditor(
    bitmap: Bitmap,
    isProcessing: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: (ProfileAvatarCropSpec) -> Unit
) {
    val cropState = remember(bitmap) { mutableStateOf(ProfileAvatarCropSpec()) }
    var crop by cropState

    BackHandler(enabled = !isProcessing, onBack = onDismiss)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onDismiss,
                enabled = !isProcessing,
                modifier = Modifier.testTag(PROFILE_AVATAR_CROP_CANCEL_TEST_TAG)
            ) {
                Text(
                    text = stringResource(R.string.common_cancel),
                    color = if (isProcessing) Color.White.copy(alpha = 0.45f) else Color.White
                )
            }
            Text(
                text = stringResource(R.string.profile_avatar_crop_title),
                modifier = Modifier.weight(1f),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            TextButton(
                onClick = { onConfirm(crop) },
                enabled = !isProcessing,
                modifier = Modifier.testTag(PROFILE_AVATAR_CROP_DONE_TEST_TAG)
            ) {
                Text(
                    text = stringResource(R.string.profile_avatar_crop_done),
                    color = if (isProcessing) Color.White.copy(alpha = 0.45f) else Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (isProcessing) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.2f)
            )
        } else {
            Spacer(modifier = Modifier.height(4.dp))
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            val side = minOf(
                (maxWidth - 32.dp).coerceAtLeast(1.dp),
                (maxHeight - 24.dp).coerceAtLeast(1.dp)
            )
            AvatarCropViewport(
                bitmap = bitmap,
                crop = crop,
                enabled = !isProcessing,
                cropDiameter = side,
                onTransformGesture = {
                        viewportSize,
                        centroidX,
                        centroidY,
                        panX,
                        panY,
                        zoomFactor ->
                    cropState.value = ProfileAvatarCropGeometry.transform(
                        crop = cropState.value,
                        sourceWidth = bitmap.width,
                        sourceHeight = bitmap.height,
                        viewportSize = viewportSize,
                        centroidX = centroidX,
                        centroidY = centroidY,
                        panX = panX,
                        panY = panY,
                        zoomFactor = zoomFactor
                    )
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        Text(
            text = stringResource(R.string.profile_avatar_crop_hint),
            modifier = Modifier.padding(horizontal = 24.dp),
            color = Color.White.copy(alpha = 0.68f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        if (!errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
        TextButton(
            onClick = { crop = ProfileAvatarCropSpec() },
            enabled = !isProcessing && crop != ProfileAvatarCropSpec(),
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.profile_avatar_crop_reset),
                color = if (!isProcessing && crop != ProfileAvatarCropSpec()) {
                    Color.White
                } else {
                    Color.White.copy(alpha = 0.38f)
                }
            )
        }
    }
}

@Composable
private fun AvatarCropViewport(
    bitmap: Bitmap,
    crop: ProfileAvatarCropSpec,
    enabled: Boolean,
    cropDiameter: Dp,
    onTransformGesture: (
        viewportSize: Float,
        centroidX: Float,
        centroidY: Float,
        panX: Float,
        panY: Float,
        zoomFactor: Float
    ) -> Unit,
    modifier: Modifier = Modifier
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val currentOnTransformGesture by rememberUpdatedState(onTransformGesture)
    val description = stringResource(R.string.profile_avatar_crop_preview_description)

    Box(
        modifier = modifier
            .testTag(PROFILE_AVATAR_CROP_VIEWPORT_TEST_TAG)
            .background(Color.Black)
            .clipToBounds()
            .semantics { contentDescription = description }
            .pointerInput(bitmap, enabled, cropDiameter) {
                if (!enabled) {
                    return@pointerInput
                }
                detectTransformGestures { centroid, pan, gestureZoom, _ ->
                    val viewport = cropDiameter.toPx()
                        .coerceAtMost(min(size.width, size.height).toFloat())
                        .coerceAtLeast(1f)
                    val cropLeft = (size.width - viewport) / 2f
                    val cropTop = (size.height - viewport) / 2f
                    currentOnTransformGesture(
                        viewport,
                        centroid.x - cropLeft,
                        centroid.y - cropTop,
                        pan.x,
                        pan.y,
                        gestureZoom
                    )
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val viewport = cropDiameter.toPx()
                .coerceAtMost(size.minDimension)
                .coerceAtLeast(1f)
            val cropLeft = (size.width - viewport) / 2f
            val cropTop = (size.height - viewport) / 2f
            val cropCenter = Offset(
                x = cropLeft + viewport / 2f,
                y = cropTop + viewport / 2f
            )
            val clamped = ProfileAvatarCropGeometry.clamp(
                crop = crop,
                sourceWidth = bitmap.width,
                sourceHeight = bitmap.height
            )
            val sourceCropSize = min(bitmap.width, bitmap.height).toFloat() / clamped.zoom
            val imageScale = viewport / sourceCropSize
            val imageWidth = (bitmap.width * imageScale).roundToInt().coerceAtLeast(1)
            val imageHeight = (bitmap.height * imageScale).roundToInt().coerceAtLeast(1)
            val imageLeft = (cropCenter.x - clamped.centerX * bitmap.width * imageScale)
                .roundToInt()
            val imageTop = (cropCenter.y - clamped.centerY * bitmap.height * imageScale)
                .roundToInt()

            drawImage(
                image = image,
                dstOffset = IntOffset(imageLeft, imageTop),
                dstSize = IntSize(imageWidth, imageHeight),
                filterQuality = FilterQuality.High
            )

            val cropBounds = Rect(
                left = cropLeft,
                top = cropTop,
                right = cropLeft + viewport,
                bottom = cropTop + viewport
            )
            val mask = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(0f, 0f, size.width, size.height))
                addOval(cropBounds)
            }
            drawPath(mask, color = Color.Black.copy(alpha = 0.58f))
            drawCircle(
                color = Color.White.copy(alpha = 0.72f),
                radius = (viewport / 2f - 0.5.dp.toPx()).coerceAtLeast(0f),
                center = cropCenter,
                style = Stroke(width = 1.dp.toPx())
            )
        }
    }
}

internal const val PROFILE_AVATAR_CROP_VIEWPORT_TEST_TAG = "profile_avatar_crop_viewport"
internal const val PROFILE_AVATAR_CROP_CANCEL_TEST_TAG = "profile_avatar_crop_cancel"
internal const val PROFILE_AVATAR_CROP_DONE_TEST_TAG = "profile_avatar_crop_done"
