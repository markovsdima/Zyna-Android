package com.zyna.app.data.presence

import android.os.SystemClock
import android.util.Log
import java.net.URI
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

class ZynaWebSocketPresenceBackend(
    private val client: OkHttpClient = defaultClient()
) : PresenceBackend {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<PresenceBackendEvent>(extraBufferCapacity = 64)
    private val _connectionStatus = MutableStateFlow(PresenceConnectionStatus.DISCONNECTED)

    @Volatile
    private var webSocket: WebSocket? = null
    @Volatile
    private var currentSession: PresenceSession? = null
    @Volatile
    private var intentionallyClosed = false

    private val connectionGeneration = AtomicLong(0)
    private var pingJob: Job? = null

    override val events: SharedFlow<PresenceBackendEvent> = _events.asSharedFlow()
    override val connectionStatus: StateFlow<PresenceConnectionStatus> =
        _connectionStatus.asStateFlow()

    override suspend fun connect(session: PresenceSession) {
        disconnect()
        _connectionStatus.value = PresenceConnectionStatus.CONNECTING
        intentionallyClosed = false
        currentSession = session
        val generation = connectionGeneration.incrementAndGet()

        val baseUri = buildBaseUri(session.homeserverUrl)
            ?: throw PresenceTransportException("Invalid homeserver URL")
        val jwt = fetchJwt(baseUri, session)
        val wsUri = presenceWsUri(baseUri)
        val opened = CompletableDeferred<Unit>()

        val request = Request.Builder()
            .url(wsUri.toString())
            .build()
        val nextWebSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!isCurrentGeneration(generation)) {
                        return
                    }
                    _connectionStatus.value = PresenceConnectionStatus.CONNECTED
                    opened.complete(Unit)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!isCurrentGeneration(generation)) {
                        return
                    }
                    parseMessage(text, generation)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    handleDisconnected(generation)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!isCurrentGeneration(generation)) {
                        return
                    }
                    if (!opened.isCompleted) {
                        opened.completeExceptionally(t)
                    }
                    handleDisconnected(generation, t)
                }
            }
        )
        webSocket = nextWebSocket

        try {
            withTimeout(CONNECTION_TIMEOUT_MS) {
                opened.await()
            }
            sendJson(
                JSONObject()
                    .put("type", "auth")
                    .put("token", jwt)
            )
            startPingLoop(generation)
            Log.d(TAG, "Connected")
        } catch (error: Throwable) {
            nextWebSocket.cancel()
            webSocket = null
            _connectionStatus.value = PresenceConnectionStatus.DISCONNECTED
            throw error
        }
    }

    override suspend fun subscribe(userIds: Set<String>) {
        if (_connectionStatus.value != PresenceConnectionStatus.CONNECTED) {
            return
        }
        sendJson(
            JSONObject()
                .put("type", "subscribe")
                .put("user_ids", JSONArray(userIds.sorted()))
        )
    }

    override fun disconnect() {
        intentionallyClosed = true
        connectionGeneration.incrementAndGet()
        pingJob?.cancel()
        pingJob = null
        webSocket?.close(NORMAL_CLOSE_CODE, "disconnect")
        webSocket = null
        _connectionStatus.value = PresenceConnectionStatus.DISCONNECTED
    }

    private suspend fun fetchJwt(baseUri: URI, session: PresenceSession): String {
        val request = Request.Builder()
            .url(presenceAuthUri(baseUri).toString())
            .post(ByteArray(0).toRequestBody())
            .header("Authorization", "Bearer ${session.accessToken}")
            .build()

        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw PresenceTransportException("Presence auth failed: HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                json.getString("token")
            }
        }
    }

    private fun sendJson(json: JSONObject) {
        val socket = webSocket ?: throw PresenceTransportException("Presence socket is not connected")
        if (!socket.send(json.toString())) {
            throw PresenceTransportException("Presence socket send failed")
        }
    }

    private fun startPingLoop(generation: Long) {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (true) {
                delay(PING_INTERVAL_MS)
                if (!isCurrentGeneration(generation)) {
                    return@launch
                }
                runCatching {
                    sendJson(JSONObject().put("type", "ping"))
                }.onFailure {
                    handleDisconnected(generation, it)
                    return@launch
                }
            }
        }
    }

    private fun parseMessage(text: String, generation: Long) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (json.optString("type")) {
            "statuses" -> parseStatuses(json.optJSONArray("users"))
            "presence" -> parsePresenceChange(json)
            "token_expired" -> refreshToken(generation)
            "error" -> Log.w(TAG, "Server error: ${json.optString("message", "unknown")}")
        }
    }

    private fun parseStatuses(users: JSONArray?) {
        if (users == null) {
            return
        }
        val statuses = buildMap {
            for (index in 0 until users.length()) {
                val entry = users.optJSONObject(index) ?: continue
                val userId = entry.optString("user_id").takeIf { it.isNotBlank() } ?: continue
                put(userId, parsePresence(userId, entry))
            }
        }
        _events.tryEmit(PresenceBackendEvent.Snapshot(statuses))
    }

    private fun parsePresenceChange(json: JSONObject) {
        val userId = json.optString("user_id").takeIf { it.isNotBlank() } ?: return
        _events.tryEmit(PresenceBackendEvent.Change(parsePresence(userId, json)))
    }

    private fun parsePresence(userId: String, json: JSONObject): UserPresenceStatus {
        val online = json.optBoolean("online", false)
        val lastSeenAtMillis = json.optString("last_seen")
            .takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull() }
        return UserPresenceStatus(
            userId = userId,
            availability = if (online) PresenceAvailability.ONLINE else PresenceAvailability.OFFLINE,
            lastSeenAtMillis = lastSeenAtMillis,
            currentlyActive = null,
            source = PresenceSource.ZYNA_WS,
            receivedAtElapsedMillis = SystemClock.elapsedRealtime()
        )
    }

    private fun refreshToken(generation: Long) {
        val session = currentSession ?: return
        val baseUri = buildBaseUri(session.homeserverUrl) ?: return
        scope.launch {
            runCatching {
                val jwt = fetchJwt(baseUri, session)
                if (!isCurrentGeneration(generation)) {
                    return@launch
                }
                sendJson(
                    JSONObject()
                        .put("type", "auth")
                        .put("token", jwt)
                )
            }.onFailure { error ->
                Log.w(TAG, "Token refresh failed", error)
                handleDisconnected(generation, error)
            }
        }
    }

    private fun handleDisconnected(generation: Long, error: Throwable? = null) {
        if (intentionallyClosed || !isCurrentGeneration(generation)) {
            return
        }
        if (error != null) {
            Log.w(TAG, "Connection lost", error)
        }
        pingJob?.cancel()
        pingJob = null
        webSocket = null
        _connectionStatus.value = PresenceConnectionStatus.DISCONNECTED
        _events.tryEmit(PresenceBackendEvent.Disconnected)
    }

    private fun isCurrentGeneration(generation: Long): Boolean {
        return connectionGeneration.get() == generation
    }

    private fun buildBaseUri(homeserverUrl: String): URI? {
        val raw = homeserverUrl.trim().let { value ->
            if (value.contains("://")) value else "https://$value"
        }
        val parsed = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return null
        }
        val host = parsed.host ?: return null
        return URI(scheme, null, host, parsed.port, null, null, null)
    }

    private fun presenceAuthUri(baseUri: URI): URI {
        return URI(baseUri.scheme, null, baseUri.host, baseUri.port, "/presence/auth", null, null)
    }

    private fun presenceWsUri(baseUri: URI): URI {
        val wsScheme = when (baseUri.scheme) {
            "https" -> "wss"
            "http" -> "ws"
            else -> baseUri.scheme
        }
        return URI(wsScheme, null, baseUri.host, baseUri.port, "/presence/ws", null, null)
    }

    private companion object {
        const val TAG = "ZynaPresence"
        const val CONNECTION_TIMEOUT_MS = 15_000L
        const val PING_INTERVAL_MS = 10_000L
        const val WEB_SOCKET_PING_INTERVAL_SECONDS = 15L
        const val NORMAL_CLOSE_CODE = 1000

        fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .pingInterval(WEB_SOCKET_PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
                .build()
        }
    }
}

class PresenceTransportException(message: String) : RuntimeException(message)
