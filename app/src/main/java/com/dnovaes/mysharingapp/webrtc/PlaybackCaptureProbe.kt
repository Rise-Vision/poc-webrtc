package com.dnovaes.mysharingapp.webrtc

import android.content.Context
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import kotlin.math.abs

/**
 * One-shot probe: grants [MediaProjection], plays a [USAGE_MEDIA] tone, and measures PCM from
 * [PlaybackAudioCapture] to determine whether device playback capture works on this OEM build.
 */
data class PlaybackCaptureProbeResult(
    val works: Boolean,
    val summary: String,
    val details: String,
    val peak: Int,
    val strategy: String?,
    val manufacturer: String = Build.MANUFACTURER,
    val model: String = Build.MODEL,
    val sdkInt: Int = Build.VERSION.SDK_INT,
)

object PlaybackCaptureProbe {

    private const val TAG = "PlaybackCaptureProbe"
    private const val PEAK_PASS_THRESHOLD = 2_000
    private const val READ_DURATION_MS = 1_800L
    private const val BUFFER_BYTES = 1920
    private const val STRATEGY_COUNT = 3

    fun logDeviceInfo() {
        Log.d(
            TAG,
            "manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} sdkInt=${Build.VERSION.SDK_INT}",
        )
    }

    fun logResult(result: PlaybackCaptureProbeResult) {
        Log.d(
            TAG,
            "result works=${result.works} peak=${result.peak} strategy=${result.strategy} " +
                "manufacturer=${result.manufacturer} model=${result.model} sdkInt=${result.sdkInt}",
        )
        Log.d(TAG, "summary=${result.summary}")
        Log.d(TAG, "details=${result.details.replace("\n", " | ")}")
    }

