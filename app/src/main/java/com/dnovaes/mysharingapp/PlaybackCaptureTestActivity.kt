package com.dnovaes.mysharingapp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dnovaes.mysharingapp.service.ScreenShareForegroundService
import com.dnovaes.mysharingapp.ui.theme.POCSharingVideoAudioTheme
import com.dnovaes.mysharingapp.webrtc.AudioCaptureRouting
import com.dnovaes.mysharingapp.webrtc.MediaTestTonePlayer
import com.dnovaes.mysharingapp.webrtc.PlaybackAudioCapture
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Isolates [PlaybackAudioCapture] from WebRTC — shows live PCM peak from the OS playback mix.
 */
class PlaybackCaptureTestActivity : ComponentActivity() {

    private var projection: MediaProjection? = null
    private var capture: PlaybackAudioCapture? = null
    private val tonePlayer = MediaTestTonePlayer()
    private val readRunning = AtomicBoolean(false)
    private var readThread: Thread? = null

    @Volatile
    private var lastPeak = 0

    @Volatile
    private var diagnosticLog = "Idle — tap Start (uses screen-capture permission, no WebRTC)."

    private val uiFrameTick = mutableIntStateOf(0)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.all { it }) {
            launchProjection()
        } else {
            appendLog("Permissions denied")
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            appendLog("MediaProjection denied")
            return@registerForActivityResult
        }
        val resultCode = result.resultCode
        val data = result.data!!
        ScreenShareForegroundService.runWhenForeground(this) {
            startTest(resultCode, data)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val _tick = uiFrameTick.intValue
            POCSharingVideoAudioTheme {
                PlaybackCaptureTestScreen(
                    onBack = { finish() },
                    onRequestStart = { requestPermissionsAndProjection() },
                    onStop = { stopTest() },
                    onNextStrategy = { cycleStrategy() },
                    onPlayTone = { tonePlayer.start() },
                    onStopTone = { tonePlayer.stop() },
                    peak = lastPeak,
                    strategy = capture?.getLastStrategyLabel() ?: "—",
                    session = capture?.getAudioSessionId().toString(),
                    mediaActive = capture?.isMediaPlaybackActive() == true,
                    running = capture != null,
                    verdict = verdictFromPeak(lastPeak),
                    log = diagnosticLog,
                )
            }
        }
    }

    private fun bumpUi() {
        runOnUiThread { uiFrameTick.intValue++ }
    }

    override fun onDestroy() {
        stopTest()
        super.onDestroy()
    }

    private fun requestPermissionsAndProjection() {
        val missing = buildList {
            if (ContextCompat.checkSelfPermission(this@PlaybackCaptureTestActivity, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                ContextCompat.checkSelfPermission(
                    this@PlaybackCaptureTestActivity,
                    PlaybackAudioCapture.PERMISSION_CAPTURE_MEDIA_OUTPUT,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(PlaybackAudioCapture.PERMISSION_CAPTURE_MEDIA_OUTPUT)
            }
        }
        if (missing.isEmpty()) {
            launchProjection()
        } else {
            permissionsLauncher.launch(missing.toTypedArray())
        }
    }

    private fun launchProjection() {
        projectionLauncher.launch(
            getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(),
        )
    }

    private fun startTest(resultCode: Int, data: Intent) {
        // Do not stop the foreground service here — getMediaProjection() requires it.
        stopCaptureOnly()
        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data)
        if (mp == null) {
            appendLog("getMediaProjection failed")
            return
        }
        projection = mp
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            appendLog("Requires API 29+")
            return
        }
        AudioCaptureRouting.enterPlaybackCaptureMode(this)
        val playbackCapture = PlaybackAudioCapture(this, mp)
        playbackCapture.enableStrategyCycleForTest()
        playbackCapture.configure(sampleRate = 48_000, channelCount = 2, bufferSizeBytes = 1920)
        playbackCapture.resetRefreshCycle()
        val ok = playbackCapture.refreshAndStartBlocking()
        if (!ok) {
            appendLog("Capture start failed")
            playbackCapture.release()
            return
        }
        capture = playbackCapture
        appendLog("Started strategy=${playbackCapture.getLastStrategyLabel()}")
        appendLog(
            "Logcat may say 'REMOTE_SUBMIX' for every strategy — that is the OS mix name, " +
                "not a separate test option.",
        )
        appendLog(playbackCapture.getActivePlaybackSummary())
        bumpUi()
        startPeakReader()
    }

    private fun startPeakReader() {
        readRunning.set(true)
        readThread = Thread({
            val scratch = ByteArray(1920)
            var frames = 0
            while (readRunning.get()) {
                val record = capture?.recordForRead
                if (record != null) {
                    val n = try {
                        record.read(scratch, 0, scratch.size)
                    } catch (_: RuntimeException) {
                        -1
                    }
                    if (n > 0) {
                        lastPeak = peakPcm16(scratch, n)
                        bumpUi()
                        frames++
                        if (frames % 50 == 0) {
                            runOnUiThread {
                                bumpUi()
                                diagnosticLog = buildString {
                                    appendLine("Frames read: $frames")
                                    appendLine("Strategy: ${capture?.getLastStrategyLabel()}")
                                    appendLine("Session: ${capture?.getAudioSessionId()}")
                                    appendLine("Media active: ${capture?.isMediaPlaybackActive()}")
                                    appendLine("Last peak: $lastPeak")
                                    appendLine()
                                    append(capture?.getActivePlaybackSummary() ?: "")
                                }
                            }
                        }
                    }
                }
                Thread.sleep(10)
            }
        }, "playback-capture-test-read").apply { start() }
    }

    private fun cycleStrategy() {
        val c = capture ?: return
        val label = c.tryNextCaptureStrategyForTest()
        appendLog("Switched strategy → $label")
        appendLog(c.getActivePlaybackSummary())
        mainHandler.postDelayed({
            appendLog("Peak ~1.5s after switch: $lastPeak (need ≫2000 for PASS)")
        }, 1_500)
        bumpUi()
    }

    private fun stopCaptureOnly() {
        readRunning.set(false)
        readThread?.join(500)
        readThread = null
        tonePlayer.stop()
        capture?.release()
        capture = null
        projection?.stop()
        projection = null
        AudioCaptureRouting.release(this)
        lastPeak = 0
    }

    private fun stopTest() {
        stopCaptureOnly()
        ScreenShareForegroundService.markForegroundEnded()
        stopService(Intent(this, ScreenShareForegroundService::class.java))
        appendLog("Stopped")
        bumpUi()
    }

    private fun appendLog(line: String) {
        Log.d(TAG, line)
        diagnosticLog = line + "\n" + diagnosticLog
        bumpUi()
    }

    private fun peakPcm16(pcm: ByteArray, length: Int): Int {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)
            peak = maxOf(peak, abs(sample))
            i += 2
        }
        return peak
    }

    private fun verdictFromPeak(peak: Int): String = when {
        peak > 2_000 -> "PASS — PCM has signal (capture works on this phone)"
        peak > 0 -> "WEAK — some signal; try louder media or next strategy"
        else -> "FAIL — playback mix is silent (all strategies use the same OS path)"
    }

    companion object {
        private const val TAG = "PlaybackCaptureTest"
    }
}

