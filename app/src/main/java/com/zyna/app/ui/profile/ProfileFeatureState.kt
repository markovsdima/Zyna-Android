package com.zyna.app.ui.profile

/**
 * Immutable render input for the independently owned profile stores.
 *
 * Grouping these values does not introduce another source of truth.
 */
data class ProfileFeatureState(
    val own: OwnProfileState,
    val user: UserProfileState
)
