package com.zyna.app

import android.os.Bundle
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
import com.zyna.app.ui.theme.ZynaAndroidTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appContainer = (application as ZynaApplication).appContainer
        setContent {
            val appViewModel: AppViewModel = viewModel(
                factory = AppViewModelFactory(appContainer.matrixClientService)
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
                    onLogout = appViewModel::logout
                )
            }
        }
    }
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
            onLogout = {}
        )
    }
}
