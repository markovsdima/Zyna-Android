package com.zyna.app.data.outgoing

import androidx.work.ExistingWorkPolicy
import com.zyna.app.data.matrix.MatrixClientState
import com.zyna.app.data.security.MatrixSessionSecurityState
import com.zyna.app.data.security.MatrixSessionSecurityStep
import com.zyna.app.data.security.MatrixVerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class OutgoingOutboxWorkerTest {
    @Test
    fun matchingReadySession_canDrainOutbox() {
        val decision = workerSessionDecision(
            state = MatrixClientState.Syncing(USER_ID),
            security = readySecurityState(USER_ID),
            expectedUserId = USER_ID
        )

        assertEquals(WorkerSessionDecision.READY, decision)
    }

    @Test
    fun differentActiveAccount_cannotDrainOutbox() {
        val decision = workerSessionDecision(
            state = MatrixClientState.Syncing("@bob:example.org"),
            security = readySecurityState("@bob:example.org"),
            expectedUserId = USER_ID
        )

        assertEquals(WorkerSessionDecision.SESSION_UNAVAILABLE, decision)
    }

    @Test
    fun securityRequiringUserAction_doesNotSendInBackground() {
        val decision = workerSessionDecision(
            state = MatrixClientState.Syncing(USER_ID),
            security = MatrixSessionSecurityState(
                userId = USER_ID,
                step = MatrixSessionSecurityStep.INITIAL
            ),
            expectedUserId = USER_ID
        )

        assertEquals(WorkerSessionDecision.USER_ACTION_REQUIRED, decision)
    }

    @Test
    fun uniqueWorkName_isStablePrivateAndAccountScoped() {
        val first = OutgoingOutboxWorker.uniqueWorkName(USER_ID)
        val second = OutgoingOutboxWorker.uniqueWorkName(USER_ID)
        val other = OutgoingOutboxWorker.uniqueWorkName("@bob:example.org")

        assertEquals(first, second)
        assertNotEquals(first, other)
        assertFalse(first.contains(USER_ID))
    }

    @Test
    fun workerPassResult_coversCompletionRetryAndUnavailableSession() {
        assertEquals(
            OutgoingOutboxWorkPass.SESSION_UNAVAILABLE,
            workerPassResult(
                sessionAvailable = false,
                didBecomeIdle = false,
                hasPersistedCandidates = true
            )
        )
        assertEquals(
            OutgoingOutboxWorkPass.RETRY,
            workerPassResult(
                sessionAvailable = true,
                didBecomeIdle = false,
                hasPersistedCandidates = false
            )
        )
        assertEquals(
            OutgoingOutboxWorkPass.RETRY,
            workerPassResult(
                sessionAvailable = true,
                didBecomeIdle = true,
                hasPersistedCandidates = true
            )
        )
        assertEquals(
            OutgoingOutboxWorkPass.COMPLETE,
            workerPassResult(
                sessionAvailable = true,
                didBecomeIdle = true,
                hasPersistedCandidates = false
            )
        )
    }

    @Test
    fun workPolicy_replacesNewWritesAndKeepsSessionBootstrap() {
        assertEquals(
            ExistingWorkPolicy.REPLACE,
            outgoingOutboxWorkPolicy(OutgoingOutboxWorkEnqueueReason.DURABLE_WRITE)
        )
        assertEquals(
            ExistingWorkPolicy.KEEP,
            outgoingOutboxWorkPolicy(OutgoingOutboxWorkEnqueueReason.SESSION_BOOTSTRAP)
        )
    }

    private fun readySecurityState(userId: String) = MatrixSessionSecurityState(
        userId = userId,
        step = MatrixSessionSecurityStep.VERIFIED,
        verificationStatus = MatrixVerificationStatus.VERIFIED,
        gateComplete = true
    )

    private companion object {
        const val USER_ID = "@alice:example.org"
    }
}
