package com.zyna.app.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatrixSessionSecurityStateTest {
    @Test
    fun pendingRecoverySetup_blocksEncryptedTrafficEvenWithLocalSecrets() {
        val state = MatrixSessionSecurityState(
            hasLocalSecrets = true,
            hasPendingRecoverySetup = true,
            gateComplete = true
        )

        assertFalse(state.canSendEncryptedMessages)
        assertFalse(state.readyForEncryptedTraffic)
        assertEquals(MatrixLogoutWarning.SECURITY_NOT_READY, state.immediateLogoutWarning)
    }

    @Test
    fun completedLocalRecovery_allowsEncryptedTrafficAfterGate() {
        val beforeGate = MatrixSessionSecurityState(
            hasLocalSecrets = true,
            hasPendingRecoverySetup = false,
            gateComplete = false
        )

        assertTrue(beforeGate.canSendEncryptedMessages)
        assertFalse(beforeGate.readyForEncryptedTraffic)
        assertNull(beforeGate.immediateLogoutWarning)
        assertTrue(beforeGate.copy(gateComplete = true).readyForEncryptedTraffic)
    }

    @Test
    fun verifiedDevice_canSendWithoutPersistedRecoverySecrets() {
        val state = MatrixSessionSecurityState(
            verificationStatus = MatrixVerificationStatus.VERIFIED,
            gateComplete = true
        )

        assertTrue(state.canSendEncryptedMessages)
        assertTrue(state.readyForEncryptedTraffic)
    }

    @Test
    fun skippedUnverifiedDevice_doesNotReleaseEncryptedOutbox() {
        val state = MatrixSessionSecurityState(
            step = MatrixSessionSecurityStep.SKIPPED,
            gateComplete = true
        )

        assertFalse(state.canSendEncryptedMessages)
        assertFalse(state.readyForEncryptedTraffic)
        assertEquals(MatrixLogoutWarning.SECURITY_NOT_READY, state.immediateLogoutWarning)
    }
}
