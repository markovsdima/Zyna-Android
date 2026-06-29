package com.zyna.app.core.di

import android.content.Context
import com.zyna.app.data.calls.matrixrtc.MatrixClientNativeMatrixRtcCallEnvironment
import com.zyna.app.data.calls.matrixrtc.MatrixRtcIncomingCallManager
import com.zyna.app.data.calls.matrixrtc.NativeMatrixRtcCallService
import com.zyna.app.data.local.LocalCacheRepository
import com.zyna.app.data.local.LocalDatabasePassphraseStore
import com.zyna.app.data.local.ZynaDatabase
import com.zyna.app.data.matrix.MatrixClientService
import com.zyna.app.data.media.AudioPlaybackController
import com.zyna.app.data.media.MatrixAudioMediaLoader
import com.zyna.app.data.media.MatrixMediaLoader
import com.zyna.app.data.media.VoiceRecorderController
import com.zyna.app.data.outgoing.OutgoingOutboxService
import com.zyna.app.data.push.FirebaseInstallationIdStore
import com.zyna.app.data.push.MatrixPushRegistrationStore
import com.zyna.app.data.push.MatrixPushRegistrar
import com.zyna.app.data.session.MatrixSessionStore
import com.zyna.app.data.session.MatrixStorePassphraseStore
import com.zyna.app.ui.chat.theme.ChatBubbleThemeStore
import com.zyna.app.ui.theme.AppThemeStore

class AppContainer(
    context: Context,
    val appThemeStore: AppThemeStore
) {
    private val appContext = context.applicationContext

    val chatBubbleThemeStore = ChatBubbleThemeStore(appContext)
    val sessionStore = MatrixSessionStore(appContext)
    val matrixStorePassphraseStore = MatrixStorePassphraseStore(appContext)
    val firebaseInstallationIdStore = FirebaseInstallationIdStore(appContext)
    val matrixPushRegistrationStore = MatrixPushRegistrationStore(appContext)
    val matrixPushRegistrar = MatrixPushRegistrar(
        firebaseInstallationIdStore = firebaseInstallationIdStore,
        pushRegistrationStore = matrixPushRegistrationStore,
        appId = appContext.packageName
    )
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
        storePassphraseStore = matrixStorePassphraseStore,
        pushRegistrar = matrixPushRegistrar
    )
    val nativeMatrixRtcCallService = NativeMatrixRtcCallService(
        environment = MatrixClientNativeMatrixRtcCallEnvironment(
            matrixClientService = matrixClientService,
            context = appContext
        )
    )
    val incomingCallManager = MatrixRtcIncomingCallManager(
        context = appContext,
        matrixClientService = matrixClientService,
        nativeMatrixRtcCallService = nativeMatrixRtcCallService
    )
    val matrixMediaLoader = MatrixMediaLoader(
        matrixClientService = matrixClientService,
        context = appContext
    )
    val matrixAudioMediaLoader = MatrixAudioMediaLoader(
        matrixClientService = matrixClientService,
        context = appContext
    )
    val audioPlaybackController = AudioPlaybackController(
        audioMediaLoader = matrixAudioMediaLoader
    )
    val voiceRecorderController = VoiceRecorderController(appContext)
    val outgoingOutboxService = OutgoingOutboxService(
        matrixClientService = matrixClientService,
        localCacheRepository = localCacheRepository,
        context = appContext
    )
}
