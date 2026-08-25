package com.zyna.app.data.matrix

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Transfers an FFI resource created on the IO dispatcher to its caller without a cancellation gap.
 *
 * A dispatcher change may discard a successfully produced result when the caller is cancelled just
 * before resumption. The pending reference keeps ownership here until that resumption succeeds.
 */
internal suspend fun <T : Any> withFfiResourceHandoff(
    release: suspend (T) -> Unit,
    acquire: suspend (own: (T) -> Unit) -> T
): T {
    val pending = AtomicReference<T?>()
    try {
        val resource = withContext(Dispatchers.IO) {
            acquire { acquired ->
                check(pending.compareAndSet(null, acquired)) {
                    "An FFI handoff may own only one resource"
                }
            }
        }
        check(pending.compareAndSet(resource, null)) {
            "The returned FFI resource was not registered for handoff"
        }
        return resource
    } catch (error: Throwable) {
        pending.getAndSet(null)?.let { resource ->
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { release(resource) }
            }
        }
        throw error
    }
}
