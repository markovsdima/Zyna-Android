package com.zyna.app.ui.calls

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.zyna.app.data.calls.matrixrtc.MatrixRtcLiveKitVideoTrackReference
import io.livekit.android.renderer.TextureViewRenderer
import livekit.org.webrtc.RendererCommon

internal class NativeMatrixRtcLiveKitVideoView(context: Context) : FrameLayout(context) {
    private val renderer = TextureViewRenderer(context)
    private var rendererInitialized = false
    private var currentTrack: MatrixRtcLiveKitVideoTrackReference? = null

    init {
        setBackgroundColor(android.graphics.Color.BLACK)
        addView(
            renderer,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        visibility = View.GONE
    }

    fun setVideoTrack(
        track: MatrixRtcLiveKitVideoTrackReference?,
        mirror: Boolean
    ) {
        if (currentTrack?.id == track?.id) {
            renderer.setMirror(mirror)
            visibility = if (track == null) View.GONE else View.VISIBLE
            return
        }

        currentTrack?.removeRenderer(renderer)
        currentTrack = null

        if (track == null) {
            renderer.clearImage()
            visibility = View.GONE
            return
        }

        if (!rendererInitialized) {
            track.initializeRenderer(renderer)
            renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            rendererInitialized = true
        }
        renderer.setMirror(mirror)
        track.addRenderer(renderer)
        currentTrack = track
        visibility = View.VISIBLE
    }

    override fun onDetachedFromWindow() {
        currentTrack?.removeRenderer(renderer)
        currentTrack = null
        if (rendererInitialized) {
            renderer.release()
            rendererInitialized = false
        }
        super.onDetachedFromWindow()
    }
}
