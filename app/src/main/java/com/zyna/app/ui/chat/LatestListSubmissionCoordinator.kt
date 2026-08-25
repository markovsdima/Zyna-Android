package com.zyna.app.ui.chat

/** Coalesces identical pending ListAdapter submissions and only completes the latest generation. */
internal class LatestListSubmissionCoordinator<T : Any>(
    private val submitValue: (T, onCommitted: () -> Unit) -> Unit
) {
    private var submittedValue: T? = null
    private var isCommitPending = false
    private var pendingCommit: ((didCommitNewValue: Boolean) -> Unit)? = null

    fun willSubmitNew(value: T): Boolean = submittedValue !== value

    fun submit(
        value: T,
        onCommitted: (didCommitNewValue: Boolean) -> Unit
    ) {
        if (submittedValue === value) {
            if (isCommitPending) {
                pendingCommit = onCommitted
            } else {
                onCommitted(false)
            }
            return
        }

        submittedValue = value
        isCommitPending = true
        pendingCommit = onCommitted
        submitValue(value) {
            if (submittedValue !== value) return@submitValue
            isCommitPending = false
            val callback = pendingCommit
            pendingCommit = null
            callback?.invoke(true)
        }
    }
}
