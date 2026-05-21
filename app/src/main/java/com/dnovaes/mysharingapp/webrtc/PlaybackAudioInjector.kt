package com.dnovaes.mysharingapp.webrtc

import android.os.Build
import androidx.annotation.RequiresApi
import org.webrtc.audio.AudioRecordDataCallback
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Replaces microphone PCM in the WebRTC pipeline with playback-captured system audio.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class PlaybackAudioInjector(
    private val playbackCapture: PlaybackAudioCapture,
) : AudioRecordDataCallback {

    override fun onAudioDataRecorded(
        audioFormat: Int,
        sampleRate: Int,
        channelCount: Int,
        audioBuffer: ByteBuffer,
    ) {
        val needed = audioBuffer.remaining()
        val playback = playbackCapture.poll(needed)
        audioBuffer.clear()
        if (playback != null && playback.remaining() > 0) {
            val bytes = ByteArray(minOf(needed, playback.remaining()))
            playback.get(bytes)
            audioBuffer.put(bytes)
        } else {
            audioBuffer.put(ByteArray(needed))
        }
        audioBuffer.order(ByteOrder.LITTLE_ENDIAN)
    }
}
