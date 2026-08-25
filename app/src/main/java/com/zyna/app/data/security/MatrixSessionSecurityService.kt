package com.zyna.app.data.security

import android.util.Log
import com.zyna.app.data.session.MatrixSessionStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.matrix.rustcomponents.sdk.BackupState
import org.matrix.rustcomponents.sdk.BackupStateListener
import org.matrix.rustcomponents.sdk.BackupSteadyStateListener
import org.matrix.rustcomponents.sdk.BackupUploadState
import org.matrix.rustcomponents.sdk.AuthData
import org.matrix.rustcomponents.sdk.AuthDataPasswordDetails
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.CrossSigningResetAuthType
import org.matrix.rustcomponents.sdk.EnableRecoveryProgress
import org.matrix.rustcomponents.sdk.EnableRecoveryProgressListener
import org.matrix.rustcomponents.sdk.Encryption
import org.matrix.rustcomponents.sdk.RecoveryState
import org.matrix.rustcomponents.sdk.RecoveryException
import org.matrix.rustcomponents.sdk.RecoveryStateListener
import org.matrix.rustcomponents.sdk.SessionVerificationController
import org.matrix.rustcomponents.sdk.SessionVerificationControllerDelegate
import org.matrix.rustcomponents.sdk.SessionVerificationData
import org.matrix.rustcomponents.sdk.SessionVerificationRequestDetails
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.VerificationState
import org.matrix.rustcomponents.sdk.VerificationStateListener

enum class MatrixSessionSecurityMode {
    CHECKING,
    FIRST_DEVICE,
    OTHER_DEVICE,
    RESPONDER
}

enum class MatrixSessionSecurityStep {
    CHECKING,
    INITIAL,
    REQUESTING_VERIFICATION,
    WAITING_FOR_ACCEPTANCE,
    ACCEPTING_REQUEST,
    SHOWING_EMOJIS,
    GENERATING_RECOVERY_KEY,
    FINISHING_RECOVERY_SETUP,
    SHOWING_RECOVERY_KEY,
    ENTERING_RECOVERY_KEY,
    RESTORING_FROM_RECOVERY_KEY,
    WAITING_FOR_SECRETS,
    NEEDS_RECOVERY_KEY,
    VERIFIED,
    CANCELLED,
    FAILED,
    SKIPPED
}

enum class MatrixVerificationStatus {
    UNKNOWN,
    VERIFIED,
    UNVERIFIED
}

enum class MatrixRecoveryStatus {
    UNKNOWN,
    ENABLED,
    DISABLED,
    INCOMPLETE
}

enum class MatrixBackupStatus {
    UNKNOWN,
    CREATING,
    ENABLING,
    RESUMING,
    ENABLED,
    DOWNLOADING,
    DISABLING
}

enum class MatrixLogoutWarning {
    SECURITY_NOT_READY,
    BACKUP_NOT_READY
}

data class MatrixVerificationEmoji(
    val symbol: String,
    val description: String
)

data class MatrixIncomingVerificationRequest(
    val senderId: String,
    val flowId: String,
    val deviceId: String,
    val deviceDisplayName: String?
)

data class MatrixSessionSecurityState(
    val userId: String? = null,
    val mode: MatrixSessionSecurityMode = MatrixSessionSecurityMode.CHECKING,
    val step: MatrixSessionSecurityStep = MatrixSessionSecurityStep.CHECKING,
    val verificationStatus: MatrixVerificationStatus = MatrixVerificationStatus.UNKNOWN,
    val recoveryStatus: MatrixRecoveryStatus = MatrixRecoveryStatus.UNKNOWN,
    val backupStatus: MatrixBackupStatus = MatrixBackupStatus.UNKNOWN,
    val backupExistsOnServer: Boolean? = null,
    val hasOtherDeviceToVerify: Boolean? = null,
    val hasLocalSecrets: Boolean = false,
    val hasPendingRecoverySetup: Boolean = false,
    val hasCompletedRecoverySetup: Boolean = false,
    val gateComplete: Boolean = false,
    val emojis: List<MatrixVerificationEmoji> = emptyList(),
    val recoveryKey: String? = null,
    val incomingRequest: MatrixIncomingVerificationRequest? = null,
    val progressMessage: String? = null,
    val warningMessage: String? = null,
    val errorMessage: String? = null
) {
    val canSendEncryptedMessages: Boolean
        get() = !hasPendingRecoverySetup &&
            (hasLocalSecrets || verificationStatus == MatrixVerificationStatus.VERIFIED)

    val readyForEncryptedTraffic: Boolean
        get() = gateComplete && canSendEncryptedMessages

    val immediateLogoutWarning: MatrixLogoutWarning?
        get() = if (canSendEncryptedMessages) {
            null
        } else {
            MatrixLogoutWarning.SECURITY_NOT_READY
        }
}

