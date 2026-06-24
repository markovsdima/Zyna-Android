package com.zyna.app.data.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.zyna.app.data.outgoing.OutgoingMediaStorage
import com.zyna.app.data.outgoing.OutgoingVoiceDraft
import java.io.File
import kotlin.math.log10

sealed interface VoiceRecorderState {
    data object Idle : VoiceRecorderState

    data class Recording(
        val durationMillis: Long,
        val waveform: List<Float>
    ) : VoiceRecorderState

    data class Finished(
        val localPath: String,
        val mimeType: String,
        val sizeBytes: Long,
        val durationMillis: Long,
        val waveform: List<Float>
    ) : VoiceRecorderState {
        fun toDraft(): OutgoingVoiceDraft {
            return OutgoingVoiceDraft(
                localPath = localPath,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                durationMillis = durationMillis,
                waveform = waveform
            )
        }
    }

    data class Error(
        val message: String
    ) : VoiceRecorderState
}

class VoiceRecorderController(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<(VoiceRecorderState) -> Unit>()
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var recordingStartedAtMillis = 0L
    private var waveform = mutableListOf<Float>()
    private var state: VoiceRecorderState = VoiceRecorderState.Idle

    private val sampleRunnable = object : Runnable {
        override fun run() {
            sampleRecorder()
            if (state is VoiceRecorderState.Recording) {
                mainHandler.postDelayed(this, SAMPLE_INTERVAL_MS)
            }
        }
    }

    fun addListener(listener: (VoiceRecorderState) -> Unit): AutoCloseable {
        runOnMain {
            listeners += listener
            listener(state)
        }
        return AutoCloseable {
            runOnMain {
                listeners -= listener
            }
        }
    }

    fun stateSnapshot(): VoiceRecorderState {
        return state
    }

    fun startRecording() {
        runOnMain {
            if (state is VoiceRecorderState.Recording) {
                return@runOnMain
            }
            discardFinishedFileIfNeeded()
            releaseRecorder(deleteFile = true)

            val file = OutgoingMediaStorage.newVoiceFile(appContext.filesDir)
            try {
                val nextRecorder = newRecorder()
                recorder = nextRecorder
                outputFile = file
                nextRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                nextRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                nextRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                nextRecorder.setAudioSamplingRate(SAMPLE_RATE_HZ)
                nextRecorder.setAudioChannels(CHANNEL_COUNT)
                nextRecorder.setAudioEncodingBitRate(BIT_RATE_BPS)
                nextRecorder.setOutputFile(file.absolutePath)
                nextRecorder.prepare()
                nextRecorder.start()

                recordingStartedAtMillis = SystemClock.elapsedRealtime()
                waveform = mutableListOf()
                publish(VoiceRecorderState.Recording(durationMillis = 0L, waveform = emptyList()))
                mainHandler.removeCallbacks(sampleRunnable)
                mainHandler.postDelayed(sampleRunnable, SAMPLE_INTERVAL_MS)
            } catch (error: Throwable) {
                Log.d(TAG, "Voice recording start failed", error)
                releaseRecorder(deleteFile = true)
                publish(VoiceRecorderState.Error(error.message ?: error.javaClass.simpleName))
            }
        }
    }

    fun stopRecording() {
        runOnMain {
            val activeRecorder = recorder ?: return@runOnMain
            val file = outputFile
            val durationMillis = elapsedRecordingMillis().coerceAtLeast(1L)
            mainHandler.removeCallbacks(sampleRunnable)
            try {
                activeRecorder.stop()
                activeRecorder.release()
                recorder = null
                outputFile = null
                val completedFile = file?.takeIf { it.isFile && it.length() > 0L }
                    ?: error("Voice file is empty")
                publish(
                    VoiceRecorderState.Finished(
                        localPath = completedFile.absolutePath,
                        mimeType = MIME_TYPE,
                        sizeBytes = completedFile.length(),
                        durationMillis = durationMillis,
                        waveform = waveform.toFinishedWaveformSnapshot()
                    )
                )
            } catch (error: Throwable) {
                Log.d(TAG, "Voice recording stop failed", error)
                releaseRecorder(deleteFile = true)
                publish(VoiceRecorderState.Error(error.message ?: error.javaClass.simpleName))
            }
        }
    }

    fun cancelRecording() {
        runOnMain {
            releaseRecorder(deleteFile = true)
            discardFinishedFileIfNeeded()
            publish(VoiceRecorderState.Idle)
        }
    }

    fun consumeFinished() {
        runOnMain {
            if (state is VoiceRecorderState.Finished) {
                publish(VoiceRecorderState.Idle)
            }
        }
    }

    fun clear() {
        runOnMain {
            releaseRecorder(deleteFile = true)
            discardFinishedFileIfNeeded()
            publish(VoiceRecorderState.Idle)
        }
    }

    private fun sampleRecorder() {
        val activeRecorder = recorder ?: return
        val durationMillis = elapsedRecordingMillis()
        val sample = runCatching { activeRecorder.maxAmplitude }
            .getOrDefault(0)
            .toNormalizedVoiceSample()
        waveform += sample
        publish(
            VoiceRecorderState.Recording(
                durationMillis = durationMillis,
                waveform = waveform.toRecordingWaveformSnapshot()
            )
        )
    }

    private fun elapsedRecordingMillis(): Long {
        return (SystemClock.elapsedRealtime() - recordingStartedAtMillis).coerceAtLeast(0L)
    }

    private fun releaseRecorder(deleteFile: Boolean) {
        mainHandler.removeCallbacks(sampleRunnable)
        recorder?.let { activeRecorder ->
            runCatching { activeRecorder.stop() }
            runCatching { activeRecorder.release() }
        }
        recorder = null
        val file = outputFile
        outputFile = null
        recordingStartedAtMillis = 0L
        waveform = mutableListOf()
        if (deleteFile) {
            runCatching { file?.delete() }
        }
    }

    private fun discardFinishedFileIfNeeded() {
        val finished = state as? VoiceRecorderState.Finished ?: return
        runCatching { File(finished.localPath).delete() }
    }

    private fun publish(next: VoiceRecorderState) {
        if (state == next) {
            return
        }
        state = next
        listeners.toList().forEach { listener -> listener(next) }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(appContext)
        } else {
            MediaRecorder()
        }
    }

    private fun Int.toNormalizedVoiceSample(): Float {
        if (this <= 0) {
            return 0f
        }
        val ratio = (toFloat() / MAX_AMPLITUDE).coerceIn(0.0001f, 1f)
        val decibels = 20f * log10(ratio)
        return ((decibels + VOICE_DB_FLOOR) / VOICE_DB_FLOOR).coerceIn(0f, 1f)
    }

    private fun List<Float>.toRecordingWaveformSnapshot(): List<Float> {
        val fromIndex = (size - RECORDING_WAVEFORM_SAMPLE_LIMIT).coerceAtLeast(0)
        return subList(fromIndex, size).toList()
    }

    private fun List<Float>.toFinishedWaveformSnapshot(): List<Float> {
        if (size <= FINISHED_WAVEFORM_SAMPLE_LIMIT) {
            return toList()
        }

        return List(FINISHED_WAVEFORM_SAMPLE_LIMIT) { bucket ->
            val start = bucket * size / FINISHED_WAVEFORM_SAMPLE_LIMIT
            val end = ((bucket + 1) * size / FINISHED_WAVEFORM_SAMPLE_LIMIT)
                .coerceAtMost(size)
                .coerceAtLeast(start + 1)
            var peak = 0f
            for (index in start until end) {
                val sample = this[index]
                if (sample > peak) {
                    peak = sample
                }
            }
            peak
        }
    }

    private companion object {
        const val TAG = "VoiceRecorder"
        const val MIME_TYPE = "audio/mp4"
        const val SAMPLE_RATE_HZ = 44_100
        const val CHANNEL_COUNT = 1
        const val BIT_RATE_BPS = 64_000
        const val SAMPLE_INTERVAL_MS = 67L
        const val MAX_AMPLITUDE = 32_767f
        const val VOICE_DB_FLOOR = 50f
        const val RECORDING_WAVEFORM_SAMPLE_LIMIT = 96
        const val FINISHED_WAVEFORM_SAMPLE_LIMIT = 100
    }
}
