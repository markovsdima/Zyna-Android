package com.zyna.app.ui.contacts

/**
 * Immutable render input for independently owned contact feature states.
 */
data class ContactsFeatureState(
    val directory: ContactsState,
    val directRoomAction: DirectRoomActionState
)
