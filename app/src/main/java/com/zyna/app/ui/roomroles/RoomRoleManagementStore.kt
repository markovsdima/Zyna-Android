package com.zyna.app.ui.roomroles

import androidx.annotation.MainThread
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixRoomMember
import com.zyna.app.data.matrix.MatrixRoomMemberMembership
import com.zyna.app.data.matrix.MatrixRoomMemberRole
import com.zyna.app.data.matrix.MatrixRoomRoleChangeContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RoomRoleManagementTarget(
    val userId: String,
    val roomId: String
)

data class RoomRoleCapabilities(
    val canEdit: Boolean,
    val ownPowerLevel: Long
)

enum class RoomAssignableRole(val powerLevel: Long) {
    MEMBER(0),
    MODERATOR(50),
    ADMINISTRATOR(100);

    companion object {
        fun fromMemberRole(role: MatrixRoomMemberRole): RoomAssignableRole? {
            return when (role) {
                MatrixRoomMemberRole.OWNER -> null
                MatrixRoomMemberRole.ADMIN -> ADMINISTRATOR
                MatrixRoomMemberRole.MODERATOR -> MODERATOR
                MatrixRoomMemberRole.MEMBER -> MEMBER
            }
        }
    }
}

data class PendingRoomRoleChange(
    val userId: String,
    val displayName: String,
    val currentRole: RoomAssignableRole,
    val requestedRole: RoomAssignableRole
)

enum class RoomRoleManagementError {
    SAVE,
    PERMISSION_CHANGED
}

data class RoomRoleManagementState(
    val target: RoomRoleManagementTarget? = null,
    val capabilities: RoomRoleCapabilities? = null,
    val confirmedPowerLevels: Map<String, Long> = emptyMap(),
    val pendingConfirmation: PendingRoomRoleChange? = null,
    val savingUserId: String? = null,
    val error: RoomRoleManagementError? = null
) {
    val isSaving: Boolean
        get() = savingUserId != null

    fun effectiveMember(member: MatrixRoomMember): MatrixRoomMember {
        val confirmedLevel = confirmedPowerLevels[member.userId] ?: return member
        return member.copy(
            role = roleForConfirmedPowerLevel(confirmedLevel),
            powerLevel = confirmedLevel
        )
    }

    fun canChange(member: MatrixRoomMember): Boolean {
        val activeTarget = target ?: return false
        val activeCapabilities = capabilities ?: return false
        val effectiveMember = effectiveMember(member)
        return !isSaving &&
            activeCapabilities.canEdit &&
            effectiveMember.membership == MatrixRoomMemberMembership.JOINED &&
            effectiveMember.userId != activeTarget.userId &&
            effectiveMember.role != MatrixRoomMemberRole.OWNER &&
            activeCapabilities.ownPowerLevel > effectiveMember.powerLevel
    }

    fun assignableRoles(member: MatrixRoomMember): List<RoomAssignableRole> {
        if (!canChange(member)) return emptyList()
        val ownPowerLevel = capabilities?.ownPowerLevel ?: return emptyList()
        return RoomAssignableRole.entries.filter { role -> ownPowerLevel >= role.powerLevel }
    }
}

internal class RoomRoleManagementDriver(
    val loadChangeContext: suspend (
        roomId: String,
        targetUserId: String
    ) -> MatrixRoomRoleChangeContext,
    val updatePowerLevel: suspend (
        roomId: String,
        targetUserId: String,
        powerLevel: Long
    ) -> Unit,
    val delayMillis: suspend (Long) -> Unit
)

/**
 * Owns the command side of the member-role route.
 *
 * The existing room-members store remains the shared read model (cache, server refresh, search).
 * This store only owns confirmation and mutation state. Every write uses a fresh, target-specific
 * authorization snapshot so a permission or role change that happened after the list was painted
 * cannot authorize a stale action. Confirmed writes are checked again after the SDK has had time
 * to observe the event; an authoritative divergence removes the optimistic overlay.
 */
