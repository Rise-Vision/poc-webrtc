package com.dnovaes.mysharingapp.webrtc

import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Device playback capture via [AudioPlaybackCaptureConfiguration].
 */
@RequiresApi(Build.VERSION_CODES.Q)
class PlaybackAudioCapture(
    private val context: android.content.Context,
    mediaProjection: MediaProjection,
) {
    private val projection = mediaProjection
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = java.util.concurrent.locks.ReentrantLock()

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var isRebuilding = false
    private var lastSampleRate = 0
    private var lastBufferSizeBytes = 0
    private var onPlaybackActiveListener: (() -> Unit)? = null
    private var playbackCallbackRegistered = false
    private var lastRebuildElapsedMs = 0L
    private var mediaPlaybackActive = false
    private var refreshAttempts = 0
    private var lastStrategy: CaptureStrategy? = null
    private var lastBuildError: String? = null
    private var cachedKnownUids: List<Int>? = null
    private var allowStrategyCycle = false

    private val debouncedRefreshRunnable = Runnable {
        onPlaybackActiveListener?.invoke()
    }

    private val playbackCallback =
        object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>) {
                val mediaActive = hasMediaUsagePlayback(configs)
                Log.d(
                    TAG,
                    "Playback configs changed: total=${configs.size} mediaActive=$mediaActive",
                )
                if (mediaActive && !mediaPlaybackActive) {
                    mediaPlaybackActive = true
                    scheduleDebouncedRefresh()
                } else if (!mediaActive) {
                    mediaPlaybackActive = false
                }
            }
        }

    val recordForRead: AudioRecord?
        get() = lock.withLock {
            if (isRebuilding || !isRecording) null else audioRecord
        }

    fun isMediaPlaybackActive(): Boolean = mediaPlaybackActive

    fun getLastStrategyLabel(): String {
        val strategy = lastStrategy?.name ?: "none"
        val err = lastBuildError
        return if (err != null) "$strategy ($err)" else strategy
    }

    fun getLastBuildError(): String? = lastBuildError

    fun getAudioSessionId(): Int = lock.withLock { audioRecord?.audioSessionId ?: -1 }

    /**
     * Cycles capture policies for the diagnostic screen (all use [AudioPlaybackCaptureConfiguration]).
     */
    fun tryNextCaptureStrategyForTest(): String {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            var label = ""
            val done = Object()
            mainHandler.post {
                synchronized(done) {
                    label = tryNextCaptureStrategyForTest()
                    done.notify()
                }
            }
            synchronized(done) {
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                (done as Object).wait(3_000)
            }
            return label
        }
        lock.withLock {
            refreshAttempts = (refreshAttempts % 3) + 1
            lastRebuildElapsedMs = 0L
            releaseCaptureInternal()
            val strategy = strategyForAttempt(refreshAttempts)
            if (!rebuildInternal(strategy)) return "FAILED:$strategy"
            startRecordingInternal()
            return strategy.name
        }
    }

    fun getActivePlaybackSummary(): String {
        return try {
            val configs = audioManager.activePlaybackConfigurations
            if (configs.isEmpty()) return "No active playback"
            val header = "Active playback (uid/pkg often hidden on API 34+):\n"
            header + configs.joinToString("\n") { config ->
                val attrs = config.audioAttributes
                "usage=${attrs.usage} uid=${reflectClientUid(config)} " +
                    "pkg=${reflectClientPackageName(config)} session=${reflectAudioSessionId(config)}"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    fun configure(sampleRate: Int, channelCount: Int, bufferSizeBytes: Int = 0) {
        lastSampleRate = sampleRate
        lastBufferSizeBytes = bufferSizeBytes
        if (cachedKnownUids == null) {
            Thread({
                cachedKnownUids = discoverKnownUids()
                Log.d(TAG, "Cached known UIDs: $cachedKnownUids")
            }, "playback-uid-cache").start()
        }
    }

    fun setOnPlaybackActiveListener(listener: (() -> Unit)?) {
        onPlaybackActiveListener = listener
        registerPlaybackCallback()
    }

    fun resetRefreshCycle() {
        refreshAttempts = 0
    }

    /** Allows cycling strategies in [PlaybackCaptureTestActivity] only. */
    fun enableStrategyCycleForTest() {
        allowStrategyCycle = true
    }

    fun refreshAndStartOnMain() {
        mainHandler.post { refreshAndStartInternal(incrementAttempt = true) }
    }

    fun refreshAndStartBlocking(): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return refreshAndStartInternal(incrementAttempt = true)
        }
        var ok = false
        val done = Object()
        mainHandler.post {
            synchronized(done) {
                ok = refreshAndStartInternal(incrementAttempt = true)
                done.notify()
            }
        }
        synchronized(done) {
            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
            (done as Object).wait(3_000)
        }
        return ok
    }

    fun scheduleDebouncedRefresh() {
        if (refreshAttempts >= MAX_REFRESH_ATTEMPTS) {
            Log.w(TAG, "Skip refresh (max attempts reached)")
            return
        }
        mainHandler.removeCallbacks(debouncedRefreshRunnable)
        mainHandler.postDelayed(debouncedRefreshRunnable, REFRESH_DEBOUNCE_MS)
    }

    fun isReady(): Boolean = lock.withLock { audioRecord != null && !isRebuilding }

    fun releaseCapture() {
        runOnMain { releaseCaptureInternal() }
    }

    /** Must complete before starting the WebRTC mic — only one capture policy at a time. */
    fun releaseCaptureBlocking() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            releaseCaptureInternal()
            return
        }
        val latch = CountDownLatch(1)
        mainHandler.post {
            releaseCaptureInternal()
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
    }

    fun release() {
        mainHandler.removeCallbacks(debouncedRefreshRunnable)
        unregisterPlaybackCallback()
        runOnMain { releaseCaptureInternal() }
    }

    private fun refreshAndStartInternal(incrementAttempt: Boolean): Boolean {
        lock.withLock {
            if (incrementAttempt) refreshAttempts++
            val now = SystemClock.elapsedRealtime()
            if (now - lastRebuildElapsedMs < MIN_REBUILD_INTERVAL_MS && audioRecord != null) {
                Log.d(TAG, "Rebuild throttled")
                return startRecordingInternal()
            }
            lastRebuildElapsedMs = now
            releaseCaptureInternal()
            val strategy = strategyForAttempt(refreshAttempts)
            if (!rebuildInternal(strategy)) return false
            return startRecordingInternal()
        }
    }

    private fun strategyForAttempt(attempt: Int): CaptureStrategy {
        if (!allowStrategyCycle) {
            return CaptureStrategy.PLAYBACK_USAGE_MEDIA
        }
        return when (attempt) {
            1 -> CaptureStrategy.PLAYBACK_USAGE_MEDIA
            2 -> CaptureStrategy.PLAYBACK_USAGE_MEDIA_WITH_KNOWN_UIDS
            else -> CaptureStrategy.PLAYBACK_ALL_USAGES
        }
    }

    private enum class CaptureStrategy {
        /** No UID filters — preferred on Samsung REMOTE_SUBMIX. */
        PLAYBACK_USAGE_MEDIA,
        /** USAGE_MEDIA/GAME + installed media app UIDs (YouTube, Chrome, …). */
        PLAYBACK_USAGE_MEDIA_WITH_KNOWN_UIDS,
        /** Broad usage list, no per-app UIDs. */
        PLAYBACK_ALL_USAGES,
    }

    private fun rebuildInternal(strategy: CaptureStrategy): Boolean {
        isRebuilding = true
        lastBuildError = null
        try {
            val record = buildAudioRecord(lastSampleRate, lastBufferSizeBytes, strategy)
            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                val reason = lastBuildError ?: "AudioRecord not initialized"
                lastBuildError = reason
                Log.e(TAG, "AudioRecord init failed strategy=$strategy: $reason")
                record?.release()
                audioRecord = null
                return false
            }
            audioRecord = record
            lastStrategy = strategy
            logActivePlaybackConfigs()
            Log.d(
                TAG,
                "Capture ready strategy=$strategy @ ${lastSampleRate}Hz ch=${record.channelCount} " +
                    "session=${record.audioSessionId}",
            )
            return true
        } finally {
            isRebuilding = false
        }
    }

    private fun startRecordingInternal(): Boolean {
        val record = audioRecord ?: return false
        if (isRecording) return true
        return try {
            record.startRecording()
            isRecording = record.recordingState == AudioRecord.RECORDSTATE_RECORDING
            Log.d(TAG, "startRecording ok=$isRecording strategy=$lastStrategy ch=${record.channelCount}")
            isRecording
        } catch (e: IllegalStateException) {
            Log.e(TAG, "startRecording failed strategy=$lastStrategy", e)
            isRecording = false
            false
        }
    }

    private fun releaseCaptureInternal() {
        isRebuilding = true
        try {
            audioRecord?.let { record ->
                try {
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        record.stop()
                    }
                } catch (_: IllegalStateException) {
                }
                record.release()
            }
            audioRecord = null
            isRecording = false
        } finally {
            isRebuilding = false
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun buildAudioRecord(
        sampleRate: Int,
        bufferSizeBytes: Int,
        strategy: CaptureStrategy,
    ): AudioRecord? {
        val channelMask = AudioFormat.CHANNEL_IN_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minBuffer <= 0) return null
        val bufferBytes = maxOf(minBuffer * 4, bufferSizeBytes * 2, minBuffer)
        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()

        return buildPlaybackCaptureRecord(format, bufferBytes, strategy)
    }

    private fun buildPlaybackCaptureRecord(
        format: AudioFormat,
        bufferBytes: Int,
        strategy: CaptureStrategy,
    ): AudioRecord? {
        return try {
            val captureBuilder = AudioPlaybackCaptureConfiguration.Builder(projection)
            when (strategy) {
                CaptureStrategy.PLAYBACK_USAGE_MEDIA -> {
                    captureBuilder.addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    captureBuilder.addMatchingUsage(AudioAttributes.USAGE_GAME)
                }
                CaptureStrategy.PLAYBACK_USAGE_MEDIA_WITH_KNOWN_UIDS -> {
                    captureBuilder.addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    captureBuilder.addMatchingUsage(AudioAttributes.USAGE_GAME)
                    addKnownPackageUids(captureBuilder)
                }
                CaptureStrategy.PLAYBACK_ALL_USAGES -> {
                    for (usage in CAPTURE_USAGES) {
                        captureBuilder.addMatchingUsage(usage)
                    }
                }
            }
            val recordBuilder = AudioRecord.Builder()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                recordBuilder.setContext(context)
            }
            recordBuilder
                .setAudioPlaybackCaptureConfig(captureBuilder.build())
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .build()
        } catch (e: Exception) {
            lastBuildError = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "buildPlaybackCaptureRecord failed strategy=$strategy", e)
            null
        }
    }

    private fun addKnownPackageUids(builder: AudioPlaybackCaptureConfiguration.Builder) {
        val uids = cachedKnownUids ?: discoverKnownUids().also { cachedKnownUids = it }
        for (uid in uids) {
            builder.addMatchingUid(uid)
        }
        Log.d(TAG, "Applied known media UIDs: $uids")
    }

    private fun discoverKnownUids(): List<Int> {
        val myUid = Process.myUid()
        val uids = linkedSetOf<Int>()
        for (packageName in discoverMediaPackages()) {
            val uid = packageUid(packageName) ?: continue
            if (uid <= 0 || uid == myUid) continue
            uids.add(uid)
        }
        return uids.toList()
    }

    private fun discoverMediaPackages(): List<String> {
        val found = linkedSetOf<String>()
        found.addAll(KNOWN_MEDIA_PACKAGES)
        try {
            val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getInstalledApplications(
                    PackageManager.ApplicationInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstalledApplications(0)
            }
            for (app in installed) {
                val name = app.packageName
                if (MEDIA_PACKAGE_KEYWORDS.any { keyword -> name.contains(keyword, ignoreCase = true) }) {
                    found.add(name)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Package discovery failed", e)
        }
        return found.toList()
    }

    private fun packageUid(packageName: String): Int? {
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(packageName, 0)
            }
            info.uid
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun hasMediaUsagePlayback(
        configs: List<android.media.AudioPlaybackConfiguration>,
    ): Boolean = configs.any { it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA }

    private fun registerPlaybackCallback() {
        if (playbackCallbackRegistered) return
        try {
            audioManager.registerAudioPlaybackCallback(playbackCallback, mainHandler)
            playbackCallbackRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "registerAudioPlaybackCallback failed", e)
        }
    }

    private fun unregisterPlaybackCallback() {
        if (!playbackCallbackRegistered) return
        try {
            audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        } catch (_: Exception) {
        }
        playbackCallbackRegistered = false
    }

    private fun logActivePlaybackConfigs() {
        try {
            val configs = audioManager.activePlaybackConfigurations
            Log.d(TAG, "Active playback (${configs.size}):")
            for (config in configs) {
                val attrs = config.audioAttributes
                Log.d(
                    TAG,
                    "  uid=${reflectClientUid(config)} pkg=${reflectClientPackageName(config)} " +
                        "usage=${attrs.usage} session=${reflectAudioSessionId(config)}",
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "log configs failed", e)
        }
    }

    private fun reflectClientUid(config: Any): Int? {
        val uid = invokeIntMethod(config, "getClientUid") ?: return null
        return if (uid > 0) uid else null
    }

    private fun reflectClientPackageName(config: Any): String? =
        invokeStringMethod(config, "getClientPackageName")

    private fun reflectAudioSessionId(config: Any): Int? =
        invokeIntMethod(config, "getAudioSessionId")

    private fun invokeIntMethod(target: Any, methodName: String): Int? {
        return try {
            val method = target.javaClass.getMethod(methodName)
            method.invoke(target) as Int
        } catch (_: ReflectiveOperationException) {
            null
        }
    }

    private fun invokeStringMethod(target: Any, methodName: String): String? {
        return try {
            val method = target.javaClass.getMethod(methodName)
            method.invoke(target) as String
        } catch (_: ReflectiveOperationException) {
            null
        }
    }

    private inline fun <T> java.util.concurrent.locks.ReentrantLock.withLock(block: () -> T): T {
        lock()
        try {
            return block()
        } finally {
            unlock()
        }
    }

    companion object {
        private const val TAG = "PlaybackAudioCapture"
        private const val REFRESH_DEBOUNCE_MS = 1_200L
        private const val MIN_REBUILD_INTERVAL_MS = 3_000L
        private const val MAX_REFRESH_ATTEMPTS = 3
        const val PERMISSION_CAPTURE_MEDIA_OUTPUT = "android.permission.CAPTURE_MEDIA_OUTPUT"

        private val MEDIA_PACKAGE_KEYWORDS = listOf(
            "youtube",
            "chrome",
            "firefox",
            "spotify",
            "netflix",
            "music",
            "browser",
        )

        private val KNOWN_MEDIA_PACKAGES = listOf(
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music",
            "com.android.chrome",
            "com.google.android.apps.chrome",
            "org.mozilla.firefox",
            "com.google.android.googlequicksearchbox",
            "com.spotify.music",
            "com.netflix.mediaclient",
        )

        private val CAPTURE_USAGES = intArrayOf(
            AudioAttributes.USAGE_UNKNOWN,
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_ASSISTANT,
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
            AudioAttributes.USAGE_ALARM,
            AudioAttributes.USAGE_NOTIFICATION,
            AudioAttributes.USAGE_NOTIFICATION_EVENT,
            AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST,
            AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT,
            AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED,
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
        )
    }
}
