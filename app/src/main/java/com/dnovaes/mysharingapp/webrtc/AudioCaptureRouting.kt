package com.dnovaes.mysharingapp.webrtc

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Routes [AudioManager] for either playback capture (REMOTE_SUBMIX) or close-range microphone.
 *
 * Mic mode must **not** use speakerphone — otherwise the mic picks up device playback from the
 * loudspeaker (sounds distant / mixed with voice).
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
            clearCommunicationDevice(am)
            am.mode = AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = true
            Log.d(TAG, "Audio mode → NORMAL speakerphone=true (device playback capture)")
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
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
            preferBuiltInMic(am)
            Log.d(TAG, "Audio mode → IN_COMMUNICATION speakerphone=false (mic-only, close range)")
        } catch (e: Exception) {
            Log.w(TAG, "enterMicrophoneCaptureMode failed", e)
        }
    }

    fun release(context: Context) {
        val am = context.getSystemService(AudioManager::class.java)
        am?.let { clearCommunicationDevice(it) }
        restoreIfNeeded(context)
        savedMode = null
        savedSpeakerphone = null
    }

    private fun preferBuiltInMic(am: AudioManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            val mic = am.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            if (mic != null && am.setCommunicationDevice(mic)) {
                Log.d(TAG, "Communication device → BUILTIN_MIC")
            }
        } catch (e: Exception) {
            Log.w(TAG, "setCommunicationDevice failed", e)
        }
    }

    private fun clearCommunicationDevice(am: AudioManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            am.clearCommunicationDevice()
        } catch (_: Exception) {
        }
    }

    private fun restoreIfNeeded(context: Context) {
        val mode = savedMode ?: return
        val am = context.getSystemService(AudioManager::class.java) ?: return
        try {
            clearCommunicationDevice(am)
            am.mode = mode
            savedSpeakerphone?.let {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = it
            }
            Log.d(TAG, "Audio mode restored → $mode")
        } catch (e: Exception) {
            Log.w(TAG, "restore failed", e)
        }
    }
}