    /**
     * Returns a failure result when the device cannot support playback capture at all, or null
     * when runtime permissions / MediaProjection may still allow a successful probe.
     */
    fun checkPreconditions(context: Context): PlaybackCaptureProbeResult? {
        logDeviceInfo()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return failure(
                summary = "Not supported on this Android version",
                details = "Audio playback capture requires Android 10 (API 29+). " +
                    "This device is API ${Build.VERSION.SDK_INT}.",
                peak = 0,
                strategy = null,
            )
        }
        return null
    }

    fun run(context: Context, projection: MediaProjection): PlaybackCaptureProbeResult {
        logDeviceInfo()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return failure(
                summary = "Not supported on this Android version",
                details = "Requires API 29+.",
                peak = 0,
                strategy = null,
            )
        }

        val tonePlayer = MediaTestTonePlayer()
        var capture: PlaybackAudioCapture? = null

        return try {
            AudioCaptureRouting.enterPlaybackCaptureMode(context)
            capture = PlaybackAudioCapture(context, projection).apply {
                enableStrategyCycleForTest()
                configure(sampleRate = 48_000, channelCount = 2, bufferSizeBytes = BUFFER_BYTES)
                resetRefreshCycle()
            }

            var bestPeak = 0
            var bestStrategy: String? = null
            var lastBuildError: String? = null
            var lastPlaybackSummary = ""

            for (attempt in 1..STRATEGY_COUNT) {
                val started = if (attempt == 1) {
                    capture.refreshAndStartBlocking()
                } else {
                    val label = capture.tryNextCaptureStrategyForTest()
                    !label.startsWith("FAILED:")
                }
                val strategyLabel = capture.getLastStrategyLabel()
                if (!started) {
                    lastBuildError = capture.getLastBuildError() ?: "AudioRecord failed to initialize"
                    Log.d(
                        TAG,
                        "strategy attempt=$attempt label=$strategyLabel failed: $lastBuildError " +
                            "manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} " +
                            "sdkInt=${Build.VERSION.SDK_INT}",
                    )
                    continue
                }

                tonePlayer.start()
                Thread.sleep(300)
                val peak = readPeak(capture, READ_DURATION_MS)
                tonePlayer.stop()
                lastPlaybackSummary = capture.getActivePlaybackSummary()

                Log.d(
                    TAG,
                    "strategy attempt=$attempt label=$strategyLabel peak=$peak " +
                        "manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} " +
                        "sdkInt=${Build.VERSION.SDK_INT}",
                )

                if (peak > bestPeak) {
                    bestPeak = peak
                    bestStrategy = strategyLabel
                }
                if (peak >= PEAK_PASS_THRESHOLD) {
                    return success(
                        peak = peak,
                        strategy = strategyLabel,
                        playbackSummary = lastPlaybackSummary,
                    )
                }
                lastBuildError = capture.getLastBuildError()
            }

            if (bestPeak > 0) {
                failure(
                    summary = "Audio playback capture is weak on this device",
                    details = buildWeakSignalDetails(bestPeak, bestStrategy, lastPlaybackSummary),
                    peak = bestPeak,
                    strategy = bestStrategy,
                )
            } else {
                failure(
                    summary = "Audio playback capture does NOT work on this device",
                    details = buildSilentCaptureDetails(lastBuildError, lastPlaybackSummary),
                    peak = 0,
                    strategy = bestStrategy,
                )
            }
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Probe crashed manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} " +
                    "sdkInt=${Build.VERSION.SDK_INT}",
                e,
            )
            failure(
                summary = "Audio playback capture check failed",
                details = e.message ?: e.javaClass.simpleName,
                peak = 0,
                strategy = capture?.getLastStrategyLabel(),
            )
        } finally {
            tonePlayer.stop()
            capture?.release()
            AudioCaptureRouting.release(context)
        }
    }

    private fun readPeak(capture: PlaybackAudioCapture, durationMs: Long): Int {
        val scratch = ByteArray(BUFFER_BYTES)
        var peak = 0
        val deadline = System.currentTimeMillis() + durationMs
        while (System.currentTimeMillis() < deadline) {
            val record = capture.recordForRead ?: continue
            val read = try {
                record.read(scratch, 0, scratch.size)
            } catch (_: RuntimeException) {
                -1
            }
            if (read > 0) {
                peak = maxOf(peak, peakPcm16(scratch, read))
            }
            Thread.sleep(10)
        }
        return peak
    }

    private fun peakPcm16(pcm: ByteArray, length: Int): Int {
        var maxSample = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)
            maxSample = maxOf(maxSample, abs(sample))
            i += 2
        }
        return maxSample
    }

    private fun success(
        peak: Int,
        strategy: String,
        playbackSummary: String,
    ): PlaybackCaptureProbeResult = PlaybackCaptureProbeResult(
        works = true,
        summary = "Audio playback capture works on this device.",
        details = buildString {
            appendLine("PCM peak: $peak (threshold $PEAK_PASS_THRESHOLD)")
            appendLine("Capture strategy: $strategy")
            appendLine()
            append(playbackSummary)
        },
        peak = peak,
        strategy = strategy,
    )

    private fun failure(
        summary: String,
        details: String,
        peak: Int,
        strategy: String?,
    ): PlaybackCaptureProbeResult = PlaybackCaptureProbeResult(
        works = false,
        summary = summary,
        details = details,
        peak = peak,
        strategy = strategy,
    )

    private fun buildWeakSignalDetails(
        peak: Int,
        strategy: String?,
        playbackSummary: String,
    ): String = buildString {
        appendLine("PCM peak: $peak (need ≥ $PEAK_PASS_THRESHOLD for a reliable pass)")
        appendLine("Best strategy tried: ${strategy ?: "none"}")
        appendLine()
        appendLine(
            "Some signal was detected but it may be too quiet or intermittent. " +
                "Try again with media volume up, or use the advanced test screen to cycle strategies.",
        )
        appendLine()
        append(playbackSummary)
    }

    private fun buildSilentCaptureDetails(
        lastBuildError: String?,
        playbackSummary: String,
    ): String = buildString {
        if (lastBuildError != null) {
            appendLine("AudioRecord error: $lastBuildError")
            appendLine()
        } else {
            appendLine("MediaProjection was granted and AudioRecord started, but captured PCM was silent.")
            appendLine()
            appendLine(
                "This usually means the OEM blocked REMOTE_SUBMIX / playback capture on this build " +
                    "(seen on some Samsung, Motorola, LG, and other vendor images). " +
                    "The app cannot read a hidden OEM flag — only whether capture returns non-zero audio.",
            )
            appendLine()
        }
        append(playbackSummary)
    }
}
