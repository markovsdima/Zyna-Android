package com.zyna.app.data.media

import java.io.File
import java.security.MessageDigest

internal class MatrixMediaDiskCache(
    private val directory: File,
    private val maxSizeBytes: Long = DEFAULT_MAX_SIZE_BYTES,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()

    fun read(key: String): ByteArray? = synchronized(lock) {
        val file = fileFor(key)
        if (!file.isFile) {
            return@synchronized null
        }

        return@synchronized try {
            file.setLastModified(nowMillis())
            file.readBytes()
        } catch (_: Throwable) {
            file.delete()
            null
        }
    }

    fun write(key: String, bytes: ByteArray) = synchronized(lock) {
        if (bytes.isEmpty() || bytes.size.toLong() > maxSizeBytes) {
            return@synchronized
        }
        if (!directory.exists() && !directory.mkdirs()) {
            return@synchronized
        }

        val target = fileFor(key)
        val temp = try {
            File.createTempFile("${target.name}.", TEMP_SUFFIX, directory)
        } catch (_: Throwable) {
            return@synchronized
        }
        try {
            temp.writeBytes(bytes)
            if (target.exists() && !target.delete()) {
                temp.delete()
                return@synchronized
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                return@synchronized
            }
            target.setLastModified(nowMillis())
            trimLocked()
        } catch (_: Throwable) {
            temp.delete()
        }
    }

    fun clear() = synchronized(lock) {
        directory.listFiles()?.forEach { file ->
            if (file.isFile) {
                file.delete()
            }
        }
    }

    private fun trimLocked() {
        val files = directory.listFiles()?.filter { it.isFile } ?: return
        files.filter { it.name.endsWith(TEMP_SUFFIX) }.forEach { it.delete() }

        val cacheFiles = files.filterNot { it.name.endsWith(TEMP_SUFFIX) }
        var totalSize = cacheFiles.sumOf { it.length() }
        if (totalSize <= maxSizeBytes) {
            return
        }

        cacheFiles
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .forEach { file ->
                if (totalSize <= maxSizeBytes) {
                    return
                }
                val size = file.length()
                if (file.delete()) {
                    totalSize -= size
                }
            }
    }

    private fun fileFor(key: String): File {
        return File(directory, "${sha256(key)}.bin")
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
        const val TEMP_SUFFIX = ".tmp"
        const val DEFAULT_MAX_SIZE_BYTES = 128L * 1024L * 1024L
        val HEX = "0123456789abcdef".toCharArray()
    }
}
