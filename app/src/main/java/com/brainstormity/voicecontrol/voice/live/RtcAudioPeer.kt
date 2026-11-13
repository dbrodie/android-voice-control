package com.brainstormity.voicecontrol.voice.live

import android.content.Context
import com.brainstormity.voicecontrol.core.VcLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One WebRTC peer with a single sendrecv Opus audio track (the microphone) and the `oai-events` data channel,
 * mirroring opencode-gpt-live `native/src/transport.rs` (itself modelled on Codex's voice helper):
 * - non-trickle ICE: no ICE servers, gather host candidates (UDP + TCP), then send the complete offer;
 * - the remote (GPT) audio track is played by libwebrtc's JavaAudioDeviceModule (AudioTrack, 48 kHz Opus);
 * - mic capture uses VOICE_COMMUNICATION + hardware AEC/NS when available, plus libwebrtc's software
 *   echo cancellation / noise suppression / AGC / high-pass (the `goog*` audio constraints);
 * - "ready" = data channel open or peer connected.
 */
class RtcAudioPeer(private val context: Context) {
    private var adm: JavaAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var dataChannel: DataChannel? = null
    private val disposed = AtomicBoolean(false)

    private val gathered = CompletableDeferred<Unit>()
    private val ready = CompletableDeferred<Unit>()
    private val _peerState = MutableStateFlow(PeerConnection.PeerConnectionState.NEW)
    val peerState: StateFlow<PeerConnection.PeerConnectionState> = _peerState

    /** Text messages from the `oai-events` data channel (GPT-Live server events). */
    var onDataChannelMessage: ((String) -> Unit)? = null
    /** Peer went to FAILED/CLOSED after having connected (or DISCONNECTED for a while). */
    var onPeerLost: ((String) -> Unit)? = null

    val dataChannelOpen: Boolean get() = dataChannel?.state() == DataChannel.State.OPEN

    fun create() {
        ensureInitialized(context)
        val admodule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setUseStereoInput(false)
            .setUseStereoOutput(false)
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(msg: String) = VcLog.e(TAG, "mic init error: $msg")
                override fun onWebRtcAudioRecordStartError(
                    code: JavaAudioDeviceModule.AudioRecordStartErrorCode, msg: String,
                ) = VcLog.e(TAG, "mic start error $code: $msg")
                override fun onWebRtcAudioRecordError(msg: String) = VcLog.e(TAG, "mic error: $msg")
            })
            .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(msg: String) = VcLog.e(TAG, "speaker init error: $msg")
                override fun onWebRtcAudioTrackStartError(
                    code: JavaAudioDeviceModule.AudioTrackStartErrorCode, msg: String,
                ) = VcLog.e(TAG, "speaker start error $code: $msg")
                override fun onWebRtcAudioTrackError(msg: String) = VcLog.e(TAG, "speaker error: $msg")
            })
            .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
                override fun onWebRtcAudioRecordStart() {
                    VcLog.d(TAG, "mic capture started")
                    // Runs on the capture thread before its read loop; debug builds may swap the mic source here.
                    adm?.let { m -> debugMicStartedHook?.invoke(m) }
                }
                override fun onWebRtcAudioRecordStop() = VcLog.d(TAG, "mic capture stopped")
            })
            .setAudioTrackStateCallback(object : JavaAudioDeviceModule.AudioTrackStateCallback {
                override fun onWebRtcAudioTrackStart() = VcLog.d(TAG, "speaker playout started")
                override fun onWebRtcAudioTrackStop() = VcLog.d(TAG, "speaker playout stopped")
            })
            .createAudioDeviceModule()
        adm = admodule
        val f = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setAudioDeviceModule(admodule)
            .createPeerConnectionFactory()
        factory = f

        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        val peer = f.createPeerConnection(config, observer)
            ?: throw LiveException("could not create the WebRTC peer connection")
        pc = peer

        val constraints = MediaConstraints().apply {
            mandatory += MediaConstraints.KeyValuePair("googEchoCancellation", "true")
            mandatory += MediaConstraints.KeyValuePair("googAutoGainControl", "true")
            mandatory += MediaConstraints.KeyValuePair("googNoiseSuppression", "true")
            mandatory += MediaConstraints.KeyValuePair("googHighpassFilter", "true")
        }
        val source = f.createAudioSource(constraints)
        audioSource = source
        val track = f.createAudioTrack("gpt-live-mic", source)
        audioTrack = track
        // addTrack -> one sendrecv audio transceiver (mic up, GPT voice down), like the Rust helper.
        peer.addTrack(track, listOf("gpt-live"))

        // Create the event channel before the offer (the server expects it, as in the official quickstart).
        val dc = peer.createDataChannel("oai-events", DataChannel.Init())
        dataChannel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                val state = dc.state()
                VcLog.d(TAG, "data channel oai-events: $state")
                if (state == DataChannel.State.OPEN) ready.complete(Unit)
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                onDataChannelMessage?.invoke(String(bytes, Charsets.UTF_8))
            }
        })
    }

    /** Creates the offer and waits for ICE gathering to finish (non-trickle). Returns the full SDP. */
    suspend fun createOffer(): String {
        val peer = pc ?: throw LiveException("peer not created")
        val constraints = MediaConstraints().apply {
            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")
            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false")
        }
        val offer = suspendCancellableCoroutine<SessionDescription> { cont ->
            peer.createOffer(object : SdpAdapter() {
                override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
                override fun onCreateFailure(error: String?) =
                    cont.resumeWithException(LiveException("createOffer failed: $error"))
            }, constraints)
        }
        setDescription(local = true, offer)
        if (withTimeoutOrNull(NEGOTIATION_TIMEOUT_MS) { gathered.await() } == null) {
            VcLog.w(TAG, "ICE gathering did not complete in time; sending the candidates gathered so far")
        }
        val sdp = peer.localDescription?.description ?: throw LiveException("missing local offer")
        VcLog.d(TAG, "offer ready: ${sdp.length} bytes, ${sdp.lines().count { it.startsWith("a=candidate") }} candidates")
        return sdp
    }

    /** Applies the SDP answer and waits until the data channel opens or the peer connects. */
    suspend fun applyAnswer(sdp: String) {
        setDescription(local = false, SessionDescription(SessionDescription.Type.ANSWER, sdp))
        if (withTimeoutOrNull(NEGOTIATION_TIMEOUT_MS) { ready.await() } == null) {
            throw LiveException("timed out connecting audio to GPT-Live (ICE/DTLS)")
        }
    }

    private suspend fun setDescription(local: Boolean, sdp: SessionDescription) {
        val peer = pc ?: throw LiveException("peer not created")
        suspendCancellableCoroutine<Unit> { cont ->
            val obs = object : SdpAdapter() {
                override fun onSetSuccess() = cont.resume(Unit)
                override fun onSetFailure(error: String?) = cont.resumeWithException(
                    LiveException("set${if (local) "Local" else "Remote"}Description failed: $error"),
                )
            }
            if (local) peer.setLocalDescription(obs, sdp) else peer.setRemoteDescription(obs, sdp)
        }
    }

    fun sendDataChannel(text: String): Boolean {
        val dc = dataChannel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        return dc.send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)), false))
    }

    fun setMicEnabled(enabled: Boolean) {
        audioTrack?.setEnabled(enabled)
    }

    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        try {
            dataChannel?.unregisterObserver()
            dataChannel?.close()
            dataChannel?.dispose()
            pc?.close()
            pc?.dispose()
            audioTrack?.dispose()
            audioSource?.dispose()
            factory?.dispose()
            adm?.release()
        } catch (e: Exception) {
            VcLog.w(TAG, "WebRTC dispose: ${e.message}")
        }
        dataChannel = null; pc = null; audioTrack = null; audioSource = null; factory = null; adm = null
        VcLog.d(TAG, "WebRTC peer disposed")
    }

    private val observer = object : PeerConnection.Observer {
        private var connected = false
        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            VcLog.d(TAG, "ICE connection: $state")
        }
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            VcLog.i(TAG, "peer connection: $newState")
            _peerState.value = newState
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> { connected = true; ready.complete(Unit) }
                PeerConnection.PeerConnectionState.FAILED, PeerConnection.PeerConnectionState.CLOSED ->
                    if (connected && !disposed.get()) onPeerLost?.invoke("audio connection $newState".lowercase())
                else -> {}
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
        }
        override fun onIceCandidate(candidate: IceCandidate) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(dc: DataChannel) {
            // Only the locally created event channel is used.
            dc.close()
        }
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            VcLog.d(TAG, "remote track: ${receiver.track()?.kind()}")
        }
        override fun onTrack(transceiver: RtpTransceiver) {}
    }

    private open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    companion object {
        private const val TAG = "Live"
        private const val NEGOTIATION_TIMEOUT_MS = 20_000L

        /** Debug-only hook (set from src/debug): called on the capture thread when the mic starts. */
        @Volatile var debugMicStartedHook: ((JavaAudioDeviceModule) -> Unit)? = null
        @Volatile private var initialized = false

        fun ensureInitialized(context: Context) {
            if (initialized) return
            synchronized(this) {
                if (initialized) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .createInitializationOptions(),
                )
                initialized = true
            }
        }
    }
}
