package com.zyna.app

import android.content.Intent
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
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zyna.app.data.outgoing.OutgoingImagePreprocessor
import com.zyna.app.data.outgoing.OutgoingMediaStorage
import com.zyna.app.data.outgoing.OutgoingOutboxDebugHooks
import com.zyna.app.data.outgoing.OutgoingPhotoDraft
import com.zyna.app.data.outgoing.OutgoingPhotoDraftItem
import com.zyna.app.ui.app.AppRoute
import com.zyna.app.ui.app.AppUiState
import com.zyna.app.ui.app.AppViewModel
import com.zyna.app.ui.app.AppViewModelFactory
import com.zyna.app.ui.glass.GlassInputBarView
import com.zyna.app.ui.navigation.ZynaAppActions
import com.zyna.app.ui.navigation.ZynaRootHostView
import com.zyna.app.ui.photo.PhotoMessageEditor
import com.zyna.app.ui.theme.ZynaAndroidTheme
import com.zyna.app.util.ZynaPerfLog
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var appViewModel: AppViewModel
    private lateinit var rootHost: ZynaRootHostView
    private lateinit var photoPickerLauncher: ActivityResultLauncher<PickVisualMediaRequest>
    private var latestState: AppUiState = AppUiState()
    private var photoEditorItems: List<OutgoingPhotoDraftItem> = emptyList()
    private var isPreparingPhotos: Boolean = false
    private var photoEditorError: String? = null
    private var photoEditorView: ComposeView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        preferMaxRefreshRate()
        val appContainer = (application as ZynaApplication).appContainer
        OutgoingOutboxDebugHooks.handleIntent(this, intent)

        appViewModel = ViewModelProvider(
            this,
            AppViewModelFactory(
                matrixClientService = appContainer.matrixClientService,
                localCacheRepository = appContainer.localCacheRepository,
                outgoingOutboxService = appContainer.outgoingOutboxService,
                matrixMediaLoader = appContainer.matrixMediaLoader
            )
        )[AppViewModel::class.java]

        photoPickerLauncher = registerForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(10)
        ) { uris ->
            handlePickedPhotos(uris)
        }

        rootHost = ZynaRootHostView(this)
        setContentView(rootHost)

        val actions = ZynaAppActions(
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
            onAttachPhotos = ::launchPhotoPicker,
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
            onRedactMessages = appViewModel::redactMessages,
            onDebugMarkOutgoingEnvelopeFailed = appViewModel::debugMarkOutgoingEnvelopeFailed,
            onVisibleReadReceiptCandidate = appViewModel::updateVisibleReadReceiptCandidate,
            onChatJumpTargetConsumed = appViewModel::clearChatJumpTarget,
            onChatScrollToLiveEdgeConsumed = appViewModel::clearChatScrollToLiveEdgeRequest,
            onLogout = appViewModel::logout
        )

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (rootHost.handleBack()) {
                        return
                    }
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appViewModel.uiState.collect { state ->
                    val collectStart = ZynaPerfLog.start()
                    ZynaPerfLog.mark {
                        "activity.uiState.collect route=${state.route.perfName()} " +
                            "messages=${state.chatMessages.size} loading=${state.isLoadingChat}"
                    }
                    latestState = state
                    rootHost.render(state, actions)
                    renderPhotoEditor()
                    ZynaPerfLog.end(
                        collectStart,
                        "activity.uiState.rendered"
                    ) {
                        "route=${state.route.perfName()} messages=${state.chatMessages.size}"
                    }
                }
            }
        }
    }

    private fun launchPhotoPicker() {
        photoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun handlePickedPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) {
            return
        }

        isPreparingPhotos = true
        photoEditorError = null
        renderPhotoEditor()
        lifecycleScope.launch {
            try {
                deletePhotoItems(photoEditorItems)
                photoEditorItems = prepareOutgoingPhotoItems(uris)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                photoEditorError = error.message ?: error.javaClass.simpleName
            } finally {
                isPreparingPhotos = false
                renderPhotoEditor()
            }
        }
    }

    private fun renderPhotoEditor() {
        val shouldShowEditor = photoEditorItems.isNotEmpty() && latestState.route is AppRoute.Chat
        if (!shouldShowEditor) {
            rootHost.showOverlay(null)
            photoEditorView = null
            return
        }

        val editorView = photoEditorView ?: ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            photoEditorView = this
        }

        editorView.setContent {
            ZynaAndroidTheme {
                PhotoMessageEditor(
                    items = photoEditorItems,
                    isSending = isPreparingPhotos,
                    errorMessage = photoEditorError,
                    onDismiss = {
                        if (!isPreparingPhotos) {
                            deletePhotoItems(photoEditorItems)
                            photoEditorItems = emptyList()
                            photoEditorError = null
                            renderPhotoEditor()
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
                        renderPhotoEditor()
                        lifecycleScope.launch {
                            var processedItems: List<OutgoingPhotoDraftItem> = emptyList()
                            try {
                                processedItems = processOutgoingPhotoItems(result.items)
                                val draft = OutgoingPhotoDraft(
                                    items = processedItems,
                                    caption = result.caption,
                                    captionPlacement = result.captionPlacement,
                                    layoutOverride = result.layoutOverride
                                )
                                val didSend = appViewModel.sendPhotoMessages(draft)
                                if (didSend) {
                                    deletePhotoItems(result.items)
                                    photoEditorItems = emptyList()
                                    photoEditorError = null
                                } else {
                                    deletePhotoItems(processedItems)
                                    photoEditorError = "Could not send photos"
                                }
                            } catch (error: CancellationException) {
                                deletePhotoItems(processedItems)
                                throw error
                            } catch (error: Throwable) {
                                deletePhotoItems(processedItems)
                                photoEditorError = error.message ?: error.javaClass.simpleName
                            } finally {
                                isPreparingPhotos = false
                                renderPhotoEditor()
                            }
                        }
                    }
                )
            }
        }
        rootHost.showOverlay(editorView)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        OutgoingOutboxDebugHooks.handleIntent(this, intent)
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
                    sizeBytes = outputFile.length(),
                    thumbnailLocalPath = null,
                    thumbnailMimeType = null,
                    thumbnailWidth = null,
                    thumbnailHeight = null,
                    thumbnailSizeBytes = null,
                    blurhash = null
                )
            }
        } catch (error: Throwable) {
            copiedFiles.forEach { file -> file.delete() }
            throw error
        }
    }

    private suspend fun processOutgoingPhotoItems(
        items: List<OutgoingPhotoDraftItem>
    ): List<OutgoingPhotoDraftItem> = withContext(Dispatchers.IO) {
        val outputDir = File(filesDir, OutgoingMediaStorage.DIRECTORY_NAME).apply {
            mkdirs()
        }
        val processedItems = mutableListOf<OutgoingPhotoDraftItem>()
        try {
            items.map { item ->
                OutgoingImagePreprocessor.processFile(
                    file = File(item.localPath),
                    outputDir = outputDir
                ).also { processed ->
                    processedItems += processed
                }
            }
        } catch (error: Throwable) {
            deletePhotoItems(processedItems)
            throw error
        }
    }

    private fun deletePhotoItems(items: List<OutgoingPhotoDraftItem>) {
        items.flatMap { item -> item.localFiles() }
            .forEach { file -> file.delete() }
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
                return Pair(sourceWidth, sourceHeight)
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

private fun AppRoute.perfName(): String {
    return when (this) {
        AppRoute.ForwardPicker -> "ForwardPicker"
        AppRoute.Login -> "Login"
        is AppRoute.RecoveryKey -> "RecoveryKey"
        AppRoute.Rooms -> "Rooms"
        is AppRoute.Chat -> "Chat(${roomId.takeLast(10)})"
    }
}

private fun OutgoingPhotoDraftItem.localFiles(): List<File> {
    return listOfNotNull(
        localPath.takeIf { it.isNotBlank() },
        thumbnailLocalPath?.takeIf { it.isNotBlank() }
    ).distinct().map(::File)
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
