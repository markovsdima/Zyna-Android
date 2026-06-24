package com.zyna.app.data.outgoing

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import com.zyna.app.data.media.BlurHashCodec
import java.io.File
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt

object OutgoingImagePreprocessor {
    private const val IMAGE_MAX_DIMENSION = 2048
    private const val THUMBNAIL_MAX_DIMENSION = 800
    private const val JPEG_QUALITY = 78
    private const val JPEG_MIME_TYPE = "image/jpeg"

    fun process(
        contentResolver: ContentResolver,
        uri: Uri,
        outputDir: File
    ): OutgoingPhotoDraftItem {
        return processDecoded(
            decoded = decodeBitmap(
                contentResolver = contentResolver,
                uri = uri,
                maxDecodeDimension = IMAGE_MAX_DIMENSION
            ),
            outputDir = outputDir
        )
    }

    fun processFile(file: File, outputDir: File): OutgoingPhotoDraftItem {
        require(file.isFile) { "Image file is not available" }
        return processDecoded(
            decoded = decodeBitmap(file, maxDecodeDimension = IMAGE_MAX_DIMENSION),
            outputDir = outputDir
        )
    }

    private fun processDecoded(decoded: Bitmap, outputDir: File): OutgoingPhotoDraftItem {
        outputDir.mkdirs()
        val filePrefix = "${System.currentTimeMillis()}-${UUID.randomUUID()}"
        val originalFile = File(outputDir, "$filePrefix-original.jpg")
        val thumbnailFile = File(outputDir, "$filePrefix-thumbnail.jpg")

        var original = decoded
        var thumbnail: Bitmap? = null
        return try {
            original = decoded.redrawWithin(maxDimension = IMAGE_MAX_DIMENSION)
            if (original !== decoded && !decoded.isRecycled) {
                decoded.recycle()
            }
            thumbnail = original.redrawWithin(maxDimension = THUMBNAIL_MAX_DIMENSION)

            original.writeJpeg(originalFile)
            thumbnail.writeJpeg(thumbnailFile)
            val blurhash = BlurHashCodec.encode(thumbnail)
            OutgoingPhotoDraftItem(
                localPath = originalFile.absolutePath,
                mimeType = JPEG_MIME_TYPE,
                width = original.width,
                height = original.height,
                sizeBytes = originalFile.length(),
                thumbnailLocalPath = thumbnailFile.absolutePath,
                thumbnailMimeType = JPEG_MIME_TYPE,
                thumbnailWidth = thumbnail.width,
                thumbnailHeight = thumbnail.height,
                thumbnailSizeBytes = thumbnailFile.length(),
                blurhash = blurhash
            )
        } catch (error: Throwable) {
            runCatching { originalFile.delete() }
            runCatching { thumbnailFile.delete() }
            throw error
        } finally {
            if (!original.isRecycled) {
                original.recycle()
            }
            thumbnail?.takeUnless { it === original || it.isRecycled }?.recycle()
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
        val bitmap = contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }?.ensureArgb8888() ?: error("Could not decode selected image")
        return bitmap.applyExifOrientation(contentResolver, uri)
    }

    private fun decodeBitmap(file: File, maxDecodeDimension: Int): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val source = ImageDecoder.createSource(file)
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
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize(
                sourceWidth = bounds.outWidth,
                sourceHeight = bounds.outHeight,
                maxDimension = maxDecodeDimension
            )
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
            ?.ensureArgb8888()
            ?: error("Could not decode selected image")
        return bitmap.applyExifOrientation(file)
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

    private fun Bitmap.redrawWithin(maxDimension: Int): Bitmap {
        val scale = min(
            maxDimension.toFloat() / width.coerceAtLeast(1).toFloat(),
            maxDimension.toFloat() / height.coerceAtLeast(1).toFloat()
        ).coerceAtMost(1f)
        val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
        val target = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(
            this,
            null,
            android.graphics.Rect(0, 0, targetWidth, targetHeight),
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

    private fun Bitmap.applyExifOrientation(file: File): Bitmap {
        val orientation = ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
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
                "Could not encode image"
            }
        }
    }
}
