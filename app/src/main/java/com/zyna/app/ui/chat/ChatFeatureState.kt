package com.zyna.app.ui.chat

/**
 * Immutable UI projection of the independently owned chat feature states.
 *
 * This type groups render inputs only; the underlying stores remain the
 * sources of truth for composer, timeline, and call-info state.
 */
data class ChatFeatureState(
    val composer: ChatComposerState,
    val timeline: ChatTimelineState,
    val callInfo: ChatCallInfoState
)
