package com.zyna.app.ui.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Runs at most one room snapshot refresh at a time and folds queued requests into one trailing run.
 *
 * Refreshes are scoped to an explicit session. Deactivating that session cancels and joins the
 * active operation, which provides the barrier required before clearing session-owned cache data.
 */
internal class CoalescingRoomRefreshCoordinator(
    scope: CoroutineScope,
    private val operation: suspend (userId: String) -> Unit
) {
    private data class Session(
        val userId: String,
        val generation: Long
    )

    private data class Request(
        val session: Session,
        val completion: CompletableDeferred<Unit>
    )

    private val stateLock = Any()
    private val requests = Channel<Request>(capacity = Channel.UNLIMITED)
    private var nextSessionGeneration = 0L
    private var activeSession: Session? = null
    private var activeOperation: Deferred<Unit>? = null

    private val worker = scope.launch {
        for (firstRequest in requests) {
            val batch = buildList {
                add(firstRequest)
                while (true) {
                    val request = requests.tryReceive().getOrNull() ?: break
                    add(request)
                }
            }

            val session = synchronized(stateLock) { activeSession }
            val currentBatch = batch.filter { request -> request.session == session }
            val staleBatch = batch.filter { request -> request.session != session }
            staleBatch.forEach { request ->
                request.completion.completeExceptionally(
                    CancellationException("Room refresh session changed")
                )
            }
            if (session == null || currentBatch.isEmpty()) {
                continue
            }

            val run = scope.async(start = CoroutineStart.LAZY) {
                operation(session.userId)
            }
            val shouldStart = synchronized(stateLock) {
                if (activeSession == session) {
                    check(activeOperation == null) { "Room refresh operation already active" }
                    activeOperation = run
                    true
                } else {
                    false
                }
            }
            if (!shouldStart) {
                run.cancel(CancellationException("Room refresh session changed"))
                currentBatch.forEach { request ->
                    request.completion.completeExceptionally(
                        CancellationException("Room refresh session changed")
                    )
                }
                continue
            }

            try {
                run.start()
                run.await()
                currentBatch.forEach { request -> request.completion.complete(Unit) }
            } catch (error: CancellationException) {
                currentBatch.forEach { request ->
                    request.completion.completeExceptionally(error)
                }
                currentCoroutineContext().ensureActive()
            } catch (error: Throwable) {
                currentBatch.forEach { request ->
                    request.completion.completeExceptionally(error)
                }
            } finally {
                synchronized(stateLock) {
                    if (activeOperation === run) {
                        activeOperation = null
                    }
                }
            }
        }
    }.also { job ->
        job.invokeOnCompletion { cause ->
            val error = cause ?: CancellationException("Room refresh coordinator stopped")
            requests.close(error)
            while (true) {
                val request = requests.tryReceive().getOrNull() ?: break
                request.completion.completeExceptionally(error)
            }
        }
    }

    fun activateSession(userId: String) {
        synchronized(stateLock) {
            if (activeSession?.userId == userId) {
                return
            }
            check(activeOperation == null) {
                "Deactivate the previous room refresh session before activating a new one"
            }
            nextSessionGeneration += 1
            activeSession = Session(userId = userId, generation = nextSessionGeneration)
        }
    }

    suspend fun deactivateSession() {
        val operationToCancel = synchronized(stateLock) {
            nextSessionGeneration += 1
            activeSession = null
            activeOperation
        }
        operationToCancel?.cancel(CancellationException("Room refresh session deactivated"))
        operationToCancel?.join()
        synchronized(stateLock) {
            if (activeOperation === operationToCancel) {
                activeOperation = null
            }
        }
    }

    suspend fun refresh() {
        val request = synchronized(stateLock) {
            val session = activeSession
                ?: throw CancellationException("Room refresh session is not active")
            Request(session = session, completion = CompletableDeferred())
        }
        requests.send(request)
        val completion = request.completion
        completion.await()
    }
}
