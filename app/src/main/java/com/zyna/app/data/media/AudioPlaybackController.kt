package com.zyna.app.data.media

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.zyna.app.data.matrix.MatrixAudioInfo
import java.io.File
import kotlin.math.max

data class AudioPlaybackSnapshot(
    val messageId: String? = null,
    val sourceJson: String? = null,
    val isLoading: Boolean = false,
    val isPlaying: Boolean = false,
    val currentPositionMillis: Long = 0L,
    val durationMillis: Long = 0L
) {
    fun isFor(messageId: String, audioInfo: MatrixAudioInfo): Boolean {
        return this.messageId == messageId && sourceJson == audioInfo.sourceJson
    }
}

class AudioPlaybackController(
    private val audioMediaLoader: MatrixAudioMediaLoader
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<(AudioPlaybackSnapshot) -> Unit>()
    private var snapshot = AudioPlaybackSnapshot()
    private var mediaPlayer: MediaPlayer? = null
    private var loadHandle: AutoCloseable? = null
    private var generation = 0L

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgressFromPlayer()
            if (snapshot.isPlaying) {
                mainHandler.postDelayed(this, PROGRESS_TICK_MS)
            }
        }
    }

    fun addListener(listener: (AudioPlaybackSnapshot) -> Unit): AutoCloseable {
        runOnMain {
            listeners += listener
            listener(snapshot)
        }
        return AutoCloseable {
            runOnMain {
                listeners -= listener
            }
        }
    }

    fun toggle(messageId: String, audioInfo: MatrixAudioInfo) {
        runOnMain {
            if (snapshot.isFor(messageId, audioInfo)) {
                when {
                    snapshot.isLoading -> stop()
                    snapshot.isPlaying -> pause()
                    mediaPlayer != null -> resume()
                    else -> start(messageId, audioInfo)
                }
            } else {
                start(messageId, audioInfo)
            }
        }
    }

    fun stop() {
        runOnMain {
            generation += 1
            loadHandle?.closeSafely()
            loadHandle = null
            releasePlayer()
            publish(AudioPlaybackSnapshot())
        }
    }

    private fun start(messageId: String, audioInfo: MatrixAudioInfo) {
        generation += 1
        val requestGeneration = generation
        loadHandle?.closeSafely()
        loadHandle = null
        releasePlayer()
        publish(
            AudioPlaybackSnapshot(
                messageId = messageId,
                sourceJson = audioInfo.sourceJson,
                isLoading = true,
                durationMillis = audioInfo.durationMillis ?: 0L
            )
        )
        loadHandle = audioMediaLoader.loadAudioFile(audioInfo) { file ->
            if (requestGeneration != generation) {
                return@loadAudioFile
            }
            loadHandle?.closeSafely()
            loadHandle = null
            if (file == null) {
                publish(AudioPlaybackSnapshot())
                return@loadAudioFile
            }
            prepareAndPlay(
                file = file,
                messageId = messageId,
                audioInfo = audioInfo,
                requestGeneration = requestGeneration
            )
        }
    }

    private fun prepareAndPlay(
        file: File,
        messageId: String,
        audioInfo: MatrixAudioInfo,
        requestGeneration: Long
    ) {
        val player = MediaPlayer()
        mediaPlayer = player
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(
                        if (audioInfo.isVoice) {
                            AudioAttributes.CONTENT_TYPE_SPEECH
                        } else {
                            AudioAttributes.CONTENT_TYPE_MUSIC
                        }
                    )
                    .build()
            )
            player.setDataSource(file.absolutePath)
            player.setOnPreparedListener { prepared ->
                if (requestGeneration != generation || mediaPlayer !== prepared) {
                    prepared.releaseSafely()
                    return@setOnPreparedListener
                }
                try {
                    prepared.start()
                    publish(
                        AudioPlaybackSnapshot(
                            messageId = messageId,
                            sourceJson = audioInfo.sourceJson,
                            isPlaying = true,
                            currentPositionMillis = prepared.safeCurrentPositionMillis(),
                            durationMillis = prepared.safeDurationMillis(audioInfo)
                        )
                    )
                    scheduleProgressTick()
                } catch (error: Throwable) {
                    Log.d(TAG, "Audio playback start failed", error)
                    stop()
                }
            }
            player.setOnCompletionListener { completed ->
                if (mediaPlayer !== completed) {
                    return@setOnCompletionListener
                }
                mainHandler.removeCallbacks(progressRunnable)
                val duration = completed.safeDurationMillis(audioInfo)
                publish(
                    snapshot.copy(
                        isLoading = false,
                        isPlaying = false,
                        currentPositionMillis = duration,
                        durationMillis = duration
                    )
                )
            }
            player.setOnErrorListener { failed, _, _ ->
                if (mediaPlayer === failed) {
                    Log.d(TAG, "Audio playback failed")
                    stop()
                }
                true
            }
            player.prepareAsync()
        } catch (error: Throwable) {
            Log.d(TAG, "Audio playback prepare failed", error)
            if (mediaPlayer === player) {
                mediaPlayer = null
            }
            player.releaseSafely()
            publish(AudioPlaybackSnapshot())
        }
    }

    private fun pause() {
        val player = mediaPlayer ?: return
        try {
            if (player.isPlaying) {
                player.pause()
            }
            mainHandler.removeCallbacks(progressRunnable)
            publish(
                snapshot.copy(
                    isLoading = false,
                    isPlaying = false,
                    currentPositionMillis = player.safeCurrentPositionMillis(),
                    durationMillis = max(snapshot.durationMillis, player.safeDurationMillis())
                )
            )
        } catch (error: Throwable) {
            Log.d(TAG, "Audio playback pause failed", error)
            stop()
        }
    }

    private fun resume() {
        val player = mediaPlayer ?: return
        try {
            if (snapshot.isEnded()) {
                player.seekTo(0)
            }
            player.start()
            publish(
                snapshot.copy(
                    isLoading = false,
                    isPlaying = true,
                    currentPositionMillis = player.safeCurrentPositionMillis(),
                    durationMillis = max(snapshot.durationMillis, player.safeDurationMillis())
                )
            )
            scheduleProgressTick()
        } catch (error: Throwable) {
            Log.d(TAG, "Audio playback resume failed", error)
            stop()
        }
    }

    private fun updateProgressFromPlayer() {
        val player = mediaPlayer ?: return
        if (!snapshot.isPlaying) {
            return
        }
        publish(
            snapshot.copy(
                currentPositionMillis = player.safeCurrentPositionMillis(),
                durationMillis = max(snapshot.durationMillis, player.safeDurationMillis())
            )
        )
    }

    private fun scheduleProgressTick() {
        mainHandler.removeCallbacks(progressRunnable)
        mainHandler.postDelayed(progressRunnable, PROGRESS_TICK_MS)
    }

    private fun releasePlayer() {
        mainHandler.removeCallbacks(progressRunnable)
        mediaPlayer?.releaseSafely()
        mediaPlayer = null
    }

    private fun publish(next: AudioPlaybackSnapshot) {
        if (snapshot == next) {
            return
        }
        snapshot = next
        listeners.toList().forEach { listener -> listener(next) }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun AudioPlaybackSnapshot.isEnded(): Boolean {
        return durationMillis > 0L && currentPositionMillis >= durationMillis - END_REPLAY_TOLERANCE_MS
    }

    private fun MediaPlayer.safeDurationMillis(audioInfo: MatrixAudioInfo? = null): Long {
        return runCatching { duration.toLong().takeIf { it > 0L } }
            .getOrNull()
            ?: audioInfo?.durationMillis
            ?: 0L
    }

    private fun MediaPlayer.safeCurrentPositionMillis(): Long {
        return runCatching { currentPosition.toLong().coerceAtLeast(0L) }
            .getOrDefault(0L)
    }

    private fun MediaPlayer.releaseSafely() {
        runCatching { release() }
    }

    private fun AutoCloseable.closeSafely() {
        runCatching { close() }
    }

    private companion object {
        const val TAG = "AudioPlaybackController"
        const val PROGRESS_TICK_MS = 250L
        const val END_REPLAY_TOLERANCE_MS = 200L
    }
}
