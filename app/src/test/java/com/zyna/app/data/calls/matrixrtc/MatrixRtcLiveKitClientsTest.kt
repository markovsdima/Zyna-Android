package com.zyna.app.data.calls.matrixrtc

import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MatrixRtcLiveKitTransportDiscoveryClientTest {
    @Test
    fun discoversBackendRtcTransportFirst() = runBlocking {
        val httpClient = FakeMatrixRtcHttpClient(
            MatrixRtcHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "rtc_transports": [
                        {
                          "type": "livekit",
                          "livekit_service_url": "https://backend-livekit.example.org"
                        }
                      ]
                    }
                """.trimIndent().encodeToByteArray()
            )
        )
        val client = MatrixRtcLiveKitTransportDiscoveryClient(httpClient)

        val discovered = client.discoverPreferredTransport(
            homeserverUrl = "https://matrix.example.org/",
            accessToken = "matrix-access",
            serverName = "example.org",
            fallbackServiceUrl = "https://fallback-livekit.example.org"
        )!!

        assertEquals(MatrixRtcLiveKitTransportDiscoverySource.BACKEND, discovered.source)
        assertEquals("https://backend-livekit.example.org", discovered.transport.liveKitServiceUrl)
        assertEquals(
            listOf("https://matrix.example.org/_matrix/client/unstable/org.matrix.msc4143/rtc/transports"),
            httpClient.requests.map { it.url }
        )
        assertEquals("Bearer matrix-access", httpClient.requests[0].headers["Authorization"])
    }

    @Test
    fun fallsBackToWellKnownRtcTransport() = runBlocking {
        val httpClient = FakeMatrixRtcHttpClient(
            MatrixRtcHttpResponse(statusCode = 404, body = ByteArray(0)),
            MatrixRtcHttpResponse(
                statusCode = 200,
                body = """
                    {
                      "org.matrix.msc4143.rtc_foci": [
                        {
                          "type": "livekit",
                          "livekit_service_url": "https://well-known-livekit.example.org"
                        }
                      ]
                    }
                """.trimIndent().encodeToByteArray()
            )
        )
        val client = MatrixRtcLiveKitTransportDiscoveryClient(httpClient)

        val discovered = client.discoverPreferredTransport(
            homeserverUrl = "https://matrix.example.org",
            accessToken = "matrix-access",
            serverName = "example.org"
        )!!

        assertEquals(MatrixRtcLiveKitTransportDiscoverySource.WELL_KNOWN, discovered.source)
        assertEquals("https://well-known-livekit.example.org", discovered.transport.liveKitServiceUrl)
        assertEquals(
            listOf(
                "https://matrix.example.org/_matrix/client/unstable/org.matrix.msc4143/rtc/transports",
                "https://example.org/.well-known/matrix/client"
            ),
            httpClient.requests.map { it.url }
        )
    }

    @Test
    fun fallsBackToConfiguredRtcTransportLast() = runBlocking {
        val httpClient = FakeMatrixRtcHttpClient(
            MatrixRtcHttpResponse(statusCode = 500, body = ByteArray(0)),
            MatrixRtcHttpResponse(statusCode = 404, body = ByteArray(0))
        )
        val client = MatrixRtcLiveKitTransportDiscoveryClient(httpClient)

        val discovered = client.discoverPreferredTransport(
            homeserverUrl = "https://matrix.example.org",
            accessToken = "matrix-access",
            serverName = "example.org",
            fallbackServiceUrl = "https://fallback-livekit.example.org"
        )!!

        assertEquals(MatrixRtcLiveKitTransportDiscoverySource.FALLBACK, discovered.source)
        assertEquals("https://fallback-livekit.example.org", discovered.transport.liveKitServiceUrl)
    }
}

class MatrixRtcLiveKitSfuClientTest {
    @Test
    fun fetchesLegacySfuConfigWithElementCallCompatibleBody() = runBlocking {
        val jwt = testJwt(
            liveKitAlias = "!room:example.org",
            liveKitIdentity = "@alice:example.org:ALICEDEVICE"
        )
        val httpClient = FakeMatrixRtcHttpClient(
            MatrixRtcHttpResponse(
                statusCode = 200,
                body = """{"url":"wss://livekit.example.org","jwt":"$jwt"}""".encodeToByteArray()
            )
        )
        val client = MatrixRtcLiveKitSfuClient(httpClient)

        val config = client.sfuConfig(
            openIdToken = openIdToken,
            membership = membership,
            serviceUrl = "https://matrix-rtc.example.org/livekit/jwt/",
            roomId = "!room:example.org"
        )

        assertEquals("wss://livekit.example.org", config.url)
        assertEquals(jwt, config.jwt)
        assertEquals("!room:example.org", config.liveKitAlias)
        assertEquals("@alice:example.org:ALICEDEVICE", config.liveKitIdentity)
        assertEquals(
            listOf("https://matrix-rtc.example.org/livekit/jwt/sfu/get"),
            httpClient.requests.map { it.url }
        )

        val body = JSONObject(httpClient.requests[0].body!!.decodeToString())
        assertEquals("!room:example.org", body.getString("room"))
        assertEquals("ALICEDEVICE", body.getString("device_id"))
        val token = body.getJSONObject("openid_token")
        assertEquals("openid-access", token.getString("access_token"))
        assertEquals("Bearer", token.getString("token_type"))
        assertEquals("example.org", token.getString("matrix_server_name"))
        assertEquals(3600L, token.getLong("expires_in"))
    }

    @Test
    fun fetchesMatrix2SfuConfigWithMemberIdentityAndDelayDelegation() = runBlocking {
        val jwt = testJwt(liveKitAlias = "lk-alias", liveKitIdentity = "hashed-livekit-identity")
        val httpClient = FakeMatrixRtcHttpClient(
            MatrixRtcHttpResponse(
                statusCode = 200,
                body = """{"url":"wss://livekit.example.org","jwt":"$jwt"}""".encodeToByteArray()
            )
        )
        val client = MatrixRtcLiveKitSfuClient(httpClient)

        val config = client.sfuConfig(
            openIdToken = openIdToken,
            membership = membership,
            serviceUrl = "https://matrix-rtc.example.org",
            roomId = "!room:example.org",
            endpointVersion = MatrixRtcLiveKitJwtEndpointVersion.MATRIX2,
            delayDelegation = MatrixRtcLiveKitDelayDelegation(
                endpointBaseUrl = "https://matrix.example.org",
                delayId = "delay-id",
                delayTimeoutMillis = 10_000
            )
        )

        assertEquals("lk-alias", config.liveKitAlias)
        assertEquals("hashed-livekit-identity", config.liveKitIdentity)
        assertEquals(listOf("https://matrix-rtc.example.org/get_token"), httpClient.requests.map { it.url })

        val body = JSONObject(httpClient.requests[0].body!!.decodeToString())
        assertEquals("!room:example.org", body.getString("room_id"))
        assertEquals("m.call#ROOM", body.getString("slot_id"))
        assertEquals("delay-id", body.getString("delay_id"))
        assertEquals(10_000, body.getInt("delay_timeout"))
        assertEquals("https://matrix.example.org", body.getString("delay_cs_api_url"))

        val member = body.getJSONObject("member")
        assertEquals("@alice:example.org:ALICEDEVICE", member.getString("id"))
        assertEquals("@alice:example.org", member.getString("claimed_user_id"))
        assertEquals("ALICEDEVICE", member.getString("claimed_device_id"))
    }

    @Test
    fun fallsBackFromMatrix2EndpointToLegacyEndpoint() = runBlocking {
        val jwt = testJwt(
            liveKitAlias = "!room:example.org",
            liveKitIdentity = "@alice:example.org:ALICEDEVICE"
        )
        val httpClient = FakeMatrixRtcHttpClient(
            MatrixRtcHttpResponse(statusCode = 404, body = ByteArray(0)),
            MatrixRtcHttpResponse(
                statusCode = 200,
                body = """{"url":"wss://legacy-livekit.example.org","jwt":"$jwt"}""".encodeToByteArray()
            )
        )
        val client = MatrixRtcLiveKitSfuClient(httpClient)

        val config = client.sfuConfig(
            openIdToken = openIdToken,
            membership = membership,
            serviceUrl = "https://matrix-rtc.example.org",
            roomId = "!room:example.org",
            endpointVersion = MatrixRtcLiveKitJwtEndpointVersion.MATRIX2_WITH_LEGACY_FALLBACK
        )

        assertEquals("wss://legacy-livekit.example.org", config.url)
        assertEquals(
            listOf(
                "https://matrix-rtc.example.org/get_token",
                "https://matrix-rtc.example.org/sfu/get"
            ),
            httpClient.requests.map { it.url }
        )
    }

    private val openIdToken = MatrixRtcLiveKitOpenIdToken(
        accessToken = "openid-access",
        tokenType = "Bearer",
        matrixServerName = "example.org",
        expiresIn = 3600UL
    )

    private val membership = MatrixRtcMembershipIdentity(
        userId = "@alice:example.org",
        deviceId = "ALICEDEVICE",
        memberId = "@alice:example.org:ALICEDEVICE"
    )

    private fun testJwt(liveKitAlias: String, liveKitIdentity: String): String {
        val header = base64Url("""{"alg":"none","typ":"JWT"}""")
        val payload = base64Url(
            """
                {
                  "sub": "$liveKitIdentity",
                  "video": {
                    "room": "$liveKitAlias"
                  }
                }
            """.trimIndent()
        )
        return "$header.$payload.signature"
    }

    private fun base64Url(value: String): String {
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.encodeToByteArray())
    }
}

class MatrixRtcMembershipStateParsingTest {
    @Test
    fun parsesOnlyMatrixRtcRawMembershipEventsFromRoomState() {
        val events = matrixRtcRawMembershipEventsFromStateJson(
            """
                [
                  {
                    "event_id": "${'$'}not-call",
                    "type": "m.room.name",
                    "state_key": "",
                    "sender": "@alice:example.org",
                    "origin_server_ts": 100,
                    "content": {"name": "Room"}
                  },
                  {
                    "event_id": "${'$'}legacy",
                    "type": "org.matrix.msc3401.call.member",
                    "state_key": "_@alice:example.org_ALICEDEVICE_m.call",
                    "sender": "@alice:example.org",
                    "origin_server_ts": 1000,
                    "content": {
                      "application": "m.call",
                      "call_id": "",
                      "device_id": "ALICEDEVICE",
                      "focus_active": {
                        "type": "livekit",
                        "focus_selection": "oldest_membership"
                      },
                      "foci_preferred": [],
                      "created_ts": 1000,
                      "expires": 50000
                    }
                  }
                ]
            """.trimIndent()
        )

        assertEquals(1, events.size)
        assertEquals("\$legacy", events.first().eventId)
        assertEquals(MatrixRtcRawMembershipEvent.LEGACY_CALL_MEMBER_EVENT_TYPE, events.first().eventType)
        assertEquals("@alice:example.org", events.first().sender)

        val membership = MatrixRtcCallMembershipParser.parse(events.first())
        assertEquals("@alice:example.org:ALICEDEVICE", membership.memberId)
    }
}

private class FakeMatrixRtcHttpClient(
    vararg responses: MatrixRtcHttpResponse
) : MatrixRtcHttpClient {
    val requests = mutableListOf<MatrixRtcHttpRequest>()
    private val responses = ArrayDeque(responses.toList())

    override suspend fun execute(request: MatrixRtcHttpRequest): MatrixRtcHttpResponse {
        requests += request
        return responses.removeFirst()
    }
}
