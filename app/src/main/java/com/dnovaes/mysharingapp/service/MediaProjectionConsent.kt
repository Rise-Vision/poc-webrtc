package com.dnovaes.mysharingapp.service

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Call [MediaProjectionManager.getMediaProjection] only while
 * [ScreenShareForegroundService] is in the foreground with type `mediaProjection` (Android 14+).
 */
object MediaProjectionConsent {

    private const val TAG = "MediaProjectionConsent"

    fun obtain(
        context: Context,
        resultCode: Int,
        resultData: Intent,
        onProjectionStopped: () -> Unit,
    ): MediaProjection? {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val projection = manager.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            Log.e(TAG, "getMediaProjection returned null")
            return null
        }
        projection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped by system")
                    onProjectionStopped()
                }
            },
            Handler(Looper.getMainLooper()),
        )
        return projection
    }
}
