package com.dnovaes.mysharingapp.webrtc

import java.util.concurrent.atomic.AtomicBoolean

/**
 * When [microphoneMuted] is true (default), only device playback is sent to peers.
 * Microphone PCM from WebRTC is not mixed into the outgoing stream.
 */
class MicrophoneMuteState(
    microphoneMuted: Boolean = true,
) {
    val microphoneMuted = AtomicBoolean(microphoneMuted)
}
