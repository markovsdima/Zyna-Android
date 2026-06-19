package com.zyna.app.data.outgoing

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.zyna.app.data.local.LocalCacheRepository
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixClientState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.ClientException

data class OutgoingOutboxFailure(
    val roomId: String,
    val message: String
)

class OutgoingOutboxService(
    private val matrixClientService: MatrixClientService,
    private val localCacheRepository: LocalCacheRepository,
    context: Context
) {
    private val appContext = context.applicationContext
    private val retryBackoff = OutgoingRetryBackoff<String>()
    private val inFlight = OutgoingInFlightTracker<String>()
    private val _sendFailures = MutableSharedFlow<OutgoingOutboxFailure>(
        extraBufferCapacity = 16
    )

    val sendFailures: SharedFlow<OutgoingOutboxFailure> = _sendFailures.asSharedFlow()

    private var scope: CoroutineScope? = null
    private var stateJob: Job? = null
    private var scanJob: Job? = null
    private var pendingScanReason: String? = null
    private var pendingEnvelopeIds: Set<String>? = null
    private var wakeJob: Job? = null
    private var wakeAtMillis: Long? = null

    fun start(scope: CoroutineScope) {
        if (stateJob?.isActive == true) {
            return
        }

        this.scope = scope
        stateJob = scope.launch {
            matrixClientService.state.collect { state ->
                handleClientState(state)
            }
        }
    }

    fun kick(reason: String, envelopeId: String? = null) {
        kick(reason, envelopeId?.let { setOf(it) })
    }

    private fun kick(reason: String, envelopeIds: Set<String>?) {
        if (!canScan()) {
            return
        }
        val activeScope = scope ?: return

        if (scanJob?.isActive == true) {
            val hadPendingScan = pendingScanReason != null
            pendingScanReason = pendingScanReason?.let { "$it,$reason" } ?: reason
            mergePendingEnvelopeIds(envelopeIds, hadPendingScan)
            return
        }

        startScan(activeScope, reason, envelopeIds)
    }

    private fun scheduleWake(delayMillis: Long, reason: String) {
        val activeScope = scope ?: return
        if (delayMillis <= 0L) {
            kick(reason)
            return
        }

        val nextWakeAt = SystemClock.elapsedRealtime() + delayMillis
        val currentWakeAt = wakeAtMillis
        if (currentWakeAt != null && currentWakeAt <= nextWakeAt) {
            return
        }

        wakeJob?.cancel()
        wakeAtMillis = nextWakeAt
        wakeJob = activeScope.launch {
            delay(delayMillis)
            wakeJob = null
            wakeAtMillis = null
            kick(reason)
        }
    }

    private suspend fun handleClientState(state: MatrixClientState) {
        if (canScan(state)) {
            kick("syncing")
        } else {
            pendingScanReason = null
            pendingEnvelopeIds = null
            wakeJob?.cancel()
            wakeJob = null
            wakeAtMillis = null
            scanJob?.cancelAndJoin()
            scanJob = null
            retryBackoff.clearAll()
            inFlight.clear()
        }
    }

    private fun mergePendingEnvelopeIds(envelopeIds: Set<String>?, hadPendingScan: Boolean) {
        if (!hadPendingScan) {
            pendingEnvelopeIds = envelopeIds
            return
        }
        if (pendingEnvelopeIds == null || envelopeIds == null) {
            pendingEnvelopeIds = null
            return
        }
        pendingEnvelopeIds = pendingEnvelopeIds.orEmpty() + envelopeIds
    }

    private fun startScan(scope: CoroutineScope, reason: String, envelopeIds: Set<String>?) {
        scanJob = scope.launch {
            try {
                runScan(reason, envelopeIds)
            } finally {
                finishScan()
            }
        }
    }

    private suspend fun finishScan() {
        scanJob = null
        val reason = pendingScanReason ?: return
        val envelopeIds = pendingEnvelopeIds
        pendingScanReason = null
        pendingEnvelopeIds = null
        kick(reason, envelopeIds)
    }

    private suspend fun runScan(reason: String, envelopeIds: Set<String>?) {
        val userId = syncingUserIdOrNull() ?: return
        val textCandidates = localCacheRepository.outgoingTextDispatchCandidates(
            userId = userId,
            envelopeIds = envelopeIds
        )
        val imageCandidates = localCacheRepository.outgoingImageDispatchCandidates(
            userId = userId,
            envelopeIds = envelopeIds
        )
        val redactionCandidates = localCacheRepository.outgoingRedactionDispatchCandidates(
            userId = userId,
            envelopeIds = envelopeIds
        )
        val editCandidates = localCacheRepository.outgoingEditDispatchCandidates(
            userId = userId
        )
        if (
            textCandidates.isEmpty() &&
            imageCandidates.isEmpty() &&
            redactionCandidates.isEmpty() &&
            editCandidates.isEmpty()
        ) {
            Log.d(TAG, "outbox scan reason=$reason count=0")
            return
        }

        Log.d(
            TAG,
            "outbox scan reason=$reason text=${textCandidates.size} " +
                "images=${imageCandidates.size} " +
                "redactions=${redactionCandidates.size} edits=${editCandidates.size}"
        )
        for (candidate in textCandidates) {
            currentCoroutineContext().ensureActive()
            if (!canScan()) {
                return
            }
            sendTextIfEligible(candidate, reason)
        }
        for (candidate in imageCandidates) {
            currentCoroutineContext().ensureActive()
            if (!canScan()) {
                return
            }
            sendImageIfEligible(candidate, reason)
        }
        for (candidate in redactionCandidates) {
            currentCoroutineContext().ensureActive()
            if (!canScan()) {
                return
            }
            sendRedactionIfEligible(candidate, reason)
        }
        for (candidate in editCandidates) {
            currentCoroutineContext().ensureActive()
            if (!canScan()) {
                return
            }
            sendEditIfEligible(candidate, reason)
        }
    }

    private suspend fun sendTextIfEligible(candidate: OutgoingTextEnvelope, reason: String) {
        if (!inFlight.begin(candidate.id)) {
            return
        }

        try {
            when (val decision = attemptDecision(candidate)) {
                AttemptDecision.Send -> Unit
                is AttemptDecision.Wait -> {
                    Log.d(
                        TAG,
                        "outbox wait reason=$reason envelope=${candidate.id} " +
                            "state=${candidate.transportState} delayMillis=${decision.delayMillis}"
                    )
                    scheduleWake(decision.delayMillis, reason = "delayed-$reason")
                    return
                }
                AttemptDecision.Skip -> return
            }

            localCacheRepository.markOutgoingDispatchStarted(
                userId = candidate.userId,
                roomId = candidate.roomId,
                envelopeId = candidate.id
            )
            val eventId = matrixClientService.sendTextMessage(
                roomId = candidate.roomId,
                body = candidate.body,
                transactionId = candidate.transactionId,
                replyInfo = candidate.replyInfo,
                forwardedFrom = candidate.forwardedFrom
            )
            retryBackoff.clear(candidate.id)
            localCacheRepository.markOutgoingDispatchAccepted(
                userId = candidate.userId,
                roomId = candidate.roomId,
                envelopeId = candidate.id,
                eventId = eventId
            )
            Log.d(TAG, "outbox sent envelope=${candidate.id} event=$eventId")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            completeFailure(candidate, error)
        } finally {
            inFlight.end(candidate.id)
        }
    }

    private suspend fun sendImageIfEligible(candidate: OutgoingImageEnvelope, reason: String) {
        if (!inFlight.begin(candidate.id)) {
            return
        }

        try {
            when (val decision = attemptDecision(candidate.transportState, candidate.id)) {
                AttemptDecision.Send -> Unit
                is AttemptDecision.Wait -> {
                    Log.d(
                        TAG,
                        "outbox image wait reason=$reason envelope=${candidate.id} " +
                            "state=${candidate.transportState} delayMillis=${decision.delayMillis}"
                    )
                    scheduleWake(decision.delayMillis, reason = "delayed-$reason")
                    return
                }
                AttemptDecision.Skip -> return
            }

            localCacheRepository.markOutgoingDispatchStarted(
                userId = candidate.userId,
                roomId = candidate.roomId,
                envelopeId = candidate.id
            )
            val uploadedImageJson = candidate.uploadedImageJson
                ?: uploadImageAndCheckpoint(candidate)
                ?: return
            val eventId = matrixClientService.sendUploadedImageMessage(
                roomId = candidate.roomId,
                uploadedImageJson = uploadedImageJson,
                caption = candidate.caption,
                transactionId = candidate.transactionId,
                zynaAttributesJson = candidate.zynaAttributesJson
            )
            retryBackoff.clear(candidate.id)
            localCacheRepository.markOutgoingDispatchAccepted(
                userId = candidate.userId,
                roomId = candidate.roomId,
                envelopeId = candidate.id,
                eventId = eventId
            )
            Log.d(TAG, "outbox image sent envelope=${candidate.id} event=$eventId")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            completeImageFailure(candidate, error)
        } finally {
            inFlight.end(candidate.id)
        }
    }

    private suspend fun uploadImageAndCheckpoint(candidate: OutgoingImageEnvelope): String? {
        val localPath = candidate.localPath
            ?: error("Image file is not available")
        val uploadedImageJson = matrixClientService.uploadImageForEvent(
            roomId = candidate.roomId,
            localPath = localPath,
            mimeType = candidate.mimeType,
            sizeBytes = candidate.sizeBytes,
            width = candidate.width,
            height = candidate.height
        )
        currentCoroutineContext().ensureActive()
        val didCheckpoint = localCacheRepository.markOutgoingImageUploadAccepted(
            userId = candidate.userId,
            roomId = candidate.roomId,
            envelopeId = candidate.id,
            uploadedImageJson = uploadedImageJson
        )
        if (!didCheckpoint) {
            Log.d(TAG, "outbox image upload skipped envelope=${candidate.id}")
            return null
        }
        currentCoroutineContext().ensureActive()
        OutgoingOutboxDebugHooks.crashAfterImageUploadCheckpointIfRequested(
            context = appContext,
            envelopeId = candidate.id,
            transactionId = candidate.transactionId
        )
        Log.d(
            TAG,
            "outbox image uploaded envelope=${candidate.id} bytes=${uploadedImageJson.length}"
        )
        return uploadedImageJson
    }

    private suspend fun sendRedactionIfEligible(
        candidate: OutgoingRedactionEnvelope,
        reason: String
    ) {
        if (!inFlight.begin(candidate.id)) {
            return
        }

        try {
            when (val decision = attemptDecision(candidate.transportState, candidate.id)) {
                AttemptDecision.Send -> Unit
                is AttemptDecision.Wait -> {
                    Log.d(
                        TAG,
                        "outbox redaction wait reason=$reason envelope=${candidate.id} " +
                            "state=${candidate.transportState} delayMillis=${decision.delayMillis}"
                    )
                    scheduleWake(decision.delayMillis, reason = "delayed-$reason")
                    return
                }
                AttemptDecision.Skip -> return
            }

            localCacheRepository.markOutgoingDispatchStarted(
                userId = candidate.userId,
                roomId = candidate.roomId,
                envelopeId = candidate.id
            )
            val redactionEventId = matrixClientService.redactMessage(
                roomId = candidate.roomId,
                eventId = candidate.targetEventId,
                transactionId = candidate.transactionId
            )
            retryBackoff.clear(candidate.id)
            localCacheRepository.markOutgoingRedactionDispatchAccepted(
                userId = candidate.userId,
                roomId = candidate.roomId,
                envelopeId = candidate.id,
                redactionEventId = redactionEventId
            )
            Log.d(
                TAG,
                "outbox redacted envelope=${candidate.id} target=${candidate.targetEventId} " +
                    "event=$redactionEventId"
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            completeRedactionFailure(candidate, error)
        } finally {
            inFlight.end(candidate.id)
        }
    }

    private suspend fun sendEditIfEligible(candidate: OutgoingEditEnvelope, reason: String) {
        if (!inFlight.begin(candidate.id)) {
            return
        }

        try {
            when (val decision = attemptDecision(OutgoingTransportState.RETRYING, candidate.id)) {
                AttemptDecision.Send -> Unit
                is AttemptDecision.Wait -> {
                    Log.d(
                        TAG,
                        "outbox edit wait reason=$reason edit=${candidate.id} " +
                            "delayMillis=${decision.delayMillis}"
                    )
                    scheduleWake(decision.delayMillis, reason = "delayed-$reason")
                    return
                }
                AttemptDecision.Skip -> return
            }

            val editEventId = matrixClientService.sendTextEdit(
                roomId = candidate.roomId,
                eventId = candidate.eventId,
                body = candidate.body,
                transactionId = candidate.transactionId
            )
            retryBackoff.clear(candidate.id)
            localCacheRepository.markOutgoingEditDispatchAccepted(
                userId = candidate.userId,
                roomId = candidate.roomId,
                eventId = candidate.eventId,
                transactionId = candidate.transactionId,
                editEventId = editEventId,
                body = candidate.body
            )
            Log.d(
                TAG,
                "outbox edited edit=${candidate.id} target=${candidate.eventId} " +
                    "event=$editEventId"
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            completeEditFailure(candidate, error)
        } finally {
            inFlight.end(candidate.id)
        }
    }

    private suspend fun completeFailure(candidate: OutgoingTextEnvelope, error: Throwable) {
        val failureMessage = error.message ?: error.javaClass.simpleName
        if (error.isRetryableTransportError()) {
            localCacheRepository.markOutgoingDispatchRetrying(
                userId = candidate.userId,
                roomId = candidate.roomId,
                envelopeId = candidate.id,
                failureMessage = failureMessage
            )
            val delayMillis = retryBackoff.scheduleRetry(candidate.id)
            scheduleWake(delayMillis, reason = "retryable-failure")
            Log.d(TAG, "outbox retrying envelope=${candidate.id} delayMillis=$delayMillis")
            return
        }

        retryBackoff.clear(candidate.id)
        localCacheRepository.markOutgoingDispatchFailed(
            userId = candidate.userId,
            roomId = candidate.roomId,
            envelopeId = candidate.id,
            failureMessage = failureMessage
        )
        _sendFailures.tryEmit(
            OutgoingOutboxFailure(
                roomId = candidate.roomId,
                message = failureMessage
            )
        )
        Log.w(TAG, "outbox failed envelope=${candidate.id}", error)
    }

    private suspend fun completeImageFailure(candidate: OutgoingImageEnvelope, error: Throwable) {
        val failureMessage = error.message ?: error.javaClass.simpleName
        if (error.isRetryableTransportError()) {
            localCacheRepository.markOutgoingDispatchRetrying(
                userId = candidate.userId,
                roomId = candidate.roomId,
                envelopeId = candidate.id,
                failureMessage = failureMessage
            )
            val delayMillis = retryBackoff.scheduleRetry(candidate.id)
            scheduleWake(delayMillis, reason = "retryable-image-failure")
            Log.d(TAG, "outbox image retrying envelope=${candidate.id} delayMillis=$delayMillis")
            return
        }

        retryBackoff.clear(candidate.id)
        localCacheRepository.markOutgoingDispatchFailed(
            userId = candidate.userId,
            roomId = candidate.roomId,
            envelopeId = candidate.id,
            failureMessage = failureMessage
        )
        _sendFailures.tryEmit(
            OutgoingOutboxFailure(
                roomId = candidate.roomId,
                message = failureMessage
            )
        )
        Log.w(TAG, "outbox image failed envelope=${candidate.id}", error)
    }

    private suspend fun completeRedactionFailure(
        candidate: OutgoingRedactionEnvelope,
        error: Throwable
    ) {
        val failureMessage = error.message ?: error.javaClass.simpleName
        if (error.isAlreadyRedactedError()) {
            retryBackoff.clear(candidate.id)
            localCacheRepository.markOutgoingRedactionDispatchResolved(
                userId = candidate.userId,
                roomId = candidate.roomId,
                envelopeId = candidate.id
            )
            Log.d(TAG, "outbox redaction already resolved envelope=${candidate.id}")
            return
        }

        if (error.isRetryableTransportError()) {
            localCacheRepository.markOutgoingDispatchRetrying(
                userId = candidate.userId,
                roomId = candidate.roomId,
                envelopeId = candidate.id,
                failureMessage = failureMessage
            )
            val delayMillis = retryBackoff.scheduleRetry(candidate.id)
            scheduleWake(delayMillis, reason = "retryable-redaction-failure")
            Log.d(
                TAG,
                "outbox redaction retrying envelope=${candidate.id} delayMillis=$delayMillis"
            )
            return
        }

        retryBackoff.clear(candidate.id)
        localCacheRepository.markOutgoingRedactionDispatchTerminalFailure(
            userId = candidate.userId,
            roomId = candidate.roomId,
            envelopeId = candidate.id,
            failureMessage = failureMessage
        )
        _sendFailures.tryEmit(
            OutgoingOutboxFailure(
                roomId = candidate.roomId,
                message = failureMessage
            )
        )
        Log.w(TAG, "outbox redaction failed envelope=${candidate.id}", error)
    }

    private suspend fun completeEditFailure(candidate: OutgoingEditEnvelope, error: Throwable) {
        val failureMessage = error.message ?: error.javaClass.simpleName
        if (error.isRetryableTransportError()) {
            val delayMillis = retryBackoff.scheduleRetry(candidate.id)
            scheduleWake(delayMillis, reason = "retryable-edit-failure")
            Log.d(TAG, "outbox edit retrying edit=${candidate.id} delayMillis=$delayMillis")
            return
        }

        retryBackoff.clear(candidate.id)
        localCacheRepository.markOutgoingEditDispatchTerminalFailure(
            userId = candidate.userId,
            roomId = candidate.roomId,
            eventId = candidate.eventId,
            transactionId = candidate.transactionId
        )
        _sendFailures.tryEmit(
            OutgoingOutboxFailure(
                roomId = candidate.roomId,
                message = failureMessage
            )
        )
        Log.w(TAG, "outbox edit failed edit=${candidate.id}", error)
    }

    private fun attemptDecision(envelope: OutgoingTextEnvelope): AttemptDecision {
        return attemptDecision(envelope.transportState, envelope.id)
    }

    private fun attemptDecision(
        transportState: OutgoingTransportState,
        envelopeId: String
    ): AttemptDecision {
        return when (transportState) {
            OutgoingTransportState.QUEUED,
            OutgoingTransportState.SENDING -> AttemptDecision.Send
            OutgoingTransportState.RETRYING -> {
                val delay = retryBackoff.waitDelayMillis(envelopeId)
                if (delay == null) AttemptDecision.Send else AttemptDecision.Wait(delay)
            }
            OutgoingTransportState.SENT,
            OutgoingTransportState.FAILED,
            OutgoingTransportState.RETIRED -> AttemptDecision.Skip
        }
    }

    private fun canScan(state: MatrixClientState = matrixClientService.state.value): Boolean {
        val userId = (state as? MatrixClientState.Syncing)?.userId ?: return false
        return matrixClientService.isRecoveryComplete(userId)
    }

    private fun syncingUserIdOrNull(): String? {
        val state = matrixClientService.state.value
        val userId = (state as? MatrixClientState.Syncing)?.userId ?: return null
        return userId.takeIf { matrixClientService.isRecoveryComplete(it) }
    }

    private sealed interface AttemptDecision {
        data object Send : AttemptDecision
        data class Wait(val delayMillis: Long) : AttemptDecision
        data object Skip : AttemptDecision
    }

    private companion object {
        const val TAG = "OutgoingOutbox"
    }
}

private class OutgoingRetryBackoff<Key : Any>(
    private val initialDelayMillis: Long = 5_000L,
    private val maxDelayMillis: Long = 60_000L
) {
    private val nextRetryAtByKey = mutableMapOf<Key, Long>()
    private val retryDelayByKey = mutableMapOf<Key, Long>()

    fun waitDelayMillis(key: Key): Long? {
        val nextRetryAt = nextRetryAtByKey[key] ?: return null
        val delay = nextRetryAt - SystemClock.elapsedRealtime()
        return delay.takeIf { it > 0L }
    }

    fun scheduleRetry(key: Key): Long {
        val delay = retryDelayByKey[key] ?: initialDelayMillis
        retryDelayByKey[key] = (delay * 2).coerceAtMost(maxDelayMillis)
        nextRetryAtByKey[key] = SystemClock.elapsedRealtime() + delay
        return delay
    }

    fun clear(key: Key) {
        nextRetryAtByKey.remove(key)
        retryDelayByKey.remove(key)
    }

    fun clearAll() {
        nextRetryAtByKey.clear()
        retryDelayByKey.clear()
    }
}

private class OutgoingInFlightTracker<Key : Any> {
    private val keys = mutableSetOf<Key>()

    fun begin(key: Key): Boolean {
        return keys.add(key)
    }

    fun end(key: Key) {
        keys.remove(key)
    }

    fun clear() {
        keys.clear()
    }
}

private fun Throwable.isRetryableTransportError(): Boolean {
    if (this is ClientException.MatrixApi) {
        val code = code.lowercase()
        if (
            code.contains("unknown_token") ||
            code.contains("forbidden") ||
            code.contains("unauthorized")
        ) {
            return false
        }
    }

    val text = generateSequence(this) { it.cause }
        .joinToString(separator = "\n") { error ->
            "${error.javaClass.name}\n${error.message.orEmpty()}"
        }
        .lowercase()
    if (
        text.contains("crosssigning") ||
        text.contains("cross signing") ||
        text.contains("unverified device") ||
        text.contains("deviceverificationrequired") ||
        text.contains("unsigned device") ||
        text.contains("senderidentitynottrusted") ||
        text.contains("unknown_token") ||
        text.contains("soft logout")
    ) {
        return false
    }

    return listOf(
        "network",
        "not connected",
        "notconnectedtointernet",
        "connection lost",
        "networkconnectionlost",
        "network is unreachable",
        "no route to host",
        "cannot find host",
        "cannotfindhost",
        "cannot connect",
        "cannotconnecttohost",
        "connection refused",
        "connection reset",
        "dns",
        "timed out",
        "timeout",
        "temporarily unavailable",
        "service unavailable",
        "bad gateway",
        "gateway timeout",
        "server error",
        "servererror",
        "too many requests",
        "limit_exceeded"
    ).any { text.contains(it) }
}

private fun Throwable.isAlreadyRedactedError(): Boolean {
    val text = generateSequence(this) { it.cause }
        .joinToString(separator = "\n") { error ->
            "${error.javaClass.name}\n${error.message.orEmpty()}"
        }
        .lowercase()

    return text.contains("already redacted") ||
        text.contains("event already redacted") ||
        text.contains("was already redacted")
}
