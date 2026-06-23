package com.zyna.app.data.calls.matrixrtc

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

data class MatrixRtcLiveKitOpenIdToken(
    val accessToken: String,
    val tokenType: String,
    val matrixServerName: String,
    val expiresIn: ULong
) {
    fun toJsonObject(): JSONObject {
        return JSONObject()
            .put("access_token", accessToken)
            .put("token_type", tokenType)
            .put("matrix_server_name", matrixServerName)
            .put("expires_in", expiresIn.toLong())
    }
}

data class MatrixRtcLiveKitSfuConfig(
    val url: String,
    val jwt: String,
    val liveKitAlias: String,
    val liveKitIdentity: String
)

enum class MatrixRtcLiveKitJwtEndpointVersion {
    LEGACY,
    MATRIX2,
    MATRIX2_WITH_LEGACY_FALLBACK
}

data class MatrixRtcLiveKitDelayDelegation(
    val endpointBaseUrl: String,
    val delayId: String,
    val delayTimeoutMillis: Int
)

class MatrixRtcLiveKitSfuClient(
    private val httpClient: MatrixRtcHttpClient = MatrixRtcUrlConnectionHttpClient()
) {
    suspend fun sfuConfig(
        openIdToken: MatrixRtcLiveKitOpenIdToken,
        membership: MatrixRtcMembershipIdentity,
        serviceUrl: String,
        roomId: String,
        endpointVersion: MatrixRtcLiveKitJwtEndpointVersion =
            MatrixRtcLiveKitJwtEndpointVersion.LEGACY,
        delayDelegation: MatrixRtcLiveKitDelayDelegation? = null
    ): MatrixRtcLiveKitSfuConfig {
        return when (endpointVersion) {
            MatrixRtcLiveKitJwtEndpointVersion.LEGACY -> legacySfuConfig(
                openIdToken = openIdToken,
                deviceId = membership.deviceId,
                serviceUrl = serviceUrl,
                roomId = roomId,
                delayDelegation = delayDelegation
            )
            MatrixRtcLiveKitJwtEndpointVersion.MATRIX2 -> matrix2SfuConfig(
                openIdToken = openIdToken,
                membership = membership,
                serviceUrl = serviceUrl,
                roomId = roomId,
                delayDelegation = delayDelegation
            )
            MatrixRtcLiveKitJwtEndpointVersion.MATRIX2_WITH_LEGACY_FALLBACK -> {
                try {
                    matrix2SfuConfig(
                        openIdToken = openIdToken,
                        membership = membership,
                        serviceUrl = serviceUrl,
                        roomId = roomId,
                        delayDelegation = delayDelegation
                    )
                } catch (_: UnsupportedMatrix2EndpointException) {
                    legacySfuConfig(
                        openIdToken = openIdToken,
                        deviceId = membership.deviceId,
                        serviceUrl = serviceUrl,
                        roomId = roomId,
                        delayDelegation = delayDelegation
                    )
                }
            }
        }
    }

    private suspend fun matrix2SfuConfig(
        openIdToken: MatrixRtcLiveKitOpenIdToken,
        membership: MatrixRtcMembershipIdentity,
        serviceUrl: String,
        roomId: String,
        delayDelegation: MatrixRtcLiveKitDelayDelegation?
    ): MatrixRtcLiveKitSfuConfig {
        val body = JSONObject()
            .put("room_id", roomId)
            .put("slot_id", MatrixRtcSlotDescription.MATRIX_CALL_ROOM.slotId)
            .put("openid_token", openIdToken.toJsonObject())
            .put(
                "member",
                JSONObject()
                    .put("id", membership.memberId)
                    .put("claimed_user_id", membership.userId)
                    .put("claimed_device_id", membership.deviceId)
            )
        encodeDelayDelegation(body, delayDelegation)

        return try {
            requestSfuConfig(
                serviceUrl = serviceUrl,
                endpointPath = "get_token",
                body = body
            )
        } catch (error: HttpStatusException) {
            if (error.statusCode == 404) {
                throw UnsupportedMatrix2EndpointException(error.statusCode)
            }
            throw error
        }
    }

    private suspend fun legacySfuConfig(
        openIdToken: MatrixRtcLiveKitOpenIdToken,
        deviceId: String,
        serviceUrl: String,
        roomId: String,
        delayDelegation: MatrixRtcLiveKitDelayDelegation?
    ): MatrixRtcLiveKitSfuConfig {
        val body = JSONObject()
            .put("room", roomId)
            .put("openid_token", openIdToken.toJsonObject())
            .put("device_id", deviceId)
        encodeDelayDelegation(body, delayDelegation)
        return requestSfuConfig(
            serviceUrl = serviceUrl,
            endpointPath = "sfu/get",
            body = body
        )
    }

    private suspend fun requestSfuConfig(
        serviceUrl: String,
        endpointPath: String,
        body: JSONObject
    ): MatrixRtcLiveKitSfuConfig {
        val response = httpClient.execute(
            MatrixRtcHttpRequest(
                url = appendUrlPath(serviceUrl, endpointPath),
                method = "POST",
                headers = mapOf("Content-Type" to "application/json"),
                body = body.toString().encodeToByteArray()
            )
        )
        if (response.statusCode !in 200..299) {
            throw HttpStatusException(response.statusCode)
        }

        val responseBody = JSONObject(response.body.decodeToString())
        val jwt = responseBody.getString("jwt")
        val payload = decodeJwtPayload(jwt)
        return MatrixRtcLiveKitSfuConfig(
            url = responseBody.getString("url"),
            jwt = jwt,
            liveKitAlias = payload.getJSONObject("video").getString("room"),
            liveKitIdentity = payload.getString("sub")
        )
    }

    private fun decodeJwtPayload(jwt: String): JSONObject {
        val parts = jwt.split(".")
        require(parts.size >= 2) { "Invalid LiveKit JWT" }
        var payload = parts[1]
            .replace('-', '+')
            .replace('_', '/')
        val remainder = payload.length % 4
        if (remainder != 0) {
            payload += "=".repeat(4 - remainder)
        }
        return JSONObject(Base64.getDecoder().decode(payload).decodeToString())
    }

    private fun encodeDelayDelegation(
        body: JSONObject,
        delayDelegation: MatrixRtcLiveKitDelayDelegation?
    ) {
        if (delayDelegation == null) {
            return
        }
        body.put("delay_id", delayDelegation.delayId)
        body.put("delay_timeout", delayDelegation.delayTimeoutMillis)
        body.put("delay_cs_api_url", delayDelegation.endpointBaseUrl)
    }
}

