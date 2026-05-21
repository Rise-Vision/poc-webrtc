package com.dnovaes.mysharingapp.webrtc

/**
 * Builds exact-size WebRTC PCM frames without zero-padding partial reads (padding causes clicks).
 */
internal class PcmFrameAssembler(private val frameBytes: Int) {

    private val pending = ByteArray(frameBytes * 2)
    private var pendingSize = 0

    fun append(source: ByteArray, offset: Int, length: Int, emit: (ByteArray) -> Unit) {
        var srcPos = offset
        var remaining = length
        while (remaining > 0) {
            val space = frameBytes - pendingSize
            val copy = minOf(remaining, space)
            System.arraycopy(source, srcPos, pending, pendingSize, copy)
            pendingSize += copy
            srcPos += copy
            remaining -= copy
            if (pendingSize == frameBytes) {
                emit(pending.copyOf(frameBytes))
                pendingSize = 0
            }
        }
    }

    fun reset() {
        pendingSize = 0
    }
}
