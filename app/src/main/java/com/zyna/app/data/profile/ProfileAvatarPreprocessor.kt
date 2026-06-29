package com.zyna.app.data.profile

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import java.io.File
import java.util.UUID

data class ProfileAvatarDraft(
    val localPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int
)

object ProfileAvatarPreprocessor {
    private const val AVATAR_SIZE_PX = 768
    private const val DECODE_MAX_DIMENSION = 1536
    private const val JPEG_QUALITY = 85
    private const val JPEG_MIME_TYPE = "image/jpeg"

    fun process(
        contentResolver: ContentResolver,
        uri: Uri,
        outputDir: File
    ): ProfileAvatarDraft {
        outputDir.mkdirs()
        val outputFile = File(
            outputDir,
            "${System.currentTimeMillis()}-${UUID.randomUUID()}-avatar.jpg"
        )
        var decoded: Bitmap? = null
        var avatar: Bitmap? = null
        try {
            decoded = decodeBitmap(
                contentResolver = contentResolver,
                uri = uri,
                maxDecodeDimension = DECODE_MAX_DIMENSION
            )
            avatar = decoded.centerCropSquare(AVATAR_SIZE_PX)
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
        contentResolver: ContentResolver,
        uri: Uri,
        maxDecodeDimension: Int
    ): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val source = ImageDecoder.createSource(contentResolver, uri)
                return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val sampleSize = sampleSize(
                        sourceWidth = info.size.width,
                        sourceHeight = info.size.height,
                        maxDimension = maxDecodeDimension
                    )
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetSampleSize(sampleSize)
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                }.ensureArgb8888()
            }
        }

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize(
                sourceWidth = bounds.outWidth,
                sourceHeight = bounds.outHeight,
                maxDimension = maxDecodeDimension
            )
        }
        val decoded = contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }?.ensureArgb8888() ?: error("Could not decode selected image")
        return decoded.applyExifOrientation(contentResolver, uri)
    }

    private fun sampleSize(sourceWidth: Int, sourceHeight: Int, maxDimension: Int): Int {
        val sourceMax = maxOf(sourceWidth, sourceHeight).coerceAtLeast(1)
        val targetMax = maxDimension.coerceAtLeast(1)
        var sampleSize = 1
        while (sourceMax / (sampleSize * 2) >= targetMax) {
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun Bitmap.centerCropSquare(sizePx: Int): Bitmap {
        val sourceSize = minOf(width, height).coerceAtLeast(1)
        val sourceLeft = ((width - sourceSize) / 2).coerceAtLeast(0)
        val sourceTop = ((height - sourceSize) / 2).coerceAtLeast(0)
        val targetSize = minOf(sizePx.coerceAtLeast(1), sourceSize)
        val target = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        canvas.drawBitmap(
            this,
            Rect(sourceLeft, sourceTop, sourceLeft + sourceSize, sourceTop + sourceSize),
            Rect(0, 0, targetSize, targetSize),
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

    private fun Bitmap.applyExifOrientation(contentResolver: ContentResolver, uri: Uri): Bitmap {
        val orientation = contentResolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
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
