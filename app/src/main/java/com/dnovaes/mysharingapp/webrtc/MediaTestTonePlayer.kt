package com.dnovaes.mysharingapp.webrtc

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.sin

/**
 * Plays a [USAGE_MEDIA] tone from this app — useful to verify playback capture picks up *any* audio.
 */
class MediaTestTonePlayer {
    private var audioTrack: AudioTrack? = null
    private var playThread: Thread? = null
    @Volatile
    private var playing = false

    fun start(sampleRate: Int = 48_000) {
        stop()
        val channelMask = AudioFormat.CHANNEL_OUT_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minBuffer <= 0) return

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = track
        playing = true
        playThread = Thread({
            val frequencyHz = 440.0
            val frameSamples = 480
            val buffer = ShortArray(frameSamples * 2)
            var phase = 0.0
            track.play()
            Log.d(TAG, "Test tone playing @ ${sampleRate}Hz USAGE_MEDIA")
            while (playing) {
                var i = 0
                while (i < buffer.size) {
                    val sample = (sin(phase) * Short.MAX_VALUE * 0.25).toInt().toShort()
                    buffer[i] = sample
                    buffer[i + 1] = sample
                    phase += 2.0 * Math.PI * frequencyHz / sampleRate
                    if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI
                    i += 2
                }
                track.write(buffer, 0, buffer.size)
            }
        }, "media-test-tone").apply { start() }
    }

    fun stop() {
        playing = false
        playThread?.join(500)
        playThread = null
        audioTrack?.let { track ->
            try {
                track.stop()
            } catch (_: IllegalStateException) {
            }
            track.release()
        }
        audioTrack = null
    }

    companion object {
        private const val TAG = "MediaTestTonePlayer"
    }
}
