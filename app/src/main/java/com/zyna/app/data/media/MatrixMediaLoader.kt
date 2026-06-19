package com.zyna.app.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixImageInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MatrixMediaLoader(
    private val matrixClientService: MatrixClientService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val inFlight = mutableMapOf<String, Deferred<Bitmap?>>()
    private val memoryCache = object : LruCache<String, Bitmap>(memoryCacheSizeBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
    }

    fun cachedImage(imageInfo: MatrixImageInfo): Bitmap? {
        return memoryCache.get(cacheKey(imageInfo))
    }

    fun loadImage(
        imageInfo: MatrixImageInfo,
        targetWidthPx: Int,
        targetHeightPx: Int,
        onLoaded: (Bitmap?) -> Unit
    ): AutoCloseable {
        val key = cacheKey(imageInfo)
        if (memoryCache.get(key) != null) {
            return NoopCloseable
        }

        val waiter = scope.launch {
            val bitmap = deferredFor(key, imageInfo, targetWidthPx, targetHeightPx).await()
            withContext(Dispatchers.Main.immediate) {
                onLoaded(bitmap)
            }
        }
        return AutoCloseable { waiter.cancel() }
    }

    fun clear() {
        synchronized(lock) {
            inFlight.values.forEach { it.cancel() }
            inFlight.clear()
            memoryCache.evictAll()
        }
    }

    fun shutdown() {
        scope.cancel()
    }

    private fun deferredFor(
        key: String,
        imageInfo: MatrixImageInfo,
        targetWidthPx: Int,
        targetHeightPx: Int
    ): Deferred<Bitmap?> {
        memoryCache.get(key)?.let { bitmap ->
            return CompletableDeferred(bitmap)
        }

        synchronized(lock) {
            memoryCache.get(key)?.let { bitmap ->
                return CompletableDeferred(bitmap)
            }
            inFlight[key]?.let { return it }

            val deferred = scope.async {
                val bitmap = loadBitmap(imageInfo, targetWidthPx, targetHeightPx)
                if (bitmap != null) {
                    memoryCache.put(key, bitmap)
                }
                bitmap
            }
            deferred.invokeOnCompletion {
                synchronized(lock) {
                    if (inFlight[key] === deferred) {
                        inFlight.remove(key)
                    }
                }
            }
            inFlight[key] = deferred
            return deferred
        }
    }

    private suspend fun loadBitmap(
        imageInfo: MatrixImageInfo,
        targetWidthPx: Int,
        targetHeightPx: Int
    ): Bitmap? {
        imageInfo.localPath?.takeIf { it.isNotBlank() }?.let { path ->
            return decodeLocalBitmap(path, targetWidthPx, targetHeightPx)
        }

        val bytes = imageInfo.thumbnailSourceJson
            ?.let { sourceJson ->
                runMediaLoad { matrixClientService.loadMediaContent(sourceJson) }
            }
            ?: runMediaLoad {
                matrixClientService.loadMediaThumbnail(
                    sourceJson = imageInfo.sourceJson,
                    width = targetWidthPx.coerceAtLeast(1),
                    height = targetHeightPx.coerceAtLeast(1)
                )
            }
            ?: runMediaLoad { matrixClientService.loadMediaContent(imageInfo.sourceJson) }

        if (bytes == null) {
            Log.w(TAG, "Failed to load image media")
            return null
        }
        return decodeBitmap(bytes, targetWidthPx, targetHeightPx)
    }

    private fun decodeLocalBitmap(
        path: String,
        targetWidthPx: Int,
        targetHeightPx: Int
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, bounds)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(
                sourceWidth = bounds.outWidth,
                sourceHeight = bounds.outHeight,
                targetWidth = targetWidthPx,
                targetHeight = targetHeightPx
            )
        }
        return BitmapFactory.decodeFile(path, decodeOptions)
    }

    private suspend fun runMediaLoad(block: suspend () -> ByteArray): ByteArray? {
        return try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.d(TAG, "Media load attempt failed", error)
            null
        }
    }

    private fun decodeBitmap(
        bytes: ByteArray,
        targetWidthPx: Int,
        targetHeightPx: Int
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(
                sourceWidth = bounds.outWidth,
                sourceHeight = bounds.outHeight,
                targetWidth = targetWidthPx,
                targetHeight = targetHeightPx
            )
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
    }

    private fun sampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return 1
        }
        var sample = 1
        while (
            sourceWidth / (sample * 2) >= targetWidth &&
            sourceHeight / (sample * 2) >= targetHeight
        ) {
            sample *= 2
        }
        return sample
    }

    private fun cacheKey(imageInfo: MatrixImageInfo): String {
        return imageInfo.localPath ?: imageInfo.thumbnailSourceJson ?: imageInfo.sourceJson
    }

    private object NoopCloseable : AutoCloseable {
        override fun close() = Unit
    }

    private companion object {
        const val TAG = "MatrixMediaLoader"

        fun memoryCacheSizeBytes(): Int {
            val maxMemory = Runtime.getRuntime().maxMemory()
            return (maxMemory / 8L)
                .coerceAtMost(64L * 1024L * 1024L)
                .toInt()
        }
    }
}
