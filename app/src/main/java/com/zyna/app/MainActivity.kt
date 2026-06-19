package com.zyna.app

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zyna.app.data.matrix.MatrixClientState
import com.zyna.app.data.outgoing.OutgoingMediaStorage
import com.zyna.app.data.outgoing.OutgoingPhotoDraft
import com.zyna.app.data.outgoing.OutgoingPhotoDraftItem
import com.zyna.app.ui.app.AppRoute
import com.zyna.app.ui.app.AppUiState
import com.zyna.app.ui.app.AppViewModel
import com.zyna.app.ui.app.AppViewModelFactory
import com.zyna.app.ui.app.ZynaApp
import com.zyna.app.ui.glass.GlassInputBarView
import com.zyna.app.ui.photo.PhotoMessageEditor
import com.zyna.app.ui.theme.ZynaAndroidTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        preferMaxRefreshRate()
        val appContainer = (application as ZynaApplication).appContainer
        setContent {
            val appViewModel: AppViewModel = viewModel(
                factory = AppViewModelFactory(
                    matrixClientService = appContainer.matrixClientService,
                    localCacheRepository = appContainer.localCacheRepository,
                    outgoingOutboxService = appContainer.outgoingOutboxService
                )
            )
            val state by appViewModel.uiState.collectAsState()
            var photoEditorItems by remember { mutableStateOf<List<OutgoingPhotoDraftItem>>(emptyList()) }
            var isPreparingPhotos by remember { mutableStateOf(false) }
            var photoEditorError by remember { mutableStateOf<String?>(null) }
            val coroutineScope = rememberCoroutineScope()
            val photoPickerLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.PickMultipleVisualMedia(10)
            ) { uris ->
                if (uris.isNotEmpty()) {
                    isPreparingPhotos = true
                    photoEditorError = null
                    coroutineScope.launch {
                        try {
                            deletePhotoItems(photoEditorItems)
                            photoEditorItems = prepareOutgoingPhotoItems(uris)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            photoEditorError = error.message ?: error.javaClass.simpleName
                        } finally {
                            isPreparingPhotos = false
                        }
                    }
                }
            }

            ZynaAndroidTheme {
                Box(Modifier.fillMaxSize()) {
                    ZynaApp(
                        state = state,
                        matrixMediaLoader = appContainer.matrixMediaLoader,
                        onLogin = appViewModel::login,
                        onSubmitRecoveryKey = appViewModel::submitRecoveryKey,
                        onRefreshRooms = appViewModel::refreshRooms,
                        onOpenRoom = appViewModel::openRoom,
                        onForwardRoomSelected = appViewModel::selectForwardRoom,
                        onCancelForwardPicker = appViewModel::cancelForwardPicker,
                        onRefreshChat = appViewModel::refreshCurrentChat,
                        onCloseChat = appViewModel::closeChat,
                        onLoadOlderChatMessages = appViewModel::loadOlderChatMessages,
                        onLoadNewerChatMessages = appViewModel::loadNewerChatMessages,
                        onJumpToChatLiveEdge = appViewModel::jumpToChatLiveEdge,
                        onSendChatMessage = appViewModel::sendChatMessage,
                        onAttachPhotos = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        onReplyToMessage = appViewModel::setChatReplyTarget,
                        onReplyHeaderClicked = appViewModel::jumpToChatEvent,
                        onCancelReply = appViewModel::clearChatReplyTarget,
                        onEditMessage = appViewModel::setChatEditTarget,
                        onCancelEdit = appViewModel::clearChatEditTarget,
                        onForwardMessage = appViewModel::startForwardMessage,
                        onCancelForward = appViewModel::clearChatForwardTarget,
                        onRetryOutgoingEnvelope = appViewModel::retryOutgoingEnvelope,
                        onDiscardOutgoingEnvelope = appViewModel::discardOutgoingEnvelope,
                        onRedactMessage = appViewModel::redactMessage,
                        onDebugMarkOutgoingEnvelopeFailed = appViewModel::debugMarkOutgoingEnvelopeFailed,
                        onVisibleReadReceiptCandidate = appViewModel::updateVisibleReadReceiptCandidate,
                        onChatJumpTargetConsumed = appViewModel::clearChatJumpTarget,
                        onChatScrollToLiveEdgeConsumed = appViewModel::clearChatScrollToLiveEdgeRequest,
                        onLogout = appViewModel::logout
                    )
                    if (photoEditorItems.isNotEmpty() && state.route is AppRoute.Chat) {
                        PhotoMessageEditor(
                            items = photoEditorItems,
                            isSending = isPreparingPhotos,
                            errorMessage = photoEditorError,
                            onDismiss = {
                                if (!isPreparingPhotos) {
                                    deletePhotoItems(photoEditorItems)
                                    photoEditorItems = emptyList()
                                    photoEditorError = null
                                }
                            },
                            onDiscardItem = { item ->
                                deletePhotoItems(listOf(item))
                            },
                            onSend = { result ->
                                if (isPreparingPhotos) {
                                    return@PhotoMessageEditor
                                }
                                isPreparingPhotos = true
                                photoEditorError = null
                                coroutineScope.launch {
                                    try {
                                        val draft = OutgoingPhotoDraft(
                                            items = result.items,
                                            caption = result.caption,
                                            captionPlacement = result.captionPlacement,
                                            layoutOverride = result.layoutOverride
                                        )
                                        val didSend = appViewModel.sendPhotoMessages(draft)
                                        if (didSend) {
                                            photoEditorItems = emptyList()
                                            photoEditorError = null
                                        } else {
                                            deletePhotoItems(draft.items)
                                            photoEditorError = "Could not send photos"
                                        }
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Throwable) {
                                        photoEditorError = error.message ?: error.javaClass.simpleName
                                    } finally {
                                        isPreparingPhotos = false
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        preferMaxRefreshRate()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            hideKeyboardIfTapOutsideInput(event)
        }
        return super.dispatchTouchEvent(event)
    }

    private fun hideKeyboardIfTapOutsideInput(event: MotionEvent) {
        val focusedView = currentFocus as? EditText ?: return
        val touchRoot = focusedView.findAncestor<GlassInputBarView>() ?: focusedView
        if (event.isInsideView(touchRoot)) {
            return
        }

        focusedView.clearFocus()
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(focusedView.windowToken, 0)
    }

    private suspend fun prepareOutgoingPhotoItems(
        uris: List<Uri>
    ): List<OutgoingPhotoDraftItem> = withContext(Dispatchers.IO) {
        val outputDir = File(filesDir, OutgoingMediaStorage.DIRECTORY_NAME).apply {
            mkdirs()
        }
        val copiedFiles = mutableListOf<File>()
        try {
            uris.map { uri ->
                val mimeType = contentResolver.getType(uri)
                    ?.takeIf { it.startsWith("image/") }
                    ?: "image/jpeg"
                val outputFile = File(
                    outputDir,
                    "${System.currentTimeMillis()}-${UUID.randomUUID()}.${mimeType.fileExtension()}"
                )
                contentResolver.openInputStream(uri)?.use { input ->
                    outputFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: error("Could not open selected image")
                copiedFiles += outputFile
                val dimensions = outputFile.imageDimensions()
                OutgoingPhotoDraftItem(
                    localPath = outputFile.absolutePath,
                    mimeType = mimeType,
                    width = dimensions.first,
                    height = dimensions.second,
                    sizeBytes = outputFile.length()
                )
            }
        } catch (error: Throwable) {
            copiedFiles.forEach { file -> file.delete() }
            throw error
        }
    }

    private fun deletePhotoItems(items: List<OutgoingPhotoDraftItem>) {
        items.forEach { item -> File(item.localPath).delete() }
    }

    private fun String.fileExtension(): String {
        val normalized = substringBefore(';').lowercase()
        return MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(normalized)
            ?: when (normalized) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                else -> "jpg"
            }
    }

    private fun File.imageDimensions(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            var sourceWidth = 0
            var sourceHeight = 0
            runCatching {
                val source = ImageDecoder.createSource(this)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    sourceWidth = info.size.width
                    sourceHeight = info.size.height
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetSampleSize(maxOf(info.size.width, info.size.height, 1))
                }
            }
            if (sourceWidth > 0 && sourceHeight > 0) {
                return Pair(
                    sourceWidth,
                    sourceHeight
                )
            }
        }

        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(absolutePath, options)
        return Pair(
            options.outWidth.takeIf { it > 0 } ?: 1,
            options.outHeight.takeIf { it > 0 } ?: 1
        )
    }

    @Suppress("DEPRECATION")
    private fun preferMaxRefreshRate() {
        val display = windowManager.defaultDisplay
        val currentMode = display.mode
        val preferredMode = display.supportedModes
            .filter {
                it.physicalWidth == currentMode.physicalWidth &&
                    it.physicalHeight == currentMode.physicalHeight
            }
            .maxByOrNull { it.refreshRate }
            ?: display.supportedModes.maxByOrNull { it.refreshRate }
            ?: return
        val maxRefreshRate = preferredMode.refreshRate

        val attributes = window.attributes
        if (
            attributes.preferredDisplayModeId != preferredMode.modeId ||
            attributes.preferredRefreshRate != maxRefreshRate
        ) {
            attributes.preferredDisplayModeId = preferredMode.modeId
            attributes.preferredRefreshRate = maxRefreshRate
            window.attributes = attributes
        }
    }
}

private fun MotionEvent.isInsideView(view: View): Boolean {
    val bounds = Rect()
    return view.getGlobalVisibleRect(bounds) && bounds.contains(rawX.toInt(), rawY.toInt())
}

private inline fun <reified T : View> View.findAncestor(): T? {
    var current: View? = this
    while (current != null) {
        if (current is T) {
            return current
        }
        current = current.parent as? View
    }
    return null
}

@Composable
@Preview
fun AppPreview() {
    ZynaAndroidTheme {
        ZynaApp(
            state = AppUiState(matrixState = MatrixClientState.LoggedOut),
            matrixMediaLoader = null,
            onLogin = { _, _, _ -> },
            onSubmitRecoveryKey = {},
            onRefreshRooms = {},
            onOpenRoom = {},
            onForwardRoomSelected = {},
            onCancelForwardPicker = {},
            onRefreshChat = {},
            onCloseChat = {},
            onLoadOlderChatMessages = {},
            onLoadNewerChatMessages = {},
            onJumpToChatLiveEdge = {},
            onSendChatMessage = { false },
            onAttachPhotos = {},
            onReplyToMessage = {},
            onReplyHeaderClicked = {},
            onCancelReply = {},
            onEditMessage = {},
            onCancelEdit = {},
            onForwardMessage = {},
            onCancelForward = {},
            onRetryOutgoingEnvelope = {},
            onDiscardOutgoingEnvelope = {},
            onRedactMessage = {},
            onDebugMarkOutgoingEnvelopeFailed = {},
            onVisibleReadReceiptCandidate = { _, _, _ -> },
            onChatJumpTargetConsumed = {},
            onChatScrollToLiveEdgeConsumed = {},
            onLogout = {}
        )
    }
}
