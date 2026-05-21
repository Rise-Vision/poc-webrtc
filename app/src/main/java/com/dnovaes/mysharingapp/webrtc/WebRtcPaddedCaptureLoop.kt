package com.dnovaes.mysharingapp.webrtc

import android.media.AudioRecord
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import org.webrtc.audio.WebRtcNativeAudioBridge
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Feeds WebRTC from mic or playback [AudioRecord].
 */
internal class WebRtcPaddedCaptureLoop(
    private val webRtcAudioRecord: Any,
    private val micAudioRecord: AudioRecord,
    private val playbackCapture: PlaybackAudioCapture?,
    private val microphoneMuteState: MicrophoneMuteState,
) : Runnable {

    private val running = AtomicBoolean(true)
    private val scratch: ByteArray
    private var silentPlaybackStreak = 0
    private var lastUsePlayback: Boolean? = null
    private var silentMicStreak = 0

    init {
        val cap = WebRtcNativeAudioBridge.getCaptureByteBuffer(webRtcAudioRecord)?.capacity() ?: 0
        scratch = ByteArray(cap)
    }

    fun stop() {
        running.set(false)
    }

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val byteBuffer = WebRtcNativeAudioBridge.getCaptureByteBuffer(webRtcAudioRecord) ?: return
        val capacity = byteBuffer.capacity()

        Log.d(TAG, "Loop started capacity=$capacity")

        var framesSent = 0
        var lastLogFrame = 0

        while (running.get()) {
            val microphoneMuted = microphoneMuteState.microphoneMuted.get()
            val usePlayback = microphoneMuted && playbackCapture != null
            if (lastUsePlayback != usePlayback) {
                lastUsePlayback = usePlayback
                Log.i(TAG, "Capture source → ${if (usePlayback) "playback" else "mic"}")
            }

            val record = if (usePlayback) {
                playbackCapture!!.recordForRead
            } else if (!microphoneMuted) {
                silentPlaybackStreak = 0
                ensureRecording(micAudioRecord)
                micAudioRecord
            } else {
                null
            }
            if (record == null) {
                Thread.sleep(FRAME_SLEEP_MS)
                continue
            }

            val bytesRead = try {
                record.read(scratch, 0, capacity)
            } catch (e: RuntimeException) {
                Log.e(TAG, "read failed", e)
                -1
            }

            if (bytesRead <= 0) {
                Thread.sleep(FRAME_SLEEP_MS)
                continue
            }

            val frameBytes = if (bytesRead < capacity) {
                padScratchToCapacity(bytesRead, capacity)
                capacity
            } else {
                bytesRead
            }

            byteBuffer.clear()
            byteBuffer.put(scratch, 0, frameBytes)

            val peak = peakPcm16(scratch, frameBytes)
            if (usePlayback) {
                WebRtcAudioInjector.notifyCapturePeak(peak)
                if (peak == 0) {
                    silentPlaybackStreak++
                    if (silentPlaybackStreak == 300) {
                        Log.w(TAG, "Playback PCM silent — use Play test tone (YouTube often not capturable)")
                    }
                } else {
                    if (silentPlaybackStreak > 0) {
                        Log.i(TAG, "Playback PCM active peak=$peak")
                    }
                    silentPlaybackStreak = 0
                }
            } else {
                if (peak == 0) {
                    silentMicStreak++
                    if (silentMicStreak == 100) {
                        Log.w(TAG, "Mic PCM silent — check RECORD_AUDIO / speak louder")
                        restartMicIfNeeded(micAudioRecord)
                    }
                } else {
                    if (silentMicStreak > 0) {
                        Log.i(TAG, "Mic PCM active peak=$peak")
                    }
                    silentMicStreak = 0
                }
            }

            if (WebRtcNativeAudioBridge.getNativeAudioRecordPointer(webRtcAudioRecord) == 0L) {
                Log.d(TAG, "Native audio record gone — exiting loop")
                break
            }

            if (WebRtcNativeAudioBridge.feedPcmFrame(webRtcAudioRecord, frameBytes)) {
                framesSent++
                if (framesSent - lastLogFrame >= 100) {
                    lastLogFrame = framesSent
                    Log.d(
                        TAG,
                        "sent $framesSent frames source=${if (usePlayback) "playback" else "mic"} " +
                            "peak=$peak bytesRead=$bytesRead ch=${record.channelCount}",
                    )
                }
            } else {
                Log.e(TAG, "native feed failed — stopping loop")
                break
            }

            Thread.sleep(FRAME_SLEEP_MS)
        }

        if (activeLoop === this) {
            activeLoop = null
        }
        Log.d(TAG, "Loop stopped sent=$framesSent")
    }

    private fun padScratchToCapacity(bytesRead: Int, capacity: Int) {
        for (i in bytesRead until capacity) {
            scratch[i] = 0
        }
    }

    private fun peakPcm16(pcm: ByteArray, length: Int): Int {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)
            val level = abs(sample)
            if (level > peak) peak = level
            i += 2
        }
        return peak
    }

    private fun ensureRecording(record: AudioRecord) {
        if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) return
        try {
            record.startRecording()
        } catch (_: IllegalStateException) {
        }
    }

    private fun restartMicIfNeeded(mic: AudioRecord) {
        try {
            if (mic.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                mic.stop()
            }
            mic.startRecording()
            Log.d(TAG, "Mic AudioRecord restarted state=${mic.recordingState}")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Mic restart failed", e)
        }
    }

    companion object {
        private const val TAG = "WebRtcPaddedCapture"
        private const val FRAME_SLEEP_MS = 10L
        private const val HANDOFF_RETRY_MS = 250L
        private const val HANDOFF_MAX_ATTEMPTS = 12

        private val mainHandler = Handler(Looper.getMainLooper())
        private var pendingHandoff: Runnable? = null
        private var handoffAttempt = 0
        private var activeLoop: WebRtcPaddedCaptureLoop? = null
        private var captureThread: Thread? = null

        fun cancelPendingHandoff() {
            pendingHandoff?.let { mainHandler.removeCallbacks(it) }
            pendingHandoff = null
            handoffAttempt = 0
            stopCaptureLoopAndAwait()
        }

        fun stopCaptureLoopAndAwait(timeoutMs: Long = 3_000L) {
            activeLoop?.stop()
            val thread = captureThread
            if (thread != null && thread.isAlive) {
                try {
                    thread.join(timeoutMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                if (thread.isAlive) {
                    Log.w(TAG, "Capture thread still alive after ${timeoutMs}ms")
                }
            }
            captureThread = null
            activeLoop = null
        }

        fun scheduleHandoff(
            webRtcAudioRecord: Any,
            micAudioRecord: AudioRecord,
            playbackCapture: PlaybackAudioCapture?,
            microphoneMuteState: MicrophoneMuteState,
            onStarted: (WebRtcPaddedCaptureLoop) -> Unit,
            onAborted: () -> Unit,
        ) {
            cancelPendingHandoff()
            handoffAttempt = 0
            val runnable = object : Runnable {
                override fun run() {
                    handoffAttempt++
                    when (
                        tryHandoff(
                            webRtcAudioRecord,
                            micAudioRecord,
                            playbackCapture,
                            microphoneMuteState,
                            onStarted,
                        )
                    ) {
                        HandoffResult.Success -> pendingHandoff = null
                        HandoffResult.Retry ->
                            if (handoffAttempt < HANDOFF_MAX_ATTEMPTS) {
                                pendingHandoff = this
                                mainHandler.postDelayed(this, HANDOFF_RETRY_MS)
                            } else {
                                pendingHandoff = null
                                Log.e(TAG, "Handoff gave up after $handoffAttempt attempts")
                                onAborted()
                            }
                        HandoffResult.Aborted -> {
                            pendingHandoff = null
                            onAborted()
                        }
                    }
                }
            }
            pendingHandoff = runnable
            mainHandler.postDelayed(runnable, HANDOFF_RETRY_MS)
        }

        private enum class HandoffResult { Success, Retry, Aborted }

        private fun tryHandoff(
            webRtcAudioRecord: Any,
            micAudioRecord: AudioRecord,
            playbackCapture: PlaybackAudioCapture?,
            microphoneMuteState: MicrophoneMuteState,
            onStarted: (WebRtcPaddedCaptureLoop) -> Unit,
        ): HandoffResult {
            val byteBuffer = WebRtcNativeAudioBridge.getCaptureByteBuffer(webRtcAudioRecord)
            val nativePtr = WebRtcNativeAudioBridge.getNativeAudioRecordPointer(webRtcAudioRecord)
            if (byteBuffer == null || nativePtr == 0L) {
                Log.w(TAG, "Handoff wait #$handoffAttempt native=$nativePtr buffer=$byteBuffer")
                return HandoffResult.Retry
            }

            if (activeLoop != null) {
                Log.d(TAG, "Handoff skipped — capture loop already active")
                return HandoffResult.Success
            }

            WebRtcNativeAudioBridge.setMicrophoneMuteField(webRtcAudioRecord, false)

            if (!WebRtcNativeAudioBridge.stopStockCaptureThread(webRtcAudioRecord)) {
                Log.e(TAG, "Handoff aborted: stock thread not stopped")
                return HandoffResult.Aborted
            }

            if (!WebRtcAudioInjector.activatePipelineForHandoff(
                    micAudioRecord,
                    playbackCapture,
                    microphoneMuteState.microphoneMuted.get(),
                )
            ) {
                Log.e(TAG, "Handoff aborted: pipeline activation failed")
                return HandoffResult.Aborted
            }

            val loop = WebRtcPaddedCaptureLoop(
                webRtcAudioRecord,
                micAudioRecord,
                playbackCapture,
                microphoneMuteState,
            )
            activeLoop = loop
            val thread = Thread(loop, "webrtc-padded-capture")
            captureThread = thread
            thread.start()
            Log.d(TAG, "Handoff OK on attempt #$handoffAttempt")
            onStarted(loop)
            return HandoffResult.Success
        }

        private fun stopRecordingQuietly(record: AudioRecord) {
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            } catch (_: IllegalStateException) {
            }
        }
    }
}
