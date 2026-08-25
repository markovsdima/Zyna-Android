package com.zyna.app.data.outgoing

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.zyna.app.ZynaApplication
import com.zyna.app.data.matrix.MatrixClientState
import com.zyna.app.data.security.MatrixSessionSecurityState
import com.zyna.app.data.security.MatrixSessionSecurityStep
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class OutgoingOutboxWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val expectedUserId = inputData.getString(KEY_USER_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return Result.failure()
        val application = applicationContext as? ZynaApplication ?: return Result.retry()
        val container = application.appContainer

        val didRestoreSession = try {
            container.matrixClientService.ensureSessionRestored()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "Outbox worker could not restore the Matrix session", error)
            return Result.retry()
        }
        if (!didRestoreSession) {
            val storedUserId = container.sessionStore.loadLastSession()?.userId
            return if (storedUserId == expectedUserId) Result.retry() else Result.success()
        }

        return when (awaitSessionDecision(application, expectedUserId)) {
            WorkerSessionDecision.READY -> {
                when (container.outgoingOutboxService.runWorkerPass(expectedUserId)) {
                    OutgoingOutboxWorkPass.COMPLETE -> Result.success()
                    OutgoingOutboxWorkPass.RETRY -> Result.retry()
                    OutgoingOutboxWorkPass.SESSION_UNAVAILABLE -> Result.success()
                }
            }
            WorkerSessionDecision.WAITING,
            WorkerSessionDecision.TIMED_OUT -> Result.retry()
            WorkerSessionDecision.USER_ACTION_REQUIRED,
            WorkerSessionDecision.SESSION_UNAVAILABLE -> Result.success()
        }
    }

    private suspend fun awaitSessionDecision(
        application: ZynaApplication,
        expectedUserId: String
    ): WorkerSessionDecision {
        val matrixClientService = application.appContainer.matrixClientService
        return withTimeoutOrNull(SESSION_READY_TIMEOUT_MILLIS) {
            combine(
                matrixClientService.state,
                matrixClientService.sessionSecurityState
            ) { state, security ->
                workerSessionDecision(
                    state = state,
                    security = security,
                    expectedUserId = expectedUserId
                )
            }.first { it != WorkerSessionDecision.WAITING }
        } ?: WorkerSessionDecision.TIMED_OUT
    }

    companion object {
        private const val TAG = "OutgoingOutboxWorker"
        private const val KEY_USER_ID = "user_id"
        private const val UNIQUE_WORK_PREFIX = "zyna-outgoing-outbox-"
        private const val SESSION_READY_TIMEOUT_MILLIS = 60_000L

        internal fun uniqueWorkName(userId: String): String {
            val stableId = UUID.nameUUIDFromBytes(userId.toByteArray(Charsets.UTF_8))
            return UNIQUE_WORK_PREFIX + stableId
        }

        internal fun request(userId: String) =
            OneTimeWorkRequestBuilder<OutgoingOutboxWorker>()
                .setInputData(workDataOf(KEY_USER_ID to userId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10L,
                    TimeUnit.SECONDS
                )
                .build()
    }
}

internal enum class WorkerSessionDecision {
    WAITING,
    READY,
    USER_ACTION_REQUIRED,
    SESSION_UNAVAILABLE,
    TIMED_OUT
}

internal fun workerSessionDecision(
    state: MatrixClientState,
    security: MatrixSessionSecurityState,
    expectedUserId: String
): WorkerSessionDecision {
    val syncingUserId = (state as? MatrixClientState.Syncing)?.userId
    return when {
        state == MatrixClientState.LoggedOut || state is MatrixClientState.Error ->
            WorkerSessionDecision.SESSION_UNAVAILABLE
        syncingUserId != null && syncingUserId != expectedUserId ->
            WorkerSessionDecision.SESSION_UNAVAILABLE
        syncingUserId == expectedUserId &&
            security.userId == expectedUserId &&
            security.readyForEncryptedTraffic -> WorkerSessionDecision.READY
        syncingUserId == expectedUserId &&
            security.userId == expectedUserId &&
            security.step != MatrixSessionSecurityStep.CHECKING ->
            WorkerSessionDecision.USER_ACTION_REQUIRED
        else -> WorkerSessionDecision.WAITING
    }
}

internal object OutgoingOutboxWorkScheduler {
    fun enqueueLatest(context: Context, userId: String) {
        enqueue(
            context,
            userId,
            outgoingOutboxWorkPolicy(OutgoingOutboxWorkEnqueueReason.DURABLE_WRITE)
        )
    }

    fun ensureScheduled(context: Context, userId: String) {
        enqueue(
            context,
            userId,
            outgoingOutboxWorkPolicy(OutgoingOutboxWorkEnqueueReason.SESSION_BOOTSTRAP)
        )
    }

    private fun enqueue(context: Context, userId: String, policy: ExistingWorkPolicy) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            OutgoingOutboxWorker.uniqueWorkName(userId),
            policy,
            OutgoingOutboxWorker.request(userId)
        )
    }
}

internal enum class OutgoingOutboxWorkEnqueueReason {
    DURABLE_WRITE,
    SESSION_BOOTSTRAP
}

/**
 * A new durable write replaces older work so it cannot land in the worker shutdown window and
 * deserves an immediate attempt. Session bootstrap keeps existing work to avoid self-cancellation.
 */
internal fun outgoingOutboxWorkPolicy(
    reason: OutgoingOutboxWorkEnqueueReason
): ExistingWorkPolicy {
    return when (reason) {
        OutgoingOutboxWorkEnqueueReason.DURABLE_WRITE -> ExistingWorkPolicy.REPLACE
        OutgoingOutboxWorkEnqueueReason.SESSION_BOOTSTRAP -> ExistingWorkPolicy.KEEP
    }
}
