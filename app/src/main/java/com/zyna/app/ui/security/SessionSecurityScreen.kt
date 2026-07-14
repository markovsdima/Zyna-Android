package com.zyna.app.ui.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zyna.app.R
import com.zyna.app.data.security.MatrixSessionSecurityAction
import com.zyna.app.data.security.MatrixSessionSecurityMode
import com.zyna.app.data.security.MatrixSessionSecurityState
import com.zyna.app.data.security.MatrixSessionSecurityStep

@Composable
fun SessionSecurityScreen(
    userId: String,
    state: MatrixSessionSecurityState,
    onAction: (MatrixSessionSecurityAction) -> Unit
) {
    var recoveryKeyInput by remember(userId) { mutableStateOf("") }
    var showSavedConfirmation by remember { mutableStateOf(false) }
    var showSkipConfirmation by remember { mutableStateOf(false) }
    var showResetWarning by remember { mutableStateOf(false) }
    var showResetFinalConfirmation by remember { mutableStateOf(false) }
    var resetPassword by remember(userId) { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val clipboardManager = LocalClipboardManager.current

    if (showSavedConfirmation) {
        AlertDialog(
            onDismissRequest = { showSavedConfirmation = false },
            title = { Text(stringResource(R.string.security_saved_key_dialog_title)) },
            text = { Text(stringResource(R.string.security_saved_key_dialog_message)) },
            dismissButton = {
                TextButton(onClick = { showSavedConfirmation = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSavedConfirmation = false
                        onAction(MatrixSessionSecurityAction.ConfirmRecoveryKeySaved)
                    }
                ) {
                    Text(stringResource(R.string.security_saved_key_confirm))
                }
            }
        )
    }

    if (showSkipConfirmation) {
        AlertDialog(
            onDismissRequest = { showSkipConfirmation = false },
            title = { Text(stringResource(R.string.security_skip_dialog_title)) },
            text = { Text(stringResource(R.string.security_skip_dialog_message)) },
            dismissButton = {
                TextButton(onClick = { showSkipConfirmation = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSkipConfirmation = false
                        onAction(MatrixSessionSecurityAction.Skip)
                    }
                ) {
                    Text(stringResource(R.string.security_skip_confirm))
                }
            }
        )
    }

    if (showResetWarning) {
        AlertDialog(
            onDismissRequest = { showResetWarning = false },
            title = { Text(stringResource(R.string.security_reset_warning_title)) },
            text = { Text(stringResource(R.string.security_reset_warning_message)) },
            dismissButton = {
                TextButton(onClick = { showResetWarning = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetWarning = false
                        resetPassword = ""
                        showResetFinalConfirmation = true
                    }
                ) {
                    Text(stringResource(R.string.security_reset))
                }
            }
        )
    }

    if (showResetFinalConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetFinalConfirmation = false },
            title = { Text(stringResource(R.string.security_reset_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.security_reset_confirm_message))
                    OutlinedTextField(
                        value = resetPassword,
                        onValueChange = { resetPassword = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.security_account_password)) },
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetFinalConfirmation = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = resetPassword.isNotBlank(),
                    onClick = {
                        val password = resetPassword
                        resetPassword = ""
                        showResetFinalConfirmation = false
                        onAction(MatrixSessionSecurityAction.ResetEncryption(password))
                    }
                ) {
                    Text(stringResource(R.string.security_delete_backup_and_reset))
                }
            }
        )
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                modifier = Modifier.widthIn(max = 520.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SecurityContent(
                    userId = userId,
                    state = state,
                    recoveryKeyInput = recoveryKeyInput,
                    onRecoveryKeyInputChanged = { recoveryKeyInput = it }
                )

                state.warningMessage?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
                state.errorMessage?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                SecurityActions(
                    state = state,
                    canRestore = recoveryKeyInput.isNotBlank(),
                    onSetUpRecovery = {
                        focusManager.clearFocus()
                        onAction(MatrixSessionSecurityAction.SetUpRecovery)
                    },
                    onUseRecoveryKey = {
                        recoveryKeyInput = ""
                        onAction(MatrixSessionSecurityAction.UseRecoveryKey)
                    },
                    onRestore = {
                        focusManager.clearFocus()
                        onAction(MatrixSessionSecurityAction.RestoreWithRecoveryKey(recoveryKeyInput))
                    },
                    onCopyRecoveryKey = {
                        state.recoveryKey?.let { clipboardManager.setText(AnnotatedString(it)) }
                    },
                    onConfirmRecoveryKeySaved = { showSavedConfirmation = true },
                    onStartVerification = {
                        onAction(MatrixSessionSecurityAction.StartVerification)
                    },
                    onAcceptVerification = {
                        onAction(MatrixSessionSecurityAction.AcceptIncomingVerification)
                    },
                    onApproveEmojis = { onAction(MatrixSessionSecurityAction.ApproveEmojis) },
                    onDeclineEmojis = { onAction(MatrixSessionSecurityAction.DeclineEmojis) },
                    onCancelVerification = {
                        onAction(MatrixSessionSecurityAction.CancelVerification)
                    },
                    onContinue = { onAction(MatrixSessionSecurityAction.Continue) },
                    onRetry = { onAction(MatrixSessionSecurityAction.Retry) },
                    onResetEncryption = { showResetWarning = true },
                    onSkip = { showSkipConfirmation = true }
                )
            }
        }
    }
}

