package com.dnovaes.mysharingapp

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.dnovaes.mysharingapp.webrtc.ScreenSharePublisher

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ScreenSharePOC"
    }

    private var publisher: ScreenSharePublisher? = null
    private var activeProjection: MediaProjection? = null
    private var isSharingState = mutableStateOf(false)

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
                    isSharing = isSharingState.value,
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

        ScreenShareForegroundService.runWhenForeground(this) {
            val projectionManager =
                getSystemService(MediaProjectionManager::class.java)
            val projection = projectionManager.getMediaProjection(resultCode, data)
                ?: return@runWhenForeground
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
                it.start(resultCode, data, projection, wsUrl, room)
            }
            isSharingState.value = true
        }
    }

    private fun stopSharing() {
        Log.d(TAG, "stopSharing")
        publisher?.stop()
        publisher = null
        isSharingState.value = false
        activeProjection?.stop()
        activeProjection = null
        ScreenShareForegroundService.markForegroundEnded()
        stopService(Intent(this, ScreenShareForegroundService::class.java))
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
    isSharing: Boolean,
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
        val recordGranted = results[Manifest.permission.RECORD_AUDIO] != false
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            results[Manifest.permission.POST_NOTIFICATIONS] != false
        } else {
            true
        }
        if (recordGranted && notificationGranted) {
            launchScreenCapture(projectionLauncher, context)
        } else if (!recordGranted) {
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
                    onClick = onOpenPlaybackTest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.open_playback_capture_test))
                }
                Button(
                    onClick = {
                        val missing = buildList {
                            if (ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO,
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                add(Manifest.permission.RECORD_AUDIO)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                                ContextCompat.checkSelfPermission(
                                    context,
                                    com.dnovaes.mysharingapp.webrtc.PlaybackAudioCapture.PERMISSION_CAPTURE_MEDIA_OUTPUT,
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                add(com.dnovaes.mysharingapp.webrtc.PlaybackAudioCapture.PERMISSION_CAPTURE_MEDIA_OUTPUT)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS,
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
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
}
