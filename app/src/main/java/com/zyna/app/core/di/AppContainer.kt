package com.zyna.app.core.di

import android.content.Context
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.session.MatrixSessionStore
import com.zyna.app.data.session.MatrixStorePassphraseStore

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val sessionStore = MatrixSessionStore(appContext)
    val matrixStorePassphraseStore = MatrixStorePassphraseStore(appContext)
    val matrixClientService = MatrixClientService(
        context = appContext,
        sessionStore = sessionStore,
        storePassphraseStore = matrixStorePassphraseStore
    )
}
