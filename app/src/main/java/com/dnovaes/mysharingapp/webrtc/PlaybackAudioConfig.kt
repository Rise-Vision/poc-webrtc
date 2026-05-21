package com.dnovaes.mysharingapp.webrtc

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build

/**
 * Tuning for [PlaybackAudioCapture] aligned with WebRTC's 10 ms PCM frames.
 */
object PlaybackAudioConfig {

    const val PREFERRED_SAMPLE_RATE_HZ = 48_000
    const val CHANNEL_COUNT = 2
    const val BYTES_PER_SAMPLE = 2 // PCM_16BIT

    /** WebRTC capture frame duration (ms). */
    const val WEBRTC_FRAME_MS = 10

    fun preferredSampleRateHz(context: Context, webRtcMicSampleRate: Int): Int {
        val deviceRate = deviceOutputSampleRateHz(context)
        if (webRtcMicSampleRate == deviceRate) return webRtcMicSampleRate
        if (isRateSupported(webRtcMicSampleRate)) return webRtcMicSampleRate
        if (isRateSupported(deviceRate)) return deviceRate
        if (isRateSupported(PREFERRED_SAMPLE_RATE_HZ)) return PREFERRED_SAMPLE_RATE_HZ
        return webRtcMicSampleRate
    }

    fun frameBytesForRate(sampleRateHz: Int, channelCount: Int = CHANNEL_COUNT): Int {
        return sampleRateHz * WEBRTC_FRAME_MS * channelCount * BYTES_PER_SAMPLE / 1000
    }

    /**
     * Device capture buffer: a few 10 ms frames above [AudioRecord.getMinBufferSize], not oversized.
     */
    fun captureBufferBytes(sampleRateHz: Int, webRtcFrameBytes: Int): Int {
        val channelMask = AudioFormat.CHANNEL_IN_STEREO
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRateHz,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return webRtcFrameBytes * 8
        val frameBytes = frameBytesForRate(sampleRateHz)
        return maxOf(minBuffer, frameBytes * 8)
    }

    private fun deviceOutputSampleRateHz(context: Context): Int {
        val manager = context.getSystemService(AudioManager::class.java) ?: return PREFERRED_SAMPLE_RATE_HZ
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            manager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
                ?: PREFERRED_SAMPLE_RATE_HZ
        } else {
            PREFERRED_SAMPLE_RATE_HZ
        }
    }

    private fun isRateSupported(sampleRateHz: Int): Boolean {
        val min = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        return min > 0
    }
}
