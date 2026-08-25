package com.zyna.app.ui.profile

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import com.zyna.app.data.profile.ProfileAvatarCropSpec
import com.zyna.app.data.profile.ProfileAvatarDraft
import com.zyna.app.data.profile.ProfileAvatarPreprocessor
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class PreparedProfileAvatarSource(
    val sourceFile: File,
    val previewBitmap: Bitmap
)

internal class ProfileAvatarCropDriver(
    val prepareSource: suspend (Uri) -> PreparedProfileAvatarSource,
    val exportDraft: suspend (File, ProfileAvatarCropSpec) -> ProfileAvatarDraft,
    val cleanupOrphanSources: suspend () -> Unit,
    val deleteSource: (File) -> Unit,
    val deleteDraft: (ProfileAvatarDraft) -> Unit,
    val recyclePreview: (Bitmap) -> Unit
)

internal fun createProfileAvatarCropDriver(
    contentResolver: ContentResolver,
    sourceDirectory: File,
    outputDirectory: File,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
): ProfileAvatarCropDriver {
    fun deleteSource(file: File) {
        runCatching { file.delete() }
    }

    fun recyclePreview(bitmap: Bitmap) {
        bitmap.takeUnless { it.isRecycled }?.recycle()
    }

    fun deleteDraft(draft: ProfileAvatarDraft) {
        runCatching { File(draft.localPath).delete() }
    }

    return ProfileAvatarCropDriver(
        prepareSource = { uri ->
            var unclaimedSource: PreparedProfileAvatarSource? = null
            try {
                val prepared = withContext(ioDispatcher) {
                    sourceDirectory.mkdirs()
                    val sourceFile = File(
                        sourceDirectory,
                        "${System.currentTimeMillis()}-${UUID.randomUUID()}-source"
                    )
                    try {
                        contentResolver.openInputStream(uri)?.use { input ->
                            sourceFile.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("Could not open selected image")
                        check(sourceFile.length() > 0L) { "Selected image is empty" }
                        PreparedProfileAvatarSource(
                            sourceFile = sourceFile,
                            previewBitmap = ProfileAvatarPreprocessor.loadPreview(sourceFile)
                        ).also { unclaimedSource = it }
                    } catch (error: Throwable) {
                        deleteSource(sourceFile)
                        throw error
                    }
                }
                unclaimedSource = null
                prepared
            } finally {
                unclaimedSource?.let { prepared ->
                    recyclePreview(prepared.previewBitmap)
                    deleteSource(prepared.sourceFile)
                }
            }
        },
        exportDraft = { sourceFile, crop ->
            var unclaimedDraft: ProfileAvatarDraft? = null
            try {
                val draft = withContext(ioDispatcher) {
                    ProfileAvatarPreprocessor.process(
                        sourceFile = sourceFile,
                        crop = crop,
                        outputDir = outputDirectory
                    ).also { unclaimedDraft = it }
                }
                unclaimedDraft = null
                draft
            } finally {
                unclaimedDraft?.let(::deleteDraft)
            }
        },
        cleanupOrphanSources = {
            withContext(ioDispatcher) {
                sourceDirectory.listFiles()?.forEach(::deleteSource)
            }
        },
        deleteSource = ::deleteSource,
        deleteDraft = ::deleteDraft,
        recyclePreview = ::recyclePreview
    )
}
