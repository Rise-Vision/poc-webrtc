package com.dnovaes.mysharingapp.webrtc

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Surface
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.ThreadUtils
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * Screen capturer that reuses an existing [MediaProjection] from
 * [com.dnovaes.mysharingapp.service.MediaProjectionConsent].
 *
 * Do not use [org.webrtc.ScreenCapturerAndroid] with the same consent [Intent] — Android allows only
 * one [MediaProjectionManager.getMediaProjection] per result token.
 */
class ExistingProjectionCapturer(
    private val mediaProjection: MediaProjection,
) : VideoCapturer, VideoSink {

    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var capturerObserver: CapturerObserver? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var width = 0
    private var height = 0
    private var virtualDisplayDpi = 0

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper,
        context: android.content.Context,
        capturerObserver: CapturerObserver,
    ) {
        this.surfaceTextureHelper = surfaceTextureHelper
        this.capturerObserver = capturerObserver
        virtualDisplayDpi = context.resources.displayMetrics.densityDpi
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        ThreadUtils.checkIsOnMainThread()
        this.width = width
        this.height = height
        val helper = surfaceTextureHelper ?: return
        helper.setTextureSize(width, height)
        val surfaceTexture = helper.surfaceTexture
        surfaceTexture.setDefaultBufferSize(width, height)
        val surface = Surface(surfaceTexture)
        try {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                width,
                height,
                virtualDisplayDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null,
            )
            helper.startListening(this)
            capturerObserver?.onCapturerStarted(true)
            Log.d(TAG, "Virtual display started ${width}x$height")
        } catch (e: SecurityException) {
            Log.e(TAG, "createVirtualDisplay failed", e)
            capturerObserver?.onCapturerStarted(false)
        }
    }

    override fun stopCapture() {
        ThreadUtils.checkIsOnMainThread()
        surfaceTextureHelper?.stopListening()
        virtualDisplay?.release()
        virtualDisplay = null
        capturerObserver?.onCapturerStopped()
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        ThreadUtils.checkIsOnMainThread()
        if (virtualDisplay == null) return
        stopCapture()
        startCapture(width, height, framerate)
    }

    override fun dispose() {
        stopCapture()
    }

    override fun isScreencast(): Boolean = true

    override fun onFrame(frame: VideoFrame) {
        capturerObserver?.onFrameCaptured(frame)
    }

    companion object {
        private const val TAG = "ExistingProjectionCap"
        private const val VIRTUAL_DISPLAY_NAME = "WebRTC_ScreenCapture"
    }
}
