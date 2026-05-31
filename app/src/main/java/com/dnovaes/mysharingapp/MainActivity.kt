package com.dnovaes.mysharingapp

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dnovaes.mysharingapp.service.ScreenShareForegroundService
import com.dnovaes.mysharingapp.signaling.SignalingClient
import com.dnovaes.mysharingapp.ui.theme.POCSharingVideoAudioTheme
import com.dnovaes.mysharingapp.webrtc.PlaybackCaptureProbe
import com.dnovaes.mysharingapp.webrtc.PlaybackCaptureProbeResult
import com.dnovaes.mysharingapp.webrtc.ProbePermissionHelper
import com.dnovaes.mysharingapp.webrtc.ScreenSharePublisher

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ScreenSharePOC"
    }

    private var publisher: ScreenSharePublisher? = null
    private var activeProjection: MediaProjection? = null
    private var isSharingState = mutableStateOf(false)
    private var probeRunningState = mutableStateOf(false)
    private var probeDialogResultState = mutableStateOf<PlaybackCaptureProbeResult?>(null)
    private var pendingProbePermissionRequest = emptyArray<String>()

    private val probePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val evaluation = ProbePermissionHelper.evaluateGrantResults(
            pendingProbePermissionRequest,
            results,
        )
        Log.d(TAG, "probe permissions denied=${evaluation.deniedPermissions}")
        if (evaluation.allGranted) {
            launchProbeProjection()
        } else {
            finishProbeWithResult(
                PlaybackCaptureProbeResult(
                    works = false,
                    summary = getString(R.string.probe_permissions_required),
                    details = ProbePermissionHelper.buildDeniedMessage(evaluation.deniedLabels),
                    peak = 0,
                    strategy = null,
                    needsPermissionGrant = true,
                ),
            )
        }
    }

    private val probeProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            finishProbeWithResult(
                PlaybackCaptureProbeResult(
                    works = false,
                    summary = "Screen capture permission denied",
                    details = "MediaProjection consent is required to test audio playback capture.",
                    peak = 0,
                    strategy = null,
                ),
            )
            return@registerForActivityResult
        }
        val resultCode = result.resultCode
        val data = result.data!!
        ScreenShareForegroundService.runWhenForeground(
            context = this,
            resultCode = resultCode,
            resultData = data,
            onProjectionStopped = { /* probe is short-lived; ignore */ },
        ) { projection ->
            Thread({
                val probeResult = PlaybackCaptureProbe.run(this@MainActivity, projection)
                PlaybackCaptureProbe.logResult(probeResult)
                try {
                    projection.stop()
                } catch (_: Exception) {
                }
                runOnUiThread {
                    finishProbeWithResult(probeResult)
                    ScreenShareForegroundService.markForegroundEnded()
                    stopService(Intent(this@MainActivity, ScreenShareForegroundService::class.java))
                }
            }, "playback-capture-probe").start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            POCSharingVideoAudioTheme {
                ScreenShareScreen(
                    onStartShare = { wsUrl, room, resultCode, data ->
                        startSharing(wsUrl, room, resultCode, data)
                    },
                    onStopShare = { stopSharing() },
                    onSetMicrophoneMuted = { muted -> publisher?.setMicrophoneMuted(muted) },
                    onPlayTestTone = { publisher?.playDebugTestTone() },
                    onOpenPlaybackTest = {
                        startActivity(Intent(this, PlaybackCaptureTestActivity::class.java))
                    },
                    onCheckPlaybackCapture = { startPlaybackCaptureProbe() },
                    onOpenAppSettings = { openAppSettings() },
                    isSharing = isSharingState.value,
                    probeRunning = probeRunningState.value,
                    probeResult = probeDialogResultState.value,
                    onDismissProbeDialog = { probeDialogResultState.value = null },
                )
            }
        }
    }

    override fun onDestroy() {
        stopSharing()
        super.onDestroy()
    }

    private fun startSharing(wsUrl: String, room: String, resultCode: Int, data: Intent) {
        if (publisher != null) return
        Log.d(TAG, "startSharing wsUrl=$wsUrl room=$room")

        ScreenShareForegroundService.runWhenForeground(
            context = this,
            resultCode = resultCode,
            resultData = data,
            onProjectionStopped = { runOnUiThread { stopSharing() } },
        ) { projection ->
            if (publisher != null) return@runWhenForeground
            activeProjection = projection
            publisher = ScreenSharePublisher(
                context = this@MainActivity,
                signalingClient = SignalingClient(),
                onStatus = { message ->
                    runOnUiThread {
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    }
                },
            ).also {
                it.start(projection, wsUrl, room)
            }
            isSharingState.value = true
        }
    }

    private fun stopSharing() {
        Log.d(TAG, "stopSharing")
        publisher?.stop()
        publisher = null
        isSharingState.value = false
        activeProjection = null
        ScreenShareForegroundService.markForegroundEnded()
        stopService(Intent(this, ScreenShareForegroundService::class.java))
    }

    private fun startPlaybackCaptureProbe() {
        if (probeRunningState.value || isSharingState.value) return
        probeRunningState.value = true
        PlaybackCaptureProbe.logDeviceInfo()

        val preconditionFailure = PlaybackCaptureProbe.checkPreconditions(this)
        if (preconditionFailure != null) {
            PlaybackCaptureProbe.logResult(preconditionFailure)
            finishProbeWithResult(preconditionFailure)
            return
        }

        val missing = ProbePermissionHelper.missingRuntimePermissions(this)
        if (missing.isEmpty()) {
            launchProbeProjection()
        } else {
            pendingProbePermissionRequest = missing.toTypedArray()
            probePermissionsLauncher.launch(pendingProbePermissionRequest)
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            },
        )
    }

    private fun launchProbeProjection() {
        probeProjectionLauncher.launch(
            getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(),
        )
    }

    private fun finishProbeWithResult(result: PlaybackCaptureProbeResult) {
        probeRunningState.value = false
        probeDialogResultState.value = result
    }
}

