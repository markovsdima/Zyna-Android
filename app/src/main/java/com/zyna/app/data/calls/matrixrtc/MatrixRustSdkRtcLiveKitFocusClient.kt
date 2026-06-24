package com.zyna.app.data.calls.matrixrtc

import org.matrix.rustcomponents.sdk.Client

interface MatrixRtcLiveKitFocusClient {
    suspend fun discoverPreferredTransport(
        fallbackServiceUrl: String? = null
    ): MatrixRtcLiveKitDiscoveredTransport?

    suspend fun requestOpenIdToken(): MatrixRtcLiveKitOpenIdToken

    suspend fun sfuConfig(
        membership: MatrixRtcMembershipIdentity,
        transport: MatrixRtcTransport,
        roomId: String,
        endpointVersion: MatrixRtcLiveKitJwtEndpointVersion =
            MatrixRtcLiveKitJwtEndpointVersion.LEGACY,
        delayDelegation: MatrixRtcLiveKitDelayDelegation? = null
    ): MatrixRtcLiveKitSfuConfig

    suspend fun discoverAndAuthenticate(
        membership: MatrixRtcMembershipIdentity,
        roomId: String,
        fallbackServiceUrl: String? = null,
        endpointVersion: MatrixRtcLiveKitJwtEndpointVersion =
            MatrixRtcLiveKitJwtEndpointVersion.LEGACY,
        delayDelegation: MatrixRtcLiveKitDelayDelegation? = null
    ): MatrixRustSdkRtcLiveKitFocus?
}

class MatrixRustSdkRtcLiveKitFocusClient(
    private val client: Client,
    private val transportDiscoveryClient: MatrixRtcLiveKitTransportDiscoveryClient =
        MatrixRtcLiveKitTransportDiscoveryClient(),
    private val sfuClient: MatrixRtcLiveKitSfuClient = MatrixRtcLiveKitSfuClient()
) : MatrixRtcLiveKitFocusClient {
    override suspend fun discoverPreferredTransport(
        fallbackServiceUrl: String?
    ): MatrixRtcLiveKitDiscoveredTransport? {
        val session = client.session()
        return transportDiscoveryClient.discoverPreferredTransport(
            homeserverUrl = session.homeserverUrl,
            accessToken = session.accessToken,
            serverName = matrixServerNameFromUserId(session.userId)
                ?: runCatching { client.userIdServerName() }.getOrNull(),
            fallbackServiceUrl = fallbackServiceUrl
        )
    }

    override suspend fun requestOpenIdToken(): MatrixRtcLiveKitOpenIdToken {
        val token = client.requestOpenidToken()
        return MatrixRtcLiveKitOpenIdToken(
            accessToken = token.accessToken,
            tokenType = token.tokenType,
            matrixServerName = token.matrixServerName,
            expiresIn = token.expiresInSeconds
        )
    }

    override suspend fun sfuConfig(
        membership: MatrixRtcMembershipIdentity,
        transport: MatrixRtcTransport,
        roomId: String,
        endpointVersion: MatrixRtcLiveKitJwtEndpointVersion,
        delayDelegation: MatrixRtcLiveKitDelayDelegation?
    ): MatrixRtcLiveKitSfuConfig {
        val serviceUrl = transport.liveKitServiceUrl
            ?: error("MatrixRTC LiveKit transport is missing livekit_service_url")
        return sfuClient.sfuConfig(
            openIdToken = requestOpenIdToken(),
            membership = membership,
            serviceUrl = serviceUrl,
            roomId = roomId,
            endpointVersion = endpointVersion,
            delayDelegation = delayDelegation
        )
    }

    override suspend fun discoverAndAuthenticate(
        membership: MatrixRtcMembershipIdentity,
        roomId: String,
        fallbackServiceUrl: String?,
        endpointVersion: MatrixRtcLiveKitJwtEndpointVersion,
        delayDelegation: MatrixRtcLiveKitDelayDelegation?
    ): MatrixRustSdkRtcLiveKitFocus? {
        val discoveredTransport = discoverPreferredTransport(fallbackServiceUrl) ?: return null
        val sfuConfig = sfuConfig(
            membership = membership,
            transport = discoveredTransport.transport,
            roomId = roomId,
            endpointVersion = endpointVersion,
            delayDelegation = delayDelegation
        )
        return MatrixRustSdkRtcLiveKitFocus(
            discoveredTransport = discoveredTransport,
            sfuConfig = sfuConfig
        )
    }
}

internal fun matrixServerNameFromUserId(userId: String): String? {
    val separator = userId.indexOf(':')
    if (separator == -1 || separator == userId.lastIndex) {
        return null
    }
    return userId.substring(separator + 1).takeIf { it.isNotBlank() }
}
