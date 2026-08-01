package com.zyna.app.ui.chat

import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Gives a chat cache bootstrap a short head start before revealing its route.
 *
 * The common path renders cached messages in the first chat frame. A slow database read still
 * reveals a loading chat after a bounded delay, so navigation never looks like an ignored tap.
 */
internal class ChatRouteRevealCoordinator(
    private val scope: CoroutineScope,
    private val awaitFallback: suspend () -> Unit = {
        delay(CHAT_ROUTE_REVEAL_GRACE_MILLIS)
    }
) {
    private class PendingRequest(
        val id: Long,
        val reveal: () -> Boolean,
        val onAbandoned: () -> Unit
    ) {
        private var didReveal = false

        fun revealOnce(): Boolean {
            if (didReveal) return true
            return reveal().also { revealed -> didReveal = revealed }
        }
    }

    private var nextRequestId = 0L
    private var pendingRequest: PendingRequest? = null
    private var fallbackJob: Job? = null

    @MainThread
    fun begin(
        reveal: () -> Boolean,
        onAbandoned: () -> Unit
    ): Long {
        cancel()
        val request = PendingRequest(
            id = ++nextRequestId,
            reveal = reveal,
            onAbandoned = onAbandoned
        )
        pendingRequest = request
        fallbackJob = scope.launch {
            try {
                awaitFallback()
            } catch (error: CancellationException) {
                throw error
            }
            if (pendingRequest !== request) return@launch
            if (!request.revealOnce()) {
                pendingRequest = null
                fallbackJob = null
                request.onAbandoned()
            } else if (pendingRequest === request) {
                fallbackJob = null
            }
        }
        return request.id
    }

    @MainThread
    fun ready(requestId: Long): Boolean {
        val request = pendingRequest?.takeIf { pending -> pending.id == requestId }
            ?: return false
        fallbackJob?.cancel()
        fallbackJob = null
        val revealed = request.revealOnce()
        if (pendingRequest === request) pendingRequest = null
        if (!revealed) request.onAbandoned()
        return revealed
    }

    @MainThread
    fun cancel() {
        pendingRequest = null
        fallbackJob?.cancel()
        fallbackJob = null
    }

    private companion object {
        const val CHAT_ROUTE_REVEAL_GRACE_MILLIS = 180L
    }
}
