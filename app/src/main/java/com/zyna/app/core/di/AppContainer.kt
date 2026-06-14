package com.zyna.app.core.di

import android.content.Context
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.session.MatrixSessionStore

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val sessionStore = MatrixSessionStore(appContext)
    val matrixClientService = MatrixClientService(appContext, sessionStore)
}
