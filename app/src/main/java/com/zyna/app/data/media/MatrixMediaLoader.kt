package com.zyna.app.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixImageInfo
import java.io.File
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
import kotlin.math.max
import kotlin.math.roundToInt

class MatrixMediaLoader(
    private val matrixClientService: MatrixClientService,
    context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val diskCache = MatrixMediaDiskCache(
        directory = File(context.cacheDir, DISK_CACHE_DIRECTORY)
    )
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

    fun cachedPreviewImage(
        imageInfo: MatrixImageInfo,
        targetWidthPx: Int,
        targetHeightPx: Int
    ): Bitmap? {
        cachedImage(imageInfo)?.let { return it }
        val blurHashKey = blurHashMemoryCacheKey(imageInfo, targetWidthPx, targetHeightPx)
            ?: return null
        return memoryCache.get(blurHashKey)
    }

    fun cachedImage(
        imageInfo: MatrixImageInfo,
        targetWidthPx: Int,
        targetHeightPx: Int,
        quality: MatrixMediaImageQuality
    ): Bitmap? {
        return memoryCache.get(memoryCacheKey(imageInfo, targetWidthPx, targetHeightPx, quality))
    }

    fun loadImage(
        imageInfo: MatrixImageInfo,
        targetWidthPx: Int,
        targetHeightPx: Int,
        quality: MatrixMediaImageQuality = MatrixMediaImageQuality.CELL,
        onLoaded: (Bitmap?) -> Unit
    ): AutoCloseable {
        val key = memoryCacheKey(imageInfo, targetWidthPx, targetHeightPx, quality)
        if (memoryCache.get(key) != null) {
            return NoopCloseable
        }

        val waiter = scope.launch {
            if (quality == MatrixMediaImageQuality.CELL) {
                decodeBlurHashPreview(imageInfo, targetWidthPx, targetHeightPx)?.let { preview ->
                    withContext(Dispatchers.Main.immediate) {
                        onLoaded(preview)
                    }
                }
            }
            val bitmap = deferredFor(key, imageInfo, targetWidthPx, targetHeightPx, quality).await()
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
        diskCache.clear()
    }

    fun shutdown() {
        scope.cancel()
    }

    private fun deferredFor(
        key: String,
        imageInfo: MatrixImageInfo,
        targetWidthPx: Int,
        targetHeightPx: Int,
        quality: MatrixMediaImageQuality
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
                val bitmap = loadBitmap(imageInfo, targetWidthPx, targetHeightPx, quality)
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
        targetHeightPx: Int,
        quality: MatrixMediaImageQuality
    ): Bitmap? {
        imageInfo.localPath?.takeIf { it.isNotBlank() }?.let { path ->
            return decodeLocalBitmap(path, targetWidthPx, targetHeightPx)
        }

        val bytes = loadRemoteBytes(
            imageInfo = imageInfo,
            targetWidthPx = targetWidthPx,
            targetHeightPx = targetHeightPx,
            quality = quality
        )

        if (bytes == null) {
            Log.w(TAG, "Failed to load image media")
            return null
        }
        return decodeBitmap(bytes, targetWidthPx, targetHeightPx)
    }

    private suspend fun loadRemoteBytes(
        imageInfo: MatrixImageInfo,
        targetWidthPx: Int,
        targetHeightPx: Int,
        quality: MatrixMediaImageQuality
    ): ByteArray? {
        val requests = remoteMediaRequests(
            imageInfo = imageInfo,
            targetWidthPx = targetWidthPx,
            targetHeightPx = targetHeightPx,
            quality = quality
        )
        requests.forEach { request ->
            diskCache.read(request.cacheKey)?.let { bytes ->
                return bytes
            }
        }

        requests.forEach { request ->
            val bytes = runMediaLoad(request.load) ?: return@forEach
            diskCache.write(request.cacheKey, bytes)
            return bytes
        }
        return null
    }

    private fun remoteMediaRequests(
        imageInfo: MatrixImageInfo,
        targetWidthPx: Int,
        targetHeightPx: Int,
        quality: MatrixMediaImageQuality
    ): List<MediaBytesRequest> {
        val width = targetWidthPx.coerceAtLeast(1)
        val height = targetHeightPx.coerceAtLeast(1)
        val fullContentRequest = MediaBytesRequest(
            cacheKey = contentDiskCacheKey(imageInfo.sourceJson),
            load = { matrixClientService.loadMediaContent(imageInfo.sourceJson) }
        )
        val serverThumbnailRequest = MediaBytesRequest(
            cacheKey = "thumbnail:$width:$height:${imageInfo.sourceJson}",
            load = {
                matrixClientService.loadMediaThumbnail(
                    sourceJson = imageInfo.sourceJson,
                    width = width,
                    height = height
                )
            }
        )
        return buildList {
            when (quality) {
                MatrixMediaImageQuality.CELL -> {
                    imageInfo.thumbnailSourceJson
                        ?.takeIf { it.isNotBlank() }
                        ?.let { sourceJson ->
                            add(
                                MediaBytesRequest(
                                    cacheKey = contentDiskCacheKey(sourceJson),
                                    load = { matrixClientService.loadMediaContent(sourceJson) }
                                )
                            )
                        }
                    add(serverThumbnailRequest)
                    add(fullContentRequest)
                }
                MatrixMediaImageQuality.VIEWER -> {
                    add(fullContentRequest)
                }
            }
        }
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
            ?.scaleDownToFill(targetWidthPx, targetHeightPx)
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
            ?.scaleDownToFill(targetWidthPx, targetHeightPx)
    }

    private fun decodeBlurHashPreview(
        imageInfo: MatrixImageInfo,
        targetWidthPx: Int,
        targetHeightPx: Int
    ): Bitmap? {
        val key = blurHashMemoryCacheKey(imageInfo, targetWidthPx, targetHeightPx)
            ?: return null
        memoryCache.get(key)?.let { return it }
        val preview = BlurHashCodec.decodeToBitmap(
            blurHash = imageInfo.blurhash,
            targetWidthPx = targetWidthPx,
            targetHeightPx = targetHeightPx
        ) ?: return null
        synchronized(lock) {
            memoryCache.get(key)?.let { cached ->
                preview.recycle()
                return cached
            }
            memoryCache.put(key, preview)
        }
        return preview
    }

    private fun Bitmap.scaleDownToFill(targetWidthPx: Int, targetHeightPx: Int): Bitmap {
        if (targetWidthPx <= 0 || targetHeightPx <= 0 || width <= 0 || height <= 0) {
            return this
        }
        val scale = max(
            targetWidthPx.toFloat() / width.toFloat(),
            targetHeightPx.toFloat() / height.toFloat()
        ).coerceAtMost(1f)
        if (scale >= 0.98f) {
            return this
        }

        val scaledWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val scaledHeight = (height * scale).roundToInt().coerceAtLeast(1)
        if (scaledWidth >= width && scaledHeight >= height) {
            return this
        }

        return try {
            Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
                .also { scaled ->
                    if (scaled !== this) {
                        recycle()
                    }
                }
        } catch (_: Throwable) {
            this
        }
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

    private fun memoryCacheKey(
        imageInfo: MatrixImageInfo,
        targetWidthPx: Int,
        targetHeightPx: Int,
        quality: MatrixMediaImageQuality
    ): String {
        return when (quality) {
            MatrixMediaImageQuality.CELL -> cacheKey(imageInfo)
            MatrixMediaImageQuality.VIEWER ->
                "viewer:${targetWidthPx.coerceAtLeast(1)}x${targetHeightPx.coerceAtLeast(1)}:${cacheKey(imageInfo)}"
        }
    }

    private fun blurHashMemoryCacheKey(
        imageInfo: MatrixImageInfo,
        targetWidthPx: Int,
        targetHeightPx: Int
    ): String? {
        val blurHash = imageInfo.blurhash?.takeIf { it.isNotBlank() } ?: return null
        return "blurhash:${targetWidthPx.coerceAtLeast(1)}x${targetHeightPx.coerceAtLeast(1)}:$blurHash"
    }

    private fun contentDiskCacheKey(sourceJson: String): String {
        return "content:$sourceJson"
    }

    private data class MediaBytesRequest(
        val cacheKey: String,
        val load: suspend () -> ByteArray
    )

    private object NoopCloseable : AutoCloseable {
        override fun close() = Unit
    }

    private companion object {
        const val TAG = "MatrixMediaLoader"
        const val DISK_CACHE_DIRECTORY = "matrix_media"

        fun memoryCacheSizeBytes(): Int {
            val maxMemory = Runtime.getRuntime().maxMemory()
            return (maxMemory / 4L)
                .coerceAtMost(96L * 1024L * 1024L)
                .toInt()
        }
    }
}

enum class MatrixMediaImageQuality {
    CELL,
    VIEWER
}
