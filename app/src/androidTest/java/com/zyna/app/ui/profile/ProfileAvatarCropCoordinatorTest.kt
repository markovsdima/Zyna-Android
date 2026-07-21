package com.zyna.app.ui.profile

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zyna.app.data.profile.ProfileAvatarCropSpec
import com.zyna.app.data.profile.ProfileAvatarDraft
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@RunWith(AndroidJUnit4::class)
class ProfileAvatarCropCoordinatorTest {
    private val allocatedBitmaps = mutableListOf<Bitmap>()
    private val bitmapNames = mutableMapOf<Bitmap, String>()

    @After
    fun tearDown() {
        allocatedBitmaps.forEach { bitmap ->
            bitmap.takeUnless { it.isRecycled }?.recycle()
        }
    }

    @Test
    fun replacingPickerRejectsAndCleansLatePreparation() = runBlocking {
        val firstUri = Uri.parse("content://test/first")
        val secondUri = Uri.parse("content://test/second")
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val fixture = fixture(
            scope = CoroutineScope(coroutineContext),
            prepareSource = { uri ->
                if (uri == firstUri) {
                    firstStarted.complete(Unit)
                    awaitIgnoringCancellation(releaseFirst)
                    preparedSource("first")
                } else {
                    preparedSource("second")
                }
            }
        )

        val firstRequest = fixture.coordinator.beginPick(EDIT_SESSION_ID)
        fixture.coordinator.handlePickerResult(firstUri, firstRequest)
        firstStarted.await()

        val secondRequest = fixture.coordinator.beginPick(EDIT_SESSION_ID)
        fixture.coordinator.handlePickerResult(secondUri, secondRequest)
        awaitCondition {
            fixture.coordinator.state.value.session?.request == secondRequest
        }
        val secondSession = requireNotNull(fixture.coordinator.state.value.session)

        releaseFirst.complete(Unit)
        awaitCondition { fixture.deletedSourceNames.contains("first") }

        assertSame(secondSession, fixture.coordinator.state.value.session)
        assertTrue(fixture.recycledPreviewNames.contains("first"))
        assertFalse(fixture.deletedSourceNames.contains("second"))
        fixture.coordinator.close()
    }

    @Test
    fun dismissDuringExportRejectsAndDeletesLateDraft() = runBlocking {
        val exportStarted = CompletableDeferred<Unit>()
        val releaseExport = CompletableDeferred<Unit>()
        val fixture = fixture(
            scope = CoroutineScope(coroutineContext),
            exportDraft = { _, _ ->
                exportStarted.complete(Unit)
                awaitIgnoringCancellation(releaseExport)
                draft("late")
            }
        )
        val request = fixture.coordinator.beginPick(EDIT_SESSION_ID)
        fixture.coordinator.handlePickerResult(TEST_URI, request)
        awaitCondition { fixture.coordinator.state.value.session != null }

        fixture.coordinator.confirm(ProfileAvatarCropSpec())
        exportStarted.await()
        fixture.coordinator.dismiss()
        releaseExport.complete(Unit)
        awaitCondition { fixture.deletedDraftNames.contains("late") }

        assertNull(fixture.coordinator.state.value.session)
        assertEquals(1, fixture.sessionCloseCount)
        assertTrue(fixture.deletedSourceNames.contains("source"))
        assertTrue(fixture.recycledPreviewNames.contains("source"))
        fixture.coordinator.close()
    }

    @Test
    fun closedEditSessionClearsProcessingStateAndRejectsExport() = runBlocking {
        var canDeliver = true
        val releaseExport = CompletableDeferred<Unit>()
        val fixture = fixture(
            scope = CoroutineScope(coroutineContext),
            canDeliver = { canDeliver },
            exportDraft = { _, _ ->
                releaseExport.await()
                draft("closed-session")
            }
        )
        val request = fixture.coordinator.beginPick(EDIT_SESSION_ID)
        fixture.coordinator.handlePickerResult(TEST_URI, request)
        awaitCondition { fixture.coordinator.state.value.session != null }

        fixture.coordinator.confirm(ProfileAvatarCropSpec())
        awaitCondition { fixture.coordinator.state.value.isProcessing }
        canDeliver = false
        releaseExport.complete(Unit)
        awaitCondition { fixture.coordinator.state.value.session == null }

        assertFalse(fixture.coordinator.state.value.isProcessing)
        assertTrue(fixture.deletedDraftNames.contains("closed-session"))
        assertEquals(1, fixture.sessionCloseCount)
        fixture.coordinator.close()
    }