internal class RoomRoleManagementStore(
    private val scope: CoroutineScope,
    private val driver: RoomRoleManagementDriver,
    private val onMembersRefreshRequested: () -> Unit,
    private val onWarning: (String, Throwable) -> Unit = { _, _ -> }
) {
    private val _state = MutableStateFlow(RoomRoleManagementState())
    val state: StateFlow<RoomRoleManagementState> = _state.asStateFlow()

    private var generation = 0L
    private var saveGeneration = 0L
    private var saveJob: Job? = null
    private val confirmationJobs = mutableMapOf<String, Job>()

    @MainThread
    fun activate(
        target: RoomRoleManagementTarget,
        capabilities: RoomRoleCapabilities?
    ) {
        val normalizedTarget = target.normalizedOrNull() ?: run {
            deactivate()
            return
        }
        val current = _state.value
        if (current.target == normalizedTarget) {
            if (current.capabilities != capabilities) {
                _state.value = current.copy(
                    capabilities = capabilities,
                    pendingConfirmation = current.pendingConfirmation?.takeIf { pending ->
                        capabilities?.canEdit == true &&
                            capabilities.ownPowerLevel >= pending.requestedRole.powerLevel
                    }
                )
            }
            return
        }

        generation += 1
        saveGeneration += 1
        saveJob?.cancel()
        saveJob = null
        cancelConfirmationJobs()
        _state.value = RoomRoleManagementState(
            target = normalizedTarget,
            capabilities = capabilities
        )
    }

    @MainThread
    fun requestRoleChange(member: MatrixRoomMember, requestedRole: RoomAssignableRole) {
        val current = _state.value
        val effectiveMember = current.effectiveMember(member)
        if (!current.canChange(effectiveMember)) return
        if (requestedRole !in current.assignableRoles(effectiveMember)) return
        val currentRole = RoomAssignableRole.fromMemberRole(effectiveMember.role) ?: return
        if (currentRole == requestedRole) return

        val change = PendingRoomRoleChange(
            userId = effectiveMember.userId,
            displayName = effectiveMember.displayNameOrUserId,
            currentRole = currentRole,
            requestedRole = requestedRole
        )
        if (
            currentRole == RoomAssignableRole.ADMINISTRATOR ||
            requestedRole == RoomAssignableRole.ADMINISTRATOR
        ) {
            _state.value = current.copy(pendingConfirmation = change, error = null)
        } else {
            save(change)
        }
    }

    @MainThread
    fun confirmPendingChange() {
        val change = _state.value.pendingConfirmation ?: return
        save(change)
    }

    @MainThread
    fun cancelPendingChange() {
        if (_state.value.pendingConfirmation == null || _state.value.isSaving) return
        _state.value = _state.value.copy(pendingConfirmation = null)
    }

    @MainThread
    fun reconcileMembers(members: List<MatrixRoomMember>) {
        val current = _state.value
        if (current.confirmedPowerLevels.isEmpty() || members.isEmpty()) return
        val membersById = members.associateBy(MatrixRoomMember::userId)
        val remaining = current.confirmedPowerLevels.filter { (userId, powerLevel) ->
            membersById[userId]?.powerLevel != powerLevel
        }
        if (remaining != current.confirmedPowerLevels) {
            current.confirmedPowerLevels.keys
                .minus(remaining.keys)
                .forEach(::cancelConfirmation)
            _state.value = current.copy(confirmedPowerLevels = remaining)
        }
    }

    @MainThread
    fun deactivate() {
        if (
            _state.value.target == null &&
            saveJob == null &&
            confirmationJobs.isEmpty()
        ) {
            return
        }
        generation += 1
        saveGeneration += 1
        saveJob?.cancel()
        saveJob = null
        cancelConfirmationJobs()
        _state.value = RoomRoleManagementState()
    }

    @MainThread
    fun clearSession() {
        deactivate()
    }

    private fun save(change: PendingRoomRoleChange) {
        val current = _state.value
        val target = current.target ?: return
        if (current.isSaving) return

        saveGeneration += 1
        val requestGeneration = generation
        val requestSaveGeneration = saveGeneration
        saveJob?.cancel()
        cancelConfirmation(change.userId)
        _state.value = current.copy(
            pendingConfirmation = null,
            savingUserId = change.userId,
            error = null
        )
        val nextJob = scope.launch {
            try {
                val fresh = driver.loadChangeContext(target.roomId, change.userId)
                if (!isCurrent(target, requestGeneration, requestSaveGeneration)) {
                    return@launch
                }
                if (
                    fresh.roomId != target.roomId ||
                    fresh.ownUserId != target.userId ||
                    fresh.targetUserId != change.userId ||
                    !fresh.canAssign(change.requestedRole.powerLevel)
                ) {
                    publishPermissionChanged()
                    return@launch
                }
                if (
                    RoomAssignableRole.fromMemberRole(fresh.targetRole) ==
                    change.requestedRole
                ) {
                    _state.value = _state.value.copy(savingUserId = null)
                    onMembersRefreshRequested()
                    return@launch
                }

                driver.updatePowerLevel(
                    target.roomId,
                    change.userId,
                    change.requestedRole.powerLevel
                )
                if (!isCurrent(target, requestGeneration, requestSaveGeneration)) {
                    return@launch
                }
                _state.value = _state.value.copy(
                    confirmedPowerLevels = _state.value.confirmedPowerLevels +
                        (change.userId to change.requestedRole.powerLevel),
                    savingUserId = null,
                    error = null
                )
                onMembersRefreshRequested()
                scheduleConfirmationRecheck(
                    target = target,
                    userId = change.userId,
                    expectedPowerLevel = change.requestedRole.powerLevel,
                    requestGeneration = requestGeneration
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrent(target, requestGeneration, requestSaveGeneration)) {
                    return@launch
                }
                onWarning("Failed to update room member role", error)
                val refreshed = try {
                    driver.loadChangeContext(target.roomId, change.userId)
                } catch (refreshError: CancellationException) {
                    throw refreshError
                } catch (refreshError: Throwable) {
                    onWarning("Failed to refresh room member role after save error", refreshError)
                    null
                }
                if (!isCurrent(target, requestGeneration, requestSaveGeneration)) {
                    return@launch
                }
                if (
                    refreshed != null &&
                    refreshed.targetPowerLevel == change.requestedRole.powerLevel
                ) {
                    _state.value = _state.value.copy(
                        confirmedPowerLevels = _state.value.confirmedPowerLevels +
                            (change.userId to change.requestedRole.powerLevel),
                        savingUserId = null,
                        error = null
                    )
                    onMembersRefreshRequested()
                    scheduleConfirmationRecheck(
                        target = target,
                        userId = change.userId,
                        expectedPowerLevel = change.requestedRole.powerLevel,
                        requestGeneration = requestGeneration
                    )
                } else {
                    _state.value = _state.value.copy(
                        savingUserId = null,
                        error = if (
                            refreshed != null &&
                            !refreshed.canAssign(change.requestedRole.powerLevel)
                        ) {
                            RoomRoleManagementError.PERMISSION_CHANGED
                        } else {
                            RoomRoleManagementError.SAVE
                        }
                    )
                    onMembersRefreshRequested()
                }
            }
        }
        saveJob = nextJob
        nextJob.invokeOnCompletion {
            if (saveJob === nextJob) saveJob = null
        }
    }

    private fun publishPermissionChanged() {
        _state.value = _state.value.copy(
            savingUserId = null,
            error = RoomRoleManagementError.PERMISSION_CHANGED
        )
        onMembersRefreshRequested()
    }

    private fun scheduleConfirmationRecheck(
        target: RoomRoleManagementTarget,
        userId: String,
        expectedPowerLevel: Long,
        requestGeneration: Long
    ) {
        cancelConfirmation(userId)
        val nextJob = scope.launch {
            try {
                driver.delayMillis(ROOM_ROLE_CONFIRMATION_RECHECK_DELAY_MS)
                val refreshed = driver.loadChangeContext(target.roomId, userId)
                if (
                    generation != requestGeneration ||
                    _state.value.target != target ||
                    _state.value.confirmedPowerLevels[userId] != expectedPowerLevel
                ) {
                    return@launch
                }
                if (refreshed.targetPowerLevel == expectedPowerLevel) {
                    onMembersRefreshRequested()
                    return@launch
                }

                _state.value = _state.value.copy(
                    confirmedPowerLevels = _state.value.confirmedPowerLevels - userId,
                    error = RoomRoleManagementError.PERMISSION_CHANGED
                )
                onMembersRefreshRequested()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (
                    generation != requestGeneration ||
                    _state.value.target != target ||
                    _state.value.confirmedPowerLevels[userId] != expectedPowerLevel
                ) {
                    return@launch
                }
                onWarning("Failed to confirm updated room member role", error)
                onMembersRefreshRequested()
            }
        }
        confirmationJobs[userId] = nextJob
        nextJob.invokeOnCompletion {
            if (confirmationJobs[userId] === nextJob) {
                confirmationJobs.remove(userId)
            }
        }
    }

    private fun cancelConfirmation(userId: String) {
        confirmationJobs.remove(userId)?.cancel()
    }

    private fun cancelConfirmationJobs() {
        confirmationJobs.values.forEach { job -> job.cancel() }
        confirmationJobs.clear()
    }

    private fun isCurrent(
        target: RoomRoleManagementTarget,
        requestGeneration: Long,
        requestSaveGeneration: Long
    ): Boolean {
        return generation == requestGeneration &&
            saveGeneration == requestSaveGeneration &&
            _state.value.target == target
    }

    private fun RoomRoleManagementTarget.normalizedOrNull(): RoomRoleManagementTarget? {
        val normalizedUserId = userId.trim().takeIf { it.isNotEmpty() } ?: return null
        val normalizedRoomId = roomId.trim().takeIf { it.isNotEmpty() } ?: return null
        return RoomRoleManagementTarget(normalizedUserId, normalizedRoomId)
    }
}

internal fun createRoomRoleManagementStore(
    scope: CoroutineScope,
    matrixClientService: MatrixClientService,
    onMembersRefreshRequested: () -> Unit,
    onWarning: (String, Throwable) -> Unit
): RoomRoleManagementStore {
    return RoomRoleManagementStore(
        scope = scope,
        driver = RoomRoleManagementDriver(
            loadChangeContext = matrixClientService::loadRoomRoleChangeContext,
            updatePowerLevel = matrixClientService::updateRoomMemberPowerLevel,
            delayMillis = { durationMillis -> delay(durationMillis) }
        ),
        onMembersRefreshRequested = onMembersRefreshRequested,
        onWarning = onWarning
    )
}

private const val ROOM_ROLE_CONFIRMATION_RECHECK_DELAY_MS = 1_000L

private fun roleForConfirmedPowerLevel(powerLevel: Long): MatrixRoomMemberRole {
    return when {
        powerLevel >= RoomAssignableRole.ADMINISTRATOR.powerLevel ->
            MatrixRoomMemberRole.ADMIN
        powerLevel >= RoomAssignableRole.MODERATOR.powerLevel ->
            MatrixRoomMemberRole.MODERATOR
        else -> MatrixRoomMemberRole.MEMBER
    }
}
