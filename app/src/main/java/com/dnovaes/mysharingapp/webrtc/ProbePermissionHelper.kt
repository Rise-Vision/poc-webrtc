package com.dnovaes.mysharingapp.webrtc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime permissions for the playback-capture probe.
 *
 * [PlaybackAudioCapture.PERMISSION_CAPTURE_MEDIA_OUTPUT] is **not** requested here — it is an
 * app-op style permission on Android 14+ and is tied to MediaProjection consent, not a dialog.
 */
object ProbePermissionHelper {

    fun missingRuntimePermissions(context: Context): List<String> = buildList {
        if (!hasRecordAudio(context)) {
            add(Manifest.permission.RECORD_AUDIO)
        }
        if (!hasPostNotifications(context)) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun hasAllRuntimePermissions(context: Context): Boolean =
        missingRuntimePermissions(context).isEmpty()

    fun hasRecordAudio(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun hasPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun describePermission(permission: String): String = when (permission) {
        Manifest.permission.RECORD_AUDIO -> "Microphone"
        Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
        else -> permission.substringAfterLast('.')
    }

    fun evaluateGrantResults(
        requested: Array<String>,
        results: Map<String, Boolean>,
    ): GrantEvaluation {
        val denied = requested.filter { results[it] != true }
        val recordOk = !requested.contains(Manifest.permission.RECORD_AUDIO) ||
            results[Manifest.permission.RECORD_AUDIO] == true
        val notifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            !requested.contains(Manifest.permission.POST_NOTIFICATIONS) ||
                results[Manifest.permission.POST_NOTIFICATIONS] == true
        } else {
            true
        }
        return GrantEvaluation(
            allGranted = recordOk && notifOk,
            deniedPermissions = denied,
            deniedLabels = denied.map { describePermission(it) },
        )
    }

    fun buildDeniedMessage(deniedLabels: List<String>): String {
        val bullets = deniedLabels.joinToString("\n") { "• $it" }
        return buildString {
            appendLine("Please allow:")
            appendLine(bullets)
            appendLine()
            appendLine("Microphone — Android requires this to open playback-capture audio.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appendLine(
                    "Notifications — required while the screen-capture foreground service runs.",
                )
            }
            appendLine()
            append("Tap Try again for the system permission prompt, or App settings to grant manually.")
        }
    }

    data class GrantEvaluation(
        val allGranted: Boolean,
        val deniedPermissions: List<String>,
        val deniedLabels: List<String>,
    )
}
