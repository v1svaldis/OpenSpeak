package openspeak.app.stream

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.RtpTransceiver.RtpTransceiverDirection
import org.webrtc.RtpTransceiver.RtpTransceiverInit
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** A screen/camera stream announced on the server. */
data class TsStream(
    val id: String,
    val clientId: Int,
    val name: String,
    /** 1 = camera, 2 = screen, 3 = window (TeamSpeak's StreamType). */
    val type: Int,
    val bitrate: Int = 0,
    val audio: Boolean = false,
)

/** What the viewer screen shows. */
sealed interface WatchState {
    data object Idle : WatchState
    data class Connecting(val stream: TsStream, val step: String) : WatchState
    data class Playing(val stream: TsStream, val track: VideoTrack) : WatchState
    data class Ended(val stream: TsStream, val reason: String) : WatchState
}

/**
 * TeamSpeak 6 screen sharing. The server only relays signaling; video flows peer to peer over
 * WebRTC, one PeerConnection per viewer. Message flow, as the official 6.0 client does it:
 *
 *  viewer → `joinstreamrequest id clid=<streamer> is_remove=0 msg`
 *  streamer ← `notifyjoinstreamrequest clid=<viewer> id is_remove`
 *  streamer → `respondjoinstreamrequest id clid=<viewer> decision=1 offer=<raw SDP> msg`
 *  viewer ← `notifyrespondjoinstreamrequest clid id decision offer`
 *  both → `streamsignaling id clid=<peer> json=...` with json being
 *      `{"cmd":"answer","args":{"answer":sdp}}`, `{"cmd":"offer","args":{"offer":sdp}}` (renegotiation) or
 *      `{"cmd":"iceCandidate","args":{"sdp":"candidate:...","mid":"0","mLine":0}}`.
 */
class StreamManager(private val context: Context, private val signaling: Signaling) {
    interface Signaling {
        val ownClientId: Int
        fun command(name: String, vararg params: Pair<String, String>)
    }

    companion object {
        private const val TAG = "StreamManager"
        private val ICE_SERVERS = listOf(
            // The STUN servers the official client uses.
            PeerConnection.IceServer.builder("stun:turn.teamspeak.com:3478").createIceServer(),
            PeerConnection.IceServer.builder("stun:turn2.teamspeak.com:3478").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        )
    }

    private val _streams = MutableStateFlow<Map<String, TsStream>>(emptyMap())
    /** Streams announced on the server, by stream id. */
    val streams: StateFlow<Map<String, TsStream>> = _streams
    private val _watch = MutableStateFlow<WatchState>(WatchState.Idle)
    val watch: StateFlow<WatchState> = _watch
    private val _publishing = MutableStateFlow<String?>(null)
    /** Id of our own stream while we share the screen ("" while the server has not confirmed it yet). */
    val publishing: StateFlow<String?> = _publishing
    private val _viewers = MutableStateFlow<Set<Int>>(emptySet())
    /** Clients currently watching our stream. */
    val viewers: StateFlow<Set<Int>> = _viewers

