package com.zyna.app.data.media

import android.content.Context
import android.util.Log
import com.zyna.app.data.matrix.MatrixAudioInfo
import com.zyna.app.data.matrix.MatrixClientService
import java.io.File
import java.security.MessageDigest
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

class MatrixAudioMediaLoader(
    private val matrixClientService: MatrixClientService,
    context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val directory = File(context.cacheDir, DISK_CACHE_DIRECTORY)
    private val sdkTempDirectory = File(context.cacheDir, SDK_TEMP_DIRECTORY)
    private val lock = Any()
    private val inFlight = mutableMapOf<String, Deferred<File?>>()

    fun cachedAudioFile(audioInfo: MatrixAudioInfo): File? = synchronized(lock) {
        val file = cacheFileFor(audioInfo)
        if (!file.isFile || file.length() <= 0L) {
            return@synchronized null
        }
        file.setLastModified(System.currentTimeMillis())
        file
    }

    fun loadAudioFile(
        audioInfo: MatrixAudioInfo,
        onLoaded: (File?) -> Unit
    ): AutoCloseable {
        cachedAudioFile(audioInfo)?.let { file ->
            val waiter = scope.launch {
                withContext(Dispatchers.Main.immediate) {
                    onLoaded(file)
                }
            }
            return AutoCloseable { waiter.cancel() }
        }

        val waiter = scope.launch {
            val file = deferredFor(audioInfo).await()
            withContext(Dispatchers.Main.immediate) {
                onLoaded(file)
            }
        }
        return AutoCloseable { waiter.cancel() }
    }

    fun clear() {
        val flights = synchronized(lock) {
            inFlight.values.toList().also {
                inFlight.clear()
            }
        }
        flights.forEach { it.cancel() }
        scope.launch {
            clearDiskCache()
        }
    }

    fun shutdown() {
        scope.cancel()
    }

    private fun deferredFor(audioInfo: MatrixAudioInfo): Deferred<File?> {
        cachedAudioFile(audioInfo)?.let { return CompletableDeferred(it) }

        val key = cacheKey(audioInfo)
        synchronized(lock) {
            cachedAudioFile(audioInfo)?.let { return CompletableDeferred(it) }
            inFlight[key]?.let { return it }

            val deferred = scope.async {
                fetchAudioFile(audioInfo)
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

    private suspend fun fetchAudioFile(audioInfo: MatrixAudioInfo): File? {
        cachedAudioFile(audioInfo)?.let { return it }
        return try {
            if (!directory.exists() && !directory.mkdirs()) {
                return null
            }
            if (!sdkTempDirectory.exists()) {
                sdkTempDirectory.mkdirs()
            }

            val target = cacheFileFor(audioInfo)
            val temp = File(directory, "${target.name}.${System.nanoTime()}$TEMP_SUFFIX")
            val handle = matrixClientService.loadMediaFile(
                sourceJson = audioInfo.sourceJson,
                filename = audioInfo.filename,
                mimeType = audioInfo.mimeType,
                tempDir = sdkTempDirectory.takeIf { it.isDirectory }
            )
            try {
                if (!handle.persist(temp.absolutePath)) {
                    val source = File(handle.path())
                    if (!source.isFile || source.length() <= 0L) {
                        temp.delete()
                        return null
                    }
                    source.copyTo(temp, overwrite = true)
                }
            } finally {
                handle.close()
            }

            if (!temp.isFile || temp.length() <= 0L) {
                temp.delete()
                return null
            }

            synchronized(lock) {
                cachedAudioFile(audioInfo)?.let { cached ->
                    temp.delete()
                    return@synchronized cached
                }
                if (target.exists() && !target.delete()) {
                    temp.delete()
                    return@synchronized null
                }
                if (!temp.renameTo(target)) {
                    temp.delete()
                    return@synchronized null
                }
                target.setLastModified(System.currentTimeMillis())
                trimLocked(protectedFileName = target.name)
                target
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.d(TAG, "Audio media load failed", error)
            null
        }
    }

    private fun trimLocked(protectedFileName: String) {
        val files = directory.listFiles()?.filter { it.isFile } ?: return
        files.filter { it.name.endsWith(TEMP_SUFFIX) }.forEach { it.delete() }

        val cacheFiles = files.filterNot { it.name.endsWith(TEMP_SUFFIX) }
        var totalSize = cacheFiles.sumOf { it.length() }
        if (totalSize <= MAX_CACHE_SIZE_BYTES) {
            return
        }

        cacheFiles
            .filterNot { it.name == protectedFileName }
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .forEach { file ->
                if (totalSize <= MAX_CACHE_SIZE_BYTES) {
                    return
                }
                val size = file.length()
                if (file.delete()) {
                    totalSize -= size
                }
            }
    }

    private fun clearDiskCache() = synchronized(lock) {
        deleteFilesIn(directory)
        deleteFilesIn(sdkTempDirectory)
    }

    private fun deleteFilesIn(directory: File) {
        directory.listFiles()?.forEach { file ->
            if (file.isFile) {
                file.delete()
            }
        }
    }

    private fun cacheFileFor(audioInfo: MatrixAudioInfo): File {
        return File(directory, "${sha256(cacheKey(audioInfo))}.audio")
    }

    private fun cacheKey(audioInfo: MatrixAudioInfo): String {
        return audioInfo.sourceJson
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        val output = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, byte ->
            val unsigned = byte.toInt() and 0xFF
            output[index * 2] = HEX[unsigned ushr 4]
            output[index * 2 + 1] = HEX[unsigned and 0x0F]
        }
        return String(output)
    }

    private companion object {
        const val TAG = "MatrixAudioMediaLoader"
        const val DISK_CACHE_DIRECTORY = "matrix_audio"
        const val SDK_TEMP_DIRECTORY = "matrix_audio_tmp"
        const val TEMP_SUFFIX = ".tmp"
        const val MAX_CACHE_SIZE_BYTES = 128L * 1024L * 1024L
        val HEX = "0123456789abcdef".toCharArray()
    }
}
