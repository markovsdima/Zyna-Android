package com.zyna.app.data.matrix

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FfiResourceHandoffTest {
    @Test
    fun successfulHandoffTransfersOwnershipToTheCaller() = runBlocking {
        val resource = FakeFfiResource()

        val returned = withFfiResourceHandoff(
            release = FakeFfiResource::release
        ) { own ->
            own(resource)
            resource
        }

        assertSame(resource, returned)
        assertFalse(resource.released)
    }

    @Test
    fun cancellationDuringDispatcherHandoffReleasesPendingResource() = runBlocking {
        val resource = FakeFfiResource()
        val acquired = CompletableDeferred<Unit>()
        val allowReturn = CompletableDeferred<Unit>()
        val job = launch {
            withFfiResourceHandoff(
                release = FakeFfiResource::release
            ) { own ->
                own(resource)
                acquired.complete(Unit)
                withContext(NonCancellable) {
                    allowReturn.await()
                }
                resource
            }
        }
        acquired.await()

        job.cancel()
        allowReturn.complete(Unit)
        job.join()

        assertTrue(resource.released)
    }
}

private class FakeFfiResource {
    var released = false
        private set

    fun release() {
        released = true
    }
}
