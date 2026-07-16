package com.zyna.app.ui.contacts

import androidx.annotation.MainThread
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.matrix.MatrixContact
import com.zyna.app.data.matrix.MatrixRoomSummary
import com.zyna.app.data.matrix.MatrixUserProfile
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ContactsState(
    val searchQuery: String = "",
    val searchResults: List<MatrixUserProfile> = emptyList(),
    val isSearching: Boolean = false,
    val searchErrorMessage: String? = null
) {
    fun contactsFor(rooms: List<MatrixRoomSummary>): List<MatrixContact> {
        val query = searchQuery.trim()
        val directContacts = rooms
            .asSequence()
            .mapNotNull { room -> room.toContactOrNull() }
            .sortedWith(contactComparator)
            .toList()
        if (query.isBlank()) {
            return directContacts
        }

        val directMatches = directContacts.filter { it.matchesQuery(query) }
        val directByUserId = directContacts.associateBy(MatrixContact::userId)
        val searchedContacts = searchResults.map { profile ->
            val existing = directByUserId[profile.userId]
            MatrixContact(
                userId = profile.userId,
                displayName = profile.effectiveDisplayName,
                avatarUrl = profile.avatarUrl ?: existing?.avatarUrl,
                roomId = existing?.roomId
            )
        }

        return (directMatches + searchedContacts)
            .fold(LinkedHashMap<String, MatrixContact>()) { contactsByUserId, contact ->
                val existing = contactsByUserId[contact.userId]
                contactsByUserId[contact.userId] = when {
                    existing == null -> contact
                    existing.roomId == null && contact.roomId != null -> contact
                    existing.avatarUrl.isNullOrBlank() && !contact.avatarUrl.isNullOrBlank() -> {
                        existing.copy(avatarUrl = contact.avatarUrl)
                    }
                    else -> existing
                }
                contactsByUserId
            }
            .values
            .sortedWith(contactComparator)
    }

    private fun MatrixRoomSummary.toContactOrNull(): MatrixContact? {
        val userId = directUserId?.takeIf { it.isNotBlank() } ?: return null
        return MatrixContact(
            userId = userId,
            displayName = displayName.takeIf { it.isNotBlank() } ?: userId,
            avatarUrl = avatarUrl,
            roomId = id
        )
    }

    private fun MatrixContact.matchesQuery(query: String): Boolean {
        return displayName.contains(query, ignoreCase = true) ||
            userId.contains(query, ignoreCase = true)
    }

    private companion object {
        val contactComparator = compareBy<MatrixContact> {
            it.displayName.lowercase(Locale.ROOT)
        }.thenBy(MatrixContact::userId)
    }
}

internal class ContactsDriver(
    val searchUsers: suspend (searchTerm: String, limit: Int) -> List<MatrixUserProfile>,
    val delayMillis: suspend (Long) -> Unit = { delay(it) }
)

/**
 * Owns the contacts search query and its debounced server-search lifecycle.
 *
 * Direct contacts remain a projection of the room list and are never mirrored
 * into this store. Public methods and driver callbacks are main-thread confined.
 */
internal class ContactsStore(
    private val scope: CoroutineScope,
    private val driver: ContactsDriver,
    private val searchDebounceMillis: Long = CONTACTS_SEARCH_DEBOUNCE_MS,
    private val onWarning: (String, Throwable) -> Unit = { _, _ -> }
) {
    private val _state = MutableStateFlow(ContactsState())
    val state: StateFlow<ContactsState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var searchGeneration = 0L

    @MainThread
    fun setSearchQuery(query: String) {
        if (_state.value.searchQuery == query) {
            return
        }

        searchGeneration += 1
        val generation = searchGeneration
        searchJob?.cancel()
        searchJob = null
        val trimmedQuery = query.trim()
        val shouldSearchServer = trimmedQuery.length >= CONTACTS_SEARCH_MIN_LENGTH
        _state.value = ContactsState(
            searchQuery = query,
            isSearching = shouldSearchServer
        )

        if (!shouldSearchServer) {
            return
        }

        val nextJob = scope.launch {
            try {
                driver.delayMillis(searchDebounceMillis)
                val results = driver.searchUsers(trimmedQuery, CONTACTS_SEARCH_LIMIT)
                if (!isCurrent(trimmedQuery, generation)) {
                    return@launch
                }
                _state.value = _state.value.copy(
                    searchResults = results,
                    isSearching = false,
                    searchErrorMessage = null
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrent(trimmedQuery, generation)) {
                    return@launch
                }
                onWarning("Failed to search contacts", error)
                _state.value = _state.value.copy(
                    searchResults = emptyList(),
                    isSearching = false,
                    searchErrorMessage = error.message ?: error.javaClass.simpleName
                )
            }
        }
        searchJob = nextJob
        nextJob.invokeOnCompletion {
            if (searchJob === nextJob) {
                searchJob = null
            }
        }
    }

    @MainThread
    fun clear() {
        searchGeneration += 1
        searchJob?.cancel()
        searchJob = null
        _state.value = ContactsState()
    }

    private fun isCurrent(trimmedQuery: String, generation: Long): Boolean {
        return searchGeneration == generation && _state.value.searchQuery.trim() == trimmedQuery
    }
}

internal fun createContactsStore(
    scope: CoroutineScope,
    matrixClientService: MatrixClientService,
    onWarning: (String, Throwable) -> Unit
): ContactsStore {
    return ContactsStore(
        scope = scope,
        driver = ContactsDriver(matrixClientService::searchUsers),
        onWarning = onWarning
    )
}

private const val CONTACTS_SEARCH_MIN_LENGTH = 2
private const val CONTACTS_SEARCH_DEBOUNCE_MS = 250L
private const val CONTACTS_SEARCH_LIMIT = 30
