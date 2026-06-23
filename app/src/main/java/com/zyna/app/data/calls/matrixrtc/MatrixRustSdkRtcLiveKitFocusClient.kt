package com.zyna.app.data.calls.matrixrtc

import org.matrix.rustcomponents.sdk.Client

class MatrixRustSdkRtcLiveKitFocusClient(
    private val client: Client,
    private val transportDiscoveryClient: MatrixRtcLiveKitTransportDiscoveryClient =
        MatrixRtcLiveKitTransportDiscoveryClient(),
    private val sfuClient: MatrixRtcLiveKitSfuClient = MatrixRtcLiveKitSfuClient()
) {
    suspend fun discoverPreferredTransport(
        fallbackServiceUrl: String? = null
    ): MatrixRtcLiveKitDiscoveredTransport? {
        val session = client.session()
        return transportDiscoveryClient.discoverPreferredTransport(
            homeserverUrl = session.homeserverUrl,
            accessToken = session.accessToken,
            serverName = runCatching { client.userIdServerName() }.getOrNull(),
            fallbackServiceUrl = fallbackServiceUrl
        )
    }

    suspend fun requestOpenIdToken(): MatrixRtcLiveKitOpenIdToken {
        val token = client.requestOpenidToken()
        return MatrixRtcLiveKitOpenIdToken(
            accessToken = token.accessToken,
            tokenType = token.tokenType,
            matrixServerName = token.matrixServerName,
            expiresIn = token.expiresInSeconds
        )
    }

    suspend fun sfuConfig(
        membership: MatrixRtcMembershipIdentity,
        transport: MatrixRtcTransport,
        roomId: String,
        endpointVersion: MatrixRtcLiveKitJwtEndpointVersion =
            MatrixRtcLiveKitJwtEndpointVersion.LEGACY,
        delayDelegation: MatrixRtcLiveKitDelayDelegation? = null
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

    suspend fun discoverAndAuthenticate(
        membership: MatrixRtcMembershipIdentity,
        roomId: String,
        fallbackServiceUrl: String? = null,
        endpointVersion: MatrixRtcLiveKitJwtEndpointVersion =
            MatrixRtcLiveKitJwtEndpointVersion.LEGACY,
        delayDelegation: MatrixRtcLiveKitDelayDelegation? = null
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

