package com.dnovaes.mysharingapp.webrtc

import android.content.Context
import android.media.AudioRecord
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.audio.WebRtcNativeAudioBridge
import java.lang.reflect.Field

/**
 * Two capture pipelines (mutually exclusive — Android allows one playback-capture policy at a time):
 * - **Mic**: WebRTC's [AudioRecord] (VOICE_COMMUNICATION) — kept alive, only [AudioRecord.stop] when muted
 * - **Playback**: [PlaybackAudioCapture] (AudioPlaybackCaptureConfiguration)
 */
object WebRtcAudioInjector {

    private const val TAG = "WebRtcAudioInjector"
    private const val WEBRTC_AUDIO_RECORD_CLASS = "org.webrtc.audio.WebRtcAudioRecord"

    private val mainHandler = Handler(Looper.getMainLooper())

    private var paddedLoop: WebRtcPaddedCaptureLoop? = null
    private var usingPaddedLoop = false
    private var micAudioRecord: AudioRecord? = null
    private var captureSampleRate = 0
    private var captureChannelCount = 0
    private var captureBufferBytes = 0
    private var appContext: Context? = null
    private var lastNonZeroPeakAtMs = 0L

    fun notifyCapturePeak(peak: Int) {
        if (peak > 0) {
            lastNonZeroPeakAtMs = SystemClock.elapsedRealtime()
        }
    }

    fun onWebRtcAudioRecordStart(
        context: Context,
        audioDeviceModule: JavaAudioDeviceModule,
        playbackCapture: PlaybackAudioCapture?,
        muteState: MicrophoneMuteState,
    ) {
        if (usingPaddedLoop && paddedLoop != null) {
            Log.d(TAG, "Ignoring duplicate onWebRtcAudioRecordStart (loop already running)")
            return
        }
        appContext = context
        val webRtcAudioRecord = getWebRtcAudioRecord(audioDeviceModule) ?: run {
            applyAdmMuteOnly(audioDeviceModule, muteState.microphoneMuted.get())
            return
        }

        val micRecord = getAudioRecordField(webRtcAudioRecord) ?: run {
            applyAdmMuteOnly(audioDeviceModule, muteState.microphoneMuted.get())
            return
        }

        micAudioRecord = micRecord
        captureSampleRate = micRecord.sampleRate
        captureChannelCount = micRecord.channelCount
        captureBufferBytes = getByteBufferCapacity(webRtcAudioRecord)

        audioDeviceModule.setMicrophoneMute(false)
        WebRtcNativeAudioBridge.setMicrophoneMuteField(webRtcAudioRecord, false)

        val playbackRate = appContext?.let { ctx ->
            PlaybackAudioConfig.preferredSampleRateHz(ctx, captureSampleRate)
        } ?: captureSampleRate
        playbackCapture?.configure(playbackRate, captureChannelCount, captureBufferBytes)
        if (playbackRate != captureSampleRate) {
            Log.w(
                TAG,
                "Playback capture @ ${playbackRate}Hz, WebRTC mic buffer @ ${captureSampleRate}Hz " +
                    "(frame=${captureBufferBytes}B) — rates should match for best quality",
            )
        }
        playbackCapture?.setOnPlaybackActiveListener {
            if (!muteState.microphoneMuted.get() || !usingPaddedLoop) return@setOnPlaybackActiveListener
            Log.d(
                TAG,
                "External media playback detected — use in-app test tone; " +
                    "YouTube may not appear in the capture mix on this device",
            )
        }

        WebRtcPaddedCaptureLoop.scheduleHandoff(
            webRtcAudioRecord = webRtcAudioRecord,
            micAudioRecord = micRecord,
            playbackCapture = playbackCapture,
            microphoneMuteState = muteState,
            onStarted = { loop ->
                paddedLoop = loop
                usingPaddedLoop = true
                Log.d(TAG, "Dual pipeline active (custom loop)")
                if (muteState.microphoneMuted.get()) {
                    onHandoffPlaybackReady(playbackCapture)
                }
            },
            onAborted = {
                Log.e(TAG, "Handoff failed — no custom capture")
                usingPaddedLoop = false
                applyAdmMuteOnly(audioDeviceModule, muteState.microphoneMuted.get())
            },
        )
    }

    fun applyMicrophoneMute(
        context: Context,
        audioDeviceModule: JavaAudioDeviceModule,
        playbackCapture: PlaybackAudioCapture?,
        microphoneMuteState: MicrophoneMuteState,
        microphoneMuted: Boolean,
    ) {
        appContext = context
        microphoneMuteState.microphoneMuted.set(microphoneMuted)
        audioDeviceModule.setMicrophoneMute(false)

        if (!usingPaddedLoop || paddedLoop == null) {
            applyAdmMuteOnly(audioDeviceModule, false)
            return
        }

        val mic = micAudioRecord ?: return
        if (microphoneMuted) {
            switchToPlaybackPipeline(mic, playbackCapture)
        } else {
            switchToMicPipeline(mic, playbackCapture)
        }
        Log.d(TAG, "Pipeline → ${if (microphoneMuted) "playback" else "microphone"}")
    }