    @Test
    fun exportErrorCanBeRetriedAndDelivered() = runBlocking {
        var exportAttempts = 0
        val fixture = fixture(
            scope = CoroutineScope(coroutineContext),
            exportDraft = { _, _ ->
                exportAttempts += 1
                if (exportAttempts == 1) {
                    error("first export failed")
                }
                draft("success")
            }
        )
        val request = fixture.coordinator.beginPick(EDIT_SESSION_ID)
        fixture.coordinator.handlePickerResult(TEST_URI, request)
        awaitCondition { fixture.coordinator.state.value.session != null }

        fixture.coordinator.confirm(ProfileAvatarCropSpec())
        awaitCondition { fixture.coordinator.state.value.error == ProfileAvatarCropError.EXPORT }
        fixture.coordinator.confirm(ProfileAvatarCropSpec(zoom = 2f))
        awaitCondition { fixture.deliveredDraftNames.contains("success") }

        assertEquals(2, exportAttempts)
        assertNull(fixture.coordinator.state.value.session)
        assertEquals(1, fixture.sessionCloseCount)
        fixture.coordinator.close()
    }

    private fun fixture(
        scope: CoroutineScope,
        canDeliver: (Long) -> Boolean = { true },
        prepareSource: suspend (Uri) -> PreparedProfileAvatarSource = {
            preparedSource("source")
        },
        exportDraft: suspend (File, ProfileAvatarCropSpec) -> ProfileAvatarDraft = { _, _ ->
            draft("draft")
        }
    ): CoordinatorFixture {
        val fixture = CoordinatorFixture()
        val driver = ProfileAvatarCropDriver(
            prepareSource = prepareSource,
            exportDraft = exportDraft,
            cleanupOrphanSources = {},
            deleteSource = { source -> fixture.deletedSourceNames += source.name },
            deleteDraft = { draft -> fixture.deletedDraftNames += File(draft.localPath).name },
            recyclePreview = { bitmap ->
                fixture.recycledPreviewNames += bitmapName(bitmap)
                bitmap.recycle()
            }
        )
        fixture.coordinator = ProfileAvatarCropCoordinator(
            scope = scope,
            driver = driver,
            canDeliver = canDeliver,
            onDraftReady = { draft, _ ->
                fixture.deliveredDraftNames += File(draft.localPath).name
            },
            onPreparationError = { fixture.preparationErrorCount += 1 },
            onSessionWillClose = { fixture.sessionCloseCount += 1 }
        )
        return fixture
    }

    private fun preparedSource(name: String): PreparedProfileAvatarSource {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, name.hashCode())
        allocatedBitmaps += bitmap
        bitmapNames[bitmap] = name
        return PreparedProfileAvatarSource(
            sourceFile = File(name),
            previewBitmap = bitmap
        )
    }

    private fun bitmapName(bitmap: Bitmap): String {
        return requireNotNull(bitmapNames[bitmap])
    }

    private fun draft(name: String): ProfileAvatarDraft {
        return ProfileAvatarDraft(
            localPath = name,
            mimeType = "image/jpeg",
            sizeBytes = 1L,
            width = 768,
            height = 768
        )
    }

    private suspend fun awaitIgnoringCancellation(gate: CompletableDeferred<Unit>) {
        suspendCoroutine { continuation ->
            gate.invokeOnCompletion {
                continuation.resume(Unit)
            }
        }
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(2_000L) {
            while (!condition()) {
                yield()
            }
        }
    }

    private class CoordinatorFixture {
        lateinit var coordinator: ProfileAvatarCropCoordinator
        val deletedSourceNames = mutableListOf<String>()
        val deletedDraftNames = mutableListOf<String>()
        val deliveredDraftNames = mutableListOf<String>()
        val recycledPreviewNames = mutableListOf<String>()
        var preparationErrorCount = 0
        var sessionCloseCount = 0
    }

    private companion object {
        const val EDIT_SESSION_ID = 41L
        val TEST_URI: Uri = Uri.parse("content://test/avatar")
    }
}
