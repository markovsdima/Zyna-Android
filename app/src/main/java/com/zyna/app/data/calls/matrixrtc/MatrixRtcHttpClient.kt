package com.zyna.app.data.calls.matrixrtc

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class MatrixRtcHttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null
)

data class MatrixRtcHttpResponse(
    val statusCode: Int,
    val body: ByteArray
)

interface MatrixRtcHttpClient {
    suspend fun execute(request: MatrixRtcHttpRequest): MatrixRtcHttpResponse
}

class MatrixRtcUrlConnectionHttpClient : MatrixRtcHttpClient {
    override suspend fun execute(request: MatrixRtcHttpRequest): MatrixRtcHttpResponse {
        val connection = URI(request.url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            request.body?.let { body ->
                connection.doOutput = true
                connection.outputStream.use { output -> output.write(body) }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }
            val bytes = stream.use { input ->
                val output = ByteArrayOutputStream()
                input.copyTo(output)
                output.toByteArray()
            }
            return MatrixRtcHttpResponse(statusCode = status, body = bytes)
        } finally {
            connection.disconnect()
        }
    }
}

internal fun normalizeHttpBaseUrl(rawUrl: String): URL {
    var raw = rawUrl.trim()
    while (raw.endsWith("/")) {
        raw = raw.dropLast(1)
    }
    if (!raw.contains("://")) {
        raw = "https://$raw"
    }

    val url = URI(raw).toURL()
    require(url.protocol == "http" || url.protocol == "https") {
        "Unsupported URL scheme: ${url.protocol}"
    }
    require(!url.host.isNullOrBlank()) { "URL host is missing" }
    return url
}

internal fun appendUrlPath(rawUrl: String, path: String): String {
    val base = normalizeHttpBaseUrl(rawUrl)
    val basePath = base.path.trim('/')
    val endpointPath = path.trim('/')
    val combinedPath = listOf(basePath, endpointPath)
        .filter { it.isNotEmpty() }
        .joinToString(separator = "/", prefix = "/")
    return URI(
        base.protocol,
        base.userInfo,
        base.host,
        base.port,
        combinedPath,
        null,
        null
    ).toString()
}

internal fun matrixClientUrl(homeserverUrl: String, percentEncodedPath: String): String {
    val base = normalizeHttpBaseUrl(homeserverUrl)
    val path = if (percentEncodedPath.startsWith("/")) {
        percentEncodedPath
    } else {
        "/$percentEncodedPath"
    }
    return "${base.protocol}://${base.authority}$path"
}

internal fun percentEncodePathComponent(value: String): String {
    return buildString {
        value.encodeToByteArray().forEach { byte ->
            val char = byte.toInt().toChar()
            if (
                char in 'a'..'z' ||
                char in 'A'..'Z' ||
                char in '0'..'9' ||
                char == '-' ||
                char == '.' ||
                char == '_' ||
                char == '~'
            ) {
                append(char)
            } else {
                append('%')
                append(byte.toUByte().toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
}
