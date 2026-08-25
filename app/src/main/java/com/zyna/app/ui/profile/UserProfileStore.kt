package com.zyna.app.ui.profile

import androidx.annotation.MainThread
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixUserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UserProfileState(
    val userId: String = "",
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
) {
    val effectiveDisplayName: String
        get() = displayName?.takeIf { it.isNotBlank() } ?: userId
}

internal class UserProfileDriver(
    val loadProfile: suspend (userId: String) -> MatrixUserProfile
)

/**
 * Owns the route-scoped state and load lifecycle of another user's profile.
 *
 * Public methods and driver callbacks are main-thread confined. A generation
 * guard prevents cancelled loads from replacing a newer route or cleared state.
 */
internal class UserProfileStore(
    private val scope: CoroutineScope,
    private val driver: UserProfileDriver,
    private val onWarning: (String, Throwable) -> Unit = { _, _ -> }
) {
    private val _state = MutableStateFlow(UserProfileState())
    val state: StateFlow<UserProfileState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var loadGeneration = 0L

    @MainThread
    fun open(
        userId: String,
        seedDisplayName: String?,
        seedAvatarUrl: String?
    ) {
        if (userId.isBlank()) {
            clear()
            return
        }
        load(
            userId = userId,
            seedDisplayName = seedDisplayName,
            seedAvatarUrl = seedAvatarUrl
        )
    }

    @MainThread
    fun refresh() {
        val current = _state.value
        val userId = current.userId.takeIf { it.isNotBlank() } ?: return
        load(
            userId = userId,
            seedDisplayName = current.displayName,
            seedAvatarUrl = current.avatarUrl
        )
    }

    @MainThread
    fun clear() {
        loadGeneration += 1
        loadJob?.cancel()
        loadJob = null
        _state.value = UserProfileState()
    }

    private fun load(
        userId: String,
        seedDisplayName: String?,
        seedAvatarUrl: String?
    ) {
        loadGeneration += 1
        val generation = loadGeneration
        loadJob?.cancel()
        _state.value = UserProfileState(
            userId = userId,
            displayName = seedDisplayName?.takeIf { it.isNotBlank() },
            avatarUrl = seedAvatarUrl?.takeIf { it.isNotBlank() },
            isLoading = true,
            errorMessage = null
        )
        val nextJob = scope.launch {
            try {
                val profile = driver.loadProfile(userId)
                if (!isCurrent(userId, generation)) {
                    return@launch
                }
                _state.value = UserProfileState(
                    userId = profile.userId,
                    displayName = profile.displayName,
                    avatarUrl = profile.avatarUrl,
                    isLoading = false,
                    errorMessage = null
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrent(userId, generation)) {
                    return@launch
                }
                onWarning("Failed to load user profile", error)
                _state.value = _state.value.copy(
                    isLoading = false,
                    errorMessage = error.message ?: error.javaClass.simpleName
                )
            }
        }
        loadJob = nextJob
        nextJob.invokeOnCompletion {
            if (loadJob === nextJob) {
                loadJob = null
            }
        }
    }

    private fun isCurrent(userId: String, generation: Long): Boolean {
        return loadGeneration == generation && _state.value.userId == userId
    }
}

internal fun createUserProfileStore(
    scope: CoroutineScope,
    matrixClientService: MatrixClientService,
    onWarning: (String, Throwable) -> Unit
): UserProfileStore {
    return UserProfileStore(
        scope = scope,
        driver = UserProfileDriver(matrixClientService::loadUserProfile),
        onWarning = onWarning
    )
}