sealed interface MatrixSessionSecurityAction {
    data object SetUpRecovery : MatrixSessionSecurityAction
    data object UseRecoveryKey : MatrixSessionSecurityAction
    data class RestoreWithRecoveryKey(val recoveryKey: String) : MatrixSessionSecurityAction
    data object ConfirmRecoveryKeySaved : MatrixSessionSecurityAction
    data object StartVerification : MatrixSessionSecurityAction
    data object AcceptIncomingVerification : MatrixSessionSecurityAction
    data object ApproveEmojis : MatrixSessionSecurityAction
    data object DeclineEmojis : MatrixSessionSecurityAction
    data object CancelVerification : MatrixSessionSecurityAction
    data object Continue : MatrixSessionSecurityAction
    data object Skip : MatrixSessionSecurityAction
    data object Retry : MatrixSessionSecurityAction
    data object Manage : MatrixSessionSecurityAction
    data class ResetEncryption(val password: String) : MatrixSessionSecurityAction
}

/**
 * Owns the E2EE session lifecycle for the currently attached Matrix client.
 *
 * The SDK states remain the source of truth. The only persisted values answer
 * questions the SDK cannot answer after process restart: whether this install
 * has local cross-signing secrets, and whether the user finished saving a newly
 * generated recovery key.
 */
