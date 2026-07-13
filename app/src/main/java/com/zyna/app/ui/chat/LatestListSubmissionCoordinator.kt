package com.zyna.app.ui.chat

/** Coalesces identical pending ListAdapter submissions and only completes the latest generation. */
internal class LatestListSubmissionCoordinator<T : Any>(
    private val currentValue: () -> T,
    private val submitValue: (T, onCommitted: () -> Unit) -> Unit
) {
    private var submittedValue: T? = null
    private var pendingCommit: ((didCommitNewValue: Boolean) -> Unit)? = null

    fun willSubmitNew(value: T): Boolean = submittedValue !== value

    fun submit(
        value: T,
        onCommitted: (didCommitNewValue: Boolean) -> Unit
    ) {
        if (submittedValue === value) {
            if (currentValue() === value) {
                onCommitted(false)
            } else {
                pendingCommit = onCommitted
            }
            return
        }

        submittedValue = value
        pendingCommit = onCommitted
        submitValue(value) {
            if (submittedValue !== value) return@submitValue
            val callback = pendingCommit
            pendingCommit = null
            callback?.invoke(true)
        }
    }
}