@Composable
private fun SecurityContent(
    userId: String,
    state: MatrixSessionSecurityState,
    recoveryKeyInput: String,
    onRecoveryKeyInputChanged: (String) -> Unit
) {
    when (state.step) {
        MatrixSessionSecurityStep.CHECKING -> ProgressContent(
            title = stringResource(R.string.security_checking_account)
        )
        MatrixSessionSecurityStep.INITIAL -> InitialContent(userId, state)
        MatrixSessionSecurityStep.REQUESTING_VERIFICATION,
        MatrixSessionSecurityStep.WAITING_FOR_ACCEPTANCE,
        MatrixSessionSecurityStep.ACCEPTING_REQUEST -> ProgressContent(
            title = stringResource(R.string.security_waiting_for_approval),
            body = stringResource(R.string.security_waiting_for_approval_body)
        )
        MatrixSessionSecurityStep.SHOWING_EMOJIS -> EmojiContent(state)
        MatrixSessionSecurityStep.GENERATING_RECOVERY_KEY -> ProgressContent(
            title = stringResource(R.string.security_generating_key),
            body = state.progressMessage
        )
        MatrixSessionSecurityStep.FINISHING_RECOVERY_SETUP -> ProgressContent(
            title = stringResource(R.string.security_saving_message_keys),
            body = state.progressMessage
        )
        MatrixSessionSecurityStep.SHOWING_RECOVERY_KEY -> RecoveryKeyContent(state)
        MatrixSessionSecurityStep.ENTERING_RECOVERY_KEY,
        MatrixSessionSecurityStep.NEEDS_RECOVERY_KEY -> RecoveryKeyInputContent(
            needsKeyAfterVerification = state.step == MatrixSessionSecurityStep.NEEDS_RECOVERY_KEY,
            recoveryKeyInput = recoveryKeyInput,
            onRecoveryKeyInputChanged = onRecoveryKeyInputChanged
        )
        MatrixSessionSecurityStep.RESTORING_FROM_RECOVERY_KEY -> ProgressContent(
            title = stringResource(R.string.security_restoring_key)
        )
        MatrixSessionSecurityStep.WAITING_FOR_SECRETS -> ProgressContent(
            title = stringResource(R.string.security_device_verified),
            body = stringResource(R.string.security_syncing_keys)
        )
        MatrixSessionSecurityStep.VERIFIED -> StatusContent(
            symbol = "✓",
            title = stringResource(R.string.security_all_set),
            body = if (state.mode == MatrixSessionSecurityMode.FIRST_DEVICE) {
                stringResource(R.string.security_all_set_recovery)
            } else {
                stringResource(R.string.security_all_set_verification)
            }
        )
        MatrixSessionSecurityStep.CANCELLED -> StatusContent(
            symbol = "×",
            title = stringResource(R.string.security_verification_cancelled),
            body = null
        )
        MatrixSessionSecurityStep.FAILED -> StatusContent(
            symbol = "!",
            title = stringResource(R.string.security_setup_failed),
            body = null
        )
        MatrixSessionSecurityStep.SKIPPED -> Unit
    }
}

