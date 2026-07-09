package com.zyna.app

import android.app.Application
import com.zyna.app.core.di.AppContainer
import com.zyna.app.data.push.FirebaseMessagingRegistration
import com.zyna.app.data.push.ZynaNotificationChannels
import com.zyna.app.ui.theme.AppThemeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ZynaApplication : Application() {
    lateinit var appThemeStore: AppThemeStore
        private set

    lateinit var appContainer: AppContainer
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        appThemeStore = AppThemeStore(this).also { it.applySavedMode() }
        ZynaForegroundState.register(this)
        ZynaNotificationChannels.ensureCreated(this)
        appContainer = AppContainer(
            context = this,
            appThemeStore = appThemeStore
        )
        FirebaseMessagingRegistration.requestIfInstallationIdMissing(
            installationIdStore = appContainer.firebaseInstallationIdStore,
            reason = REASON_APP_START
        )
    }

    override fun onTerminate() {
        applicationScope.cancel()
        super.onTerminate()
    }

    fun registerMatrixPusherForCurrentSession() {
        applicationScope.launch {
            appContainer.matrixClientService.registerPushPusherIfAvailable()
        }
    }

    companion object {
        private const val REASON_APP_START = "app_start"
    }
}
