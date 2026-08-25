package com.zyna.app.ui.chat

import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Coordinates read receipts for the active chat.
 *
 * This class is main-thread confined. [scope] must dispatch onto the same main
 * thread from which [updateVisibleCandidate] and [reset] are called.
 */
internal class ChatReadReceiptCoordinator(
    private val scope: CoroutineScope,
    private val messageIndex: (eventId: String) -> Int?,
    private val sendReadReceipt: suspend (roomId: String, eventId: String) -> Boolean,
    private val onSendFailure: (Throwable) -> Unit,
    private val sendDelayMillis: Long = SEND_DELAY_MS,
    private val delayBeforeSend: suspend (Long) -> Unit = { delay(it) }
) {
    private var sendJob: Job? = null
    private var baselineTarget: ReadReceiptTarget? = null
    private var pendingSend: PendingReadReceiptSend? = null

    @MainThread
    fun updateVisibleCandidate(
        activeRoomId: String?,
        roomId: String,
        eventId: String?,
        canEstablishBaseline: Boolean
    ) {
        if (activeRoomId != roomId) {
            return
        }
        if (eventId.isNullOrBlank()) {
            clearPendingCandidate()
            return
        }

        val target = ReadReceiptTarget(
            roomId = roomId,
            eventId = eventId
        )

        if (baselineTarget == null) {
            if (!canEstablishBaseline) {
                return
            }
            val pendingBootstrap = PendingReadReceiptSend.Bootstrap(target)
            if (pendingSend == pendingBootstrap) {
                return
            }

            scheduleSend(
                target = target,
                pending = pendingBootstrap
            )
            return
        }

        if (!shouldAdvance(target)) {
            return
        }

        val pendingAdvance = PendingReadReceiptSend.Advance(target)
        if (pendingSend == pendingAdvance) {
            return
        }

        scheduleSend(
            target = target,
            pending = pendingAdvance
        )
    }

    @MainThread
    fun reset() {
        clearPendingCandidate()
        baselineTarget = null
    }

    private fun clearPendingCandidate() {
        sendJob?.cancel()
        sendJob = null
        pendingSend = null
    }

    private fun scheduleSend(
        target: ReadReceiptTarget,
        pending: PendingReadReceiptSend
    ) {
        sendJob?.cancel()
        pendingSend = pending
        sendJob = scope.launch {
            delayBeforeSend(sendDelayMillis)

            val didSend = try {
                sendReadReceipt(target.roomId, target.eventId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                onSendFailure(error)
                false
            }

            finishSend(pending, didSend)
        }
    }

    private fun finishSend(
        pending: PendingReadReceiptSend,
        didSend: Boolean
    ) {
        if (pendingSend == pending) {
            pendingSend = null
        }

        if (didSend) {
            establishBaseline(pending.target)
        }
    }

    private fun shouldAdvance(target: ReadReceiptTarget): Boolean {
        val baseline = baselineTarget
        if (baseline != null && !isNewer(target, reference = baseline)) {
            return false
        }

        val pendingTarget = pendingSend?.target
        if (pendingTarget != null && !isNewer(target, reference = pendingTarget)) {
            return false
        }

        return true
    }

    private fun isNewer(
        target: ReadReceiptTarget,
        reference: ReadReceiptTarget
    ): Boolean {
        if (target.roomId != reference.roomId || target.eventId == reference.eventId) {
            return false
        }

        val targetIndex = messageIndex(target.eventId)
        val referenceIndex = messageIndex(reference.eventId)

        return when {
            targetIndex != null && referenceIndex != null -> targetIndex > referenceIndex
            targetIndex != null && referenceIndex == null -> true
            else -> false
        }
    }

    private fun establishBaseline(target: ReadReceiptTarget) {
        val currentBaseline = baselineTarget
        if (currentBaseline != null && !isNewer(target, reference = currentBaseline)) {
            return
        }

        baselineTarget = target

        val pendingTarget = pendingSend?.target
        if (pendingTarget != null && !isNewer(pendingTarget, reference = target)) {
            clearPendingCandidate()
        }
    }

    private companion object {
        const val SEND_DELAY_MS = 250L
    }
}

private data class ReadReceiptTarget(
    val roomId: String,
    val eventId: String
)

private sealed interface PendingReadReceiptSend {
    val target: ReadReceiptTarget

    data class Bootstrap(
        override val target: ReadReceiptTarget
    ) : PendingReadReceiptSend

    data class Advance(
        override val target: ReadReceiptTarget
    ) : PendingReadReceiptSend
}