class MatrixSessionSecurityService(
    private val sessionStore: MatrixSessionStore
) : SessionVerificationControllerDelegate {
    private val _state = MutableStateFlow(MatrixSessionSecurityState())
    val state: StateFlow<MatrixSessionSecurityState> = _state.asStateFlow()

    private val lifecycleMutex = Mutex()
    private var client: Client? = null
    private var encryption: Encryption? = null
    private var controller: SessionVerificationController? = null
    private var verificationStateHandle: TaskHandle? = null
    private var recoveryStateHandle: TaskHandle? = null
    private var backupStateHandle: TaskHandle? = null
    private var sessionScope: CoroutineScope? = null
    private var initializationJob: Job? = null
    private var isInitiator = false

    suspend fun attach(activeClient: Client) {
        lifecycleMutex.withLock {
            if (client === activeClient) {
                return
            }
            detachLocked(resetState = false)
            client = activeClient
            encryption = activeClient.encryption()
            sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

            val userId = activeClient.userId()
            _state.value = MatrixSessionSecurityState(
                userId = userId,
                hasLocalSecrets = sessionStore.hasLocalEncryptionSecrets(userId),
                hasPendingRecoverySetup = sessionStore.hasPendingRecoverySetup(userId),
                hasCompletedRecoverySetup = sessionStore.hasCompletedRecoverySetup(userId)
            )
            attachStateListeners()
            refreshSynchronousSdkState()
            initializationJob = sessionScope?.launch {
                initialize(activeClient)
            }
        }
    }

    suspend fun detach() {
        lifecycleMutex.withLock {
            detachLocked(resetState = true)
        }
    }

    fun handle(action: MatrixSessionSecurityAction) {
        when (action) {
            MatrixSessionSecurityAction.SetUpRecovery -> launchOperation(::setUpRecovery)
            MatrixSessionSecurityAction.UseRecoveryKey -> showRecoveryKeyEntry()
            is MatrixSessionSecurityAction.RestoreWithRecoveryKey -> {
                launchOperation { restoreWithRecoveryKey(action.recoveryKey) }
            }
            MatrixSessionSecurityAction.ConfirmRecoveryKeySaved -> confirmRecoveryKeySaved()
            MatrixSessionSecurityAction.StartVerification -> launchOperation(::startVerification)
            MatrixSessionSecurityAction.AcceptIncomingVerification -> {
                launchOperation(::acceptIncomingVerification)
            }
            MatrixSessionSecurityAction.ApproveEmojis -> launchOperation(::approveEmojis)
            MatrixSessionSecurityAction.DeclineEmojis -> launchOperation(::declineEmojis)
            MatrixSessionSecurityAction.CancelVerification -> launchOperation(::cancelVerification)
            MatrixSessionSecurityAction.Continue -> {
                _state.update { it.copy(gateComplete = true, errorMessage = null) }
            }
            MatrixSessionSecurityAction.Skip -> {
                _state.update {
                    it.copy(
                        step = MatrixSessionSecurityStep.SKIPPED,
                        gateComplete = true,
                        errorMessage = null
                    )
                }
            }
            MatrixSessionSecurityAction.Retry -> retryCurrentFlow()
            MatrixSessionSecurityAction.Manage -> launchOperation(::openManagementFlow)
            is MatrixSessionSecurityAction.ResetEncryption -> {
                launchOperation { resetEncryption(action.password) }
            }
        }
    }

    private suspend fun openManagementFlow() {
        val activeEncryption = encryption ?: error("Encryption service is not available")
        val keepGateOpen = _state.value.gateComplete
        _state.update {
            it.copy(
                mode = MatrixSessionSecurityMode.CHECKING,
                step = MatrixSessionSecurityStep.CHECKING,
                recoveryKey = null,
                incomingRequest = null,
                progressMessage = null,
                warningMessage = null,
                errorMessage = null
            )
        }
        refreshSynchronousSdkState()
        refreshRemoteSecurityFacts(activeEncryption)
        detectInitialMode(activeEncryption, preserveCompletedGate = keepGateOpen)
    }

    /**
     * Best-effort preflight for the sign-out warning. This never blocks logout:
     * callers must let the user confirm that they accept the reported risk.
     */
    suspend fun prepareForLogout(): MatrixLogoutWarning? {
        val activeEncryption = encryption ?: return null
        val currentState = _state.value
        currentState.immediateLogoutWarning?.let { return it }
        val backupReady = withTimeoutOrNull(LOGOUT_BACKUP_CHECK_TIMEOUT_MILLIS) {
            runCatching {
                activeEncryption.waitForBackupUploadSteadyState(progressListener = null)
            }.isSuccess
        } ?: false
        return if (backupReady) null else MatrixLogoutWarning.BACKUP_NOT_READY
    }

    private suspend fun initialize(activeClient: Client) {
        val activeEncryption = encryption ?: return
        try {
            withTimeoutOrNull(E2EE_INITIALIZATION_TIMEOUT_MILLIS) {
                activeEncryption.waitForE2eeInitializationTasks()
            }
            if (client !== activeClient) return
            refreshSynchronousSdkState()
            refreshRemoteSecurityFacts(activeEncryption)
            trySetUpVerificationController(activeClient, activeEncryption)
            detectInitialMode(activeEncryption)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to initialize session security", error)
            _state.update {
                it.copy(
                    mode = MatrixSessionSecurityMode.OTHER_DEVICE,
                    step = MatrixSessionSecurityStep.INITIAL,
                    errorMessage = error.displayMessage()
                )
            }
        }
    }

    private fun attachStateListeners() {
        val activeEncryption = encryption ?: return
        verificationStateHandle = activeEncryption.verificationStateListener(
            object : VerificationStateListener {
                override fun onUpdate(status: VerificationState) {
                    _state.update { it.copy(verificationStatus = status.toDomain()) }
                }
            }
        )
        recoveryStateHandle = activeEncryption.recoveryStateListener(
            object : RecoveryStateListener {
                override fun onUpdate(status: RecoveryState) {
                    _state.update { it.copy(recoveryStatus = status.toDomain()) }
                }
            }
        )
        backupStateHandle = activeEncryption.backupStateListener(
            object : BackupStateListener {
                override fun onUpdate(status: BackupState) {
                    _state.update { it.copy(backupStatus = status.toDomain()) }
                }
            }
        )
    }

    private fun refreshSynchronousSdkState() {
        val activeEncryption = encryption ?: return
        runCatching {
            _state.update {
                it.copy(
                    verificationStatus = activeEncryption.verificationState().toDomain(),
                    recoveryStatus = activeEncryption.recoveryState().toDomain(),
                    backupStatus = activeEncryption.backupState().toDomain()
                )
            }
        }.onFailure { Log.w(TAG, "Failed to read E2EE state", it) }
    }

    private suspend fun refreshRemoteSecurityFacts(activeEncryption: Encryption) {
        val backupExists = runCatching { activeEncryption.backupExistsOnServer() }.getOrNull()
        val hasOtherDevice = runCatching { !activeEncryption.isLastDevice() }.getOrNull()
        _state.update {
            it.copy(
                backupExistsOnServer = backupExists,
                hasOtherDeviceToVerify = hasOtherDevice
            )
        }
    }

    private suspend fun detectInitialMode(
        activeEncryption: Encryption,
        preserveCompletedGate: Boolean = false
    ) {
        val current = _state.value
        if (!current.hasPendingRecoverySetup &&
            (current.hasLocalSecrets || current.verificationStatus == MatrixVerificationStatus.VERIFIED)
        ) {
            _state.update {
                it.copy(
                    step = MatrixSessionSecurityStep.VERIFIED,
                    gateComplete = true,
                    errorMessage = null
                )
            }
            return
        }

        val isLastDevice = runCatching { activeEncryption.isLastDevice() }
            .onFailure { Log.w(TAG, "Failed to determine whether this is the last device", it) }
            .getOrDefault(false)
        _state.update {
            it.copy(
                mode = if (isLastDevice) {
                    MatrixSessionSecurityMode.FIRST_DEVICE
                } else {
                    MatrixSessionSecurityMode.OTHER_DEVICE
                },
                step = MatrixSessionSecurityStep.INITIAL,
                hasOtherDeviceToVerify = !isLastDevice,
                gateComplete = preserveCompletedGate,
                errorMessage = null
            )
        }
    }

    private suspend fun trySetUpVerificationController(
        activeClient: Client,
        activeEncryption: Encryption
    ) {
        if (controller != null) return
        runCatching {
            activeEncryption.userIdentity(activeClient.userId(), fallbackToServer = true)?.destroy()
            activeClient.getSessionVerificationController().also { newController ->
                newController.setDelegate(this)
                controller = newController
            }
        }.onFailure {
            Log.w(TAG, "Verification controller is not ready yet", it)
        }
    }

    private suspend fun ensureVerificationController(): SessionVerificationController {
        controller?.let { return it }
        val activeClient = client ?: error("Matrix client is not available")
        val activeEncryption = encryption ?: error("Encryption service is not available")
        trySetUpVerificationController(activeClient, activeEncryption)
        return controller ?: error("Verification controller is not ready")
    }

    private suspend fun startVerification() {
        val activeController = ensureVerificationController()
        isInitiator = true
        _state.update {
            it.copy(
                step = MatrixSessionSecurityStep.REQUESTING_VERIFICATION,
                errorMessage = null
            )
        }
        waitForIdentityReady()
        try {
            activeController.requestDeviceVerification()
        } catch (firstError: Throwable) {
            delay(RETRY_DELAY_MILLIS)
            try {
                activeController.requestDeviceVerification()
            } catch (_: Throwable) {
                throw firstError
            }
        }
        _state.update { it.copy(step = MatrixSessionSecurityStep.WAITING_FOR_ACCEPTANCE) }
    }

    private suspend fun acceptIncomingVerification() {
        _state.update {
            it.copy(
                step = MatrixSessionSecurityStep.ACCEPTING_REQUEST,
                errorMessage = null
            )
        }
        ensureVerificationController().acceptVerificationRequest()
    }

    private suspend fun approveEmojis() {
        ensureVerificationController().approveVerification()
    }

    private suspend fun declineEmojis() {
        ensureVerificationController().declineVerification()
        _state.update { it.copy(step = MatrixSessionSecurityStep.CANCELLED) }
    }

    private suspend fun cancelVerification() {
        runCatching { ensureVerificationController().cancelVerification() }
        _state.update { it.copy(step = MatrixSessionSecurityStep.CANCELLED) }
    }

    private suspend fun setUpRecovery() {
        val activeEncryption = encryption ?: error("Encryption service is not available")
        val userId = _state.value.userId ?: error("Matrix session is not available")
        _state.update {
            it.copy(
                step = MatrixSessionSecurityStep.GENERATING_RECOVERY_KEY,
                progressMessage = null,
                warningMessage = null,
                errorMessage = null
            )
        }

        waitForAccountDataReady()
        refreshSynchronousSdkState()
        val recoveryState = activeEncryption.recoveryState()
        if (recoveryState == RecoveryState.ENABLED || recoveryState == RecoveryState.INCOMPLETE) {
            requireExistingRecoveryKey("Recovery is already configured for this account.")
            return
        }
        if (recoveryState == RecoveryState.UNKNOWN) {
            error("Recovery state is still loading. Try again in a moment.")
        }

        val backupExists = activeEncryption.backupExistsOnServer()
        _state.update { it.copy(backupExistsOnServer = backupExists) }
        if (backupExists) {
            requireExistingRecoveryKey("An encrypted key backup already exists.")
            return
        }

        sessionStore.markRecoverySetupPending(userId)
        refreshLocalFlags(userId)
        val roomKeyUploadFailed = AtomicBoolean(false)
        val recoveryKey = try {
            activeEncryption.enableRecovery(
                waitForBackupsToUpload = false,
                passphrase = null,
                progressListener = object : EnableRecoveryProgressListener {
                override fun onUpdate(status: EnableRecoveryProgress) {
                    when (status) {
                        EnableRecoveryProgress.Starting,
                        EnableRecoveryProgress.CreatingBackup,
                        EnableRecoveryProgress.CreatingRecoveryKey -> {
                            _state.update {
                                it.copy(
                                    step = MatrixSessionSecurityStep.GENERATING_RECOVERY_KEY,
                                    progressMessage = status.progressLabel()
                                )
                            }
                        }
                        is EnableRecoveryProgress.BackingUp -> {
                            _state.update {
                                it.copy(
                                    step = MatrixSessionSecurityStep.FINISHING_RECOVERY_SETUP,
                                    progressMessage = "${status.backedUpCount}/${status.totalCount}"
                                )
                            }
                        }
                        EnableRecoveryProgress.RoomKeyUploadError -> {
                            roomKeyUploadFailed.set(true)
                        }
                        is EnableRecoveryProgress.Done -> Unit
                    }
                }
                }
            )
        } catch (_: RecoveryException.BackupExistsOnServer) {
            requireExistingRecoveryKey("An encrypted key backup already exists.")
            return
        }

        sessionStore.markLocalEncryptionSecretsPresent(userId)
        val backupUploadConfirmed = !roomKeyUploadFailed.get() && waitForBackupUploadSteadyState()
        refreshLocalFlags(userId)
        refreshSynchronousSdkState()
        _state.update {
            it.copy(
                step = MatrixSessionSecurityStep.SHOWING_RECOVERY_KEY,
                recoveryKey = recoveryKey,
                progressMessage = null,
                warningMessage = if (backupUploadConfirmed) {
                    null
                } else {
                    "Recovery key was created, but Zyna could not confirm that message keys reached encrypted backup. Save the key and avoid signing out until backup finishes."
                },
                errorMessage = null
            )
        }
    }

    private fun requireExistingRecoveryKey(message: String) {
        _state.update {
            it.copy(
                step = MatrixSessionSecurityStep.ENTERING_RECOVERY_KEY,
                progressMessage = null,
                errorMessage = message
            )
        }
    }

    private fun showRecoveryKeyEntry() {
        _state.update {
            it.copy(
                step = MatrixSessionSecurityStep.ENTERING_RECOVERY_KEY,
                recoveryKey = null,
                progressMessage = null,
                warningMessage = null,
                errorMessage = null
            )
        }
    }

    private suspend fun restoreWithRecoveryKey(recoveryKey: String) {
        val trimmedKey = recoveryKey.trim()
        require(trimmedKey.isNotEmpty()) { "Recovery key is empty" }
        val activeEncryption = encryption ?: error("Encryption service is not available")
        val userId = _state.value.userId ?: error("Matrix session is not available")
        _state.update {
            it.copy(
                step = MatrixSessionSecurityStep.RESTORING_FROM_RECOVERY_KEY,
                errorMessage = null,
                warningMessage = null
            )
        }
        waitForAccountDataReady()
        try {
            activeEncryption.recoverAndFixBackup(trimmedKey)
        } catch (firstError: Throwable) {
            delay(RETRY_DELAY_MILLIS)
            try {
                activeEncryption.recoverAndFixBackup(trimmedKey)
            } catch (_: Throwable) {
                throw firstError
            }
        }

        sessionStore.markLocalEncryptionSecretsPresent(userId)
        sessionStore.markRecoverySetupComplete(userId)
        refreshLocalFlags(userId)
        refreshSynchronousSdkState()
        _state.update {
            it.copy(
                step = MatrixSessionSecurityStep.VERIFIED,
                recoveryKey = null,
                errorMessage = null
            )
        }
    }

    /**
     * Destructive fallback for an account whose recovery key and verified
     * devices are both unavailable. The password is only passed to the SDK's
     * UIAA request and is never retained by this service.
     */
    private suspend fun resetEncryption(password: String) {
        val trimmedPassword = password.trim()
        require(trimmedPassword.isNotEmpty()) { "Account password is required" }
        val activeEncryption = encryption ?: error("Encryption service is not available")
        val userId = _state.value.userId ?: error("Matrix session is not available")

        _state.update {
            it.copy(
                step = MatrixSessionSecurityStep.GENERATING_RECOVERY_KEY,
                progressMessage = "Resetting encryption",
                warningMessage = null,
                errorMessage = null
            )
        }

        val resetHandle = activeEncryption.resetIdentity()
        if (resetHandle != null) {
            try {
                when (resetHandle.authType()) {
                    CrossSigningResetAuthType.Uiaa -> resetHandle.reset(
                        AuthData.Password(
                            AuthDataPasswordDetails(
                                identifier = userId,
                                password = trimmedPassword
                            )
                        )
                    )
                    is CrossSigningResetAuthType.OAuth -> {
                        resetHandle.cancel()
                        error("Resetting encryption requires browser approval, which Zyna does not support yet.")
                    }
                }
            } finally {
                resetHandle.destroy()
            }
        }

        sessionStore.clearLocalEncryptionFlags(userId)
        sessionStore.markRecoverySetupPending(userId)
        refreshLocalFlags(userId)
        refreshSynchronousSdkState()

        val recoveryState = activeEncryption.recoveryState()
        val backupExists = runCatching { activeEncryption.backupExistsOnServer() }
            .getOrDefault(false)
        val result = if (
            recoveryState == RecoveryState.ENABLED ||
            recoveryState == RecoveryState.INCOMPLETE ||
            backupExists
        ) {
            val newRecoveryKey = activeEncryption.resetRecoveryKey()
            reconnectBackupWithRecoveryKey(activeEncryption, newRecoveryKey)
            RecoverySetupResult(
                recoveryKey = newRecoveryKey,
                backupUploadConfirmed = waitForBackupUploadSteadyState()
            )
        } else {
            try {
                bootstrapRecovery(activeEncryption)
            } catch (_: RecoveryException.BackupExistsOnServer) {
                val newRecoveryKey = activeEncryption.resetRecoveryKey()
                reconnectBackupWithRecoveryKey(activeEncryption, newRecoveryKey)
                RecoverySetupResult(
                    recoveryKey = newRecoveryKey,
                    backupUploadConfirmed = waitForBackupUploadSteadyState()
                )
            }
        }

        sessionStore.markLocalEncryptionSecretsPresent(userId)
        refreshLocalFlags(userId)
        refreshSynchronousSdkState()
        _state.update {
            it.copy(
                step = MatrixSessionSecurityStep.SHOWING_RECOVERY_KEY,
                recoveryKey = result.recoveryKey,
                progressMessage = null,
                warningMessage = result.backupWarning(),
                errorMessage = null
            )
        }
    }

    private suspend fun bootstrapRecovery(
        activeEncryption: Encryption
    ): RecoverySetupResult {
        val roomKeyUploadFailed = AtomicBoolean(false)
        val recoveryKey = activeEncryption.enableRecovery(
            waitForBackupsToUpload = false,
            passphrase = null,
            progressListener = object : EnableRecoveryProgressListener {
                override fun onUpdate(status: EnableRecoveryProgress) {
                    if (status == EnableRecoveryProgress.RoomKeyUploadError) {
                        roomKeyUploadFailed.set(true)
                    }
                    _state.update {
                        it.copy(
                            step = if (status is EnableRecoveryProgress.BackingUp) {
                                MatrixSessionSecurityStep.FINISHING_RECOVERY_SETUP
                            } else {
                                MatrixSessionSecurityStep.GENERATING_RECOVERY_KEY
                            },
                            progressMessage = status.progressLabel()
                        )
                    }
                }
            }
        )
        return RecoverySetupResult(
            recoveryKey = recoveryKey,
            backupUploadConfirmed = !roomKeyUploadFailed.get() &&
                waitForBackupUploadSteadyState()
        )
    }

    private suspend fun reconnectBackupWithRecoveryKey(
        activeEncryption: Encryption,
        recoveryKey: String
    ) {
        try {
            activeEncryption.recoverAndFixBackup(recoveryKey)
        } catch (firstError: Throwable) {
            delay(RETRY_DELAY_MILLIS)
            try {
                activeEncryption.recoverAndFixBackup(recoveryKey)
            } catch (_: Throwable) {
                throw firstError
            }
        }
    }

    private fun confirmRecoveryKeySaved() {
        val userId = _state.value.userId ?: return
        sessionStore.markLocalEncryptionSecretsPresent(userId)
        sessionStore.markRecoverySetupComplete(userId)
        refreshLocalFlags(userId)
        _state.update {
            it.copy(
                step = MatrixSessionSecurityStep.VERIFIED,
                recoveryKey = null,
                warningMessage = null,
                errorMessage = null
            )
        }
    }

    private fun retryCurrentFlow() {
        when (_state.value.mode) {
            MatrixSessionSecurityMode.FIRST_DEVICE -> launchOperation(::setUpRecovery)
            MatrixSessionSecurityMode.OTHER_DEVICE -> launchOperation(::startVerification)
            MatrixSessionSecurityMode.RESPONDER -> {
                _state.update {
                    it.copy(step = MatrixSessionSecurityStep.INITIAL, errorMessage = null)
                }
            }
            MatrixSessionSecurityMode.CHECKING -> {
                val activeEncryption = encryption ?: return
                launchOperation { detectInitialMode(activeEncryption) }
            }
        }
    }

    private suspend fun waitForIdentityReady() {
        if (_state.value.verificationStatus != MatrixVerificationStatus.UNKNOWN) return
        withTimeoutOrNull(IDENTITY_WAIT_TIMEOUT_MILLIS) {
            state.first { it.verificationStatus != MatrixVerificationStatus.UNKNOWN }
        }
    }

    private suspend fun waitForAccountDataReady() {
        val current = _state.value.recoveryStatus
        if (current != MatrixRecoveryStatus.UNKNOWN) {
            return
        }
        withTimeoutOrNull(ACCOUNT_DATA_WAIT_TIMEOUT_MILLIS) {
            state.first { it.recoveryStatus != MatrixRecoveryStatus.UNKNOWN }
        }
    }

    private suspend fun waitForBackupUploadSteadyState(): Boolean {
        val activeEncryption = encryption ?: return false
        return withTimeoutOrNull(BACKUP_UPLOAD_TIMEOUT_MILLIS) {
            runCatching {
                activeEncryption.waitForBackupUploadSteadyState(
                    object : BackupSteadyStateListener {
                        override fun onUpdate(status: BackupUploadState) {
                            _state.update {
                                it.copy(
                                    step = MatrixSessionSecurityStep.FINISHING_RECOVERY_SETUP,
                                    progressMessage = status.toString()
                                )
                            }
                        }
                    }
                )
            }.isSuccess
        } ?: false
    }

    private fun refreshLocalFlags(userId: String) {
        _state.update {
            it.copy(
                hasLocalSecrets = sessionStore.hasLocalEncryptionSecrets(userId),
                hasPendingRecoverySetup = sessionStore.hasPendingRecoverySetup(userId),
                hasCompletedRecoverySetup = sessionStore.hasCompletedRecoverySetup(userId)
            )
        }
    }

    private fun launchOperation(operation: suspend () -> Unit) {
        val scope = sessionScope ?: return
        scope.launch {
            try {
                operation()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Session security operation failed", error)
                _state.update {
                    it.copy(
                        step = MatrixSessionSecurityStep.FAILED,
                        progressMessage = null,
                        errorMessage = error.displayMessage()
                    )
                }
            }
        }
    }

    override fun didReceiveVerificationRequest(details: SessionVerificationRequestDetails) {
        val request = MatrixIncomingVerificationRequest(
            senderId = details.senderProfile.userId,
            flowId = details.flowId,
            deviceId = details.deviceId,
            deviceDisplayName = details.deviceDisplayName
        )
        isInitiator = false
        _state.update {
            // A verified, active session stays usable while this dismissible
            // flow is shown. An onboarding session naturally keeps its false gate.
            it.copy(
                mode = MatrixSessionSecurityMode.RESPONDER,
                step = MatrixSessionSecurityStep.INITIAL,
                incomingRequest = request,
                errorMessage = null
            )
        }
        launchOperation {
            ensureVerificationController().acknowledgeVerificationRequest(
                senderId = request.senderId,
                flowId = request.flowId
            )
        }
    }

    override fun didAcceptVerificationRequest() {
        _state.update { it.copy(step = MatrixSessionSecurityStep.WAITING_FOR_ACCEPTANCE) }
        if (isInitiator) {
            launchOperation { ensureVerificationController().startSasVerification() }
        }
    }

    override fun didStartSasVerification() = Unit

    override fun didReceiveVerificationData(data: SessionVerificationData) {
        try {
            if (data is SessionVerificationData.Emojis) {
                val emojis = data.emojis.map { emoji ->
                    try {
                        MatrixVerificationEmoji(
                            symbol = emoji.symbol(),
                            description = emoji.description()
                        )
                    } finally {
                        emoji.destroy()
                    }
                }
                _state.update {
                    it.copy(
                        step = MatrixSessionSecurityStep.SHOWING_EMOJIS,
                        emojis = emojis,
                        errorMessage = null
                    )
                }
            }
        } finally {
            data.destroy()
        }
    }

    override fun didFail() {
        _state.update {
            it.copy(
                step = MatrixSessionSecurityStep.FAILED,
                errorMessage = "Device verification failed."
            )
        }
    }

    override fun didCancel() {
        _state.update { it.copy(step = MatrixSessionSecurityStep.CANCELLED) }
    }

    override fun didFinish() {
        _state.update {
            it.copy(
                verificationStatus = MatrixVerificationStatus.VERIFIED,
                step = MatrixSessionSecurityStep.WAITING_FOR_SECRETS,
                errorMessage = null
            )
        }
        val userId = _state.value.userId ?: return
        if (_state.value.mode == MatrixSessionSecurityMode.RESPONDER) {
            sessionStore.markLocalEncryptionSecretsPresent(userId)
            refreshLocalFlags(userId)
            _state.update { it.copy(step = MatrixSessionSecurityStep.VERIFIED) }
            return
        }
        sessionScope?.launch {
            val currentRecovery = _state.value.recoveryStatus
            val secretsReceived = if (currentRecovery == MatrixRecoveryStatus.DISABLED) {
                false
            } else {
                withTimeoutOrNull(SECRET_GOSSIP_TIMEOUT_MILLIS) {
                    state.drop(1).first {
                        it.recoveryStatus == MatrixRecoveryStatus.ENABLED
                    }
                } != null
            }
            if (secretsReceived) {
                sessionStore.markLocalEncryptionSecretsPresent(userId)
                sessionStore.markRecoverySetupComplete(userId)
                refreshLocalFlags(userId)
                _state.update { it.copy(step = MatrixSessionSecurityStep.VERIFIED) }
            } else {
                _state.update { it.copy(step = MatrixSessionSecurityStep.NEEDS_RECOVERY_KEY) }
            }
        }
    }

    private fun detachLocked(resetState: Boolean) {
        initializationJob?.cancel()
        initializationJob = null
        sessionScope?.cancel()
        sessionScope = null
        verificationStateHandle.cancelAndDestroy()
        verificationStateHandle = null
        recoveryStateHandle.cancelAndDestroy()
        recoveryStateHandle = null
        backupStateHandle.cancelAndDestroy()
        backupStateHandle = null
        controller?.setDelegate(null)
        controller?.destroy()
        controller = null
        encryption?.destroy()
        encryption = null
        client = null
        isInitiator = false
        if (resetState) {
            _state.value = MatrixSessionSecurityState()
        }
    }

    private fun TaskHandle?.cancelAndDestroy() {
        this ?: return
        runCatching { cancel() }
        runCatching { destroy() }
    }

    private fun Throwable.displayMessage(): String {
        return message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
    }

    private fun EnableRecoveryProgress.progressLabel(): String = when (this) {
        EnableRecoveryProgress.Starting -> "Starting recovery setup"
        EnableRecoveryProgress.CreatingBackup -> "Creating encrypted backup"
        EnableRecoveryProgress.CreatingRecoveryKey -> "Creating recovery key"
        is EnableRecoveryProgress.BackingUp -> "Backing up message keys"
        EnableRecoveryProgress.RoomKeyUploadError -> "Message key upload failed"
        is EnableRecoveryProgress.Done -> "Recovery setup ready"
    }

    private companion object {
        const val TAG = "SessionSecurity"
        const val RETRY_DELAY_MILLIS = 2_000L
        const val E2EE_INITIALIZATION_TIMEOUT_MILLIS = 20_000L
        const val IDENTITY_WAIT_TIMEOUT_MILLIS = 10_000L
        const val ACCOUNT_DATA_WAIT_TIMEOUT_MILLIS = 10_000L
        const val BACKUP_UPLOAD_TIMEOUT_MILLIS = 30_000L
        const val LOGOUT_BACKUP_CHECK_TIMEOUT_MILLIS = 2_000L
        const val SECRET_GOSSIP_TIMEOUT_MILLIS = 10_000L
    }
}

