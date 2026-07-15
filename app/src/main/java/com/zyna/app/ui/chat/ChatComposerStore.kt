package com.zyna.app.ui.chat

import androidx.annotation.MainThread
import com.zyna.app.data.matrix.MatrixEditTarget
import com.zyna.app.data.matrix.MatrixForwardTarget
import com.zyna.app.data.matrix.MatrixReplyInfo

data class ChatComposerState(
    val replyTarget: MatrixReplyInfo? = null,
    val editTarget: MatrixEditTarget? = null,
    val forwardTarget: MatrixForwardTarget? = null,
    val pendingForwardTarget: MatrixForwardTarget? = null
)

/**
 * Owns target-selection rules for the chat composer.
 *
 * This class is main-thread confined. [state] is mirrored into AppUiState by
 * AppViewModel while the send pipeline is extracted in later steps.
 */
internal class ChatComposerStore(
    initialState: ChatComposerState = ChatComposerState()
) {
    var state: ChatComposerState = initialState
        private set

    @MainThread
    fun selectReply(target: MatrixReplyInfo): ChatComposerState? {
        if (target.eventId.isBlank()) {
            return null
        }
        return state.copy(
            replyTarget = target,
            editTarget = null,
            forwardTarget = null
        ).also(::setState)
    }

    @MainThread
    fun selectEdit(target: MatrixEditTarget): ChatComposerState? {
        if (target.eventId.isBlank() || target.body.isBlank()) {
            return null
        }
        return state.copy(
            replyTarget = null,
            editTarget = target,
            forwardTarget = null
        ).also(::setState)
    }

    @MainThread
    fun startForwardPicker(target: MatrixForwardTarget): ChatComposerState? {
        if (target.body.isBlank() && target.imageItems.isEmpty()) {
            return null
        }
        return state.copy(
            replyTarget = null,
            editTarget = null,
            forwardTarget = null,
            pendingForwardTarget = target
        ).also(::setState)
    }

    @MainThread
    fun cancelForwardPicker(): ChatComposerState {
        return state.copy(pendingForwardTarget = null).also(::setState)
    }

    @MainThread
    fun enterRoom(forwardTarget: MatrixForwardTarget?): ChatComposerState {
        return ChatComposerState(forwardTarget = forwardTarget).also(::setState)
    }

    @MainThread
    fun clearReply(): ChatComposerState {
        return state.copy(replyTarget = null).also(::setState)
    }

    @MainThread
    fun clearEdit(): ChatComposerState {
        return state.copy(editTarget = null).also(::setState)
    }

    @MainThread
    fun clearForward(): ChatComposerState {
        return state.copy(forwardTarget = null).also(::setState)
    }

    @MainThread
    fun clearActiveTargets(): ChatComposerState {
        return state.copy(
            replyTarget = null,
            editTarget = null,
            forwardTarget = null
        ).also(::setState)
    }

    @MainThread
    fun clearAll(): ChatComposerState {
        return ChatComposerState().also(::setState)
    }

    private fun setState(nextState: ChatComposerState) {
        state = nextState
    }
}
