package com.zyna.app.data.matrix

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Removes an owned alias mapping before clearing room state, while rolling the pair back together
 * if the state write fails.
 *
 * Keeping the mapping removal first means a retry can still discover the alias from room state.
 * Restoring the mapping before restoring state prevents a failed state write from leaving a
 * visible canonical alias that no longer resolves.
 */
internal suspend fun removeOwnedRoomAliasSafely(
    roomId: String,
    resolveAliasRoomId: suspend () -> String?,
    removeAliasMapping: suspend () -> Boolean,
    clearAliasState: suspend () -> Unit,
    ensureAliasMapping: suspend () -> Unit,
    restoreAliasState: suspend () -> Unit
) = withContext(NonCancellable) {
    val hadOwnedMapping = removeAliasMappingIfOwned(
        roomId = roomId,
        resolveAliasRoomId = resolveAliasRoomId,
        removeAliasMapping = removeAliasMapping
    )

    try {
        clearAliasState()
    } catch (stateError: Throwable) {
        val canRestoreState = if (hadOwnedMapping) {
            try {
                ensureAliasMapping()
                true
            } catch (mappingRestoreError: Throwable) {
                stateError.addSuppressed(mappingRestoreError)
                false
            }
        } else {
            true
        }
        if (canRestoreState) {
            try {
                restoreAliasState()
            } catch (stateRestoreError: Throwable) {
                stateError.addSuppressed(stateRestoreError)
            }
        }
        throw stateError
    }
}

/**
 * Completes an alias replacement even if the route-scoped caller is cancelled midway through it.
 *
 * The new mapping must exist before room state can expose it. The old mapping is removed only after
 * state points at the replacement, and only when it still belongs to this room.
 */
internal suspend fun replaceOwnedRoomAliasSafely(
    roomId: String,
    previousAlias: String?,
    desiredAlias: String,
    ensureDesiredAliasMapping: suspend () -> Unit,
    updateAliasState: suspend () -> Unit,
    resolvePreviousAliasRoomId: suspend () -> String?,
    removePreviousAliasMapping: suspend () -> Boolean
) = withContext(NonCancellable) {
    ensureDesiredAliasMapping()
    updateAliasState()

    if (
        previousAlias != null &&
        !matrixRoomAliasesEqual(previousAlias, desiredAlias)
    ) {
        removeAliasMappingIfOwned(
            roomId = roomId,
            resolveAliasRoomId = resolvePreviousAliasRoomId,
            removeAliasMapping = removePreviousAliasMapping
        )
    }
}

/** Uses Matrix alias semantics: the localpart is exact, while the server name is not. */
internal fun matrixRoomAliasesEqual(first: String?, second: String?): Boolean {
    if (first == null || second == null) return first == second
    val firstParts = first.matrixRoomAliasPartsOrNull() ?: return first == second
    val secondParts = second.matrixRoomAliasPartsOrNull() ?: return first == second
    return firstParts.first == secondParts.first &&
        firstParts.second.equals(secondParts.second, ignoreCase = true)
}

/** Rewrites only the case-insensitive server-name component of a local room alias. */
internal fun String.withCanonicalMatrixServerName(serverName: String): String {
    val parts = matrixRoomAliasPartsOrNull() ?: return this
    return "${parts.first}:$serverName"
}

private suspend fun removeAliasMappingIfOwned(
    roomId: String,
    resolveAliasRoomId: suspend () -> String?,
    removeAliasMapping: suspend () -> Boolean
): Boolean {
    if (resolveAliasRoomId() != roomId) return false
    try {
        val didRemove = removeAliasMapping()
        if (!didRemove && resolveAliasRoomId() == roomId) {
            error("Failed to remove room alias")
        }
    } catch (removalError: Throwable) {
        val stillOwned = try {
            resolveAliasRoomId() == roomId
        } catch (verificationError: Throwable) {
            removalError.addSuppressed(verificationError)
            throw removalError
        }
        if (stillOwned) throw removalError
    }
    return true
}

private fun String.matrixRoomAliasPartsOrNull(): Pair<String, String>? {
    if (!startsWith('#')) return null
    val separatorIndex = indexOf(':', startIndex = 1)
    if (separatorIndex <= 1 || separatorIndex == lastIndex) return null
    return substring(0, separatorIndex) to substring(separatorIndex + 1)
}
