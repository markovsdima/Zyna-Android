package com.zyna.app

import android.app.Application
import com.zyna.app.core.di.AppContainer

class ZynaApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
    }
}
