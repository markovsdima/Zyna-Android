package com.zyna.app.ui.profile

import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.MainThread
import com.zyna.app.data.profile.ProfileAvatarCropSpec
import com.zyna.app.data.profile.ProfileAvatarDraft
import com.zyna.app.ui.avatar.AvatarCropTarget
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class ProfileAvatarPickRequest(
    val target: AvatarCropTarget,
    val generation: Long
)

internal data class ProfileAvatarCropSession(
    val sourceFile: File,
    val previewBitmap: Bitmap,
    val request: ProfileAvatarPickRequest
)

internal enum class ProfileAvatarCropError {
    EXPORT
}

internal data class ProfileAvatarCropState(
    val session: ProfileAvatarCropSession? = null,
    val isProcessing: Boolean = false,
    val error: ProfileAvatarCropError? = null
)

/**
 * Owns the activity-scoped workflow between the system photo picker and the profile draft.
 * The Activity remains responsible only for launching the picker and presenting the overlay.
 */
internal class ProfileAvatarCropCoordinator(
    private val scope: CoroutineScope,
    private val driver: ProfileAvatarCropDriver,
    private val canDeliver: (AvatarCropTarget) -> Boolean,
    private val onDraftReady: (ProfileAvatarDraft, AvatarCropTarget) -> Unit,
    private val onPreparationError: (AvatarCropTarget) -> Unit,
    private val onSessionWillClose: () -> Unit
) {
    private val _state = MutableStateFlow(ProfileAvatarCropState())
    val state: StateFlow<ProfileAvatarCropState> = _state.asStateFlow()

    private var generation = 0L
    private var operationJob: Job? = null
    private var sourceCleanupJob: Job? = null

    @MainThread
    fun beginPick(target: AvatarCropTarget): ProfileAvatarPickRequest {
        clearSession(cancelOperation = true)
        generation += 1
        return ProfileAvatarPickRequest(
            target = target,
            generation = generation
        )
    }

    @MainThread
    fun handlePickerResult(uri: Uri?, request: ProfileAvatarPickRequest?) {
        if (
            uri == null ||
            request == null ||
            request.generation != generation ||
            !canDeliver(request.target)
        ) {
            return
        }
        operationJob?.cancel()
        operationJob = scope.launch {
            var sourceFile: File? = null
            var previewBitmap: Bitmap? = null
            try {
                sourceCleanupJob?.join()
                val prepared = driver.prepareSource(uri)
                sourceFile = prepared.sourceFile
                previewBitmap = prepared.previewBitmap
                if (!owns(request) || !canDeliver(request.target)) {
                    return@launch
                }
                _state.value = ProfileAvatarCropState(
                    session = ProfileAvatarCropSession(
                        sourceFile = prepared.sourceFile,
                        previewBitmap = prepared.previewBitmap,
                        request = request
                    )
                )
                sourceFile = null
                previewBitmap = null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (owns(request) && canDeliver(request.target)) {
                    onPreparationError(request.target)
                }
            } finally {
                previewBitmap?.let(driver.recyclePreview)
                sourceFile?.let(driver.deleteSource)
            }
        }
    }

    @MainThread
    fun confirm(crop: ProfileAvatarCropSpec) {
        val session = _state.value.session ?: return
        if (_state.value.isProcessing) {
            return
        }
        _state.value = _state.value.copy(isProcessing = true, error = null)
        operationJob?.cancel()
        operationJob = scope.launch {
            var unclaimedDraft: ProfileAvatarDraft? = null
            try {
                val draft = driver.exportDraft(session.sourceFile, crop)
                unclaimedDraft = draft
                if (!owns(session)) {
                    return@launch
                }
                if (!canDeliver(session.request.target)) {
                    clearSession(cancelOperation = false)
                    return@launch
                }
                onDraftReady(draft, session.request.target)
                unclaimedDraft = null
                clearSession(cancelOperation = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (owns(session)) {
                    _state.value = _state.value.copy(
                        isProcessing = false,
                        error = ProfileAvatarCropError.EXPORT
                    )
                }
            } finally {
                unclaimedDraft?.let(driver.deleteDraft)
            }
        }
    }

    @MainThread
    fun dismiss() {
        clearSession(cancelOperation = true)
        generation += 1
    }

    @MainThread
    fun cleanupOrphanSources() {
        sourceCleanupJob?.cancel()
        sourceCleanupJob = scope.launch {
            driver.cleanupOrphanSources()
        }
    }

    @MainThread
    fun close() {
        clearSession(cancelOperation = true)
        sourceCleanupJob?.cancel()
        sourceCleanupJob = null
        generation += 1
    }

    private fun owns(request: ProfileAvatarPickRequest): Boolean {
        return request.generation == generation
    }

    private fun owns(session: ProfileAvatarCropSession): Boolean {
        return _state.value.session === session && owns(session.request)
    }

    private fun clearSession(cancelOperation: Boolean) {
        if (cancelOperation) {
            operationJob?.cancel()
        }
        operationJob = null
        val session = _state.value.session
        if (session != null) {
            onSessionWillClose()
        }
        _state.value = ProfileAvatarCropState()
        session?.previewBitmap?.let(driver.recyclePreview)
        session?.sourceFile?.let(driver.deleteSource)
    }
}