private data class RecoverySetupResult(
    val recoveryKey: String,
    val backupUploadConfirmed: Boolean
) {
    fun backupWarning(): String? = if (backupUploadConfirmed) {
        null
    } else {
        "Recovery key was created, but Zyna could not confirm that message keys reached encrypted backup. Save the key and avoid signing out until backup finishes."
    }
}

private fun VerificationState.toDomain(): MatrixVerificationStatus = when (this) {
    VerificationState.UNKNOWN -> MatrixVerificationStatus.UNKNOWN
    VerificationState.VERIFIED -> MatrixVerificationStatus.VERIFIED
    VerificationState.UNVERIFIED -> MatrixVerificationStatus.UNVERIFIED
}

private fun RecoveryState.toDomain(): MatrixRecoveryStatus = when (this) {
    RecoveryState.UNKNOWN -> MatrixRecoveryStatus.UNKNOWN
    RecoveryState.ENABLED -> MatrixRecoveryStatus.ENABLED
    RecoveryState.DISABLED -> MatrixRecoveryStatus.DISABLED
    RecoveryState.INCOMPLETE -> MatrixRecoveryStatus.INCOMPLETE
}

private fun BackupState.toDomain(): MatrixBackupStatus = when (this) {
    BackupState.UNKNOWN -> MatrixBackupStatus.UNKNOWN
    BackupState.CREATING -> MatrixBackupStatus.CREATING
    BackupState.ENABLING -> MatrixBackupStatus.ENABLING
    BackupState.RESUMING -> MatrixBackupStatus.RESUMING
    BackupState.ENABLED -> MatrixBackupStatus.ENABLED
    BackupState.DOWNLOADING -> MatrixBackupStatus.DOWNLOADING
    BackupState.DISABLING -> MatrixBackupStatus.DISABLING
}
