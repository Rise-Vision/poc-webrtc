package com.dnovaes.mysharingapp.service

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.dnovaes.mysharingapp.R

/**
 * Foreground service with [ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION] must be running
 * **before** [android.media.projection.MediaProjectionManager.getMediaProjection] (Android 14+).
 */
class ScreenShareForegroundService : Service() {

    private var projectionDelivered = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        promoteToForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        readProjectionIntent(intent)
        promoteToForeground()
        deliverProjectionWhenReady()
        return START_STICKY
    }

    override fun onDestroy() {
        isForegroundActive = false
        projectionDelivered = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun readProjectionIntent(intent: Intent?) {
        if (intent == null || !intent.hasExtra(EXTRA_RESULT_CODE)) return
        pendingResultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        pendingResultData = IntentCompat.getParcelableExtra(
            intent,
            EXTRA_RESULT_DATA,
            Intent::class.java,
        )
    }

    private fun promoteToForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.screen_share_notification_title))
            .setContentText(getString(R.string.screen_share_notification_body))
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isForegroundActive = true
    }

    private fun deliverProjectionWhenReady() {
        if (!isForegroundActive || projectionDelivered) return
        val code = pendingResultCode ?: return
        val data = pendingResultData
        if (code != Activity.RESULT_OK || data == null) {
            failPending("screen capture not granted")
            return
        }

        val callbacks = synchronized(pendingCallbacks) {
            if (pendingCallbacks.isEmpty()) return
            pendingCallbacks.toList().also { pendingCallbacks.clear() }
        }

        val projection = MediaProjectionConsent.obtain(this, code, data) {
            mainHandler.post {
                callbacks.forEach { it.onProjectionStopped() }
            }
        }
        if (projection == null) {
            failPending("getMediaProjection failed")
            return
        }

        projectionDelivered = true
        pendingResultCode = null
        pendingResultData = null
        callbacks.forEach { mainHandler.post { it.onProjection(projection) } }
    }

    private fun failPending(reason: String) {
        Log.e(TAG, reason)
        synchronized(pendingCallbacks) { pendingCallbacks.clear() }
        pendingResultCode = null
        pendingResultData = null
        stopSelf()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.screen_share_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private data class PendingCallback(
        val onProjection: (MediaProjection) -> Unit,
        val onProjectionStopped: () -> Unit,
    )

    companion object {
        private const val TAG = "ScreenShareFGS"
        private const val CHANNEL_ID = "screen_share"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        private val mainHandler = Handler(Looper.getMainLooper())
        private val pendingCallbacks = mutableListOf<PendingCallback>()

        @Volatile
        private var isForegroundActive = false

        @Volatile
        private var pendingResultCode: Int? = null

        @Volatile
        private var pendingResultData: Intent? = null

        fun runWhenForeground(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            onProjectionStopped: () -> Unit,
            onProjection: (MediaProjection) -> Unit,
        ) {
            synchronized(pendingCallbacks) {
                pendingCallbacks.add(PendingCallback(onProjection, onProjectionStopped))
            }
            pendingResultCode = resultCode
            pendingResultData = resultData
            val intent = Intent(context, ScreenShareForegroundService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun markForegroundEnded() {
            isForegroundActive = false
            pendingResultCode = null
            pendingResultData = null
        }
    }
}
