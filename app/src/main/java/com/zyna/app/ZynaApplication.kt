package com.zyna.app

import android.app.Application
import com.zyna.app.core.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ZynaApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
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
}
