package com.zyna.app

import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zyna.app.data.matrix.MatrixClientState
import com.zyna.app.ui.app.AppUiState
import com.zyna.app.ui.app.AppViewModel
import com.zyna.app.ui.app.AppViewModelFactory
import com.zyna.app.ui.app.ZynaApp
import com.zyna.app.ui.glass.GlassInputBarView
import com.zyna.app.ui.theme.ZynaAndroidTheme

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
                    localCacheRepository = appContainer.localCacheRepository
                )
            )
            val state by appViewModel.uiState.collectAsState()

            ZynaAndroidTheme {
                ZynaApp(
                    state = state,
                    onLogin = appViewModel::login,
                    onSubmitRecoveryKey = appViewModel::submitRecoveryKey,
                    onRefreshRooms = appViewModel::refreshRooms,
                    onOpenRoom = appViewModel::openRoom,
                    onRefreshChat = appViewModel::refreshCurrentChat,
                    onCloseChat = appViewModel::closeChat,
                    onLoadOlderChatMessages = appViewModel::loadOlderChatMessages,
                    onSendChatMessage = appViewModel::sendChatMessage,
                    onLogout = appViewModel::logout
                )
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
            onLogin = { _, _, _ -> },
            onSubmitRecoveryKey = {},
            onRefreshRooms = {},
            onOpenRoom = {},
            onRefreshChat = {},
            onCloseChat = {},
            onLoadOlderChatMessages = {},
            onSendChatMessage = { false },
            onLogout = {}
        )
    }
}