enum class MatrixRtcLiveKitTransportDiscoverySource {
    BACKEND,
    WELL_KNOWN,
    FALLBACK
}

data class MatrixRtcLiveKitDiscoveredTransport(
    val transport: MatrixRtcTransport,
    val source: MatrixRtcLiveKitTransportDiscoverySource
)

class MatrixRtcLiveKitTransportDiscoveryClient(
    private val httpClient: MatrixRtcHttpClient = MatrixRtcUrlConnectionHttpClient()
) {
    suspend fun discoverPreferredTransport(
        homeserverUrl: String,
        accessToken: String?,
        serverName: String?,
        fallbackServiceUrl: String? = null
    ): MatrixRtcLiveKitDiscoveredTransport? {
        if (!accessToken.isNullOrBlank()) {
            val transport = runCatching {
                backendTransport(homeserverUrl, accessToken)
            }.getOrNull()
            if (transport != null) {
                return MatrixRtcLiveKitDiscoveredTransport(
                    transport = transport,
                    source = MatrixRtcLiveKitTransportDiscoverySource.BACKEND
                )
            }
        }

        if (!serverName.isNullOrBlank()) {
            val transport = runCatching {
                wellKnownTransport(serverName)
            }.getOrNull()
            if (transport != null) {
                return MatrixRtcLiveKitDiscoveredTransport(
                    transport = transport,
                    source = MatrixRtcLiveKitTransportDiscoverySource.WELL_KNOWN
                )
            }
        }

        if (fallbackServiceUrl.isNullOrBlank()) {
            return null
        }
        return MatrixRtcLiveKitDiscoveredTransport(
            transport = MatrixRtcTransport.liveKit(fallbackServiceUrl),
            source = MatrixRtcLiveKitTransportDiscoverySource.FALLBACK
        )
    }

    private suspend fun backendTransport(
        homeserverUrl: String,
        accessToken: String
    ): MatrixRtcTransport? {
        val response = httpClient.execute(
            MatrixRtcHttpRequest(
                url = matrixClientUrl(
                    homeserverUrl,
                    "/_matrix/client/unstable/org.matrix.msc4143/rtc/transports"
                ),
                headers = mapOf("Authorization" to "Bearer $accessToken")
            )
        )
        if (response.statusCode !in 200..299) {
            return null
        }
        val transports = JSONObject(response.body.decodeToString()).optJSONArray("rtc_transports")
            ?: return null
        return transports.firstUsableLiveKitTransport()
    }

    private suspend fun wellKnownTransport(serverName: String): MatrixRtcTransport? {
        val response = httpClient.execute(
            MatrixRtcHttpRequest(
                url = matrixClientUrl(serverName, "/.well-known/matrix/client")
            )
        )
        if (response.statusCode !in 200..299) {
            return null
        }
        val foci = JSONObject(response.body.decodeToString())
            .optJSONArray("org.matrix.msc4143.rtc_foci")
            ?: return null
        return foci.firstUsableLiveKitTransport()
    }

    private fun JSONArray.firstUsableLiveKitTransport(): MatrixRtcTransport? {
        return (0 until length()).asSequence()
            .map { index -> MatrixRtcTransport.fromJsonObject(getJSONObject(index)) }
            .firstOrNull { transport ->
                transport.type == "livekit" && transport.liveKitServiceUrl != null
            }
    }
}

data class MatrixRustSdkRtcLiveKitFocus(
    val discoveredTransport: MatrixRtcLiveKitDiscoveredTransport,
    val sfuConfig: MatrixRtcLiveKitSfuConfig
)

class HttpStatusException(
    val statusCode: Int
) : Exception("HTTP status $statusCode")

class UnsupportedMatrix2EndpointException(
    val statusCode: Int
) : Exception("Unsupported MatrixRTC matrix2 endpoint: HTTP $statusCode")