    // libwebrtc objects are not thread safe in all places; serialize everything on one thread.
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "os-webrtc") }
    val eglBase: EglBase by lazy { EglBase.create() }
    private var factory: PeerConnectionFactory? = null

    private var watchPeer: Peer? = null
    private val publishPeers = ConcurrentHashMap<Int, Peer>()
    private var capturer: ScreenCapturerAndroid? = null
    private var captureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var repeater: FrameRepeater? = null

    private fun factory(): PeerConnectionFactory = factory ?: run {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        val adm = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()
        PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
            .also { factory = it }
    }

    /** One WebRTC connection to one remote client for one stream. */
    private inner class Peer(val streamId: String, val remoteClient: Int, val inbound: Boolean) : PeerConnection.Observer {
        val pc: PeerConnection
        private val pendingCandidates = ArrayList<IceCandidate>()
        private var remoteSet = false

        init {
            val config = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            }
            pc = factory().createPeerConnection(config, this)!!
        }

        fun sendSignal(cmd: String, args: JSONObject) {
            val json = JSONObject().put("args", args).put("cmd", cmd).toString()
            signaling.command("streamsignaling", "id" to streamId, "clid" to remoteClient.toString(), "json" to json)
        }

        /** Viewer side: apply the streamer's offer and answer it. */
        fun handleOffer(rawSdp: String, viaSignaling: Boolean) {
            val sdp = normalizeSdp(rawSdp)
            pc.setRemoteDescription(object : SimpleSdp("setRemote(offer)") {
                override fun onSetSuccess() = executor.execute {
                    remoteSet = true
                    flushCandidates()
                    pc.createAnswer(object : SimpleSdp("createAnswer") {
                        override fun onCreateSuccess(desc: SessionDescription) = executor.execute {
                            pc.setLocalDescription(SimpleSdp("setLocal(answer)"), desc)
                            sendSignal("answer", JSONObject().put("answer", desc.description))
                            if (!viaSignaling) setWatchStep("Подключение видео…")
                        }
                    }, MediaConstraints())
                }
            }, SessionDescription(SessionDescription.Type.OFFER, sdp))
        }

        /** Streamer side: create the offer that goes into respondjoinstreamrequest. */
        fun createOffer(onOffer: (String) -> Unit) {
            pc.createOffer(object : SimpleSdp("createOffer") {
                override fun onCreateSuccess(desc: SessionDescription) = executor.execute {
                    pc.setLocalDescription(SimpleSdp("setLocal(offer)"), desc)
                    onOffer(desc.description)
                }
            }, MediaConstraints())
        }

        fun handleAnswer(rawSdp: String) {
            val sdp = normalizeSdp(rawSdp)
            pc.setRemoteDescription(object : SimpleSdp("setRemote(answer)") {
                override fun onSetSuccess() = executor.execute {
                    remoteSet = true
                    flushCandidates()
                }
            }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
        }

        fun addRemoteCandidate(c: IceCandidate) {
            if (remoteSet) pc.addIceCandidate(c) else pendingCandidates += c
        }

        private fun flushCandidates() {
            pendingCandidates.forEach { pc.addIceCandidate(it) }
            pendingCandidates.clear()
        }

        fun close() {
            runCatching { pc.dispose() }
        }

        override fun onIceCandidate(c: IceCandidate) = executor.execute {
            sendSignal("iceCandidate", JSONObject().put("sdp", c.sdp).put("mid", c.sdpMid).put("mLine", c.sdpMLineIndex))
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            Log.i(TAG, "peer $remoteClient stream $streamId: $state")
            if (inbound && state == PeerConnection.PeerConnectionState.FAILED) executor.execute {
                val w = _watch.value
                if (w is WatchState.Connecting || w is WatchState.Playing)
                    endWatch("Не удалось установить соединение с транслирующим")
            }
        }

        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            val track = receiver.track()
            if (inbound && track is VideoTrack) executor.execute {
                val s = currentWatchStream() ?: return@execute
                _watch.value = WatchState.Playing(s, track)
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: org.webrtc.DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onTrack(transceiver: RtpTransceiver) {}
    }

    /** libwebrtc only parses SDP with CRLF line ends, including after the last line. */
    private fun normalizeSdp(sdp: String): String =
        sdp.trim().split("\r\n", "\n").joinToString("\r\n", postfix = "\r\n")

    private open class SimpleSdp(val what: String) : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) = Log.w(TAG, "$what failed: $error").let {}
        override fun onSetFailure(error: String?) = Log.w(TAG, "$what failed: $error").let {}
    }

    private fun currentWatchStream(): TsStream? = when (val w = _watch.value) {
        is WatchState.Connecting -> w.stream
        is WatchState.Playing -> w.stream
        else -> null
    }

    private fun setWatchStep(step: String) {
        val w = _watch.value
        if (w is WatchState.Connecting) _watch.value = w.copy(step = step)
    }

    // ---------------------------------------------------------------- watching

    fun watch(stream: TsStream) = executor.execute {
        stopWatchInternal(sendLeave = true)
        _watch.value = WatchState.Connecting(stream, "Запрос на просмотр…")
        signaling.command(
            "joinstreamrequest", "id" to stream.id, "clid" to stream.clientId.toString(),
            "is_remove" to "0", "msg" to "",
        )
    }

    fun stopWatching() = executor.execute {
        stopWatchInternal(sendLeave = true)
        _watch.value = WatchState.Idle
    }

    private fun stopWatchInternal(sendLeave: Boolean) {
        val s = currentWatchStream()
        watchPeer?.close()
        watchPeer = null
        if (sendLeave && s != null && _streams.value.containsKey(s.id))
            signaling.command(
                "joinstreamrequest", "id" to s.id, "clid" to s.clientId.toString(),
                "is_remove" to "1", "msg" to "",
            )
    }

    private fun endWatch(reason: String) {
        val s = currentWatchStream() ?: return
        stopWatchInternal(sendLeave = false)
        _watch.value = WatchState.Ended(s, reason)
    }

    // ---------------------------------------------------------------- publishing

    /** Starts sharing the screen. [projectionData] is the result of the MediaProjection consent dialog. */
    fun startScreenShare(projectionData: Intent, name: String, width: Int, height: Int, fps: Int) = executor.execute {
        if (_publishing.value != null) return@execute
        val cap = ScreenCapturerAndroid(projectionData, object : MediaProjection.Callback() {
            override fun onStop() {
                executor.execute { if (_publishing.value != null) stopScreenShareInternal() }
            }
        })
        val helper = SurfaceTextureHelper.create("ts-screen", eglBase.eglBaseContext)
        val source = factory().createVideoSource(true)
        val rep = FrameRepeater(source.capturerObserver)
        repeater = rep
        cap.initialize(helper, context, rep)
        cap.startCapture(width, height, fps)
        capturer = cap
        captureHelper = helper
        videoSource = source
        videoTrack = factory().createVideoTrack("screen", source)
        _publishing.value = ""
        signaling.command(
            "setupstream", "name" to name, "type" to "2", "accessibility" to "1", "mode" to "1",
            "bitrate" to "4000", "viewer_limit" to "0", "audio" to "0",
        )
    }

    fun stopScreenShare() = executor.execute { stopScreenShareInternal() }

    private fun stopScreenShareInternal() {
        val id = _publishing.value
        if (!id.isNullOrEmpty()) signaling.command("stopstream", "id" to id, "reason" to "1")
        publishPeers.values.forEach { it.close() }
        publishPeers.clear()
        _viewers.value = emptySet()
        runCatching { capturer?.stopCapture() }
        capturer?.dispose()
        videoTrack?.dispose()
        videoSource?.dispose()
        captureHelper?.dispose()
        repeater?.dispose()
        repeater = null
        capturer = null
        videoTrack = null
        videoSource = null
        captureHelper = null
        _publishing.value = null
    }

    private fun acceptViewer(streamId: String, viewer: Int) {
        publishPeers.remove(viewer)?.close()
        val track = videoTrack ?: return
        val peer = Peer(streamId, viewer, inbound = false)
        publishPeers[viewer] = peer
        peer.pc.addTransceiver(track, RtpTransceiverInit(RtpTransceiverDirection.SEND_ONLY, listOf("stream-p2p-av")))
        // The official client always negotiates an audio m-line as well; keep the same shape.
        peer.pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, RtpTransceiverInit(RtpTransceiverDirection.SEND_ONLY))
        peer.createOffer { sdp ->
            signaling.command(
                "respondjoinstreamrequest", "id" to streamId, "clid" to viewer.toString(),
                "decision" to "1", "offer" to sdp, "msg" to "",
            )
        }
        _viewers.update { it + viewer }
    }

    private fun dropViewer(viewer: Int) {
        publishPeers.remove(viewer)?.close()
        _viewers.update { it - viewer }
    }

    // ---------------------------------------------------------------- server notifications

    /** Feeds a notify* command from the server; returns true when it was a stream notification. */
    fun onNotify(name: String, m: Map<String, String>): Boolean {
        if (!name.startsWith("notifystream") && name != "notifyjoinstreamrequest" && name != "notifyrespondjoinstreamrequest")
            return false
        executor.execute { handleNotify(name, m) }
        return true
    }

    private fun handleNotify(name: String, m: Map<String, String>) {
        Log.d(TAG, "<- $name id=${m["id"]} clid=${m["clid"]}")
        val id = m["id"] ?: return
        val clid = m["clid"]?.toIntOrNull() ?: return
        when (name) {
            "notifystreamstarted", "notifystreaminfo" -> {
                val s = TsStream(
                    id = id, clientId = clid, name = m["name"].orEmpty(), type = m["type"]?.toIntOrNull() ?: 2,
                    bitrate = m["bitrate"]?.toIntOrNull() ?: 0, audio = m["audio"] == "1",
                )
                _streams.update { it + (id to s) }
                if (clid == signaling.ownClientId && _publishing.value == "") _publishing.value = id
            }
            "notifystreamupdated" -> _streams.update { map ->
                val old = map[id] ?: return@update map
                map + (id to old.copy(name = m["name"] ?: old.name, type = m["type"]?.toIntOrNull() ?: old.type))
            }
            "notifystreamstopped" -> {
                _streams.update { it - id }
                if (currentWatchStream()?.id == id) endWatch("Трансляция завершена")
            }
            "notifyrespondjoinstreamrequest" -> {
                val s = currentWatchStream() ?: return
                if (s.id != id) return
                val offer = m["offer"].orEmpty()
                if (m["decision"] != "1" || offer.isBlank()) {
                    endWatch("Транслирующий отклонил запрос")
                    return
                }
                watchPeer?.close()
                watchPeer = Peer(id, clid, inbound = true).also { it.handleOffer(offer, false) }
            }
            "notifyjoinstreamrequest" -> {
                if (id != _publishing.value) return
                if (m["is_remove"] == "1") dropViewer(clid) else acceptViewer(id, clid)
            }
            "notifystreamclientleft" -> if (id == _publishing.value) dropViewer(clid)
            "notifystreamsignaling" -> {
                val json = runCatching { JSONObject(m["json"].orEmpty()) }.getOrNull() ?: return
                val peer = if (watchPeer?.streamId == id && watchPeer?.remoteClient == clid) watchPeer
                else publishPeers[clid]?.takeIf { it.streamId == id }
                peer ?: return
                val args = json.optJSONObject("args") ?: JSONObject()
                when (json.optString("cmd")) {
                    "iceCandidate" -> peer.addRemoteCandidate(
                        IceCandidate(args.optString("mid"), args.optInt("mLine"), args.optString("sdp"))
                    )
                    "answer" -> peer.handleAnswer(args.optString("answer").ifEmpty { args.optString("sdp") })
                    "offer", "reconnectOffer" -> peer.handleOffer(args.optString("offer").ifEmpty { args.optString("sdp") }, true)
                }
            }
        }
    }

    /** A client left our view: forget its streams and connections. */
    fun onClientLeft(clientId: Int) = executor.execute {
        _streams.update { map -> map.filterValues { it.clientId != clientId } }
        if (currentWatchStream()?.clientId == clientId) endWatch("Транслирующий покинул сервер")
        dropViewer(clientId)
    }

    /** Disconnected from the server: tear everything down. */
    fun reset() = executor.execute {
        stopWatchInternal(sendLeave = false)
        _watch.value = WatchState.Idle
        if (_publishing.value != null) stopScreenShareInternal()
        _streams.value = emptyMap()
    }
}
