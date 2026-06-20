package com.zyna.app.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

object BlurHashCodec {
    private const val BASE83_ALPHABET =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~"
    private const val DEFAULT_MAX_PIXEL_SIZE = 32

    fun decodeToBitmap(
        blurHash: String?,
        targetWidthPx: Int,
        targetHeightPx: Int,
        maxPixelSize: Int = DEFAULT_MAX_PIXEL_SIZE
    ): Bitmap? {
        val normalized = blurHash?.takeIf { it.isNotBlank() } ?: return null
        val sizeFlag = decodeBase83(normalized, 0, 1) ?: return null
        val componentX = (sizeFlag % 9) + 1
        val componentY = (sizeFlag / 9) + 1
        val expectedLength = 4 + 2 * componentX * componentY
        if (normalized.length != expectedLength) {
            return null
        }

        val quantizedMaximumValue = decodeBase83(normalized, 1, 1) ?: return null
        val maximumValue = (quantizedMaximumValue + 1).toFloat() / 166f
        val colors = Array(componentX * componentY) { FloatArray(3) }
        decodeDC(decodeBase83(normalized, 2, 4) ?: return null, colors[0])
        for (index in 1 until colors.size) {
            decodeAC(
                value = decodeBase83(normalized, 4 + index * 2, 2) ?: return null,
                maximumValue = maximumValue,
                out = colors[index]
            )
        }

        val (width, height) = previewSize(
            targetWidthPx = targetWidthPx,
            targetHeightPx = targetHeightPx,
            maxPixelSize = maxPixelSize
        )
        val pixels = IntArray(width * height)
        var pixelIndex = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                var red = 0f
                var green = 0f
                var blue = 0f
                for (componentYIndex in 0 until componentY) {
                    for (componentXIndex in 0 until componentX) {
                        val basis = (
                            cos(PI * componentXIndex.toDouble() * x.toDouble() / width.toDouble()) *
                                cos(PI * componentYIndex.toDouble() * y.toDouble() / height.toDouble())
                            ).toFloat()
                        val color = colors[componentXIndex + componentYIndex * componentX]
                        red += color[0] * basis
                        green += color[1] * basis
                        blue += color[2] * basis
                    }
                }
                pixels[pixelIndex++] = Color.rgb(
                    linearToSrgb(red),
                    linearToSrgb(green),
                    linearToSrgb(blue)
                )
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    fun encodeFile(
        path: String,
        componentX: Int = 3,
        componentY: Int = 3,
        maxPixelSize: Int = DEFAULT_MAX_PIXEL_SIZE
    ): String? {
        if (componentX !in 1..9 || componentY !in 1..9) {
            return null
        }
        val bitmap = decodeSampledBitmap(path, maxPixelSize) ?: return null
        return try {
            encode(bitmap = bitmap, componentX = componentX, componentY = componentY)
        } finally {
            bitmap.recycle()
        }
    }

    fun encode(bitmap: Bitmap, componentX: Int = 3, componentY: Int = 3): String? {
        if (componentX !in 1..9 || componentY !in 1..9 || bitmap.width <= 0 || bitmap.height <= 0) {
            return null
        }

        val factors = Array(componentX * componentY) { FloatArray(3) }
        for (componentYIndex in 0 until componentY) {
            for (componentXIndex in 0 until componentX) {
                val normalisation = if (componentXIndex == 0 && componentYIndex == 0) 1f else 2f
                multiplyBasisFunction(
                    bitmap = bitmap,
                    normalisation = normalisation,
                    componentX = componentXIndex,
                    componentY = componentYIndex,
                    out = factors[componentXIndex + componentYIndex * componentX]
                )
            }
        }

        val builder = StringBuilder()
        builder.append(encodeBase83((componentX - 1) + (componentY - 1) * 9, 1))
        val acFactors = factors.drop(1)
        val maximumValue = if (acFactors.isEmpty()) {
            builder.append(encodeBase83(0, 1))
            1f
        } else {
            val actualMaximumValue = acFactors.maxOf { factor ->
                max(abs(factor[0]), max(abs(factor[1]), abs(factor[2]))).toDouble()
            }.toFloat()
            val quantizedMaximumValue = floor(actualMaximumValue * 166f - 0.5f)
                .roundToInt()
                .coerceIn(0, 82)
            builder.append(encodeBase83(quantizedMaximumValue, 1))
            (quantizedMaximumValue + 1).toFloat() / 166f
        }

        builder.append(encodeBase83(encodeDC(factors[0]), 4))
        for (factor in acFactors) {
            builder.append(encodeBase83(encodeAC(factor, maximumValue), 2))
        }
        return builder.toString()
    }

    private fun previewSize(
        targetWidthPx: Int,
        targetHeightPx: Int,
        maxPixelSize: Int
    ): Pair<Int, Int> {
        val maxSide = maxPixelSize.coerceAtLeast(1)
        val targetWidth = targetWidthPx.coerceAtLeast(1)
        val targetHeight = targetHeightPx.coerceAtLeast(1)
        val scale = maxSide.toFloat() / max(targetWidth, targetHeight).toFloat()
        return Pair(
            (targetWidth * scale).roundToInt().coerceIn(1, maxSide),
            (targetHeight * scale).roundToInt().coerceIn(1, maxSide)
        )
    }

    private fun decodeSampledBitmap(path: String, maxPixelSize: Int): Bitmap? {
        val file = File(path)
        if (!file.isFile) {
            return null
        }
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, bounds)
        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return null
        }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                maxPixelSize = maxPixelSize
            )
        }
        return BitmapFactory.decodeFile(path, options)
    }

    private fun sampleSize(sourceWidth: Int, sourceHeight: Int, maxPixelSize: Int): Int {
        val maxSide = maxPixelSize.coerceAtLeast(1)
        val sourceMaxSide = max(sourceWidth, sourceHeight)
        var sample = 1
        while (sourceMaxSide / (sample * 2) >= maxSide) {
            sample *= 2
        }
        return sample
    }

    private fun multiplyBasisFunction(
        bitmap: Bitmap,
        normalisation: Float,
        componentX: Int,
        componentY: Int,
        out: FloatArray
    ) {
        var red = 0f
        var green = 0f
        var blue = 0f
        val width = bitmap.width
        val height = bitmap.height
        for (y in 0 until height) {
            for (x in 0 until width) {
                val basis = normalisation *
                    cos(PI * componentX.toDouble() * x.toDouble() / width.toDouble()).toFloat() *
                    cos(PI * componentY.toDouble() * y.toDouble() / height.toDouble()).toFloat()
                val pixel = bitmap.getPixel(x, y)
                red += basis * srgbToLinear(Color.red(pixel))
                green += basis * srgbToLinear(Color.green(pixel))
                blue += basis * srgbToLinear(Color.blue(pixel))
            }
        }
        val scale = 1f / (width * height).toFloat()
        out[0] = red * scale
        out[1] = green * scale
        out[2] = blue * scale
    }

    private fun decodeDC(value: Int, out: FloatArray) {
        out[0] = srgbToLinear((value shr 16) and 255)
        out[1] = srgbToLinear((value shr 8) and 255)
        out[2] = srgbToLinear(value and 255)
    }

    private fun decodeAC(value: Int, maximumValue: Float, out: FloatArray) {
        val quantizedRed = value / (19 * 19)
        val quantizedGreen = (value / 19) % 19
        val quantizedBlue = value % 19
        out[0] = signPow((quantizedRed - 9).toFloat() / 9f, 2f) * maximumValue
        out[1] = signPow((quantizedGreen - 9).toFloat() / 9f, 2f) * maximumValue
        out[2] = signPow((quantizedBlue - 9).toFloat() / 9f, 2f) * maximumValue
    }

    private fun encodeDC(value: FloatArray): Int {
        val red = linearToSrgb(value[0])
        val green = linearToSrgb(value[1])
        val blue = linearToSrgb(value[2])
        return (red shl 16) + (green shl 8) + blue
    }

    private fun encodeAC(value: FloatArray, maximumValue: Float): Int {
        val red = floor(signPow(value[0] / maximumValue, 0.5f) * 9f + 9.5f)
            .roundToInt()
            .coerceIn(0, 18)
        val green = floor(signPow(value[1] / maximumValue, 0.5f) * 9f + 9.5f)
            .roundToInt()
            .coerceIn(0, 18)
        val blue = floor(signPow(value[2] / maximumValue, 0.5f) * 9f + 9.5f)
            .roundToInt()
            .coerceIn(0, 18)
        return red * 19 * 19 + green * 19 + blue
    }

    private fun srgbToLinear(value: Int): Float {
        val bounded = value.coerceIn(0, 255).toFloat() / 255f
        return if (bounded <= 0.04045f) {
            bounded / 12.92f
        } else {
            ((bounded + 0.055f) / 1.055f).pow(2.4f)
        }
    }

    private fun linearToSrgb(value: Float): Int {
        val bounded = value.coerceIn(0f, 1f)
        return if (bounded <= 0.0031308f) {
            (bounded * 12.92f * 255f + 0.5f).toInt()
        } else {
            ((1.055f * bounded.pow(1f / 2.4f) - 0.055f) * 255f + 0.5f).toInt()
        }.coerceIn(0, 255)
    }

    private fun signPow(value: Float, exponent: Float): Float {
        return value.sign * abs(value).pow(exponent)
    }

    private val Float.sign: Float
        get() = when {
            this < 0f -> -1f
            this > 0f -> 1f
            else -> 0f
        }

    private fun decodeBase83(value: String, offset: Int, length: Int): Int? {
        if (offset < 0 || length <= 0 || offset + length > value.length) {
            return null
        }
        var result = 0
        for (index in offset until offset + length) {
            val digit = BASE83_ALPHABET.indexOf(value[index])
            if (digit < 0) {
                return null
            }
            result = result * 83 + digit
        }
        return result
    }

    private fun encodeBase83(value: Int, length: Int): String {
        val builder = StringBuilder()
        for (index in 1..length) {
            val divisor = intPow(83, length - index)
            val digit = (value / divisor) % 83
            builder.append(BASE83_ALPHABET[digit])
        }
        return builder.toString()
    }

    private fun intPow(base: Int, exponent: Int): Int {
        var result = 1
        repeat(exponent) {
            result *= base
        }
        return result
    }
}
