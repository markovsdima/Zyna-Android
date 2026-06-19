package com.zyna.app.core.di

import android.content.Context
import com.zyna.app.data.local.LocalCacheRepository
import com.zyna.app.data.local.LocalDatabasePassphraseStore
import com.zyna.app.data.local.ZynaDatabase
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.media.MatrixMediaLoader
import com.zyna.app.data.outgoing.OutgoingOutboxService
import com.zyna.app.data.session.MatrixSessionStore
import com.zyna.app.data.session.MatrixStorePassphraseStore

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val sessionStore = MatrixSessionStore(appContext)
    val matrixStorePassphraseStore = MatrixStorePassphraseStore(appContext)
    val localDatabasePassphraseStore = LocalDatabasePassphraseStore(appContext)
    val database = ZynaDatabase.create(
        context = appContext,
        passphraseStore = localDatabasePassphraseStore
    )
    val localCacheRepository = LocalCacheRepository(
        database = database,
        context = appContext
    )
    val matrixClientService = MatrixClientService(
        context = appContext,
        sessionStore = sessionStore,
        storePassphraseStore = matrixStorePassphraseStore
    )
    val matrixMediaLoader = MatrixMediaLoader(
        matrixClientService = matrixClientService,
        context = appContext
    )
    val outgoingOutboxService = OutgoingOutboxService(
        matrixClientService = matrixClientService,
        localCacheRepository = localCacheRepository
    )
}
