package com.zyna.app.ui.glass

import android.graphics.Bitmap

/** Small CPU blur used only by the bitmap fallback path. */
internal object GlassBoxBlur {
    fun blur(bitmap: Bitmap, radius: Int, passes: Int = 2) {
        val safeRadius = radius.coerceAtLeast(1)
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 1 || height <= 1) {
            return
        }

        val pixels = IntArray(width * height)
        val scratch = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        repeat(passes.coerceAtLeast(1)) {
            blurHorizontal(pixels, scratch, width, height, safeRadius)
            blurVertical(scratch, pixels, width, height, safeRadius)
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun blurHorizontal(input: IntArray, output: IntArray, width: Int, height: Int, radius: Int) {
        val window = radius * 2 + 1
        for (y in 0 until height) {
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0
            val row = y * width

            for (x in -radius..radius) {
                val pixel = input[row + x.coerceIn(0, width - 1)]
                alpha += pixel ushr 24
                red += pixel shr 16 and 0xff
                green += pixel shr 8 and 0xff
                blue += pixel and 0xff
            }

            for (x in 0 until width) {
                output[row + x] =
                    (alpha / window shl 24) or
                        (red / window shl 16) or
                        (green / window shl 8) or
                        (blue / window)

                val removeX = (x - radius).coerceIn(0, width - 1)
                val addX = (x + radius + 1).coerceIn(0, width - 1)
                val remove = input[row + removeX]
                val add = input[row + addX]
                alpha += (add ushr 24) - (remove ushr 24)
                red += (add shr 16 and 0xff) - (remove shr 16 and 0xff)
                green += (add shr 8 and 0xff) - (remove shr 8 and 0xff)
                blue += (add and 0xff) - (remove and 0xff)
            }
        }
    }

    private fun blurVertical(input: IntArray, output: IntArray, width: Int, height: Int, radius: Int) {
        val window = radius * 2 + 1
        for (x in 0 until width) {
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0

            for (y in -radius..radius) {
                val pixel = input[y.coerceIn(0, height - 1) * width + x]
                alpha += pixel ushr 24
                red += pixel shr 16 and 0xff
                green += pixel shr 8 and 0xff
                blue += pixel and 0xff
            }

            for (y in 0 until height) {
                output[y * width + x] =
                    (alpha / window shl 24) or
                        (red / window shl 16) or
                        (green / window shl 8) or
                        (blue / window)

                val removeY = (y - radius).coerceIn(0, height - 1)
                val addY = (y + radius + 1).coerceIn(0, height - 1)
                val remove = input[removeY * width + x]
                val add = input[addY * width + x]
                alpha += (add ushr 24) - (remove ushr 24)
                red += (add shr 16 and 0xff) - (remove shr 16 and 0xff)
                green += (add shr 8 and 0xff) - (remove shr 8 and 0xff)
                blue += (add and 0xff) - (remove and 0xff)
            }
        }
    }
}
