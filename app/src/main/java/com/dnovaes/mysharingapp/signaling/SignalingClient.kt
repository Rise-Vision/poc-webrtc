package com.dnovaes.mysharingapp.signaling

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

sealed class SignalingEvent {
    data object PeerReady : SignalingEvent()
    data class Offer(val sdp: String) : SignalingEvent()
    data class Answer(val sdp: String) : SignalingEvent()
    data class IceCandidate(
        val candidate: String,
        val sdpMid: String?,
        val sdpMLineIndex: Int,
    ) : SignalingEvent()

    data class Error(val message: String) : SignalingEvent()
    data object Closed : SignalingEvent()
}

class SignalingClient(
    private val okHttpClient: OkHttpClient = OkHttpClient(),
) {
    private var webSocket: WebSocket? = null
    private val events = Channel<SignalingEvent>(Channel.BUFFERED)

    val eventsFlow: Flow<SignalingEvent> = events.receiveAsFlow()

    fun connect(wsUrl: String, room: String, role: String = "publisher") {
        disconnect()
        Log.d(TAG, "connecting wsUrl=$wsUrl room=$room role=$role")
        val request = Request.Builder().url(wsUrl).build()
        webSocket = okHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket open http=${response.code} ${response.message}")
                    sendJson(
                        JSONObject()
                            .put("type", "join")
                            .put("room", room)
                            .put("role", role),
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val json = JSONObject(text)
                    val type = json.optString("type")
                    when (type) {
                        "joined" -> Log.d(
                            TAG,
                            "recv joined room=${json.optString("room")} role=${json.optString("role")}",
                        )
                        "peer-ready" -> {
                            Log.d(TAG, "recv peer-ready")
                            events.trySend(SignalingEvent.PeerReady)
                        }
                        "offer" -> {
                            val sdp = json.getString("sdp")
                            Log.d(TAG, "recv offer sdpLen=${sdp.length}")
                            events.trySend(SignalingEvent.Offer(sdp))
                        }
                        "answer" -> {
                            val sdp = json.getString("sdp")
                            Log.d(TAG, "recv answer sdpLen=${sdp.length}")
                            events.trySend(SignalingEvent.Answer(sdp))
                        }
                        "ice" -> {
                            Log.d(TAG, "recv ice mid=${json.optString("sdpMid")} mLine=${json.optInt("sdpMLineIndex")}")
                            events.trySend(
                                SignalingEvent.IceCandidate(
                                    candidate = json.getString("candidate"),
                                    sdpMid = json.optString("sdpMid").ifEmpty { null },
                                    sdpMLineIndex = json.getInt("sdpMLineIndex"),
                                ),
                            )
                        }
                        "error" -> {
                            val msg = json.optString("message", "Unknown error")
                            Log.w(TAG, "recv error: $msg")
                            events.trySend(SignalingEvent.Error(msg))
                        }
                        else -> Log.w(TAG, "recv unknown type=$type raw=${text.take(120)}")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket failure http=${response?.code} ${t.message}", t)
                    events.trySend(SignalingEvent.Error(t.message ?: "WebSocket failure"))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closed code=$code reason=$reason")
                    events.trySend(SignalingEvent.Closed)
                }
            },
        )
    }

    fun sendOffer(sdp: String) {
        sendJson(JSONObject().put("type", "offer").put("sdp", sdp))
    }

    fun sendAnswer(sdp: String) {
        sendJson(JSONObject().put("type", "answer").put("sdp", sdp))
    }

    fun sendIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        sendJson(
            JSONObject()
                .put("type", "ice")
                .put("candidate", candidate)
                .put("sdpMid", sdpMid ?: "")
                .put("sdpMLineIndex", sdpMLineIndex),
        )
    }

    fun disconnect() {
        Log.d(TAG, "disconnect")
        webSocket?.close(1000, "bye")
        webSocket = null
    }

    private fun sendJson(json: JSONObject) {
        val type = json.optString("type")
        when (type) {
            "offer", "answer" -> Log.d(TAG, "send $type sdpLen=${json.optString("sdp").length}")
            "ice" -> Log.d(TAG, "send ice mid=${json.optString("sdpMid")} mLine=${json.optInt("sdpMLineIndex")}")
            else -> Log.d(TAG, "send $type")
        }
        val sent = webSocket?.send(json.toString()) ?: false
        if (!sent) Log.w(TAG, "send failed (socket null or queue full) type=$type")
    }

    companion object {
        private const val TAG = "SignalingClient"
    }
}
