package com.dnovaes.mysharingapp.webrtc

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures device playback (system/app) audio using [MediaProjection] on API 29+.
 * Requires [android.Manifest.permission.RECORD_AUDIO] at runtime.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class PlaybackAudioCapture(
    mediaProjection: MediaProjection,
) {
    private val projection = mediaProjection
    private val running = AtomicBoolean(false)
    private val capturing = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var readBufferSize = 0
    private val queue = ArrayBlockingQueue<ByteArray>(32)

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return capturing.get()

        val record = createAudioRecord()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed, state=${record?.state ?: "null"}")
            record?.release()
            running.set(false)
            return false
        }

        audioRecord = record
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "startRecording failed", e)
            record.release()
            audioRecord = null
            running.set(false)
            return false
        }

        capturing.set(true)
        captureThread = Thread(
            {
                val buffer = ByteArray(readBufferSize)
                while (running.get()) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        queue.offer(buffer.copyOf(read))
                    }
                }
                capturing.set(false)
                try {
                    record.stop()
                } catch (_: IllegalStateException) {
                }
                record.release()
                audioRecord = null
            },
            "playback-audio-capture",
        ).also { it.start() }
        return true
    }

    fun stop() {
        running.set(false)
        captureThread?.join(1_500)
        captureThread = null
        queue.clear()
        capturing.set(false)
    }

    fun isCapturing(): Boolean = capturing.get()

    /**
     * Returns up to [sizeBytes] of PCM 16-bit little-endian audio for WebRTC injection.
     */
    fun poll(sizeBytes: Int): ByteBuffer? {
        val chunk = queue.poll() ?: return null
        val copy = chunk.copyOf(minOf(chunk.size, sizeBytes))
        return ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun createAudioRecord(): AudioRecord? {
        return try {
            val sampleRate = 48_000
            val channelMask = AudioFormat.CHANNEL_IN_STEREO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
            if (minBuffer <= 0) {
                Log.e(TAG, "Invalid min buffer size: $minBuffer")
                return null
            }
            readBufferSize = minBuffer
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build()
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuffer * 4)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioRecord", e)
            null
        }
    }

    companion object {
        private const val TAG = "PlaybackAudioCapture"
    }
}
