package com.dnovaes.mysharingapp.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dnovaes.mysharingapp.R

class ScreenShareForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
        promoteToForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        notifyForegroundReady()
        return START_STICKY
    }

    override fun onDestroy() {
        isForegroundReady = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promoteToForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.screen_share_notification_title))
            .setContentText(getString(R.string.screen_share_notification_body))
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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

    companion object {
        private const val CHANNEL_ID = "screen_share"
        private const val NOTIFICATION_ID = 1001

        private val mainHandler = Handler(Looper.getMainLooper())
        private val pendingCallbacks = mutableListOf<() -> Unit>()

        @Volatile
        private var isForegroundReady = false

        fun runWhenForeground(context: Context, block: () -> Unit) {
            if (isForegroundReady) {
                mainHandler.post(block)
                return
            }
            synchronized(pendingCallbacks) {
                pendingCallbacks.add(block)
            }
            ContextCompat.startForegroundService(
                context,
                Intent(context, ScreenShareForegroundService::class.java),
            )
        }

        private fun notifyForegroundReady() {
            isForegroundReady = true
            val callbacks = synchronized(pendingCallbacks) {
                pendingCallbacks.toList().also { pendingCallbacks.clear() }
            }
            callbacks.forEach { mainHandler.post(it) }
        }
    }
}
