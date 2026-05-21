package com.dnovaes.mysharingapp.webrtc

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * WebRTC sets [AudioManager.MODE_IN_COMMUNICATION] for the mic, which can duck or exclude
 * media playback from the REMOTE_SUBMIX mix used by playback capture.
 */
internal object AudioCaptureRouting {
    private const val TAG = "AudioCaptureRouting"

    private var savedMode: Int? = null
    private var savedSpeakerphone: Boolean? = null

    fun enterPlaybackCaptureMode(context: Context) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        if (savedMode == null) {
            savedMode = am.mode
            savedSpeakerphone = am.isSpeakerphoneOn
        }
        try {
            am.mode = AudioManager.MODE_NORMAL
            am.isSpeakerphoneOn = true
            Log.d(TAG, "Audio mode → NORMAL (was=$savedMode) speakerphone=true")
        } catch (e: Exception) {
            Log.w(TAG, "enterPlaybackCaptureMode failed", e)
        }
    }

    fun enterMicrophoneCaptureMode(context: Context, switchingFromPlayback: Boolean = false) {
        if (!switchingFromPlayback) {
            restoreIfNeeded(context)
        }
        val am = context.getSystemService(AudioManager::class.java) ?: return
        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            Log.d(TAG, "Audio mode → IN_COMMUNICATION (fromPlayback=$switchingFromPlayback)")
        } catch (e: Exception) {
            Log.w(TAG, "enterMicrophoneCaptureMode failed", e)
        }
    }

    fun release(context: Context) {
        restoreIfNeeded(context)
        savedMode = null
        savedSpeakerphone = null
    }

    private fun restoreIfNeeded(context: Context) {
        val mode = savedMode ?: return
        val am = context.getSystemService(AudioManager::class.java) ?: return
        try {
            am.mode = mode
            savedSpeakerphone?.let { am.isSpeakerphoneOn = it }
            Log.d(TAG, "Audio mode restored → $mode")
        } catch (e: Exception) {
            Log.w(TAG, "restore failed", e)
        }
    }
}