private fun launchScreenCapture(
    projectionLauncher: ActivityResultLauncher<Intent>,
    context: Context,
) {
    val manager = context.getSystemService(MediaProjectionManager::class.java)
    projectionLauncher.launch(manager.createScreenCaptureIntent())
}

@Composable
private fun ScreenShareScreen(
    onStartShare: (wsUrl: String, room: String, resultCode: Int, data: Intent) -> Unit,
    onStopShare: () -> Unit,
    onSetMicrophoneMuted: (microphoneMuted: Boolean) -> Unit,
    onPlayTestTone: () -> Unit,
    onOpenPlaybackTest: () -> Unit,
    onCheckPlaybackCapture: () -> Unit,
    onOpenAppSettings: () -> Unit,
    isSharing: Boolean,
    probeRunning: Boolean,
    probeResult: PlaybackCaptureProbeResult?,
    onDismissProbeDialog: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("poc_screen_share", Context.MODE_PRIVATE) }
    var wsUrl by remember {
        mutableStateOf(prefs.getString("ws_url", "ws://10.0.2.2:8080") ?: "ws://10.0.2.2:8080")
    }
    var room by remember { mutableStateOf(prefs.getString("room", "poc") ?: "poc") }
    var status by remember { mutableStateOf("Idle") }
    var isMicrophoneMuted by remember { mutableStateOf(true) }

    LaunchedEffect(isSharing) {
        if (isSharing) {
            onSetMicrophoneMuted(true)
        } else {
            isMicrophoneMuted = true
        }
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            prefs.edit().putString("ws_url", wsUrl).putString("room", room).apply()
            status = "Starting screen share…"
            onStartShare(wsUrl.trim(), room.trim(), result.resultCode, result.data!!)
        } else {
            status = "Screen capture permission denied"
        }
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val evaluation = ProbePermissionHelper.evaluateGrantResults(
            results.keys.toTypedArray(),
            results,
        )
        if (evaluation.allGranted) {
            launchScreenCapture(projectionLauncher, context)
        } else if (evaluation.deniedPermissions.contains(Manifest.permission.RECORD_AUDIO)) {
            status = "Microphone permission is required to capture system audio"
        } else {
            status = "Notification permission is required while sharing"
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Screen share POC",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "Share Android screen + system audio to a browser via WebRTC. " +
                    "Use your computer's LAN IP (not localhost) on a physical device. " +
                    context.getString(R.string.record_audio_rationale),
                style = MaterialTheme.typography.bodyMedium,
            )

            OutlinedTextField(
                value = wsUrl,
                onValueChange = { wsUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Signaling WebSocket URL") },
                singleLine = true,
                enabled = !isSharing,
            )
            OutlinedTextField(
                value = room,
                onValueChange = { room = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Room ID") },
                singleLine = true,
                enabled = !isSharing,
            )

            if (!isSharing) {
                Button(
                    onClick = onCheckPlaybackCapture,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !probeRunning,
                ) {
                    Text(
                        if (probeRunning) {
                            stringResource(R.string.check_playback_capture_running)
                        } else {
                            stringResource(R.string.check_playback_capture)
                        },
                    )
                }
                Button(
                    onClick = onOpenPlaybackTest,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !probeRunning,
                ) {
                    Text(stringResource(R.string.open_playback_capture_test))
                }
                Button(
                    onClick = {
                        val missing = ProbePermissionHelper.missingRuntimePermissions(context)
                        if (missing.isEmpty()) {
                            launchScreenCapture(projectionLauncher, context)
                        } else {
                            permissionsLauncher.launch(missing.toTypedArray())
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Start screen + audio share")
                }
            } else {
                Button(
                    onClick = onPlayTestTone,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.play_test_tone_while_sharing))
                }
                Button(
                    onClick = {
                        isMicrophoneMuted = !isMicrophoneMuted
                        onSetMicrophoneMuted(isMicrophoneMuted)
                        status = if (isMicrophoneMuted) {
                            context.getString(R.string.status_microphone_muted)
                        } else {
                            context.getString(R.string.status_microphone_unmuted)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (isMicrophoneMuted) {
                            stringResource(R.string.unmute_microphone)
                        } else {
                            stringResource(R.string.mute_microphone)
                        },
                    )
                }
                Button(
                    onClick = {
                        onStopShare()
                        status = "Stopped"
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Stop sharing")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Status: $status", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "1. Run signaling server on your PC\n" +
                    "2. Open web/index.html and connect as viewer\n" +
                    "3. Start share here with the same room ID",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    probeResult?.let { result ->
        AlertDialog(
            onDismissRequest = onDismissProbeDialog,
            title = { Text(result.summary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${result.manufacturer} ${result.model} (API ${result.sdkInt})",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(text = result.details, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                if (result.needsPermissionGrant) {
                    TextButton(
                        onClick = {
                            onDismissProbeDialog()
                            onCheckPlaybackCapture()
                        },
                    ) {
                        Text(stringResource(R.string.probe_dialog_try_again))
                    }
                } else {
                    TextButton(onClick = onDismissProbeDialog) {
                        Text(stringResource(R.string.probe_dialog_ok))
                    }
                }
            },
            dismissButton = if (result.needsPermissionGrant) {
                {
                    TextButton(onClick = onOpenAppSettings) {
                        Text(stringResource(R.string.probe_dialog_app_settings))
                    }
                }
            } else {
                null
            },
        )
    }
}
