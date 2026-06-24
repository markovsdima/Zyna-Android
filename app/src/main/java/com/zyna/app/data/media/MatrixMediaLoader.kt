package com.zyna.app.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Process
import android.util.Log
import android.util.LruCache
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixImageInfo
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private val inFlightPolicies = mutableMapOf<String, MediaLoadPolicy>()
    private val representativeCellKeys = mutableMapOf<String, String>()
    private val decodeSemaphore = Semaphore(MAX_PARALLEL_MEDIA_DECODES)
    private val prefetchDecodeSemaphore = Semaphore(MAX_PARALLEL_MEDIA_PREFETCH_DECODES)
    private val decodeThreadCounter = AtomicInteger(0)
    private val decodeDispatcher = Executors.newFixedThreadPool(MAX_PARALLEL_MEDIA_DECODES) { runnable ->
        Thread(
            {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                runnable.run()
            },
            "ZynaMediaDecode-${decodeThreadCounter.incrementAndGet()}"
        ).apply {
            isDaemon = true
        }
    }.asCoroutineDispatcher()
    private val memoryCache = object : LruCache<String, Bitmap>(memoryCacheSizeBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
    }

    fun cachedImage(imageInfo: MatrixImageInfo): Bitmap? {
        val baseKey = cacheKey(imageInfo)
        val cellKey = synchronized(lock) {
            representativeCellKeys[baseKey]
        } ?: return null
        memoryCache.get(cellKey)
            ?.takeIf { !it.isRecycled }
            ?.let { return it }
        synchronized(lock) {
            if (representativeCellKeys[baseKey] == cellKey) {
                representativeCellKeys.remove(baseKey)
            }
        }
        return null
    }

    fun cachedPreviewImage(
        imageInfo: MatrixImageInfo,
        targetWidthPx: Int,
        targetHeightPx: Int
    ): Bitmap? {
        cachedImage(
            imageInfo = imageInfo,
            targetWidthPx = targetWidthPx,
            targetHeightPx = targetHeightPx,
            quality = MatrixMediaImageQuality.CELL
        )?.let { return it }
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
            ?.takeIf { !it.isRecycled }
    }

    fun cachedAvatar(
        avatarUrl: String,
        sizePx: Int
    ): Bitmap? {
        return memoryCache.get(avatarMemoryCacheKey(avatarUrl, sizePx))
            ?.takeIf { !it.isRecycled }
    }

    fun hasCellImageCovering(
        imageInfo: MatrixImageInfo,
        targetWidthPx: Int,
        targetHeightPx: Int
    ): Boolean {
        val exactKey = memoryCacheKey(
            imageInfo = imageInfo,
            targetWidthPx = targetWidthPx,
            targetHeightPx = targetHeightPx,
            quality = MatrixMediaImageQuality.CELL
        )
        if (memoryCache.get(exactKey)?.isRecycled == false) {
            return true
        }

        val baseKey = cacheKey(imageInfo)
        val cellKey = synchronized(lock) {
            representativeCellKeys[baseKey]
        } ?: return false

        memoryCache.get(cellKey)?.let { bitmap ->
            return !bitmap.isRecycled &&
                bitmap.width >= targetWidthPx.coerceAtLeast(1) &&
                bitmap.height >= targetHeightPx.coerceAtLeast(1)
        }
        synchronized(lock) {
            if (representativeCellKeys[baseKey] == cellKey) {
                representativeCellKeys.remove(baseKey)
            }
        }
        return false
    }

    fun loadImage(
        imageInfo: MatrixImageInfo,
        targetWidthPx: Int,
        targetHeightPx: Int,
        quality: MatrixMediaImageQuality = MatrixMediaImageQuality.CELL,
        onLoaded: (Bitmap?) -> Unit
    ): AutoCloseable {
        val key = memoryCacheKey(imageInfo, targetWidthPx, targetHeightPx, quality)
        if (memoryCache.get(key)?.isRecycled == false) {
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
            val bitmap = deferredFor(
                memoryKey = key,
                imageInfo = imageInfo,
                targetWidthPx = targetWidthPx,
                targetHeightPx = targetHeightPx,
                quality = quality
            ).await()
            withContext(Dispatchers.Main.immediate) {
                onLoaded(bitmap)
            }
        }
        return AutoCloseable { waiter.cancel() }
    }

    fun loadAvatar(
        avatarUrl: String,
        sizePx: Int,
        onLoaded: (Bitmap?) -> Unit
    ): AutoCloseable {
        val key = avatarMemoryCacheKey(avatarUrl, sizePx)
        memoryCache.get(key)?.takeIf { !it.isRecycled }?.let { bitmap ->
            val waiter = scope.launch {
                withContext(Dispatchers.Main.immediate) {
                    onLoaded(bitmap)
                }
            }
            return AutoCloseable { waiter.cancel() }
        }

        val waiter = scope.launch {
            val bitmap = deferredForAvatar(
                memoryKey = key,
                avatarUrl = avatarUrl,
                sizePx = sizePx
            ).await()
            withContext(Dispatchers.Main.immediate) {
                onLoaded(bitmap)
            }
        }
        return AutoCloseable { waiter.cancel() }
    }

    fun prefetchImage(
        imageInfo: MatrixImageInfo,
        targetWidthPx: Int,
        targetHeightPx: Int,
        quality: MatrixMediaImageQuality = MatrixMediaImageQuality.CELL
    ) {
        val key = memoryCacheKey(imageInfo, targetWidthPx, targetHeightPx, quality)
        val flightKey = prefetchInFlightKey(key)
        if (isCachedOrInFlight(memoryKey = key, flightKey = flightKey)) {
            return
        }

        deferredFor(
            memoryKey = key,
            flightKey = flightKey,
            imageInfo = imageInfo,
            targetWidthPx = targetWidthPx,
            targetHeightPx = targetHeightPx,
            quality = quality,
            allowFullFallback = false,
            allowRemoteLoad = false,
            isPrefetch = true
        ).invokeOnCompletion { error ->
            if (error != null && error !is CancellationException) {
                Log.d(TAG, "Media prefetch failed", error)
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            inFlight.values.forEach { it.cancel() }
            inFlight.clear()
            inFlightPolicies.clear()
            representativeCellKeys.clear()
            memoryCache.evictAll()
        }
        diskCache.clear()
    }

    fun shutdown() {
        scope.cancel()
        decodeDispatcher.close()
    }

    private fun deferredFor(
        memoryKey: String,
        flightKey: String = memoryKey,
        imageInfo: MatrixImageInfo,
        targetWidthPx: Int,
        targetHeightPx: Int,
        quality: MatrixMediaImageQuality,
        allowFullFallback: Boolean = true,
        allowRemoteLoad: Boolean = true,
        isPrefetch: Boolean = false
    ): Deferred<Bitmap?> {
        memoryCache.get(memoryKey)?.takeIf { !it.isRecycled }?.let { bitmap ->
            return CompletableDeferred(bitmap)
        }

        synchronized(lock) {
            memoryCache.get(memoryKey)?.takeIf { !it.isRecycled }?.let { bitmap ->
                return CompletableDeferred(bitmap)
            }
            inFlight[flightKey]?.let { return it }
            if (flightKey != memoryKey) {
                inFlight[memoryKey]?.let { return it }
            }
            val prefetchDeferred = if (flightKey == memoryKey && allowRemoteLoad) {
                val prefetchKey = prefetchInFlightKey(memoryKey)
                inFlight[prefetchKey]?.also {
                    inFlightPolicies[prefetchKey]?.upgrade(
                        allowFullFallback = allowFullFallback,
                        allowRemoteLoad = allowRemoteLoad,
                        isPrefetch = isPrefetch
                    )
                }
            } else {
                null
            }
            val policy = MediaLoadPolicy(
                allowFullFallback = allowFullFallback,
                allowRemoteLoad = allowRemoteLoad,
                isPrefetch = isPrefetch
            )

            val deferred = scope.async {
                prefetchDeferred?.let { prefetch ->
                    awaitPrefetchOrNull(prefetch)?.let { return@async it }
                }
                val bitmap = loadBitmap(
                    imageInfo = imageInfo,
                    targetWidthPx = targetWidthPx,
                    targetHeightPx = targetHeightPx,
                    quality = quality,
                    policy = policy
                )
                if (bitmap != null) {
                    memoryCache.put(memoryKey, bitmap)
                    if (quality == MatrixMediaImageQuality.CELL) {
                        updateRepresentativeCell(
                            baseKey = cacheKey(imageInfo),
                            memoryKey = memoryKey,
                            bitmap = bitmap
                        )
                    }
                }
                bitmap
            }
            deferred.invokeOnCompletion {
                synchronized(lock) {
                    if (inFlight[flightKey] === deferred) {
                        inFlight.remove(flightKey)
                        inFlightPolicies.remove(flightKey)
                    }
                }
            }
            inFlight[flightKey] = deferred
            inFlightPolicies[flightKey] = policy
            return deferred
        }
    }

    private fun deferredForAvatar(
        memoryKey: String,
        avatarUrl: String,
        sizePx: Int
    ): Deferred<Bitmap?> {
        memoryCache.get(memoryKey)?.takeIf { !it.isRecycled }?.let { bitmap ->
            return CompletableDeferred(bitmap)
        }

        synchronized(lock) {
            memoryCache.get(memoryKey)?.takeIf { !it.isRecycled }?.let { bitmap ->
                return CompletableDeferred(bitmap)
            }
            inFlight[memoryKey]?.let { return it }

            val policy = MediaLoadPolicy(
                allowFullFallback = true,
                allowRemoteLoad = true,
                isPrefetch = false
            )
            val deferred = scope.async {
                val bitmap = loadAvatarBitmap(
                    avatarUrl = avatarUrl,
                    sizePx = sizePx,
                    policy = policy
                )
                if (bitmap != null) {
                    memoryCache.put(memoryKey, bitmap)
                }
                bitmap
            }
            deferred.invokeOnCompletion {
                synchronized(lock) {
                    if (inFlight[memoryKey] === deferred) {
                        inFlight.remove(memoryKey)
                    }
                }
            }
            inFlight[memoryKey] = deferred
            return deferred
        }
    }

    private suspend fun loadAvatarBitmap(
        avatarUrl: String,
        sizePx: Int,
        policy: MediaLoadPolicy
    ): Bitmap? {
        val size = sizePx.coerceAtLeast(1)
        val bytes = loadAvatarBytes(
            avatarUrl = avatarUrl,
            sizePx = size,
            policy = policy
        )
        if (bytes == null) {
            Log.d(TAG, "Failed to load avatar media")
            return null
        }
        return decodeBitmap(bytes, size, size, policy)
    }

    private suspend fun loadAvatarBytes(
        avatarUrl: String,
        sizePx: Int,
        policy: MediaLoadPolicy
    ): ByteArray? {
        val requests = avatarMediaRequests(
            avatarUrl = avatarUrl,
            sizePx = sizePx
        )
        requests.forEach { request ->
            diskCache.read(request.cacheKey)?.let { bytes ->
                return bytes
            }
        }

        if (!policy.allowRemoteLoad) {
            return null
        }

        requests.forEach { request ->
            val bytes = runMediaLoad(request.load) ?: return@forEach
            diskCache.write(request.cacheKey, bytes)
            return bytes
        }
        return null
    }

    private fun avatarMediaRequests(
        avatarUrl: String,
        sizePx: Int
    ): List<MediaBytesRequest> {
        val size = sizePx.coerceAtLeast(1)
        return listOf(
            MediaBytesRequest(
                cacheKey = "avatar-thumbnail:$size:$avatarUrl",
                load = {
                    matrixClientService.loadMediaThumbnailFromUrl(
                        url = avatarUrl,
                        width = size,
                        height = size
                    )
                }
            ),
            MediaBytesRequest(
                cacheKey = "avatar-content:$avatarUrl",
                load = { matrixClientService.loadMediaContentFromUrl(avatarUrl) }
            )
        )
    }

    private suspend fun loadBitmap(
        imageInfo: MatrixImageInfo,
        targetWidthPx: Int,
        targetHeightPx: Int,
        quality: MatrixMediaImageQuality,
        policy: MediaLoadPolicy
    ): Bitmap? {
        imageInfo.localPath?.takeIf { it.isNotBlank() }?.let { path ->
            return decodeLocalBitmap(path, targetWidthPx, targetHeightPx, policy)
        }

        val bytes = loadRemoteBytes(
            imageInfo = imageInfo,
            targetWidthPx = targetWidthPx,
            targetHeightPx = targetHeightPx,
            quality = quality,
            policy = policy
        )

        if (bytes == null) {
            if (policy.allowRemoteLoad) {
                Log.w(TAG, "Failed to load image media")
            }
            return null
        }
        return decodeBitmap(bytes, targetWidthPx, targetHeightPx, policy)
    }

    private suspend fun loadRemoteBytes(
        imageInfo: MatrixImageInfo,
        targetWidthPx: Int,
        targetHeightPx: Int,
        quality: MatrixMediaImageQuality,
        policy: MediaLoadPolicy
    ): ByteArray? {
        val diskAllowFullFallback = policy.allowFullFallback
        val diskRequests = remoteMediaRequests(
            imageInfo = imageInfo,
            targetWidthPx = targetWidthPx,
            targetHeightPx = targetHeightPx,
            quality = quality,
            allowFullFallback = diskAllowFullFallback
        )
        diskRequests.forEach { request ->
            diskCache.read(request.cacheKey)?.let { bytes ->
                return bytes
            }
        }

        if (!policy.allowRemoteLoad) {
            return null
        }

        val remoteAllowFullFallback = policy.allowFullFallback
        val remoteRequests = if (remoteAllowFullFallback == diskAllowFullFallback) {
            diskRequests
        } else {
            remoteMediaRequests(
                imageInfo = imageInfo,
                targetWidthPx = targetWidthPx,
                targetHeightPx = targetHeightPx,
                quality = quality,
                allowFullFallback = remoteAllowFullFallback
            )
        }
        remoteRequests.forEach { request ->
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
        quality: MatrixMediaImageQuality,
        allowFullFallback: Boolean
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
                    if (allowFullFallback) {
                        add(fullContentRequest)
                    }
                }
                MatrixMediaImageQuality.VIEWER -> {
                    add(fullContentRequest)
                }
            }
        }
    }

    private suspend fun decodeLocalBitmap(
        path: String,
        targetWidthPx: Int,
        targetHeightPx: Int,
        policy: MediaLoadPolicy
    ): Bitmap? {
        return withDecodePermit(policy.isPrefetch) {
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
            val decoded = BitmapFactory.decodeFile(path, decodeOptions)
                ?.scaleDownToFill(targetWidthPx, targetHeightPx)
            decoded
        }
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

    private suspend fun decodeBitmap(
        bytes: ByteArray,
        targetWidthPx: Int,
        targetHeightPx: Int,
        policy: MediaLoadPolicy
    ): Bitmap? {
        return withDecodePermit(policy.isPrefetch) {
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
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                ?.scaleDownToFill(targetWidthPx, targetHeightPx)
            decoded
        }
    }

    private suspend fun awaitPrefetchOrNull(prefetch: Deferred<Bitmap?>): Bitmap? {
        return try {
            prefetch.await()?.takeIf { !it.isRecycled }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.d(TAG, "Media prefetch handoff failed", error)
            null
        }
    }

    private suspend fun <T> withDecodePermit(isPrefetch: Boolean, block: () -> T): T {
        return if (isPrefetch) {
            prefetchDecodeSemaphore.withPermit {
                decodeSemaphore.withPermit {
                    withContext(decodeDispatcher) {
                        block()
                    }
                }
            }
        } else {
            decodeSemaphore.withPermit {
                withContext(decodeDispatcher) {
                    block()
                }
            }
        }
    }

    private fun isCachedOrInFlight(memoryKey: String, flightKey: String): Boolean {
        if (memoryCache.get(memoryKey)?.isRecycled == false) {
            return true
        }
        return synchronized(lock) {
            memoryCache.get(memoryKey)?.isRecycled == false ||
                inFlight[flightKey] != null ||
                (flightKey != memoryKey && inFlight[memoryKey] != null)
        }
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

    private fun updateRepresentativeCell(baseKey: String, memoryKey: String, bitmap: Bitmap) {
        val newArea = bitmap.cellArea()
        val existingKey = synchronized(lock) {
            representativeCellKeys[baseKey]
        }
        val existingArea = existingKey
            ?.let { memoryCache.get(it) }
            ?.takeUnless { it.isRecycled }
            ?.cellArea()
            ?: -1L
        synchronized(lock) {
            if (representativeCellKeys[baseKey] == existingKey && newArea >= existingArea) {
                representativeCellKeys[baseKey] = memoryKey
            }
        }
    }

    private fun Bitmap.cellArea(): Long {
        return width.coerceAtLeast(1).toLong() * height.coerceAtLeast(1).toLong()
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
            MatrixMediaImageQuality.CELL ->
                "cell:${targetWidthPx.coerceAtLeast(1)}x${targetHeightPx.coerceAtLeast(1)}:${cacheKey(imageInfo)}"
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

    private fun avatarMemoryCacheKey(avatarUrl: String, sizePx: Int): String {
        return "avatar:${sizePx.coerceAtLeast(1)}:$avatarUrl"
    }

    private fun prefetchInFlightKey(memoryKey: String): String {
        return "prefetch:$memoryKey"
    }

    private data class MediaBytesRequest(
        val cacheKey: String,
        val load: suspend () -> ByteArray
    )

    private class MediaLoadPolicy(
        allowFullFallback: Boolean,
        allowRemoteLoad: Boolean,
        isPrefetch: Boolean
    ) {
        private val allowFullFallbackRef = AtomicBoolean(allowFullFallback)
        private val allowRemoteLoadRef = AtomicBoolean(allowRemoteLoad)
        private val isPrefetchRef = AtomicBoolean(isPrefetch)

        val allowFullFallback: Boolean
            get() = allowFullFallbackRef.get()

        val allowRemoteLoad: Boolean
            get() = allowRemoteLoadRef.get()

        val isPrefetch: Boolean
            get() = isPrefetchRef.get()

        fun upgrade(
            allowFullFallback: Boolean,
            allowRemoteLoad: Boolean,
            isPrefetch: Boolean
        ) {
            if (allowFullFallback) {
                allowFullFallbackRef.set(true)
            }
            if (allowRemoteLoad) {
                allowRemoteLoadRef.set(true)
            }
            if (!isPrefetch) {
                isPrefetchRef.set(false)
            }
        }
    }

    private object NoopCloseable : AutoCloseable {
        override fun close() = Unit
    }

    companion object {
        const val TAG = "MatrixMediaLoader"
        const val DISK_CACHE_DIRECTORY = "matrix_media"
        const val MAX_PARALLEL_MEDIA_DECODES = 2
        const val MAX_PARALLEL_MEDIA_PREFETCH_DECODES = 1

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
