package com.dnovaes.mysharingapp.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import com.dnovaes.mysharingapp.signaling.SignalingClient
import com.dnovaes.mysharingapp.signaling.SignalingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.AudioRecordDataCallback
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteOrder

class ScreenSharePublisher(
    private val context: Context,
    private val signalingClient: SignalingClient,
    private val onStatus: (String) -> Unit,
) {
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val eglBase = EglBase.create()
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var audioDeviceModule: AudioDeviceModule? = null
    private var playbackAudioCapture: PlaybackAudioCapture? = null
    private var mediaProjection: MediaProjection? = null
    private var offerCreated = false
    private var peerReadyReceived = false

    fun start(
        resultCode: Int,
        resultData: Intent,
        mediaProjection: MediaProjection,
        wsUrl: String,
        room: String,
    ) {
        stop()
        // stop() cancels scope; recreate so observeSignaling() actually collects events.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        this.mediaProjection = mediaProjection
        Log.d(TAG, "start wsUrl=$wsUrl room=$room")
        // WebRTC must be ready before signaling: peer-ready can arrive immediately on connect.
        initWebRtc(resultCode, resultData, mediaProjection)
        observeSignaling()
        signalingClient.connect(wsUrl, room, role = "publisher")
        onStatus("Connected to signaling. Waiting for browser viewer…")
    }

    fun stop() {
        Log.d(TAG, "stop")
        offerCreated = false
        peerReadyReceived = false
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoCapturer = null
        videoSource?.dispose()
        videoSource = null
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        playbackAudioCapture?.stop()
        playbackAudioCapture = null
        audioDeviceModule?.release()
        audioDeviceModule = null
        mediaProjection?.stop()
        mediaProjection = null
        signalingClient.disconnect()
        scope.cancel()
        onStatus("Stopped")
    }

    private fun initWebRtc(
        resultCode: Int,
        resultData: Intent,
        projection: MediaProjection,
    ) {
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        audioDeviceModule = createAudioDeviceModule(projection)

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext,
            true,
            true,
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()

        val factory = peerConnectionFactory ?: return

        videoCapturer = ScreenCapturerAndroid(resultData, object : MediaProjection.Callback() {
            override fun onStop() {
                onStatus("Screen capture ended by system")
                stop()
            }
        })
        val surfaceHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglBase.eglBaseContext)
        videoSource = factory.createVideoSource(videoCapturer!!.isScreencast)
        videoCapturer!!.initialize(surfaceHelper, context, videoSource!!.capturerObserver)
        videoCapturer!!.startCapture(1280, 720, 30)

        val videoTrack: VideoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource)
        videoTrack.setEnabled(true)

        val audioConstraints = MediaConstraints()
        val audioSource = factory.createAudioSource(audioConstraints)
        val audioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, audioSource)
        audioTrack.setEnabled(true)

        peerConnection = factory.createPeerConnection(
            rtcConfig(),
            object : PeerConnection.Observer {
                override fun onSignalingChange(newState: PeerConnection.SignalingState?) {
                    Log.d(TAG, "signalingState=$newState")
                }
                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                    Log.d(TAG, "iceConnectionState=$newState")
                    onStatus("ICE: $newState")
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {
                    Log.d(TAG, "iceReceiving=$receiving")
                }
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
                    Log.d(TAG, "iceGatheringState=$newState")
                }
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate ?: return
                    Log.d(TAG, "localIce mid=${candidate.sdpMid} mLine=${candidate.sdpMLineIndex} ${candidate.sdp.take(80)}")
                    signalingClient.sendIceCandidate(
                        candidate.sdp,
                        candidate.sdpMid,
                        candidate.sdpMLineIndex,
                    )
                }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
                override fun onAddStream(stream: org.webrtc.MediaStream?) = Unit
                override fun onRemoveStream(stream: org.webrtc.MediaStream?) = Unit
                override fun onDataChannel(channel: org.webrtc.DataChannel?) = Unit
                override fun onRenegotiationNeeded() = Unit
                override fun onAddTrack(
                    receiver: org.webrtc.RtpReceiver?,
                    mediaStreams: Array<out org.webrtc.MediaStream>?,
                ) = Unit
            },
        )

        peerConnection?.addTrack(videoTrack, listOf("stream"))
        peerConnection?.addTrack(audioTrack, listOf("stream"))
        Log.d(TAG, "PeerConnection created, tracks added (video+audio)")
        if (peerReadyReceived) {
            Log.d(TAG, "peer-ready arrived early — creating offer now")
            createAndSendOffer()
        }
    }

    private fun createAudioDeviceModule(projection: MediaProjection): AudioDeviceModule {
        val builder = JavaAudioDeviceModule.builder(context)
            .setUseStereoInput(true)
            .setUseStereoOutput(true)
            .setUseHardwareNoiseSuppressor(false)
            .setUseHardwareAcousticEchoCanceler(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val capture = PlaybackAudioCapture(projection)
            if (capture.start()) {
                playbackAudioCapture = capture
                onStatus("Capturing system audio (playback capture)")
                builder.setAudioRecordDataCallback(PlaybackAudioInjector(capture))
            } else {
                onStatus("System audio unavailable — sharing video only")
                builder.setAudioRecordDataCallback(silentAudioCallback)
            }
        } else {
            onStatus("API < 29: system audio capture not supported")
            builder.setAudioRecordDataCallback(silentAudioCallback)
        }

        return builder.createAudioDeviceModule()
    }

    private val silentAudioCallback = AudioRecordDataCallback { _, _, _, audioBuffer ->
        val silence = ByteArray(audioBuffer.remaining())
        audioBuffer.clear()
        audioBuffer.put(silence)
        audioBuffer.order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun observeSignaling() {
        signalingClient.eventsFlow
            .onEach { event ->
                when (event) {
                    SignalingEvent.PeerReady -> {
                        peerReadyReceived = true
                        Log.d(TAG, "signaling: peer-ready → creating offer")
                        createAndSendOffer()
                    }
                    is SignalingEvent.Answer -> {
                        Log.d(TAG, "signaling: answer sdpLen=${event.sdp.length}")
                        setRemoteDescription(
                            SessionDescription(SessionDescription.Type.ANSWER, event.sdp),
                        )
                    }
                    is SignalingEvent.IceCandidate -> {
                        Log.d(TAG, "signaling: remoteIce mid=${event.sdpMid} mLine=${event.sdpMLineIndex}")
                        peerConnection?.addIceCandidate(
                            IceCandidate(event.sdpMid, event.sdpMLineIndex, event.candidate),
                        )
                    }
                    is SignalingEvent.Error -> {
                        Log.e(TAG, "signaling error: ${event.message}")
                        onStatus("Error: ${event.message}")
                    }
                    SignalingEvent.Closed -> {
                        Log.d(TAG, "signaling closed")
                        onStatus("Signaling closed")
                    }
                    is SignalingEvent.Offer -> Log.w(TAG, "signaling: unexpected offer as publisher")
                }
            }
            .launchIn(scope)
    }

    private fun createAndSendOffer() {
        if (offerCreated) {
            Log.d(TAG, "createOffer skipped (already sent)")
            return
        }
        val pc = peerConnection
        if (pc == null) {
            Log.w(TAG, "createOffer deferred — PeerConnection not ready yet")
            return
        }
        offerCreated = true
        Log.d(TAG, "createOffer…")
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        pc.createOffer(
            object : SdpObserverAdapter() {
                override fun onCreateSuccess(description: SessionDescription?) {
                    description ?: return
                    Log.d(TAG, "offer created sdpLen=${description.description.length}")
                    pc.setLocalDescription(
                        object : SdpObserverAdapter() {
                            override fun onSetSuccess() {
                                Log.d(TAG, "localDescription set (offer)")
                                signalingClient.sendOffer(description.description)
                                onStatus("Offer sent — check browser viewer")
                            }
                        },
                        description,
                    )
                }
            },
            constraints,
        )
    }

    private fun setRemoteDescription(description: SessionDescription) {
        Log.d(TAG, "setRemoteDescription type=${description.type} sdpLen=${description.description.length}")
        peerConnection?.setRemoteDescription(
            object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    Log.d(TAG, "remoteDescription set (answer)")
                    onStatus("Remote answer applied")
                }
            },
            description,
        )
    }

    private fun rtcConfig(): PeerConnection.RTCConfiguration {
        return PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()),
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
    }

    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(sessionDescription: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) {
            Log.e(TAG, "SDP create failed: $error")
        }
        override fun onSetFailure(error: String?) {
            Log.e(TAG, "SDP set failed: $error")
        }
    }

    companion object {
        private const val TAG = "ScreenSharePublisher"
        private const val VIDEO_TRACK_ID = "screen_video"
        private const val AUDIO_TRACK_ID = "screen_audio"
    }
}
