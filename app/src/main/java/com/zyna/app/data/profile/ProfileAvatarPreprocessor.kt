package com.zyna.app.data.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.os.Build
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

data class ProfileAvatarDraft(
    val localPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int
)

object ProfileAvatarPreprocessor {
    private const val AVATAR_SIZE_PX = 768
    private const val PREVIEW_MAX_DIMENSION = 2048
    private const val EXPORT_MAX_DIMENSION = 3072
    private const val JPEG_QUALITY = 85
    private const val JPEG_MIME_TYPE = "image/jpeg"

    fun loadPreview(sourceFile: File): Bitmap {
        require(sourceFile.isFile) { "Avatar source file is not available" }
        return decodeBitmap(
            sourceFile = sourceFile,
            maxDecodeDimension = PREVIEW_MAX_DIMENSION
        )
    }

    fun process(
        sourceFile: File,
        crop: ProfileAvatarCropSpec,
        outputDir: File
    ): ProfileAvatarDraft {
        require(sourceFile.isFile) { "Avatar source file is not available" }
        outputDir.mkdirs()
        val outputFile = File(
            outputDir,
            "${System.currentTimeMillis()}-${UUID.randomUUID()}-avatar.jpg"
        )
        var decoded: Bitmap? = null
        var avatar: Bitmap? = null
        try {
            decoded = decodeBitmap(
                sourceFile = sourceFile,
                maxDecodeDimension = EXPORT_MAX_DIMENSION
            )
            avatar = decoded.cropSquare(
                sizePx = AVATAR_SIZE_PX,
                crop = crop
            )
            avatar.writeJpeg(outputFile)
            return ProfileAvatarDraft(
                localPath = outputFile.absolutePath,
                mimeType = JPEG_MIME_TYPE,
                sizeBytes = outputFile.length(),
                width = avatar.width,
                height = avatar.height
            )
        } catch (error: Throwable) {
            runCatching { outputFile.delete() }
            throw error
        } finally {
            avatar?.takeUnless { it.isRecycled }?.recycle()
            decoded?.takeUnless { it === avatar || it.isRecycled }?.recycle()
        }
    }

    private fun decodeBitmap(
        sourceFile: File,
        maxDecodeDimension: Int
    ): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = ImageDecoder.createSource(sourceFile)
                return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                    val sourceMax = maxOf(info.size.width, info.size.height).coerceAtLeast(1)
                    if (sourceMax > maxDecodeDimension) {
                        val scale = maxDecodeDimension.toFloat() / sourceMax
                        decoder.setTargetSize(
                            (info.size.width * scale).roundToInt().coerceAtLeast(1),
                            (info.size.height * scale).roundToInt().coerceAtLeast(1)
                        )
                    }
                }.ensureArgb8888()
            } catch (_: Exception) {
                // BitmapFactory remains a compatibility fallback for decoders
                // that ImageDecoder cannot open. VM errors must not be swallowed.
            }
        }

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(sourceFile.absolutePath, bounds)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize(
                sourceWidth = bounds.outWidth,
                sourceHeight = bounds.outHeight,
                maxDimension = maxDecodeDimension
            )
        }
        val decoded = BitmapFactory.decodeFile(sourceFile.absolutePath, options)
            ?.ensureArgb8888()
            ?: error("Could not decode selected image")
        return decoded.applyExifOrientation(sourceFile)
    }

    private fun sampleSize(sourceWidth: Int, sourceHeight: Int, maxDimension: Int): Int {
        val sourceMax = maxOf(sourceWidth, sourceHeight).coerceAtLeast(1)
        val targetMax = maxDimension.coerceAtLeast(1)
        var sampleSize = 1
        while (sourceMax / sampleSize > targetMax) {
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun Bitmap.cropSquare(sizePx: Int, crop: ProfileAvatarCropSpec): Bitmap {
        val sourceRect = ProfileAvatarCropGeometry.sourceRect(
            crop = crop,
            sourceWidth = width,
            sourceHeight = height
        )
        val targetSize = sizePx.coerceAtLeast(1)
        val target = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        val matrix = Matrix().apply {
            setRectToRect(
                RectF(sourceRect.left, sourceRect.top, sourceRect.right, sourceRect.bottom),
                RectF(0f, 0f, targetSize.toFloat(), targetSize.toFloat()),
                Matrix.ScaleToFit.FILL
            )
        }
        canvas.drawBitmap(
            this,
            matrix,
            paint
        )
        return target
    }

    private fun Bitmap.ensureArgb8888(): Bitmap {
        if (config == Bitmap.Config.ARGB_8888) {
            return this
        }
        val converted = copy(Bitmap.Config.ARGB_8888, false)
        recycle()
        return converted
    }

    private fun Bitmap.applyExifOrientation(sourceFile: File): Bitmap {
        val orientation = sourceFile.inputStream().use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }
        return applyExifOrientation(orientation)
    }

    private fun Bitmap.applyExifOrientation(orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return this
        }
        val transformed = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
        if (transformed !== this) {
            recycle()
        }
        return transformed.ensureArgb8888()
    }

    private fun Bitmap.writeJpeg(file: File) {
        file.outputStream().use { output ->
            check(compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                "Could not encode avatar"
            }
        }
    }
}