@Composable
private fun PlaybackCaptureTestScreen(
    onBack: () -> Unit,
    onRequestStart: () -> Unit,
    onStop: () -> Unit,
    onNextStrategy: () -> Unit,
    onPlayTone: () -> Unit,
    onStopTone: () -> Unit,
    peak: Int,
    strategy: String,
    session: String,
    mediaActive: Boolean,
    running: Boolean,
    verdict: String,
    log: String,
) {
    val peakColor = when {
        peak > 2_000 -> Color(0xFF2E7D32)
        peak > 0 -> Color(0xFFF9A825)
        else -> Color(0xFFC62828)
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Samsung playback capture test", style = MaterialTheme.typography.headlineSmall)
            Text(
                "No WebRTC. Measures PCM level from device playback capture.\n\n" +
                    "1. Start → approve screen capture\n" +
                    "2. Play test tone OR open YouTube (volume up)\n" +
                    "3. Watch Peak — must be ≫ 0\n\n" +
                    "Next capture strategy cycles:\n" +
                    "• PLAYBACK_USAGE_MEDIA\n" +
                    "• PLAYBACK_USAGE_MEDIA_WITH_KNOWN_UIDS\n" +
                    "• PLAYBACK_ALL_USAGES\n\n" +
                    "Ignore logcat 'Will record from REMOTE_SUBMIX' — Android prints that " +
                    "for all three; it is not a fourth strategy and not REMOTE_SUBMIX_DIRECT " +
                    "(removed). Only Peak + Strategy line matter.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Verdict", style = MaterialTheme.typography.titleMedium)
            Text(verdict, color = peakColor, style = MaterialTheme.typography.bodyLarge)
            Text("Peak: $peak", color = peakColor, style = MaterialTheme.typography.displaySmall)
            Text("Strategy: $strategy", style = MaterialTheme.typography.bodyMedium)
            Text("Session: $session", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Media playback active: $mediaActive",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!running) {
                Button(onClick = onRequestStart, modifier = Modifier.fillMaxWidth()) {
                    Text("Start capture test")
                }
            } else {
                Button(onClick = onNextStrategy, modifier = Modifier.fillMaxWidth()) {
                    Text("Next capture strategy")
                }
                Button(onClick = onPlayTone, modifier = Modifier.fillMaxWidth()) {
                    Text("Play in-app test tone (USAGE_MEDIA)")
                }
                Button(onClick = onStopTone, modifier = Modifier.fillMaxWidth()) {
                    Text("Stop test tone")
                }
                Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Text("Stop test")
                }
            }
            Text("Log", style = MaterialTheme.typography.titleMedium)
            Text(log, style = MaterialTheme.typography.bodySmall)
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Back")
            }
        }
    }
}
