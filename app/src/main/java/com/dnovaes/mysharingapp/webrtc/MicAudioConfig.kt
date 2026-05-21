package com.dnovaes.mysharingapp.webrtc

import android.media.AudioAttributes
import android.media.MediaRecorder
import android.os.Build
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * WebRTC microphone capture tuning (unmuted / voice path).
 */
object MicAudioConfig {

    const val INPUT_SAMPLE_RATE_HZ = PlaybackAudioConfig.PREFERRED_SAMPLE_RATE_HZ

    fun applyTo(builder: JavaAudioDeviceModule.Builder): JavaAudioDeviceModule.Builder {
        builder.setSampleRate(INPUT_SAMPLE_RATE_HZ)
        builder.setInputSampleRate(INPUT_SAMPLE_RATE_HZ)
        builder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        builder.setUseStereoInput(true)
        builder.setUseStereoOutput(true)
        // HW voice processing improves clarity; WebRTC disables if unsupported on device.
        builder.setUseHardwareNoiseSuppressor(true)
        builder.setUseHardwareAcousticEchoCanceler(true)
        builder.setUseLowLatency(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
        }
        return builder
    }

    fun logMicRecord(mic: android.media.AudioRecord, webRtcFrameBytes: Int) {
        val expectedBytes = PlaybackAudioConfig.frameBytesForRate(
            mic.sampleRate,
            mic.channelCount,
        )
        if (expectedBytes != webRtcFrameBytes) {
            android.util.Log.w(
                "MicAudioConfig",
                "WebRTC frame=$webRtcFrameBytes B but mic ${mic.sampleRate}Hz " +
                    "${mic.channelCount}ch expects ~$expectedBytes B per 10ms",
            )
        }
    }
}