@Composable
private fun InitialContent(userId: String, state: MatrixSessionSecurityState) {
    when (state.mode) {
        MatrixSessionSecurityMode.FIRST_DEVICE -> StatusContent(
            symbol = "🔑",
            title = stringResource(R.string.security_setup_recovery),
            body = stringResource(R.string.security_setup_recovery_body)
        )
        MatrixSessionSecurityMode.OTHER_DEVICE -> StatusContent(
            symbol = "🔐",
            title = stringResource(R.string.security_verify_device),
            body = stringResource(R.string.security_verify_device_body)
        )
        MatrixSessionSecurityMode.RESPONDER -> {
            StatusContent(
                symbol = "🔐",
                title = stringResource(R.string.security_verification_request),
                body = stringResource(R.string.security_verification_request_body)
            )
            state.incomingRequest?.let { request ->
                Text(
                    text = request.deviceDisplayName ?: request.deviceId,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = request.deviceId,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        MatrixSessionSecurityMode.CHECKING -> ProgressContent(
            title = stringResource(R.string.security_checking_account)
        )
    }
    Text(
        text = userId,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ProgressContent(title: String, body: String? = null) {
    CircularProgressIndicator(modifier = Modifier.size(40.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center
    )
    body?.takeIf { it.isNotBlank() }?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun StatusContent(symbol: String, title: String, body: String?) {
    Text(text = symbol, style = MaterialTheme.typography.displayMedium)
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center
    )
    body?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun EmojiContent(state: MatrixSessionSecurityState) {
    StatusContent(
        symbol = "🔐",
        title = stringResource(R.string.security_compare_emojis),
        body = stringResource(R.string.security_compare_emojis_body)
    )
    state.emojis.chunked(4).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            row.forEach { emoji ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = emoji.symbol, style = MaterialTheme.typography.headlineLarge)
                    Text(
                        text = emoji.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            repeat(4 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun RecoveryKeyContent(state: MatrixSessionSecurityState) {
    StatusContent(
        symbol = "🔑",
        title = stringResource(R.string.security_your_recovery_key),
        body = stringResource(R.string.security_your_recovery_key_body)
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = state.recoveryKey.orEmpty(),
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun RecoveryKeyInputContent(
    needsKeyAfterVerification: Boolean,
    recoveryKeyInput: String,
    onRecoveryKeyInputChanged: (String) -> Unit
) {
    val focusManager = LocalFocusManager.current
    StatusContent(
        symbol = "🔑",
        title = if (needsKeyAfterVerification) {
            stringResource(R.string.security_device_verified)
        } else {
            stringResource(R.string.security_enter_recovery_key)
        },
        body = if (needsKeyAfterVerification) {
            stringResource(R.string.security_keys_not_transferred)
        } else {
            stringResource(R.string.security_enter_recovery_key_body)
        }
    )
    OutlinedTextField(
        value = recoveryKeyInput,
        onValueChange = onRecoveryKeyInputChanged,
        modifier = Modifier.fillMaxWidth(),
        minLines = 3,
        maxLines = 5,
        label = { Text(stringResource(R.string.security_recovery_key_label)) },
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
    )
}

@Composable
private fun SecurityActions(
    state: MatrixSessionSecurityState,
    canRestore: Boolean,
    onSetUpRecovery: () -> Unit,
    onUseRecoveryKey: () -> Unit,
    onRestore: () -> Unit,
    onCopyRecoveryKey: () -> Unit,
    onConfirmRecoveryKeySaved: () -> Unit,
    onStartVerification: () -> Unit,
    onAcceptVerification: () -> Unit,
    onApproveEmojis: () -> Unit,
    onDeclineEmojis: () -> Unit,
    onCancelVerification: () -> Unit,
    onContinue: () -> Unit,
    onRetry: () -> Unit,
    onResetEncryption: () -> Unit,
    onSkip: () -> Unit
) {
    when (state.step) {
        MatrixSessionSecurityStep.INITIAL -> when (state.mode) {
            MatrixSessionSecurityMode.FIRST_DEVICE -> {
                PrimaryAction(R.string.security_generate_recovery_key, onSetUpRecovery)
                SecondaryAction(R.string.security_have_recovery_key, onUseRecoveryKey)
                SecondaryAction(R.string.security_reset_recovery_key, onResetEncryption)
                SecondaryAction(R.string.security_skip_for_now, onSkip)
            }
            MatrixSessionSecurityMode.OTHER_DEVICE -> {
                PrimaryAction(R.string.security_start_verification, onStartVerification)
                SecondaryAction(R.string.security_have_recovery_key, onUseRecoveryKey)
                SecondaryAction(R.string.security_reset_recovery_key, onResetEncryption)
                SecondaryAction(R.string.security_skip_for_now, onSkip)
            }
            MatrixSessionSecurityMode.RESPONDER -> {
                PrimaryAction(R.string.security_accept, onAcceptVerification)
                SecondaryAction(R.string.security_ignore, onSkip)
            }
            MatrixSessionSecurityMode.CHECKING -> Unit
        }
        MatrixSessionSecurityStep.REQUESTING_VERIFICATION,
        MatrixSessionSecurityStep.WAITING_FOR_ACCEPTANCE -> {
            SecondaryAction(R.string.common_cancel, onCancelVerification)
        }
        MatrixSessionSecurityStep.SHOWING_EMOJIS -> {
            PrimaryAction(R.string.security_emojis_match, onApproveEmojis)
            SecondaryAction(R.string.security_emojis_do_not_match, onDeclineEmojis)
        }
        MatrixSessionSecurityStep.SHOWING_RECOVERY_KEY -> {
            OutlinedButton(onClick = onCopyRecoveryKey, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.security_copy_key))
            }
            PrimaryAction(R.string.security_done, onConfirmRecoveryKeySaved)
        }
        MatrixSessionSecurityStep.ENTERING_RECOVERY_KEY,
        MatrixSessionSecurityStep.NEEDS_RECOVERY_KEY -> {
            Button(
                onClick = onRestore,
                modifier = Modifier.fillMaxWidth(),
                enabled = canRestore
            ) {
                Text(stringResource(R.string.security_restore))
            }
            SecondaryAction(R.string.security_reset_recovery_key, onResetEncryption)
            SecondaryAction(R.string.security_skip_for_now, onSkip)
        }
        MatrixSessionSecurityStep.VERIFIED -> {
            PrimaryAction(R.string.security_continue, onContinue)
        }
        MatrixSessionSecurityStep.CANCELLED,
        MatrixSessionSecurityStep.FAILED -> {
            PrimaryAction(R.string.security_try_again, onRetry)
            SecondaryAction(R.string.security_have_recovery_key, onUseRecoveryKey)
            SecondaryAction(R.string.security_reset_recovery_key, onResetEncryption)
            SecondaryAction(R.string.security_skip_for_now, onSkip)
        }
        MatrixSessionSecurityStep.CHECKING,
        MatrixSessionSecurityStep.ACCEPTING_REQUEST,
        MatrixSessionSecurityStep.GENERATING_RECOVERY_KEY,
        MatrixSessionSecurityStep.FINISHING_RECOVERY_SETUP,
        MatrixSessionSecurityStep.RESTORING_FROM_RECOVERY_KEY,
        MatrixSessionSecurityStep.WAITING_FOR_SECRETS,
        MatrixSessionSecurityStep.SKIPPED -> Unit
    }
}

@Composable
private fun PrimaryAction(labelRes: Int, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(labelRes))
    }
}

@Composable
private fun SecondaryAction(labelRes: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(labelRes))
    }
}