    fun refreshPlaybackCapture(playbackCapture: PlaybackAudioCapture?) {
        playbackCapture ?: return
        playbackCapture.resetRefreshCycle()
        playbackCapture.refreshAndStartOnMain()
        Log.d(TAG, "Playback capture refresh (USAGE_MEDIA)")
    }

    fun clear(context: Context? = appContext) {
        WebRtcPaddedCaptureLoop.cancelPendingHandoff()
        paddedLoop = null
        usingPaddedLoop = false
        micAudioRecord?.let { stopRecordingQuietly(it) }
        micAudioRecord = null
        context?.let { AudioCaptureRouting.release(it) }
        appContext = null
    }

    private fun onHandoffPlaybackReady(playbackCapture: PlaybackAudioCapture?) {
        runOnMain { activatePlaybackPipeline(playbackCapture) }
    }

    private fun switchToPlaybackPipeline(mic: AudioRecord, playbackCapture: PlaybackAudioCapture?) {
        runOnMain {
            appContext?.let { AudioCaptureRouting.enterPlaybackCaptureMode(it) }
            stopRecordingQuietly(mic)
            activatePlaybackPipeline(playbackCapture)
        }
    }

    private fun switchToMicPipeline(mic: AudioRecord, playbackCapture: PlaybackAudioCapture?) {
        playbackCapture?.releaseCaptureBlocking()
        appContext?.let { AudioCaptureRouting.enterMicrophoneCaptureMode(it, switchingFromPlayback = true) }
        stopRecordingQuietly(mic)
        val ok = startRecordingQuietly(mic)
        Log.d(
            TAG,
            "Mic pipeline active startRecording=$ok state=${mic.recordingState} " +
                "rate=${mic.sampleRate} ch=${mic.channelCount}",
        )
    }

    internal fun activatePipelineForHandoff(
        mic: AudioRecord,
        playbackCapture: PlaybackAudioCapture?,
        microphoneMuted: Boolean,
    ): Boolean {
        return if (microphoneMuted) {
            appContext?.let { AudioCaptureRouting.enterPlaybackCaptureMode(it) }
            stopRecordingQuietly(mic)
            playbackCapture?.resetRefreshCycle()
            playbackCapture?.refreshAndStartBlocking() == true
        } else {
            playbackCapture?.releaseCaptureBlocking()
            appContext?.let { AudioCaptureRouting.enterMicrophoneCaptureMode(it, switchingFromPlayback = true) }
            stopRecordingQuietly(mic)
            startRecordingQuietly(mic)
        }
    }

    private fun applyAdmMuteOnly(audioDeviceModule: JavaAudioDeviceModule, muted: Boolean) {
        audioDeviceModule.setMicrophoneMute(muted)
        Log.d(TAG, "ADM setMicrophoneMute($muted)")
    }

    private fun getByteBufferCapacity(webRtcAudioRecord: Any): Int {
        return WebRtcNativeAudioBridge.getCaptureByteBuffer(webRtcAudioRecord)?.capacity() ?: 0
    }

    private fun getWebRtcAudioRecord(audioDeviceModule: JavaAudioDeviceModule): Any? {
        return try {
            val field: Field = JavaAudioDeviceModule::class.java.getDeclaredField("audioInput")
            field.isAccessible = true
            field.get(audioDeviceModule)
        } catch (_: ReflectiveOperationException) {
            null
        }
    }

    private fun getAudioRecordField(webRtcAudioRecord: Any): AudioRecord? {
        return try {
            val field: Field = Class.forName(WEBRTC_AUDIO_RECORD_CLASS)
                .getDeclaredField("audioRecord")
            field.isAccessible = true
            field.get(webRtcAudioRecord) as? AudioRecord
        } catch (_: ReflectiveOperationException) {
            null
        }
    }

    private fun stopRecordingQuietly(record: AudioRecord) {
        try {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        } catch (_: IllegalStateException) {
        }
    }

    private fun startRecordingQuietly(record: AudioRecord): Boolean {
        return try {
            record.startRecording()
            record.recordingState == AudioRecord.RECORDSTATE_RECORDING
        } catch (_: IllegalStateException) {
            false
        }
    }

    private fun activatePlaybackPipeline(playbackCapture: PlaybackAudioCapture?) {
        playbackCapture ?: return
        playbackCapture.resetRefreshCycle()
        val ok = playbackCapture.refreshAndStartBlocking()
        Log.d(TAG, "Playback pipeline ready ok=$ok")
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}
