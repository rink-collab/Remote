package com.example.webrtc

import android.content.Context
import android.util.Log
import com.example.signaling.IceCandidateData
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnection.IceServer
import org.webrtc.PeerConnection.PeerConnectionState
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

interface WebRTCListener {
    fun onIceCandidateGenerated(candidate: IceCandidateData)
    fun onConnectionStateChanged(state: PeerConnectionState)
    fun onDataChannelStateChanged(isOpen: Boolean)
    fun onMessageReceived(message: String)
}

class WebRTCManager(
    private val context: Context,
    private val listener: WebRTCListener
) {
    private val TAG = "WebRTCManager"

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    init {
        initializeFactory()
    }

    private fun initializeFactory() {
        try {
            val initOptions = PeerConnectionFactory.InitializationOptions
                .builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(initOptions)

            val options = PeerConnectionFactory.Options()
            factory = PeerConnectionFactory.builder()
                .setOptions(options)
                .createPeerConnectionFactory()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WebRTC factory", e)
        }
    }

    fun createPeerConnection(isHost: Boolean): Boolean {
        val f = factory ?: return false

        val iceServers = listOf(
            IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pcObserver = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Log.d(TAG, "Signaling state: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE connection state: $state")
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "ICE gathering state: $state")
            }

            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null) {
                    listener.onIceCandidateGenerated(
                        IceCandidateData(
                            sdpMid = candidate.sdpMid,
                            sdpMLineIndex = candidate.sdpMLineIndex,
                            sdp = candidate.sdp
                        )
                    )
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onAddStream(stream: org.webrtc.MediaStream?) {}

            override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}

            override fun onDataChannel(channel: DataChannel?) {
                Log.d(TAG, "Incoming remote DataChannel created")
                if (channel != null) {
                    setupDataChannel(channel)
                }
            }

            override fun onRenegotiationNeeded() {
                Log.d(TAG, "Renegotiation needed")
            }

            override fun onConnectionChange(newState: PeerConnectionState?) {
                Log.d(TAG, "PeerConnection state changed: $newState")
                if (newState != null) {
                    listener.onConnectionStateChanged(newState)
                }
            }

            override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {}
        }

        peerConnection = f.createPeerConnection(rtcConfig, pcObserver)

        if (isHost) {
            // Host creates the initial Data Channel
            val dcInit = DataChannel.Init().apply {
                ordered = true
                negotiated = false
            }
            val dc = peerConnection?.createDataChannel("media_channel", dcInit)
            if (dc != null) {
                setupDataChannel(dc)
            }
        }

        return peerConnection != null
    }

    private fun setupDataChannel(channel: DataChannel) {
        dataChannel?.dispose()
        dataChannel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                val state = channel.state()
                Log.d(TAG, "DataChannel state: $state")
                listener.onDataChannelStateChanged(state == DataChannel.State.OPEN)
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                try {
                    val data = buffer.data
                    val bytes = ByteArray(data.remaining())
                    data.get(bytes)
                    val message = String(bytes, StandardCharsets.UTF_8)
                    listener.onMessageReceived(message)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing incoming data channel buffer", e)
                }
            }
        })
    }

    fun createOffer(onSuccess: (SessionDescription) -> Unit, onError: (String) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc != null) {
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            onSuccess(desc)
                        }
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(err: String?) {
                            onError("Failed to set local description: $err")
                        }
                    }, desc)
                } else {
                    onError("Created offer was null")
                }
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) {
                onError("Failed to create offer: $err")
            }
            override fun onSetFailure(err: String?) {}
        }, constraints)
    }

    fun handleRemoteOffer(offerSdp: String, onAnswerCreated: (SessionDescription) -> Unit, onError: (String) -> Unit) {
        val sessionDesc = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                // Now create answer
                val constraints = MediaConstraints()
                peerConnection?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answerDesc: SessionDescription?) {
                        if (answerDesc != null) {
                            peerConnection?.setLocalDescription(object : SdpObserver {
                                override fun onCreateSuccess(p0: SessionDescription?) {}
                                override fun onSetSuccess() {
                                    onAnswerCreated(answerDesc)
                                }
                                override fun onCreateFailure(p0: String?) {}
                                override fun onSetFailure(err: String?) {
                                    onError("Set local answer failure: $err")
                                }
                            }, answerDesc)
                        } else {
                            onError("Answer was null")
                        }
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(err: String?) {
                        onError("Create answer failure: $err")
                    }
                    override fun onSetFailure(p0: String?) {}
                }, constraints)
            }

            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(err: String?) {
                onError("Set remote offer failure: $err")
            }
        }, sessionDesc)
    }

    fun handleRemoteAnswer(answerSdp: String, onError: (String) -> Unit) {
        val sessionDesc = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                Log.d(TAG, "Remote answer set successfully")
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(err: String?) {
                onError("Set remote answer failure: $err")
            }
        }, sessionDesc)
    }

    fun addRemoteIceCandidate(candidate: IceCandidateData) {
        try {
            val iceCandidate = IceCandidate(
                candidate.sdpMid,
                candidate.sdpMLineIndex,
                candidate.sdp
            )
            peerConnection?.addIceCandidate(iceCandidate)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding ICE candidate", e)
        }
    }

    fun sendMessage(text: String): Boolean {
        val dc = dataChannel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false

        return try {
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            val buffer = DataChannel.Buffer(ByteBuffer.wrap(bytes), false)
            dc.send(buffer)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending data channel message", e)
            false
        }
    }

    fun isDataChannelOpen(): Boolean {
        return dataChannel?.state() == DataChannel.State.OPEN
    }

    fun close() {
        try {
            dataChannel?.unregisterObserver()
            dataChannel?.close()
            dataChannel?.dispose()
            dataChannel = null

            peerConnection?.close()
            peerConnection?.dispose()
            peerConnection = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing WebRTC connection", e)
        }
    }
}
